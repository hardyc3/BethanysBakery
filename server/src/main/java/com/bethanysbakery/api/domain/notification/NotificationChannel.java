package com.bethanysbakery.api.domain.notification;

public enum NotificationChannel {
    EMAIL, SMS,
    /** S7-era value retained only so upgraded databases can read historical delivery rows. */
    PUSH
}
