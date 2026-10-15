package com.bridgeops.identity.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_sessions")
public class RefreshSession {
    @Id private UUID id;
    @Column(nullable = false) private UUID familyId;
    private UUID parentSessionId;
    @Column(nullable = false, length = 64) private String tokenHash;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false) private Instant expiresAt;
    private Instant consumedAt;

    protected RefreshSession() { }
    public RefreshSession(UUID familyId, UUID parentSessionId, String hash, Instant now, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.familyId = familyId;
        this.parentSessionId = parentSessionId;
        this.tokenHash = hash;
        this.createdAt = now;
        this.expiresAt = expiresAt;
    }
    public UUID getId() { return id; }
    public UUID getFamilyId() { return familyId; }
    public boolean isConsumed() { return consumedAt != null; }
    public void consume(Instant now) { consumedAt = now; }
}
