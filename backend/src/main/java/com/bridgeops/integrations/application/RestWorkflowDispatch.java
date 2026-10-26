package com.bridgeops.integrations.application;

import com.bridgeops.executions.application.*;
import com.bridgeops.integrations.infrastructure.*;
import com.bridgeops.shared.web.DomainFailure;
import com.bridgeops.workflows.application.WorkflowDefinitions;
import java.time.Duration;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.json.JsonMapper;

/** Deliberately outside every database transaction while resolving credentials and contacting the provider. */
@Service
public class RestWorkflowDispatch implements ExternalDispatch {
    private record Seen(CapabilityDispatch.Work work,String action,RestDispatchBoundary.Ready ready,Completion completion) implements Observation { @Override public String toString(){return "ExternalObservation[REDACTED]";} }
    private final RestDispatchBoundary boundary;private final OutboundAdmission admission;private final SecretStore secrets;private final RestTransport transport;private final WorkflowDefinitions definitions;
    private final JsonMapper json=JsonMapper.builder().build();
    RestWorkflowDispatch(RestDispatchBoundary boundary,OutboundAdmission admission,SecretStore secrets,RestTransport transport,WorkflowDefinitions definitions){this.boundary=boundary;this.admission=admission;this.secrets=secrets;this.transport=transport;this.definitions=definitions;}
    @Override @Transactional(propagation=Propagation.NEVER)
    public Observation execute(CapabilityDispatch.Work work,String action){
        var ready=boundary.inspect(work,action);if(ready.immediate()!=null)return new Seen(work,action,ready,ready.immediate());
        UUID slot;try{slot=admission.acquire(work.organization(),ready.binding().connection().id());}catch(DomainFailure busy){if(busy.status()!=429)throw busy;return new Seen(work,action,ready,new Completion(null,SyntheticCapability.FailureKind.TRANSIENT,busy.code(),false,Duration.ofSeconds(60)));}
        byte[] credential=null;
        try{
            ready=boundary.begin(work,action,slot);if(ready.immediate()!=null)return new Seen(work,action,ready,ready.immediate());
            if(ready.plan().credential()!=null)credential=secrets.resolve(work.organization(),ready.plan().credential(),"REST_CREDENTIAL");
            try{
                var response=transport.call(ready.plan().base(),ready.plan().operation(),json.readTree(ready.plan().arguments()),credential,ready.context().logicalOperation());var output=json.createObjectNode();output.set("response",response);
                return new Seen(work,action,ready,new Completion(definitions.stableValue(output),null,null,false,null));
            }catch(RestFailure failure){var kind=switch(failure.kind()){case TRANSIENT->SyntheticCapability.FailureKind.TRANSIENT;case PERMANENT->SyntheticCapability.FailureKind.PERMANENT;case AMBIGUOUS->SyntheticCapability.FailureKind.AMBIGUOUS;};return new Seen(work,action,ready,new Completion(null,kind,kind==SyntheticCapability.FailureKind.AMBIGUOUS?"RECONCILIATION_REQUIRED":failure.code(),false,failure.retryAfter()));}
        }finally{if(credential!=null)Arrays.fill(credential,(byte)0);try{admission.release(work.organization(),slot);}catch(DataAccessException|TransactionException unavailable){/* Expiry recovers this bounded slot. */}}
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public Completion finish(CapabilityDispatch.Work work,String action,Observation observation){
        if(!(observation instanceof Seen seen)||!seen.work().equals(work)||!seen.action().equals(action))throw new DomainFailure(409,"OPERATION_INTENT_MISMATCH");
        return boundary.record(work,action,seen.ready(),seen.completion());
    }
}
