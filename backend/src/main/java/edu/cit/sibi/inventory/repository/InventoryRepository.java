package edu.cit.sibi.inventory.repository;

import edu.cit.sibi.inventory.model.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InventoryRepository extends JpaRepository<Inventory, String> {
}
