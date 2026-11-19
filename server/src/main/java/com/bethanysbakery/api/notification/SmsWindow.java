package com.bethanysbakery.api.notification;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Promotional SMS quiet hours: 09:00-20:00 local, enforced in the dispatcher. */
public final class SmsWindow {

    private static final LocalTime START = LocalTime.of(9, 0);
    private static final LocalTime END = LocalTime.of(20, 0);

    private SmsWindow() {}

    public static boolean allowed(Instant instant, ZoneId zone) {
        LocalTime localTime = instant.atZone(zone).toLocalTime();
        return !localTime.isBefore(START) && localTime.isBefore(END);
    }

    public static Instant nextAllowed(Instant instant, ZoneId zone) {
        if (allowed(instant, zone)) {
            return instant;
        }
        ZonedDateTime local = instant.atZone(zone);
        LocalDate target = local.toLocalTime().isBefore(START)
            ? local.toLocalDate()
            : local.toLocalDate().plusDays(1);
        return target.atTime(START).atZone(zone).toInstant();
    }

    public static boolean bakeDatePassed(LocalDate bakeDate, Instant now, ZoneId zone) {
        return now.atZone(zone).toLocalDate().isAfter(bakeDate);
    }
}
