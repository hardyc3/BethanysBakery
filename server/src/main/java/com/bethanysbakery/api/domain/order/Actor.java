package com.bethanysbakery.api.domain.order;

/** Who triggered an order domain event. Mirrors audit ActorType for the order event interface. */
public enum Actor {
    CUSTOMER, ADMIN, SYSTEM
}
