package com.fulfillops.orders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

public record OrderView(
        long id,
        String orderNumber,
        OrderStatus status,
        String customerName,
        String customerEmail,
        String shippingAddress,
        String notes,
        BigDecimal totalAmount,
        String exceptionReason,
        String cancelReason,
        String createdBy,
        Instant createdAt,
        Instant updatedAt,
        Instant submittedAt,
        long version,
        List<Line> lines,
        List<StatusChange> history,
        Set<OrderStatus> nextStatuses) {

    public record Line(int lineNo, long productId, String sku, String productName, int quantity, BigDecimal unitPrice,
                       BigDecimal lineTotal) {
    }

    public record StatusChange(OrderStatus from, OrderStatus to, String actor, String note, Instant at) {
    }

    public int totalUnits() {
        return lines.stream().mapToInt(Line::quantity).sum();
    }
}
