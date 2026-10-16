package com.bridgeops.identity.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class UserAccount {
    @Id private UUID id;
    @Column(nullable = false, length = 254) private String email;
    @Column(nullable = false, length = 254) private String normalizedEmail;
    @Column(nullable = false, length = 100) private String passwordHash;
    @Column(nullable = false, length = 120) private String displayName;
    @Column(nullable = false, length = 16) private String accountStatus;
    @Column(nullable = false) private Instant createdAt;

    private Instant emailVerifiedAt;
    public boolean isVerified(){return emailVerifiedAt!=null;}
    public void changePasswordHash(String value){passwordHash=value;}
    protected UserAccount() { }
    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getDisplayName() { return displayName; }
    public String getPasswordHash() { return passwordHash; }
    public boolean isActive() { return "ACTIVE".equals(accountStatus); }
}
