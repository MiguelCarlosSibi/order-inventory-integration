package edu.cit.sibi.inventory;

import edu.cit.sibi.inventory.dto.InventoryItem;
import edu.cit.sibi.inventory.dto.ReservationResult;

import java.util.List;

/**
 * The Inventory module's public contract. This is the ONLY type from this
 * module that other modules (namely edu.cit.sibi.shop) are allowed to
 * reference. The implementation is package-private (see
 * InventoryServiceImpl) so nothing outside this package can new it up,
 * downcast to it, or depend on any method that isn't declared here.
 */
public interface InventoryService {

    /**
     * Looks up a single product's current stock.
     * @throws java.util.NoSuchElementException if the product doesn't exist
     */
    InventoryItem getItem(String productId);

    /**
     * Attempts to reserve (decrement) stock for an order. Never throws for
     * "not enough stock" or "unknown product" — those are represented as a
     * failed ReservationResult so the caller can decide what CONFIRMED vs
     * REJECTED means for its own domain.
     */
    ReservationResult reserve(String productId, int quantity);

    /** Convenience read used by the frontend to populate the product list. */
    List<InventoryItem> getAllItems();
}
