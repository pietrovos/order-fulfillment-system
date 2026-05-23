package com.fulfillops.fulfillment.internal;

import com.fulfillops.orders.OrderView;
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

@Entity
@Table(name = "pick_list_lines")
class PickListLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pick_list_id")
    private PickList pickList;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "product_id", nullable = false)
    private long productId;

    @Column(nullable = false)
    private String sku;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private boolean picked;

    @Column(name = "picked_by")
    private String pickedBy;

    @Column(name = "picked_at")
    private Instant pickedAt;

    protected PickListLine() {
    }

    PickListLine(PickList pickList, OrderView.Line line) {
        this.pickList = pickList;
        this.lineNo = line.lineNo();
        this.productId = line.productId();
        this.sku = line.sku();
        this.productName = line.productName();
        this.quantity = line.quantity();
    }

    void mark(boolean picked, String actor) {
        this.picked = picked;
        this.pickedBy = picked ? actor : null;
        this.pickedAt = picked ? Instant.now() : null;
    }

    int getLineNo() {
        return lineNo;
    }

    boolean isPicked() {
        return picked;
    }

    PickListView.Line toView() {
        return new PickListView.Line(lineNo, productId, sku, productName, quantity, picked, pickedBy, pickedAt);
    }
}
