package com.store.inventory;

import com.store.inventory.api.ProductCategory;
import java.time.Duration;

/**
 * Single place where the business rules of every {@link ProductCategory} live.
 *
 * <p><b>Adding a category:</b> add the constant to {@code ProductCategory} and then the compiler will
 * point here: the {@code switch} below has no {@code default} on purpose, so a category without rules
 * does not compile instead of silently getting the wrong behaviour in production.
 */
final class CategoryPolicies {

    private static final ReservationPolicy STANDARD = ReservationPolicy.unlimited(Duration.ofMinutes(15));
    // Paid by bank transfer, hence the long window.
    private static final ReservationPolicy PRE_ORDER = ReservationPolicy.unlimited(Duration.ofHours(24));
    private static final ReservationPolicy FLASH_SALE = ReservationPolicy.limitedTo(Duration.ofMinutes(5), 2);

    private CategoryPolicies() {
    }

    static ReservationPolicy forCategory(ProductCategory category) {
        return switch (category) {
            case STANDARD -> STANDARD;
            case PRE_ORDER -> PRE_ORDER;
            case FLASH_SALE -> FLASH_SALE;
        };
    }
}
