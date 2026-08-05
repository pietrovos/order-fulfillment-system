package com.fulfillops.orders;

import java.time.Instant;

/** One row of the audit feed: who moved which order from what to what, and why. */
public record ActivityEntry(long id, long orderId, String orderNumber, OrderStatus from, OrderStatus to, String actor,
                            String note, Instant at) {
}
