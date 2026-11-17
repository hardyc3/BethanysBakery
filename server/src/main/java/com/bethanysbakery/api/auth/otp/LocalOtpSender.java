package com.bethanysbakery.api.auth.otp;

import com.bethanysbakery.api.domain.auth.OtpChannel;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!staging & !prod")
public class LocalOtpSender implements OtpSender {
    private static final Logger log = LoggerFactory.getLogger(LocalOtpSender.class);
    private final ConcurrentHashMap<String, String> lastCodes = new ConcurrentHashMap<>();

    @Override
    public void send(OtpChannel channel, String destination, String code, Instant expiresAt) {
        lastCodes.put(destination, code);
        log.info("[local otp] channel={} destination={} code={} expiresAt={}", channel, mask(destination), code, expiresAt);
    }

    public String lastCode(String destination) {
        return lastCodes.get(destination);
    }

    private static String mask(String value) {
        if (value == null || value.length() < 4) return "***";
        return value.charAt(0) + "***" + value.substring(value.length() - 2);
    }
}
