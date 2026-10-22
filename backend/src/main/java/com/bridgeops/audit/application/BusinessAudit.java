package com.bridgeops.audit.application;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Business metadata only: no ticket titles, note/message bodies, credentials or provider diagnostics. */
@Service
public class BusinessAudit {
    public enum Action{TICKET_ASSIGNED,TICKET_NOTE_ADDED,INTERNAL_NOTIFICATION_RECORDED}
    private final JdbcTemplate jdbc;
    public BusinessAudit(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Transactional(propagation=Propagation.MANDATORY)
    public void write(UUID org,UUID actor,UUID execution,UUID version,String hash,Action action,UUID target,long revision,Instant now){
        String resource=action==Action.INTERNAL_NOTIFICATION_RECORDED?"NOTIFICATION":"SUPPORT_TICKET";
        jdbc.update("INSERT INTO organization_audit_events(id,organization_id,actor_id,action,target_id,occurred_at,resource_type,revision,version_id,definition_hash,correlation_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID(),org,actor,action.name(),target,java.sql.Timestamp.from(now),resource,revision,version,hash,execution.toString());
    }
}
