package com.bridgeops.workflows.application;

import com.bridgeops.organizations.application.TenantAuthorization;
import com.bridgeops.organizations.application.AutomationAuthority;
import com.bridgeops.shared.web.DomainFailure;
import com.bridgeops.workflows.persistence.WorkflowStore;
import com.bridgeops.workflows.domain.GraphDefinition;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.JsonNode;

/** Immutable snapshot projection for trusted workers, with live actor/tenant checks. */
@Service
public class WorkflowRuntime {
    public record Definition(JsonNode graph, String hash) {}
    private final WorkflowStore store;
    private final GraphDefinition graphs;
    private final TenantAuthorization authority;
    private final AutomationAuthority automations;
    public WorkflowRuntime(WorkflowStore store, GraphDefinition graphs, TenantAuthorization authority) {
        this(store,graphs,authority,null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public WorkflowRuntime(WorkflowStore store,GraphDefinition graphs,TenantAuthorization authority,AutomationAuthority automations){
        this.store=store;this.graphs=graphs;this.authority=authority;this.automations=automations;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Definition snapshot(UUID actor, UUID organization, UUID workflow, UUID version) {
        authority.worker(actor,organization);
        var row=store.version(organization,workflow,version);
        return new Definition(graphs.parse(row.definition()),row.hash());
    }
    @Transactional(propagation=Propagation.MANDATORY,noRollbackFor=DomainFailure.class)
    public Definition automationSnapshot(UUID organization,UUID principal,UUID workflow,UUID version,String hash){
        if(automations==null)throw new DomainFailure(403,"AUTOMATION_SCOPE_DENIED");automations.worker(organization,principal,workflow,version,hash);
        if(!store.get(organization,workflow,false).status().equals("ACTIVE"))throw new DomainFailure(409,"WORKFLOW_ARCHIVED");var row=store.version(organization,workflow,version);if(!row.hash().equals(hash))throw new DomainFailure(409,"OPERATION_INTENT_MISMATCH");return new Definition(graphs.parse(row.definition()),row.hash());
    }
}
