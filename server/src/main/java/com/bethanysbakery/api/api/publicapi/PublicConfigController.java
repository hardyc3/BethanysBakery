package com.bethanysbakery.api.api.publicapi;

import com.bethanysbakery.api.config.BakeryProperties;
import com.bethanysbakery.api.config.NotificationProperties;
import com.bethanysbakery.api.notification.NotificationService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class PublicConfigController {

    public static final String NONE_SELECTED_MESSAGE =
        "If you turn off all notifications, you will not receive any email or text message about: new weekly menus, "
            + "reminders before baking day, order accepted, order ready, order delayed, order rejected, "
            + "order cancelled, order expired, or a cancelled menu/date. You can still open the app any time to check "
            + "your orders, payment status, and pickup details. Text messages also require phone verification and "
            + "are stopped if you reply STOP.";

    private final BakeryProperties props;
    private final NotificationProperties notifications;

    public PublicConfigController(BakeryProperties props, NotificationProperties notifications) {
        this.props = props;
        this.notifications = notifications;
    }

    @GetMapping("/config")
    public PublicConfigResponse config() {
        boolean emailAvailable = notifications.mailgunConfigured();
        boolean smsAvailable = notifications.textbeeConfigured();
        List<NotificationOption> events = NotificationService.customerEvents().stream()
            .map(type -> new NotificationOption(type.name(), emailAvailable, smsAvailable))
            .toList();
        return new PublicConfigResponse(
            props.name(),
            props.timezone(),
            props.pickupAddress(),
            props.mapsUrl(),
            props.supportEmail(),
            props.orderCutoffHours(),
            new NotificationOptions(events, NONE_SELECTED_MESSAGE)
        );
    }

    public record PublicConfigResponse(
        String bakeryName,
        String timezone,
        String pickupAddress,
        String mapsUrl,
        String supportEmail,
        Integer orderCutoffHours,
        NotificationOptions notificationOptions
    ) {}

    public record NotificationOptions(List<NotificationOption> events, String noneSelectedMessage) {}

    public record NotificationOption(String eventType, boolean emailAvailable, boolean smsAvailable) {}
}
