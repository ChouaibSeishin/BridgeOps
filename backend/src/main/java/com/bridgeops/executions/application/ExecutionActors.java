package com.bridgeops.executions.application;

import com.bridgeops.executions.persistence.ExecutionStore.Execution;
import com.bridgeops.organizations.application.TenantAuthorization;
import com.bridgeops.workflows.application.WorkflowRuntime;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** The runtime never converts an automation principal/configurer into a user identity. */
@Service
class ExecutionActors {
    private final TenantAuthorization humans;private final ExecutionAutomation automations;private final WorkflowRuntime workflows;
    ExecutionActors(TenantAuthorization humans,ExecutionAutomation automations,WorkflowRuntime workflows){this.humans=humans;this.automations=automations;this.workflows=workflows;}
    WorkflowRuntime.Definition snapshot(Execution e){
        if(e.automation()==null)return workflows.snapshot(e.actor(),e.organization(),e.workflow(),e.version());
        worker(e);return workflows.automationSnapshot(e.organization(),e.automation().principal(),e.workflow(),e.version(),e.hash());
    }
    void worker(Execution e){if(e.automation()==null)humans.trusted(e.actor(),e.organization(),"execution.run");else automations.authorize(e.organization(),e.automation(),e.workflow(),e.version(),e.hash());}
    void durable(Execution e){if(e.automation()==null)humans.lockDurableContext(e.actor(),e.organization());else humans.lockDurableOrganization(e.organization());}
    void operation(Execution e,String node,UUID connection,Long revision,String operation,String classification){
        if(e.automation()==null)humans.trusted(e.actor(),e.organization(),classification.equals("WRITE")?"capability.write":"capability.read");
        else automations.operation(e.organization(),e.automation(),e.workflow(),e.version(),e.hash(),node,connection,revision,operation,classification);
    }
}
