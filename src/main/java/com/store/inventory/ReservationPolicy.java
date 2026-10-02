package com.store.inventory;

import java.time.Duration;
import java.util.Objects;

/**
 * Business rules that a product category imposes on a reservation: how long the customer has to pay
 * and how many units a single order may contain.
 */
record ReservationPolicy(Duration holdTime, int maxUnitsPerOrder) {

    private static final int NO_LIMIT = Integer.MAX_VALUE;

    ReservationPolicy {
        Objects.requireNonNull(holdTime, "holdTime");
        if (holdTime.isZero() || holdTime.isNegative()) {
            throw new IllegalArgumentException("holdTime must be positive, was " + holdTime);
        }
        if (maxUnitsPerOrder <= 0) {
            throw new IllegalArgumentException("maxUnitsPerOrder must be positive, was " + maxUnitsPerOrder);
        }
    }

    static ReservationPolicy unlimited(Duration holdTime) {
        return new ReservationPolicy(holdTime, NO_LIMIT);
    }

    static ReservationPolicy limitedTo(Duration holdTime, int maxUnitsPerOrder) {
        return new ReservationPolicy(holdTime, maxUnitsPerOrder);
    }

    boolean allows(int quantity) {
        return quantity <= maxUnitsPerOrder;
    }
}
