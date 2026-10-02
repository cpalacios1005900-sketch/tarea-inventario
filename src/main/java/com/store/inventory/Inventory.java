package com.store.inventory;

import com.store.inventory.api.InventoryService;
import com.store.inventory.api.StockAlertListener;
import java.time.Clock;

/**
 * Entry point used by our automated tests. Keep this signature exactly as it is,
 * and build your implementation here.
 *
 * <p>This class only wires the pieces together. The behaviour lives in
 * {@link InMemoryInventoryService}, and the per-category rules in {@link CategoryPolicies}.
 */
public final class Inventory {

    /** Purchasing is warned when a product has this many available units or fewer. */
    static final int LOW_STOCK_THRESHOLD = 5;

    private Inventory() {
    }

    public static InventoryService create(Clock clock, StockAlertListener alertListener) {
        return new InMemoryInventoryService(
                clock, alertListener, CategoryPolicies::forCategory, LOW_STOCK_THRESHOLD);
    }
}
