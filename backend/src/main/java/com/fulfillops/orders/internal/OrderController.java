package com.fulfillops.orders.internal;

import com.fulfillops.inventory.MovementView;
import com.fulfillops.orders.OrderCommand;
import com.fulfillops.orders.OrderService;
import com.fulfillops.orders.OrderStatus;
import com.fulfillops.orders.OrderSummary;
import com.fulfillops.orders.OrderView;
import com.fulfillops.shared.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
class OrderController {

    private final OrderService orders;

    OrderController(OrderService orders) {
        this.orders = orders;
    }

    @GetMapping
    PageResponse<OrderSummary> list(@RequestParam(required = false) String q,
                                    @RequestParam(required = false) Set<OrderStatus> status,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "25") int size,
                                    @RequestParam(defaultValue = "createdAt") String sort,
                                    @RequestParam(defaultValue = "desc") String direction) {
        return orders.list(q, status, page, size, sort, direction);
    }

    @GetMapping("/counts")
    Map<OrderStatus, Long> counts() {
        return orders.countsByStatus();
    }

    @GetMapping("/{id}")
    OrderView get(@PathVariable long id) {
        return orders.get(id);
    }

    @GetMapping("/by-number/{orderNumber}")
    OrderView getByNumber(@PathVariable String orderNumber) {
        return orders.getByNumber(orderNumber);
    }

    @GetMapping("/{id}/movements")
    List<MovementView> movements(@PathVariable long id) {
        return orders.stockMovements(id);
    }

    /**
     * Create (and optionally submit) an order. Send a fresh {@code Idempotency-Key} per logical order and
     * reuse it on every retry: the first request answers 201, and replays answer 200 with
     * {@code Idempotent-Replayed: true} and the same order.
     */
    @PostMapping
    ResponseEntity<OrderView> create(@Valid @RequestBody OrderCommand cmd,
                                     @RequestParam(defaultValue = "false") boolean submit,
                                     @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        OrderService.CreateResult result = orders.create(cmd, submit, key);
        OrderView view = result.order();
        if (result.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(view);
        }
        return ResponseEntity.created(URI.create("/api/orders/" + view.id())).body(view);
    }

    @PutMapping("/{id}")
    OrderView update(@PathVariable long id, @RequestParam long version, @Valid @RequestBody OrderCommand cmd) {
        return orders.updateDraft(id, version, cmd);
    }

    @PostMapping("/{id}/submit")
    OrderView submit(@PathVariable long id) {
        return orders.submit(id);
    }

    @PostMapping("/{id}/cancel")
    OrderView cancel(@PathVariable long id, @Valid @RequestBody(required = false) CancelRequest body) {
        return orders.cancel(id, body == null ? null : body.reason());
    }

    @PostMapping("/{id}/retry-reservation")
    OrderView retry(@PathVariable long id) {
        return orders.retryReservation(id);
    }

    record CancelRequest(@Size(max = 500) String reason) {
    }
}
