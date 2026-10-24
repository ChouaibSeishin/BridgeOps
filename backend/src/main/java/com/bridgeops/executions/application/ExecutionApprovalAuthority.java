package com.bridgeops.executions.application;

import com.bridgeops.approvals.application.*;
import com.bridgeops.executions.domain.ExecutionPlan;
import com.bridgeops.executions.persistence.ExecutionStore;
import com.bridgeops.shared.domain.*;
import com.bridgeops.shared.web.DomainFailure;
import com.bridgeops.workflows.application.WorkflowDefinitions;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class ExecutionApprovalAuthority implements ApprovalRequesterAuthority {
    private final ExecutionStore store;private final ExecutionActors actors;private final WorkflowDefinitions definitions;private final Clock clock;
    public ExecutionApprovalAuthority(ExecutionStore store,ExecutionActors actors,WorkflowDefinitions definitions,Clock clock){this.store=store;this.actors=actors;this.definitions=definitions;this.clock=clock;}
    @Override @Transactional(propagation=Propagation.MANDATORY,noRollbackFor=DomainFailure.class)
    public void check(UUID org,UUID execution,ActorReference actor,AutomationOrigin origin,String action,OperationApprovalIntent.Connection connection,String operation,String classification){
        var preview=store.get(org,execution,false);
        if(actor.type()!=ActorReference.Type.AUTOMATION||!actor.equals(preview.requester())||!Objects.equals(origin,preview.automation()))throw new DomainFailure(409,"APPROVAL_INTENT_MISMATCH");
        var snapshot=actors.snapshot(preview);var e=store.get(org,execution,true);
        if(e.state().terminal()||!e.deadline().isAfter(clock.instant()))throw new DomainFailure(409,"EXECUTION_NOT_DISPATCHABLE");
        var plan=ExecutionPlan.runtime(snapshot.graph(),definitions);if(e.position()>=plan.size()||!new ExecutionGraphData(store,definitions,clock).action(snapshot.graph(),plan,e.position()).id().equals(action))throw new DomainFailure(409,"APPROVAL_INTENT_MISMATCH");
        actors.operation(e,action,connection==null?null:connection.id(),connection==null?null:connection.revision(),operation,classification);
    }
}
