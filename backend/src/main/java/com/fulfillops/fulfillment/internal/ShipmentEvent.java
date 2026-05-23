package com.fulfillops.fulfillment.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/** Append-only (database trigger). */
@Entity
@Table(name = "shipment_events")
class ShipmentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shipment_id", updatable = false)
    private Shipment shipment;

    @Column(nullable = false, updatable = false)
    private String type;

    @Column(updatable = false)
    private String detail;

    @Column(nullable = false, updatable = false)
    private String actor;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected ShipmentEvent() {
    }

    ShipmentEvent(Shipment shipment, String type, String detail, String actor) {
        this.shipment = shipment;
        this.type = type;
        this.detail = detail;
        this.actor = actor;
    }

    ShipmentView.Event toView() {
        return new ShipmentView.Event(type, detail, actor, createdAt);
    }
}
