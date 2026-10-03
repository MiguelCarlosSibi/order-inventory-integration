package edu.cit.sibi.channel;

import edu.cit.sibi.inventory.InventoryService;
import edu.cit.sibi.inventory.dto.InventoryItem;
import edu.cit.sibi.supplier.SupplierGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Task 2 (go live): publishes our products as Tiangge listings, each naming
 * the LegacySupply SupplierSku we restock it from (via SupplierGateway),
 * then publishes current stock. Runs once at startup, after the first
 * heartbeat (@Order(2)). If Tiangge is unreachable, it keeps trying every
 * 5 seconds until the listings are accepted, so a bad moment at startup
 * can't leave the shop dark.
 */
@Component
class ListingAndStockPublisher {

    private static final Logger log = LoggerFactory.getLogger(ListingAndStockPublisher.class);

    private final InventoryService inventoryService;
    private final SupplierGateway supplierGateway;
    private final TiangeClient client;
    private final StockSyncListener stockSync;
    private final AtomicBoolean published = new AtomicBoolean(false);

    ListingAndStockPublisher(InventoryService inventoryService, SupplierGateway supplierGateway,
                             TiangeClient client, StockSyncListener stockSync) {
        this.inventoryService = inventoryService;
        this.supplierGateway = supplierGateway;
        this.client = client;
        this.stockSync = stockSync;
    }

    /** True once Tiangge has accepted our listings. */
    boolean listingsPublished() {
        return published.get();
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(2)
    void publishOnStartup() {
        tryPublish();
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    void retryUntilPublished() {
        if (!published.get()) {
            tryPublish();
        }
    }

    private synchronized void tryPublish() {
        if (published.get()) {
            return;
        }
        List<InventoryItem> items = inventoryService.getAllItems();

        List<ListingDto> listings = items.stream()
                .map(item -> supplierGateway.supplierSkuFor(item.productId())
                        .map(sku -> new ListingDto(item.productId(), item.name(), sku))
                        .orElse(null))
                .filter(Objects::nonNull)
                .toList();

        if (listings.isEmpty()) {
            log.error("No products have a LegacySupply SupplierSku mapping - cannot publish any Tiangge listings.");
            return;
        }

        try {
            client.publishListings(listings);
        } catch (RuntimeException e) {
            log.warn("Could not publish Tiangge listings yet, will retry in 5 s: {}", e.getMessage());
            return;
        }
        published.set(true);
        log.info("Published {} Tiangge listing(s): {}", listings.size(),
                listings.stream().map(ListingDto::sellerSku).toList());

        // Stock goes through the same retrying path as every other stock update.
        stockSync.publishAll();
    }
}