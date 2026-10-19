package com.bridgeops.organizations.application;

import com.bridgeops.identity.application.AccountAuthority;
import com.bridgeops.organizations.persistence.OrganizationStore;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Public tenant boundary. Callers never depend on organization repositories or entities. */
@Service
public class TenantAuthorization {
    private final AccountAuthority identities;
    private final OrganizationStore organizations;
    public TenantAuthorization(AccountAuthority identities, OrganizationStore organizations) {
        this.identities = identities; this.organizations = organizations;
    }
    @Transactional(readOnly = true, timeout = 5)
    public void read(UUID user, UUID family, UUID organization, String permission) {
        identities.requireLive(user, family);
        var member = organizations.member(organization, user);
        organizations.organization(organization, false);
        organizations.require(organization, member.id(), permission);
    }
    @Transactional(readOnly=true,timeout=5)
    public boolean permitted(UUID user,UUID family,UUID organization,String permission) {
        identities.requireLive(user,family);var member=organizations.member(organization,user);organizations.organization(organization,false);
        return organizations.permissions(organization,member.id()).contains(permission);
    }
    /** Locks only; grants no dispatch authority. Used for cancellation/deadline terminal records. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockDurableContext(UUID actor, UUID organization) {
        identities.lockDurableActor(actor);
        organizations.lockDurable(organization);
    }
    /** Trusted durable worker context; browser session expiry is not execution authority. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void worker(UUID user, UUID organization) {
        trusted(user,organization,"execution.run");
    }
    /** Declare all worker identities before locking the organization; no grants are implied. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockActors(java.util.Collection<UUID> users) {
        users.stream().distinct().sorted().forEach(identities::lockDurableActor);
    }
    @Transactional(propagation = Propagation.MANDATORY,noRollbackFor=com.bridgeops.shared.web.DomainFailure.class)
    public void trusted(UUID user,UUID organization,String permission) {
        if(!identities.lockWorkerEligibility(user))throw new com.bridgeops.shared.web.DomainFailure(403,"PERMISSION_DENIED");
        organizations.organization(organization, true);
        var member = organizations.member(organization, user);
        organizations.require(organization, member.id(), permission);
    }
    /** The enclosing business transaction retains these locks through its required audit write. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void write(UUID user, UUID family, UUID organization, String permission) {
        identities.lockLive(user, family);
        organizations.member(organization, user);
        organizations.organization(organization, true);
        var member = organizations.member(organization, user);
        organizations.require(organization, member.id(), permission);
    }
}
