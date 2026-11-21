package com.bethanysbakery.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.bethanysbakery.api.api.auth.AdminAppInfoController;
import com.bethanysbakery.api.api.auth.AuthApi;
import com.bethanysbakery.api.config.BakeryProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AdminAppInfoCacheTest {

    private static final Instant START = Instant.parse("2026-11-20T12:00:00Z");

    @Test
    void appInfoIsCachedForFiveMinutes() {
        AtomicReference<BakeryProperties.AdminApp> config = new AtomicReference<>(
            new BakeryProperties.AdminApp("1.0.0", "1.0.0", "https://a.test/app.apk", null));
        MutableClock clock = new MutableClock(START);
        AdminAppInfoController controller = new AdminAppInfoController(config::get, clock);

        AuthApi.AdminAppInfo first = controller.info();
        config.set(new BakeryProperties.AdminApp("2.0.0", "2.0.0", "https://b.test/app.apk", null));
        assertThat(controller.info().latestVersion()).isEqualTo(first.latestVersion());

        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        assertThat(controller.info().latestVersion()).isEqualTo("2.0.0");
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
