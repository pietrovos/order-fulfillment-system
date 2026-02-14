package com.fulfillops.inventory;

import java.time.Instant;

public record StockLevelView(long productId, String sku, String name, int onHand, int reserved, int available,
                             int reorderPoint, boolean lowStock, Instant updatedAt) {
}
