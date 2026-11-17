package com.bethanysbakery.api.auth.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.HashingAlgorithm;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TotpServiceTest {

    private final TotpService totp = new TotpService();
    private final DefaultCodeGenerator generator = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);

    @Test
    void verifiesCurrentCodeAndRejectsWrongCode() throws Exception {
        String secret = totp.generateSecret();
        String code = currentCode(secret);

        assertThat(totp.verify(secret, code)).isTrue();
        assertThat(totp.verify(secret, "000000")).isFalse();
        assertThat(totp.verify(secret, null)).isFalse();
        assertThat(totp.verify(secret, "")).isFalse();
    }

    @Test
    void toleratesOneTimeStepOfClockDrift() throws Exception {
        String secret = totp.generateSecret();
        long previousPeriod = Math.floorDiv(Instant.now().getEpochSecond(), 30) - 1;

        assertThat(totp.verify(secret, generator.generate(secret, previousPeriod))).isTrue();
    }

    @Test
    void generatedSecretsAreRandom() {
        assertThat(totp.generateSecret()).isNotEqualTo(totp.generateSecret()).hasSizeGreaterThanOrEqualTo(32);
    }

    @Test
    void buildsOtpauthUri() {
        String uri = totp.otpauthUri("Bethany's Bakery Admin", "admin@example.com", "SECRET");

        assertThat(uri).startsWith("otpauth://totp/");
        assertThat(uri).contains("secret=SECRET", "algorithm=SHA1", "digits=6", "period=30");
    }

    private String currentCode(String secret) throws Exception {
        return generator.generate(secret, Math.floorDiv(Instant.now().getEpochSecond(), 30));
    }
}
