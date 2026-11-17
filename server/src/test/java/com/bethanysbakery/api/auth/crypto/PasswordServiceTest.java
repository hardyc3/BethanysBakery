package com.bethanysbakery.api.auth.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordServiceTest {

    private final PasswordService passwords = new PasswordService();

    @Test
    void hashesWithArgon2idAndVerifies() {
        String hash = passwords.hash("correct horse battery staple");

        assertThat(hash).startsWith("$argon2id$");
        assertThat(passwords.matches("correct horse battery staple", hash)).isTrue();
        assertThat(passwords.matches("wrong password", hash)).isFalse();
    }

    @Test
    void saltsEveryHash() {
        assertThat(passwords.hash("same")).isNotEqualTo(passwords.hash("same"));
    }
}
