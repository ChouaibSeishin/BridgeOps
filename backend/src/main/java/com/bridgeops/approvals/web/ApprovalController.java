package com.bridgeops.approvals.web;

import com.bridgeops.approvals.application.ApprovalService;
import com.bridgeops.identity.application.AccessTokens.Identity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/organizations/{organization}")
@ConditionalOnProperty(name="bridgeops.auth.enabled",havingValue="true")
public class ApprovalController {
    public record Decision(@NotNull @PositiveOrZero Long expectedRevision,@NotBlank String decision,@Size(max=200)String reason) {}
    public record Policy(@NotNull @PositiveOrZero Long expectedRevision,@NotNull Boolean requireRecordApproval,@NotNull Integer lifetimeSeconds) {}
    private final ApprovalService approvals;
    public ApprovalController(ApprovalService approvals){this.approvals=approvals;}
    @GetMapping("/approvals") public ApprovalService.Page list(@AuthenticationPrincipal Identity actor,@PathVariable UUID organization,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="25")int size,@RequestParam(required=false)String status,@RequestParam(required=false)UUID executionId){return approvals.list(actor.userId(),actor.familyId(),organization,page,size,status,executionId);}
    @GetMapping("/approvals/{id}") public ApprovalService.View get(@AuthenticationPrincipal Identity actor,@PathVariable UUID organization,@PathVariable UUID id){return approvals.get(actor.userId(),actor.familyId(),organization,id);}
    @PostMapping("/approvals/{id}/decision") public ApprovalService.View decide(@AuthenticationPrincipal Identity actor,@PathVariable UUID organization,@PathVariable UUID id,@Valid @RequestBody Decision body){return approvals.decide(actor.userId(),actor.familyId(),organization,id,body.expectedRevision(),body.decision(),body.reason());}
    @GetMapping("/approval-policy") public ApprovalService.PolicyView policy(@AuthenticationPrincipal Identity actor,@PathVariable UUID organization){return approvals.policy(actor.userId(),actor.familyId(),organization);}
    @PutMapping("/approval-policy") public ApprovalService.PolicyView policy(@AuthenticationPrincipal Identity actor,@PathVariable UUID organization,@Valid @RequestBody Policy body){return approvals.updatePolicy(actor.userId(),actor.familyId(),organization,body.expectedRevision(),body.requireRecordApproval(),body.lifetimeSeconds());}
}
