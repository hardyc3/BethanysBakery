package com.bethanysbakery.api.common;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/** UUIDv7 generator (time-ordered, random tail). */
public final class Uuids {
    private static final SecureRandom RANDOM = new SecureRandom();

    private Uuids() {}

    public static UUID v7() {
        long timestamp = Instant.now().toEpochMilli() & 0xFFFFFFFFFFFFL;
        long randA = RANDOM.nextInt(1 << 12);
        long randB = RANDOM.nextLong();
        long msb = (timestamp << 16) | 0x7000L | randA;
        long lsb = (randB & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }
}
