package com.bridgeops.identity.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "identity_security_events")
public class IdentitySecurityEvent {
    @Id private UUID id;
    private UUID userId;
    private UUID familyId;
    @Column(nullable = false, length = 48) private String eventType;
    @Column(nullable = false) private Instant occurredAt;

    protected IdentitySecurityEvent() { }
    public IdentitySecurityEvent(UUID userId, UUID familyId, String eventType, Instant now) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.familyId = familyId;
        this.eventType = eventType;
        this.occurredAt = now;
    }
}
