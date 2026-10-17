package com.bridgeops.executions.application;

import com.bridgeops.audit.application.ExecutionAudit;
import com.bridgeops.executions.domain.*;
import com.bridgeops.executions.persistence.ExecutionStore;
import com.bridgeops.executions.persistence.ExecutionStore.*;
import com.bridgeops.organizations.application.TenantAuthorization;
import com.bridgeops.shared.web.DomainFailure;
import com.bridgeops.workflows.application.WorkflowRuntime;
import com.bridgeops.workflows.application.WorkflowDefinitions;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.bridgeops.executions.persistence.ExecutionStore.time;

@Service
public class ExecutionRuntime {
    public record Dispatch(Claim claim,ExecutionPlan.Node node,UUID actor,UUID operation) {}
    private final ExecutionStore store;private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    private final TenantAuthorization authority;private final WorkflowRuntime workflows;private final WorkflowDefinitions graphs;
    private final ExecutionService service;private final ExecutionAudit audit;private final Clock clock;private final RetryPolicy retries;
    public ExecutionRuntime(ExecutionStore store,PlatformTransactionManager manager,TenantAuthorization authority,WorkflowRuntime workflows,
        WorkflowDefinitions graphs,ExecutionService service,ExecutionAudit audit,Clock clock,ExecutionRandom randomness){
        this.store=store;this.jdbc=store.jdbc();this.tx=new TransactionTemplate(manager);tx.setTimeout(10);
        this.authority=authority;this.workflows=workflows;this.graphs=graphs;this.service=service;this.audit=audit;this.clock=clock;this.retries=new RetryPolicy(randomness::sample);
    }
    public Claim claim(UUID owner,int globalLimit,int tenantLimit,int leaseSeconds){
        if(globalLimit<1||globalLimit>16||tenantLimit<1||tenantLimit>globalLimit||leaseSeconds<5||leaseSeconds>120)throw new IllegalArgumentException("Invalid worker bounds.");
        return tx.execute(status->{
            Instant now=clock.instant();
            // Global claim admission is serialized; no waiting transaction is held across execution.
            if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(834721903)",Boolean.class)))return null;
            if(jdbc.queryForObject("SELECT count(*) FROM execution_work_items WHERE state='CLAIMED' AND lease_expires_at>?",Long.class,time(now))>=globalLimit)return null;
            var orgs=jdbc.queryForList("SELECT o.id FROM organizations o WHERE EXISTS(SELECT 1 FROM execution_work_items w JOIN workflow_executions e ON e.organization_id=w.organization_id AND e.id=w.execution_id WHERE w.organization_id=o.id AND w.state='READY' AND w.due_at<=? AND e.completed_at IS NULL) AND (SELECT count(*) FROM execution_work_items a WHERE a.organization_id=o.id AND a.state='CLAIMED' AND a.lease_expires_at>?)<? ORDER BY (SELECT min(due_at) FROM execution_work_items z WHERE z.organization_id=o.id AND z.state='READY') FOR UPDATE OF o SKIP LOCKED LIMIT 1",UUID.class,time(now),time(now),tenantLimit);
            if(orgs.isEmpty())return null;UUID org=orgs.getFirst();
            var ids=jdbc.queryForList("SELECT e.id FROM workflow_executions e JOIN execution_work_items w ON w.organization_id=e.organization_id AND w.execution_id=e.id WHERE e.organization_id=? AND e.completed_at IS NULL AND w.state='READY' AND w.due_at<=? ORDER BY w.due_at,e.id FOR UPDATE OF e,w SKIP LOCKED LIMIT 1",UUID.class,org,time(now));
            if(ids.isEmpty())return null;UUID id=ids.getFirst();
            jdbc.update("UPDATE execution_work_items SET state='CLAIMED',lease_owner=?,lease_expires_at=?,fencing_token=fencing_token+1 WHERE organization_id=? AND execution_id=?",owner,time(now.plusSeconds(leaseSeconds)),org,id);
            long token=jdbc.queryForObject("SELECT fencing_token FROM execution_work_items WHERE organization_id=? AND execution_id=?",Long.class,org,id);
            return new Claim(org,id,owner,token);
        });
    }
    private Execution owned(Claim c){
        var e=store.get(c.organization(),c.execution(),true);
        return !e.state().terminal()&&store.guard(c,clock.instant())?e:null;
    }
    public Dispatch prepare(Claim c){return tx.execute(status->{
        var preview=store.get(c.organization(),c.execution(),false);
        var snapshot=workflows.snapshot(preview.actor(),c.organization(),preview.workflow(),preview.version());
        var e=owned(c);if(e==null)return null;Instant now=clock.instant();
        if(!e.deadline().isAfter(now)){fail(e,"DEADLINE_EXCEEDED",now);return null;}
        if(!snapshot.hash().equals(e.hash())){fail(e,"RECONCILIATION_REQUIRED",now);return null;}
        if(e.state()==ExecutionState.WAITING_FOR_DELAY||e.state()==ExecutionState.WAITING_FOR_RETRY){store.state(e,ExecutionState.QUEUED,null,now);e=store.get(e.organization(),e.id(),false);}
        if(e.state()==ExecutionState.QUEUED){store.state(e,ExecutionState.RUNNING,null,now);e=store.get(e.organization(),e.id(),false);store.event(e,"EXECUTION_RUNNING",null,now);}
        if(e.notBefore().isAfter(now)){
            store.state(e,ExecutionState.WAITING_FOR_DELAY,null,now);store.event(e,"DELAY_SCHEDULED",null,now);store.release(c,e.notBefore(),false);return null;
        }
        var plan=ExecutionPlan.from(snapshot.graph(),graphs);var steps=store.steps(e.organization(),e.id());
        if(e.position()>=plan.size()){succeed(e,now);return null;}
        var node=plan.get(e.position());var step=steps.get(e.position());
        if(!node.id().equals(step.node())||!node.argumentHash().equals(step.argumentHash())){fail(e,"RECONCILIATION_REQUIRED",now);return null;}
        if(step.state().equals("SUCCEEDED")){advance(e);return null;}
        if(step.attempts()>=3){fail(e,"TRANSIENT_EXHAUSTED",now);return null;}
        jdbc.update("UPDATE execution_steps SET status='RUNNING',attempt_count=attempt_count+1,attempt_started_at=?,error_code=NULL WHERE organization_id=? AND execution_id=? AND node_id=?",time(now),e.organization(),e.id(),node.id());
        store.event(e,"STEP_STARTED",node.id(),now);
        return new Dispatch(c,node,e.actor(),step.operation());
    });}
    /** Effect, receipt, successful attempt and step result share one fenced database transaction. */
    public boolean complete(Dispatch dispatch){return Boolean.TRUE.equals(tx.execute(status->{
        var c=dispatch.claim();authority.worker(dispatch.actor(),c.organization());var e=owned(c);if(e==null)return false;Instant now=clock.instant();
        if(!e.deadline().isAfter(now)){fail(e,"DEADLINE_EXCEEDED",now);return false;}
        var step=store.steps(e.organization(),e.id()).get(e.position());
        if(!step.node().equals(dispatch.node().id())||!step.state().equals("RUNNING"))return false;
        String output="{}";
        if(step.type().equals("syntheticAction")){
            var receipts=jdbc.queryForList("SELECT argument_hash,result_json FROM execution_operation_receipts WHERE organization_id=? AND capability='synthetic.record' AND logical_operation_id=? AND item_id=?",e.organization(),step.operation(),step.node());
            if(!receipts.isEmpty()){
                if(!step.argumentHash().equals(receipts.getFirst().get("argument_hash"))){fail(e,"RECONCILIATION_REQUIRED",now);return false;}
                output=(String)receipts.getFirst().get("result_json");store.event(e,"RECEIPT_REUSED",step.node(),now);
            }else{
                output="{\"recorded\":true}";
                jdbc.update("INSERT INTO synthetic_records(organization_id,logical_operation_id,message,recorded_at) VALUES(?,?,?,?)",e.organization(),step.operation(),dispatch.node().message(),time(now));
                jdbc.update("INSERT INTO execution_operation_receipts(organization_id,logical_operation_id,capability,item_id,argument_hash,result_json,committed_at) VALUES(?,?,'synthetic.record',?,?,?,?)",e.organization(),step.operation(),step.node(),step.argumentHash(),output,time(now));
                store.event(e,"RECORD_COMMITTED",step.node(),now);
            }
        }
        attempt(e,step,"SUCCEEDED",null,now);
        jdbc.update("UPDATE execution_steps SET status='SUCCEEDED',completed_at=?,output_json=?,error_code=NULL WHERE organization_id=? AND execution_id=? AND node_id=?",time(now),output,e.organization(),e.id(),step.node());
        store.event(e,"STEP_SUCCEEDED",step.node(),now);advance(e);
        return true;
    }));}
    private void advance(Execution e){jdbc.update("UPDATE workflow_executions SET current_position=current_position+1,revision=revision+1 WHERE organization_id=? AND id=? AND revision=?",e.organization(),e.id(),e.revision());}
    public void acknowledge(Claim c){tx.executeWithoutResult(status->{
        lockOrganization(c.organization());var e=owned(c);if(e==null)return;
        if(!e.deadline().isAfter(clock.instant())){fail(e,"DEADLINE_EXCEEDED",clock.instant());return;}
        if(e.position()==store.steps(e.organization(),e.id()).size())succeed(e,clock.instant());else store.release(c,clock.instant(),false);
    });}
    public void failure(Dispatch dispatch,SyntheticCapability.FailureKind kind){tx.executeWithoutResult(status->{
        var c=dispatch.claim();lockOrganization(c.organization());var e=owned(c);if(e==null)return;Instant now=clock.instant();
        if(!e.deadline().isAfter(now)){fail(e,"DEADLINE_EXCEEDED",now);return;}
        var step=store.steps(e.organization(),e.id()).get(e.position());
        if(!step.node().equals(dispatch.node().id())||!step.state().equals("RUNNING"))return;
        String code=switch(kind){case TRANSIENT->"TRANSIENT_FAILURE";case PERMANENT->"PERMANENT_FAILURE";case AMBIGUOUS->"RECONCILIATION_REQUIRED";};
        attempt(e,step,code,code,now);
        jdbc.update("UPDATE execution_steps SET status=?,error_code=?,completed_at=? WHERE organization_id=? AND execution_id=? AND node_id=?",kind==SyntheticCapability.FailureKind.TRANSIENT&&step.attempts()<3?"WAITING_FOR_RETRY":"FAILED",code,kind==SyntheticCapability.FailureKind.TRANSIENT&&step.attempts()<3?null:time(now),e.organization(),e.id(),step.node());
        if(kind==SyntheticCapability.FailureKind.TRANSIENT&&step.attempts()<3){
            Instant due=now.plus(retries.delay(step.attempts()));store.state(e,ExecutionState.WAITING_FOR_RETRY,code,now);store.event(e,"RETRY_SCHEDULED",step.node(),now);store.release(c,due,false);
        }else fail(e,kind==SyntheticCapability.FailureKind.TRANSIENT?"TRANSIENT_EXHAUSTED":code,now);
    });}
    public void permissionDenied(Claim c){tx.executeWithoutResult(status->{lockOrganization(c.organization());var e=owned(c);if(e!=null)fail(e,"PERMISSION_REVOKED",clock.instant());});}
    private void fail(Execution e,String code,Instant now){
        service.terminateSteps(e,code.equals("DEADLINE_EXCEEDED")?code:"RECONCILIATION_REQUIRED",now);
        store.state(e,ExecutionState.FAILED,code,now);store.done(e);store.event(e,"EXECUTION_FAILED",null,now);
        audit.write(e.organization(),e.actor(),ExecutionAudit.Action.EXECUTION_FAILED,e.id(),e.revision()+1,e.version(),e.hash(),now);
    }
    private void succeed(Execution e,Instant now){
        store.state(e,ExecutionState.SUCCEEDED,null,now);store.done(e);store.event(e,"EXECUTION_SUCCEEDED",null,now);
        audit.write(e.organization(),e.actor(),ExecutionAudit.Action.EXECUTION_SUCCEEDED,e.id(),e.revision()+1,e.version(),e.hash(),now);
    }
    private void attempt(Execution e,Step step,String outcome,String error,Instant now){
        jdbc.update("INSERT INTO execution_attempts(id,organization_id,execution_id,node_id,attempt_number,started_at,completed_at,outcome,error_code) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",UUID.randomUUID(),e.organization(),e.id(),step.node(),step.attempts(),time(step.started()),time(now),outcome,error);
    }
    private void lockOrganization(UUID organization){jdbc.queryForList("SELECT id FROM organizations WHERE id=? FOR UPDATE",organization);}
    /** Bounded reconciliation executes one execution per transaction, preserving lock order. */
    public int reconcile(){
        Instant now=clock.instant();var candidates=jdbc.queryForList("SELECT e.organization_id,e.id FROM workflow_executions e JOIN execution_work_items w ON w.organization_id=e.organization_id AND w.execution_id=e.id WHERE e.completed_at IS NULL AND (e.deadline_at<=? OR (w.state='CLAIMED' AND w.lease_expires_at<=?)) ORDER BY e.deadline_at LIMIT 100",time(now),time(now));
        int count=0;
        for(var candidate:candidates){UUID org=(UUID)candidate.get("organization_id"),id=(UUID)candidate.get("id");
            Boolean changed=tx.execute(status->{
                lockOrganization(org);var e=store.get(org,id,true);if(e.state().terminal())return false;
                var work=jdbc.queryForList("SELECT * FROM execution_work_items WHERE organization_id=? AND execution_id=? FOR UPDATE",org,id).getFirst();Instant at=clock.instant();
                if(!e.deadline().isAfter(at)){fail(e,"DEADLINE_EXCEEDED",at);return true;}
                var lease=(java.sql.Timestamp)work.get("lease_expires_at");
                if(!"CLAIMED".equals(work.get("state"))||lease==null||lease.toInstant().isAfter(at))return false;
                for(var step:store.steps(org,id))if(step.state().equals("RUNNING")){
                    attempt(e,step,"INTERRUPTED","WORKER_INTERRUPTED",at);
                    if(step.attempts()>=3){fail(e,"TRANSIENT_EXHAUSTED",at);return true;}
                    jdbc.update("UPDATE execution_steps SET status='WAITING_FOR_RETRY',error_code='WORKER_INTERRUPTED' WHERE organization_id=? AND execution_id=? AND node_id=?",org,id,step.node());
                }
                if(e.state()==ExecutionState.RUNNING){store.state(e,ExecutionState.WAITING_FOR_RETRY,"WORKER_INTERRUPTED",at);}
                jdbc.update("UPDATE execution_work_items SET state='READY',lease_owner=NULL,lease_expires_at=NULL,due_at=?,fencing_token=fencing_token+1 WHERE organization_id=? AND execution_id=?",time(at),org,id);
                store.event(e,"LEASE_RECOVERED",null,at);return true;
            });if(Boolean.TRUE.equals(changed))count++;
        }
        return count;
    }
    public boolean renew(Claim c,int leaseSeconds){return Boolean.TRUE.equals(tx.execute(status->{
        lockOrganization(c.organization());var e=owned(c);if(e==null)return false;
        if(!e.deadline().isAfter(clock.instant())){fail(e,"DEADLINE_EXCEEDED",clock.instant());return false;}
        return jdbc.update("UPDATE execution_work_items SET lease_expires_at=? WHERE organization_id=? AND execution_id=? AND lease_owner=? AND fencing_token=? AND state='CLAIMED'",time(clock.instant().plusSeconds(leaseSeconds)),c.organization(),c.execution(),c.owner(),c.token())==1;
    }));}
}
