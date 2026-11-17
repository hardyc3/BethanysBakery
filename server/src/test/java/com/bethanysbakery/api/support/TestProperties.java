package com.bethanysbakery.api.support;

import com.bethanysbakery.api.config.BakeryProperties;

/** Builds valid properties for unit tests without booting Spring. */
public final class TestProperties {
    public static final String JWT_SECRET = "dGVzdC1qd3Qtc2VjcmV0LXRlc3Qtand0LXNlY3JldC10ZXN0";
    public static final String ENCRYPTION_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private TestProperties() {}

    public static BakeryProperties defaults() {
        return new BakeryProperties(
            "Test Bakery",
            "America/Denver",
            "123 Test St",
            "https://maps.google.com/?q=123+Test+St",
            "test@example.com",
            12,
            auth(),
            new BakeryProperties.AdminApp("1.0.0", "1.0.0", "https://example.com/admin.apk", null)
        );
    }

    public static BakeryProperties.Auth auth() {
        return auth(false);
    }

    public static BakeryProperties.Auth auth(boolean localBypass) {
        return new BakeryProperties.Auth(
            JWT_SECRET,
            ENCRYPTION_KEY,
            null,
            null,
            "Cf-Authenticated-Client-Cert",
            "Cf-Authenticated-Client-Cert-Verified",
            localBypass,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }
}
