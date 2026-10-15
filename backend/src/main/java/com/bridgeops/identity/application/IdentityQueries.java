package com.bridgeops.identity.application;

import com.bridgeops.identity.persistence.UserAccountRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityQueries {
    public record UserSummary(UUID id, String email, String displayName) { }
    private final UserAccountRepository users;
    public IdentityQueries(UserAccountRepository users) { this.users = users; }
    @Transactional(readOnly=true,timeout=5)
    public UserSummary summary(UUID userId) {
        var user = users.findById(userId).orElseThrow();
        return new UserSummary(user.getId(),user.getEmail(),user.getDisplayName());
    }
}