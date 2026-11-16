package com.bethanysbakery.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class BakeryPropertiesValidationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            ConfigurationPropertiesAutoConfiguration.class,
            ValidationAutoConfiguration.class))
        .withUserConfiguration(TestConfig.class);

    @Test
    void rejectsBlankBakeryName() {
        runner
            .withPropertyValues(
                "bakery.name=",
                "bakery.timezone=America/Denver",
                "bakery.pickup-address=Lehi, Utah",
                "bakery.maps-url=https://maps.google.com/?q=Lehi",
                "bakery.support-email=hi@example.com",
                "bakery.order-cutoff-hours=12")
            .run(context -> assertThat(context).hasFailed());
    }

    @Configuration
    @EnableConfigurationProperties(BakeryProperties.class)
    static class TestConfig {}
}
