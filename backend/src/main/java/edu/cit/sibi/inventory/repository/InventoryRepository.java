package edu.cit.sibi.inventory.repository;

import edu.cit.sibi.inventory.model.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryRepository extends JpaRepository<Inventory, String> {

    /** Returns 1 if stock was decremented, 0 if there wasn't enough. Atomic in the DB. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Inventory i set i.stock = i.stock - :q where i.productId = :id and i.stock >= :q")
    int tryDecrement(@Param("id") String id, @Param("q") int q);
}