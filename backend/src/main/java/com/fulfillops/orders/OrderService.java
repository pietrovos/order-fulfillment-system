package com.fulfillops.orders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fulfillops.catalog.CatalogService;
import com.fulfillops.catalog.ProductSummary;
import com.fulfillops.inventory.InventoryService;
import com.fulfillops.inventory.MovementView;
import com.fulfillops.orders.internal.OrderFacts;
import com.fulfillops.orders.internal.OrderRepository;
import com.fulfillops.orders.internal.ReservationService;
import com.fulfillops.shared.jobs.Outbox;
import com.fulfillops.shared.security.CurrentActor;
import com.fulfillops.shared.web.DomainException;
import com.fulfillops.shared.web.PageResponse;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Orders API for the web layer and for the fulfillment module. */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");
    private static final Set<String> SORTABLE = Set.of("orderNumber", "createdAt", "updatedAt", "customerName",
            "status", "totalAmount");

    private final OrderRepository orders;
    private final OrderFacts facts;
    private final CatalogService catalog;
    private final InventoryService inventory;
    private final ReservationService reservations;
    private final Outbox outbox;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;
    private final ObjectMapper json;

    OrderService(OrderRepository orders, OrderFacts facts, CatalogService catalog, InventoryService inventory,
                 ReservationService reservations, Outbox outbox, TransactionTemplate tx, ObjectMapper json) {
        this.orders = orders;
        this.facts = facts;
        this.catalog = catalog;
        this.inventory = inventory;
        this.reservations = reservations;
        this.outbox = outbox;
        this.tx = tx;
        this.readTx = new TransactionTemplate(tx.getTransactionManager());
        this.readTx.setReadOnly(true);
        this.json = json;
    }

    public record CreateResult(OrderView order, boolean replayed) {
    }

    // ---------------------------------------------------------------- queries

    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public PageResponse<OrderSummary> list(String q, Set<OrderStatus> statuses, int page, int size, String sort,
                                           String direction) {
        String field = SORTABLE.contains(sort) ? sort : "createdAt";
        Sort.Direction dir = "asc".equalsIgnoreCase(direction) ? Sort.Direction.ASC : Sort.Direction.DESC;
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(dir, field).and(Sort.by(Sort.Direction.DESC, "id")));
        Set<OrderStatus> st = statuses == null ? Set.of() : statuses;
        var result = orders.search(q == null ? "" : q.trim(), st.isEmpty() ? EnumSet.allOf(OrderStatus.class) : st,
                st.isEmpty(), pageable);
        return PageResponse.of(result.map(facts::summary));
    }

    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public OrderView get(long id) {
        return facts.view(orders.findById(id).orElseThrow(() -> DomainException.notFound("Order", id)));
    }

    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public OrderView getByNumber(String orderNumber) {
        return facts.view(orders.findByOrderNumber(orderNumber)
                .orElseThrow(() -> DomainException.notFound("Order", orderNumber)));
    }

    @PreAuthorize("isAuthenticated()")
    @Transactional(readOnly = true)
    public Map<OrderStatus, Long> countsByStatus() {
        Map<OrderStatus, Long> counts = new java.util.EnumMap<>(OrderStatus.class);
        for (OrderStatus s : OrderStatus.values()) {
            counts.put(s, 0L);
        }
        orders.countByStatus().forEach(c -> counts.put(c.getStatus(), c.getTotal()));
        return counts;
    }

    @PreAuthorize("isAuthenticated()")
    public List<MovementView> stockMovements(long id) {
        return inventory.movementsForOrder(getInternal(id).orderNumber());
    }

    // ---------------------------------------------------------------- sales actions

    /**
     * Creates an order, optionally submitting it, at most once per idempotency key.
     *
     * <ul>
     *   <li>Same key, same payload: returns the existing order ({@code replayed = true}), whatever its status.</li>
     *   <li>Same key, different payload: returns 422 for conflicting use of the key.</li>
     *   <li>Two identical requests racing: both try to insert; the unique index on {@code idempotency_key}
     *       makes the second block until the first commits and then fail. We catch that and return the winner.</li>
     * </ul>
     * Reservation runs after the create transaction commits. If it is interrupted, the RESERVE_ORDER outbox
     * row written with the order guarantees it still happens.
     */
    @PreAuthorize("hasAnyRole('SALES','SUPERVISOR')")
    public CreateResult create(OrderCommand cmd, boolean submit, String idempotencyKey) {
        String key = normaliseKey(idempotencyKey);
        String fingerprint = key == null ? null : facts.fingerprint(json, cmd, submit);
        if (key != null) {
            Optional<CreateResult> replay = replay(key, fingerprint);
            if (replay.isPresent()) {
                return replay.get();
            }
        }
        List<OrderFacts.LineDraft> lines = resolveLines(cmd);
        String actor = CurrentActor.username();
        long id;
        try {
            id = tx.execute(s -> facts.insert(cmd, lines, submit, actor, key, fingerprint, outbox));
        } catch (DataIntegrityViolationException e) {
            if (key != null) {
                Optional<CreateResult> replay = replay(key, fingerprint);
                if (replay.isPresent()) {
                    return replay.get();
                }
            }
            throw e;
        }
        if (submit) {
            reserveAfterCommit(id);
        }
        return new CreateResult(getInternal(id), false);
    }

    @PreAuthorize("hasAnyRole('SALES','SUPERVISOR')")
    @Transactional
    public OrderView updateDraft(long id, long expectedVersion, OrderCommand cmd) {
        var order = facts.lock(id);
        if (order.getVersion() != expectedVersion) {
            throw new DomainException(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                    "Order was changed by someone else. Reload and try again.");
        }
        facts.edit(order, cmd, resolveLines(cmd));
        orders.flush();
        return facts.view(order);
    }

    @PreAuthorize("hasAnyRole('SALES','SUPERVISOR')")
    public OrderView submit(long id) {
        String actor = CurrentActor.username();
        tx.executeWithoutResult(s -> {
            var order = facts.lock(id);
            facts.submit(order, actor, outbox);
        });
        reserveAfterCommit(id);
        return getInternal(id);
    }

    /**
     * Cancels and, if the order is holding stock, releases it in the same transaction. Sales can cancel
     * up to RESERVED; once the warehouse has started picking only a supervisor can.
     */
    @PreAuthorize("hasAnyRole('SALES','SUPERVISOR')")
    @Transactional
    public OrderView cancel(long id, String reason) {
        var order = facts.lock(id);
        if (order.getStatus() == OrderStatus.PICKING && !hasRole("SUPERVISOR")) {
            throw new DomainException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "Order is being picked; ask a supervisor to cancel it");
        }
        boolean release = order.getStatus().holdsReservation();
        String why = reason == null || reason.isBlank() ? "Cancelled" : reason.trim();
        facts.cancel(order, CurrentActor.username(), why);
        if (release) {
            inventory.release(order.getOrderNumber(), facts.stockLines(order), "Order cancelled: " + why);
        }
        orders.flush();
        return facts.view(order);
    }

    // ---------------------------------------------------------------- supervisor actions

    @PreAuthorize("hasRole('SUPERVISOR')")
    public OrderView retryReservation(long id) {
        var outcome = reservations.reserve(id, EnumSet.of(OrderStatus.STOCK_EXCEPTION));
        OrderView view = getInternal(id);
        if (outcome == ReservationService.Outcome.NOT_ELIGIBLE) {
            throw new InvalidTransitionException(view.orderNumber(), view.status(), OrderStatus.RESERVED);
        }
        return view;
    }

    // ---------------------------------------------------------------- fulfillment API (no role checks:
    // the fulfillment module authorizes its own callers and invokes these inside its transaction)

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public OrderView transitionForFulfillment(long id, OrderStatus next, String note) {
        if (next != OrderStatus.PICKING && next != OrderStatus.PACKED && next != OrderStatus.SHIPPED) {
            throw new IllegalArgumentException("Fulfillment cannot set " + next);
        }
        var order = facts.lock(id);
        facts.transition(order, next, CurrentActor.username(), note);
        if (next == OrderStatus.SHIPPED) {
            inventory.ship(order.getOrderNumber(), facts.stockLines(order));
        }
        orders.flush();
        return facts.view(order);
    }

    /** Unsecured read for internal callers; also used after this class's own write transactions commit. */
    public OrderView getInternal(long id) {
        return readTx.execute(s -> facts.view(orders.findById(id)
                .orElseThrow(() -> DomainException.notFound("Order", id))));
    }

    @Transactional(readOnly = true)
    public List<OrderSummary> findByStatus(OrderStatus status, int limit) {
        return orders.search("", Set.of(status), false,
                        PageRequest.of(0, Math.min(limit, 200), Sort.by(Sort.Direction.ASC, "submittedAt", "id")))
                .map(facts::summary).getContent();
    }

    // ---------------------------------------------------------------- helpers

    private void reserveAfterCommit(long id) {
        try {
            reservations.reserve(id, EnumSet.of(OrderStatus.SUBMITTED));
        } catch (RuntimeException e) {
            // Not fatal: the RESERVE_ORDER outbox job committed with the order and will retry.
            log.warn("Inline reservation for order {} failed; the outbox job will retry", id, e);
        }
    }

    private Optional<CreateResult> replay(String key, String fingerprint) {
        return tx.execute(s -> orders.findByIdempotencyKey(key).map(existing -> {
            if (!existing.getRequestFingerprint().equals(fingerprint)) {
                throw new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED",
                        "This Idempotency-Key was already used for a different order");
            }
            return new CreateResult(facts.view(existing), true);
        }));
    }

    private List<OrderFacts.LineDraft> resolveLines(OrderCommand cmd) {
        Set<Long> seen = new HashSet<>();
        for (OrderCommand.Line l : cmd.lines()) {
            if (!seen.add(l.productId())) {
                throw new DomainException(HttpStatus.BAD_REQUEST, "DUPLICATE_LINE",
                        "Each product may appear on only one line; combine the quantities");
            }
        }
        Map<Long, ProductSummary> products = catalog.summaries(seen);
        return cmd.lines().stream().map(l -> {
            ProductSummary p = products.get(l.productId());
            if (p == null) {
                throw DomainException.notFound("Product", l.productId());
            }
            if (!p.active()) {
                throw new DomainException(HttpStatus.BAD_REQUEST, "PRODUCT_INACTIVE", p.sku() + " is not orderable");
            }
            return new OrderFacts.LineDraft(p.id(), p.sku(), p.name(), l.quantity(), p.unitPrice());
        }).toList();
    }

    private static String normaliseKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        if (!KEY.matcher(key).matches()) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must be 8-64 characters of letters, digits, '-' or '_'");
        }
        return key;
    }

    private static boolean hasRole(String role) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + role));
    }

}
