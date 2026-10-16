package com.bridgeops.organizations.web;

import com.bridgeops.identity.application.AccessTokens.Identity;
import com.bridgeops.organizations.application.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/members/{membershipId}")
@ConditionalOnProperty(name="bridgeops.auth.enabled",havingValue="true")
public class MembershipController {
 public record Change(@NotNull @Min(0) Long expectedRevision,@NotBlank @Pattern(regexp="ACTIVE|SUSPENDED") String status,@NotEmpty @Size(max=6) List<UUID> roleIds){}
 private final MembershipService members;public MembershipController(MembershipService members){this.members=members;}
 @PatchMapping public OrganizationService.MemberSummary change(@AuthenticationPrincipal Identity actor,@PathVariable UUID organizationId,@PathVariable UUID membershipId,@Valid @RequestBody Change body){return members.change(actor.userId(),actor.familyId(),organizationId,membershipId,body.expectedRevision(),body.status(),body.roleIds());}
 @DeleteMapping public ResponseEntity<Void> remove(@AuthenticationPrincipal Identity actor,@PathVariable UUID organizationId,@PathVariable UUID membershipId,@RequestHeader("If-Match") String version){
    if(!version.matches("\"[0-9]+\""))throw new IllegalArgumentException();
    members.change(actor.userId(),actor.familyId(),organizationId,membershipId,Long.parseLong(version.substring(1,version.length()-1)),"REMOVED",List.of());return ResponseEntity.noContent().build();
 }
}
