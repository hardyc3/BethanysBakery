package com.bethanysbakery.api.menu;

import com.bethanysbakery.api.config.BakeryProperties;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

/** Bakery-local time calculations for menus, windows, cutoffs, and notifications. */
@Component
public class MenuTimeRules {

    private final BakeryProperties properties;
    private final ZoneId zone;

    public MenuTimeRules(BakeryProperties properties) {
        this.properties = properties;
        this.zone = ZoneId.of(properties.timezone());
    }

    public ZoneId zone() {
        return zone;
    }

    public LocalDate today() {
        return LocalDate.now(zone);
    }

    /** Default notification instant: first bake date minus four days at 09:00 bakery-local. */
    public Instant defaultNotifyAt(LocalDate firstBakeDate) {
        return firstBakeDate.minusDays(4).atTime(LocalTime.of(9, 0)).atZone(zone).toInstant();
    }

    public boolean fallsOnBakeDate(Instant instant, LocalDate bakeDate) {
        return instant != null && instant.atZone(zone).toLocalDate().equals(bakeDate);
    }

    public Instant orderCutoffAt(Instant startAt) {
        return startAt.minus(properties.orderCutoffHours().longValue(), ChronoUnit.HOURS);
    }
}
