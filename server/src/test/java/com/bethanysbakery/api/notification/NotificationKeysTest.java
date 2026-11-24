package com.bethanysbakery.api.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationKeysTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void keysMatchSpecFormats() {
        assertThat(NotificationKeys.menuPublished(ID)).isEqualTo("MENU_PUBLISHED:" + ID);
        assertThat(NotificationKeys.reminder(ID, OTHER)).isEqualTo("ORDER_REMINDER:" + ID + ":" + OTHER);
        assertThat(NotificationKeys.orderEvent("ORDER_ACCEPTED", ID)).isEqualTo("ORDER_ACCEPTED:" + ID);
        assertThat(NotificationKeys.paid(ID)).isEqualTo("order:" + ID + ":paid");
        assertThat(NotificationKeys.menuCancelled(ID, OTHER)).isEqualTo("MENU_CANCELLED:" + ID + ":" + OTHER);
        assertThat(NotificationKeys.adminNewOrderEmail(ID)).isEqualTo("ADMIN_NEW_ORDER_EMAIL:" + ID);
        assertThat(NotificationKeys.adminNewOrderSms(ID)).isEqualTo("ADMIN_NEW_ORDER_SMS:" + ID);
    }
}
