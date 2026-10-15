package com.bridgeops.identity.persistence;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SessionFamilyRepository extends JpaRepository<SessionFamily, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from SessionFamily f where f.id = :id")
    Optional<SessionFamily> lockById(@Param("id") UUID id);
    List<SessionFamily> findByUserIdAndRevokedAtIsNull(UUID userId);
}
