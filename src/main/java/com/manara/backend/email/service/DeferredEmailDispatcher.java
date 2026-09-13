package com.manara.backend.email.service;

import com.manara.backend.email.exception.EmailDeliveryException;
import com.manara.backend.email.model.EmailMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Sends an email after the transaction that decided to send it has committed, off the request
 * thread, and never lets the outcome reach the caller.
 *
 * <p>Both halves of that matter, and both are about the same thing: an anonymous caller must not be
 * able to tell from a recovery request whether the address they submitted belongs to an account.
 *
 * <p><strong>Off the request thread</strong>, because the provider call is an outbound HTTP request.
 * Made inline, it costs a few hundred milliseconds when there is an account to write to and nothing
 * at all when there is not — a difference a caller can measure, and one that survives every
 * unification of status codes and message text.
 *
 * <p><strong>Never reaching the caller</strong>, because a provider outage otherwise answers
 * existing accounts with a 503 and absent ones with the ordinary success. That turns an outage into
 * a working oracle, and it is exactly when nobody is looking closely.
 *
 * <p><strong>After commit</strong>, because before it the row is not yet real. A code emailed by a
 * transaction that then rolls back is a code the database has never heard of, and the person holding
 * it is told to enter something that cannot work. Outside a transaction — the unit tests — there is
 * nothing to wait for and the send happens inline, which is what an after-commit hook means when
 * there is no commit coming.
 *
 * <p>What this is not: a queue. Delivery is attempted once. If the provider is down the code is not
 * delivered and the user asks for another one, which is the same outcome the synchronous version had
 * — minus the disclosure. A durable outbox is a larger piece of work and is recorded as such rather
 * than half-built here.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeferredEmailDispatcher {

    private final EmailService emailService;

    @Qualifier("emailDispatchExecutor")
    private final Executor executor;

    /**
     * Queues {@code message} for delivery once the current transaction commits.
     *
     * <p>Returns immediately and reports nothing. A caller that needs to know whether delivery
     * succeeded must not use this method — but on the paths this exists for, a caller that could
     * know would be the disclosure.
     */
    public void dispatchAfterCommit(EmailMessage message) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            send(message);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                submit(message);
            }
        });
    }

    private void submit(EmailMessage message) {
        try {
            executor.execute(() -> send(message));
        } catch (RejectedExecutionException e) {
            // The queue is full: more mail is being asked for than the provider can be given. The
            // request that caused it has already been answered and must not now be failed, so this
            // is recorded and dropped. A sustained rejection rate is the signal that the sending
            // budget, not this class, needs attention.
            log.error("Email dispatch rejected, queue is full; message dropped", e);
        }
    }

    private void send(EmailMessage message) {
        try {
            emailService.send(message);
        } catch (EmailDeliveryException e) {
            // DefaultEmailService masks the recipient before it reaches a log.
            log.error("Email delivery failed after commit", e);
        }
    }
}
