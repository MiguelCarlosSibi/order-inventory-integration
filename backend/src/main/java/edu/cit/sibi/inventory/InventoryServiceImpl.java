package edu.cit.sibi.inventory;

import edu.cit.sibi.inventory.dto.InventoryItem;
import edu.cit.sibi.inventory.dto.ReservationResult;
import edu.cit.sibi.inventory.model.Inventory;
import edu.cit.sibi.inventory.repository.InventoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Package-private on purpose: this class is an implementation detail of the
 * Inventory module. Spring can still find and wire it (component scanning
 * and reflection don't care about visibility modifiers), but no class
 * outside edu.cit.sibi.inventory can import it, reference its type, or new
 * it up directly. Everything outside this package is forced to go through
 * the InventoryService interface. See the README for why that matters.
 */
@Service
class InventoryServiceImpl implements InventoryService {

    private final InventoryRepository inventoryRepository;

    InventoryServiceImpl(InventoryRepository inventoryRepository) {
        this.inventoryRepository = inventoryRepository;
    }

    @Override
    public InventoryItem getItem(String productId) {
        Inventory inventory = inventoryRepository.findById(productId)
                .orElseThrow(() -> new NoSuchElementException("Product not found: " + productId));
        return toDto(inventory);
    }

    @Override
    @Transactional
    public ReservationResult reserve(String productId, int quantity) {
        Optional<Inventory> maybeInventory = inventoryRepository.findById(productId);

        if (maybeInventory.isEmpty()) {
            return new ReservationResult(false, "Product not found: " + productId, null);
        }

        Inventory inventory = maybeInventory.get();

        if (quantity <= 0) {
            return new ReservationResult(false, "Quantity must be greater than zero", toDto(inventory));
        }

        if (quantity > inventory.getStock()) {
            String reason = "Insufficient stock for " + productId
                    + ": requested " + quantity + ", available " + inventory.getStock();
            return new ReservationResult(false, reason, toDto(inventory));
        }

        inventory.setStock(inventory.getStock() - quantity);
        Inventory saved = inventoryRepository.save(inventory);
        return new ReservationResult(true, null, toDto(saved));
    }

    @Override
    public ReservationResult checkAvailability(String productId, int quantity) {
        Optional<Inventory> maybeInventory = inventoryRepository.findById(productId);

        if (maybeInventory.isEmpty()) {
            return new ReservationResult(false, "Product not found: " + productId, null);
        }

        Inventory inventory = maybeInventory.get();

        if (quantity <= 0) {
            return new ReservationResult(false, "Quantity must be greater than zero", toDto(inventory));
        }

        if (quantity > inventory.getStock()) {
            String reason = "Insufficient stock for " + productId
                    + ": requested " + quantity + ", available " + inventory.getStock();
            return new ReservationResult(false, reason, toDto(inventory));
        }

        // Would succeed — but nothing is mutated here, unlike reserve().
        return new ReservationResult(true, null, toDto(inventory));
    }

    @Override
    @Transactional
    public InventoryItem restock(String productId, int quantity) {
        Inventory inventory = inventoryRepository.findById(productId)
                .orElseThrow(() -> new NoSuchElementException("Product not found: " + productId));
        inventory.setStock(inventory.getStock() + quantity);
        Inventory saved = inventoryRepository.save(inventory);
        return toDto(saved);
    }

    @Override
    public List<InventoryItem> getAllItems() {
        return inventoryRepository.findAll().stream()
                .map(this::toDto)
                .toList();
    }

    private InventoryItem toDto(Inventory inventory) {
        return new InventoryItem(inventory.getProductId(), inventory.getName(), inventory.getStock());
    }
}
