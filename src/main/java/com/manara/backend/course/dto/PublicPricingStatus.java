package com.manara.backend.course.dto;

/**
 * Whether a public offer can state its price, told apart from what kind of offer it is.
 *
 * <p>Read together with {@code accessType}, never instead of it. The two exist separately because
 * the one mistake a public price list must not make is turning "we cannot say what this costs" into
 * "this is free": a purchase course with no stored price and a subscription whose plans were all
 * retired are paid courses with a data problem, and a client that inferred FREE from a missing or
 * zero amount would advertise them as free. This field says which case it is, so no client has to
 * infer anything from an amount.
 *
 * <p>{@code accessType = FREE} if and only if this is {@link #FREE}.
 */
public enum PublicPricingStatus {

    /** Free by the course's access type. No currency, no amount. */
    FREE,

    /**
     * Paid, with a truthful price: a purchase price above zero, or at least one active plan whose
     * price and duration are above zero.
     */
    PRICED,

    /**
     * Paid, but no truthful price can be stated. Shown as "price unavailable" — never as free and
     * never as 0.
     */
    UNAVAILABLE
}
