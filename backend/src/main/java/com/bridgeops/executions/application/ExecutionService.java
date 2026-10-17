package com.bridgeops.executions.application;

import com.bridgeops.audit.application.ExecutionAudit;
import com.bridgeops.executions.domain.*;
import com.bridgeops.executions.persistence.ExecutionStore;
import com.bridgeops.executions.persistence.ExecutionStore.Execution;
import com.bridgeops.organizations.application.TenantAuthorization;
import com.bridgeops.shared.web.DomainFailure;
import com.bridgeops.workflows.application.WorkflowService;
import com.bridgeops.workflows.application.WorkflowDefinitions;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import static com.bridgeops.executions.application.ExecutionViews.*;
import static com.bridgeops.executions.persistence.ExecutionStore.time;

@Service
public class ExecutionService {
    private final TenantAuthorization authority;private final WorkflowService workflows;private final WorkflowDefinitions graphs;
    private final ExecutionStore store;private final ExecutionAudit audit;private final Clock clock;
    public ExecutionService(TenantAuthorization authority,WorkflowService workflows,WorkflowDefinitions graphs,ExecutionStore store,ExecutionAudit audit,Clock clock){
        this.authority=authority;this.workflows=workflows;this.graphs=graphs;this.store=store;this.audit=audit;this.clock=clock;
    }
    @Transactional(timeout=10)
    public Submission start(UUID actor,UUID family,UUID org,UUID workflow,UUID version,JsonNode input,int delay,String key){
        authority.write(actor,family,org,"execution.run");
        var draft=workflows.get(actor,family,org,workflow);
        if(!draft.workflow().status().equals("ACTIVE"))throw new DomainFailure(409,"WORKFLOW_ARCHIVED");
        UUID pinned=version==null?draft.workflow().currentPublishedVersionId():version;
        if(pinned==null)throw new DomainFailure(422,"WORKFLOW_NOT_PUBLISHED");
        return submit(actor,family,org,workflow,pinned,input,delay,key,null);
    }
    private Submission submit(UUID actor,UUID family,UUID org,UUID workflow,UUID version,JsonNode input,int delay,String key,Execution retry){
        key(key);if(delay<0||delay>3600)throw new DomainFailure(400,"INVALID_REQUEST");
        var snapshot=workflows.version(actor,family,org,workflow,version);
        String source=retry==null?"MANUAL":"RETRY";
        String fingerprint=ExecutionPlan.hash(version+"\n"+snapshot.version().definitionHash()+"\n"+String.valueOf(input)+"\n"+delay+"\n"+(retry==null?"":retry.id()));
        var existing=store.replay(org,workflow,source,key);
        if(existing.isPresent()){
            if(!existing.get().fingerprint().equals(fingerprint))throw new DomainFailure(409,"IDEMPOTENCY_CONFLICT");
            return new Submission(summary(existing.get()),true);
        }
        if(input==null||!input.isObject()||!input.isEmpty())throw new DomainFailure(422,"UNSUPPORTED_TRIGGER_INPUT");
        var plan=ExecutionPlan.from(snapshot.definition(),graphs);UUID id=UUID.randomUUID();Instant now=clock.instant();
        store.insert(id,org,workflow,version,snapshot.version().definitionHash(),actor,source,key,fingerprint,retry==null?null:retry.id(),now,now.plusSeconds(delay));
        Map<String,ExecutionStore.Step> old=new HashMap<>();if(retry!=null)for(var step:store.steps(org,retry.id()))old.put(step.node(),step);
        for(int i=0;i<plan.size();i++){
            var node=plan.get(i);var original=old.get(node.id());
            store.insertStep(org,id,node.id(),i,node.type(),original==null?UUID.randomUUID():original.operation(),node.argumentHash());
        }
        var execution=store.get(org,id,false);store.event(execution,"EXECUTION_QUEUED",null,now);
        audit.write(org,actor,retry==null?ExecutionAudit.Action.EXECUTION_STARTED:ExecutionAudit.Action.EXECUTION_RETRIED,id,0,version,execution.hash(),now);
        return new Submission(summary(execution),false);
    }
    @Transactional(timeout=10)
    public Summary cancel(UUID actor,UUID family,UUID org,UUID id,long revision,String reason){
        authority.write(actor,family,org,"execution.cancel");reason(reason);var e=store.get(org,id,true);revision(e,revision);
        if(e.state().terminal())throw new DomainFailure(409,"EXECUTION_TERMINAL");
        Instant now=clock.instant();terminateSteps(e,"CANCELLED",now);
        store.state(e,ExecutionState.CANCELLED,"CANCELLED_BY_USER",now);store.done(e);store.event(e,"EXECUTION_CANCELLED",null,now);
        audit.write(org,actor,ExecutionAudit.Action.EXECUTION_CANCELLED,id,revision+1,e.version(),e.hash(),now);return summary(store.get(org,id,false));
    }
    @Transactional(timeout=10)
    public Submission retry(UUID actor,UUID family,UUID org,UUID id,long revision,String reason,String key){
        authority.write(actor,family,org,"execution.retry");authority.write(actor,family,org,"execution.run");reason(reason);
        var e=store.get(org,id,true);revision(e,revision);
        if(!eligible(e))throw new DomainFailure(409,"RETRY_INELIGIBLE");
        var workflow=workflows.get(actor,family,org,e.workflow());if(!workflow.workflow().status().equals("ACTIVE"))throw new DomainFailure(409,"WORKFLOW_ARCHIVED");
        return submit(actor,family,org,e.workflow(),e.version(),graphs.parse("{}"),0,key,e);
    }
    @Transactional(readOnly=true,timeout=5)
    public Summary get(UUID actor,UUID family,UUID org,UUID id){authority.read(actor,family,org,"execution.read");return summary(store.get(org,id,false));}
    @Transactional(readOnly=true,timeout=5)
    public Page<Summary> list(UUID actor,UUID family,UUID org,int page,int size,UUID workflow,ExecutionState status){
        authority.read(actor,family,org,"execution.read");bounds(page,size);
        var rows=store.list(org,page,size,workflow,status);
        long total=store.jdbc().queryForObject("SELECT count(*) FROM workflow_executions WHERE organization_id=? AND (?::uuid IS NULL OR workflow_id=?) AND (?::varchar IS NULL OR status=?)",Long.class,org,workflow,workflow,status==null?null:status.name(),status==null?null:status.name());
        return page(rows.stream().map(this::summary).toList(),page,size,total);
    }
    @Transactional(readOnly=true,timeout=5)
    public List<Step> steps(UUID actor,UUID family,UUID org,UUID id){authority.read(actor,family,org,"execution.read");store.get(org,id,false);
        var due=store.due(org,id);
        return store.steps(org,id).stream().map(s->new Step(s.node(),s.position(),s.type(),s.state(),s.attempts(),s.started(),s.completed(),s.output()==null?null:graphs.parse(s.output()),s.error(),s.state().equals("WAITING_FOR_RETRY")?due:null)).toList();
    }
    @Transactional(readOnly=true,timeout=5)
    public Page<Attempt> attempts(UUID actor,UUID family,UUID org,UUID id,int page,int size){authority.read(actor,family,org,"execution.read");store.get(org,id,false);bounds(page,size);
        var items=store.jdbc().query("SELECT * FROM execution_attempts WHERE organization_id=? AND execution_id=? ORDER BY started_at,id LIMIT ? OFFSET ?",(r,i)->new Attempt(r.getObject("id",UUID.class),r.getString("node_id"),r.getInt("attempt_number"),r.getTimestamp("started_at").toInstant(),r.getTimestamp("completed_at").toInstant(),r.getString("outcome"),r.getString("error_code")),org,id,size,(long)page*size);
        long total=store.jdbc().queryForObject("SELECT count(*) FROM execution_attempts WHERE organization_id=? AND execution_id=?",Long.class,org,id);return page(items,page,size,total);
    }
    @Transactional(readOnly=true,timeout=5)
    public Page<Event> events(UUID actor,UUID family,UUID org,UUID id,int page,int size){authority.read(actor,family,org,"execution.read");store.get(org,id,false);bounds(page,size);
        var items=store.jdbc().query("SELECT * FROM execution_events WHERE organization_id=? AND execution_id=? ORDER BY event_sequence LIMIT ? OFFSET ?",(r,i)->new Event(r.getLong("event_sequence"),r.getTimestamp("occurred_at").toInstant(),r.getString("code"),r.getString("node_id")),org,id,size,(long)page*size);
        long total=store.jdbc().queryForObject("SELECT count(*) FROM execution_events WHERE organization_id=? AND execution_id=?",Long.class,org,id);return page(items,page,size,total);
    }
    void terminateSteps(Execution e,String outcome,Instant now){
        for(var step:store.steps(e.organization(),e.id()))if(step.state().equals("RUNNING")){
            store.jdbc().update("INSERT INTO execution_attempts(id,organization_id,execution_id,node_id,attempt_number,started_at,completed_at,outcome,error_code) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",UUID.randomUUID(),e.organization(),e.id(),step.node(),step.attempts(),time(step.started()),time(now),outcome,outcome);
        }
        store.jdbc().update("UPDATE execution_steps SET status=CASE WHEN status='PENDING' AND ?='FAILED' THEN 'SKIPPED' ELSE ? END,completed_at=?,error_code=CASE WHEN status='PENDING' THEN 'NOT_DISPATCHED' ELSE ? END WHERE organization_id=? AND execution_id=? AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED','SKIPPED')",outcome.equals("CANCELLED")?"CANCELLED":"FAILED",outcome.equals("CANCELLED")?"CANCELLED":"FAILED",time(now),outcome,e.organization(),e.id());
    }
    public Summary summary(Execution e){return new Summary(e.id(),e.workflow(),e.version(),e.hash(),e.actor(),e.source(),"LIVE_EXECUTION",e.state(),e.revision(),e.position(),e.created(),e.started(),e.completed(),e.deadline(),e.notBefore(),e.error(),e.retryOf(),eligible(e));}
    private boolean eligible(Execution e){return e.state()==ExecutionState.FAILED&&"TRANSIENT_EXHAUSTED".equals(e.error());}
    private void revision(Execution e,long expected){if(expected<0)throw new DomainFailure(400,"INVALID_REQUEST");if(e.revision()!=expected)throw new DomainFailure(409,"STALE_REVISION");}
    private void reason(String value){if(value==null||value.isBlank()||value.length()>200||value.codePoints().anyMatch(Character::isISOControl))throw new DomainFailure(400,"INVALID_REQUEST");}
    private void key(String value){if(value==null||!value.matches("[A-Za-z0-9_-]{8,128}"))throw new DomainFailure(400,"IDEMPOTENCY_KEY_REQUIRED");}
    private void bounds(int page,int size){if(page<0||page>100000||size<1||size>100)throw new DomainFailure(400,"INVALID_REQUEST");}
    private <T> Page<T> page(List<T> items,int page,int size,long total){return new Page<>(items,page,size,total,(total+size-1)/size);}
}
