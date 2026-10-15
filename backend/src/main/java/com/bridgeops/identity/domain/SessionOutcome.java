package com.bridgeops.identity.domain;

import java.time.Instant;
import java.util.UUID;

public sealed interface SessionOutcome permits SessionOutcome.Granted, SessionOutcome.Denied {
    enum Rejection { INVALID_CREDENTIALS, INVALID_REFRESH, EXPIRED, REVOKED, REUSED, ACCOUNT_DISABLED }
    record Denied(Rejection reason) implements SessionOutcome { }

    // Unlike a record, diagnostic output must never include the bearer credential.
    final class Granted implements SessionOutcome {
        private final UUID userId;
        private final UUID familyId;
        private final Instant expiresAt;
        private final String refreshToken;

        public Granted(UUID userId, UUID familyId, Instant expiresAt, String refreshToken) {
            this.userId = userId;
            this.familyId = familyId;
            this.expiresAt = expiresAt;
            this.refreshToken = refreshToken;
        }
        public UUID userId() { return userId; }
        public UUID familyId() { return familyId; }
        public Instant expiresAt() { return expiresAt; }
        public String refreshToken() { return refreshToken; }
        @Override public String toString() { return "SessionOutcome.Granted[REDACTED]"; }
    }
}
