package com.bridgeops.integrations.web;

import com.bridgeops.identity.application.AccessTokens.Identity;
import com.bridgeops.integrations.application.DemoWorkflowService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/organizations/{organization}/demo-workflows")
@ConditionalOnProperty(name="bridgeops.auth.enabled",havingValue="true")
public class DemoWorkflowController {
    public record Install(@NotNull Boolean confirmed){}
    private final DemoWorkflowService service;
    public DemoWorkflowController(DemoWorkflowService service){this.service=service;}
    @GetMapping public DemoWorkflowService.Result get(@AuthenticationPrincipal Identity actor,@PathVariable UUID organization){return service.get(actor.userId(),actor.familyId(),organization);}
    @PostMapping public ResponseEntity<DemoWorkflowService.Result> install(@AuthenticationPrincipal Identity actor,@PathVariable UUID organization,@Valid @RequestBody Install body){var result=service.install(actor.userId(),actor.familyId(),organization,body.confirmed());return ResponseEntity.status(result.created()?201:200).location(URI.create("/api/v1/organizations/"+organization+"/demo-workflows")).body(result);}
}
