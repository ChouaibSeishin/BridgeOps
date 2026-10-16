package com.bridgeops.workflows.web;

import com.bridgeops.identity.application.AccessTokens.Identity;
import com.bridgeops.workflows.application.WorkflowService;
import com.bridgeops.workflows.application.WorkflowViews.*;
import com.bridgeops.workflows.domain.GraphDefinition.Validation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/organizations/{organization}/workflows")
@ConditionalOnProperty(name="bridgeops.auth.enabled", havingValue="true")
public class WorkflowController {
    public record Create(@NotBlank @Size(max=120) String name, @NotNull @Size(max=2000) String description, @NotNull JsonNode definition) {}
    public record Save(@NotNull @PositiveOrZero Long expectedRevision, @NotBlank @Size(max=120) String name, @NotNull @Size(max=2000) String description, @NotNull JsonNode definition) {}
    public record Revision(@NotNull @PositiveOrZero Long expectedRevision) {}
    private final WorkflowService workflows;
    public WorkflowController(WorkflowService workflows) { this.workflows = workflows; }
    @GetMapping public Page<Summary> list(@AuthenticationPrincipal Identity user, @PathVariable UUID organization, @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="25") int size) {
        return workflows.list(user.userId(), user.familyId(), organization, page, size);
    }
    @PostMapping public ResponseEntity<Detail> create(@AuthenticationPrincipal Identity user, @PathVariable UUID organization, @Valid @RequestBody Create request) {
        var created = workflows.create(user.userId(), user.familyId(), organization, request.name(), request.description(), request.definition());
        return ResponseEntity.created(URI.create(base(organization) + "/" + created.workflow().id())).body(created);
    }
    @GetMapping("/{id}") public Detail get(@AuthenticationPrincipal Identity user, @PathVariable UUID organization, @PathVariable UUID id) {
        return workflows.get(user.userId(), user.familyId(), organization, id);
    }
    @PatchMapping("/{id}") public Detail save(@AuthenticationPrincipal Identity user, @PathVariable UUID organization, @PathVariable UUID id, @Valid @RequestBody Save request) {
        return workflows.save(user.userId(), user.familyId(), organization, id, request.expectedRevision(), request.name(), request.description(), request.definition());
    }
    @DeleteMapping("/{id}") public ResponseEntity<Void> archive(@AuthenticationPrincipal Identity user, @PathVariable UUID organization, @PathVariable UUID id, @RequestHeader("If-Match") String expected) {
        if (!expected.matches("\"(?:0|[1-9][0-9]{0,18})\"")) throw new IllegalArgumentException("A quoted revision is required.");
        workflows.archive(user.userId(), user.familyId(), organization, id, Long.parseLong(expected.substring(1, expected.length() - 1)));
        return ResponseEntity.noContent().build();
    }
    @PostMapping("/{id}/validate") public Validation validate(@AuthenticationPrincipal Identity user, @PathVariable UUID organization, @PathVariable UUID id, @Valid @RequestBody Revision request) {
        return workflows.validate(user.userId(), user.familyId(), organization, id, request.expectedRevision());
    }
    @PostMapping("/{id}/publish") public ResponseEntity<Snapshot> publish(@AuthenticationPrincipal Identity user, @PathVariable UUID organization, @PathVariable UUID id, @Valid @RequestBody Revision request) {
        var version = workflows.publish(user.userId(), user.familyId(), organization, id, request.expectedRevision());
        return ResponseEntity.created(URI.create(base(organization) + "/" + id + "/versions/" + version.version().id())).body(version);
    }
    @PostMapping("/{id}/duplicate") public ResponseEntity<Detail> duplicate(@AuthenticationPrincipal Identity user, @PathVariable UUID organization, @PathVariable UUID id, @Valid @RequestBody Revision request) {
        var created = workflows.duplicate(user.userId(), user.familyId(), organization, id, request.expectedRevision());
        return ResponseEntity.created(URI.create(base(organization) + "/" + created.workflow().id())).body(created);
    }
    @GetMapping("/{id}/versions") public Page<VersionSummary> versions(@AuthenticationPrincipal Identity user, @PathVariable UUID organization, @PathVariable UUID id, @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="25") int size) {
        return workflows.versions(user.userId(), user.familyId(), organization, id, page, size);
    }
    @GetMapping("/{id}/versions/{version}") public Snapshot version(@AuthenticationPrincipal Identity user, @PathVariable UUID organization, @PathVariable UUID id, @PathVariable UUID version) {
        return workflows.version(user.userId(), user.familyId(), organization, id, version);
    }
    private String base(UUID organization) { return "/api/v1/organizations/" + organization + "/workflows"; }
}
