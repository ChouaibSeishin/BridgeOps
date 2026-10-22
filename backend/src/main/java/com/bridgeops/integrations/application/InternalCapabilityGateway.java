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

/** Internal registered reads only. A supplied work reference never supplies actor, arguments or connection authority. */
@Service
public class InternalCapabilityGateway implements CapabilityDispatch {
    private final ExecutionEffects effects;private final ConnectionStore connections;private final TenantAuthorization authority;
    private final InternalBusinessReads reads;private final IntegrationWorkStore receipts;private final ApprovalRuntime approvals;private final Clock clock;
    private final JsonMapper json=JsonMapper.builder().build();
    public InternalCapabilityGateway(ExecutionEffects effects,ConnectionStore connections,TenantAuthorization authority,InternalBusinessReads reads,IntegrationWorkStore receipts,ApprovalRuntime approvals,Clock clock){this.effects=effects;this.connections=connections;this.authority=authority;this.reads=reads;this.receipts=receipts;this.approvals=approvals;this.clock=clock;}
    @Override @Transactional(propagation=Propagation.MANDATORY,noRollbackFor=DomainFailure.class)
    public Binding prepare(Work work,String action){var context=effects.prepare(work,action);return binding(context,work,false);}
    @Override @Transactional(propagation=Propagation.MANDATORY,noRollbackFor=DomainFailure.class)
    public Binding resume(UUID org,UUID execution,String action,Set<UUID> actors){var context=effects.resume(org,execution,action,actors);return binding(context,null,true);}
    @Override @Transactional(propagation=Propagation.MANDATORY,noRollbackFor=DomainFailure.class)
    public Result perform(Work work,String action){
        var context=effects.dispatch(work,action);var binding=binding(context,work,false);
        var gate=approvals.check(new ApprovalRuntime.OperationSeed(context.organization(),context.execution(),context.gate(),context.node(),context.version(),context.hash(),context.actor(),context.logicalOperation(),context.blueprintHash(),binding.id(),binding.capability(),binding.operation(),binding.classification(),binding.connection(),binding.arguments(),context.prompt(),context.deadline()),context.explicit(),context.lockedActors());
        if(gate.result()!=ApprovalRuntime.Result.ALLOW)throw new DomainFailure(409,gate.error()==null?"OPERATION_APPROVAL_REQUIRED":gate.error());
        String operation=binding.capability()+"."+binding.operation();var saved=receipts.receipt(context.organization(),context.logicalOperation(),operation,context.node(),binding.argumentHash());
        if(saved.isPresent())return new Result(saved.get(),false,null,true);
        String output=json.writeValueAsString(reads.read(context.actor(),context.organization(),binding.capability(),binding.operation(),json.readTree(binding.arguments())));
        return new Result(receipts.commit(context.organization(),context.execution(),context.logicalOperation(),operation,context.node(),binding.argumentHash(),output,clock.instant()),false,null,false);
    }
    private Binding binding(ExecutionEffects.Context context,Work work,boolean resume){
        var config=json.readTree(context.configuration());String name=config.path("operation").asString();int dot=name.indexOf('.');
        if(dot<1)throw new DomainFailure(422,"INVALID_CAPABILITY_BINDING");String capability=name.substring(0,dot),operation=name.substring(dot+1);
        var descriptor=InternalOperation.definition(capability,operation);if(!descriptor.classification().equals("READ"))throw new DomainFailure(422,"UNSUPPORTED_CAPABILITY_OPERATION");
        authority.trusted(context.actor(),context.organization(),"capability.read");
        UUID id;try{id=UUID.fromString(config.path("integrationId").asString());}catch(IllegalArgumentException invalid){throw new DomainFailure(422,"INVALID_CAPABILITY_BINDING");}
        var connection=connections.get(context.organization(),id,true);
        if(!connection.type().equals(capability.equals("support")?"SUPPORT":"TEAMS")||connection.implementationVersion()!=1)throw new DomainFailure(422,"INVALID_CAPABILITY_BINDING");
        if(!connection.status().equals("ENABLED"))throw new DomainFailure(409,"INTEGRATION_DISABLED");
        if(connection.revision()!=config.path("revision").asLong())throw new DomainFailure(409,"STALE_CONNECTION");
        String arguments=InternalOperation.arguments(capability,operation,json.readTree(context.inputs()));var reference=new OperationApprovalIntent.Connection(id,connection.revision(),connection.implementationVersion(),operation);
        return resume?effects.bindResume(context.organization(),context.execution(),context.node(),context.lockedActors(),capability,operation,descriptor.classification(),reference,arguments):effects.bind(work,context.node(),capability,operation,descriptor.classification(),reference,arguments);
    }
}
