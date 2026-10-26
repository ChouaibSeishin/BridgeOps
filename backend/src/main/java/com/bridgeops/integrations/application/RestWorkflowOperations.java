package com.bridgeops.integrations.application;

import com.bridgeops.approvals.application.*;
import com.bridgeops.executions.application.*;
import com.bridgeops.integrations.domain.RestOperation;
import com.bridgeops.integrations.persistence.ConnectionStore;
import com.bridgeops.shared.web.DomainFailure;
import com.bridgeops.workflows.application.WorkflowDefinitions;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Registered REST bindings only. This service does not perform network I/O. */
@Service
class RestWorkflowOperations {
    record Plan(String base,RestOperation operation,UUID credential,OperationApprovalIntent.Connection connection,String arguments){
        @Override public String toString(){return "RestWorkflowPlan[REDACTED]";}
    }
    private final ExecutionEffects effects;private final ConnectionStore connections;private final WorkflowDefinitions definitions;private final ApprovalRuntime approvals;
    private final JsonMapper json=JsonMapper.builder().build();
    RestWorkflowOperations(ExecutionEffects effects,ConnectionStore connections,WorkflowDefinitions definitions,ApprovalRuntime approvals){this.effects=effects;this.connections=connections;this.definitions=definitions;this.approvals=approvals;}
    Plan plan(ExecutionEffects.Context context){
        if(!context.type().equals("configuredHttp"))throw new DomainFailure(409,"OPERATION_INTENT_MISMATCH");
        var config=json.readTree(context.configuration());UUID id;try{id=UUID.fromString(config.path("integrationId").asString());}catch(IllegalArgumentException invalid){throw new DomainFailure(422,"INVALID_CAPABILITY_BINDING");}
        var row=connections.get(context.organization(),id,true);
        if(!row.type().equals("REST")||row.implementationVersion()!=1)throw new DomainFailure(422,"INVALID_CAPABILITY_BINDING");
        if(!row.status().equals("ENABLED"))throw new DomainFailure(409,"INTEGRATION_DISABLED");if(row.revision()!=config.path("revision").asLong())throw new DomainFailure(409,"STALE_CONNECTION");
        var configuration=json.readTree(row.configuration());RestOperation operation=null;
        for(var value:configuration.path("operations")){var candidate=RestOperation.parse(value);if(candidate.id().equals(config.path("operation").asString())){if(operation!=null)throw new DomainFailure(422,"INVALID_REST_OPERATION");operation=candidate;}}
        if(operation==null)throw new DomainFailure(422,"UNSUPPORTED_CAPABILITY_OPERATION");var arguments=json.readTree(context.inputs());operation.validateArguments(arguments);
        return new Plan(configuration.path("baseDestination").asString(),operation,row.credential(),new OperationApprovalIntent.Connection(id,row.revision(),row.implementationVersion(),operation.id()),definitions.stableValue(arguments));
    }
    CapabilityDispatch.Binding binding(ExecutionEffects.Context context,CapabilityDispatch.Work work,boolean resume){
        var plan=plan(context);effects.authorize(context,plan.connection(),plan.operation().id(),plan.operation().classification());
        return resume?effects.bindResume(context.organization(),context.execution(),context.node(),context.lockedActors(),"rest",plan.operation().id(),plan.operation().classification(),plan.connection(),plan.arguments())
            :effects.bind(work,context.node(),"rest",plan.operation().id(),plan.operation().classification(),plan.connection(),plan.arguments());
    }
    void decision(ExecutionEffects.Decision decision){matches(plan(decision.context()),decision.binding());}
    void matches(Plan plan,CapabilityDispatch.Binding binding){if(!binding.capability().equals("rest")||!binding.operation().equals(plan.operation().id())||!binding.classification().equals(plan.operation().classification())||!binding.connection().equals(plan.connection())||!binding.arguments().equals(plan.arguments()))throw new DomainFailure(409,"APPROVAL_INTENT_MISMATCH");}
    void approved(ExecutionEffects.Context context,CapabilityDispatch.Binding binding){
        var gate=approvals.check(new ApprovalRuntime.OperationSeed(context.organization(),context.execution(),context.gate(),context.node(),context.version(),context.hash(),context.requester(),context.origin(),context.logicalOperation(),context.blueprintHash(),binding.id(),"rest",binding.operation(),binding.classification(),binding.connection(),binding.arguments(),context.prompt(),context.deadline()),context.explicit(),context.lockedActors());
        if(gate.result()!=ApprovalRuntime.Result.ALLOW)throw new DomainFailure(409,gate.error()==null?"OPERATION_APPROVAL_REQUIRED":gate.error());
    }
}
