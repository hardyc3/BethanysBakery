package com.bethanysbakery.api.notification;

import com.bethanysbakery.api.domain.notification.NotificationEventType;
import com.bethanysbakery.api.notification.NotificationMessages.EmailMessage;
import com.bethanysbakery.api.notification.NotificationMessages.PushMessage;
import com.bethanysbakery.api.notification.NotificationMessages.SmsMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

/** Renders one logical notification to email (HTML + text) and SMS; legacy push formatting stays read-only. */
public final class NotificationTemplates {

    private static final int SMS_LIMIT = 320;
    private static final String BAKERY = "Bethany's Bakery";

    private final SpringTemplateEngine htmlEngine;
    private final SpringTemplateEngine textEngine;

    public NotificationTemplates(SpringTemplateEngine htmlEngine, SpringTemplateEngine textEngine) {
        this.htmlEngine = htmlEngine;
        this.textEngine = textEngine;
    }

    public EmailMessage renderEmail(NotificationEventType type, Map<String, Object> payload) {
        return renderEmail(type, payload, str(payload.get("customerEmail"), ""));
    }

    public EmailMessage renderEmail(NotificationEventType type, Map<String, Object> payload, String to) {
        Context context = new Context();
        context.setVariable("title", subject(type, payload));
        context.setVariable("intro", intro(type, payload));
        context.setVariable("lines", lines(type, payload));
        context.setVariable("footer", BAKERY + " — " + str(payload.get("menuUrl"), ""));
        return new EmailMessage(to, subject(type, payload),
            htmlEngine.process("email-body", context), textEngine.process("email-body", context));
    }

    public SmsMessage renderSms(NotificationEventType type, Map<String, Object> payload) {
        return renderSms(type, payload, str(payload.get("customerPhone"), ""));
    }

    public SmsMessage renderSms(NotificationEventType type, Map<String, Object> payload, String to) {
        String number = str(payload.get("orderNumber"), "");
        String body = switch (type) {
            case MENU_PUBLISHED -> "New menu: " + str(payload.get("menuTitle"), "Available now")
                + " " + str(payload.get("menuUrl"), "");
            case ORDER_REMINDER -> "Reminder: bake date " + str(payload.get("bakeDate"), "soon")
                + ". Order at " + str(payload.get("menuUrl"), "");
            case MENU_CANCELLED -> "Menu update for " + str(payload.get("bakeDate"), "your bake date")
                + ". Details: " + str(payload.get("menuUrl"), "");
            case ORDER_ACCEPTED -> "Order " + number + " accepted. Pay "
                + str(payload.get("venmoAmount"), "") + " to @" + str(payload.get("venmoHandle"), "") + ".";
            case ORDER_READY -> "Order " + number + " is ready for pickup. " + str(payload.get("mapsUrl"), "");
            case ORDER_DELAYED -> "Order " + number + " delayed"
                + (payload.get("delayReason") == null ? "." : ": " + str(payload.get("delayReason"), "") + ".");
            case ORDER_REJECTED -> "Order " + number + " was rejected"
                + (payload.get("rejectionReason") == null ? "." : ": " + str(payload.get("rejectionReason"), "") + ".");
            case ORDER_CANCELLED -> "Order " + number + " was cancelled"
                + (payload.get("cancellationReason") == null ? "."
                    : ": " + str(payload.get("cancellationReason"), "") + ".");
            case ORDER_EXPIRED -> "Order " + number + " expired before cutoff. Order again at "
                + str(payload.get("menuUrl"), "");
            case ADMIN_NEW_ORDER -> "New order " + number + ". Details: " + str(payload.get("mapsUrl"), "");
            default -> BAKERY + ": update on " + number + ".";
        };
        return new SmsMessage(to, truncate(body));
    }

    /** Legacy S7 shape for historical rows only; rendering never triggers a send. */
    public PushMessage renderPush(NotificationEventType type, Map<String, Object> payload) {
        String number = str(payload.get("orderNumber"), "");
        String firstName = str(payload.get("firstName"), "");
        if (firstName.isBlank()) {
            String fullName = str(payload.get("customerName"), "");
            firstName = fullName.isBlank() ? "there" : fullName.trim().split("\\s+")[0];
        }
        String title = switch (type) {
            case ADMIN_NEW_ORDER -> "New order " + number;
            case ORDER_READY -> "Order " + number + " ready";
            case ORDER_ACCEPTED -> "Order " + number + " accepted";
            case ORDER_REJECTED, ORDER_CANCELLED, ORDER_EXPIRED, ORDER_DELAYED -> "Order " + number + " update";
            default -> BAKERY;
        };
        String body = "Hi " + firstName + ", "
            + switch (type) {
                case ADMIN_NEW_ORDER -> "your order " + number + ": "
                    + str(payload.get("pushItemSummary"), "New order") + " · "
                    + str(payload.get("totalDisplay"), "");
                case ORDER_READY -> "your order " + number + " is ready for pickup.";
                case ORDER_ACCEPTED -> "your order " + number + " was accepted. Pay via @"
                    + str(payload.get("venmoHandle"), "") + ".";
                default -> "there is an update on order " + number + ".";
            };
        return new PushMessage(str(payload.get("token"), ""), title, body);
    }

