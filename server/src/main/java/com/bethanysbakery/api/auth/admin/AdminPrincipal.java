package com.bethanysbakery.api.auth.admin;

import java.util.UUID;

public record AdminPrincipal(UUID adminId, String email, String name, UUID deviceId, UUID sessionId) {}
