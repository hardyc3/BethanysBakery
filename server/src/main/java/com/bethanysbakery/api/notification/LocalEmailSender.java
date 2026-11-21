package com.bethanysbakery.api.notification;

import com.bethanysbakery.api.api.notification.NotificationApi;
import com.bethanysbakery.api.notification.NotificationMessages.DeliveryResult;
import com.bethanysbakery.api.notification.NotificationMessages.EmailMessage;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Local profile: render/print messages, never call a network provider. */
@Component
@Profile("local")
public class LocalEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LocalEmailSender.class);

    @Override
    public DeliveryResult send(EmailMessage message) {
        log.info("[LOCAL EMAIL] to={} subject={}\n{}\n---\n{}",
            NotificationApi.Mask.destination("EMAIL", message.to()), message.subject(),
            message.textBody(), message.htmlBody());
        return DeliveryResult.sent("local-email-" + UUID.randomUUID());
    }
}
