package edu.cit.sibi.inventory;

import edu.cit.sibi.inventory.dto.InventoryItem;
import edu.cit.sibi.inventory.dto.ReservationResult;
import edu.cit.sibi.inventory.model.Inventory;
import edu.cit.sibi.inventory.repository.InventoryRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Package-private on purpose: nothing outside edu.cit.sibi.inventory can
 * reference this type. Everyone else goes through InventoryService.
 */
@Service
class InventoryServiceImpl implements InventoryService {

    private final InventoryRepository inventoryRepository;
    private final ApplicationEventPublisher eventPublisher;

    InventoryServiceImpl(InventoryRepository inventoryRepository, ApplicationEventPublisher eventPublisher) {
        this.inventoryRepository = inventoryRepository;
        this.eventPublisher = eventPublisher;
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
        Optional<Inventory> maybe = inventoryRepository.findById(productId);
        if (maybe.isEmpty()) {
            return new ReservationResult(false, "Product not found: " + productId, null);
        }
        if (quantity <= 0) {
            return new ReservationResult(false, "Quantity must be greater than zero", toDto(maybe.get()));
        }

        // Atomic in the database: decrements only if enough stock remains.
        int updated = inventoryRepository.tryDecrement(productId, quantity);
        Inventory current = inventoryRepository.findById(productId).orElseThrow();

        if (updated == 0) {
            String reason = "Insufficient stock for " + productId
                    + ": requested " + quantity + ", available " + current.getStock();
            return new ReservationResult(false, reason, toDto(current));
        }
        eventPublisher.publishEvent(new InventoryChangedEvent(current.getProductId(), current.getStock()));
        return new ReservationResult(true, null, toDto(current));
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

        return new ReservationResult(true, null, toDto(inventory));
    }

    @Override
    @Transactional
    public InventoryItem restock(String productId, int quantity) {
        Inventory inventory = inventoryRepository.findById(productId)
                .orElseThrow(() -> new NoSuchElementException("Product not found: " + productId));
        inventory.setStock(inventory.getStock() + quantity);
        Inventory saved = inventoryRepository.save(inventory);
        eventPublisher.publishEvent(new InventoryChangedEvent(saved.getProductId(), saved.getStock()));
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