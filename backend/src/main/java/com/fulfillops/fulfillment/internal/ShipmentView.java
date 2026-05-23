package com.fulfillops.fulfillment.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

record ShipmentView(UUID id, long orderId, String orderNumber, String status, String carrier,
                    String carrierIdempotencyKey, String carrierShipmentId, String trackingNumber, int parcels,
                    BigDecimal weightKg, String shipToName, String shipToAddress, int bookingAttempts,
                    String lastError, String packedBy, Instant createdAt, Instant bookedAt, List<Event> events) {

    record Event(String type, String detail, String actor, Instant at) {
    }
}
