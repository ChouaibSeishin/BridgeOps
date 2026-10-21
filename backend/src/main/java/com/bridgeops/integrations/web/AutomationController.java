package com.bridgeops.integrations.web;

import com.bridgeops.identity.application.AccessTokens.Identity;
import com.bridgeops.integrations.application.AutomationConfiguration;
import com.bridgeops.organizations.application.AutomationAuthority;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/organizations/{organization}/automations")
@ConditionalOnProperty(name="bridgeops.auth.enabled",havingValue="true")
public class AutomationController {
    public record Create(@NotBlank @Size(max=120) String name, @NotNull UUID workflowId, @NotNull UUID versionId) {}
    public record Revoke(@NotNull @PositiveOrZero Long expectedRevision) {}
    private final AutomationAuthority automations; private final AutomationConfiguration configuration;
    public AutomationController(AutomationAuthority automations, AutomationConfiguration configuration) { this.automations = automations; this.configuration = configuration; }
    @GetMapping public AutomationAuthority.Page list(@AuthenticationPrincipal Identity a, @PathVariable UUID organization, @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="25") int size) { return automations.list(a.userId(), a.familyId(), organization, page, size); }
    @GetMapping("/{id}") public AutomationAuthority.Principal get(@AuthenticationPrincipal Identity a, @PathVariable UUID organization, @PathVariable UUID id) { return automations.get(a.userId(), a.familyId(), organization, id); }
    @PostMapping public ResponseEntity<AutomationAuthority.Principal> create(@AuthenticationPrincipal Identity a, @PathVariable UUID organization, @Valid @RequestBody Create body) { var created = configuration.create(a.userId(), a.familyId(), organization, body.name(), body.workflowId(), body.versionId()); return ResponseEntity.created(URI.create("/api/v1/organizations/" + organization + "/automations/" + created.id())).body(created); }
    @PostMapping("/{id}/revoke") public AutomationAuthority.Principal revoke(@AuthenticationPrincipal Identity a, @PathVariable UUID organization, @PathVariable UUID id, @Valid @RequestBody Revoke body) { return automations.revoke(a.userId(), a.familyId(), organization, id, body.expectedRevision()); }
}
