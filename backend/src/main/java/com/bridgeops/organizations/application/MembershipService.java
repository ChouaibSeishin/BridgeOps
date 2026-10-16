package com.bridgeops.organizations.application;

import com.bridgeops.identity.application.AccountAuthority;
import com.bridgeops.organizations.persistence.OrganizationStore;
import com.bridgeops.shared.web.DomainFailure;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MembershipService {
    private final com.bridgeops.identity.application.IdentityQueries labels;private final AccountAuthority identities;private final OrganizationStore store;private final RoleDelegation delegation;private final Clock clock;
    public MembershipService(AccountAuthority identities,OrganizationStore store,RoleDelegation delegation,Clock clock,com.bridgeops.identity.application.IdentityQueries labels){this.labels=labels;this.identities=identities;this.store=store;this.delegation=delegation;this.clock=clock;}
    @Transactional(timeout=10)
    public OrganizationService.MemberSummary change(UUID user,UUID family,UUID org,UUID member,long revision,String status,List<UUID> requested){
        if(revision<0 || !Set.of("ACTIVE","SUSPENDED","REMOVED").contains(status) || (!"REMOVED".equals(status) && (requested==null || requested.isEmpty())))throw new DomainFailure(400,"INVALID_REQUEST");
        identities.lockLive(user,family);store.member(org,user);store.organization(org,true);
        var actor=store.member(org,user);var target=store.byId(org,member);store.require(org,actor.id(),"membership.manage");
        if(target.revision()!=revision)throw new DomainFailure(409,"STALE_REVISION");
        var previous=store.roles(org,member);var assigned=delegation.validate(org,actor.id(),requested,previous);
        boolean wasOwner=target.status().equals("ACTIVE") && previous.stream().anyMatch(role->role.name().equals("OWNER"));
        boolean remainsOwner=status.equals("ACTIVE") && store.catalog(org).stream().anyMatch(role->role.name().equals("OWNER") && assigned.contains(role.id()));
        if(wasOwner && !remainsOwner && store.activeOwnerUsers(org).stream().filter(identities::active).count()<=1)throw new DomainFailure(409,"LAST_OWNER");
        var changed=store.change(org,member,revision,status,assigned);store.audit(org,user,"REMOVED".equals(status)?"MEMBER_REMOVED":"MEMBERSHIP_CHANGED",member,clock.instant());
        return new OrganizationService.MemberSummary(changed.id(),changed.userId(),changed.status(),changed.revision(),store.roles(org,member),labels.labels(List.of(changed.userId())).get(changed.userId()));
    }
}
