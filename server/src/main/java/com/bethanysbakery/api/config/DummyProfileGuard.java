package com.bethanysbakery.api.config;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Fails fast if dummy credentials are activated alongside a deployment profile. */
@Component
@Profile("dummy")
public class DummyProfileGuard {

    private final Environment environment;

    public DummyProfileGuard(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void validate() {
        if (environment.acceptsProfiles(Profiles.of("prod", "staging"))) {
            throw new IllegalStateException("dummy profile must never be combined with prod or staging");
        }
    }
}
