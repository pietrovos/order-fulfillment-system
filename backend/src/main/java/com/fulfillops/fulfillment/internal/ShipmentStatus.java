package com.fulfillops.fulfillment.internal;

enum ShipmentStatus {
    /** Packed; booking with the carrier requested (outbox job pending or retrying). */
    PENDING,
    /** Carrier confirmed; order is SHIPPED. */
    BOOKED,
    /** Booking gave up (attempts exhausted or carrier rejected); needs a supervisor. */
    FAILED
}
