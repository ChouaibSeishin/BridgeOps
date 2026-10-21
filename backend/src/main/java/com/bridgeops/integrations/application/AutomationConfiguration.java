package com.bridgeops.integrations.application;

import com.bridgeops.organizations.application.AutomationAuthority;
import com.bridgeops.organizations.application.TenantAuthorization;
import com.bridgeops.shared.web.DomainFailure;
import com.bridgeops.workflows.application.WorkflowService;
import com.bridgeops.workflows.application.WorkflowDefinitions;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves a human-authorized immutable graph into minimal, non-wildcard operation grants. */
@Service
public class AutomationConfiguration {
    private final AutomationAuthority automations; private final TenantAuthorization authority; private final WorkflowService workflows; private final WorkflowDefinitions definitions; private final ConnectionService connections;
    public AutomationConfiguration(AutomationAuthority automations, TenantAuthorization authority, WorkflowService workflows, WorkflowDefinitions definitions, ConnectionService connections) { this.automations = automations; this.authority = authority; this.workflows = workflows; this.definitions = definitions; this.connections = connections; }
    @Transactional(timeout=10)
    public AutomationAuthority.Principal create(UUID actor, UUID family, UUID org, String name, UUID workflow, UUID version) {
        authority.write(actor, family, org, "integration.manage"); authority.write(actor, family, org, "execution.run");
        if (workflow == null || version == null) throw new DomainFailure(400, "INVALID_REQUEST");
        var draft = workflows.get(actor, family, org, workflow); if (!draft.workflow().status().equals("ACTIVE")) throw new DomainFailure(409, "WORKFLOW_ARCHIVED");
        var snapshot = workflows.version(actor, family, org, workflow, version); var graph = snapshot.definition();
        if (snapshot.version().schemaVersion() != 2 || !definitions.valid(graph)) throw new DomainFailure(422, "INCOMPATIBLE_AUTOMATION_WORKFLOW");
        boolean webhook = false; var grants = new ArrayList<AutomationAuthority.Grant>();
        for (var node : graph.path("nodes")) {
            String type = node.path("type").asString(); String id = node.path("id").asString(); var config = node.path("config");
            if (type.equals("manualTrigger")) throw new DomainFailure(422, "INCOMPATIBLE_AUTOMATION_WORKFLOW");
            if (type.equals("webhookTrigger")) webhook = true;
            if (type.equals("syntheticAction")) grants.add(new AutomationAuthority.Grant(id, null, null, "synthetic.record", "WRITE"));
            else if (Set.of("capabilityAction", "configuredHttp", "notification").contains(type)) {
                UUID integration = UUID.fromString(config.path("integrationId").asString()); long revision = config.path("revision").asLong();
                var connection = connections.get(actor, family, org, integration);
                if (!connection.enabled()) throw new DomainFailure(409, "INTEGRATION_DISABLED");
                if (connection.revision() != revision) throw new DomainFailure(409, "STALE_CONNECTION");
                String operation = type.equals("notification") ? "create" : config.path("operation").asString();
                String registered = operation;
                if (type.equals("capabilityAction")) {
                    String prefix = operation.startsWith("support.") ? "support." : "teams.";
                    if (!connection.type().equals(prefix.equals("support.") ? "SUPPORT" : "TEAMS")) throw new DomainFailure(422, "INVALID_CAPABILITY_BINDING");
                    registered = operation.substring(prefix.length());
                } else if (!connection.type().equals(type.equals("notification") ? "NOTIFICATIONS" : "REST")) throw new DomainFailure(422, "INVALID_CAPABILITY_BINDING");
                String selected = registered;
                var descriptor = connections.operations(actor, family, org, integration).stream().filter(o -> o.id().equals(selected)).findFirst().orElseThrow(() -> new DomainFailure(422, "UNSUPPORTED_CAPABILITY_OPERATION"));
                grants.add(new AutomationAuthority.Grant(id, integration, revision, type.equals("notification") ? "notifications.create" : operation, descriptor.classification()));
            }
        }
        if (!webhook) throw new DomainFailure(422, "INCOMPATIBLE_AUTOMATION_WORKFLOW");
        return automations.create(actor, family, org, name, workflow, version, snapshot.version().definitionHash(), grants);
    }
}
