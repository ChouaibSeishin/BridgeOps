package com.bridgeops.organizations.application;

import com.bridgeops.audit.application.IntegrationAudit;
import com.bridgeops.organizations.persistence.AutomationStore;
import com.bridgeops.organizations.persistence.OrganizationStore;
import com.bridgeops.shared.web.DomainFailure;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Organization-owned identity/grant boundary. Automation has no human session or approval permission. */
@Service
public class AutomationAuthority {
    public record Grant(String nodeId, UUID integrationId, Long revision, String operation, String classification) {}
    public record Principal(UUID id, String name, UUID workflowId, UUID versionId, String definitionHash, String actorType,
                            String status, long revision, UUID createdBy, Instant createdAt, UUID revokedBy, Instant revokedAt, List<Grant> grants) {}
    public record Page(List<Principal> items, int page, int size, long totalElements, long totalPages) {}
    private final AutomationStore store; private final OrganizationStore organizations; private final TenantAuthorization authority; private final IntegrationAudit audit; private final Clock clock;
    public AutomationAuthority(AutomationStore store, OrganizationStore organizations, TenantAuthorization authority, IntegrationAudit audit, Clock clock) { this.store = store; this.organizations = organizations; this.authority = authority; this.audit = audit; this.clock = clock; }
    /** Called by the integration configuration service after checking the exact pinned graph/registered operations. */
    @Transactional(propagation=Propagation.MANDATORY)
    public Principal create(UUID actor, UUID family, UUID org, String name, UUID workflow, UUID version, String hash, List<Grant> grants) {
        configure(actor, family, org);
        if (name == null || name.isBlank() || name.length() > 120 || name.codePoints().anyMatch(Character::isISOControl)
            || workflow == null || version == null || hash == null || !hash.matches("[a-f0-9]{64}") || grants == null || grants.size() > 50) throw new DomainFailure(400, "INVALID_REQUEST");
        var seen = new HashSet<String>();
        for (var grant : grants) {
            if (grant == null || grant.nodeId() == null || !grant.nodeId().matches("[A-Za-z][A-Za-z0-9_-]{0,63}") || !seen.add(grant.nodeId())
                || !Set.of("READ", "WRITE").contains(grant.classification() == null ? "" : grant.classification()) || grant.operation() == null || !grant.operation().matches("[A-Za-z][A-Za-z0-9_.-]{0,63}")
                || (grant.integrationId() == null ? grant.revision() != null || !grant.operation().equals("synthetic.record") || !grant.classification().equals("WRITE") : grant.revision() == null || grant.revision() < 0)) throw new DomainFailure(422, "INVALID_AUTOMATION_SCOPE");
            if (grant.classification().equals("WRITE")) authority.write(actor, family, org, "capability.write");
        }
        if (store.count(org) >= 1000) throw new DomainFailure(429, "AUTOMATION_LIMIT");
        UUID id = store.create(actor, org, name.trim(), workflow, version, hash, clock.instant());
        grants.forEach(g -> store.grant(org, id, new AutomationStore.Grant(g.nodeId(), g.integrationId(), g.revision(), g.operation(), g.classification())));
        audit.write(org, actor, IntegrationAudit.Action.AUTOMATION_CREATED, id, 0, clock.instant()); return view(store.get(org, id, false));
    }
    @Transactional(readOnly=true,timeout=5)
    public Principal get(UUID actor, UUID family, UUID org, UUID id) { authority.read(actor, family, org, "integration.read"); return view(store.get(org, id, false)); }
    @Transactional(readOnly=true,timeout=5)
    public Page list(UUID actor, UUID family, UUID org, int page, int size) { authority.read(actor, family, org, "integration.read"); if (page < 0 || page > 100000 || size < 1 || size > 100) throw new DomainFailure(400, "INVALID_REQUEST"); long total = store.count(org); return new Page(store.list(org, size, (long) page * size).stream().map(this::view).toList(), page, size, total, (total + size - 1) / size); }
    @Transactional(timeout=10)
    public Principal revoke(UUID actor, UUID family, UUID org, UUID id, long expected) { configure(actor, family, org); var row = store.get(org, id, true); if (expected < 0) throw new DomainFailure(400, "INVALID_REQUEST"); if (row.revision() != expected || !row.status().equals("ACTIVE")) throw new DomainFailure(409, "AUTOMATION_CONFLICT"); store.revoke(row, actor, clock.instant()); audit.write(org, actor, IntegrationAudit.Action.AUTOMATION_REVOKED, id, row.revision() + 1, clock.instant()); return view(store.get(org, id, false)); }
    /** No user/session is synthesized. Holds the active tenant/principal locks through its caller's transaction. */
    @Transactional(propagation=Propagation.MANDATORY,noRollbackFor=DomainFailure.class)
    public Principal worker(UUID org, UUID id, UUID workflow, UUID version, String hash) {
        organizations.organization(org, true); var row = store.get(org, id, true);
        if (!row.status().equals("ACTIVE")) throw new DomainFailure(403, "AUTOMATION_REVOKED");
        if (!row.workflow().equals(workflow) || !row.version().equals(version) || !row.hash().equals(hash)) throw new DomainFailure(403, "AUTOMATION_SCOPE_DENIED");
        return view(row);
    }
    @Transactional(propagation=Propagation.MANDATORY,noRollbackFor=DomainFailure.class)
    public void operation(UUID org, UUID id, UUID workflow, UUID version, String hash, Grant operation) {
        var principal = worker(org, id, workflow, version, hash); if (!principal.grants().contains(operation)) throw new DomainFailure(403, "AUTOMATION_SCOPE_DENIED");
    }
    private void configure(UUID actor, UUID family, UUID org) { authority.write(actor, family, org, "integration.manage"); authority.write(actor, family, org, "execution.run"); }
    private Principal view(AutomationStore.Row row) { return new Principal(row.id(), row.name(), row.workflow(), row.version(), row.hash(), "AUTOMATION", row.status(), row.revision(), row.createdBy(), row.createdAt(), row.revokedBy(), row.revokedAt(), store.grants(row.organization(), row.id()).stream().map(g -> new Grant(g.node(), g.connection(), g.connectionRevision(), g.operation(), g.classification())).toList()); }
}
