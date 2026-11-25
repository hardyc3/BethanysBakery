package com.bethanysbakery.api.order;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration;
import org.springframework.context.annotation.Bean;

/** Registers the shared counters on the management child Prometheus registry. */
@ManagementContextConfiguration(proxyBeanMethods = false)
public class OrderMetricsManagementConfiguration {

    @Bean
    MeterRegistryCustomizer<PrometheusMeterRegistry> managementOrderMetricsCustomizer() {
        return OrderMetrics::registerWith;
    }
}
