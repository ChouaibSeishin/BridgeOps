package com.bridgeops.organizations.persistence;

import com.bridgeops.shared.web.DomainFailure;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class InvitationStore {
 public record Invitation(UUID id,UUID organizationId,String email,UUID issuerMembership,Instant expiresAt,Instant acceptedAt,Instant revokedAt){}
 private final JdbcTemplate jdbc;public InvitationStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
 private List<Invitation> rows(String condition,Object...args){return jdbc.query("SELECT id,organization_id,normalized_email,created_by_membership_id,expires_at,accepted_at,revoked_at FROM invitations WHERE "+condition,
    (rs,n)->new Invitation(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getObject(4,UUID.class),rs.getTimestamp(5).toInstant(),rs.getTimestamp(6)==null?null:rs.getTimestamp(6).toInstant(),rs.getTimestamp(7)==null?null:rs.getTimestamp(7).toInstant()),args);}
 public Invitation hash(String hash){var values=rows("token_hash=?",hash);if(values.isEmpty())throw rejected();return values.getFirst();}
 public Invitation id(UUID org,UUID id){var values=rows("organization_id=? AND id=?",org,id);if(values.isEmpty())throw new DomainFailure(404,"NOT_FOUND");return values.getFirst();}
 public boolean memberExists(UUID org,UUID user){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM organization_memberships WHERE organization_id=? AND user_id=?)",Boolean.class,org,user));}
 public List<UUID> roles(UUID org,UUID invitation){return jdbc.queryForList("SELECT role_id FROM invitation_roles WHERE organization_id=? AND invitation_id=?",UUID.class,org,invitation);}
 public Invitation create(UUID org,UUID issuer,String email,String hash,Set<UUID> roles,Instant now){
    jdbc.update("UPDATE invitations SET revoked_at=? WHERE organization_id=? AND normalized_email=? AND accepted_at IS NULL AND revoked_at IS NULL AND expires_at<=?",java.sql.Timestamp.from(now),org,email,java.sql.Timestamp.from(now));
    UUID id=UUID.randomUUID();jdbc.update("INSERT INTO invitations(id,organization_id,normalized_email,token_hash,created_by_membership_id,created_at,expires_at) VALUES(?,?,?,?,?,?,?)",id,org,email,hash,issuer,java.sql.Timestamp.from(now),java.sql.Timestamp.from(now.plusSeconds(172800)));
    roles.forEach(role->jdbc.update("INSERT INTO invitation_roles VALUES(?,?,?)",org,id,role));return id(org,id);
 }
 public UUID join(Invitation invitation,UUID user,List<UUID> roles,Instant now){
    if(memberExists(invitation.organizationId(),user))throw rejected();UUID id=UUID.randomUUID();
    jdbc.update("INSERT INTO organization_memberships(id,organization_id,user_id,status,joined_at) VALUES(?,?,?,'ACTIVE',?)",id,invitation.organizationId(),user,java.sql.Timestamp.from(now));
    roles.forEach(role->jdbc.update("INSERT INTO membership_roles VALUES(?,?,?)",invitation.organizationId(),id,role));return id;
 }
 public void consume(UUID org,UUID invitation,Instant now){
    if(jdbc.update("UPDATE invitations SET accepted_at=? WHERE organization_id=? AND id=? AND accepted_at IS NULL AND revoked_at IS NULL AND expires_at>?",java.sql.Timestamp.from(now),org,invitation,java.sql.Timestamp.from(now))!=1)throw rejected();
 }
 public void revoke(UUID org,UUID invitation,Instant now){jdbc.update("UPDATE invitations SET revoked_at=? WHERE organization_id=? AND id=? AND accepted_at IS NULL AND revoked_at IS NULL",java.sql.Timestamp.from(now),org,invitation);}
 public static DomainFailure rejected(){return new DomainFailure(400,"INVITATION_REJECTED");}
}
