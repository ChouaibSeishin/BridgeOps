package com.bridgeops.identity.application;

import com.bridgeops.identity.domain.SessionOutcome;
import com.bridgeops.identity.domain.SessionOutcome.Denied;
import com.bridgeops.identity.domain.SessionOutcome.Granted;
import com.bridgeops.identity.domain.SessionOutcome.Rejection;
import com.bridgeops.identity.persistence.IdentitySecurityEvent;
import com.bridgeops.identity.persistence.IdentitySecurityEventRepository;
import com.bridgeops.identity.persistence.RefreshSession;
import com.bridgeops.identity.persistence.RefreshSessionRepository;
import com.bridgeops.identity.persistence.SessionFamily;
import com.bridgeops.identity.persistence.SessionFamilyRepository;
import com.bridgeops.identity.persistence.UserAccount;
import com.bridgeops.identity.persistence.UserAccountRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Internal boundary only. HTTP adapters must add CSRF/Origin, throttling and access-token checks. */
@Service
public class PasswordSessionService {
    private static final Duration FAMILY_LIFETIME = Duration.ofDays(7);
    private final UserAccountRepository users;
    private final SessionFamilyRepository families;
    private final RefreshSessionRepository credentials;
    private final IdentitySecurityEventRepository events;
    private final AccountPasswords passwords;
    private final RefreshTokens tokens;
    private final Clock clock;

    public PasswordSessionService(UserAccountRepository users, SessionFamilyRepository families,
            RefreshSessionRepository credentials, IdentitySecurityEventRepository events,
            AccountPasswords passwords, RefreshTokens tokens, Clock clock) {
        this.users = users; this.families = families; this.credentials = credentials;
        this.events = events; this.passwords = passwords; this.tokens = tokens; this.clock = clock;
    }

    @Transactional(timeout = 10)
    public SessionOutcome authenticate(String email, String password) {
        String normalized = normalize(email);
        UserAccount user = normalized == null ? null : users.lockByEmail(normalized).orElse(null);
        boolean validPassword = passwords.matches(password, user == null ? null : user.getPasswordHash());
        if (user == null || !validPassword || !user.isActive()) {
            event(user == null ? null : user.getId(), null, "LOGIN_REJECTED");
            return new Denied(Rejection.INVALID_CREDENTIALS);
        }
        Instant now = clock.instant();
        SessionFamily family = families.save(new SessionFamily(user.getId(), now, now.plus(FAMILY_LIFETIME)));
        event(user.getId(), family.getId(), "SESSION_STARTED");
        return issue(family, null, now);
    }

    @Transactional(timeout = 10)
    public SessionOutcome rotate(String rawToken) {
        if (!tokens.isWellFormed(rawToken)) return new Denied(Rejection.INVALID_REFRESH);
        String hash = tokens.hash(rawToken);
        RefreshSessionRepository.SessionReference candidate = credentials.locate(hash).orElse(null);
        if (candidate == null) return new Denied(Rejection.INVALID_REFRESH);
        // Every rotation/revocation uses account -> family lock order. This also serializes logout-all.
        UserAccount user = users.lockById(candidate.getUserId()).orElseThrow();
        SessionFamily family = families.lockById(candidate.getFamilyId()).orElseThrow();
        RefreshSession credential = credentials.findByTokenHash(hash).orElseThrow();
        Instant now = clock.instant();
        if (!user.isActive()) {
            family.revoke(now);
            event(user.getId(), family.getId(), "INACTIVE_ACCOUNT_REJECTED");
            return new Denied(Rejection.ACCOUNT_DISABLED);
        }
        if (family.isRevoked()) return new Denied(Rejection.REVOKED);
        if (family.isExpired(now)) {
            family.revoke(now);
            event(user.getId(), family.getId(), "SESSION_EXPIRED");
            return new Denied(Rejection.EXPIRED);
        }
        if (credential.isConsumed()) {
            family.revoke(now);
            event(user.getId(), family.getId(), "REFRESH_REUSE_DETECTED");
            // Return a rejection so this security mutation commits instead of rolling back on an exception.
            return new Denied(Rejection.REUSED);
        }
        credential.consume(now);
        event(user.getId(), family.getId(), "REFRESH_ROTATED");
        return issue(family, credential.getId(), now);
    }

    /** Caller identity must come from the validated access-token context, never a request-body user ID. */
    @Transactional(timeout = 10)
    public boolean revokeFamily(UUID authenticatedUserId, UUID familyId) {
        if (users.lockById(authenticatedUserId).isEmpty()) return false;
        SessionFamily family = families.lockById(familyId).orElse(null);
        if (family == null || !family.getUserId().equals(authenticatedUserId)) return false;
        family.revoke(clock.instant());
        event(authenticatedUserId, familyId, "SESSION_REVOKED");
        return true;
    }

    @Transactional(timeout = 10)
    public void revokeAll(UUID authenticatedUserId) {
        if (users.lockById(authenticatedUserId).isEmpty()) return;
        Instant now = clock.instant();
        families.findByUserIdAndRevokedAtIsNull(authenticatedUserId).forEach(family -> family.revoke(now));
        event(authenticatedUserId, null, "ALL_SESSIONS_REVOKED");
    }

    @Transactional(readOnly = true, timeout = 5)
    public boolean isActive(UUID userId, UUID familyId) {
        SessionFamily family = families.findById(familyId).orElse(null);
        return family != null && family.getUserId().equals(userId) && !family.isRevoked()
                && !family.isExpired(clock.instant()) && users.findById(userId).map(UserAccount::isActive).orElse(false);
    }

    private Granted issue(SessionFamily family, UUID parent, Instant now) {
        String raw = tokens.generate();
        credentials.save(new RefreshSession(family.getId(), parent, tokens.hash(raw), now, family.getExpiresAt()));
        return new Granted(family.getUserId(), family.getId(), family.getExpiresAt(), raw);
    }
    private void event(UUID user, UUID family, String type) {
        events.save(new IdentitySecurityEvent(user, family, type, clock.instant()));
    }
    private static String normalize(String email) {
        if (email == null) return null;
        String trimmed = email.trim();
        int at = trimmed.indexOf('@');
        if (trimmed.length() > 254 || !trimmed.matches("[\\x21-\\x7E]+")
                || at <= 0 || at == trimmed.length() - 1 || at != trimmed.lastIndexOf('@')) return null;
        return trimmed.toLowerCase(Locale.ROOT);
    }
}
