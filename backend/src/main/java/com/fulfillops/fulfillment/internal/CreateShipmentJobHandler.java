package com.fulfillops.fulfillment.internal;

import com.fulfillops.orders.OrderService;
import com.fulfillops.orders.OrderStatus;
import com.fulfillops.shared.jobs.Job;
import com.fulfillops.shared.jobs.JobHandler;
import com.fulfillops.shared.jobs.PermanentJobFailure;
import com.fulfillops.shared.security.CurrentActor;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Books a packed shipment with the carrier. The network call runs outside a database transaction:
 *
 * <ol>
 *   <li>Read the shipment; if it is no longer PENDING this is a duplicate delivery, so do nothing.</li>
 *   <li>Call the carrier with the shipment's fixed idempotency key (no transaction open).</li>
 *   <li>Lock the shipment, record BOOKED, move the order to SHIPPED and consume the stock, all in one
 *       transaction. If step 2 failed, record the attempt instead and rethrow so the job runner backs off
 *       and retries.</li>
 * </ol>
 *
 * Lost-response case: the carrier booked it, the connection dropped, and step 2 threw. The retry sends the
 * same key, the carrier answers with the existing booking, and step 3 records it.
 */
@Component
class CreateShipmentJobHandler implements JobHandler {

    private final ShipmentRepository shipments;
    private final CarrierClient carrier;
    private final OrderService orders;
    private final TransactionTemplate tx;

    CreateShipmentJobHandler(ShipmentRepository shipments, CarrierClient carrier, OrderService orders,
                             TransactionTemplate tx) {
        this.shipments = shipments;
        this.carrier = carrier;
        this.orders = orders;
        this.tx = tx;
    }

    @Override
    public String type() {
        return FulfillmentService.CREATE_SHIPMENT;
    }

    private record Snapshot(String key, Map<String, Object> request) {
    }

    @Override
    public void handle(Job job) {
        UUID id = UUID.fromString(job.aggregateId());
        Snapshot snapshot = tx.execute(s -> {
            Shipment sh = shipments.findById(id).orElseThrow(() -> new PermanentJobFailure("Shipment " + id + " not found"));
            if (sh.getStatus() != ShipmentStatus.PENDING) {
                return null;
            }
            return new Snapshot(sh.getCarrierIdempotencyKey(), Map.of(
                    "reference", sh.getOrderNumber(),
                    "recipient", sh.getShipToName(),
                    "address", sh.getShipToAddress(),
                    "parcels", sh.getParcels(),
                    "weightKg", sh.getWeightKg()));
        });
        if (snapshot == null) {
            return;
        }

        CarrierClient.Booking booking;
        try {
            booking = carrier.book(snapshot.key(), snapshot.request());
        } catch (CarrierClient.TransientCarrierException e) {
            boolean lastAttempt = job.attempts() >= job.maxAttempts();
            recordFailure(id, e.getMessage(), job, lastAttempt);
            throw e;
        } catch (CarrierClient.RejectedByCarrierException e) {
            recordFailure(id, e.getMessage(), job, true);
            throw new PermanentJobFailure(e.getMessage());
        }

        tx.executeWithoutResult(s -> {
            Shipment sh = lock(id);
            if (sh.getStatus() == ShipmentStatus.BOOKED) {
                return;
            }
            sh.booked(booking.shipmentId(), booking.trackingNumber());
            sh.record("BOOKED", "Carrier ref " + booking.shipmentId() + ", tracking " + booking.trackingNumber()
                    + (booking.replayed() ? " (carrier returned the booking it already had for this key)" : ""),
                    CurrentActor.SYSTEM);
            orders.transitionForFulfillment(sh.getOrderId(), OrderStatus.SHIPPED,
                    "Handed to " + FulfillmentService.CARRIER + ", tracking " + booking.trackingNumber());
            shipments.flush();
        });
    }

    private void recordFailure(UUID id, String error, Job job, boolean giveUp) {
        tx.executeWithoutResult(s -> {
            Shipment sh = lock(id);
            if (sh.getStatus() != ShipmentStatus.PENDING) {
                return;
            }
            sh.attemptFailed(error);
            sh.record("BOOKING_ATTEMPT_FAILED", "Attempt " + job.attempts() + ": " + error
                    + (giveUp ? "" : ". Will retry with the same idempotency key."), CurrentActor.SYSTEM);
            if (giveUp) {
                sh.failed(error);
                sh.record("BOOKING_FAILED", "Gave up after " + job.attempts() + " attempt(s); needs a supervisor",
                        CurrentActor.SYSTEM);
            }
            shipments.flush();
        });
    }

    private Shipment lock(UUID id) {
        return shipments.findByIdForUpdate(id).orElseThrow();
    }
}
