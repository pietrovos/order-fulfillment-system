package com.fulfillops.fulfillment.internal;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "shipments")
class Shipment {

    @Id
    private UUID id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private long orderId;

    @Column(name = "order_number", nullable = false, updatable = false)
    private String orderNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ShipmentStatus status = ShipmentStatus.PENDING;

    @Column(nullable = false, updatable = false)
    private String carrier;

    @Column(name = "carrier_idempotency_key", nullable = false, updatable = false)
    private String carrierIdempotencyKey;

    @Column(name = "carrier_shipment_id")
    private String carrierShipmentId;

    @Column(name = "tracking_number")
    private String trackingNumber;

    @Column(nullable = false)
    private int parcels;

    @Column(name = "weight_kg", nullable = false)
    private BigDecimal weightKg;

    @Column(name = "ship_to_name", nullable = false)
    private String shipToName;

    @Column(name = "ship_to_address", nullable = false)
    private String shipToAddress;

    @Column(name = "booking_attempts", nullable = false)
    private int bookingAttempts;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "packed_by", nullable = false, updatable = false)
    private String packedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "booked_at")
    private Instant bookedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    private long version;

    @OneToMany(mappedBy = "shipment", cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @OrderBy("id")
    private List<ShipmentEvent> events = new ArrayList<>();

    protected Shipment() {
    }

    Shipment(long orderId, String orderNumber, String carrier, int parcels, BigDecimal weightKg, String shipToName,
             String shipToAddress, String packedBy) {
        this.id = UUID.randomUUID();
        this.orderId = orderId;
        this.orderNumber = orderNumber;
        this.carrier = carrier;
        // Derived from our own shipment id: stable for the life of this shipment, unique across shipments.
        this.carrierIdempotencyKey = "fo-shp-" + id;
        this.parcels = parcels;
        this.weightKg = weightKg;
        this.shipToName = shipToName;
        this.shipToAddress = shipToAddress;
        this.packedBy = packedBy;
    }

    void record(String type, String detail, String actor) {
        events.add(new ShipmentEvent(this, type, detail, actor));
    }

    void attemptFailed(String error) {
        bookingAttempts++;
        lastError = error;
    }

    void booked(String carrierShipmentId, String trackingNumber) {
        bookingAttempts++;
        this.status = ShipmentStatus.BOOKED;
        this.carrierShipmentId = carrierShipmentId;
        this.trackingNumber = trackingNumber;
        this.bookedAt = Instant.now();
        this.lastError = null;
    }

    void failed(String error) {
        this.status = ShipmentStatus.FAILED;
        this.lastError = error;
    }

    void retry() {
        this.status = ShipmentStatus.PENDING;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    UUID getId() {
        return id;
    }

    long getOrderId() {
        return orderId;
    }

    String getOrderNumber() {
        return orderNumber;
    }

    ShipmentStatus getStatus() {
        return status;
    }

    String getCarrierIdempotencyKey() {
        return carrierIdempotencyKey;
    }

    int getParcels() {
        return parcels;
    }

    BigDecimal getWeightKg() {
        return weightKg;
    }

    String getShipToName() {
        return shipToName;
    }

    String getShipToAddress() {
        return shipToAddress;
    }

    ShipmentView toView() {
        return new ShipmentView(id, orderId, orderNumber, status.name(), carrier, carrierIdempotencyKey,
                carrierShipmentId, trackingNumber, parcels, weightKg, shipToName, shipToAddress, bookingAttempts,
                lastError, packedBy, createdAt, bookedAt, events.stream().map(ShipmentEvent::toView).toList());
    }
}
