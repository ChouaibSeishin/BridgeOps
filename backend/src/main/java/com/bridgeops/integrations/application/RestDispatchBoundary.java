package com.bridgeops.integrations.application;

import com.bridgeops.audit.application.IntegrationAudit;
import com.bridgeops.executions.application.*;
import com.bridgeops.integrations.persistence.*;
import com.bridgeops.shared.web.DomainFailure;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Short database boundaries only. No network operation is called from this class. */
@Service
class RestDispatchBoundary {
    record Ready(ExecutionEffects.Context context,CapabilityDispatch.Binding binding,RestWorkflowOperations.Plan plan,RestDispatchStore.Dispatch dispatch,ExternalDispatch.Completion immediate){@Override public String toString(){return "ReadyExternalDispatch[REDACTED]";}}
    private final ExecutionEffects effects;private final RestWorkflowOperations operations;private final RestDispatchStore dispatches;private final IntegrationWorkStore receipts;private final IntegrationAudit audit;private final JdbcTemplate jdbc;private final Clock clock;
    RestDispatchBoundary(ExecutionEffects effects,RestWorkflowOperations operations,RestDispatchStore dispatches,IntegrationWorkStore receipts,IntegrationAudit audit,JdbcTemplate jdbc,Clock clock){this.effects=effects;this.operations=operations;this.dispatches=dispatches;this.receipts=receipts;this.audit=audit;this.jdbc=jdbc;this.clock=clock;}
    @Transactional(timeout=10)
    public Ready inspect(CapabilityDispatch.Work work,String action){return current(work,action);}
    private Ready current(CapabilityDispatch.Work work,String action){
        var context=effects.dispatch(work,action);var binding=operations.binding(context,work,false);var plan=operations.plan(context);operations.matches(plan,binding);operations.approved(context,binding);
        var previous=dispatches.latest(context.organization(),context.logicalOperation());var receipt=receipts.receipt(context.organization(),context.logicalOperation(),"rest."+binding.operation(),context.node(),binding.argumentHash());
        ExternalDispatch.Completion immediate=null;
        if(receipt.isPresent()){
            var outcome=previous.flatMap(d->dispatches.outcome(context.organization(),d.id()));
            if(outcome.isEmpty()||!outcome.get().outcome().equals("SUCCEEDED")||!hash(receipt.get()).equals(outcome.get().resultHash()))immediate=unknown();
            else immediate=new ExternalDispatch.Completion(receipt.get(),null,null,true,null);
        }else if(previous.isPresent()){
            var prior=previous.get();var outcome=dispatches.outcome(context.organization(),prior.id());
            if(prior.attempt()>=context.attempt()||binding.classification().equals("WRITE")||outcome.isPresent()&&!outcome.get().outcome().equals("TRANSIENT_FAILURE"))immediate=unknown();
        }
        return new Ready(context,binding,plan,null,immediate);
    }
    @Transactional(timeout=10)
    public Ready begin(CapabilityDispatch.Work work,String action,UUID slot){
        var ready=current(work,action);if(ready.immediate()!=null)return ready;var context=ready.context();var binding=ready.binding();var now=clock.instant().truncatedTo(ChronoUnit.MICROS);
        if(jdbc.queryForList("SELECT id FROM integration_outbound_slots WHERE organization_id=? AND id=? AND connection_id=? AND released_at IS NULL AND lease_expires_at>? FOR UPDATE",context.organization(),slot,binding.connection().id(),java.sql.Timestamp.from(now)).isEmpty())throw new DomainFailure(429,"OUTBOUND_CAPACITY");
        var dispatch=new RestDispatchStore.Dispatch(UUID.randomUUID(),context.organization(),context.execution(),context.node(),context.logicalOperation(),context.attempt(),work.owner(),work.fence(),binding.connection().id(),binding.connection().revision(),binding.connection().implementationVersion(),binding.operation(),binding.classification(),binding.argumentHash(),context.blueprintHash(),now,context.deadline());
        dispatches.begin(dispatch);audit.write(context.organization(),context.requester(),IntegrationAudit.Action.OUTBOUND_DISPATCHED,dispatch.id(),context.attempt(),now);
        return new Ready(context,binding,ready.plan(),dispatch,null);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public ExternalDispatch.Completion record(CapabilityDispatch.Work work,String action,Ready sent,ExternalDispatch.Completion observed){
        var context=effects.dispatch(work,action);var binding=operations.binding(context,work,false);operations.approved(context,binding);
        if(!context.logicalOperation().equals(sent.context().logicalOperation())||!binding.equals(sent.binding()))throw new DomainFailure(409,"OPERATION_INTENT_MISMATCH");
        if(sent.dispatch()==null){
            if(observed.reused()){var latest=current(work,action).immediate();if(latest==null||!latest.reused()||!Objects.equals(latest.output(),observed.output()))throw new DomainFailure(409,"RECONCILIATION_REQUIRED");return latest;}
            if(observed.failure()==null)throw new DomainFailure(409,"RECONCILIATION_REQUIRED");return observed;
        }
        var d=dispatches.get(context.organization(),sent.dispatch().id());
        if(!d.equals(sent.dispatch())||!d.owner().equals(work.owner())||d.fence()!=work.fence()||d.attempt()!=context.attempt()||dispatches.outcome(context.organization(),d.id()).isPresent())throw new DomainFailure(409,"EXECUTION_OWNERSHIP_LOST");
        if(receipts.receipt(context.organization(),context.logicalOperation(),"rest."+binding.operation(),context.node(),binding.argumentHash()).isPresent())throw new DomainFailure(409,"RECONCILIATION_REQUIRED");
        String outcome=observed.failure()==null?"SUCCEEDED":switch(observed.failure()){case TRANSIENT->"TRANSIENT_FAILURE";case PERMANENT->"PERMANENT_FAILURE";case AMBIGUOUS->"RECONCILIATION_REQUIRED";};
        dispatches.finish(d,new RestDispatchStore.Outcome(outcome,observed.failure()==null?null:observed.code(),observed.failure()==null?hash(observed.output()):null,clock.instant()));
        if(observed.failure()==null){String saved=receipts.commit(context.organization(),context.execution(),context.logicalOperation(),"rest."+binding.operation(),context.node(),binding.argumentHash(),observed.output(),clock.instant());if(!saved.equals(observed.output()))throw new IllegalStateException("External receipt requires reconciliation.");}
        audit.write(context.organization(),context.requester(),IntegrationAudit.Action.OUTBOUND_RESULT_RECORDED,d.id(),d.attempt(),clock.instant());return observed;
    }
    private ExternalDispatch.Completion unknown(){return new ExternalDispatch.Completion(null,SyntheticCapability.FailureKind.AMBIGUOUS,"RECONCILIATION_REQUIRED",false,null);}
    private String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 unavailable.");}}
}
