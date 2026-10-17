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
    /** Trusted durable worker context; browser session expiry is not execution authority. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void worker(UUID user, UUID organization) {
        identities.lockActiveWorker(user);
        organizations.organization(organization, true);
        var member = organizations.member(organization, user);
        organizations.require(organization, member.id(), "execution.run");
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
