package com.bridgeops.workflows.application;

import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Immutable application DTOs; persistence rows never cross the HTTP boundary. */
public final class WorkflowViews {
    private WorkflowViews() {}
    public record Page<T>(List<T> items, int page, int size, long totalElements, long totalPages) {}
    public record Summary(UUID id, String name, String description, String status, long revision, UUID currentPublishedVersionId, Instant updatedAt) {}
    public record VersionSummary(UUID id, UUID workflowId, int versionNumber, long sourceRevision, int schemaVersion,
        int canonicalizationVersion, String name, String description, String definitionHash, UUID publishedBy, Instant publishedAt) {}
    public record Detail(Summary workflow, JsonNode definition, VersionSummary currentPublishedVersion) {}
    public record Snapshot(VersionSummary version, JsonNode definition) {}
}
