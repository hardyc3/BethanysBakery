package com.bethanysbakery.api.auth.otp;

import com.bethanysbakery.api.domain.auth.OtpChannel;
import java.time.Instant;

public interface OtpSender {
    void send(OtpChannel channel, String destination, String code, Instant expiresAt);
}
