package com.bethanysbakery.api.auth.otp;

import com.bethanysbakery.api.auth.AuthException;
import com.bethanysbakery.api.domain.auth.OtpChannel;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({"staging", "prod"})
public class UnconfiguredOtpSender implements OtpSender {
    @Override
    public void send(OtpChannel channel, String destination, String code, Instant expiresAt) {
        throw AuthException.notConfigured("OTP delivery is not configured until S6");
    }
}
