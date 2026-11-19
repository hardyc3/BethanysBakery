package com.bethanysbakery.api.domain.notification;

import com.bethanysbakery.api.common.Uuids;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notification_delivery")
public class NotificationDelivery {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "notification_id", nullable = false)
    private Notification notification;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationChannel channel;

    @Column(nullable = false)
    private String destination;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationStatus status;

    @Column(name = "provider_message_id")
    private String providerMessageId;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected NotificationDelivery() {}

    public NotificationDelivery(Notification notification, NotificationChannel channel, String destination) {
        this.id = Uuids.v7();
        this.notification = notification;
        this.channel = channel;
        this.destination = destination;
        this.status = NotificationStatus.QUEUED;
        this.attempts = 0;
    }

    public UUID getId() { return id; }
    public Notification getNotification() { return notification; }
    public NotificationChannel getChannel() { return channel; }
    public String getDestination() { return destination; }
    public NotificationStatus getStatus() { return status; }
    public String getProviderMessageId() { return providerMessageId; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }
    public Instant getSentAt() { return sentAt; }

    public void markSending() { this.status = NotificationStatus.SENDING; }

    public void markSent(String providerMessageId, Instant now) {
        this.status = NotificationStatus.SENT;
        this.providerMessageId = providerMessageId;
        this.sentAt = now;
        this.lastError = null;
        this.attempts++;
    }

    public void markSuppressed(String reason) {
        this.status = NotificationStatus.SUPPRESSED;
        this.lastError = reason;
    }

    public void fail(String error) {
        this.status = NotificationStatus.FAILED;
        this.lastError = error;
    }

    public void requeue() {
        this.status = NotificationStatus.QUEUED;
        this.attempts = 0;
        this.lastError = null;
        this.sentAt = null;
        this.providerMessageId = null;
    }
}
