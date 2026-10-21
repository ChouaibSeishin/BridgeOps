package com.bridgeops.integrations.web;

import com.bridgeops.identity.application.AccessTokens.Identity;
import com.bridgeops.integrations.application.BusinessService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/organizations/{organization}")
@ConditionalOnProperty(name="bridgeops.auth.enabled",havingValue="true")
public class BusinessController {
    public record Demo(@NotNull Boolean confirmed) {}
    private final BusinessService business;
    public BusinessController(BusinessService business){this.business=business;}
    @GetMapping("/support/tickets") public BusinessService.Page<BusinessService.Ticket> tickets(@AuthenticationPrincipal Identity a,@PathVariable UUID organization,@RequestParam(required=false)String status,@RequestParam(required=false)String priority,@RequestParam(required=false)String category,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="25")int size){return business.tickets(a.userId(),a.familyId(),organization,status,priority,category,page,size);}
    @GetMapping("/support/tickets/{id}") public BusinessService.Ticket ticket(@AuthenticationPrincipal Identity a,@PathVariable UUID organization,@PathVariable UUID id){return business.ticket(a.userId(),a.familyId(),organization,id);}
    @GetMapping("/teams") public List<BusinessService.Team> teams(@AuthenticationPrincipal Identity a,@PathVariable UUID organization){return business.teams(a.userId(),a.familyId(),organization);}
    @GetMapping("/teams/{id}") public BusinessService.Team team(@AuthenticationPrincipal Identity a,@PathVariable UUID organization,@PathVariable UUID id){return business.team(a.userId(),a.familyId(),organization,id);}
    @GetMapping("/teams/responsibilities") public List<BusinessService.Responsibility> responsibilities(@AuthenticationPrincipal Identity a,@PathVariable UUID organization){return business.responsibilities(a.userId(),a.familyId(),organization);}
    @GetMapping("/notifications") public BusinessService.Page<BusinessService.Notification> notifications(@AuthenticationPrincipal Identity a,@PathVariable UUID organization,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="25")int size){return business.notifications(a.userId(),a.familyId(),organization,page,size);}
    @GetMapping("/notifications/{id}") public BusinessService.Notification notification(@AuthenticationPrincipal Identity a,@PathVariable UUID organization,@PathVariable UUID id){return business.notification(a.userId(),a.familyId(),organization,id);}
    @GetMapping("/notifications/{id}/attempts") public List<BusinessService.Attempt> attempts(@AuthenticationPrincipal Identity a,@PathVariable UUID organization,@PathVariable UUID id){return business.attempts(a.userId(),a.familyId(),organization,id);}
    @PostMapping("/demo-data") public BusinessService.DemoResult demo(@AuthenticationPrincipal Identity a,@PathVariable UUID organization,@Valid @RequestBody Demo body){return business.demo(a.userId(),a.familyId(),organization,body.confirmed());}
}
