package com.bethanysbakery.api.notification;

import com.bethanysbakery.api.notification.NotificationMessages.DeliveryResult;
import com.bethanysbakery.api.notification.NotificationMessages.PushMessage;

public interface PushSender {
    DeliveryResult send(PushMessage message);
}
