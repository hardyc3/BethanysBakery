package com.bethanysbakery.api.order;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the shared counters on the application Prometheus registry. */
@Configuration(proxyBeanMethods = false)
public class OrderMetricsConfiguration {

    @Bean
    MeterRegistryCustomizer<PrometheusMeterRegistry> orderMetricsCustomizer() {
        return OrderMetrics::registerWith;
    }
}
