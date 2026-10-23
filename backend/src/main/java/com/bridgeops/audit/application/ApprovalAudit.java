package com.bridgeops.audit.application;

import java.time.Instant;
import java.util.UUID;
import com.bridgeops.shared.domain.ActorReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class ApprovalAudit {
    public enum Action { APPROVAL_REQUESTED,APPROVAL_APPROVED,APPROVAL_REJECTED,APPROVAL_EXPIRED,APPROVAL_INVALIDATED,APPROVAL_CANCELLED,APPROVAL_SIGNAL_CONSUMED,APPROVAL_POLICY_CHANGED }
    private final JdbcTemplate jdbc;
    public ApprovalAudit(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Transactional(propagation=Propagation.MANDATORY)
    public void write(UUID org,UUID actor,Action action,UUID target,long revision,Instant now){jdbc.update("INSERT INTO organization_audit_events(id,organization_id,actor_id,action,target_id,occurred_at,resource_type,revision,correlation_id) VALUES(?,?,?,?,?,?,'APPROVAL',?,?)",UUID.randomUUID(),org,actor,action.name(),target,java.sql.Timestamp.from(now),revision,UUID.randomUUID().toString());}
    @Transactional(propagation=Propagation.MANDATORY)
    public void write(UUID org,ActorReference actor,Action action,UUID target,long revision,Instant now){if(actor.type()==ActorReference.Type.USER){write(org,actor.id(),action,target,revision,now);return;}jdbc.update("INSERT INTO organization_audit_events(id,organization_id,actor_id,actor_type,automation_actor_id,action,target_id,occurred_at,resource_type,revision,correlation_id) VALUES(?,?,NULL,'AUTOMATION',?,?,?,?,'APPROVAL',?,?)",UUID.randomUUID(),org,actor.id(),action.name(),target,java.sql.Timestamp.from(now),revision,UUID.randomUUID().toString());}
}
