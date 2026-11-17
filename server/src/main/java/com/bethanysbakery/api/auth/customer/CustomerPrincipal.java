package com.bethanysbakery.api.auth.customer;

import java.util.UUID;

public record CustomerPrincipal(UUID customerId, UUID sessionId) {}
