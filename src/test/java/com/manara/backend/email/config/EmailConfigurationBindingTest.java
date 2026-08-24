package com.manara.backend.email.config;

import com.manara.backend.email.provider.resend.ResendConfiguration;
import com.manara.backend.email.provider.resend.ResendProperties;
import com.resend.Resend;
import com.resend.services.emails.Emails;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the email feature's configuration binds and wires, without needing a database or Redis.
 */
class EmailConfigurationBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(EmailConfiguration.class, ResendConfiguration.class);

    @Test
    void bindsEmailAndResendProperties() {
        contextRunner
                .withPropertyValues(
                        "email.from.address=no-reply@manara.com",
                        "email.from.name=Manara",
                        "email.reply-to=support@manara.com",
                        "resend.api-key=re_test_key")
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    EmailProperties email = context.getBean(EmailProperties.class);
                    assertThat(email.from().address()).isEqualTo("no-reply@manara.com");
                    assertThat(email.from().formatted()).isEqualTo("Manara <no-reply@manara.com>");
                    assertThat(email.replyTo()).isEqualTo("support@manara.com");

                    assertThat(context.getBean(ResendProperties.class).hasApiKey()).isTrue();
                    assertThat(context).hasSingleBean(Resend.class);
                    assertThat(context).hasSingleBean(Emails.class);
                });
    }

    /**
     * A missing key must not stop the application from starting — only the email path degrades, and
     * it does so with an explicit error rather than silently dropping mail.
     */
    @Test
    void startsWithoutAnApiKey() {
        contextRunner
                .withPropertyValues(
                        "email.from.address=no-reply@manara.com",
                        "email.from.name=Manara",
                        "email.reply-to=support@manara.com",
                        "resend.api-key=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ResendProperties.class).hasApiKey()).isFalse();
                });
    }
}
