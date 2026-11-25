package com.bethanysbakery.api.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class DummyProfileGuardTest {

    @Test
    void rejectsDeploymentProfiles() {
        for (String profile : new String[] {"prod", "staging"}) {
            MockEnvironment environment = new MockEnvironment();
            environment.setActiveProfiles("dummy", profile);
            assertThatThrownBy(() -> new DummyProfileGuard(environment).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dummy profile");
        }
    }

    @Test
    void permitsNonDeploymentProfiles() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dummy");
        assertThatCode(() -> new DummyProfileGuard(environment).validate()).doesNotThrowAnyException();
    }
}
