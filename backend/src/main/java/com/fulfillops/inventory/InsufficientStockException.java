package com.fulfillops.inventory;

import com.fulfillops.shared.web.DomainException;
import org.springframework.http.HttpStatus;

/** A reservation could not be satisfied. Thrown so the whole multi-line reservation rolls back. */
public class InsufficientStockException extends DomainException {

    private final long productId;
    private final int requested;
    private final int available;

    public InsufficientStockException(long productId, int requested, int available) {
        super(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK",
                "Product " + productId + ": requested " + requested + ", only " + available + " available");
        this.productId = productId;
        this.requested = requested;
        this.available = available;
    }

    public long productId() {
        return productId;
    }

    public int requested() {
        return requested;
    }

    public int available() {
        return available;
    }
}
