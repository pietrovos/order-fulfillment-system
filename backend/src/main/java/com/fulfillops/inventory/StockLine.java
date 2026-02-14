package com.fulfillops.inventory;

/** A quantity of one product, as used by reservations, releases and shipments. */
public record StockLine(long productId, int quantity) {

    public StockLine {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
    }
}
