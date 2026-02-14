package com.fulfillops.inventory.internal;

import com.fulfillops.catalog.ProductCreated;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Runs synchronously inside the catalog's transaction: a product never exists without a stock row. */
@Component
class ProductCreatedListener {

    private final StockLedger ledger;

    ProductCreatedListener(StockLedger ledger) {
        this.ledger = ledger;
    }

    @EventListener
    void on(ProductCreated event) {
        ledger.ensureRow(event.productId());
    }
}
