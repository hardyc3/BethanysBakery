package com.bethanysbakery.api.domain.order;

public enum OrderStatus {
    PENDING, ACCEPTED, READY, COMPLETED, REJECTED, CANCELLED, EXPIRED;

    public boolean isActive() {
        return this == PENDING || this == ACCEPTED || this == READY;
    }

    public boolean isTerminalFailure() {
        return this == REJECTED || this == CANCELLED || this == EXPIRED;
    }
}
