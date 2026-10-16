package com.bridgeops.identity.application;

import com.bridgeops.identity.persistence.UserAccountRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityQueries {
    public record UserSummary(UUID id, String email, String displayName, boolean canCreateOrganizations) { }
    private final UserAccountRepository users; private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    public IdentityQueries(UserAccountRepository users,org.springframework.jdbc.core.JdbcTemplate jdbc) { this.users = users; this.jdbc=jdbc; }
    @Transactional(readOnly=true,timeout=5)
    public UserSummary summary(UUID userId) {
        var user = users.findById(userId).orElseThrow();
        return new UserSummary(user.getId(),user.getEmail(),user.getDisplayName(),user.isActive() && Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM user_grants WHERE user_id=? AND grant_code='organization.create')",Boolean.class,userId)));
    }
}