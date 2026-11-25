package com.bethanysbakery.api.auth.otp;

import com.bethanysbakery.api.auth.crypto.CryptoService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Production/local code source: cryptographically random six digits. */
@Component
@Profile("!dummy")
public class RandomOtpCodeGenerator implements OtpCodeGenerator {

    private final CryptoService crypto;

    public RandomOtpCodeGenerator(CryptoService crypto) {
        this.crypto = crypto;
    }

    @Override
    public String generate() {
        return crypto.randomDigits(6);
    }
}
