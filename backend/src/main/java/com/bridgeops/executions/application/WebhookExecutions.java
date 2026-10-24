package com.bridgeops.executions.application;

import com.bridgeops.audit.application.ExecutionAudit;
import com.bridgeops.executions.domain.ExecutionPlan;
import com.bridgeops.executions.persistence.ExecutionStore;
import com.bridgeops.shared.domain.AutomationOrigin;
import com.bridgeops.shared.web.DomainFailure;
import com.bridgeops.workflows.application.*;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.JsonNode;

/** Trusted delivery boundary, not an HTTP entry point. The caller must commit its verified receipt in this transaction. */
@Service
public class WebhookExecutions {
    private final ExecutionStore store;private final ExecutionAutomation authority;private final WorkflowRuntime workflows;private final WorkflowDefinitions definitions;private final ExecutionAudit audit;private final Clock clock;
    public WebhookExecutions(ExecutionStore store,ExecutionAutomation authority,WorkflowRuntime workflows,WorkflowDefinitions definitions,ExecutionAudit audit,Clock clock){this.store=store;this.authority=authority;this.workflows=workflows;this.definitions=definitions;this.audit=audit;this.clock=clock;}
    public record Accepted(UUID execution,java.time.Instant createdAt,boolean replay){}
    @Transactional(propagation=Propagation.MANDATORY)
    public Accepted submit(UUID org,AutomationOrigin origin,UUID workflow,UUID version,String hash,JsonNode input,String key){
        authority.authorize(org,origin,workflow,version,hash);var source=workflows.automationSnapshot(org,origin.principal(),workflow,version,hash);
        if(source.graph().path("schemaVersion").asInt()!=2||!java.util.stream.StreamSupport.stream(source.graph().path("nodes").spliterator(),false).anyMatch(n->n.path("type").asString().equals("webhookTrigger"))||key==null||!key.matches("[a-f0-9]{64}"))throw new DomainFailure(422,"INCOMPATIBLE_AUTOMATION_WORKFLOW");
        var normalized=definitions.triggerV2(source.graph(),input);String text=definitions.stableValue(normalized),fingerprint=ExecutionPlan.hash(version+"\n"+hash+"\n"+text+"\n0\n");var previous=store.replay(org,workflow,"WEBHOOK",key);
        if(previous.isPresent()){var e=previous.get();if(!e.fingerprint().equals(fingerprint)||!Objects.equals(e.automation(),origin))throw new DomainFailure(409,"IDEMPOTENCY_CONFLICT");return new Accepted(e.id(),e.created(),true);}
        var plan=ExecutionPlan.runtime(source.graph(),definitions);
        if(!Boolean.TRUE.equals(store.jdbc().queryForObject("SELECT pg_try_advisory_xact_lock(834721906)",Boolean.class)))throw new DomainFailure(429,"WEBHOOK_BUSY");
        if(active(null,null,null)>=10000||active(org,null,null)>=1000||active(org,origin.endpoint(),null)>=100||active(org,null,origin.principal())>=100)throw new DomainFailure(429,"WEBHOOK_QUEUE_LIMIT");
        UUID id=UUID.randomUUID();var now=clock.instant();store.insertAutomation(id,org,workflow,version,hash,origin,text,key,fingerprint,now);for(int i=0;i<plan.size();i++){var node=plan.get(i);store.insertStepV2(org,id,node.id(),i,node.type(),UUID.randomUUID(),node.argumentHash());}
        var e=store.get(org,id,false);store.event(e,"EXECUTION_QUEUED",null,now);audit.write(org,e.requester(),ExecutionAudit.Action.EXECUTION_STARTED,id,0,version,hash,now);return new Accepted(id,e.created(),false);
    }
    private long active(UUID org,UUID endpoint,UUID principal){return store.jdbc().queryForObject("SELECT count(*) FROM workflow_executions WHERE requester_type='AUTOMATION' AND completed_at IS NULL AND (?::uuid IS NULL OR organization_id=?) AND (?::uuid IS NULL OR webhook_endpoint_id=?) AND (?::uuid IS NULL OR automation_principal_id=?)",Long.class,org,org,endpoint,endpoint,principal,principal);}
}
