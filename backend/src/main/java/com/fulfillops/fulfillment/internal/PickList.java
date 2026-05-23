package com.fulfillops.fulfillment.internal;

import com.fulfillops.orders.OrderView;
import com.fulfillops.shared.web.DomainException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;

@Entity
@Table(name = "pick_lists")
class PickList {

    enum Status { OPEN, COMPLETED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private long orderId;

    @Column(name = "order_number", nullable = false, updatable = false)
    private String orderNumber;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.OPEN;

    @Column(nullable = false)
    private String picker;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    private long version;

    @OneToMany(mappedBy = "pickList", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo")
    private List<PickListLine> lines = new ArrayList<>();

    protected PickList() {
    }

    PickList(OrderView order, String picker) {
        this.orderId = order.id();
        this.orderNumber = order.orderNumber();
        this.picker = picker;
        order.lines().forEach(l -> lines.add(new PickListLine(this, l)));
    }

    void confirm(int lineNo, boolean picked, String actor) {
        requireOpen();
        PickListLine line = lines.stream().filter(l -> l.getLineNo() == lineNo).findFirst()
                .orElseThrow(() -> DomainException.notFound("Pick line", lineNo));
        line.mark(picked, actor);
    }

    void complete() {
        requireOpen();
        long missing = lines.stream().filter(l -> !l.isPicked()).count();
        if (missing > 0) {
            throw new DomainException(HttpStatus.CONFLICT, "PICK_INCOMPLETE",
                    missing + " line(s) on " + orderNumber + " are not picked yet");
        }
        status = Status.COMPLETED;
        completedAt = Instant.now();
    }

    private void requireOpen() {
        if (status != Status.OPEN) {
            throw new DomainException(HttpStatus.CONFLICT, "PICK_CLOSED", "Pick list for " + orderNumber + " is closed");
        }
    }

    Long getId() {
        return id;
    }

    long getOrderId() {
        return orderId;
    }

    String getOrderNumber() {
        return orderNumber;
    }

    Status getStatus() {
        return status;
    }

    PickListView toView(String orderStatus) {
        return new PickListView(id, orderId, orderNumber, orderStatus, status.name(), picker, startedAt, completedAt,
                version, lines.stream().map(PickListLine::toView).toList());
    }
}
