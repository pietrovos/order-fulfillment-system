package com.fulfillops.orders.internal;

import com.fulfillops.inventory.InsufficientStockException;
import com.fulfillops.inventory.InventoryService;
import com.fulfillops.orders.OrderStatus;
import com.fulfillops.shared.jobs.Outbox;
import com.fulfillops.shared.security.CurrentActor;
import com.fulfillops.shared.web.DomainException;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns a SUBMITTED (or STOCK_EXCEPTION, on retry) order into RESERVED or STOCK_EXCEPTION.
 *
 * <p>Attempt transaction: lock the order row, re-check its status (so a duplicate call is a no-op),
 * reserve every line via conditional UPDATEs, transition, mark the outbox job done. If any line is short,
 * the inventory call throws and the whole transaction rolls back (no partial reservation survives). A second,
 * separate transaction then records STOCK_EXCEPTION with the reason.
 */
@Service
public class ReservationService {

    public static final String JOB_TYPE = "RESERVE_ORDER";

    public enum Outcome { RESERVED, STOCK_EXCEPTION, NOT_ELIGIBLE }

    private final OrderRepository orders;
    private final InventoryService inventory;
    private final Outbox outbox;
    private final TransactionTemplate tx;

    ReservationService(OrderRepository orders, InventoryService inventory, Outbox outbox, TransactionTemplate tx) {
        this.orders = orders;
        this.inventory = inventory;
        this.outbox = outbox;
        this.tx = tx;
    }

    public Outcome reserve(long orderId, Set<OrderStatus> eligible) {
        String actor = CurrentActor.username();
        try {
            return tx.execute(s -> {
                Order order = lock(orderId);
                if (!eligible.contains(order.getStatus())) {
                    outbox.completeInline(JOB_TYPE, String.valueOf(orderId));
                    return Outcome.NOT_ELIGIBLE;
                }
                inventory.reserve(order.getOrderNumber(), order.stockLines());
                order.transitionTo(OrderStatus.RESERVED, actor, "Stock reserved");
                outbox.completeInline(JOB_TYPE, String.valueOf(orderId));
                return Outcome.RESERVED;
            });
        } catch (InsufficientStockException shortage) {
            return tx.execute(s -> {
                Order order = lock(orderId);
                if (!eligible.contains(order.getStatus())) {
                    // Someone else (cancel, a concurrent retry) got there first; nothing to record.
                    outbox.completeInline(JOB_TYPE, String.valueOf(orderId));
                    return Outcome.NOT_ELIGIBLE;
                }
                order.markStockException(actor, describe(order, shortage));
                outbox.completeInline(JOB_TYPE, String.valueOf(orderId));
                return Outcome.STOCK_EXCEPTION;
            });
        }
    }

    private Order lock(long orderId) {
        return orders.findByIdForUpdate(orderId).orElseThrow(() -> DomainException.notFound("Order", orderId));
    }

    private static String describe(Order order, InsufficientStockException e) {
        String sku = order.getLines().stream().filter(l -> l.getProductId() == e.productId())
                .map(OrderLine::getSku).findFirst().orElse("product " + e.productId());
        return "Insufficient stock for " + sku + ": ordered " + e.requested() + ", available " + e.available();
    }
}
