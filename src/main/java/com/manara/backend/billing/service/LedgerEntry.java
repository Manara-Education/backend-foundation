package com.manara.backend.billing.service;

import com.manara.backend.billing.model.BillingReceipt;
import com.manara.backend.billing.model.PaymentTransaction;

/** What recording a charge produced: the transaction, and its receipt when one was issued. */
public record LedgerEntry(PaymentTransaction transaction, BillingReceipt receipt) {
}
