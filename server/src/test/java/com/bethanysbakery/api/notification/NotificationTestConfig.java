package com.bethanysbakery.api.notification;

import com.bethanysbakery.api.notification.NotificationMessages.DeliveryResult;
import com.bethanysbakery.api.notification.NotificationMessages.EmailMessage;
import com.bethanysbakery.api.notification.NotificationMessages.PushMessage;
import com.bethanysbakery.api.notification.NotificationMessages.SmsMessage;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Fixed clock and in-memory providers for deterministic S6 integration tests. */
@TestConfiguration
public class NotificationTestConfig {

    static final AtomicReference<Instant> NOW =
        new AtomicReference<>(Instant.parse("2026-11-18T15:00:00Z"));
    static final RecordingEmailSender EMAIL = new RecordingEmailSender();
    static final RecordingSmsSender SMS = new RecordingSmsSender();
    static final RecordingPushSender PUSH = new RecordingPushSender();
    static final AtomicInteger PAID_EMAIL_FAILURES = new AtomicInteger();
    static final AtomicBoolean PUSH_NOT_CONFIGURED = new AtomicBoolean();

    static void reset(Instant now) {
        NOW.set(now);
        EMAIL.sent.clear();
        SMS.sent.clear();
        PUSH.sent.clear();
        PUSH.rejectedTokens.clear();
        PAID_EMAIL_FAILURES.set(0);
        PUSH_NOT_CONFIGURED.set(false);
    }

    @Bean
    @Primary
    Clock notificationTestClock() {
        return new Clock() {
            @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return NOW.get(); }
            @Override public long millis() { return NOW.get().toEpochMilli(); }
        };
    }

    @Bean
    @Primary
    EmailSender recordingEmailSender() { return EMAIL; }

    @Bean
    @Primary
    SmsSender recordingSmsSender() { return SMS; }

    @Bean
    @Primary
    PushSender recordingPushSender() { return PUSH; }

    static final class RecordingEmailSender implements EmailSender {
        final List<EmailMessage> sent = new ArrayList<>();
        @Override public DeliveryResult send(EmailMessage message) {
            sent.add(message);
            if (message.subject().contains("Paid order") && PAID_EMAIL_FAILURES.getAndDecrement() > 0) {
                return DeliveryResult.failed("TEST_EMAIL_FAILURE");
            }
            return DeliveryResult.sent("email-" + UUID.randomUUID());
        }
    }

    static final class RecordingSmsSender implements SmsSender {
        final List<SmsMessage> sent = new ArrayList<>();
        @Override public DeliveryResult send(SmsMessage message) {
            sent.add(message);
            return DeliveryResult.sent("sms-" + UUID.randomUUID());
        }
    }

    static final class RecordingPushSender implements PushSender {
        final List<PushMessage> sent = new ArrayList<>();
        final java.util.Set<String> rejectedTokens = new java.util.HashSet<>();
        @Override public DeliveryResult send(PushMessage message) {
            if (PUSH_NOT_CONFIGURED.get()) {
                throw new ProviderNotConfiguredException("FCM");
            }
            if (rejectedTokens.contains(message.token())) {
                return DeliveryResult.failed("FCM_INVALID_TOKEN");
            }
            sent.add(message);
            return DeliveryResult.sent("push-" + UUID.randomUUID());
        }
    }
}
