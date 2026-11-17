package com.bethanysbakery.api.auth.otp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bethanysbakery.api.auth.AuthException;
import com.bethanysbakery.api.domain.auth.OtpChannel;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class UnconfiguredOtpSenderTest {

    private final UnconfiguredOtpSender sender = new UnconfiguredOtpSender();

    @Test
    void failsClosedOutsideLocalProfiles() {
        assertThatThrownBy(() -> sender.send(OtpChannel.EMAIL, "a@example.com", "123456",
            Instant.now().plusSeconds(600)))
            .isInstanceOfSatisfying(AuthException.class, ex -> {
                assertThat(ex.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(ex.code()).isEqualTo("NOT_CONFIGURED");
            });
    }
}
