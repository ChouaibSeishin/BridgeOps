package com.bridgeops.approvals.application;

import com.bridgeops.approvals.domain.ApprovalState;
import com.bridgeops.approvals.persistence.ApprovalStore;
import com.bridgeops.approvals.persistence.ApprovalStore.*;
import com.bridgeops.audit.application.ApprovalAudit;
import com.bridgeops.identity.application.IdentityQueries;
import com.bridgeops.organizations.application.TenantAuthorization;
import com.bridgeops.shared.web.DomainFailure;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ApprovalService {
    public record PolicyView(long version,long revision,boolean requireRecordApproval,int lifetimeSeconds) {}
    public record DecisionView(UUID id,UUID actor,String decision,Instant at,long expectedRevision,String reason,String actorName) {}
    public record EventView(long sequence,Instant occurredAt,UUID actor,String code,String actorName) {}
    public record View(UUID id,UUID executionId,UUID workflowId,UUID versionId,String definitionHash,UUID requestedBy,
        String gateNodeId,String actionNodeId,String prompt,String capability,String operation,JsonNode arguments,String argumentHash,String intentHash,
        long policyVersion,JsonNode policySnapshot,Instant createdAt,Instant expiresAt,Instant deadlineAt,
        ApprovalState status,long revision,Instant finalizedAt,String errorCode,String executionStatus,boolean actionable,boolean invalidated,
        DecisionView decision,List<EventView> events,String workflowName,int versionNumber,String requesterName) {}
    public record Page(List<View> items,int page,int size,long totalElements,long totalPages) {}
    private final ApprovalStore store;private final TenantAuthorization authority;private final ApprovalExecutionContext executions;
    private final ApprovalLifecycle lifecycle;private final ApprovalAudit audit;private final Clock clock;
    private final IdentityQueries identities;
    private final JsonMapper json=JsonMapper.builder().build();
    public ApprovalService(ApprovalStore store,TenantAuthorization authority,ApprovalExecutionContext executions,ApprovalLifecycle lifecycle,ApprovalAudit audit,Clock clock,IdentityQueries identities){this.store=store;this.authority=authority;this.executions=executions;this.lifecycle=lifecycle;this.audit=audit;this.clock=clock;this.identities=identities;}
    @Transactional(readOnly=true,timeout=5)
    public PolicyView policy(UUID actor,UUID family,UUID org){authority.read(actor,family,org,"approval.read");return policyView(store.policy(org));}
    @Transactional(timeout=10)
    public PolicyView updatePolicy(UUID actor,UUID family,UUID org,long expected,boolean required,int lifetime){
        authority.write(actor,family,org,"policy.manage");
        if(expected<0||lifetime<60||lifetime>86400)throw new DomainFailure(422,"INVALID_APPROVAL_POLICY");
        var old=store.policy(org);if(old.version()!=expected)throw new DomainFailure(409,"STALE_REVISION");Instant now=clock.instant();
        store.ensurePolicy(org,now);var next=new Policy(old.version()+1,required,lifetime);store.changePolicy(org,actor,next,now);
        for(var r:store.pending(org,null))lifecycle.finish(r,r.expires().isAfter(now)?ApprovalState.INVALIDATED:ApprovalState.EXPIRED,r.expires().isAfter(now)?"POLICY_CHANGED":"APPROVAL_EXPIRED",actor,now);
        audit.write(org,actor,ApprovalAudit.Action.APPROVAL_POLICY_CHANGED,org,next.version(),now);return policyView(next);
    }
    @Transactional(readOnly=true,timeout=5)
    public View get(UUID actor,UUID family,UUID org,UUID id){authority.read(actor,family,org,"approval.read");return view(actor,family,store.get(org,id,false),true);}
    @Transactional(readOnly=true,timeout=5)
    public Page list(UUID actor,UUID family,UUID org,int page,int size,String state,UUID execution){
        authority.read(actor,family,org,"approval.read");if(page<0||page>100000||size<1||size>100)throw new DomainFailure(400,"INVALID_REQUEST");
        if(state!=null)try{ApprovalState.valueOf(state);}catch(IllegalArgumentException bad){throw new DomainFailure(400,"INVALID_REQUEST");}
        long total=store.count(org,state,execution);return new Page(store.list(org,page,size,state,execution).stream().map(r->view(actor,family,r,false)).toList(),page,size,total,(total+size-1)/size);
    }
    @Transactional(timeout=10)
    public View decide(UUID actor,UUID family,UUID org,UUID id,long revision,String outcome,String reason){
        authority.write(actor,family,org,"approval.decide");
        if(revision<0||!Set.of("APPROVE","REJECT").contains(outcome==null?"":outcome)||reason!=null&&(reason.length()>200||reason.codePoints().anyMatch(Character::isISOControl)))throw new DomainFailure(400,"INVALID_REQUEST");
        var preview=store.get(org,id,false);var execution=executions.lock(actor,family,org,preview.execution());var r=store.get(org,id,true);Instant now=clock.instant();
        if(r.requester().equals(actor))throw new DomainFailure(403,"SELF_APPROVAL_FORBIDDEN");
        if(r.revision()!=revision||r.state()!=ApprovalState.PENDING)throw new DomainFailure(409,"APPROVAL_CONFLICT");
        if(!r.expires().isAfter(now)||!execution.deadline().isAfter(now))throw new DomainFailure(409,"APPROVAL_EXPIRED");
        if(execution.terminal()||!execution.status().equals("WAITING_FOR_APPROVAL"))throw new DomainFailure(409,"EXECUTION_NOT_WAITING");
        if(r.policyVersion()!=store.policy(org).version()||store.invalidated(org,id))throw new DomainFailure(409,"APPROVAL_INVALIDATED");
        var action=execution.action(r.action());
        if(!execution.version().equals(r.version())||!execution.definitionHash().equals(r.definitionHash())||!execution.requester().equals(r.requester())
            ||!action.operation().equals(r.operation())||!action.argumentHash().equals(r.operationHash())||Set.of("SUCCEEDED","RUNNING","FAILED","CANCELLED","SKIPPED").contains(action.status())
            ||!execution.steps().get(execution.position()).node().equals(r.gate()))throw new DomainFailure(409,"APPROVAL_INTENT_MISMATCH");
        var canonical=ApprovalIntent.record(org,r.execution(),r.gate(),r.action(),execution.version(),execution.definitionHash(),execution.requester(),action.operation(),r.policyVersion(),r.policySnapshot(),r.prompt(),json.readTree(r.arguments()).path("message").asString(),r.created(),r.expires(),execution.deadline());
        if(!canonical.hash().equals(r.intentHash()))throw new DomainFailure(409,"APPROVAL_INTENT_MISMATCH");
        store.decide(r,actor,outcome,reason,now);lifecycle.finish(r,outcome.equals("APPROVE")?ApprovalState.APPROVED:ApprovalState.REJECTED,null,actor,now);
        return view(actor,family,store.get(org,id,false),true);
    }
    private View view(UUID actor,UUID family,Request r,boolean detail){
        var e=executions.read(actor,family,r.organization(),r.execution());boolean decide=authority.permitted(actor,family,r.organization(),"approval.decide");
        boolean invalid=store.invalidated(r.organization(),r.id())||r.policyVersion()!=store.policy(r.organization()).version();Instant now=clock.instant();
        boolean actionable=decide&&!actor.equals(r.requester())&&r.state()==ApprovalState.PENDING&&!invalid&&r.expires().isAfter(now)&&e.deadline().isAfter(now)&&e.status().equals("WAITING_FOR_APPROVAL");
        var recorded=store.decision(r.organization(),r.id());var history=detail?store.events(r.organization(),r.id()):List.<Event>of();
        var actors=new HashSet<UUID>();actors.add(r.requester());recorded.ifPresent(d->actors.add(d.actor()));history.forEach(v->actors.add(v.actor()));var labels=identities.labels(actors);
        java.util.function.Function<UUID,String> name=id->labels.containsKey(id)?labels.get(id).displayName():"Member";
        var decision=recorded.map(d->new DecisionView(d.id(),d.actor(),d.decision(),d.at(),d.expectedRevision(),d.reason(),name.apply(d.actor()))).orElse(null);
        var events=history.stream().map(v->new EventView(v.sequence(),v.occurredAt(),v.actor(),v.code(),name.apply(v.actor()))).toList();
        return new View(r.id(),r.execution(),e.workflow(),r.version(),r.definitionHash(),r.requester(),r.gate(),r.action(),r.prompt(),"synthetic","record",json.readTree(r.arguments()),r.argumentHash(),r.intentHash(),r.policyVersion(),json.readTree(r.policySnapshot()),r.created(),r.expires(),r.deadline(),r.state(),r.revision(),r.finalized(),r.error(),e.status(),actionable,invalid,decision,events,e.workflowName(),e.versionNumber(),name.apply(r.requester()));
    }
    private PolicyView policyView(Policy p){return new PolicyView(p.version(),p.version(),p.required(),p.lifetime());}
}
