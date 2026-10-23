package com.bridgeops.audit.application;

import java.time.Instant;
import java.util.UUID;
import com.bridgeops.shared.domain.ActorReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class ExecutionAudit {
    public enum Action { EXECUTION_STARTED, EXECUTION_CANCELLED, EXECUTION_RETRIED, EXECUTION_SUCCEEDED, EXECUTION_FAILED }
    private final JdbcTemplate jdbc;
    public ExecutionAudit(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Transactional(propagation=Propagation.MANDATORY)
    public void write(UUID organization, UUID actor, Action action, UUID execution, long revision, UUID version, String hash, Instant now) {
        jdbc.update("INSERT INTO organization_audit_events(id,organization_id,actor_id,action,target_id,occurred_at,resource_type,revision,version_id,definition_hash,correlation_id) VALUES(?,?,?,?,?,?,'EXECUTION',?,?,?,?)",
            UUID.randomUUID(),organization,actor,action.name(),execution,java.sql.Timestamp.from(now),revision,version,hash,UUID.randomUUID().toString());
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void write(UUID organization,ActorReference actor,Action action,UUID execution,long revision,UUID version,String hash,Instant now){
        if(actor.type()==ActorReference.Type.USER){write(organization,actor.id(),action,execution,revision,version,hash,now);return;}
        jdbc.update("INSERT INTO organization_audit_events(id,organization_id,actor_id,actor_type,automation_actor_id,action,target_id,occurred_at,resource_type,revision,version_id,definition_hash,correlation_id) VALUES(?,?,NULL,'AUTOMATION',?,?,?,?,'EXECUTION',?,?,?,?)",UUID.randomUUID(),organization,actor.id(),action.name(),execution,java.sql.Timestamp.from(now),revision,version,hash,UUID.randomUUID().toString());
    }
}
