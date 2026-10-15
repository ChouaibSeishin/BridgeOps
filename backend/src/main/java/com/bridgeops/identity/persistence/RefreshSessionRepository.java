package com.bridgeops.identity.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshSessionRepository extends JpaRepository<RefreshSession, UUID> {
    Optional<RefreshSession> findByTokenHash(String tokenHash);

    // Projection avoids caching stale token/family entities before the serialization lock is held.
    @Query("select t.familyId as familyId, f.userId as userId from RefreshSession t, SessionFamily f "
            + "where t.familyId = f.id and t.tokenHash = :hash")
    Optional<SessionReference> locate(@Param("hash") String hash);

    interface SessionReference {
        UUID getFamilyId();
        UUID getUserId();
    }
}
