package com.bridgeops.approvals.application;

import com.bridgeops.approvals.domain.ApprovalState;
import com.bridgeops.approvals.persistence.ApprovalStore;
import com.bridgeops.approvals.persistence.ApprovalStore.*;
import com.bridgeops.audit.application.ApprovalAudit;
import com.bridgeops.organizations.application.TenantAuthorization;
import com.bridgeops.shared.web.DomainFailure;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Trusted execution boundary. Caller holds declared actors, tenant, execution and work locks. */
@Service
public class ApprovalRuntime {
    public record Seed(UUID organization,UUID execution,String gate,String action,UUID version,String definitionHash,
        UUID requester,UUID operation,String operationHash,String prompt,String message,Instant deadline) {}
    public record OperationSeed(UUID organization,UUID execution,String gate,String action,UUID version,String definitionHash,
        UUID requester,UUID operation,String blueprintHash,UUID prepared,String capability,String registeredOperation,String classification,
        OperationApprovalIntent.Connection connection,String arguments,String prompt,Instant deadline) {}
    public enum Result { ALLOW,WAIT,FAIL,RECHECK_ACTORS }
    public record Gate(Result result,UUID request,String error,boolean created) {}
    public record SignalView(UUID id,UUID request,UUID execution,String outcome,String gate,String action,Instant createdAt) {}
    public record Candidate(UUID organization,UUID execution,UUID requester) {}
    public record Waiting(UUID id,Instant expiresAt,String status) {}
    private final ApprovalStore store;private final ApprovalLifecycle lifecycle;private final ApprovalAudit audit;
    private final TenantAuthorization authority;private final Clock clock;
    public ApprovalRuntime(ApprovalStore store,ApprovalLifecycle lifecycle,ApprovalAudit audit,TenantAuthorization authority,Clock clock){this.store=store;this.lifecycle=lifecycle;this.audit=audit;this.authority=authority;this.clock=clock;}
    @Transactional(readOnly=true,timeout=5)
    public Set<UUID> actors(UUID org,UUID execution){return store.decisionActors(org,execution);}
    @Transactional(readOnly=true,timeout=5)
    public List<Candidate> candidates(){return store.candidates(clock.instant()).stream().map(c->new Candidate(c.organization(),c.execution(),c.requester())).toList();}
    @Transactional(readOnly=true,timeout=5)
    public Waiting waiting(UUID org,UUID execution){return store.outstanding(org,execution).map(r->new Waiting(r.id(),r.expires(),r.state().name())).orElse(null);}
    @Transactional(propagation=Propagation.MANDATORY)
    public Gate check(Seed seed,boolean explicit,Set<UUID> lockedActors){return gate(seed,explicit,lockedActors,true);}
    @Transactional(propagation=Propagation.MANDATORY)
    public Gate resumeCheck(Seed seed,Set<UUID> lockedActors){return gate(seed,true,lockedActors,false);}
    @Transactional(propagation=Propagation.MANDATORY)
    public Gate check(OperationSeed seed,boolean explicit,Set<UUID> lockedActors){return operationGate(seed,explicit,lockedActors,true);}
    @Transactional(propagation=Propagation.MANDATORY)
    public Gate resumeCheck(OperationSeed seed,Set<UUID> lockedActors){return operationGate(seed,true,lockedActors,false);}
    private Gate operationGate(OperationSeed seed,boolean explicit,Set<UUID> lockedActors,boolean requireConsumed){
        var existing=store.action(seed.organization(),seed.execution(),seed.action());
        // A V16 execution already waiting on a schema-1 record request keeps that exact request/hash.
        if(existing.isPresent()&&!existing.get().generalized()){
            if(!seed.capability().equals("synthetic")||!seed.registeredOperation().equals("record"))return new Gate(Result.FAIL,existing.get().id(),"INTENT_MISMATCH",false);
            String message=tools.jackson.databind.json.JsonMapper.builder().build().readTree(seed.arguments()).path("message").asString();
            return gate(new Seed(seed.organization(),seed.execution(),seed.gate(),seed.action(),seed.version(),seed.definitionHash(),seed.requester(),seed.operation(),seed.blueprintHash(),seed.prompt(),message,seed.deadline()),explicit,lockedActors,requireConsumed);
        }
        if(!lockedActors.contains(seed.requester()))return new Gate(Result.RECHECK_ACTORS,null,null,false);
        try{authority.trusted(seed.requester(),seed.organization(),seed.classification().equals("WRITE")?"capability.write":"capability.read");}
        catch(DomainFailure revoked){return new Gate(Result.FAIL,null,"CAPABILITY_PERMISSION_REVOKED",false);}
        var policy=store.policy(seed.organization());Instant now=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if(!seed.deadline().isAfter(now))return new Gate(Result.FAIL,null,"DEADLINE_EXCEEDED",false);
        Instant candidateExpiry=now.plusSeconds(policy.lifetime());if(candidateExpiry.isAfter(seed.deadline()))candidateExpiry=seed.deadline();
        OperationApprovalIntent candidate;
        try{candidate=operationIntent(seed,policy.version(),policy.snapshot(),now,candidateExpiry);}
        catch(IllegalArgumentException malformed){return new Gate(Result.FAIL,null,"INTENT_MISMATCH",false);}
        if(existing.isEmpty()){
            boolean mandatoryWrite=seed.classification().equals("WRITE")&&Set.of("support","rest").contains(seed.capability());
            if(!explicit&&!mandatoryWrite&&!(seed.capability().equals("synthetic")&&policy.required()))return new Gate(Result.ALLOW,null,null,false);
            if(!seed.deadline().isAfter(now))return new Gate(Result.FAIL,null,"DEADLINE_EXCEEDED",false);
            store.ensurePolicy(seed.organization(),now);
            var r=store.insert(candidate,seed.blueprintHash());
            store.event(r,seed.requester(),"APPROVAL_REQUESTED",now);audit.write(r.organization(),seed.requester(),ApprovalAudit.Action.APPROVAL_REQUESTED,r.id(),0,now);
            return new Gate(Result.WAIT,r.id(),null,true);
        }
        var r=store.get(seed.organization(),existing.get().id(),true);
        if(r.state()!=ApprovalState.PENDING&&r.state()!=ApprovalState.APPROVED)return new Gate(Result.FAIL,r.id(),r.error()==null?"APPROVAL_"+r.state().name():r.error(),false);
        String invalid=null;
        try{var actual=operationIntent(seed,r.policyVersion(),r.policySnapshot(),r.created(),r.expires());if(!r.operationHash().equals(seed.blueprintHash())||!actual.hash().equals(r.intentHash())||!actual.argumentHash().equals(r.argumentHash()))invalid="INTENT_MISMATCH";}
        catch(IllegalArgumentException malformed){invalid="INTENT_MISMATCH";}
        if(invalid==null&&r.policyVersion()!=policy.version())invalid="POLICY_CHANGED";
        if(invalid==null&&!r.expires().isAfter(now))invalid="APPROVAL_EXPIRED";
        if(invalid!=null){invalidate(r,invalid,seed.requester(),now);return new Gate(Result.FAIL,r.id(),invalid,false);}
        if(store.invalidated(r.organization(),r.id()))return new Gate(Result.FAIL,r.id(),"APPROVAL_INVALIDATED",false);
        if(r.state()==ApprovalState.PENDING)return new Gate(Result.WAIT,r.id(),null,false);
        var decision=store.decision(r.organization(),r.id()).orElseThrow(()->new DomainFailure(409,"APPROVAL_INVALIDATED"));
        if(!lockedActors.contains(decision.actor()))return new Gate(Result.RECHECK_ACTORS,r.id(),null,false);
        try{authority.trusted(decision.actor(),r.organization(),"approval.decide");}
        catch(DomainFailure revoked){invalidate(r,"APPROVER_REVOKED",seed.requester(),now);return new Gate(Result.FAIL,r.id(),"APPROVER_REVOKED",false);}
        if(decision.actor().equals(r.requester())||!decision.decision().equals("APPROVE"))return new Gate(Result.FAIL,r.id(),"APPROVAL_INVALIDATED",false);
        if(requireConsumed&&!store.consumed(r.organization(),r.id()))return new Gate(Result.WAIT,r.id(),null,false);
        return new Gate(Result.ALLOW,r.id(),null,false);
    }
    private OperationApprovalIntent operationIntent(OperationSeed seed,long policyVersion,String policy,Instant created,Instant expires){
        var json=tools.jackson.databind.json.JsonMapper.builder().build();
        return OperationApprovalIntent.create(seed.organization(),seed.execution(),seed.gate(),seed.action(),seed.version(),seed.definitionHash(),com.bridgeops.shared.domain.ActorReference.user(seed.requester()),seed.operation(),seed.prepared(),seed.capability(),seed.registeredOperation(),seed.classification(),seed.connection(),json.readTree(seed.arguments()),policyVersion,json.readTree(policy),seed.prompt(),created,expires,seed.deadline());
    }
    private Gate gate(Seed seed,boolean explicit,Set<UUID> lockedActors,boolean requireConsumed){
        if(!lockedActors.contains(seed.requester()))return new Gate(Result.RECHECK_ACTORS,null,null,false);
        try{authority.trusted(seed.requester(),seed.organization(),"capability.write");}
        catch(DomainFailure revoked){return new Gate(Result.FAIL,null,"CAPABILITY_PERMISSION_REVOKED",false);}
        var policy=store.policy(seed.organization());var existing=store.action(seed.organization(),seed.execution(),seed.action());Instant now=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if(existing.isEmpty()){
            if(!explicit&&!policy.required())return new Gate(Result.ALLOW,null,null,false);
            if(!seed.deadline().isAfter(now))return new Gate(Result.FAIL,null,"DEADLINE_EXCEEDED",false);
            store.ensurePolicy(seed.organization(),now);Instant expiry=now.plusSeconds(policy.lifetime());if(expiry.isAfter(seed.deadline()))expiry=seed.deadline();
            var intent=ApprovalIntent.record(seed.organization(),seed.execution(),seed.gate(),seed.action(),seed.version(),seed.definitionHash(),seed.requester(),seed.operation(),policy.version(),policy.snapshot(),seed.prompt(),seed.message(),now,expiry,seed.deadline());
            var r=store.insert(intent,seed.operationHash(),now);store.event(r,seed.requester(),"APPROVAL_REQUESTED",now);audit.write(r.organization(),seed.requester(),ApprovalAudit.Action.APPROVAL_REQUESTED,r.id(),0,now);
            return new Gate(Result.WAIT,r.id(),null,true);
        }
        var r=store.get(seed.organization(),existing.get().id(),true);
        if(r.state()!=ApprovalState.PENDING&&r.state()!=ApprovalState.APPROVED)return new Gate(Result.FAIL,r.id(),r.error()==null?"APPROVAL_"+r.state().name():r.error(),false);
        String invalid=null;
        if(!r.operationHash().equals(seed.operationHash()))invalid="INTENT_MISMATCH";
        else {
            var actual=ApprovalIntent.record(seed.organization(),seed.execution(),seed.gate(),seed.action(),seed.version(),seed.definitionHash(),seed.requester(),seed.operation(),r.policyVersion(),r.policySnapshot(),seed.prompt(),seed.message(),r.created(),r.expires(),seed.deadline());
            if(!actual.hash().equals(r.intentHash())||!actual.argumentHash().equals(r.argumentHash()))invalid="INTENT_MISMATCH";
        }
        if(invalid==null&&r.policyVersion()!=policy.version())invalid="POLICY_CHANGED";
        if(invalid==null&&!r.expires().isAfter(now))invalid="APPROVAL_EXPIRED";
        if(invalid!=null){invalidate(r,invalid,seed.requester(),now);return new Gate(Result.FAIL,r.id(),invalid,false);}
        if(store.invalidated(r.organization(),r.id()))return new Gate(Result.FAIL,r.id(),"APPROVAL_INVALIDATED",false);
        if(r.state()==ApprovalState.PENDING)return new Gate(Result.WAIT,r.id(),null,false);
        if(r.state()!=ApprovalState.APPROVED)return new Gate(Result.FAIL,r.id(),r.error()==null?"APPROVAL_"+r.state().name():r.error(),false);
        var decision=store.decision(r.organization(),r.id()).orElseThrow(()->new DomainFailure(409,"APPROVAL_INVALIDATED"));
        if(!lockedActors.contains(decision.actor()))return new Gate(Result.RECHECK_ACTORS,r.id(),null,false);
        try{authority.trusted(decision.actor(),r.organization(),"approval.decide");}
        catch(DomainFailure revoked){invalidate(r,"APPROVER_REVOKED",seed.requester(),now);return new Gate(Result.FAIL,r.id(),"APPROVER_REVOKED",false);}
        if(decision.actor().equals(r.requester())||!decision.decision().equals("APPROVE"))return new Gate(Result.FAIL,r.id(),"APPROVAL_INVALIDATED",false);
        if(requireConsumed&&!store.consumed(r.organization(),r.id()))return new Gate(Result.WAIT,r.id(),null,false);
        return new Gate(Result.ALLOW,r.id(),null,false);
    }
    private void invalidate(Request r,String code,UUID actor,Instant now){
        if(r.state()==ApprovalState.PENDING)lifecycle.finish(r,code.equals("APPROVAL_EXPIRED")?ApprovalState.EXPIRED:ApprovalState.INVALIDATED,code,actor,now);
        else if(r.state()==ApprovalState.APPROVED&&store.invalidate(r,code,now)){store.event(r,actor,"APPROVAL_INVALIDATED",now);audit.write(r.organization(),actor,ApprovalAudit.Action.APPROVAL_INVALIDATED,r.id(),r.revision(),now);}
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void reconcile(UUID org,UUID execution,UUID actor){
        var policy=store.policy(org);Instant now=clock.instant();
        for(var r:store.pending(org,execution)){
            if(!r.expires().isAfter(now))lifecycle.finish(r,ApprovalState.EXPIRED,"APPROVAL_EXPIRED",actor,now);
            else if(r.policyVersion()!=policy.version())lifecycle.finish(r,ApprovalState.INVALIDATED,"POLICY_CHANGED",actor,now);
        }
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void terminate(UUID org,UUID execution,UUID actor,String reason){
        Instant now=clock.instant();for(var r:store.pending(org,execution))lifecycle.finish(r,reason.equals("CANCELLED")?ApprovalState.CANCELLED:reason.equals("DEADLINE_EXCEEDED")?ApprovalState.EXPIRED:ApprovalState.INVALIDATED,reason,actor,now);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public List<SignalView> signals(UUID org,UUID execution){return store.signals(org,execution).stream().map(s->{var r=store.get(org,s.request(),true);return new SignalView(s.id(),s.request(),s.execution(),s.outcome(),r.gate(),r.action(),r.created());}).toList();}
    @Transactional(propagation=Propagation.MANDATORY)
    public boolean consume(UUID org,UUID execution,UUID signal,UUID actor){
        var current=store.signals(org,execution).stream().filter(s->s.id().equals(signal)).findFirst();if(current.isEmpty())return false;
        var r=store.get(org,current.get().request(),true);boolean changed=store.consume(org,current.get(),clock.instant());
        if(changed){store.event(r,actor,"APPROVAL_SIGNAL_CONSUMED",clock.instant());audit.write(org,actor,ApprovalAudit.Action.APPROVAL_SIGNAL_CONSUMED,r.id(),r.revision(),clock.instant());}return changed;
    }
}
