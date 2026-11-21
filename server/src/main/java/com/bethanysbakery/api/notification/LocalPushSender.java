package com.bethanysbakery.api.notification;

import com.bethanysbakery.api.notification.NotificationMessages.DeliveryResult;
import com.bethanysbakery.api.notification.NotificationMessages.PushMessage;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
public class LocalPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(LocalPushSender.class);

    @Override
    public DeliveryResult send(PushMessage message) {
        log.info("[LOCAL PUSH] token=***{} title={} body={}",
            message.token().length() < 4 ? message.token() : message.token().substring(message.token().length() - 4),
            message.title(), message.body());
        return DeliveryResult.sent("local-push-" + UUID.randomUUID());
    }
}
