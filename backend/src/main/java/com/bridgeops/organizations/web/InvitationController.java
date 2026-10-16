package com.bridgeops.organizations.web;

import com.bridgeops.identity.application.*;
import com.bridgeops.identity.application.AccessTokens.Identity;
import com.bridgeops.organizations.application.InvitationService;
import com.bridgeops.shared.web.ApiErrors;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name="bridgeops.auth.enabled",havingValue="true")
public class InvitationController {
 public record Issue(@NotBlank @Size(max=254) String email,@NotEmpty @Size(max=6) List<UUID> roleIds){}
 public record Accept(@NotBlank @Size(min=43,max=43) String token){@Override public String toString(){return "Accept[REDACTED]";}}
 public record Enroll(@NotBlank @Size(min=43,max=43) String token,@NotBlank @Size(max=254) String email,@NotBlank @Size(max=120) String displayName,@NotBlank @Size(max=256) String password){@Override public String toString(){return "Enroll[REDACTED]";}}
 private final InvitationService invitations;private final AuthenticationThrottle throttle;
 public InvitationController(InvitationService invitations,AuthenticationThrottle throttle){this.invitations=invitations;this.throttle=throttle;}
 @PostMapping("/api/v1/organizations/{org}/invitations")
 public ResponseEntity<InvitationService.Summary> issue(@AuthenticationPrincipal Identity actor,@PathVariable UUID org,@Valid @RequestBody Issue body,HttpServletRequest request,HttpServletResponse response)throws IOException{
    if(!allow("invite",body.email(),request,response))return null;
    var invitation=invitations.issue(actor.userId(),actor.familyId(),org,body.email(),body.roleIds());
    return ResponseEntity.created(java.net.URI.create("/api/v1/organizations/"+org+"/invitations/"+invitation.id())).body(invitation);
 }
 @PostMapping("/api/v1/invitations/accept")
 public InvitationService.Accepted accept(@AuthenticationPrincipal Identity actor,@Valid @RequestBody Accept body,HttpServletRequest request,HttpServletResponse response)throws IOException{
    if(!allow("invite-accept","",request,response))return null;return invitations.accept(actor.userId(),actor.familyId(),body.token());
 }
 @PostMapping("/api/v1/invitations/enroll")
 public ResponseEntity<InvitationService.Accepted> enroll(@Valid @RequestBody Enroll body,HttpServletRequest request,HttpServletResponse response)throws IOException{
    if(!allow("enroll",body.email(),request,response))return null;var accepted=invitations.enroll(body.token(),body.email(),body.displayName(),body.password());
    return ResponseEntity.created(java.net.URI.create("/api/v1/organizations/"+accepted.organizationId()+"/members/"+accepted.membershipId())).body(accepted);
 }
 @DeleteMapping("/api/v1/organizations/{org}/invitations/{id}")
 public ResponseEntity<Void> revoke(@AuthenticationPrincipal Identity actor,@PathVariable UUID org,@PathVariable UUID id){invitations.revoke(actor.userId(),actor.familyId(),org,id);return ResponseEntity.noContent().build();}
 private boolean allow(String operation,String email,HttpServletRequest request,HttpServletResponse response)throws IOException{
    response.setHeader("Cache-Control","no-store");long retry=throttle.acquire(operation,request.getRemoteAddr(),email);if(retry==0)return true;
    response.setHeader("Retry-After",Long.toString(retry));ApiErrors.write(request,response,429,"RATE_LIMITED","Too many authentication requests.");return false;
 }
}
