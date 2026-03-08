package com.fulfillops.orders.internal;

import com.fulfillops.inventory.StockLine;
import com.fulfillops.orders.InvalidTransitionException;
import com.fulfillops.orders.OrderStatus;
import com.fulfillops.orders.OrderSummary;
import com.fulfillops.orders.OrderView;
import com.fulfillops.shared.web.DomainException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
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
import org.hibernate.annotations.Formula;
import org.hibernate.annotations.Generated;
import org.springframework.http.HttpStatus;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Assigned by the database (sequence default) and read back after insert. */
    @Generated
    @Column(name = "order_number", insertable = false, updatable = false)
    private String orderNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(name = "customer_name", nullable = false)
    private String customerName;

    @Column(name = "customer_email")
    private String customerEmail;

    @Column(name = "shipping_address", nullable = false)
    private String shippingAddress;

    private String notes;

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "idempotency_key", updatable = false)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", updatable = false)
    private String requestFingerprint;

    @Column(name = "exception_reason")
    private String exceptionReason;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Column(name = "created_by", nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Version
    private long version;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo")
    private List<OrderLine> lines = new ArrayList<>();

    @OneToMany(mappedBy = "order", cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @OrderBy("id")
    private List<OrderStatusChange> history = new ArrayList<>();

    @Formula("(select count(*) from order_lines l where l.order_id = id)")
    private int lineCount;

    @Formula("(select coalesce(sum(l.quantity), 0) from order_lines l where l.order_id = id)")
    private int totalUnits;

    protected Order() {
    }

    static Order draft(String createdBy, String idempotencyKey, String fingerprint) {
        Order o = new Order();
        o.status = OrderStatus.DRAFT;
        o.createdBy = createdBy;
        o.idempotencyKey = idempotencyKey;
        o.requestFingerprint = fingerprint;
        o.history.add(new OrderStatusChange(o, null, OrderStatus.DRAFT, createdBy, "Order created"));
        return o;
    }

    void setCustomer(String name, String email, String shippingAddress, String notes) {
        requireStatus(OrderStatus.DRAFT, "edit");
        this.customerName = name.trim();
        this.customerEmail = email == null || email.isBlank() ? null : email.trim();
        this.shippingAddress = shippingAddress.trim();
        this.notes = notes == null || notes.isBlank() ? null : notes.trim();
    }

    /** Replaces all line items. Only drafts are editable; submitted orders are immutable. */
    void replaceLines(List<OrderFacts.LineDraft> drafts) {
        requireStatus(OrderStatus.DRAFT, "edit");
        lines.clear();
        int lineNo = 1;
        for (OrderFacts.LineDraft d : drafts) {
            lines.add(new OrderLine(this, lineNo++, d));
        }
        totalAmount = lines.stream().map(OrderLine::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * The only way to change status. Rejects anything the {@link OrderStatus} table does not allow and
     * records an audit row for everything it does allow.
     */
    void transitionTo(OrderStatus next, String actor, String note) {
        if (!status.canTransitionTo(next)) {
            throw new InvalidTransitionException(orderNumber, status, next);
        }
        history.add(new OrderStatusChange(this, status, next, actor, note));
        if (next == OrderStatus.SUBMITTED) {
            submittedAt = Instant.now();
        }
        if (next == OrderStatus.RESERVED) {
            exceptionReason = null;
        }
        status = next;
    }

    void markStockException(String actor, String reason) {
        if (status == OrderStatus.STOCK_EXCEPTION) {
            // A retry that failed again: keep the status, refresh the explanation.
            exceptionReason = reason;
            return;
        }
        transitionTo(OrderStatus.STOCK_EXCEPTION, actor, reason);
        exceptionReason = reason;
    }

    void cancel(String actor, String reason) {
        transitionTo(OrderStatus.CANCELLED, actor, reason);
        cancelReason = reason;
    }

    private void requireStatus(OrderStatus required, String action) {
        if (status != required) {
            throw new DomainException(HttpStatus.CONFLICT, "ORDER_NOT_EDITABLE",
                    "Order " + orderNumber + " is " + status + "; only " + required + " orders can " + action);
        }
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    List<StockLine> stockLines() {
        return lines.stream().map(l -> new StockLine(l.getProductId(), l.getQuantity())).toList();
    }

    public Long getId() {
        return id;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public List<OrderLine> getLines() {
        return lines;
    }

    public long getVersion() {
        return version;
    }

    OrderView toView() {
        return new OrderView(id, orderNumber, status, customerName, customerEmail, shippingAddress, notes, totalAmount,
                exceptionReason, cancelReason, createdBy, createdAt, updatedAt, submittedAt, version,
                lines.stream().map(OrderLine::toView).toList(),
                history.stream().map(OrderStatusChange::toView).toList(),
                status.nextStatuses());
    }

    OrderSummary toSummary() {
        return new OrderSummary(id, orderNumber, status, customerName, lineCount, totalUnits, totalAmount, createdBy,
                createdAt, updatedAt, exceptionReason);
    }
}
