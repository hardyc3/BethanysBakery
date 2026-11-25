package com.bethanysbakery.api.config;

/** Non-production constants shipped only with the dummy Spring profile. */
public final class DummyConstants {

    public static final String ADMIN_EMAIL = "admin@dummy.local";
    public static final String ADMIN_PASSWORD = "DummyAdmin!234";
    public static final String ADMIN_TOTP_SECRET = "JBSWY3DPEHPK3PXP";
    public static final String ADMIN_RECOVERY_CODE = "DUMMY-12345";
    public static final String DEVICE_KEY = "dummy-device-key";
    public static final String CUSTOMER_OTP = "123456";

    private DummyConstants() {}
}
