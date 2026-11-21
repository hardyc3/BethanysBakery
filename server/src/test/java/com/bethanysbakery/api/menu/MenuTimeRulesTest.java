package com.bethanysbakery.api.menu;

import static org.assertj.core.api.Assertions.assertThat;

import com.bethanysbakery.api.config.BakeryProperties;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** DST-sensitive time rules for the America/Denver bakery timezone. */
class MenuTimeRulesTest {

    private MenuTimeRules rules;

    @BeforeEach
    void setUp() {
        BakeryProperties.Auth auth = new BakeryProperties.Auth(
            "secret", "key", null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, "https://public.example.com", null, null);
        BakeryProperties props = new BakeryProperties("Bakery", "America/Denver", "addr", "url",
            "test@example.com", 12, auth, null, new BakeryProperties.Venmo(null, null));
        rules = new MenuTimeRules(props);
    }

    @Test
    void defaultNotifyUses09LocalAcrossMarchDst() {
        // 2024-03-10 is the spring-forward transition in America/Denver.
        Instant before = rules.defaultNotifyAt(LocalDate.of(2024, 3, 9)); // minus 4 days = 03-05, MST (UTC-7)
        Instant after = rules.defaultNotifyAt(LocalDate.of(2024, 3, 14)); // minus 4 days = 03-10, MDT (UTC-6)

        assertThat(before).isEqualTo(Instant.parse("2024-03-05T16:00:00Z"));
        assertThat(after).isEqualTo(Instant.parse("2024-03-10T15:00:00Z"));
    }

    @Test
    void defaultNotifyUses09LocalAcrossNovemberDst() {
        // 2024-11-03 is the fall-back transition in America/Denver.
        Instant before = rules.defaultNotifyAt(LocalDate.of(2024, 11, 6)); // minus 4 days = 11-02, MDT (UTC-6)
        Instant after = rules.defaultNotifyAt(LocalDate.of(2024, 11, 7)); // minus 4 days = 11-03, MST (UTC-7)

        assertThat(before).isEqualTo(Instant.parse("2024-11-02T15:00:00Z"));
        assertThat(after).isEqualTo(Instant.parse("2024-11-03T16:00:00Z"));
    }

    @Test
    void fallsOnBakeDateUsesDenverLocalDateNotUtc() {
        Instant lateMst = Instant.parse("2024-03-10T06:00:00Z"); // 2024-03-09 23:00 MST
        assertThat(rules.fallsOnBakeDate(lateMst, LocalDate.of(2024, 3, 9))).isTrue();
        assertThat(rules.fallsOnBakeDate(lateMst, LocalDate.of(2024, 3, 10))).isFalse();
    }

    @Test
    void orderCutoffIsStartMinusConfiguredHours() {
        Instant start = Instant.parse("2024-06-15T18:00:00Z");
        assertThat(rules.orderCutoffAt(start)).isEqualTo(Instant.parse("2024-06-15T06:00:00Z"));
    }
}
