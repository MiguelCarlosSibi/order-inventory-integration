package edu.cit.sibi.supplier;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/**
 * Our own record of a reorder — never exposes LegacySupply's vocabulary
 * outside this package. {@code cases}/{@code units} are both stored so the
 * unit conversion is auditable: {@code units} is what Inventory asked for,
 * {@code cases} is what was actually sent to LegacySupply (rounded up).
 */
@Entity
@Table(name = "supplier_orders")
class SupplierOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "product_id", nullable = false)
    private String productId;

    // Nullable at the DB level: this is set in a second save() immediately
    // after the first one assigns the id ("RO-" + id), so it's briefly
    // null between those two calls but never null once requestReorder()
    // returns.
    @Column(name = "buyer_ref", unique = true, length = 40)
    private String buyerRef;

    @Column(name = "request_id", nullable = false, unique = true, length = 80)
    private String requestId;

    @Column(name = "po_number")
    private String poNumber;

    @Column(name = "cases", nullable = false)
    private int cases;

    @Column(name = "units", nullable = false)
    private int units;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SupplierOrderStatus status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected SupplierOrder() {
        // JPA
    }

    /**
     * requestId is generated here (not derived from the DB id) so it's
     * non-null on the very first insert and never changes afterward —
     * exactly what Part D asks for ("the same reorder must always be sent
     * with the same X-Request-Id, including across retries and restarts").
     * buyerRef, by contrast, IS derived from the generated id per the spec
     * ("RO-" plus your supplier_orders id), so it's set in a second save
     * once the id is known — see SupplierGatewayImpl.requestReorder().
     */
    SupplierOrder(String productId, int cases, int units) {
        this.productId = productId;
        this.requestId = "REORDER-" + java.util.UUID.randomUUID();
        this.cases = cases;
        this.units = units;
        this.status = SupplierOrderStatus.PENDING;
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = this.createdAt;
    }

    Long getId() {
        return id;
    }

    String getProductId() {
        return productId;
    }

    String getBuyerRef() {
        return buyerRef;
    }

    void setBuyerRef(String buyerRef) {
        this.buyerRef = buyerRef;
    }

    String getRequestId() {
        return requestId;
    }

    String getPoNumber() {
        return poNumber;
    }

    void setPoNumber(String poNumber) {
        this.poNumber = poNumber;
    }

    int getCases() {
        return cases;
    }

    int getUnits() {
        return units;
    }

    SupplierOrderStatus getStatus() {
        return status;
    }

    void setStatus(SupplierOrderStatus status) {
        this.status = status;
        this.updatedAt = OffsetDateTime.now();
    }

    OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
