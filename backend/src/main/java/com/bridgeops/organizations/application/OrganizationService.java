package com.bridgeops.organizations.application;

import com.bridgeops.identity.application.AccountAuthority;
import com.bridgeops.organizations.persistence.OrganizationStore;
import com.bridgeops.organizations.persistence.OrganizationStore.*;
import com.bridgeops.shared.web.DomainFailure;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrganizationService {
    public record Page<T>(List<T> items,int page,int size,long totalElements,long totalPages){}
    public record Access(UUID membershipId,Set<String> permissions){}
    public record RoleSummary(UUID id,String name,boolean assignable){}
    public record MemberSummary(UUID id,UUID userId,String status,long revision,List<Role> roles,com.bridgeops.identity.application.IdentityQueries.MemberLabel account){}
    private final com.bridgeops.identity.application.IdentityQueries labels;private final AccountAuthority identities;private final OrganizationStore store;private final Clock clock;
    public OrganizationService(AccountAuthority identities,OrganizationStore store,Clock clock,com.bridgeops.identity.application.IdentityQueries labels){this.labels=labels;this.identities=identities;this.store=store;this.clock=clock;}
    @Transactional(timeout=10)
    public Organization create(UUID user,UUID family,String name,String slug){
        identities.lockLive(user,family);identities.requireOrganizationGrant(user);
        if(name==null || name.isBlank() || name.length()>120 || name.codePoints().anyMatch(Character::isISOControl)
            || slug==null || slug.length()<3 || slug.length()>64 || !slug.matches("[a-z0-9]+(?:-[a-z0-9]+)*"))throw new DomainFailure(400,"INVALID_REQUEST");
        return store.insert(user,name.trim(),slug,clock.instant());
    }
    @Transactional(readOnly=true,timeout=5)
    public Page<Organization> list(UUID user,UUID family,int page,int size){identities.requireLive(user,family);bounds(page,size);return page(store.list(user,page,size),page,size,store.count(user));}
    @Transactional(readOnly=true,timeout=5)
    public Organization detail(UUID user,UUID family,UUID org){identities.requireLive(user,family);var m=store.member(org,user);store.require(org,m.id(),"organization.read");return store.organization(org,false);}
    @Transactional(readOnly=true,timeout=5)
    public Page<MemberSummary> members(UUID user,UUID family,UUID org,int page,int size){
        identities.requireLive(user,family);bounds(page,size);var m=store.member(org,user);store.require(org,m.id(),"membership.read");store.organization(org,false);
        var members=store.members(org,page,size);var accounts=labels.labels(members.stream().map(Membership::userId).toList());
        var rows=members.stream().map(member->new MemberSummary(member.id(),member.userId(),member.status(),member.revision(),store.roles(org,member.id()),accounts.get(member.userId()))).toList();
        return page(rows,page,size,store.memberCount(org));
    }
    @Transactional(readOnly=true,timeout=5)
    public List<RoleSummary> roles(UUID user,UUID family,UUID org){identities.requireLive(user,family);var member=store.member(org,user);store.require(org,member.id(),"membership.read");store.organization(org,false);var permissions=store.permissions(org,member.id());return store.catalog(org).stream().map(role->new RoleSummary(role.id(),role.name(),permissions.containsAll(store.rolePermissions(org,role.id())) && (!Set.of("OWNER","ADMIN").contains(role.name()) || permissions.contains("membership.grant_privileged")))).toList();}
    @Transactional(readOnly=true,timeout=5) public Access access(UUID user,UUID family,UUID org){identities.requireLive(user,family);var member=store.member(org,user);store.organization(org,false);return new Access(member.id(),store.permissions(org,member.id()));}
    private void bounds(int page,int size){if(page<0 || size<1 || size>100)throw new DomainFailure(400,"INVALID_REQUEST");}
    private <T> Page<T> page(List<T> items,int page,int size,long total){return new Page<>(items,page,size,total,(total+size-1)/size);}
}
