package com.store.inventory;

import com.store.inventory.api.ProductCategory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;

/**
 * Stock of a single product.
 *
 * <ul>
 *   <li>{@code onHand}: units not sold yet (in the warehouse, whether or not they are reserved).</li>
 *   <li>{@code held}: units covered by pending (unconfirmed, unexpired) reservations.</li>
 *   <li>{@code available = onHand - held}.</li>
 * </ul>
 *
 * Not thread-safe by itself: the owning service serialises every access.
 */
final class ProductStock {

    private final String sku;
    private ProductCategory category;
    private int onHand;
    private int held;
    private boolean lowStockAlerted;
    private final NavigableSet<Hold> pending = new TreeSet<>(Hold.BY_EXPIRY);

    ProductStock(String sku, ProductCategory category) {
        this.sku = sku;
        this.category = category;
    }

    ProductCategory category() {
        return category;
    }

    /** Only affects reservations made from now on; existing ones keep the expiry they were given. */
    void changeCategory(ProductCategory newCategory) {
        this.category = newCategory;
    }

    int available() {
        return onHand - held;
    }

    void addStock(int quantity) {
        try {
            onHand = Math.addExact(onHand, quantity);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Stock for " + sku + " would overflow", e);
        }
        // A restock starts a new "low stock" cycle: purchasing must be told again if it runs low later.
        lowStockAlerted = false;
    }

    void hold(Hold hold) {
        pending.add(hold);
        held += hold.quantity();
    }

    /** Turns a pending hold into a sale: the units leave the warehouse for good. */
    void confirm(Hold hold) {
        pending.remove(hold);
        held -= hold.quantity();
        onHand -= hold.quantity();
        hold.markConfirmed();
    }

    /** Releases every pending hold whose time to pay is over at {@code now}, and returns them. */
    List<Hold> releaseExpired(Instant now) {
        List<Hold> released = new ArrayList<>();
        while (!pending.isEmpty() && !now.isBefore(pending.first().expiresAt())) {
            Hold expired = pending.pollFirst();
            held -= expired.quantity();
            released.add(expired);
        }
        return released;
    }

    /**
     * Re-arms the low stock alert once the product is comfortably stocked again (for example because
     * reservations expired), so a later drop is reported instead of being swallowed.
     */
    void rearmLowStockAlertIfAbove(int threshold) {
        if (available() > threshold) {
            lowStockAlerted = false;
        }
    }

    /**
     * Returns true exactly once per low stock cycle: when the product is at or below the threshold and
     * nobody has been told yet.
     */
    boolean claimLowStockAlert(int threshold) {
        if (available() > threshold || lowStockAlerted) {
            return false;
        }
        lowStockAlerted = true;
        return true;
    }
}
