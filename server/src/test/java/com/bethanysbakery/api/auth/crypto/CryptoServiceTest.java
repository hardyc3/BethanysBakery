package com.bethanysbakery.api.auth.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bethanysbakery.api.auth.AuthException;
import com.bethanysbakery.api.support.TestProperties;
import org.junit.jupiter.api.Test;

class CryptoServiceTest {

    private final CryptoService crypto = new CryptoService(TestProperties.defaults());

    @Test
    void aesGcmRoundTripsAndUsesRandomIv() {
        String plaintext = "user@example.com";

        String first = crypto.encrypt(plaintext);
        String second = crypto.encrypt(plaintext);

        assertThat(first).isNotEqualTo(second);
        assertThat(crypto.decrypt(first)).isEqualTo(plaintext);
        assertThat(crypto.decrypt(second)).isEqualTo(plaintext);
    }

    @Test
    void rejectsTamperedCiphertext() {
        String encrypted = crypto.encrypt("+15555550100");
        char[] chars = encrypted.toCharArray();
        chars[0] = chars[0] == 'A' ? 'B' : 'A';
        String tampered = new String(chars);

        assertThat(tampered).isNotEqualTo(encrypted);
        assertThatThrownBy(() -> crypto.decrypt(tampered))
            .isInstanceOf(AuthException.class);
    }

    @Test
    void hashesAreDeterministicAndPepperedSeparately() {
        assertThat(crypto.sha256Hex("code")).isEqualTo(crypto.sha256Hex("code"));
        assertThat(crypto.hmacSha256Hex("code")).isEqualTo(crypto.hmacSha256Hex("code"));
        assertThat(crypto.sha256Hex("code")).isNotEqualTo(crypto.hmacSha256Hex("code"));
        assertThat(crypto.sha256Hex("code")).hasSize(64);
    }

    @Test
    void randomValuesHaveExpectedShape() {
        String token = crypto.randomToken(32);
        assertThat(token).hasSizeGreaterThanOrEqualTo(42);
        assertThat(crypto.randomToken(32)).isNotEqualTo(token);
        assertThat(crypto.randomDigits(6)).matches("^[0-9]{6}$");
    }

    @Test
    void constantTimeEqualsOnlyMatchesIdenticalValues() {
        assertThat(crypto.constantTimeEquals("abc", "abc")).isTrue();
        assertThat(crypto.constantTimeEquals("abc", "abd")).isFalse();
        assertThat(crypto.constantTimeEquals(null, "abc")).isFalse();
        assertThat(crypto.constantTimeEquals("abc", null)).isFalse();
    }
}
