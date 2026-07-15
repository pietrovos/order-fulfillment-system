package com.fulfillops.fulfillment.internal;

import com.fulfillops.inventory.InventoryService;
import com.fulfillops.inventory.StockLevelView;
import com.fulfillops.orders.OrderService;
import com.fulfillops.orders.OrderStatus;
import com.fulfillops.orders.OrderSummary;
import com.fulfillops.orders.OrderView;
import com.fulfillops.shared.jobs.Outbox;
import com.fulfillops.shared.security.CurrentActor;
import com.fulfillops.shared.web.DomainException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Warehouse workflow. Each state-changing method runs in one transaction that covers both the
 * fulfillment rows and the order transition (orders API, Propagation.MANDATORY), so an order is never
 * PACKED without a shipment, and never PICKING without a pick list.
 */
@Service
class FulfillmentService {

    static final String CARRIER = "SIMSHIP";
    static final String CREATE_SHIPMENT = "CREATE_SHIPMENT";

    private final PickListRepository pickLists;
    private final ShipmentRepository shipments;
    private final OrderService orders;
    private final InventoryService inventory;
    private final Outbox outbox;

    FulfillmentService(PickListRepository pickLists, ShipmentRepository shipments, OrderService orders,
                       InventoryService inventory, Outbox outbox) {
        this.pickLists = pickLists;
        this.shipments = shipments;
        this.orders = orders;
        this.inventory = inventory;
        this.outbox = outbox;
    }

    // ------------------------------------------------------------------ picking

    @PreAuthorize("hasAnyRole('WAREHOUSE','SUPERVISOR')")
    @Transactional(readOnly = true)
    public List<OrderSummary> pickQueue() {
        return orders.findByStatus(OrderStatus.RESERVED, 200);
    }

    @PreAuthorize("hasAnyRole('WAREHOUSE','SUPERVISOR')")
    @Transactional(readOnly = true)
    public List<PickListView> openPickLists() {
        return views(pickLists.findByStatusOrderByStartedAt(PickList.Status.OPEN), Set.of(OrderStatus.PICKING));
    }

    /** Packing queue: picks are complete and the order is still waiting to be packed. */
    @PreAuthorize("hasAnyRole('WAREHOUSE','SUPERVISOR')")
    @Transactional(readOnly = true)
    public List<PickListView> packQueue() {
        return views(pickLists.findByStatusOrderByStartedAt(PickList.Status.COMPLETED), Set.of(OrderStatus.PICKING));
    }

    @PreAuthorize("hasAnyRole('WAREHOUSE','SUPERVISOR')")
    @Transactional(readOnly = true)
    public PickListView pickList(long id) {
        PickList list = find(id);
        return list.toView(orders.getInternal(list.getOrderId()).status().name());
    }

    /**
     * Claims a RESERVED order for picking. The order row lock plus the state machine make this safe when
     * two pickers press "start" together: the second sees PICKING and gets 409 INVALID_TRANSITION.
     */
    @PreAuthorize("hasAnyRole('WAREHOUSE','SUPERVISOR')")
    @Transactional
    public PickListView startPicking(long orderId) {
        String actor = CurrentActor.username();
        OrderView order = orders.transitionForFulfillment(orderId, OrderStatus.PICKING, "Picking started");
        PickList list = pickLists.saveAndFlush(new PickList(order, actor));
        return list.toView(order.status().name());
    }

    @PreAuthorize("hasAnyRole('WAREHOUSE','SUPERVISOR')")
    @Transactional
    public PickListView confirmLine(long pickListId, int lineNo, boolean picked) {
        PickList list = find(pickListId);
        requireOrderStatus(list, OrderStatus.PICKING);
        list.confirm(lineNo, picked, CurrentActor.username());
        pickLists.flush();
        return list.toView(OrderStatus.PICKING.name());
    }

    @PreAuthorize("hasAnyRole('WAREHOUSE','SUPERVISOR')")
    @Transactional
    public PickListView completePick(long pickListId) {
        PickList list = find(pickListId);
        requireOrderStatus(list, OrderStatus.PICKING);
        list.complete();
        pickLists.flush();
        return list.toView(OrderStatus.PICKING.name());
    }

    // ------------------------------------------------------------------ packing

    /**
     * Packs the order and requests a carrier booking: order -> PACKED, shipment row (PENDING, with a fixed
     * carrier idempotency key) and a CREATE_SHIPMENT outbox job, all in one transaction. The carrier call
     * itself happens later in the job, outside any transaction.
     */
    @PreAuthorize("hasAnyRole('WAREHOUSE','SUPERVISOR')")
    @Transactional
    public ShipmentView pack(long pickListId, int parcels, BigDecimal weightKg) {
        PickList list = find(pickListId);
        if (list.getStatus() != PickList.Status.COMPLETED) {
            throw new DomainException(HttpStatus.CONFLICT, "PICK_INCOMPLETE", "Finish picking before packing");
        }
        String actor = CurrentActor.username();
        OrderView order = orders.transitionForFulfillment(list.getOrderId(), OrderStatus.PACKED,
                parcels + " parcel(s), " + weightKg.stripTrailingZeros().toPlainString() + " kg");
        Shipment shipment = new Shipment(order.id(), order.orderNumber(), CARRIER, parcels, weightKg,
                order.customerName(), order.shippingAddress(), actor);
        shipment.record("PACKED", parcels + " parcel(s), " + weightKg.stripTrailingZeros().toPlainString() + " kg", actor);
        shipment.record("BOOKING_REQUESTED", "Queued for " + CARRIER + " (key " + shipment.getCarrierIdempotencyKey() + ")",
                actor);
        shipments.saveAndFlush(shipment);
        outbox.enqueue(CREATE_SHIPMENT, shipment.getId().toString(), Map.of("orderId", order.id()));
        return shipment.toView();
    }

