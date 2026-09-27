package com.manara.backend.billing.dto;

import java.math.BigDecimal;

/** Confirmed money received in one currency. Currencies are never added together. */
public record CurrencyTotalResponse(String currency, BigDecimal amount, long count) {
}
