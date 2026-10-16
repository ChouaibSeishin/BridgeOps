package com.bridgeops.identity.application;

import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Shared across instances. Bounded rows; no raw email/IP values, sleeps or password work under the lock. */
@Service
public class AuthenticationThrottle {
    private final JdbcTemplate jdbc;
    private final RefreshTokens hashes;
    private final Clock clock;
    private record Bucket(String key, int limit, int seconds) { }
    public AuthenticationThrottle(JdbcTemplate jdbc, RefreshTokens hashes, Clock clock) { this.jdbc=jdbc; this.hashes=hashes; this.clock=clock; }
    @Transactional(timeout=5)
    public long acquire(String operation, String peer, String email) {
        Instant now = clock.instant();
        // The single short allocation lock also makes row-cap enforcement atomic across replicas.
        jdbc.execute("SELECT pg_advisory_xact_lock(20261015, 12)");
        jdbc.update("DELETE FROM authentication_throttles WHERE expires_at <= ?",java.sql.Timestamp.from(now));
        List<Bucket> buckets = new ArrayList<>();
        buckets.add(new Bucket("global",300,60));
        buckets.add(new Bucket("peer:" + peer,120,60));
        if (Set.of("login","invite","enroll","forgot","reset","password-change").contains(operation)) {
            buckets.add(new Bucket(operation + "-peer:" + peer,20,900));
            buckets.add(new Bucket(operation + "-account:" + email.trim().toLowerCase(Locale.ROOT),10,900));
        }
        long retry = 0;
        for (Bucket bucket : buckets) {
            String hash = hashes.hash(bucket.key());
            var rows = jdbc.query("SELECT attempts, expires_at FROM authentication_throttles WHERE bucket_hash=?",
                    (rs,n) -> new AbstractMap.SimpleImmutableEntry<>(rs.getInt(1),rs.getTimestamp(2).toInstant()),hash);
            if (rows.isEmpty()) {
                if (jdbc.queryForObject("SELECT count(*) FROM authentication_throttles",Integer.class) >= 10000) return 60;
                jdbc.update("INSERT INTO authentication_throttles(bucket_hash,attempts,expires_at) VALUES(?,1,?)",hash,
                        java.sql.Timestamp.from(now.plusSeconds(bucket.seconds())));
            } else {
                var current = rows.getFirst();
                if (current.getKey() >= bucket.limit()) retry = Math.max(retry,Math.max(1,Duration.between(now,current.getValue()).toSeconds()));
                jdbc.update("UPDATE authentication_throttles SET attempts=LEAST(attempts+1, ?) WHERE bucket_hash=?",bucket.limit()+1,hash);
            }
        }
        return retry;
    }
}