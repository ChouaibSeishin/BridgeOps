package com.bridgeops.identity.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "session_families")
public class SessionFamily {
    @Id private UUID id;
    @Column(nullable = false) private UUID userId;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant expiresAt;
    private Instant revokedAt;

    protected SessionFamily() { }
    public SessionFamily(UUID userId, Instant now, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.createdAt = now;
        this.expiresAt = expiresAt;
    }
    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean isRevoked() { return revokedAt != null; }
    public boolean isExpired(Instant now) { return !now.isBefore(expiresAt); }
    public void revoke(Instant now) { if (revokedAt == null) revokedAt = now; }
}
