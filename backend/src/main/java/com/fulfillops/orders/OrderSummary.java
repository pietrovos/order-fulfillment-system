package com.fulfillops.orders;

import java.math.BigDecimal;
import java.time.Instant;

/** One row of the order table. */
public record OrderSummary(long id, String orderNumber, OrderStatus status, String customerName, int lineCount,
                           int totalUnits, BigDecimal totalAmount, String createdBy, Instant createdAt,
                           Instant updatedAt, String exceptionReason) {
}
