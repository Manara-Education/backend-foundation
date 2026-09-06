package com.manara.backend.payment.service;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.payment.dto.PaymentMethodRequest;
import com.manara.backend.payment.model.PaymentCharge;
import com.manara.backend.payment.model.PaymentReceipt;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A gateway that takes no money.
 *
 * <p><strong>This is a simulation.</strong> No provider is contacted, no instrument is authorised
 * and no funds move. It records that a learner asked to proceed, and issues a reference that the
 * entitlement and subscription rows store — so every grant in the database has a traceable origin
 * from the day a real provider replaces this class.
 *
 * <p>The {@code sim_} prefix on every reference is the point: no row created by this gateway can
 * ever be mistaken for one backed by an actual charge.
 *
 * <p><strong>It no longer inspects card details, because it no longer receives any.</strong> This
 * class used to validate a card number's length, a CVC's length and an expiry's shape. Since
 * nothing here authorises a payment, those checks proved nothing — but accepting the data meant
 * real primary account numbers and CVCs were transmitted to and held by a server with no acquirer
 * and no PCI DSS scope. The checks went when the fields did. The client-side form remains free to
 * validate whatever it collects; the difference is that it is no longer sent here.
 */
/*
 * Registered only in DEMONSTRATION mode.
 *
 * This bean returns a successful receipt for every charge without contacting anybody, and checkout
 * grants a paid entitlement from that receipt. In a demonstration that is the intended behaviour.
 * In a deployment that believes it is selling courses it is a way to be given a paid entitlement for
 * nothing -- and nothing in the code could previously tell those two deployments apart, because the
 * bean was registered unconditionally.
 *
 * The condition is what tells them apart. matchIfMissing = true keeps today's behaviour exactly as
 * it is: an existing deployment that sets nothing stays in demonstration mode and nothing changes
 * for it. What it can no longer do is quietly become a real-money deployment while still being
 * served by this class -- selecting LIVE removes this bean, and CommerceConfig then refuses to start
 * unless a real provider has taken its place.
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "manara.commerce.mode",
        havingValue = "DEMONSTRATION",
        matchIfMissing = true)
@RequiredArgsConstructor
public class SimulatedPaymentGateway implements PaymentGateway {

    private static final String REFERENCE_PREFIX = "sim_";

    private final Clock clock;

    @Override
    public PaymentReceipt charge(PaymentCharge charge, PaymentMethodRequest paymentMethod) {
        // A missing instrument is still refused. It is the learner's explicit "yes, proceed", and
        // it is the field a real provider's token will arrive in.
        if (paymentMethod == null) {
            throw new BusinessException("error.payment.required");
        }

        PaymentReceipt receipt = new PaymentReceipt(
                REFERENCE_PREFIX + UUID.randomUUID(),
                charge.amount(),
                LocalDateTime.now(clock));

        log.info("SIMULATED payment accepted - no money moved. reference={} amount={} idempotencyKey={} description={}",
                receipt.reference(), charge.amount(), charge.idempotencyKey(), charge.description());

        return receipt;
    }
}
