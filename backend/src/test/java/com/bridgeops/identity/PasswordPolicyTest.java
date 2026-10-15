package com.bridgeops.identity;

import com.bridgeops.identity.application.AccountPasswords;
import com.bridgeops.identity.application.RefreshTokens;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PasswordPolicyTest {
    private final AccountPasswords passwords = new AccountPasswords();

    @Test
    void bcryptPolicyEnforcesBytesInsteadOfSilentlyTruncating() {
        assertThatThrownBy(() -> passwords.hash("short")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> passwords.hash("é".repeat(37))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> passwords.hash("😀".repeat(6))).isInstanceOf(IllegalArgumentException.class);
        String hash = passwords.hash("synthetic-test-password");
        assertThat(hash).startsWith("$2a$12$");
        assertThat(passwords.matches("synthetic-test-password", hash)).isTrue();
        assertThat(passwords.matches("wrong", hash)).isFalse();
        assertThat(passwords.matches("é".repeat(37), hash)).isFalse();
        assertThat(passwords.matches("synthetic-test-password", null)).isFalse();
    }

    @Test
    void tokenGeneratorUses256BitsAndHashesItsCredential() {
        RefreshTokens tokens = new RefreshTokens();
        String first = tokens.generate();
        String second = tokens.generate();
        assertThat(tokens.isWellFormed(first)).isTrue();
        assertThat(first.equals(second)).isFalse();
        assertThat(tokens.hash(first)).matches("[a-f0-9]{64}");
        assertThat(tokens.isWellFormed(tokens.hash(first))).isFalse();
    }
}
