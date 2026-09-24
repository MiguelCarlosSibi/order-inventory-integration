package edu.cit.sibi.supplier;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Maps OUR inventory product id to LegacySupply's SupplierSku + PackSize.
 * <p>
 * Real values measured 2026-09-24 via GET /catalog for this project's
 * ClientId — see INTEGRATION.md for the same mapping table plus the
 * discovery steps that produced it.
 */
@Component
class ProductCatalogMapping {

    private record Mapping(String supplierSku, int packSize) {
    }

    // productId -> LegacySupply SupplierSku / PackSize
    // Measured 2026-09-24 via GET /catalog for ClientId 17-0551-337.
    private static final Map<String, Mapping> MAPPINGS = Map.of(
            "P100", new Mapping("SGU-4425", 12),   // Wireless Mouse -> WIRELESS MOUSE 2.4GHZ
            "P200", new Mapping("SGU-8430", 24),   // Mechanical Keyboard -> KEYBOARD MECH TKL
            "P300", new Mapping("SGU-7316", 20)    // USB-C Hub -> USB HUB 4-PORT
    );

    Optional<String> supplierSkuFor(String productId) {
        return Optional.ofNullable(MAPPINGS.get(productId)).map(Mapping::supplierSku);
    }

    /**
     * Converts a units-needed figure to whole LegacySupply cases, rounding
     * up — the ACL's unit conversion, per Part C ("rounding up").
     */
    int unitsToCases(String productId, int units) {
        Mapping mapping = MAPPINGS.get(productId);
        if (mapping == null) {
            throw new IllegalArgumentException("No LegacySupply mapping for product " + productId);
        }
        int packSize = Math.max(1, mapping.packSize());
        return (units + packSize - 1) / packSize; // ceiling division
    }

    /** Units that physically arrive when this many cases are delivered. */
    int casesToUnits(String productId, int cases) {
        Mapping mapping = MAPPINGS.get(productId);
        if (mapping == null) {
            throw new IllegalArgumentException("No LegacySupply mapping for product " + productId);
        }
        return cases * Math.max(1, mapping.packSize());
    }
}