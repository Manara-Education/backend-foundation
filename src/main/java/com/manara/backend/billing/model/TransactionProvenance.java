package com.manara.backend.billing.model;

/** Where a transaction's facts come from. Only {@link #LIVE} is money received. See V20. */
public enum TransactionProvenance {
    LIVE,
    SIMULATED,
    LEGACY
}
