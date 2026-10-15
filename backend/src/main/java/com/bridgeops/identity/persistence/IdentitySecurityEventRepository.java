package com.bridgeops.identity.persistence;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdentitySecurityEventRepository extends JpaRepository<IdentitySecurityEvent, UUID> { }
