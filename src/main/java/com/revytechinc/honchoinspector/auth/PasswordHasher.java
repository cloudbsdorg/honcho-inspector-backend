package com.revytechinc.honchoinspector.auth;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class PasswordHasher {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);

    public String hash(String password) {
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("password must not be blank");
        }
        return encoder.encode(password);
    }

    public boolean verify(String password, String hash) {
        if (password == null || hash == null || hash.isBlank()) {
            return false;
        }
        return encoder.matches(password, hash);
    }
}
