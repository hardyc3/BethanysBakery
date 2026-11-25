package com.bethanysbakery.api.auth.otp;

import com.bethanysbakery.api.config.DummyConstants;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Dummy-image only: every customer OTP is the documented fixed code. */
@Component
@Profile("dummy")
public class DummyOtpCodeGenerator implements OtpCodeGenerator {

    public static final String CODE = DummyConstants.CUSTOMER_OTP;

    @Override
    public String generate() {
        return CODE;
    }
}
