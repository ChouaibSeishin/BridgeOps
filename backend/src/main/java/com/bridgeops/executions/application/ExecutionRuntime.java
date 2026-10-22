package com.bridgeops.executions.application;

import com.bridgeops.audit.application.ExecutionAudit;
import com.bridgeops.approvals.application.ApprovalRuntime;
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
    private final ApprovalRuntime approvals;
    private final ExecutionGraphData data;
    public ExecutionRuntime(ExecutionStore store,PlatformTransactionManager manager,TenantAuthorization authority,WorkflowRuntime workflows,
        WorkflowDefinitions graphs,ExecutionService service,ExecutionAudit audit,Clock clock,ExecutionRandom randomness,ApprovalRuntime approvals){
        this.store=store;this.jdbc=store.jdbc();this.tx=new TransactionTemplate(manager);tx.setTimeout(10);
        this.authority=authority;this.workflows=workflows;this.graphs=graphs;this.service=service;this.audit=audit;this.clock=clock;this.retries=new RetryPolicy(randomness::sample);this.approvals=approvals;
        this.data=new ExecutionGraphData(store,graphs,clock);
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
        var actors=workerActors(preview);
        var snapshot=workflows.snapshot(preview.actor(),c.organization(),preview.workflow(),preview.version());
        var e=owned(c);if(e==null)return null;Instant now=clock.instant();
        if(!e.deadline().isAfter(now)){fail(e,"DEADLINE_EXCEEDED",now);return null;}
        if(!snapshot.hash().equals(e.hash())){fail(e,"RECONCILIATION_REQUIRED",now);return null;}
        if(e.state()==ExecutionState.WAITING_FOR_DELAY||e.state()==ExecutionState.WAITING_FOR_RETRY){store.state(e,ExecutionState.QUEUED,null,now);e=store.get(e.organization(),e.id(),false);}
        if(e.state()==ExecutionState.QUEUED){store.state(e,ExecutionState.RUNNING,null,now);e=store.get(e.organization(),e.id(),false);store.event(e,"EXECUTION_RUNNING",null,now);}
        if(e.notBefore().isAfter(now)){
            store.state(e,ExecutionState.WAITING_FOR_DELAY,null,now);store.event(e,"DELAY_SCHEDULED",null,now);store.release(c,e.notBefore(),false);return null;
        }
        var plan=ExecutionPlan.runtime(snapshot.graph(),graphs);var steps=store.steps(e.organization(),e.id());
        if(e.position()>=plan.size()){succeed(e,now);return null;}
        var node=plan.get(e.position());var step=steps.get(e.position());
        if(!node.id().equals(step.node())||!node.argumentHash().equals(step.argumentHash())){fail(e,"RECONCILIATION_REQUIRED",now);return null;}
        if(step.state().equals("SUCCEEDED")){advance(e);return null;}
        if(data.v2(snapshot.graph())){
            try{
                data.inputs(e,snapshot.graph(),node.id());
                if(node.type().equals("approval")){var action=data.action(snapshot.graph(),plan,e.position());data.inputs(e,snapshot.graph(),action.id(),node.id());}
            }catch(DomainFailure invalid){jdbc.update("UPDATE execution_steps SET status='FAILED',completed_at=?,error_code=? WHERE organization_id=? AND execution_id=? AND node_id=?",time(now),invalid.code(),e.organization(),e.id(),node.id());fail(e,invalid.code(),now);return null;}
            if(node.type().equals("delay")){
                Instant due=data.delay(e,snapshot.graph(),node.id());
                if(due.isAfter(now)||step.attempts()==0){
                    if(step.attempts()==0)jdbc.update("UPDATE execution_steps SET status='WAITING_FOR_DELAY',attempt_count=1,attempt_started_at=? WHERE organization_id=? AND execution_id=? AND node_id=?",time(now),e.organization(),e.id(),node.id());
                    store.state(e,ExecutionState.WAITING_FOR_DELAY,null,now);store.event(e,"DELAY_SCHEDULED",node.id(),now);store.release(c,due,false);return null;
                }
            }
        }
        if(node.type().equals("approval")||node.type().equals("syntheticAction")){
            var gate=checkedGate(e,plan,snapshot.graph(),actors,false);
            if(handleGate(e,c,node,gate,now))return null;
            if(node.type().equals("approval")){fail(e,"RECONCILIATION_REQUIRED",now);return null;}
        }
        if(step.attempts()>=3){fail(e,"TRANSIENT_EXHAUSTED",now);return null;}
        if(node.type().equals("delay")&&step.state().equals("WAITING_FOR_DELAY"))jdbc.update("UPDATE execution_steps SET status='RUNNING',error_code=NULL WHERE organization_id=? AND execution_id=? AND node_id=?",e.organization(),e.id(),node.id());
        else jdbc.update("UPDATE execution_steps SET status='RUNNING',attempt_count=attempt_count+1,attempt_started_at=?,error_code=NULL WHERE organization_id=? AND execution_id=? AND node_id=?",time(now),e.organization(),e.id(),node.id());
        store.event(e,"STEP_STARTED",node.id(),now);
        return new Dispatch(c,node,e.actor(),step.operation());
    });}
    /** Effect, receipt, successful attempt and step result share one fenced database transaction. */
    public boolean complete(Dispatch dispatch){return Boolean.TRUE.equals(tx.execute(status->{
        var c=dispatch.claim();var preview=store.get(c.organization(),c.execution(),false);var actors=workerActors(preview);authority.worker(dispatch.actor(),c.organization());var e=owned(c);if(e==null)return false;Instant now=clock.instant();
        if(!e.deadline().isAfter(now)){fail(e,"DEADLINE_EXCEEDED",now);return false;}
        var step=store.steps(e.organization(),e.id()).get(e.position());
        if(!step.node().equals(dispatch.node().id())||!step.state().equals("RUNNING"))return false;
        var snapshot=workflows.snapshot(e.actor(),e.organization(),e.workflow(),e.version());var plan=ExecutionPlan.runtime(snapshot.graph(),graphs);boolean v2=data.v2(snapshot.graph());
        if(v2&&(!dispatch.node().equals(plan.get(e.position()))||!dispatch.operation().equals(step.operation())||!dispatch.actor().equals(e.actor()))){fail(e,"APPROVAL_INTENT_MISMATCH",now);return false;}
        String output="{}";
        if(step.type().equals("syntheticAction")){
            if(!dispatch.node().equals(plan.get(e.position()))){fail(e,"APPROVAL_INTENT_MISMATCH",now);return false;}
            var gate=checkedGate(e,plan,snapshot.graph(),actors,false);
            if(handleGate(e,c,dispatch.node(),gate,now))return false;
            var receipts=jdbc.queryForList("SELECT argument_hash,result_json FROM execution_operation_receipts WHERE organization_id=? AND capability='synthetic.record' AND logical_operation_id=? AND item_id=?",e.organization(),step.operation(),step.node());
            if(!receipts.isEmpty()){
                if(!step.argumentHash().equals(receipts.getFirst().get("argument_hash"))){fail(e,"RECONCILIATION_REQUIRED",now);return false;}
                output=(String)receipts.getFirst().get("result_json");store.event(e,"RECEIPT_REUSED",step.node(),now);
            }else{
                output="{\"recorded\":true}";
                if(v2){var result=tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();result.put("recorded",true);result.put("message",dispatch.node().message());output=graphs.stableValue(result);}
                jdbc.update("INSERT INTO synthetic_records(organization_id,logical_operation_id,message,recorded_at) VALUES(?,?,?,?)",e.organization(),step.operation(),dispatch.node().message(),time(now));
                jdbc.update("INSERT INTO execution_operation_receipts(organization_id,logical_operation_id,capability,item_id,argument_hash,result_json,committed_at) VALUES(?,?,'synthetic.record',?,?,?,?)",e.organization(),step.operation(),step.node(),step.argumentHash(),output,time(now));
                store.event(e,"RECORD_COMMITTED",step.node(),now);
            }
        }else if(v2){
            try{output=data.output(e,snapshot.graph(),step);}
            catch(DomainFailure invalid){attempt(e,step,"PERMANENT_FAILURE",invalid.code(),now);jdbc.update("UPDATE execution_steps SET status='FAILED',completed_at=?,error_code=? WHERE organization_id=? AND execution_id=? AND node_id=?",time(now),invalid.code(),e.organization(),e.id(),step.node());fail(e,invalid.code(),now);return false;}
        }
        attempt(e,step,"SUCCEEDED",null,now);
        jdbc.update("UPDATE execution_steps SET status='SUCCEEDED',completed_at=?,output_json=?,error_code=NULL WHERE organization_id=? AND execution_id=? AND node_id=?",time(now),output,e.organization(),e.id(),step.node());
        store.event(e,"STEP_SUCCEEDED",step.node(),now);advance(e);
        return true;
    }));}
    private void advance(Execution e){
        var snapshot=workflows.snapshot(e.actor(),e.organization(),e.workflow(),e.version());
        if(data.v2(snapshot.graph()))data.advance(e,snapshot.graph(),ExecutionPlan.runtime(snapshot.graph(),graphs));
        else jdbc.update("UPDATE workflow_executions SET current_position=current_position+1,revision=revision+1 WHERE organization_id=? AND id=? AND revision=?",e.organization(),e.id(),e.revision());
    }
    public void acknowledge(Claim c){tx.executeWithoutResult(status->{
        lockContext(c);var e=owned(c);if(e==null)return;
        if(!e.deadline().isAfter(clock.instant())){fail(e,"DEADLINE_EXCEEDED",clock.instant());return;}
        if(e.position()==store.steps(e.organization(),e.id()).size())succeed(e,clock.instant());else store.release(c,clock.instant(),false);
    });}
    public void failure(Dispatch dispatch,SyntheticCapability.FailureKind kind){tx.executeWithoutResult(status->{
        var c=dispatch.claim();lockContext(c);var e=owned(c);if(e==null)return;Instant now=clock.instant();
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
    public void permissionDenied(Claim c){tx.executeWithoutResult(status->{lockContext(c);var e=owned(c);if(e!=null)fail(e,"PERMISSION_REVOKED",clock.instant());});}
    private void fail(Execution e,String code,Instant now){
        service.terminateSteps(e,code.equals("DEADLINE_EXCEEDED")?code:"RECONCILIATION_REQUIRED",now);
        approvals.terminate(e.organization(),e.id(),e.actor(),code);
        store.state(e,ExecutionState.FAILED,code,now);store.done(e);store.event(e,"EXECUTION_FAILED",null,now);
        audit.write(e.organization(),e.actor(),ExecutionAudit.Action.EXECUTION_FAILED,e.id(),e.revision()+1,e.version(),e.hash(),now);
    }
    private void succeed(Execution e,Instant now){
        data.skipped(e,now);
        store.state(e,ExecutionState.SUCCEEDED,null,now);store.done(e);store.event(e,"EXECUTION_SUCCEEDED",null,now);
        audit.write(e.organization(),e.actor(),ExecutionAudit.Action.EXECUTION_SUCCEEDED,e.id(),e.revision()+1,e.version(),e.hash(),now);
    }
    private void attempt(Execution e,Step step,String outcome,String error,Instant now){
        jdbc.update("INSERT INTO execution_attempts(id,organization_id,execution_id,node_id,attempt_number,started_at,completed_at,outcome,error_code) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",UUID.randomUUID(),e.organization(),e.id(),step.node(),step.attempts(),time(step.started()),time(now),outcome,error);
    }
    private void lockContext(Claim c){
        var preview=store.get(c.organization(),c.execution(),false);
        authority.lockDurableContext(preview.actor(),c.organization());
    }
    /** Bounded reconciliation executes one execution per transaction, preserving lock order. */
    public int reconcile(){
        Instant now=clock.instant();var candidates=jdbc.queryForList("SELECT e.organization_id,e.id FROM workflow_executions e JOIN execution_work_items w ON w.organization_id=e.organization_id AND w.execution_id=e.id WHERE e.completed_at IS NULL AND (e.deadline_at<=? OR (w.state='CLAIMED' AND w.lease_expires_at<=?)) ORDER BY e.deadline_at LIMIT 100",time(now),time(now));
        int count=0;
        for(var candidate:candidates){UUID org=(UUID)candidate.get("organization_id"),id=(UUID)candidate.get("id");
            Boolean changed=tx.execute(status->{
                var preview=store.get(org,id,false);authority.lockDurableContext(preview.actor(),org);var e=store.get(org,id,true);if(e.state().terminal())return false;
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
        for(var candidate:approvals.candidates())if(resumeApproval(candidate))count++;
        return count;
    }
    private Set<UUID> workerActors(Execution e){var actors=new HashSet<>(approvals.actors(e.organization(),e.id()));actors.add(e.actor());authority.lockActors(actors);return Set.copyOf(actors);}
    private ApprovalRuntime.Gate checkedGate(Execution e,List<ExecutionPlan.Node> plan,tools.jackson.databind.JsonNode graph,Set<UUID> actors,boolean resume){
        try{var intent=seed(e,plan,e.position(),graph);return resume?approvals.resumeCheck(intent,actors):approvals.check(intent,explicit(e,plan,e.position(),graph),actors);}
        catch(DomainFailure failure){if(!failure.code().equals("OPERATION_INTENT_MISMATCH"))throw failure;return new ApprovalRuntime.Gate(ApprovalRuntime.Result.FAIL,null,failure.code(),false);}
    }
    private boolean explicit(Execution e,List<ExecutionPlan.Node> plan,int position,tools.jackson.databind.JsonNode graph){return data.gate(e,graph,plan,position).type().equals("approval");}
    private ApprovalRuntime.Seed seed(Execution e,List<ExecutionPlan.Node> plan,int position,tools.jackson.databind.JsonNode graph){
        var action=data.action(graph,plan,position);var gate=data.gate(e,graph,plan,position);
        var step=store.steps(e.organization(),e.id()).stream().filter(s->s.node().equals(action.id())).findFirst().orElseThrow();
        if(data.v2(graph))data.prepareRecord(e,step,action.message());
        return new ApprovalRuntime.Seed(e.organization(),e.id(),gate.id(),action.id(),e.version(),e.hash(),e.actor(),step.operation(),step.argumentHash(),gate.type().equals("approval")?gate.prompt():"Review the exact internal record operation",action.message(),e.deadline());
    }
    private boolean handleGate(Execution e,Claim c,ExecutionPlan.Node node,ApprovalRuntime.Gate gate,Instant now){
        if(gate.result()==ApprovalRuntime.Result.ALLOW)return false;
        if(gate.result()==ApprovalRuntime.Result.FAIL){fail(e,gate.error(),now);return true;}
        var step=store.steps(e.organization(),e.id()).get(e.position());
        if(gate.result()==ApprovalRuntime.Result.RECHECK_ACTORS){
            if(step.state().equals("RUNNING")){attempt(e,step,"INTERRUPTED","ACTOR_SET_CHANGED",now);jdbc.update("UPDATE execution_steps SET status='WAITING_FOR_RETRY',error_code='ACTOR_SET_CHANGED' WHERE organization_id=? AND execution_id=? AND node_id=?",e.organization(),e.id(),step.node());store.state(e,ExecutionState.WAITING_FOR_RETRY,"ACTOR_SET_CHANGED",now);}
            store.release(c,now,false);return true;
        }
        if(step.state().equals("RUNNING"))attempt(e,step,"INTERRUPTED","APPROVAL_REQUIRED",now);
        jdbc.update("UPDATE execution_steps SET status='WAITING_FOR_APPROVAL',error_code=NULL WHERE organization_id=? AND execution_id=? AND node_id=?",e.organization(),e.id(),node.id());
        store.state(e,ExecutionState.WAITING_FOR_APPROVAL,null,now);store.park(c,now);store.event(e,gate.created()?"APPROVAL_REQUESTED":"APPROVAL_WAITING",node.id(),now);return true;
    }
    private boolean resumeApproval(ApprovalRuntime.Candidate candidate){return Boolean.TRUE.equals(tx.execute(status->{
        var preview=store.get(candidate.organization(),candidate.execution(),false);var actors=workerActors(preview);authority.lockDurableContext(preview.actor(),preview.organization());
        var e=store.get(preview.organization(),preview.id(),true);jdbc.queryForList("SELECT execution_id FROM execution_work_items WHERE organization_id=? AND execution_id=? FOR UPDATE",e.organization(),e.id());
        approvals.reconcile(e.organization(),e.id(),e.actor());boolean changed=false;
        for(var signal:approvals.signals(e.organization(),e.id())){
            e=store.get(e.organization(),e.id(),false);Instant now=clock.instant();
            if(e.state().terminal()){changed|=consumeApprovalSignal(e,signal);continue;}
            if(!e.deadline().isAfter(now)){fail(e,"DEADLINE_EXCEEDED",now);changed|=consumeApprovalSignal(e,signal);continue;}
            if(e.state()!=ExecutionState.WAITING_FOR_APPROVAL){fail(e,"RECONCILIATION_REQUIRED",now);changed|=consumeApprovalSignal(e,signal);continue;}
            try{authority.trusted(e.actor(),e.organization(),"execution.run");}catch(DomainFailure revoked){fail(e,"PERMISSION_REVOKED",now);changed|=consumeApprovalSignal(e,signal);continue;}
            var snapshot=workflows.snapshot(e.actor(),e.organization(),e.workflow(),e.version());var plan=ExecutionPlan.runtime(snapshot.graph(),graphs);
            if(e.position()>=plan.size()||!plan.get(e.position()).id().equals(signal.gate())){fail(e,"APPROVAL_INTENT_MISMATCH",now);changed|=consumeApprovalSignal(e,signal);continue;}
            var gate=checkedGate(e,plan,snapshot.graph(),actors,true);
            if(gate.result()==ApprovalRuntime.Result.RECHECK_ACTORS)continue;
            if(!signal.outcome().equals("APPROVED")||gate.result()!=ApprovalRuntime.Result.ALLOW){fail(e,gate.error()==null?"APPROVAL_"+signal.outcome():gate.error(),now);changed|=consumeApprovalSignal(e,signal);continue;}
            changed|=consumeApprovalSignal(e,signal);
            var node=plan.get(e.position());
            if(node.type().equals("approval")){
                jdbc.update("UPDATE execution_steps SET status='SUCCEEDED',attempt_count=1,attempt_started_at=?,completed_at=?,output_json='{\"approved\":true}',error_code=NULL WHERE organization_id=? AND execution_id=? AND node_id=? AND status='WAITING_FOR_APPROVAL'",time(signal.createdAt()),time(now),e.organization(),e.id(),node.id());
                var step=store.steps(e.organization(),e.id()).get(e.position());attempt(e,step,"SUCCEEDED",null,now);store.event(e,"STEP_SUCCEEDED",node.id(),now);advance(e);e=store.get(e.organization(),e.id(),false);
            }else jdbc.update("UPDATE execution_steps SET status='PENDING',error_code=NULL WHERE organization_id=? AND execution_id=? AND node_id=? AND status='WAITING_FOR_APPROVAL'",e.organization(),e.id(),node.id());
            store.state(e,ExecutionState.QUEUED,null,now);store.resume(e,now);store.event(e,"EXECUTION_RESUMED",node.id(),now);
        }
        return changed;
    }));}
    private boolean consumeApprovalSignal(Execution e,ApprovalRuntime.SignalView signal){
        if(!approvals.consume(e.organization(),e.id(),signal.id(),e.actor()))return false;
        store.event(e,"APPROVAL_SIGNAL_"+signal.outcome(),signal.gate(),clock.instant());
        store.event(e,"APPROVAL_SIGNAL_CONSUMED",signal.gate(),clock.instant());return true;
    }
    public boolean renew(Claim c,int leaseSeconds){return Boolean.TRUE.equals(tx.execute(status->{
        lockContext(c);var e=owned(c);if(e==null)return false;
        if(!e.deadline().isAfter(clock.instant())){fail(e,"DEADLINE_EXCEEDED",clock.instant());return false;}
        return jdbc.update("UPDATE execution_work_items SET lease_expires_at=? WHERE organization_id=? AND execution_id=? AND lease_owner=? AND fencing_token=? AND state='CLAIMED'",time(clock.instant().plusSeconds(leaseSeconds)),c.organization(),c.execution(),c.owner(),c.token())==1;
    }));}
}
