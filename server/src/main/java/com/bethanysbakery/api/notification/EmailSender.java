package com.bethanysbakery.api.notification;

import com.bethanysbakery.api.notification.NotificationMessages.DeliveryResult;
import com.bethanysbakery.api.notification.NotificationMessages.EmailMessage;

public interface EmailSender {
    DeliveryResult send(EmailMessage message);
}
