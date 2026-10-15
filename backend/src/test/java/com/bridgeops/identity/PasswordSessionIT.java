package com.bridgeops.identity;

import com.bridgeops.identity.application.AccountPasswords;
import com.bridgeops.identity.application.PasswordSessionService;
import com.bridgeops.identity.domain.SessionOutcome;
import com.bridgeops.identity.domain.SessionOutcome.Denied;
import com.bridgeops.identity.domain.SessionOutcome.Granted;
import com.bridgeops.identity.domain.SessionOutcome.Rejection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Testcontainers
@Import(PasswordSessionIT.TimeConfiguration.class)
class PasswordSessionIT {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse(
            "postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873")
            .asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
    }
    @Autowired PasswordSessionService sessions;
    @Autowired AccountPasswords passwords;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestClock clock;
    private UUID userId;
    private static String passwordHash;

    @BeforeEach
    void fixture() {
        clock.set(Instant.parse("2026-10-15T12:00:00Z"));
        jdbc.update("DELETE FROM identity_security_events");
        jdbc.update("DELETE FROM refresh_sessions");
        jdbc.update("DELETE FROM session_families");
        jdbc.update("DELETE FROM users");
        if (passwordHash == null) passwordHash = passwords.hash("synthetic-test-password");
        userId = insertUser("owner@example.invalid", "ACTIVE");
    }

    @Test
    void authenticationRejectsInvalidUnknownAndDisabledAccountsUniformly() {
        assertThat(sessions.authenticate("owner@example.invalid", "wrong")).isEqualTo(new Denied(Rejection.INVALID_CREDENTIALS));
        assertThat(sessions.authenticate("missing@example.invalid", "synthetic-test-password")).isEqualTo(new Denied(Rejection.INVALID_CREDENTIALS));
        jdbc.update("UPDATE users SET account_status='DISABLED' WHERE id=?", userId);
        assertThat(sessions.authenticate("owner@example.invalid", "synthetic-test-password")).isEqualTo(new Denied(Rejection.INVALID_CREDENTIALS));
        assertThat(count("session_families")).isZero();
        assertThat(count("identity_security_events")).isEqualTo(3);
    }

    @Test
    void rotationRetainsConsumedHashAndNeverExtendsAbsoluteFamilyExpiry() {
        Granted first = login();
        clock.set(clock.instant().plus(Duration.ofDays(2)));
        Granted second = grant(sessions.rotate(first.refreshToken()));
        assertThat(second.familyId()).isEqualTo(first.familyId());
        assertThat(second.expiresAt()).isEqualTo(first.expiresAt());
        assertThat(count("refresh_sessions")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_sessions WHERE consumed_at IS NOT NULL", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT bool_and(length(token_hash)=64) FROM refresh_sessions", Boolean.class)).isTrue();
        assertThat(second.toString().contains(second.refreshToken())).isFalse();
    }

    @Test
    void replayRevokesTheEntireFamilyAndPersistsItsSecurityEvent() {
        Granted first = login();
        Granted second = grant(sessions.rotate(first.refreshToken()));
        assertThat(sessions.rotate(first.refreshToken())).isEqualTo(new Denied(Rejection.REUSED));
        assertThat(sessions.isActive(userId, first.familyId())).isFalse();
        assertThat(sessions.rotate(second.refreshToken())).isEqualTo(new Denied(Rejection.REVOKED));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_security_events WHERE event_type='REFRESH_REUSE_DETECTED'", Integer.class)).isEqualTo(1);
    }

    @Test
    void expiryAtTheExactBoundaryRejectsRefresh() {
        Granted first = login();
        clock.set(first.expiresAt());
        assertThat(sessions.rotate(first.refreshToken())).isEqualTo(new Denied(Rejection.EXPIRED));
        assertThat(sessions.isActive(userId, first.familyId())).isFalse();
    }

    @Test
    void logoutAllRevokesEveryFamilyAndOtherUsersCannotRevokeOne() {
        Granted first = login();
        Granted second = login();
        UUID other = insertUser("other@example.invalid", "ACTIVE");
        assertThat(sessions.revokeFamily(other, first.familyId())).isFalse();
        assertThat(sessions.isActive(other, first.familyId())).isFalse();
        assertThat(sessions.isActive(userId, first.familyId())).isTrue();
        sessions.revokeAll(userId);
        assertThat(sessions.rotate(first.refreshToken())).isEqualTo(new Denied(Rejection.REVOKED));
        assertThat(sessions.isActive(userId, second.familyId())).isFalse();
    }

    @Test
    void individualRevocationIsIdempotentAndDisabledAccountsLoseSessionAccess() {
        Granted first = login();
        assertThat(sessions.revokeFamily(userId, first.familyId())).isTrue();
        assertThat(sessions.revokeFamily(userId, first.familyId())).isTrue();
        assertThat(sessions.rotate(first.refreshToken())).isEqualTo(new Denied(Rejection.REVOKED));
        Granted second = login();
        jdbc.update("UPDATE users SET account_status='DISABLED' WHERE id=?", userId);
        assertThat(sessions.isActive(userId, second.familyId())).isFalse();
        assertThat(sessions.rotate(second.refreshToken())).isEqualTo(new Denied(Rejection.ACCOUNT_DISABLED));
    }

    @Test
    void malformedAndUnknownCredentialsCannotCreateSessions() {
        assertThat(sessions.rotate(null)).isEqualTo(new Denied(Rejection.INVALID_REFRESH));
        assertThat(sessions.rotate("bad")).isEqualTo(new Denied(Rejection.INVALID_REFRESH));
        assertThat(sessions.rotate("a".repeat(43))).isEqualTo(new Denied(Rejection.INVALID_REFRESH));
        assertThat(count("session_families")).isZero();
    }

    @Test
    void simultaneousRefreshCreatesOneSuccessorThenRevokesOnStrictReplayPolicy() throws Exception {
        Granted first = login();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var left = executor.submit(() -> { start.await(); return sessions.rotate(first.refreshToken()); });
            var right = executor.submit(() -> { start.await(); return sessions.rotate(first.refreshToken()); });
            start.countDown();
            List<SessionOutcome> results = List.of(left.get(15, TimeUnit.SECONDS), right.get(15, TimeUnit.SECONDS));
            assertThat(results.stream().filter(Granted.class::isInstance).count()).isEqualTo(1);
            assertThat(results).contains(new Denied(Rejection.REUSED));
        }
        assertThat(count("refresh_sessions")).isEqualTo(2);
        assertThat(sessions.isActive(userId, first.familyId())).isFalse();
    }

    @Test
    void databaseEnforcesNormalizedEmailUniqueness() {
        assertThatThrownBy(() -> insertUser("owner@example.invalid", "ACTIVE"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsForgedEmailNormalization() {
        assertThatThrownBy(() -> jdbc.update("UPDATE users SET normalized_email='forged@example.invalid' WHERE id=?", userId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT normalized_email FROM users WHERE id=?", String.class, userId))
                .isEqualTo("owner@example.invalid");
    }
    private Granted login() { return grant(sessions.authenticate(" OWNER@EXAMPLE.INVALID ", "synthetic-test-password")); }
    private static Granted grant(SessionOutcome result) {
        assertThat(result).isInstanceOf(Granted.class);
        return (Granted) result;
    }
    private int count(String table) {
        // All callers use fixed test-owned table constants, never request input.
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
    private UUID insertUser(String email, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,email,normalized_email,password_hash,display_name,account_status,created_at) VALUES (?,?,?,?,?,?,?)",
                id, email, email, passwordHash, "Synthetic account", status, java.sql.Timestamp.from(clock.instant()));
        return id;
    }

    @TestConfiguration
    static class TimeConfiguration {
        @Bean @Primary TestClock testClock() { return new TestClock(); }
    }
    static class TestClock extends Clock {
        private volatile Instant now = Instant.parse("2026-10-15T12:00:00Z");
        void set(Instant instant) { now = instant; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException("UTC only");
            return this;
        }
        @Override public Instant instant() { return now; }
    }
}
