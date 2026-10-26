package com.bridgeops.integrations.application;

import com.bridgeops.approvals.application.*;
import com.bridgeops.executions.application.CapabilityDispatch;
import com.bridgeops.executions.application.ExecutionEffects;
import com.bridgeops.integrations.domain.InternalOperation;
import com.bridgeops.integrations.persistence.ConnectionStore;
import com.bridgeops.integrations.persistence.IntegrationWorkStore;
import com.bridgeops.organizations.application.TenantAuthorization;
import com.bridgeops.shared.web.DomainFailure;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.json.JsonMapper;

/** Registered internal operations. A supplied work reference never supplies actor, arguments or connection authority. */
@Service
public class InternalCapabilityGateway implements CapabilityDispatch,ApprovalOperationChecks {
    private final ExecutionEffects effects;private final ConnectionStore connections;private final TenantAuthorization authority;
    private final InternalBusinessReads reads;private final IntegrationWorkStore receipts;private final ApprovalRuntime approvals;private final Clock clock;
    private final BusinessPreconditions preconditions;private final InternalBusinessWrites writes;private final RestWorkflowOperations rest;
    private final JsonMapper json=JsonMapper.builder().build();
    public InternalCapabilityGateway(ExecutionEffects effects,ConnectionStore connections,TenantAuthorization authority,InternalBusinessReads reads,IntegrationWorkStore receipts,ApprovalRuntime approvals,Clock clock,BusinessPreconditions preconditions,InternalBusinessWrites writes,RestWorkflowOperations rest){this.effects=effects;this.connections=connections;this.authority=authority;this.reads=reads;this.receipts=receipts;this.approvals=approvals;this.clock=clock;this.preconditions=preconditions;this.writes=writes;this.rest=rest;}
    @Override @Transactional(propagation=Propagation.MANDATORY,noRollbackFor=DomainFailure.class)
    public Binding prepare(Work work,String action){var context=effects.prepare(work,action);return context.type().equals("configuredHttp")?rest.binding(context,work,false):binding(context,work,false);}
    @Override @Transactional(propagation=Propagation.MANDATORY,noRollbackFor=DomainFailure.class)
    public Binding resume(UUID org,UUID execution,String action,Set<UUID> actors){var context=effects.resume(org,execution,action,actors);return context.type().equals("configuredHttp")?rest.binding(context,null,true):binding(context,null,true);}
    @Override @Transactional(propagation=Propagation.MANDATORY,noRollbackFor=DomainFailure.class)
    public Result perform(Work work,String action){
        var context=effects.dispatch(work,action);if(context.type().equals("configuredHttp"))throw new DomainFailure(409,"EXTERNAL_BOUNDARY_REQUIRED");var binding=binding(context,work,false);
        var gate=approvals.check(new ApprovalRuntime.OperationSeed(context.organization(),context.execution(),context.gate(),context.node(),context.version(),context.hash(),context.requester(),context.origin(),context.logicalOperation(),context.blueprintHash(),binding.id(),binding.capability(),binding.operation(),binding.classification(),binding.connection(),binding.arguments(),context.prompt(),context.deadline()),context.explicit(),context.lockedActors());
        if(gate.result()!=ApprovalRuntime.Result.ALLOW)throw new DomainFailure(409,gate.error()==null?"OPERATION_APPROVAL_REQUIRED":gate.error());
        if(binding.classification().equals("WRITE"))return writes.perform(context,binding);
        String operation=binding.capability()+"."+binding.operation();var saved=receipts.receipt(context.organization(),context.logicalOperation(),operation,context.node(),binding.argumentHash());
        if(saved.isPresent())return new Result(saved.get(),false,null,true);
        String output=json.writeValueAsString(context.origin()==null?reads.read(context.actor(),context.organization(),binding.capability(),binding.operation(),json.readTree(binding.arguments())):reads.readRegistered(context.organization(),binding.capability(),binding.operation(),json.readTree(binding.arguments())));
        return new Result(receipts.commit(context.organization(),context.execution(),context.logicalOperation(),operation,context.node(),binding.argumentHash(),output,clock.instant()),false,null,false);
    }
    @Override @Transactional(propagation=Propagation.MANDATORY,noRollbackFor=DomainFailure.class)
    public void validateDecision(UUID actor,UUID family,UUID org,UUID execution,String action,UUID prepared){
        var decision=effects.decision(actor,family,org,execution,action,prepared);var context=decision.context();if(context.type().equals("configuredHttp")){rest.decision(decision);return;}var expected=plan(context);var binding=decision.binding();
        if(!binding.capability().equals(expected.definition().capability())||!binding.operation().equals(expected.definition().id())||!binding.classification().equals(expected.definition().classification())||!binding.arguments().equals(expected.arguments())||!Objects.equals(binding.connection(),expected.connection()))throw new DomainFailure(409,"APPROVAL_INTENT_MISMATCH");
        preconditions.check(org,context.logicalOperation(),context.node(),binding.capability(),binding.operation(),binding.argumentHash(),json.readTree(binding.arguments()));
    }
    private record Plan(InternalOperation.Definition definition,OperationApprovalIntent.Connection connection,String arguments){@Override public String toString(){return "InternalOperationPlan[REDACTED]";}}
    private Plan plan(ExecutionEffects.Context context){
        var config=json.readTree(context.configuration());String name=context.type().equals("notification")?"notifications.create":config.path("operation").asString();int dot=name.indexOf('.');
        if(dot<1)throw new DomainFailure(422,"INVALID_CAPABILITY_BINDING");String capability=name.substring(0,dot),operation=name.substring(dot+1);
        var descriptor=InternalOperation.definition(capability,operation);
        UUID id;try{id=UUID.fromString(config.path("integrationId").asString());}catch(IllegalArgumentException invalid){throw new DomainFailure(422,"INVALID_CAPABILITY_BINDING");}
        var connection=connections.get(context.organization(),id,true);
        String type=switch(capability){case "support"->"SUPPORT";case "teams"->"TEAMS";case "notifications"->"NOTIFICATIONS";default->throw new DomainFailure(422,"UNSUPPORTED_CAPABILITY_OPERATION");};
        if(!connection.type().equals(type)||connection.implementationVersion()!=1)throw new DomainFailure(422,"INVALID_CAPABILITY_BINDING");
        if(!connection.status().equals("ENABLED"))throw new DomainFailure(409,"INTEGRATION_DISABLED");
        if(connection.revision()!=config.path("revision").asLong())throw new DomainFailure(409,"STALE_CONNECTION");
        return new Plan(descriptor,new OperationApprovalIntent.Connection(id,connection.revision(),connection.implementationVersion(),operation),InternalOperation.arguments(capability,operation,json.readTree(context.inputs())));
    }
    private Binding binding(ExecutionEffects.Context context,Work work,boolean resume){
        var plan=plan(context);var descriptor=plan.definition();effects.authorize(context,plan.connection(),descriptor.capability()+"."+descriptor.id(),descriptor.classification());
        var bound=resume?effects.bindResume(context.organization(),context.execution(),context.node(),context.lockedActors(),descriptor.capability(),descriptor.id(),descriptor.classification(),plan.connection(),plan.arguments()):effects.bind(work,context.node(),descriptor.capability(),descriptor.id(),descriptor.classification(),plan.connection(),plan.arguments());
        if(resume||!context.stepStatus().equals("RUNNING")||descriptor.classification().equals("READ"))preconditions.check(context.organization(),context.logicalOperation(),context.node(),descriptor.capability(),descriptor.id(),bound.argumentHash(),json.readTree(bound.arguments()));
        return bound;
    }
}
