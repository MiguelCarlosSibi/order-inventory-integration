package edu.cit.sibi.inventory.controller;

import edu.cit.sibi.inventory.InventoryService;
import edu.cit.sibi.inventory.dto.InventoryItem;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only endpoints, not required by the assignment spec but small and
 * useful: they let the React product dropdown show live stock instead of
 * hardcoded numbers. Not part of the Order flow.
 */
@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping
    public List<InventoryItem> getAll() {
        return inventoryService.getAllItems();
    }

    @GetMapping("/{productId}")
    public InventoryItem getOne(@PathVariable String productId) {
        return inventoryService.getItem(productId);
    }
}
