package com.fulfillops.orders.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fulfillops.inventory.StockLine;
import com.fulfillops.orders.OrderCommand;
import com.fulfillops.orders.OrderStatus;
import com.fulfillops.orders.OrderSummary;
import com.fulfillops.orders.OrderView;
import com.fulfillops.shared.jobs.Outbox;
import com.fulfillops.shared.web.DomainException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Internal bridge between {@link com.fulfillops.orders.OrderService} (the module API) and the
 * aggregate, whose mutators stay package-private so nothing outside this package can bypass the
 * state machine.
 */
@Component
public class OrderFacts {

    public record LineDraft(long productId, String sku, String productName, int quantity, BigDecimal unitPrice) {
    }

    private final OrderRepository orders;

    OrderFacts(OrderRepository orders) {
        this.orders = orders;
    }

    /** Runs inside the create transaction; returns the new order id. */
    public long insert(OrderCommand cmd, List<LineDraft> lines, boolean submit, String actor, String key,
                       String fingerprint, Outbox outbox) {
        Order order = Order.draft(actor, key, fingerprint);
        order.setCustomer(cmd.customerName(), cmd.customerEmail(), cmd.shippingAddress(), cmd.notes());
        order.replaceLines(lines);
        if (submit) {
            order.transitionTo(OrderStatus.SUBMITTED, actor, "Submitted");
        }
        order = orders.saveAndFlush(order);
        if (submit) {
            outbox.enqueue(ReservationService.JOB_TYPE, String.valueOf(order.getId()), Map.of());
        }
        return order.getId();
    }

    public Order lock(long id) {
        return orders.findByIdForUpdate(id).orElseThrow(() -> DomainException.notFound("Order", id));
    }

    public void edit(Order order, OrderCommand cmd, List<LineDraft> lines) {
        order.setCustomer(cmd.customerName(), cmd.customerEmail(), cmd.shippingAddress(), cmd.notes());
        order.replaceLines(lines);
    }

    public void submit(Order order, String actor, Outbox outbox) {
        order.transitionTo(OrderStatus.SUBMITTED, actor, "Submitted");
        orders.flush();
        outbox.enqueue(ReservationService.JOB_TYPE, String.valueOf(order.getId()), Map.of());
    }

    public void cancel(Order order, String actor, String reason) {
        order.cancel(actor, reason);
    }

    public void transition(Order order, OrderStatus next, String actor, String note) {
        order.transitionTo(next, actor, note);
    }

    public List<StockLine> stockLines(Order order) {
        return order.stockLines();
    }

    public OrderView view(Order order) {
        return order.toView();
    }

    public OrderSummary summary(Order order) {
        return order.toSummary();
    }

    public String fingerprint(ObjectMapper json, OrderCommand cmd, boolean submit) {
        return Fingerprints.of(json, cmd, submit);
    }
}
