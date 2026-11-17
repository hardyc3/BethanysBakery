package com.bethanysbakery.api.auth.crypto;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class PasswordService {
    private final PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    public String hash(String password) {
        return encoder.encode(password);
    }

    public boolean matches(String password, String hash) {
        return encoder.matches(password, hash);
    }
}
