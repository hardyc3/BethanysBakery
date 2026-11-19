package com.bethanysbakery.api.auth.otp;

import com.bethanysbakery.api.auth.AuthException;
import com.bethanysbakery.api.domain.auth.OtpChannel;
import com.bethanysbakery.api.notification.EmailSender;
import com.bethanysbakery.api.notification.NotificationMessages.DeliveryResult;
import com.bethanysbakery.api.notification.NotificationMessages.EmailMessage;
import com.bethanysbakery.api.notification.NotificationMessages.SmsMessage;
import com.bethanysbakery.api.notification.ProviderNotConfiguredException;
import com.bethanysbakery.api.notification.SmsSender;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Sends OTP codes through the S6 Mailgun/Textbee providers outside the local profile. */
@Component
@Profile({"staging", "prod"})
public class ProviderOtpSender implements OtpSender {

    private static final String SUBJECT = "Your Bethany's Bakery sign-in code";

    private final EmailSender emailSender;
    private final SmsSender smsSender;

    public ProviderOtpSender(EmailSender emailSender, SmsSender smsSender) {
        this.emailSender = emailSender;
        this.smsSender = smsSender;
    }

    @Override
    public void send(OtpChannel channel, String destination, String code, Instant expiresAt) {
        String body = "Your Bethany's Bakery code is " + code + ". It expires at " + expiresAt + ".";
        DeliveryResult result;
        try {
            result = switch (channel) {
                case EMAIL -> emailSender.send(new EmailMessage(destination, SUBJECT, body, body));
                case SMS -> smsSender.send(new SmsMessage(destination, body));
            };
        } catch (ProviderNotConfiguredException ex) {
            throw AuthException.notConfigured(ex.getMessage());
        }
        if (!result.success()) {
            throw new AuthException(HttpStatus.SERVICE_UNAVAILABLE, "OTP_DELIVERY_FAILED",
                "OTP delivery provider failed");
        }
    }
}