    private String subject(NotificationEventType type, Map<String, Object> payload) {
        String number = str(payload.get("orderNumber"), "");
        return switch (type) {
            case MENU_PUBLISHED -> "New menu from " + BAKERY;
            case ORDER_REMINDER -> "Order reminder — " + BAKERY;
            case MENU_CANCELLED -> "Menu update — " + BAKERY;
            case ORDER_PAID -> "Paid order " + number + " — fulfillment details";
            case ADMIN_NEW_ORDER -> "New order " + number + " — " + BAKERY;
            case ORDER_READY -> "Order " + number + " ready for pickup";
            case ORDER_ACCEPTED -> "Order " + number + " accepted";
            case ORDER_DELAYED -> "Update on order " + number;
            case ORDER_REJECTED -> "Order " + number + " update";
            case ORDER_CANCELLED -> "Order " + number + " cancelled";
            case ORDER_EXPIRED -> "Order " + number + " expired";
        };
    }

    private String intro(NotificationEventType type, Map<String, Object> payload) {
        return switch (type) {
            case ORDER_PAID -> "Payment received. Full fulfillment details below.";
            case MENU_PUBLISHED -> "This week's menu is ready.";
            case ORDER_REMINDER -> "Bake date is coming up — order before cutoff.";
            case ADMIN_NEW_ORDER -> "A new order needs your attention.";
            default -> "An update on your order.";
        };
    }

    private List<Map<String, Object>> lines(NotificationEventType type, Map<String, Object> payload) {
        List<Map<String, Object>> lines = new ArrayList<>();
        if (type == NotificationEventType.MENU_PUBLISHED) {
            add(lines, "Menu", payload.get("menuTitle"));
            add(lines, "View menu", payload.get("menuUrl"));
            return lines;
        }
        if (type == NotificationEventType.ORDER_REMINDER) {
            add(lines, "Bake date", payload.get("bakeDate"));
            add(lines, "Order at", payload.get("menuUrl"));
            return lines;
        }
        if (type == NotificationEventType.MENU_CANCELLED) {
            add(lines, "Bake date", payload.get("bakeDate"));
            add(lines, "Details", payload.get("menuUrl"));
            return lines;
        }
        add(lines, "Order", payload.get("orderNumber"));
        add(lines, "Customer", payload.get("customerName"));
        add(lines, "Items", payload.get("itemSummary"));
        add(lines, "Pickup", payload.get("pickupWindow"));
        if (type == NotificationEventType.ORDER_PAID || type == NotificationEventType.ADMIN_NEW_ORDER) {
            add(lines, "Bake date", payload.get("bakeDate"));
            if (payload.get("customerEmail") != null || payload.get("customerPhone") != null) {
                add(lines, "Contact", str(payload.get("customerEmail"), "") + " / "
                    + str(payload.get("customerPhone"), ""));
            }
            add(lines, "Order notes", payload.get("notes"));
            add(lines, "Subtotal", money(payload.get("subtotalCents")));
            add(lines, "Total", str(payload.get("totalDisplay"), money(payload.get("totalCents"))));
            add(lines, "Payment", payload.get("paymentMethod"));
            add(lines, "Status", payload.get("paymentStatus"));
            add(lines, "Paid at", payload.get("paidAt"));
        }
        if (type == NotificationEventType.ORDER_ACCEPTED) {
            add(lines, "Amount", payload.get("venmoAmount"));
            add(lines, "Venmo", "@" + str(payload.get("venmoHandle"), ""));
        }
        if (type == NotificationEventType.ORDER_READY) {
            add(lines, "Directions", payload.get("mapsUrl"));
        }
        if (type == NotificationEventType.ORDER_REJECTED) {
            add(lines, "Reason", payload.get("rejectionReason"));
        }
        if (type == NotificationEventType.ORDER_CANCELLED) {
            add(lines, "Reason", payload.get("cancellationReason"));
        }
        if (type == NotificationEventType.ORDER_DELAYED) {
            add(lines, "Reason", payload.get("delayReason"));
        }
        return lines;
    }

    private static void add(List<Map<String, Object>> lines, String label, Object value) {
        if (value == null || value.toString().isBlank()) {
            return;
        }
        lines.add(Map.of("label", label, "value", value.toString()));
    }

    private static String money(Object cents) {
        return cents instanceof Number number ? String.format(Locale.US, "$%.2f", number.doubleValue() / 100.0) : "";
    }

    private static String str(Object value, String fallback) {
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private static String truncate(String body) {
        return body.length() <= SMS_LIMIT ? body : body.substring(0, SMS_LIMIT);
    }
}
