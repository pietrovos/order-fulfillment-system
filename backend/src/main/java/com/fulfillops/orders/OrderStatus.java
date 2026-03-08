package com.fulfillops.orders;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The order lifecycle. This table is the single definition of which transitions are legal; every
 * status change goes through {@code Order.transitionTo}, which consults it.
 *
 * <pre>
 * DRAFT -> SUBMITTED -> RESERVED -> PICKING -> PACKED -> SHIPPED
 *              |  \         |          |
 *              |   \        +----------+--> CANCELLED (releases reserved stock)
 *              v    +-----------------------> CANCELLED
 *       STOCK_EXCEPTION --(retry)--> RESERVED
 *              +--> CANCELLED
 * DRAFT -> CANCELLED
 * </pre>
 */
public enum OrderStatus {
    DRAFT,
    SUBMITTED,
    RESERVED,
    PICKING,
    PACKED,
    SHIPPED,
    CANCELLED,
    STOCK_EXCEPTION;

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = new EnumMap<>(OrderStatus.class);

    static {
        ALLOWED.put(DRAFT, EnumSet.of(SUBMITTED, CANCELLED));
        ALLOWED.put(SUBMITTED, EnumSet.of(RESERVED, STOCK_EXCEPTION, CANCELLED));
        ALLOWED.put(RESERVED, EnumSet.of(PICKING, CANCELLED));
        ALLOWED.put(PICKING, EnumSet.of(PACKED, CANCELLED));
        ALLOWED.put(PACKED, EnumSet.of(SHIPPED));
        ALLOWED.put(SHIPPED, EnumSet.noneOf(OrderStatus.class));
        ALLOWED.put(CANCELLED, EnumSet.noneOf(OrderStatus.class));
        ALLOWED.put(STOCK_EXCEPTION, EnumSet.of(RESERVED, CANCELLED));
    }

    public boolean canTransitionTo(OrderStatus next) {
        return ALLOWED.get(this).contains(next);
    }

    public Set<OrderStatus> nextStatuses() {
        return Collections.unmodifiableSet(ALLOWED.get(this));
    }

    /** Statuses in which the order's quantities are counted in stock_levels.reserved. */
    public boolean holdsReservation() {
        return this == RESERVED || this == PICKING || this == PACKED;
    }

    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }
}
