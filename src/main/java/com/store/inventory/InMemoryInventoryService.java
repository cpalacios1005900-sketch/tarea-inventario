package com.store.inventory;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.OrderLimitExceededException;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import com.store.inventory.api.StockAlertListener;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * In-memory implementation of the reservation service.
 *
 * <h2>Behaviour worth knowing</h2>
 * <ul>
 *   <li><b>Idempotent:</b> the mobile app resends orders when the connection is slow, so repeating
 *       {@code reserve} with the same payload returns the original reservation (it neither reserves
 *       twice nor extends the deadline) and repeating {@code confirm} is a no-op.</li>
 *   <li><b>Lazy expiry:</b> there is no background thread. Expired reservations are released against the
 *       injected {@link Clock} whenever the product is touched, which keeps everything deterministic.</li>
 *   <li><b>Thread-safe:</b> a single lock serialises all state changes. Alerts are delivered after the
 *       lock is released, so a slow or failing channel can never block or break a reservation.</li>
 * </ul>
 */
final class InMemoryInventoryService implements InventoryService {

    private static final Logger LOG = System.getLogger(InMemoryInventoryService.class.getName());

    private final Clock clock;
    private final StockAlertListener alertListener;
    private final Function<ProductCategory, ReservationPolicy> policies;
    private final int lowStockThreshold;

    private final ReentrantLock lock = new ReentrantLock();
    // Both maps are guarded by `lock`.
    private final Map<String, ProductStock> products = new HashMap<>();
    /** Orders with a pending or confirmed hold. Expired holds are removed so the map does not grow with them. */
    private final Map<String, Hold> orders = new HashMap<>();

    InMemoryInventoryService(
            Clock clock,
            StockAlertListener alertListener,
            Function<ProductCategory, ReservationPolicy> policies,
            int lowStockThreshold) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.alertListener = Objects.requireNonNull(alertListener, "alertListener");
        this.policies = Objects.requireNonNull(policies, "policies");
        if (lowStockThreshold < 0) {
            throw new IllegalArgumentException("lowStockThreshold must not be negative");
        }
        this.lowStockThreshold = lowStockThreshold;
    }

    @Override
    public void registerProduct(String sku, ProductCategory category) {
        requireText(sku, "sku");
        Objects.requireNonNull(category, "category");
        policyFor(category); // fail fast if a category has no rules
        lock.lock();
        try {
            ProductStock existing = products.get(sku);
            if (existing == null) {
                products.put(sku, new ProductStock(sku, category));
            } else {
                existing.changeCategory(category);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void addStock(String sku, int quantity) {
        requireText(sku, "sku");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive, was " + quantity);
        }
        lock.lock();
        try {
            ProductStock stock = products.get(sku);
            if (stock == null) {
                throw new IllegalArgumentException("Product " + sku + " is not registered");
            }
            stock.addStock(quantity);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Reservation reserve(String orderId, String sku, int quantity) {
        requireText(orderId, "orderId");
        requireText(sku, "sku");
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive, was " + quantity);
        }

        ReserveResult result;
        lock.lock();
        try {
            result = reserveLocked(orderId, sku, quantity);
        } finally {
            lock.unlock();
        }

        // Outside the lock on purpose: see class documentation.
        if (result.lowStockAlert()) {
            publishLowStock(sku, result.availableUnits());
        }
        return result.reservation();
    }

    @Override
    public void confirm(String orderId) {
        requireText(orderId, "orderId");
        lock.lock();
        try {
            Hold hold = currentHold(orderId, clock.instant());
            if (hold == null) {
                throw new IllegalStateException("Order " + orderId + " has no active reservation");
            }
            if (hold.isConfirmed()) {
                return; // payment notifications can be delivered more than once
            }
            products.get(hold.sku()).confirm(hold);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int available(String sku) {
        Objects.requireNonNull(sku, "sku");
        lock.lock();
        try {
            ProductStock stock = products.get(sku);
            if (stock == null) {
                return 0;
            }
            refresh(stock, clock.instant());
            return stock.available();
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------------------------------------------

    private ReserveResult reserveLocked(String orderId, String sku, int quantity) {
        Instant now = clock.instant();

        Hold existing = currentHold(orderId, now);
        if (existing != null) {
            if (!existing.matches(sku, quantity)) {
                throw new IllegalArgumentException(
                        "Order " + orderId + " already exists with a different product or quantity");
            }
            return ReserveResult.replayOf(existing.toReservation());
        }

        ProductStock stock = products.get(sku);
        if (stock == null) {
            throw new InsufficientStockException(sku, quantity, 0);
        }

        ReservationPolicy policy = policyFor(stock.category());
        if (!policy.allows(quantity)) {
            throw new OrderLimitExceededException(sku, quantity, policy.maxUnitsPerOrder());
        }

        refresh(stock, now);
        if (quantity > stock.available()) {
            throw new InsufficientStockException(sku, quantity, stock.available());
        }

        Hold hold = new Hold(orderId, sku, quantity, now.plus(policy.holdTime()));
        stock.hold(hold);
        orders.put(orderId, hold);
        boolean lowStock = stock.claimLowStockAlert(lowStockThreshold);
        return new ReserveResult(hold.toReservation(), lowStock, stock.available());
    }

    /**
     * Returns the pending-or-confirmed hold of an order, or null if it never existed or its time to pay
     * ran out. Expired holds of the product are released as a side effect.
     */
    private Hold currentHold(String orderId, Instant now) {
        Hold hold = orders.get(orderId);
        if (hold != null && !hold.isConfirmed()) {
            refresh(products.get(hold.sku()), now);
            hold = orders.get(orderId);
        }
        return hold;
    }

    /** Releases expired holds and re-arms the low stock alert if the product recovered. */
    private void refresh(ProductStock stock, Instant now) {
        for (Hold expired : stock.releaseExpired(now)) {
            orders.remove(expired.orderId(), expired);
        }
        stock.rearmLowStockAlertIfAbove(lowStockThreshold);
    }

    private ReservationPolicy policyFor(ProductCategory category) {
        return Objects.requireNonNull(policies.apply(category), () -> "No reservation policy for " + category);
    }

    private void publishLowStock(String sku, int availableUnits) {
        try {
            alertListener.onLowStock(sku, availableUnits);
        } catch (RuntimeException e) {
            // A broken notification channel must not undo or fail a customer's reservation.
            LOG.log(Level.WARNING, "Low stock alert for " + sku + " could not be delivered", e);
        }
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private record ReserveResult(Reservation reservation, boolean lowStockAlert, int availableUnits) {

        static ReserveResult replayOf(Reservation reservation) {
            return new ReserveResult(reservation, false, 0);
        }
    }
}
