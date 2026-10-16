package com.bridgeops.organizations.application;

import com.bridgeops.organizations.persistence.OrganizationStore;
import com.bridgeops.shared.web.DomainFailure;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class RoleDelegation {
    private final OrganizationStore store;
    public RoleDelegation(OrganizationStore store){this.store=store;}
    public Set<UUID> validate(UUID org,UUID actorMember,List<UUID> requested,List<OrganizationStore.Role> existing){
        if(requested==null || requested.size()>6 || new HashSet<>(requested).size()!=requested.size() || requested.stream().anyMatch(Objects::isNull))throw new DomainFailure(400,"INVALID_REQUEST");
        var authority=store.permissions(org,actorMember);boolean privileged=authority.contains("membership.grant_privileged");
        if(!privileged && existing.stream().anyMatch(role->Set.of("OWNER","ADMIN").contains(role.name())))throw new DomainFailure(403,"PERMISSION_DENIED");
        var catalog=store.catalog(org);var ids=new HashSet<UUID>();
        for(UUID id:requested){
            var role=catalog.stream().filter(item->item.id().equals(id)).findFirst().orElseThrow(()->new DomainFailure(404,"NOT_FOUND"));
            if((!privileged && Set.of("OWNER","ADMIN").contains(role.name())) || !authority.containsAll(store.rolePermissions(org,id)))throw new DomainFailure(403,"PERMISSION_DENIED");
            ids.add(id);
        }
        return Set.copyOf(ids);
    }
}
