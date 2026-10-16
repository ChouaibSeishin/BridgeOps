package com.bridgeops.identity.application;

import com.bridgeops.shared.web.DomainFailure;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Public identity application boundary; other modules never import identity repositories/entities. */
@Service
public class AccountAuthority {
    private final JdbcTemplate jdbc;private final PasswordSessionService sessions;
    public AccountAuthority(JdbcTemplate jdbc,PasswordSessionService sessions){this.jdbc=jdbc;this.sessions=sessions;}
    @Transactional(timeout=10)
    public void lockLive(UUID user,UUID family){
        if(jdbc.queryForList("SELECT id FROM users WHERE id=? AND account_status='ACTIVE' FOR UPDATE",user).isEmpty() || !sessions.isActive(user,family))
            throw new DomainFailure(401,"AUTHENTICATION_REQUIRED");
    }
    @Transactional(readOnly=true,timeout=5)
    public void requireLive(UUID user,UUID family){if(!sessions.isActive(user,family))throw new DomainFailure(401,"AUTHENTICATION_REQUIRED");}
    @Transactional(readOnly=true,timeout=5)
    public boolean active(UUID user){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM users WHERE id=? AND account_status=\'ACTIVE\')",Boolean.class,user));}
    @Transactional(timeout=10)
    public void requireOrganizationGrant(UUID user){
        if(jdbc.queryForList("SELECT user_id FROM user_grants WHERE user_id=? AND grant_code='organization.create'",user).isEmpty())
            throw new DomainFailure(403,"PERMISSION_DENIED");
    }
}
