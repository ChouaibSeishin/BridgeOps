package com.bridgeops.approvals.persistence;

import com.bridgeops.approvals.application.ApprovalIntent;
import com.bridgeops.approvals.domain.ApprovalState;
import com.bridgeops.shared.web.DomainFailure;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ApprovalStore {
    public record Policy(long version,boolean required,int lifetime) {
        public String snapshot(){return "{\"lifetimeSeconds\":"+lifetime+",\"required\":"+required+"}";}
    }
    public record Request(UUID id,UUID organization,UUID execution,String gate,String action,UUID version,String definitionHash,
        UUID requester,UUID operation,String operationHash,String arguments,String argumentHash,long policyVersion,String policySnapshot,
        String prompt,String canonical,String intentHash,Instant created,Instant expires,Instant deadline,ApprovalState state,long revision,Instant finalized,String error) {}
    public record Decision(UUID id,UUID actor,String decision,Instant at,long expectedRevision,String reason) {}
    public record Signal(UUID id,UUID request,UUID execution,String outcome) {}
    public record Candidate(UUID organization,UUID execution,UUID requester) {}
    private final JdbcTemplate jdbc;
    public ApprovalStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Policy policy(UUID org){return jdbc.query("SELECT v.* FROM organization_approval_policies p JOIN approval_policy_versions v ON v.organization_id=p.organization_id AND v.version=p.version WHERE p.organization_id=?",(r,i)->new Policy(r.getLong("version"),r.getBoolean("require_record_approval"),r.getInt("lifetime_seconds")),org).stream().findFirst().orElse(new Policy(0,false,3600));}
    public void ensurePolicy(UUID org,Instant now){
        jdbc.update("INSERT INTO approval_policy_versions VALUES(?,0,false,3600,NULL,?) ON CONFLICT DO NOTHING",org,time(now));
        jdbc.update("INSERT INTO organization_approval_policies VALUES(?,0) ON CONFLICT DO NOTHING",org);
    }
    public void changePolicy(UUID org,UUID actor,Policy policy,Instant now){
        jdbc.update("INSERT INTO approval_policy_versions VALUES(?,?,?,?,?,?)",org,policy.version(),policy.required(),policy.lifetime(),actor,time(now));
        if(jdbc.update("UPDATE organization_approval_policies SET version=? WHERE organization_id=? AND version=?",policy.version(),org,policy.version()-1)!=1)throw new DomainFailure(409,"STALE_REVISION");
    }
    public Request get(UUID org,UUID id,boolean lock){return jdbc.query("SELECT * FROM approval_requests WHERE organization_id=? AND id=?"+(lock?" FOR UPDATE":""),this::request,org,id).stream().findFirst().orElseThrow(()->new DomainFailure(404,"NOT_FOUND"));}
    public Optional<Request> action(UUID org,UUID execution,String action){return jdbc.query("SELECT * FROM approval_requests WHERE organization_id=? AND execution_id=? AND action_node_id=?",this::request,org,execution,action).stream().findFirst();}
    public Optional<Request> outstanding(UUID org,UUID execution){return jdbc.query("SELECT r.* FROM approval_requests r WHERE r.organization_id=? AND r.execution_id=? AND (r.status='PENDING' OR EXISTS(SELECT 1 FROM approval_signals s WHERE s.organization_id=r.organization_id AND s.request_id=r.id AND NOT EXISTS(SELECT 1 FROM approval_signal_receipts c WHERE c.organization_id=s.organization_id AND c.signal_id=s.id))) ORDER BY CASE WHEN r.status='PENDING' THEN 0 ELSE 1 END,r.created_at,r.id LIMIT 1",this::request,org,execution).stream().findFirst();}
    public List<Request> list(UUID org,int page,int size,String state,UUID execution){return jdbc.query("SELECT * FROM approval_requests WHERE organization_id=? AND (?::varchar IS NULL OR status=?) AND (?::uuid IS NULL OR execution_id=?) ORDER BY created_at DESC,id LIMIT ? OFFSET ?",this::request,org,state,state,execution,execution,size,(long)page*size);}
    public long count(UUID org,String state,UUID execution){return jdbc.queryForObject("SELECT count(*) FROM approval_requests WHERE organization_id=? AND (?::varchar IS NULL OR status=?) AND (?::uuid IS NULL OR execution_id=?)",Long.class,org,state,state,execution,execution);}
    public List<Request> pending(UUID org,UUID execution){return jdbc.query("SELECT * FROM approval_requests WHERE organization_id=? AND status='PENDING' AND (?::uuid IS NULL OR execution_id=?) ORDER BY created_at,id FOR UPDATE",this::request,org,execution,execution);}
    public Request insert(ApprovalIntent intent,String operationHash,Instant created){
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO approval_requests(id,organization_id,execution_id,gate_node_id,action_node_id,workflow_version_id,definition_hash,requested_by,logical_operation_id,operation_argument_hash,capability,operation,arguments_json,argument_hash,policy_version,policy_snapshot,prompt,canonical_intent,intent_hash,created_at,expires_at,execution_deadline_at) VALUES(?,?,?,?,?,?,?,?,?,?,'synthetic','record',?,?,?,?,?,?,?,?,?,?)",
            id,intent.organization(),intent.execution(),intent.gateNode(),intent.actionNode(),intent.version(),intent.definitionHash(),intent.requester(),intent.logicalOperation(),operationHash,intent.arguments(),intent.argumentHash(),intent.policyVersion(),intent.policySnapshot(),intent.prompt(),intent.canonical(),intent.hash(),time(created),time(intent.expiresAt()),time(intent.deadlineAt()));
        return get(intent.organization(),id,false);
    }
    public void finalize(Request request,ApprovalState state,String error,Instant now){
        request.state().requireTransition(state);
        if(jdbc.update("UPDATE approval_requests SET status=?,revision=revision+1,finalized_at=?,error_code=? WHERE organization_id=? AND id=? AND revision=? AND status='PENDING'",state.name(),time(now),error,request.organization(),request.id(),request.revision())!=1)throw new DomainFailure(409,"APPROVAL_CONFLICT");
        jdbc.update("INSERT INTO approval_signals(id,organization_id,request_id,execution_id,outcome,created_at) VALUES(?,?,?,?,?,?)",UUID.randomUUID(),request.organization(),request.id(),request.execution(),state.name(),time(now));
    }
    public void decide(Request request,UUID actor,String outcome,String reason,Instant now){jdbc.update("INSERT INTO approval_decisions(id,organization_id,request_id,decided_by,decision,decided_at,expected_revision,reason) VALUES(?,?,?,?,?,?,?,?)",UUID.randomUUID(),request.organization(),request.id(),actor,outcome,time(now),request.revision(),reason);}
    public Optional<Decision> decision(UUID org,UUID request){return jdbc.query("SELECT * FROM approval_decisions WHERE organization_id=? AND request_id=?",(r,i)->new Decision(r.getObject("id",UUID.class),r.getObject("decided_by",UUID.class),r.getString("decision"),instant(r,"decided_at"),r.getLong("expected_revision"),r.getString("reason")),org,request).stream().findFirst();}
    public Set<UUID> decisionActors(UUID org,UUID execution){return Set.copyOf(jdbc.queryForList("SELECT DISTINCT d.decided_by FROM approval_decisions d JOIN approval_requests r ON r.organization_id=d.organization_id AND r.id=d.request_id WHERE r.organization_id=? AND r.execution_id=?",UUID.class,org,execution));}
    public List<Signal> signals(UUID org,UUID execution){return jdbc.query("SELECT s.* FROM approval_signals s WHERE s.organization_id=? AND s.execution_id=? AND NOT EXISTS(SELECT 1 FROM approval_signal_receipts r WHERE r.organization_id=s.organization_id AND r.signal_id=s.id) ORDER BY s.created_at,s.id",(r,i)->new Signal(r.getObject("id",UUID.class),r.getObject("request_id",UUID.class),r.getObject("execution_id",UUID.class),r.getString("outcome")),org,execution);}
    public boolean consumed(UUID org,UUID request){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM approval_signal_receipts WHERE organization_id=? AND request_id=?)",Boolean.class,org,request));}
    public boolean consume(UUID org,Signal signal,Instant now){return jdbc.update("INSERT INTO approval_signal_receipts VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING",org,signal.id(),signal.request(),signal.execution(),time(now))==1;}
    public List<Candidate> candidates(Instant now){return jdbc.query("SELECT DISTINCT r.organization_id,r.execution_id,r.requested_by FROM approval_requests r WHERE (r.status='PENDING' AND r.expires_at<=?) OR EXISTS(SELECT 1 FROM approval_signals s WHERE s.organization_id=r.organization_id AND s.request_id=r.id AND NOT EXISTS(SELECT 1 FROM approval_signal_receipts c WHERE c.organization_id=s.organization_id AND c.signal_id=s.id)) ORDER BY r.organization_id,r.execution_id LIMIT 100",(r,i)->new Candidate(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,UUID.class)),time(now));}
    public boolean invalidate(Request r,String code,Instant now){return jdbc.update("INSERT INTO approval_invalidations VALUES(?,?,?,?) ON CONFLICT DO NOTHING",r.organization(),r.id(),time(now),code)==1;}
    public boolean invalidated(UUID org,UUID request){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM approval_invalidations WHERE organization_id=? AND request_id=?)",Boolean.class,org,request));}
    public void event(Request r,UUID actor,String code,Instant now){jdbc.update("INSERT INTO approval_events(id,organization_id,request_id,event_sequence,occurred_at,actor_id,code) SELECT ?,?,?,coalesce(max(event_sequence),0)+1,?,?,? FROM approval_events WHERE organization_id=? AND request_id=?",UUID.randomUUID(),r.organization(),r.id(),time(now),actor,code,r.organization(),r.id());}
    public record Event(long sequence,Instant occurredAt,UUID actor,String code) {}
    public List<Event> events(UUID org,UUID request){return jdbc.query("SELECT * FROM approval_events WHERE organization_id=? AND request_id=? ORDER BY event_sequence LIMIT 100",(r,i)->new Event(r.getLong("event_sequence"),instant(r,"occurred_at"),r.getObject("actor_id",UUID.class),r.getString("code")),org,request);}
    private Request request(ResultSet r,int index)throws SQLException{return new Request(r.getObject("id",UUID.class),r.getObject("organization_id",UUID.class),r.getObject("execution_id",UUID.class),r.getString("gate_node_id"),r.getString("action_node_id"),r.getObject("workflow_version_id",UUID.class),r.getString("definition_hash"),r.getObject("requested_by",UUID.class),r.getObject("logical_operation_id",UUID.class),r.getString("operation_argument_hash"),r.getString("arguments_json"),r.getString("argument_hash"),r.getLong("policy_version"),r.getString("policy_snapshot"),r.getString("prompt"),r.getString("canonical_intent"),r.getString("intent_hash"),instant(r,"created_at"),instant(r,"expires_at"),instant(r,"execution_deadline_at"),ApprovalState.valueOf(r.getString("status")),r.getLong("revision"),instant(r,"finalized_at"),r.getString("error_code"));}
    public static Timestamp time(Instant value){return value==null?null:Timestamp.from(value);}
    private static Instant instant(ResultSet r,String name)throws SQLException{var value=r.getTimestamp(name);return value==null?null:value.toInstant();}
}
