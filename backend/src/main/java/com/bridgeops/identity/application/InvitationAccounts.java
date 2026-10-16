package com.bridgeops.identity.application;

import com.bridgeops.shared.web.DomainFailure;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class InvitationAccounts {
 public record Enrolled(UUID userId,UUID membershipId,UUID organizationId){}
 private final JdbcTemplate jdbc;private final AccountPasswords passwords;
 public InvitationAccounts(JdbcTemplate jdbc,AccountPasswords passwords){this.jdbc=jdbc;this.passwords=passwords;}
 @Transactional(readOnly=true,timeout=5) public Optional<UUID> find(String email){return jdbc.queryForList("SELECT id FROM users WHERE normalized_email=?",UUID.class,AccountIdentifiers.email(email)).stream().findFirst();}
 @Transactional(readOnly=true,timeout=5) public String email(UUID user){var values=jdbc.queryForList("SELECT normalized_email FROM users WHERE id=?",String.class,user);if(values.isEmpty())throw new DomainFailure(401,"AUTHENTICATION_REQUIRED");return values.getFirst();}
 /** SQL capability validates raw invitation proof and creates only the invited account/membership. No generic INSERT privilege. */
 @Transactional(propagation=Propagation.MANDATORY)
 public Enrolled enroll(String raw,String email,String displayName,String password){
    String normalized=AccountIdentifiers.email(email),name=AccountIdentifiers.displayName(displayName),hash=passwords.hash(password);
    try{
        var values=jdbc.query("SELECT new_user_id,new_membership_id,tenant_id FROM public.enroll_invitation(?,?,?,?)",
            (rs,n)->new Enrolled(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class)),raw,normalized,hash,name);
        if(values.size()!=1)throw new DomainFailure(400,"INVITATION_REJECTED");return values.getFirst();
    }catch(DataAccessException failure){
        if(failure.getMostSpecificCause() instanceof java.sql.SQLException sql && "22023".equals(sql.getSQLState()))throw new DomainFailure(400,"INVITATION_REJECTED");
        throw failure;
    }
 }
}
