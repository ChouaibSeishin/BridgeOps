package com.bridgeops.organizations.web;

import com.bridgeops.identity.application.AccessTokens.Identity;
import com.bridgeops.organizations.application.OrganizationService;
import com.bridgeops.organizations.persistence.OrganizationStore.Organization;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/organizations")
@ConditionalOnProperty(name="bridgeops.auth.enabled",havingValue="true")
public class OrganizationController {
    public record CreateRequest(@NotBlank @Size(max=120) String name,@NotBlank @Size(min=3,max=64) @Pattern(regexp="[a-z0-9]+(?:-[a-z0-9]+)*") String slug){}
    private final OrganizationService organizations;
    public OrganizationController(OrganizationService organizations){this.organizations=organizations;}
    @PostMapping public ResponseEntity<Organization> create(@AuthenticationPrincipal Identity actor,@Valid @RequestBody CreateRequest body){
        var org=organizations.create(actor.userId(),actor.familyId(),body.name(),body.slug());
        return ResponseEntity.created(URI.create("/api/v1/organizations/"+org.id())).body(org);
    }
    @GetMapping public OrganizationService.Page<Organization> list(@AuthenticationPrincipal Identity actor,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size){return organizations.list(actor.userId(),actor.familyId(),page,size);}
    @GetMapping("/{id}") public Organization detail(@AuthenticationPrincipal Identity actor,@PathVariable UUID id){return organizations.detail(actor.userId(),actor.familyId(),id);}
    @GetMapping("/{id}/roles") public java.util.List<com.bridgeops.organizations.persistence.OrganizationStore.Role> roles(@AuthenticationPrincipal Identity actor,@PathVariable UUID id){return organizations.roles(actor.userId(),actor.familyId(),id);}
    @GetMapping("/{id}/members") public OrganizationService.Page<OrganizationService.MemberSummary> members(@AuthenticationPrincipal Identity actor,@PathVariable UUID id,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size){return organizations.members(actor.userId(),actor.familyId(),id,page,size);}
}