    // ------------------------------------------------------------------ supervisor queue

    record ExceptionLine(String sku, String productName, int ordered, int available, int shortBy) {
    }

    record ExceptionView(long orderId, String orderNumber, String customerName, String reason, Instant since,
                         List<ExceptionLine> lines, boolean fulfillableNow) {
    }

    @PreAuthorize("hasRole('SUPERVISOR')")
    @Transactional(readOnly = true)
    public List<ExceptionView> exceptionQueue() {
        Map<Long, StockLevelView> stock = inventory.stockLevels(null).stream()
                .collect(Collectors.toMap(StockLevelView::productId, Function.identity()));
        return orders.findByStatus(OrderStatus.STOCK_EXCEPTION, 200).stream().map(summary -> {
            OrderView o = orders.getInternal(summary.id());
            List<ExceptionLine> lines = o.lines().stream().map(l -> {
                int available = stock.containsKey(l.productId()) ? stock.get(l.productId()).available() : 0;
                return new ExceptionLine(l.sku(), l.productName(), l.quantity(), available,
                        Math.max(0, l.quantity() - available));
            }).toList();
            Instant since = o.history().isEmpty() ? o.updatedAt() : o.history().get(o.history().size() - 1).at();
            return new ExceptionView(o.id(), o.orderNumber(), o.customerName(), o.exceptionReason(), since, lines,
                    lines.stream().allMatch(l -> l.shortBy() == 0));
        }).toList();
    }

    // ------------------------------------------------------------------ shipments

    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public List<ShipmentView> shipments(String status, String q) {
        ShipmentStatus st = status == null || status.isBlank() ? null : ShipmentStatus.valueOf(status.toUpperCase());
        return shipments.search(st, q == null ? "" : q.trim(), PageRequest.of(0, 200)).stream()
                .map(Shipment::toView).toList();
    }

    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public ShipmentView shipment(UUID id) {
        return shipments.findById(id).orElseThrow(() -> DomainException.notFound("Shipment", id)).toView();
    }

    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public ShipmentView shipmentForOrder(long orderId) {
        return shipments.findByOrderId(orderId)
                .orElseThrow(() -> DomainException.notFound("Shipment for order", orderId)).toView();
    }

    /**
     * Re-queues a FAILED booking with the original idempotency key. If an earlier attempt succeeded
     * without returning a response, the carrier returns that booking.
     */
    @PreAuthorize("hasRole('SUPERVISOR')")
    @Transactional
    public ShipmentView retryBooking(UUID id) {
        Shipment sh = shipments.findByIdForUpdate(id).orElseThrow(() -> DomainException.notFound("Shipment", id));
        if (sh.getStatus() != ShipmentStatus.FAILED) {
            throw new DomainException(HttpStatus.CONFLICT, "SHIPMENT_NOT_FAILED",
                    "Only failed bookings can be retried; this one is " + sh.getStatus());
        }
        sh.retry();
        sh.record("BOOKING_RETRY_REQUESTED", "Requeued by supervisor (same idempotency key)", CurrentActor.username());
        shipments.flush();
        outbox.enqueue(CREATE_SHIPMENT, sh.getId().toString(), Map.of("orderId", sh.getOrderId()));
        return sh.toView();
    }

    // ------------------------------------------------------------------ helpers

    private PickList find(long id) {
        return pickLists.findById(id).orElseThrow(() -> DomainException.notFound("Pick list", id));
    }

    private void requireOrderStatus(PickList list, OrderStatus expected) {
        OrderStatus actual = orders.getInternal(list.getOrderId()).status();
        if (actual != expected) {
            throw new DomainException(HttpStatus.CONFLICT, "ORDER_NOT_PICKABLE",
                    list.getOrderNumber() + " is " + actual + "; it can no longer be picked");
        }
    }

    private List<PickListView> views(List<PickList> lists, Set<OrderStatus> orderStatuses) {
        Map<Long, OrderStatus> status = lists.stream().collect(Collectors.toMap(PickList::getOrderId,
                l -> orders.getInternal(l.getOrderId()).status()));
        return lists.stream().filter(l -> orderStatuses.contains(status.get(l.getOrderId())))
                .map(l -> l.toView(status.get(l.getOrderId()).name())).toList();
    }
}
