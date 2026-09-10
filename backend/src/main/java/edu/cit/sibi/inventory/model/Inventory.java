package edu.cit.sibi.inventory.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "inventory")
public class Inventory {

    @Id
    @Column(name = "product_id", length = 20)
    private String productId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private Integer stock;

    protected Inventory() {
        // required by JPA
    }

    public Inventory(String productId, String name, Integer stock) {
        this.productId = productId;
        this.name = name;
        this.stock = stock;
    }

    public String getProductId() {
        return productId;
    }

    public String getName() {
        return name;
    }

    public Integer getStock() {
        return stock;
    }

    public void setStock(Integer stock) {
        this.stock = stock;
    }
}
