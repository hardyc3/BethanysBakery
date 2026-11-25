package com.bethanysbakery.api.order;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.prometheus.metrics.model.registry.Collector;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.model.snapshots.CounterSnapshot;
import io.prometheus.metrics.model.snapshots.MetricMetadata;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** S9 business counters. No labels: server transitions only, never caller or customer identity. */
@Component
public class OrderMetrics {

    private static final CounterSet CREATED = new CounterSet("bakery_orders_created_total", "Orders created");
    private static final CounterSet PAID = new CounterSet("bakery_orders_paid_total", "Orders marked paid");
    private static final CounterSet CANCELLED = new CounterSet("bakery_orders_cancelled_total",
        "Orders cancelled by customer or admin");
    private static final CounterSet READY = new CounterSet("bakery_orders_ready_total", "Orders marked ready");
    private static final CounterSet DELAYED = new CounterSet("bakery_orders_delayed_total", "Orders with delay notice");
    private static final CounterSet REJECTED = new CounterSet("bakery_orders_rejected_total", "Orders rejected");
    private static final CounterSet EXPIRED = new CounterSet("bakery_orders_expired_total", "Orders expired");

    /** Registers counters on any Prometheus registry, including the management child registry. */
    public static void registerWith(PrometheusMeterRegistry registry) {
        PrometheusRegistry prometheus = registry.getPrometheusRegistry();
        CREATED.register(prometheus);
        PAID.register(prometheus);
        CANCELLED.register(prometheus);
        READY.register(prometheus);
        DELAYED.register(prometheus);
        REJECTED.register(prometheus);
        EXPIRED.register(prometheus);
    }

    public void created() {
        CREATED.increment();
    }

    public void paid() {
        PAID.increment();
    }

    public void cancelled() {
        CANCELLED.increment();
    }

    public void ready() {
        READY.increment();
    }

    public void delayed() {
        DELAYED.increment();
    }

    public void rejected() {
        REJECTED.increment();
    }

    public void expired() {
        EXPIRED.increment();
    }

    private static final class CounterSet {

        private final String name;
        private final String help;
        private final Map<PrometheusRegistry, MutableCounter> counters =
            Collections.synchronizedMap(new IdentityHashMap<>());

        private CounterSet(String name, String help) {
            this.name = name;
            this.help = help;
        }

        private void register(PrometheusRegistry registry) {
            counters.computeIfAbsent(registry, key -> {
                MutableCounter counter = new MutableCounter(name, help);
                key.register(counter);
                return counter;
            });
        }

        private void increment() {
            List<MutableCounter> values;
            synchronized (counters) {
                values = List.copyOf(counters.values());
            }
            if (!TransactionSynchronizationManager.isSynchronizationActive()) {
                values.forEach(MutableCounter::increment);
                return;
            }
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    values.forEach(MutableCounter::increment);
                }
            });
        }
    }

    private static final class MutableCounter implements Collector {

        private final MetricMetadata metadata;
        private final AtomicLong value = new AtomicLong();

        private MutableCounter(String name, String help) {
            this.metadata = exactMetadata(name, help);
        }

        private void increment() {
            value.incrementAndGet();
        }

        @Override
        public CounterSnapshot collect() {
            return new CounterSnapshot(metadata, List.of(
                CounterSnapshot.CounterDataPointSnapshot.builder().value(value.get()).build()));
        }
    }

    /**
     * Prometheus client metadata validation rejects reserved suffixes even though the exposition
     * format requires them for counter series. Set the exact spec name once, at registration.
     */
    private static MetricMetadata exactMetadata(String name, String help) {
        String baseName = name.substring(0, name.length() - "_total".length());
        MetricMetadata metadata = new MetricMetadata("bakery_orders_metric", help);
        try {
            Field nameField = MetricMetadata.class.getDeclaredField("name");
            nameField.setAccessible(true);
            nameField.set(metadata, baseName);
            Field prometheusNameField = MetricMetadata.class.getDeclaredField("prometheusName");
            prometheusNameField.setAccessible(true);
            prometheusNameField.set(metadata, baseName);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Cannot register exact metric name " + name, ex);
        }
        return metadata;
    }
}
