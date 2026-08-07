package com.manara.backend.email.service;

import com.manara.backend.email.exception.EmailDeliveryException;
import com.manara.backend.email.model.EmailMessage;
import com.manara.backend.email.model.EmailSendResult;
import com.manara.backend.email.provider.EmailProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DefaultEmailServiceTest {

    @Mock
    private EmailProvider emailProvider;

    @InjectMocks
    private DefaultEmailService emailService;

    private static EmailMessage message() {
        return EmailMessage.builder()
                .to("student@manara.com")
                .subject("Subject")
                .html("<p>Body</p>")
                .build();
    }

    @Test
    void delegatesToTheProviderAndReturnsItsResult() {
        EmailMessage message = message();
        given(emailProvider.send(message)).willReturn(new EmailSendResult("msg-1"));

        EmailSendResult result = emailService.send(message);

        assertThat(result.messageId()).isEqualTo("msg-1");
        verify(emailProvider).send(message);
    }

    @Test
    void propagatesDeliveryFailuresUnchanged() {
        EmailDeliveryException failure = new EmailDeliveryException("error.email.deliveryFailed");
        given(emailProvider.send(any())).willThrow(failure);

        assertThatThrownBy(() -> emailService.send(message()))
                .isSameAs(failure);
    }

    @Test
    void rejectsMessagesMissingRequiredFields() {
        assertThatThrownBy(() -> EmailMessage.builder().subject("s").html("<p>h</p>").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("to");

        assertThatThrownBy(() -> EmailMessage.builder().to("a@b.com").html("<p>h</p>").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subject");

        assertThatThrownBy(() -> EmailMessage.builder().to("a@b.com").subject("s").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("html");
    }

    /**
     * Architecture check: an unrelated email type goes through the same API, with no new method, no
     * enum switch, and no change to any provider.
     */
    @Test
    void sendsAnyKindOfEmailThroughTheSameApi() {
        EmailMessage welcome = EmailMessage.builder()
                .to("new.user@manara.com")
                .subject("Welcome to Manara")
                .html("<h1>Welcome</h1>")
                .text("Welcome to Manara")
                .build();
        given(emailProvider.send(welcome)).willReturn(new EmailSendResult("msg-2"));

        assertThat(emailService.send(welcome).messageId()).isEqualTo("msg-2");
    }
}
