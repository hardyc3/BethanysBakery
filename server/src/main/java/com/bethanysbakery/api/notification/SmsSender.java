package com.bethanysbakery.api.notification;

import com.bethanysbakery.api.notification.NotificationMessages.DeliveryResult;
import com.bethanysbakery.api.notification.NotificationMessages.SmsMessage;

public interface SmsSender {
    DeliveryResult send(SmsMessage message);
}
