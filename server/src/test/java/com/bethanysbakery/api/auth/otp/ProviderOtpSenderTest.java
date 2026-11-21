package com.bethanysbakery.api.auth.otp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bethanysbakery.api.auth.AuthException;
import com.bethanysbakery.api.domain.auth.OtpChannel;
import com.bethanysbakery.api.notification.EmailSender;
import com.bethanysbakery.api.notification.NotificationMessages.DeliveryResult;
import com.bethanysbakery.api.notification.NotificationMessages.EmailMessage;
import com.bethanysbakery.api.notification.NotificationMessages.SmsMessage;
import com.bethanysbakery.api.notification.ProviderNotConfiguredException;
import com.bethanysbakery.api.notification.SmsSender;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ProviderOtpSenderTest {

    private static final Instant EXPIRES = Instant.parse("2026-11-18T16:00:00Z");

    private final RecordingEmailSender email = new RecordingEmailSender();
    private final RecordingSmsSender sms = new RecordingSmsSender();
    private final ProviderOtpSender sender = new ProviderOtpSender(email, sms);

    @Test
    void sendsEmailOtpThroughMailgunProvider() {
        sender.send(OtpChannel.EMAIL, "buyer@example.com", "123456", EXPIRES);

        assertThat(email.message).isNotNull();
        assertThat(email.message.to()).isEqualTo("buyer@example.com");
        assertThat(email.message.subject()).contains("sign-in code");
        assertThat(email.message.textBody()).contains("123456", EXPIRES.toString());
        assertThat(sms.message).isNull();
    }

    @Test
    void sendsSmsOtpThroughTextbeeProvider() {
        sender.send(OtpChannel.SMS, "+15555550101", "654321", EXPIRES);

        assertThat(sms.message).isNotNull();
        assertThat(sms.message.to()).isEqualTo("+15555550101");
        assertThat(sms.message.body()).contains("654321", EXPIRES.toString());
        assertThat(email.message).isNull();
    }

    @Test
    void propagatesMissingProviderConfiguration() {
        email.failWithNotConfigured = true;

        assertThatThrownBy(() -> sender.send(OtpChannel.EMAIL, "buyer@example.com", "123456", EXPIRES))
            .isInstanceOfSatisfying(AuthException.class, ex -> {
                assertThat(ex.status().value()).isEqualTo(503);
                assertThat(ex.code()).isEqualTo("NOT_CONFIGURED");
            });
    }

    @Test
    void failsClosedWhenProviderRejectsDelivery() {
        sms.result = DeliveryResult.failed("Textbee rejected message");

        assertThatThrownBy(() -> sender.send(OtpChannel.SMS, "+15555550101", "123456", EXPIRES))
            .isInstanceOfSatisfying(AuthException.class, ex -> {
                assertThat(ex.status().value()).isEqualTo(503);
                assertThat(ex.code()).isEqualTo("OTP_DELIVERY_FAILED");
            });
    }

    private static class RecordingEmailSender implements EmailSender {
        private EmailMessage message;
        private boolean failWithNotConfigured;
        private DeliveryResult result = DeliveryResult.sent("mailgun-1");

        @Override
        public DeliveryResult send(EmailMessage message) {
            if (failWithNotConfigured) {
                throw new ProviderNotConfiguredException("Mailgun");
            }
            this.message = message;
            return result;
        }
    }

    private static class RecordingSmsSender implements SmsSender {
        private SmsMessage message;
        private DeliveryResult result = DeliveryResult.sent("textbee-1");

        @Override
        public DeliveryResult send(SmsMessage message) {
            this.message = message;
            return result;
        }
    }
}
