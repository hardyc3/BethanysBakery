package com.bethanysbakery.api.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bethanysbakery.api.auth.audit.AuditService;
import com.bethanysbakery.api.config.NotificationProperties;
import com.bethanysbakery.api.domain.auth.CustomerRepository;
import com.bethanysbakery.api.domain.notification.Notification;
import com.bethanysbakery.api.domain.notification.NotificationChannel;
import com.bethanysbakery.api.domain.notification.NotificationPreferenceRepository;
import com.bethanysbakery.api.domain.notification.NotificationRepository;
import com.bethanysbakery.api.domain.order.Order;
import com.bethanysbakery.api.domain.order.OrderRepository;
import com.bethanysbakery.api.support.TestProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;

/** No FCM tokens and no phone configured: exactly one admin email alert is queued. */
@ExtendWith(MockitoExtension.class)
class NotificationServiceAdminAlertTest {

    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");

    @Mock NotificationRepository notifications;
    @Mock NotificationAudienceResolver audience;
    @Mock NotificationPreferenceRepository preferences;
    @Mock CustomerRepository customers;
    @Mock OrderRepository orders;
    @Mock AuditService audit;
    @Mock ObjectMapper mapper;
    @Mock Environment environment;

    @Test
    void withoutAdminPhoneOnlyEmailAlertIsQueued() {
        NotificationProperties properties = new NotificationProperties(
            false, "admin@example.com", null, null, null);
        NotificationService service = new NotificationService(notifications, audience, preferences, customers, orders,
            audit, TestProperties.defaults(), properties, mapper,
            Clock.fixed(Instant.parse("2026-11-20T12:00:00Z"), ZoneOffset.UTC), environment);
        Order order = mock(Order.class);
        when(order.getId()).thenReturn(ORDER_ID);
        when(order.getLines()).thenReturn(List.of());
        when(notifications.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(notifications.save(any(Notification.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.enqueueAdminNewOrder(order);

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(notifications, times(1)).save(saved.capture());
        Notification alert = saved.getValue();
        assertThat(alert.getIdempotencyKey()).isEqualTo("ADMIN_NEW_ORDER_EMAIL:" + ORDER_ID);
        assertThat(alert.getDeliveries()).singleElement()
            .extracting(delivery -> delivery.getChannel()).isEqualTo(NotificationChannel.EMAIL);
        assertThat(properties.adminAlertPhoneConfigured()).isFalse();
    }

    @Test
    void withAdminPhoneQueuesEmailAndSmsWithDistinctKeys() {
        NotificationProperties properties = new NotificationProperties(
            false, "admin@example.com", "+15551234567", null, null);
        NotificationService service = new NotificationService(notifications, audience, preferences, customers, orders,
            audit, TestProperties.defaults(), properties, mapper,
            Clock.fixed(Instant.parse("2026-11-20T12:00:00Z"), ZoneOffset.UTC), environment);
        Order order = mock(Order.class);
        when(order.getId()).thenReturn(ORDER_ID);
        when(order.getLines()).thenReturn(List.of());
        when(orders.findById(ORDER_ID)).thenReturn(Optional.of(order));
        when(notifications.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(notifications.save(any(Notification.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.enqueueAdminNewOrder(order);

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(notifications, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(Notification::getIdempotencyKey)
            .containsExactly("ADMIN_NEW_ORDER_EMAIL:" + ORDER_ID, "ADMIN_NEW_ORDER_SMS:" + ORDER_ID);
        assertThat(saved.getAllValues().get(0).getDeliveries()).singleElement()
            .extracting(delivery -> delivery.getChannel()).isEqualTo(NotificationChannel.EMAIL);
        assertThat(saved.getAllValues().get(1).getDeliveries()).singleElement()
            .extracting(delivery -> delivery.getChannel()).isEqualTo(NotificationChannel.SMS);
    }
}
