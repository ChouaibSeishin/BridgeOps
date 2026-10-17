package com.bridgeops.executions.web;

import com.bridgeops.executions.application.*;
import com.bridgeops.executions.application.ExecutionViews.*;
import com.bridgeops.executions.domain.ExecutionState;
import com.bridgeops.identity.application.AccessTokens.Identity;
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
public class ExecutionController {
    public record Start(UUID versionId,@NotNull JsonNode input,@Min(0) @Max(3600) Integer delaySeconds) {}
    public record Mutation(@NotNull @PositiveOrZero Long expectedRevision,@NotBlank @Size(max=200) String reason) {}
    private final ExecutionService executions;
    public ExecutionController(ExecutionService executions){this.executions=executions;}
    @PostMapping("/workflows/{workflow}/executions")
    public ResponseEntity<Summary> start(@AuthenticationPrincipal Identity actor,@PathVariable UUID organization,@PathVariable UUID workflow,
        @RequestHeader(value="Idempotency-Key",required=false)String key,@Valid @RequestBody Start body){
        return submitted(organization,executions.start(actor.userId(),actor.familyId(),organization,workflow,body.versionId(),body.input(),body.delaySeconds()==null?0:body.delaySeconds(),key));
    }
    @GetMapping("/executions") public Page<Summary> list(@AuthenticationPrincipal Identity actor,@PathVariable UUID organization,
        @RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="25")int size,@RequestParam(required=false)UUID workflowId,@RequestParam(required=false)ExecutionState status){
        return executions.list(actor.userId(),actor.familyId(),organization,page,size,workflowId,status);
    }
    @GetMapping("/executions/{id}") public Summary detail(@AuthenticationPrincipal Identity actor,@PathVariable UUID organization,@PathVariable UUID id){return executions.get(actor.userId(),actor.familyId(),organization,id);}
    @GetMapping("/executions/{id}/steps") public List<Step> steps(@AuthenticationPrincipal Identity actor,@PathVariable UUID organization,@PathVariable UUID id){return executions.steps(actor.userId(),actor.familyId(),organization,id);}
    @GetMapping("/executions/{id}/attempts") public Page<Attempt> attempts(@AuthenticationPrincipal Identity actor,@PathVariable UUID organization,@PathVariable UUID id,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="100")int size){return executions.attempts(actor.userId(),actor.familyId(),organization,id,page,size);}
    @GetMapping("/executions/{id}/events") public Page<Event> events(@AuthenticationPrincipal Identity actor,@PathVariable UUID organization,@PathVariable UUID id,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="100")int size){return executions.events(actor.userId(),actor.familyId(),organization,id,page,size);}
    @PostMapping("/executions/{id}/cancel") public Summary cancel(@AuthenticationPrincipal Identity actor,@PathVariable UUID organization,@PathVariable UUID id,@Valid @RequestBody Mutation body){return executions.cancel(actor.userId(),actor.familyId(),organization,id,body.expectedRevision(),body.reason());}
    @PostMapping("/executions/{id}/retry") public ResponseEntity<Summary> retry(@AuthenticationPrincipal Identity actor,@PathVariable UUID organization,@PathVariable UUID id,@RequestHeader(value="Idempotency-Key",required=false)String key,@Valid @RequestBody Mutation body){return submitted(organization,executions.retry(actor.userId(),actor.familyId(),organization,id,body.expectedRevision(),body.reason(),key));}
    private ResponseEntity<Summary> submitted(UUID org,Submission result){
        return ResponseEntity.status(result.execution().status().terminal()?200:202).location(URI.create("/api/v1/organizations/"+org+"/executions/"+result.execution().id())).body(result.execution());
    }
}
