package com.store.inventory;

import com.store.inventory.api.Reservation;
import java.time.Instant;
import java.util.Comparator;

/**
 * A reservation tracked by the service. Not thread-safe by itself: it is only touched while the
 * service lock is held.
 */
final class Hold {

    /** Ordering used to find expired holds quickly. The order id breaks ties so it is a total order. */
    static final Comparator<Hold> BY_EXPIRY = Comparator.comparing(Hold::expiresAt).thenComparing(Hold::orderId);

    private final String orderId;
    private final String sku;
    private final int quantity;
    private final Instant expiresAt;
    private boolean confirmed;

    Hold(String orderId, String sku, int quantity, Instant expiresAt) {
        this.orderId = orderId;
        this.sku = sku;
        this.quantity = quantity;
        this.expiresAt = expiresAt;
    }

    String orderId() {
        return orderId;
    }

    String sku() {
        return sku;
    }

    int quantity() {
        return quantity;
    }

    Instant expiresAt() {
        return expiresAt;
    }

    boolean isConfirmed() {
        return confirmed;
    }

    void markConfirmed() {
        confirmed = true;
    }

    /** True when a retried request carries exactly the same payload as the one that created this hold. */
    boolean matches(String otherSku, int otherQuantity) {
        return sku.equals(otherSku) && quantity == otherQuantity;
    }

    Reservation toReservation() {
        return new Reservation(orderId, sku, quantity, expiresAt);
    }
}
