package com.bridgeops.audit.application;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Typed metadata only; never tickets, credentials, destinations or provider exception text. */
@Service
public class IntegrationAudit {
    public enum Action { DEMO_DATA_INSTALLED,DEMO_WORKFLOWS_INSTALLED,INTEGRATION_CREATED,INTEGRATION_CHANGED,INTEGRATION_CHECKED,SECRET_REBOUND,TICKETS_ASSIGNED,TICKET_NOTE_ADDED,NOTIFICATION_RECORDED,AUTOMATION_CREATED,AUTOMATION_REVOKED,WEBHOOK_CREATED,WEBHOOK_ROTATED,WEBHOOK_REVOKED,WEBHOOK_ACCEPTED }
    private final JdbcTemplate jdbc;
    public IntegrationAudit(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Transactional(propagation=Propagation.MANDATORY)
    public void write(UUID org,UUID actor,Action action,UUID target,long revision,Instant now){
        jdbc.update("INSERT INTO organization_audit_events(id,organization_id,actor_id,action,target_id,occurred_at,resource_type,revision,correlation_id) VALUES(?,?,?,?,?,?,'INTEGRATION',?,?)",UUID.randomUUID(),org,actor,action.name(),target,java.sql.Timestamp.from(now),revision,UUID.randomUUID().toString());
    }
}
