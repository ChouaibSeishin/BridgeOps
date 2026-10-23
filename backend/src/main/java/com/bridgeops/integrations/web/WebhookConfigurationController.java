package com.bridgeops.integrations.web;

import com.bridgeops.identity.application.AccessTokens.Identity;
import com.bridgeops.integrations.application.WebhookConfiguration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/organizations/{organization}/webhook-endpoints")
@ConditionalOnProperty(name="bridgeops.auth.enabled",havingValue="true")
public class WebhookConfigurationController {
    public record Create(@NotBlank @Size(max=120)String name,@NotNull UUID principalId,@NotNull @AssertTrue Boolean confirmed){}
    public record Rotate(@NotNull @PositiveOrZero Long expectedRevision,@NotNull @AssertTrue Boolean confirmed){}
    public record Revoke(@NotNull @PositiveOrZero Long expectedRevision){}
    private final WebhookConfiguration configuration;
    public WebhookConfigurationController(WebhookConfiguration configuration){this.configuration=configuration;}
    @GetMapping public WebhookConfiguration.Page list(@AuthenticationPrincipal Identity a,@PathVariable UUID organization,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="25")int size){return configuration.list(a.userId(),a.familyId(),organization,page,size);}
    @GetMapping("/{id}") public WebhookConfiguration.Endpoint get(@AuthenticationPrincipal Identity a,@PathVariable UUID organization,@PathVariable UUID id){return configuration.get(a.userId(),a.familyId(),organization,id);}
    @PostMapping public ResponseEntity<WebhookConfiguration.Revealed> create(@AuthenticationPrincipal Identity a,@PathVariable UUID organization,@Valid @RequestBody Create body){var result=configuration.create(a.userId(),a.familyId(),organization,body.name(),body.principalId(),body.confirmed());return ResponseEntity.created(URI.create("/api/v1/organizations/"+organization+"/webhook-endpoints/"+result.endpoint().id())).cacheControl(CacheControl.noStore()).body(result);}
    @PostMapping("/{id}/rotate") public ResponseEntity<WebhookConfiguration.Revealed> rotate(@AuthenticationPrincipal Identity a,@PathVariable UUID organization,@PathVariable UUID id,@Valid @RequestBody Rotate body){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(configuration.rotate(a.userId(),a.familyId(),organization,id,body.expectedRevision(),body.confirmed()));}
    @PostMapping("/{id}/revoke") public WebhookConfiguration.Endpoint revoke(@AuthenticationPrincipal Identity a,@PathVariable UUID organization,@PathVariable UUID id,@Valid @RequestBody Revoke body){return configuration.revoke(a.userId(),a.familyId(),organization,id,body.expectedRevision());}
}
