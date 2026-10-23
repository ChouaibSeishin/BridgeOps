package com.bridgeops.executions.persistence;

import com.bridgeops.executions.domain.ExecutionState;
import com.bridgeops.shared.web.DomainFailure;
import com.bridgeops.shared.domain.ActorReference;
import com.bridgeops.shared.domain.AutomationOrigin;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
public class ExecutionStore {
    public record Execution(UUID id, UUID organization, UUID workflow, UUID version, String hash, UUID actor,
        String source, ExecutionState state, long revision, int position, Instant created, Instant started,
        Instant completed, Instant deadline, Instant notBefore, String error, UUID retryOf, String fingerprint, String input,AutomationOrigin automation) {
        public ActorReference requester(){return automation==null?ActorReference.user(actor):automation.actor();}
        @Override public String toString(){return "Execution[REDACTED]";}
    }
    public record Step(String node, int position, String type, UUID operation, String argumentHash, String state,
        int attempts, Instant started, Instant completed, String output, String error) {}
    public record Claim(UUID organization, UUID execution, UUID owner, long token) {}
    private final JdbcTemplate jdbc;
    public ExecutionStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public JdbcTemplate jdbc(){return jdbc;}
    public static Timestamp time(Instant value){return value==null?null:Timestamp.from(value);}
    private static Instant instant(ResultSet rs,String column)throws SQLException {var t=rs.getTimestamp(column);return t==null?null:t.toInstant();}
    public Execution get(UUID org,UUID id,boolean lock){
        var rows=jdbc.query("SELECT * FROM workflow_executions WHERE organization_id=? AND id=?"+(lock?" FOR UPDATE":""),this::execution,org,id);
        if(rows.isEmpty())throw new DomainFailure(404,"NOT_FOUND");return rows.getFirst();
    }
    public List<Execution> list(UUID org,int page,int size,UUID workflow,ExecutionState state){
        return jdbc.query("SELECT * FROM workflow_executions WHERE organization_id=? AND (?::uuid IS NULL OR workflow_id=?) AND (?::varchar IS NULL OR status=?) ORDER BY created_at DESC,id LIMIT ? OFFSET ?",this::execution,org,workflow,workflow,state==null?null:state.name(),state==null?null:state.name(),size,(long)page*size);
    }
    public Optional<Execution> replay(UUID org,UUID workflow,String source,String key){
        return jdbc.query("SELECT * FROM workflow_executions WHERE organization_id=? AND workflow_id=? AND trigger_source=? AND idempotency_key=?",this::execution,org,workflow,source,key).stream().findFirst();
    }
    public void insert(UUID id,UUID org,UUID workflow,UUID version,String hash,UUID actor,String source,String key,String fingerprint,UUID retry,Instant now,Instant notBefore){
        jdbc.update("INSERT INTO workflow_executions(id,organization_id,workflow_id,workflow_version_id,definition_hash,requested_by,trigger_source,input_json,idempotency_key,request_fingerprint,retry_of_execution_id,status,created_at,deadline_at,not_before) VALUES(?,?,?,?,?,?,?,'{}',?,?,?,'QUEUED',?,?,?)",
            id,org,workflow,version,hash,actor,source,key,fingerprint,retry,time(now),time(now.plusSeconds(86400)),time(notBefore));
        jdbc.update("INSERT INTO execution_work_items(organization_id,execution_id,due_at,state) VALUES(?,?,?,'READY')",org,id,time(now));
    }
    public void insertStep(UUID org,UUID execution,String node,int position,String type,UUID operation,String hash){
        jdbc.update("INSERT INTO execution_steps(organization_id,execution_id,node_id,position,node_type,logical_operation_id,argument_hash) VALUES(?,?,?,?,?,?,?)",org,execution,node,position,type,operation,hash);
    }
    public void insertV2(UUID id,UUID org,UUID workflow,UUID version,String hash,UUID actor,String source,String input,String key,String fingerprint,UUID retry,Instant now,Instant notBefore){
        jdbc.update("INSERT INTO workflow_executions(id,organization_id,workflow_id,workflow_version_id,definition_hash,requested_by,trigger_source,graph_schema_version,input_json,idempotency_key,request_fingerprint,retry_of_execution_id,status,created_at,deadline_at,not_before) VALUES(?,?,?,?,?,?,?,2,?,?,?,?,'QUEUED',?,?,?)",id,org,workflow,version,hash,actor,source,input,key,fingerprint,retry,time(now),time(now.plusSeconds(86400)),time(notBefore));
        jdbc.update("INSERT INTO execution_work_items(organization_id,execution_id,due_at,state) VALUES(?,?,?,'READY')",org,id,time(now));
    }
    public void insertStepV2(UUID org,UUID execution,String node,int position,String type,UUID operation,String hash){jdbc.update("INSERT INTO execution_steps(organization_id,execution_id,node_id,position,node_type,logical_operation_id,argument_hash,graph_schema_version) VALUES(?,?,?,?,?,?,?,2)",org,execution,node,position,type,operation,hash);}
    /** Persistence only. A delivery service must authorize and atomically insert its receipt/audit with this queue record. */
    public void insertAutomation(UUID id,UUID org,UUID workflow,UUID version,String hash,AutomationOrigin actor,String input,String key,String fingerprint,Instant now){
        if(!TransactionSynchronizationManager.isActualTransactionActive()||jdbc.getDataSource()==null||!TransactionSynchronizationManager.hasResource(jdbc.getDataSource()))throw new IllegalStateException("Automation submission requires its database transaction.");
        jdbc.update("INSERT INTO workflow_executions(id,organization_id,workflow_id,workflow_version_id,definition_hash,requested_by,requester_type,automation_principal_id,principal_created_by,webhook_endpoint_id,webhook_endpoint_revision,endpoint_revision_author,trigger_source,graph_schema_version,input_json,idempotency_key,request_fingerprint,status,created_at,deadline_at,not_before) VALUES(?,?,?,?,?,NULL,'AUTOMATION',?,?,?,?,?,'WEBHOOK',2,?,?,?,'QUEUED',?,?,?)",id,org,workflow,version,hash,actor.principal(),actor.principalCreator(),actor.endpoint(),actor.endpointRevision(),actor.endpointAuthor(),input,key,fingerprint,time(now),time(now.plusSeconds(86400)),time(now));
        jdbc.update("INSERT INTO execution_work_items(organization_id,execution_id,due_at,state) VALUES(?,?,?,'READY')",org,id,time(now));
    }
    public List<Step> steps(UUID org,UUID execution){return jdbc.query("SELECT * FROM execution_steps WHERE organization_id=? AND execution_id=? ORDER BY position",this::step,org,execution);}
    public Instant due(UUID org,UUID execution){return jdbc.queryForObject("SELECT due_at FROM execution_work_items WHERE organization_id=? AND execution_id=?",Timestamp.class,org,execution).toInstant();}
    public void event(Execution e,String code,String node,Instant now){
        jdbc.update("INSERT INTO execution_events(id,organization_id,execution_id,event_sequence,occurred_at,code,node_id) SELECT ?,?,?,coalesce(max(event_sequence),0)+1,?,?,? FROM execution_events WHERE organization_id=? AND execution_id=?",
            UUID.randomUUID(),e.organization(),e.id(),time(now),code,node,e.organization(),e.id());
    }
    /** Execution lock must precede work lock. Held locks fence every write until commit. */
    public boolean guard(Claim claim,Instant now){
        return !jdbc.queryForList("SELECT execution_id FROM execution_work_items WHERE organization_id=? AND execution_id=? AND state='CLAIMED' AND lease_owner=? AND fencing_token=? AND lease_expires_at>? FOR UPDATE",
            claim.organization(),claim.execution(),claim.owner(),claim.token(),time(now)).isEmpty();
    }
    public void state(Execution e,ExecutionState next,String error,Instant now){
        e.state().requireTransition(next);
        if(jdbc.update("UPDATE workflow_executions SET status=?,revision=revision+1,error_code=?,started_at=CASE WHEN ?='RUNNING' THEN coalesce(started_at,?) ELSE started_at END,completed_at=? WHERE organization_id=? AND id=? AND revision=? AND status=?",
            next.name(),error,next.name(),time(now),next.terminal()?time(now):null,e.organization(),e.id(),e.revision(),e.state().name())!=1)throw new DomainFailure(409,"STALE_REVISION");
    }
    public void release(Claim claim,Instant due,boolean done){
        if(jdbc.update("UPDATE execution_work_items SET state=?,due_at=?,lease_owner=NULL,lease_expires_at=NULL WHERE organization_id=? AND execution_id=? AND lease_owner=? AND fencing_token=? AND state='CLAIMED'",
            done?"DONE":"READY",time(due),claim.organization(),claim.execution(),claim.owner(),claim.token())!=1)throw new IllegalStateException("Lost execution ownership.");
    }
    public void done(Execution e){jdbc.update("UPDATE execution_work_items SET state='DONE',lease_owner=NULL,lease_expires_at=NULL,fencing_token=fencing_token+1 WHERE organization_id=? AND execution_id=?",e.organization(),e.id());}
    public void park(Claim c,Instant due){if(jdbc.update("UPDATE execution_work_items SET state='WAITING',due_at=?,lease_owner=NULL,lease_expires_at=NULL,fencing_token=fencing_token+1 WHERE organization_id=? AND execution_id=? AND state='CLAIMED' AND lease_owner=? AND fencing_token=?",time(due),c.organization(),c.execution(),c.owner(),c.token())!=1)throw new IllegalStateException("Lost execution ownership.");}
    public void resume(Execution e,Instant due){if(jdbc.update("UPDATE execution_work_items SET state='READY',due_at=?,lease_owner=NULL,lease_expires_at=NULL,fencing_token=fencing_token+1 WHERE organization_id=? AND execution_id=? AND state='WAITING'",time(due),e.organization(),e.id())!=1)throw new IllegalStateException("Execution is not parked.");}
    private Execution execution(ResultSet r,int index)throws SQLException {
        UUID user=r.getObject("requested_by",UUID.class);
        // Historical V10/V11 fixtures have no automation columns, and always have a real requested_by.
        var automation=user==null?new AutomationOrigin(r.getObject("automation_principal_id",UUID.class),r.getObject("principal_created_by",UUID.class),r.getObject("webhook_endpoint_id",UUID.class),r.getLong("webhook_endpoint_revision"),r.getObject("endpoint_revision_author",UUID.class)):null;
        return new Execution(r.getObject("id",UUID.class),r.getObject("organization_id",UUID.class),r.getObject("workflow_id",UUID.class),r.getObject("workflow_version_id",UUID.class),r.getString("definition_hash"),user,r.getString("trigger_source"),ExecutionState.valueOf(r.getString("status")),r.getLong("revision"),r.getInt("current_position"),instant(r,"created_at"),instant(r,"started_at"),instant(r,"completed_at"),instant(r,"deadline_at"),instant(r,"not_before"),r.getString("error_code"),r.getObject("retry_of_execution_id",UUID.class),r.getString("request_fingerprint"),r.getString("input_json"),automation);
    }
    private Step step(ResultSet r,int index)throws SQLException {
        return new Step(r.getString("node_id"),r.getInt("position"),r.getString("node_type"),r.getObject("logical_operation_id",UUID.class),r.getString("argument_hash"),r.getString("status"),r.getInt("attempt_count"),instant(r,"attempt_started_at"),instant(r,"completed_at"),r.getString("output_json"),r.getString("error_code"));
    }
}
