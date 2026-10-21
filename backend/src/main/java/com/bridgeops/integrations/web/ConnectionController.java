package com.bridgeops.integrations.web;

import com.bridgeops.identity.application.AccessTokens.Identity;
import com.bridgeops.integrations.application.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/organizations/{organization}")
@ConditionalOnProperty(name="bridgeops.auth.enabled",havingValue="true")
public class ConnectionController {
    public record Create(@NotBlank @Size(max=120)String name,@NotNull @Size(max=1000)String description,@NotBlank String type,@NotNull JsonNode configuration,@Size(max=4096)String credential) {}
    public record Update(@NotNull @PositiveOrZero Long expectedRevision,@NotBlank @Size(max=120)String name,@NotNull @Size(max=1000)String description,@NotNull Boolean enabled,@NotNull JsonNode configuration,@Size(max=4096)String credential,Boolean removeCredential) {}
    public record Check(@NotNull @PositiveOrZero Long expectedRevision,Boolean effectsAcknowledged) {}
    private final ConnectionService connections;private final ConnectionChecks checks;
    public ConnectionController(ConnectionService connections,ConnectionChecks checks){this.connections=connections;this.checks=checks;}
    @GetMapping("/integration-definitions") public List<ConnectionService.Definition> definitions(@AuthenticationPrincipal Identity a,@PathVariable UUID organization){return connections.definitions(a.userId(),a.familyId(),organization);}
    @GetMapping("/integrations") public ConnectionService.Page list(@AuthenticationPrincipal Identity a,@PathVariable UUID organization,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="25")int size){return connections.list(a.userId(),a.familyId(),organization,page,size);}
    @GetMapping("/integrations/{id}") public ConnectionService.Summary get(@AuthenticationPrincipal Identity a,@PathVariable UUID organization,@PathVariable UUID id){return connections.get(a.userId(),a.familyId(),organization,id);}
    @GetMapping("/integrations/{id}/configuration") public ConnectionService.Managed configuration(@AuthenticationPrincipal Identity a,@PathVariable UUID organization,@PathVariable UUID id){return connections.configuration(a.userId(),a.familyId(),organization,id);}
    @GetMapping("/integrations/{id}/operations") public List<ConnectionService.Operation> operations(@AuthenticationPrincipal Identity a,@PathVariable UUID organization,@PathVariable UUID id){return connections.operations(a.userId(),a.familyId(),organization,id);}
    @PostMapping("/integrations") public ResponseEntity<ConnectionService.Managed> create(@AuthenticationPrincipal Identity a,@PathVariable UUID organization,@Valid @RequestBody Create body){var created=connections.create(a.userId(),a.familyId(),organization,body.name(),body.description(),body.type(),body.configuration(),body.credential());return ResponseEntity.created(URI.create("/api/v1/organizations/"+organization+"/integrations/"+created.connection().id())).body(created);}
    @PutMapping("/integrations/{id}") public ConnectionService.Managed update(@AuthenticationPrincipal Identity a,@PathVariable UUID organization,@PathVariable UUID id,@Valid @RequestBody Update body){return connections.update(a.userId(),a.familyId(),organization,id,body.expectedRevision(),body.name(),body.description(),body.enabled(),body.configuration(),body.credential(),Boolean.TRUE.equals(body.removeCredential()));}
    @PostMapping("/integrations/{id}/test") public ConnectionService.Summary check(@AuthenticationPrincipal Identity a,@PathVariable UUID organization,@PathVariable UUID id,@Valid @RequestBody Check body){return checks.check(a.userId(),a.familyId(),organization,id,body.expectedRevision(),Boolean.TRUE.equals(body.effectsAcknowledged()));}
}
