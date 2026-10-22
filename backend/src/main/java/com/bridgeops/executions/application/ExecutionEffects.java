package com.bridgeops.executions.application;

import com.bridgeops.approvals.application.ApprovalRuntime;
import com.bridgeops.approvals.application.OperationApprovalIntent;
import com.bridgeops.executions.domain.*;
import com.bridgeops.executions.persistence.*;
import com.bridgeops.organizations.application.TenantAuthorization;
import com.bridgeops.shared.web.DomainFailure;
import com.bridgeops.workflows.application.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.JsonNode;

/** Verifies persisted ownership for capability adapters; no caller-provided identity/configuration is trusted. */
@Service
public class ExecutionEffects {
    public record Context(UUID organization,UUID execution,UUID actor,UUID workflow,UUID version,String hash,
        String node,String type,String configuration,String inputs,UUID logicalOperation,String blueprintHash,
        String gate,String prompt,boolean explicit,Instant deadline,Set<UUID> lockedActors,String stepStatus){
        public Context{lockedActors=Set.copyOf(lockedActors);}
        @Override public String toString(){return "ExecutionEffectContext[REDACTED]";}
    }
    public record Decision(Context context,CapabilityDispatch.Binding binding){@Override public String toString(){return "OperationDecisionContext[REDACTED]";}}
    private enum Mode{PREPARE,DISPATCH,RESUME}
    private final ExecutionStore store;private final TenantAuthorization authority;private final WorkflowRuntime workflows;
    private final WorkflowDefinitions definitions;private final ApprovalRuntime approvals;private final Clock clock;
    private final WorkflowService browserWorkflows;
    public ExecutionEffects(ExecutionStore store,TenantAuthorization authority,WorkflowRuntime workflows,WorkflowDefinitions definitions,ApprovalRuntime approvals,Clock clock,WorkflowService browserWorkflows){this.store=store;this.authority=authority;this.workflows=workflows;this.definitions=definitions;this.approvals=approvals;this.clock=clock;this.browserWorkflows=browserWorkflows;}
    @Transactional(propagation=Propagation.MANDATORY,noRollbackFor=DomainFailure.class)
    public Context prepare(CapabilityDispatch.Work work,String action){return context(work.organization(),work.execution(),work,action,work.actors(),Mode.PREPARE);}
    @Transactional(propagation=Propagation.MANDATORY,noRollbackFor=DomainFailure.class)
    public Context dispatch(CapabilityDispatch.Work work,String action){return context(work.organization(),work.execution(),work,action,work.actors(),Mode.DISPATCH);}
    @Transactional(propagation=Propagation.MANDATORY,noRollbackFor=DomainFailure.class)
    public Context resume(UUID org,UUID execution,String action,Set<UUID> actors){return context(org,execution,null,action,actors,Mode.RESUME);}
    /** Decision already locks its deciding human, tenant, execution and work; never lock the requester after the tenant. */
    @Transactional(propagation=Propagation.MANDATORY,noRollbackFor=DomainFailure.class)
    public Decision decision(UUID actor,UUID family,UUID org,UUID execution,String action,UUID prepared){
        authority.write(actor,family,org,"approval.decide");var e=store.get(org,execution,true);
        if(e.actor().equals(actor))throw new DomainFailure(403,"SELF_APPROVAL_FORBIDDEN");
        if(e.state()!=ExecutionState.WAITING_FOR_APPROVAL||!e.deadline().isAfter(clock.instant())||store.jdbc().queryForList("SELECT execution_id FROM execution_work_items WHERE organization_id=? AND execution_id=? AND state='WAITING' FOR UPDATE",org,execution).isEmpty())throw new DomainFailure(409,"EXECUTION_NOT_WAITING");
        var snapshot=browserWorkflows.version(actor,family,org,e.workflow(),e.version());if(!snapshot.version().definitionHash().equals(e.hash()))throw new DomainFailure(409,"APPROVAL_INTENT_MISMATCH");
        var plan=ExecutionPlan.runtime(snapshot.definition(),definitions);if(e.position()>=plan.size())throw new DomainFailure(409,"APPROVAL_INTENT_MISMATCH");var data=new ExecutionGraphData(store,definitions,clock);var target=data.action(snapshot.definition(),plan,e.position());if(!target.id().equals(action)||!Set.of("capabilityAction","notification").contains(target.type()))throw new DomainFailure(409,"APPROVAL_INTENT_MISMATCH");
        var step=store.steps(org,execution).stream().filter(s->s.node().equals(action)).findFirst().orElseThrow();var binding=new OperationInputStore(store.jdbc()).read(org,execution,action).orElseThrow(()->new DomainFailure(409,"RECONCILIATION_REQUIRED"));if(!binding.id().equals(prepared)||!binding.blueprintHash().equals(target.argumentHash())||!binding.logicalOperation().equals(step.operation()))throw new DomainFailure(409,"APPROVAL_INTENT_MISMATCH");
        var mapped=store.jdbc().queryForList("SELECT arguments_json FROM execution_step_inputs WHERE organization_id=? AND execution_id=? AND node_id=?",String.class,org,execution,action);if(mapped.isEmpty())throw new DomainFailure(409,"RECONCILIATION_REQUIRED");var gate=data.gate(e,snapshot.definition(),plan,e.position());
        return new Decision(new Context(org,execution,e.actor(),e.workflow(),e.version(),e.hash(),action,target.type(),definitions.stableValue(data.node(snapshot.definition(),action).path("config")),mapped.getFirst(),step.operation(),step.argumentHash(),gate.id(),gate.prompt(),gate.type().equals("approval"),e.deadline(),Set.of(actor),step.state()),new CapabilityDispatch.Binding(binding.id(),binding.capability(),binding.operation(),binding.classification(),binding.connection(),binding.arguments(),binding.argumentHash()));
    }
    @Transactional(propagation=Propagation.MANDATORY,noRollbackFor=DomainFailure.class)
    public CapabilityDispatch.Binding bind(CapabilityDispatch.Work work,String action,String capability,String operation,String classification,OperationApprovalIntent.Connection connection,String arguments){
        return bind(prepare(work,action),capability,operation,classification,connection,arguments,false);
    }
    @Transactional(propagation=Propagation.MANDATORY,noRollbackFor=DomainFailure.class)
    public CapabilityDispatch.Binding bindResume(UUID org,UUID execution,String action,Set<UUID> actors,String capability,String operation,String classification,OperationApprovalIntent.Connection connection,String arguments){
        return bind(resume(org,execution,action,actors),capability,operation,classification,connection,arguments,true);
    }
    private CapabilityDispatch.Binding bind(Context context,String capability,String operation,String classification,OperationApprovalIntent.Connection connection,String arguments,boolean resume){
        var config=definitions.parse(context.configuration());
        String name=capability+"."+operation;String expected=switch(name){case "support.list","support.get","teams.list","teams.get","teams.resolve"->"READ";case "support.assignBatch","support.addNote","notifications.create"->"WRITE";default->throw new DomainFailure(422,"UNSUPPORTED_CAPABILITY_OPERATION");};
        String declared=context.type().equals("notification")?"notifications.create":config.path("operation").asString();
        if(!Set.of("capabilityAction","notification").contains(context.type())||connection==null||!expected.equals(classification)
            ||!declared.equals(name)||!config.path("integrationId").asString().equals(connection.id().toString())||config.path("revision").asLong()!=connection.revision()||!connection.operationId().equals(operation))throw new DomainFailure(409,"OPERATION_INTENT_MISMATCH");
        var inputs=new OperationInputStore(store.jdbc());
        if(resume){var saved=inputs.read(context.organization(),context.execution(),context.node()).orElseThrow(()->new DomainFailure(409,"RECONCILIATION_REQUIRED"));
            if(!saved.arguments().equals(arguments)||!saved.capability().equals(capability)||!saved.operation().equals(operation)||!saved.classification().equals(classification)||!Objects.equals(saved.connection(),connection))throw new DomainFailure(409,"OPERATION_INTENT_MISMATCH");
            return new CapabilityDispatch.Binding(saved.id(),capability,operation,classification,connection,saved.arguments(),saved.argumentHash());}
        var saved=inputs.prepare(context.organization(),context.execution(),context.node(),context.logicalOperation(),context.blueprintHash(),capability,operation,classification,connection,definitions.parse(arguments),clock.instant());
        return new CapabilityDispatch.Binding(saved.id(),capability,operation,classification,connection,saved.arguments(),saved.argumentHash());
    }
    private Context context(UUID org,UUID execution,CapabilityDispatch.Work work,String action,Set<UUID> declaredActors,Mode mode){
        var preview=store.get(org,execution,false);var actors=new HashSet<>(approvals.actors(org,execution));actors.add(preview.actor());if(!actors.equals(declaredActors))throw new DomainFailure(409,"ACTOR_SET_CHANGED");authority.lockActors(declaredActors);
        var snapshot=workflows.snapshot(preview.actor(),org,preview.workflow(),preview.version());var e=store.get(org,execution,true);
        var currentActors=new HashSet<>(approvals.actors(org,execution));currentActors.add(e.actor());if(!currentActors.equals(declaredActors))throw new DomainFailure(409,"ACTOR_SET_CHANGED");
        if(e.state().terminal()||!e.deadline().isAfter(clock.instant())||!snapshot.hash().equals(e.hash()))throw new DomainFailure(409,"EXECUTION_NOT_DISPATCHABLE");
        if(mode==Mode.RESUME){
            if(e.state()!=ExecutionState.WAITING_FOR_APPROVAL||store.jdbc().queryForList("SELECT execution_id FROM execution_work_items WHERE organization_id=? AND execution_id=? AND state='WAITING' FOR UPDATE",org,execution).isEmpty())throw new DomainFailure(409,"EXECUTION_NOT_WAITING");
        }else if(e.state()!=ExecutionState.RUNNING||!store.guard(new ExecutionStore.Claim(org,execution,work.owner(),work.fence()),clock.instant()))throw new DomainFailure(409,"EXECUTION_OWNERSHIP_LOST");
        var plan=ExecutionPlan.runtime(snapshot.graph(),definitions);if(e.position()>=plan.size())throw new DomainFailure(409,"EXECUTION_NOT_DISPATCHABLE");
        var current=plan.get(e.position());var graphData=new ExecutionGraphData(store,definitions,clock);var target=graphData.action(snapshot.graph(),plan,e.position());
        if(!target.id().equals(action)||!Set.of("capabilityAction","notification").contains(target.type())||mode==Mode.DISPATCH&&(!current.id().equals(action)||!store.steps(org,execution).get(e.position()).state().equals("RUNNING")))throw new DomainFailure(409,"OPERATION_INTENT_MISMATCH");
        var step=store.steps(org,execution).stream().filter(s->s.node().equals(action)).findFirst().orElseThrow();if(!step.argumentHash().equals(target.argumentHash()))throw new DomainFailure(409,"OPERATION_INTENT_MISMATCH");
        var node=graphData.node(snapshot.graph(),action);JsonNode input=graphData.inputs(e,snapshot.graph(),action,current.type().equals("approval")?current.id():null);
        var gate=graphData.gate(e,snapshot.graph(),plan,e.position());boolean explicit=gate.type().equals("approval");
        return new Context(org,execution,e.actor(),e.workflow(),e.version(),e.hash(),action,target.type(),definitions.stableValue(node.path("config")),definitions.stableValue(input),step.operation(),step.argumentHash(),gate.id(),explicit?gate.prompt():"Review the exact registered operation",explicit,e.deadline(),declaredActors,step.state());
    }
}
