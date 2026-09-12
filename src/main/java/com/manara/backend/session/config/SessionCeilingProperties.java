package com.manara.backend.session.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How many sessions one account may hold at once.
 *
 * <p>Five unless {@code app.session.maximum-per-account} says otherwise. Going past it does not
 * refuse the sign-in: it ends the account's oldest session, so a device the user has lost track of
 * can never lock them out — the session that goes is always the one opened longest ago. See
 * {@code SessionCeiling}.
 */
@ConfigurationProperties(prefix = "app.session")
public record SessionCeilingProperties(Integer maximumPerAccount) {

    private static final int DEFAULT_MAXIMUM_PER_ACCOUNT = 5;

    public SessionCeilingProperties {
        maximumPerAccount = maximumPerAccount == null ? DEFAULT_MAXIMUM_PER_ACCOUNT : maximumPerAccount;
        // Refused at startup rather than read as "no limit": zero would end every session the moment
        // it was opened, and a negative number means nothing at all.
        if (maximumPerAccount < 1) {
            throw new IllegalArgumentException(
                    "app.session.maximum-per-account must be at least 1, was " + maximumPerAccount);
        }
    }
}
