package com.bridgeops.identity.application;

import java.nio.charset.StandardCharsets;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AccountPasswords {
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
    private final String dummyHash = encoder.encode("non-account-timing-placeholder");

    public String hash(String password) {
        if (password == null || password.codePointCount(0, password.length()) < 12 || utf8Length(password) > 72) {
            throw new IllegalArgumentException("Password must contain at least 12 Unicode code points and at most 72 UTF-8 bytes.");
        }
        return encoder.encode(password);
    }
    public boolean matches(String password, String storedHash) {
        if (password == null || utf8Length(password) > 72) return false;
        return encoder.matches(password, storedHash == null ? dummyHash : storedHash) && storedHash != null;
    }
    private static int utf8Length(String password) { return password.getBytes(StandardCharsets.UTF_8).length; }
}
