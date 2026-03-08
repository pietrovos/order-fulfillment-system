package com.fulfillops.orders.internal;

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
import java.math.BigDecimal;

@Entity
@Table(name = "order_lines")
public class OrderLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private Order order;

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

    @Column(name = "unit_price", nullable = false)
    private BigDecimal unitPrice;

    protected OrderLine() {
    }

    OrderLine(Order order, int lineNo, OrderFacts.LineDraft d) {
        this.order = order;
        this.lineNo = lineNo;
        this.productId = d.productId();
        this.sku = d.sku();
        this.productName = d.productName();
        this.quantity = d.quantity();
        this.unitPrice = d.unitPrice();
    }

    BigDecimal lineTotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }

    public long getProductId() {
        return productId;
    }

    public int getQuantity() {
        return quantity;
    }

    public String getSku() {
        return sku;
    }

    OrderView.Line toView() {
        return new OrderView.Line(lineNo, productId, sku, productName, quantity, unitPrice, lineTotal());
    }
}
