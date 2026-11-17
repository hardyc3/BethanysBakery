package com.bethanysbakery.api.auth.crypto;

import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Service;

@Service
public class TotpService {
    private final DefaultSecretGenerator secretGenerator = new DefaultSecretGenerator(32);
    private final CodeVerifier verifier;

    public TotpService() {
        CodeGenerator generator = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);
        DefaultCodeVerifier delegate = new DefaultCodeVerifier(generator, new SystemTimeProvider());
        delegate.setAllowedTimePeriodDiscrepancy(1);
        this.verifier = delegate;
    }

    public String generateSecret() {
        return secretGenerator.generate();
    }

    public boolean verify(String secret, String code) {
        return code != null && !code.isBlank() && verifier.isValidCode(secret, code);
    }

    public String otpauthUri(String issuer, String accountName, String secret) {
        String label = URLEncoder.encode(issuer + ":" + accountName, StandardCharsets.UTF_8);
        String issuerParam = URLEncoder.encode(issuer, StandardCharsets.UTF_8);
        return "otpauth://totp/" + label + "?secret=" + secret + "&issuer=" + issuerParam + "&algorithm=SHA1&digits=6&period=30";
    }
}
