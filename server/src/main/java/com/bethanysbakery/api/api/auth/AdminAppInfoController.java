package com.bethanysbakery.api.api.auth;

import com.bethanysbakery.api.config.BakeryProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminAppInfoController {

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final Supplier<BakeryProperties.AdminApp> config;
    private final Clock clock;
    private volatile Cached cached;

    @Autowired
    public AdminAppInfoController(BakeryProperties properties, Clock clock) {
        this(() -> properties.adminApp(), clock);
    }

    public AdminAppInfoController(Supplier<BakeryProperties.AdminApp> config, Clock clock) {
        this.config = config;
        this.clock = clock;
    }

    @GetMapping("/admin/v1/app-info")
    public AuthApi.AdminAppInfo info() {
        Instant now = clock.instant();
        Cached current = cached;
        if (current == null || !now.isBefore(current.cachedAt().plus(CACHE_TTL))) {
            BakeryProperties.AdminApp source = config.get();
            AuthApi.AdminAppInfo info = new AuthApi.AdminAppInfo(source.latestVersion(), source.minVersion(),
                source.downloadUrl(), source.releaseNotes());
            current = new Cached(info, now);
            cached = current;
        }
        return current.info();
    }

    private record Cached(AuthApi.AdminAppInfo info, Instant cachedAt) {}
}
