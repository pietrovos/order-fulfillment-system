package com.fulfillops.catalog;

/** Published inside the creating transaction so listeners can set up related rows atomically. */
public record ProductCreated(Long productId) {
}
