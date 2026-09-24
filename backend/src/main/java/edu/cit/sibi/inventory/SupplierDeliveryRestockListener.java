package edu.cit.sibi.inventory;

import edu.cit.sibi.supplier.SupplierOrderDeliveredEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Part E: "Inventory listens and restocks the correct number of units.
 * Order and Inventory never call the supplier module directly for this."
 * This listener is the entire extent of Inventory's dependency on the
 * supplier module — it imports SupplierOrderDeliveredEvent (our own terms:
 * productId + units) and nothing else. No XML class, SupplierSku,
 * PackSize, or LegacySupply status code is visible from here.
 */
@Component
class SupplierDeliveryRestockListener {

    private static final Logger log = LoggerFactory.getLogger(SupplierDeliveryRestockListener.class);

    private final InventoryService inventoryService;

    SupplierDeliveryRestockListener(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @EventListener
    void onDelivered(SupplierOrderDeliveredEvent event) {
        inventoryService.restock(event.productId(), event.units());
        log.info("Restocked {} units of {} following supplier order #{} delivery",
                event.units(), event.productId(), event.supplierOrderId());
    }
}
