package edu.cit.sibi.supplier;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface SupplierOrderRepository extends JpaRepository<SupplierOrder, Long> {

    Optional<SupplierOrder> findByRequestId(String requestId);

    Optional<SupplierOrder> findByBuyerRef(String buyerRef);

    List<SupplierOrder> findByStatus(SupplierOrderStatus status);

    /** Orders still being tracked — anything short of a terminal state. */
    List<SupplierOrder> findByStatusIn(List<SupplierOrderStatus> statuses);
}
