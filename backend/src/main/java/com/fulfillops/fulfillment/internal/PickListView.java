package com.fulfillops.fulfillment.internal;

import java.time.Instant;
import java.util.List;

record PickListView(long id, long orderId, String orderNumber, String orderStatus, String status, String picker,
                    Instant startedAt, Instant completedAt, long version, List<Line> lines) {

    record Line(int lineNo, long productId, String sku, String productName, int quantity, boolean picked,
                String pickedBy, Instant pickedAt) {
    }

    long pickedCount() {
        return lines.stream().filter(Line::picked).count();
    }
}
