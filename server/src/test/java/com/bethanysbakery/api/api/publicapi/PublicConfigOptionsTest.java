package com.bethanysbakery.api.api.publicapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.bethanysbakery.api.api.publicapi.PublicConfigController.NotificationOption;
import com.bethanysbakery.api.api.publicapi.PublicConfigController.PublicConfigResponse;
import com.bethanysbakery.api.config.NotificationProperties;
import com.bethanysbakery.api.support.TestProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Provider availability must reflect real configuration, not the local fallback senders. */
class PublicConfigOptionsTest {

    private static final NotificationProperties.Mailgun MAILGUN =
        new NotificationProperties.Mailgun("key", "mg.example.com", "https://api.mailgun.net/v3", "from@example.com");
    private static final NotificationProperties.Textbee TEXTBEE =
        new NotificationProperties.Textbee("https://sms.example.com", "tb-key", "device-1", "secret");

    // Independent copy of docs/specs/server/S8-review-followups.md:77; never derive from the server constant.
    private static final String EXPECTED_NONE_SELECTED_MESSAGE =
        "If you turn off all notifications, you will not receive any email or text message about: new weekly menus, "
            + "reminders before baking day, order accepted, order ready, order delayed, order rejected, order cancelled, "
            + "order expired, or a cancelled menu/date. You can still open the app any time to check your orders, "
            + "payment status, and pickup details. Text messages also require phone verification and are stopped if "
            + "you reply STOP.";

    private static final List<String> EXPECTED_EVENT_TYPES = List.of(
        "MENU_PUBLISHED", "ORDER_REMINDER", "ORDER_ACCEPTED", "ORDER_READY", "ORDER_DELAYED",
        "ORDER_REJECTED", "ORDER_CANCELLED", "ORDER_EXPIRED", "MENU_CANCELLED");

    @Test
    void reportsEmailAndSmsOnlyWhenProvidersAreConfigured() {
        PublicConfigResponse both = new PublicConfigController(TestProperties.defaults(),
            new NotificationProperties(false, "admin@example.com", null, MAILGUN, TEXTBEE)).config();
        assertThat(both.notificationOptions().events()).hasSize(9)
            .allSatisfy(option -> {
                assertThat(option.emailAvailable()).isTrue();
                assertThat(option.smsAvailable()).isTrue();
            });
        assertThat(both.notificationOptions().events())
            .extracting(NotificationOption::eventType)
            .containsExactlyElementsOf(EXPECTED_EVENT_TYPES);
        assertThat(both.notificationOptions().noneSelectedMessage())
            .isEqualTo(EXPECTED_NONE_SELECTED_MESSAGE);

        NotificationOption emailOnly = new PublicConfigController(TestProperties.defaults(),
            new NotificationProperties(false, "admin@example.com", null, MAILGUN, null))
            .config().notificationOptions().events().getFirst();
        assertThat(emailOnly.emailAvailable()).isTrue();
        assertThat(emailOnly.smsAvailable()).isFalse();

        NotificationOption smsOnly = new PublicConfigController(TestProperties.defaults(),
            new NotificationProperties(false, "admin@example.com", null, null, TEXTBEE))
            .config().notificationOptions().events().getFirst();
        assertThat(smsOnly.emailAvailable()).isFalse();
        assertThat(smsOnly.smsAvailable()).isTrue();

        NotificationOption neither = new PublicConfigController(TestProperties.defaults(),
            new NotificationProperties(false, null, null, null, null))
            .config().notificationOptions().events().getFirst();
        assertThat(neither.emailAvailable()).isFalse();
        assertThat(neither.smsAvailable()).isFalse();
    }
}
