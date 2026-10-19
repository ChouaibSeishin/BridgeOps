package com.bridgeops.workflows.application;

import com.bridgeops.audit.application.WorkflowAudit;
import com.bridgeops.audit.application.WorkflowAudit.Action;
import com.bridgeops.organizations.application.TenantAuthorization;
import com.bridgeops.shared.web.DomainFailure;
import com.bridgeops.workflows.domain.GraphDefinition;
import com.bridgeops.workflows.persistence.WorkflowStore;
import com.bridgeops.workflows.application.WorkflowViews.*;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
public class WorkflowService {
    private final TenantAuthorization authority; private final WorkflowStore store;
    private final GraphDefinition graphs; private final WorkflowAudit audit; private final Clock clock;
    public WorkflowService(TenantAuthorization authority, WorkflowStore store, GraphDefinition graphs, WorkflowAudit audit, Clock clock) {
        this.authority = authority; this.store = store; this.graphs = graphs; this.audit = audit; this.clock = clock;
    }
    @Transactional(timeout = 10)
    public Detail create(UUID user, UUID family, UUID organization, String name, String description, JsonNode definition) {
        authority.write(user, family, organization, "workflow.edit"); metadata(name, description);
        UUID id = store.create(organization, user, name.trim(), description, graphs.bounded(definition), clock.instant());
        audit.write(organization, user, Action.WORKFLOW_CREATED, id, 0, null, null, clock.instant());
        return detail(store.get(organization, id, false));
    }
    @Transactional(readOnly = true, timeout = 5)
    public Page<Summary> list(UUID user, UUID family, UUID organization, int page, int size) {
        authority.read(user, family, organization, "workflow.read"); bounds(page, size);
        return page(store.list(organization, page, size).stream().map(this::summary).toList(), page, size, store.count(organization));
    }
    @Transactional(readOnly = true, timeout = 5)
    public Detail get(UUID user, UUID family, UUID organization, UUID id) {
        authority.read(user, family, organization, "workflow.read"); return detail(store.get(organization, id, false));
    }
    @Transactional(timeout = 10)
    public Detail save(UUID user, UUID family, UUID organization, UUID id, long expected, String name, String description, JsonNode definition) {
        authority.write(user, family, organization, "workflow.edit"); var row = store.get(organization, id, true); revision(row, expected); active(row); metadata(name, description);
        store.save(row, user, name.trim(), description, graphs.bounded(definition), clock.instant());
        audit.write(organization, user, Action.DRAFT_SAVED, id, expected + 1, null, null, clock.instant()); return detail(store.get(organization, id, false));
    }
    @Transactional(readOnly = true, timeout = 5)
    public GraphDefinition.Validation validate(UUID user, UUID family, UUID organization, UUID id, long expected) {
        authority.read(user, family, organization, "workflow.read"); var row = store.get(organization, id, false); revision(row, expected);
        return graphs.validate(graphs.parse(row.definition()));
    }
    @Transactional(timeout = 10)
    public Snapshot publish(UUID user, UUID family, UUID organization, UUID id, long expected) {
        authority.write(user, family, organization, "workflow.publish"); var row = store.get(organization, id, true); revision(row, expected); active(row);
        var definition = graphs.parse(row.definition()); var validation = graphs.validate(definition); if (!validation.valid()) throw new InvalidDefinition(validation);
        var canonical = graphs.canonical(definition); UUID version = store.publish(row, user, canonical.json(), canonical.hash(), clock.instant());
        store.point(row, version, clock.instant()); store.advance(row, user, row.definition(), clock.instant());
        audit.write(organization, user, Action.WORKFLOW_PUBLISHED, id, expected, version, canonical.hash(), clock.instant());
        return snapshot(store.version(organization, id, version));
    }
    @Transactional(timeout = 10)
    public void archive(UUID user, UUID family, UUID organization, UUID id, long expected) {
        authority.write(user, family, organization, "workflow.edit"); var row = store.get(organization, id, true); revision(row, expected); active(row);
        store.archive(row, clock.instant()); store.advance(row, user, row.definition(), clock.instant());
        audit.write(organization, user, Action.WORKFLOW_ARCHIVED, id, expected + 1, null, null, clock.instant());
    }
    @Transactional(timeout = 10)
    public Detail duplicate(UUID user, UUID family, UUID organization, UUID id, long expected) {
        authority.write(user, family, organization, "workflow.edit"); var row = store.get(organization, id, true); revision(row, expected);
        String name = "Copy of " + row.name(); if (name.length() > 120) name = name.substring(0, 120); if (Character.isHighSurrogate(name.charAt(name.length() - 1))) name = name.substring(0, name.length() - 1);
        UUID copy = store.create(organization, user, name, row.description(), row.definition(), clock.instant());
        audit.write(organization, user, Action.WORKFLOW_DUPLICATED, copy, 0, null, null, clock.instant()); return detail(store.get(organization, copy, false));
    }
    @Transactional(readOnly = true, timeout = 5)
    public Page<VersionSummary> versions(UUID user, UUID family, UUID organization, UUID id, int page, int size) {
        authority.read(user, family, organization, "workflow.read"); bounds(page, size); store.get(organization, id, false);
        return page(store.versions(organization, id, page, size).stream().map(this::version).toList(), page, size, store.versionCount(organization, id));
    }
    @Transactional(readOnly = true, timeout = 5)
    public Snapshot version(UUID user, UUID family, UUID organization, UUID id, UUID versionId) {
        authority.read(user, family, organization, "workflow.read"); store.get(organization, id, false); return snapshot(store.version(organization, id, versionId));
    }
    /** Authorized bounded source metadata without returning the definition graph. */
    @Transactional(readOnly=true,timeout=5)
    public VersionSummary reference(UUID user,UUID family,UUID organization,UUID id,UUID versionId) {
        authority.read(user,family,organization,"workflow.read");return version(store.version(organization,id,versionId));
    }
    private Summary summary(WorkflowStore.Row row) { return new Summary(row.id(), row.name(), row.description(), row.status(), row.revision(), row.currentVersion(), row.updatedAt()); }
    private Detail detail(WorkflowStore.Row row) { return new Detail(summary(row), graphs.parse(row.definition()), row.currentVersion() == null ? null : version(store.version(row.organizationId(), row.id(), row.currentVersion()))); }
    private VersionSummary version(WorkflowStore.Version row) { return new VersionSummary(row.id(), row.workflowId(), row.number(), row.sourceRevision(), row.schemaVersion(), row.canonicalizationVersion(), row.name(), row.description(), row.hash(), row.actor(), row.publishedAt()); }
    private Snapshot snapshot(WorkflowStore.Version row) { return new Snapshot(version(row), graphs.parse(row.definition())); }
    private void metadata(String name, String description) {
        if (name == null || name.isBlank() || name.length() > 120 || name.codePoints().anyMatch(Character::isISOControl) || description == null || description.length() > 2000)
            throw new DomainFailure(400, "INVALID_REQUEST");
    }
    private void active(WorkflowStore.Row row) { if (!row.status().equals("ACTIVE")) throw new DomainFailure(409, "WORKFLOW_ARCHIVED"); }
    private void revision(WorkflowStore.Row row, long expected) { if (expected < 0) throw new DomainFailure(400, "INVALID_REQUEST"); if (row.revision() != expected) throw new DomainFailure(409, "STALE_REVISION"); if (expected == Long.MAX_VALUE) throw new DomainFailure(409, "REVISION_LIMIT"); }
    private void bounds(int page, int size) { if (page < 0 || size < 1 || size > 100) throw new DomainFailure(400, "INVALID_REQUEST"); }
    private <T> Page<T> page(List<T> items, int page, int size, long total) { return new Page<>(items, page, size, total, (total + size - 1) / size); }
}
