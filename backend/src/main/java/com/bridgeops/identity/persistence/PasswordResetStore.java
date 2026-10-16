package com.bridgeops.identity.persistence;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
@Repository
public class PasswordResetStore {
 public record Proof(UUID id,UUID userId,Instant expiresAt,Instant consumedAt){}
 private final JdbcTemplate jdbc;public PasswordResetStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public Optional<Proof> find(String hash,boolean lock){return jdbc.query("SELECT id,user_id,expires_at,consumed_at FROM password_reset_tokens WHERE token_hash=?"+(lock?" FOR UPDATE":""),(rs,n)->new Proof(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getTimestamp(3).toInstant(),rs.getTimestamp(4)==null?null:rs.getTimestamp(4).toInstant()),hash).stream().findFirst();}
 public void invalidate(UUID user,Instant now){jdbc.update("UPDATE password_reset_tokens SET consumed_at=? WHERE user_id=? AND consumed_at IS NULL",java.sql.Timestamp.from(now),user);}
 public void create(UUID user,String hash,Instant now){jdbc.update("INSERT INTO password_reset_tokens(id,user_id,token_hash,created_at,expires_at) VALUES(?,?,?,?,?)",UUID.randomUUID(),user,hash,java.sql.Timestamp.from(now),java.sql.Timestamp.from(now.plusSeconds(900)));}
 public boolean consume(UUID id,Instant now){return jdbc.update("UPDATE password_reset_tokens SET consumed_at=? WHERE id=? AND consumed_at IS NULL AND expires_at>?",java.sql.Timestamp.from(now),id,java.sql.Timestamp.from(now))==1;}
}
