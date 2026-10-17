package com.bridgeops.workflows.application;

import com.bridgeops.organizations.application.TenantAuthorization;
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
    public WorkflowRuntime(WorkflowStore store, GraphDefinition graphs, TenantAuthorization authority) {
        this.store=store;this.graphs=graphs;this.authority=authority;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Definition snapshot(UUID actor, UUID organization, UUID workflow, UUID version) {
        authority.worker(actor,organization);
        var row=store.version(organization,workflow,version);
        return new Definition(graphs.parse(row.definition()),row.hash());
    }
}
