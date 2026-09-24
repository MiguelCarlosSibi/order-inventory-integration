package edu.cit.sibi.shop.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "order_item_id")
    private Long orderItemId;

    @ManyToOne
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "product_id", nullable = false, length = 20)
    private String productId;

    @Column(nullable = false)
    private Integer quantity;

    // "OK" if this item was (or would have been) reservable; otherwise the
    // specific rejection reason for THIS item. Persisted so order history
    // can show the real per-item result instead of falling back to the
    // order's overall status for every line item.
    @Column(nullable = false, length = 255)
    private String outcome;

    protected OrderItem() {
        // required by JPA
    }

    public OrderItem(String productId, Integer quantity, String outcome) {
        this.productId = productId;
        this.quantity = quantity;
        this.outcome = outcome;
    }

    public Long getOrderItemId() {
        return orderItemId;
    }

    public Order getOrder() {
        return order;
    }

    public void setOrder(Order order) {
        this.order = order;
    }

    public String getProductId() {
        return productId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public String getOutcome() {
        return outcome;
    }
}
