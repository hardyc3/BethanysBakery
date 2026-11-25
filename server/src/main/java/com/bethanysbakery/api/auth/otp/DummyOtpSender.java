package com.bethanysbakery.api.auth.otp;

import com.bethanysbakery.api.domain.auth.OtpChannel;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Dummy-image only: no provider call; the fixed code is documented for clients. */
@Component
@Profile("dummy")
public class DummyOtpSender implements OtpSender {

    private static final Logger log = LoggerFactory.getLogger(DummyOtpSender.class);

    @Override
    public void send(OtpChannel channel, String destination, String code, Instant expiresAt) {
        log.info("[dummy otp] channel={} destination={} code={}", channel, mask(destination), code);
    }

    static String mask(String destination) {
        if (destination == null || destination.length() < 4) {
            return "***";
        }
        return destination.charAt(0) + "***" + destination.substring(destination.length() - 2);
    }
}
