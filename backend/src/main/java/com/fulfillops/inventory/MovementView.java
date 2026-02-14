package com.fulfillops.inventory;

import java.time.Instant;

public record MovementView(long id, long productId, String sku, String type, int onHandDelta, int reservedDelta,
                           int onHandAfter, int reservedAfter, String referenceType, String referenceId,
                           String reason, String actor, Instant createdAt) {
}
