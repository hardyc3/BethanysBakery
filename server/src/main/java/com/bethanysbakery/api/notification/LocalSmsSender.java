package com.bethanysbakery.api.notification;

import com.bethanysbakery.api.notification.NotificationMessages.DeliveryResult;
import com.bethanysbakery.api.notification.NotificationMessages.SmsMessage;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({"local", "dummy"})
public class LocalSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(LocalSmsSender.class);

    @Override
    public DeliveryResult send(SmsMessage message) {
        log.info("[LOCAL SMS] to={} body={}", mask(message.to()), message.body());
        return DeliveryResult.sent("local-sms-" + UUID.randomUUID());
    }

    static String mask(String destination) {
        if (destination == null || destination.length() < 4) {
            return "***";
        }
        return "***" + destination.substring(destination.length() - 4);
    }
}
