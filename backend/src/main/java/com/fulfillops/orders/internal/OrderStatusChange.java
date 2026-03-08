package com.fulfillops.orders.internal;

import com.fulfillops.orders.OrderStatus;
import com.fulfillops.orders.OrderView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/** Append-only (enforced by a database trigger): every column is insert-only. */
@Entity
@Table(name = "order_status_history")
class OrderStatusChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", updatable = false)
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", updatable = false)
    private OrderStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, updatable = false)
    private OrderStatus toStatus;

    @Column(nullable = false, updatable = false)
    private String actor;

    @Column(updatable = false)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected OrderStatusChange() {
    }

    OrderStatusChange(Order order, OrderStatus from, OrderStatus to, String actor, String note) {
        this.order = order;
        this.fromStatus = from;
        this.toStatus = to;
        this.actor = actor;
        this.note = note;
    }

    OrderView.StatusChange toView() {
        return new OrderView.StatusChange(fromStatus, toStatus, actor, note, createdAt);
    }
}
