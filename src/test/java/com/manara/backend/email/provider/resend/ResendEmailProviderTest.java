package com.manara.backend.email.provider.resend;

import com.manara.backend.email.config.EmailProperties;
import com.manara.backend.email.exception.EmailDeliveryException;
import com.manara.backend.email.model.EmailMessage;
import com.manara.backend.email.model.EmailSendResult;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.Emails;
import com.resend.services.emails.model.CreateEmailOptions;
import com.resend.services.emails.model.CreateEmailResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ResendEmailProviderTest {

    private static final EmailProperties EMAIL_PROPERTIES = new EmailProperties(
            new EmailProperties.Sender("no-reply@manara.com", "Manara"),
            "support@manara.com");

    @Mock
    private Emails emails;

    @Captor
    private ArgumentCaptor<CreateEmailOptions> optionsCaptor;

    private ResendEmailProvider provider;

    @BeforeEach
    void setUp() {
        provider = new ResendEmailProvider(emails, EMAIL_PROPERTIES, new ResendProperties("re_test_key"));
    }

    private static EmailMessage message() {
        return EmailMessage.builder()
                .to("student@manara.com")
                .subject("Your Manara verification code")
                .html("<p>123456</p>")
                .text("123456")
                .build();
    }

    @Test
    void mapsMessageOntoTheProviderRequestAndAppliesConfiguredSender() throws Exception {
        given(emails.send(any(CreateEmailOptions.class))).willReturn(new CreateEmailResponse("msg-1"));

        EmailSendResult result = provider.send(message());

        CreateEmailOptions options = captureSentOptions();
        assertThat(options.getFrom()).isEqualTo("Manara <no-reply@manara.com>");
        assertThat(options.getTo()).containsExactly("student@manara.com");
        assertThat(options.getSubject()).isEqualTo("Your Manara verification code");
        assertThat(options.getHtml()).isEqualTo("<p>123456</p>");
        assertThat(options.getText()).isEqualTo("123456");
        assertThat(options.getReplyTo()).containsExactly("support@manara.com");
        assertThat(result.messageId()).isEqualTo("msg-1");
    }

    @Test
    void omitsOptionalFieldsWhenAbsent() throws Exception {
        given(emails.send(any(CreateEmailOptions.class))).willReturn(new CreateEmailResponse("msg-2"));
        ResendEmailProvider withoutReplyTo = new ResendEmailProvider(
                emails,
                new EmailProperties(new EmailProperties.Sender("no-reply@manara.com", null), null),
                new ResendProperties("re_test_key"));

        withoutReplyTo.send(EmailMessage.builder()
                .to("student@manara.com")
                .subject("Subject")
                .html("<p>Body</p>")
                .build());

        CreateEmailOptions options = captureSentOptions();
        assertThat(options.getFrom()).isEqualTo("no-reply@manara.com");
        assertThat(options.getText()).isNull();
        assertThat(options.getReplyTo()).isNull();
    }

    @Test
    void perMessageReplyToOverridesTheDefault() throws Exception {
        given(emails.send(any(CreateEmailOptions.class))).willReturn(new CreateEmailResponse("msg-3"));

        provider.send(EmailMessage.builder()
                .to("student@manara.com")
                .subject("Subject")
                .html("<p>Body</p>")
                .replyTo("instructors@manara.com")
                .build());

        assertThat(captureSentOptions().getReplyTo()).containsExactly("instructors@manara.com");
    }

    @Test
    void mapsInlineImagesOntoResendAttachmentsWithContentIds() throws Exception {
        given(emails.send(any(CreateEmailOptions.class))).willReturn(new CreateEmailResponse("msg-4"));

        provider.send(EmailMessage.builder()
                .to("student@manara.com")
                .subject("Subject")
                .html("<img src=\"cid:brand-logo\">")
                .inlineImages(java.util.List.of(
                        new com.manara.backend.email.model.InlineImage(
                                "brand-logo", "logo.png", "image/png", "aGVsbG8=")))
                .build());

        var attachments = captureSentOptions().getAttachments();
        assertThat(attachments).hasSize(1);
        assertThat(attachments.getFirst().getContentId()).isEqualTo("brand-logo");
        assertThat(attachments.getFirst().getFileName()).isEqualTo("logo.png");
        assertThat(attachments.getFirst().getContentType()).isEqualTo("image/png");
        assertThat(attachments.getFirst().getContent()).isEqualTo("aGVsbG8=");
    }

    @Test
    void sendsNoAttachmentsWhenThereAreNoInlineImages() throws Exception {
        given(emails.send(any(CreateEmailOptions.class))).willReturn(new CreateEmailResponse("msg-5"));

        provider.send(message());

        assertThat(captureSentOptions().getAttachments()).isNull();
    }

    @Test
    void mapsProviderRejectionOntoEmailDeliveryException() throws Exception {
        ResendException rejection = new ResendException("validation_error", 422, "Domain is not verified");
        given(emails.send(any(CreateEmailOptions.class))).willThrow(rejection);

        assertThatThrownBy(() -> provider.send(message()))
                .isInstanceOf(EmailDeliveryException.class)
                .hasFieldOrPropertyWithValue("messageCode", "error.email.deliveryFailed")
                .hasCause(rejection);
    }

    /** The SDK surfaces connect/read timeouts as a bare RuntimeException, not a ResendException. */
    @Test
    void mapsTransportFailuresOntoEmailDeliveryException() throws Exception {
        RuntimeException transportFailure = new RuntimeException(new java.io.IOException("timeout"));
        given(emails.send(any(CreateEmailOptions.class))).willThrow(transportFailure);

        assertThatThrownBy(() -> provider.send(message()))
                .isInstanceOf(EmailDeliveryException.class)
                .hasCause(transportFailure);
    }

    @Test
    void failsFastAndNeverCallsTheProviderWhenTheApiKeyIsMissing() {
        ResendEmailProvider unconfigured =
                new ResendEmailProvider(emails, EMAIL_PROPERTIES, new ResendProperties("  "));

        assertThatThrownBy(() -> unconfigured.send(message()))
                .isInstanceOf(EmailDeliveryException.class)
                .hasFieldOrPropertyWithValue("messageCode", "error.email.notConfigured");
        verifyNoInteractions(emails);
    }

    private CreateEmailOptions captureSentOptions() throws Exception {
        verify(emails).send(optionsCaptor.capture());
        return optionsCaptor.getValue();
    }
}
