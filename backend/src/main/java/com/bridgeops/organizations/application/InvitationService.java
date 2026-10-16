package com.bridgeops.organizations.application;

import com.bridgeops.identity.application.*;
import com.bridgeops.organizations.persistence.*;
import com.bridgeops.organizations.persistence.InvitationStore.Invitation;
import com.bridgeops.shared.web.DomainFailure;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvitationService {
 public record Summary(UUID id,UUID organizationId,String email,Instant expiresAt){}
 public record Accepted(UUID userId,UUID organizationId,UUID membershipId){}
 private final AccountAuthority identities;private final InvitationAccounts accounts;private final OrganizationStore organizations;
 private final InvitationStore invitations;private final RoleDelegation delegation;private final RefreshTokens tokens;private final InvitationDelivery delivery;private final Clock clock;
 public InvitationService(AccountAuthority identities,InvitationAccounts accounts,OrganizationStore organizations,InvitationStore invitations,RoleDelegation delegation,RefreshTokens tokens,InvitationDelivery delivery,Clock clock){
    this.identities=identities;this.accounts=accounts;this.organizations=organizations;this.invitations=invitations;this.delegation=delegation;this.tokens=tokens;this.delivery=delivery;this.clock=clock;
 }
 @Transactional(timeout=20)
 public Summary issue(UUID user,UUID family,UUID org,String email,List<UUID> roles){
    identities.lockLive(user,family);organizations.member(org,user);organizations.organization(org,true);var actor=organizations.member(org,user);organizations.require(org,actor.id(),"membership.invite");
    if(roles==null || roles.isEmpty())throw new DomainFailure(400,"INVALID_REQUEST");
    var assigned=delegation.validate(org,actor.id(),roles,List.of());String normalized=AccountIdentifiers.email(email);
    if(accounts.find(normalized).filter(id->invitations.memberExists(org,id)).isPresent())throw new DomainFailure(409,"RESOURCE_CONFLICT");
    String raw=tokens.generate();var invitation=invitations.create(org,actor.id(),normalized,tokens.hash(raw),assigned,clock.instant());
    delivery.deliver(normalized,raw,invitation.expiresAt());organizations.audit(org,user,"INVITATION_ISSUED",invitation.id(),clock.instant());
    return new Summary(invitation.id(),org,normalized,invitation.expiresAt());
 }
 @Transactional(timeout=10)
 public Accepted accept(UUID user,UUID family,String raw){
    identities.lockLive(user,family);var invitation=locked(raw);if(!accounts.email(user).equals(invitation.email()))throw InvitationStore.rejected();
    UUID member=invitations.join(invitation,user,invitations.roles(invitation.organizationId(),invitation.id()),clock.instant());
    invitations.consume(invitation.organizationId(),invitation.id(),clock.instant());organizations.audit(invitation.organizationId(),user,"INVITATION_ACCEPTED",invitation.id(),clock.instant());
    return new Accepted(user,invitation.organizationId(),member);
 }
 @Transactional(timeout=10)
 public Accepted enroll(String raw,String email,String displayName,String password){
    var invitation=locked(raw);String normalized=AccountIdentifiers.email(email);
    if(!normalized.equals(invitation.email()) || accounts.find(normalized).isPresent())throw InvitationStore.rejected();
    var enrolled=accounts.enroll(raw,normalized,displayName,password);
    return new Accepted(enrolled.userId(),enrolled.organizationId(),enrolled.membershipId());
 }
 @Transactional(timeout=10)
 public void revoke(UUID user,UUID family,UUID org,UUID id){
    identities.lockLive(user,family);organizations.member(org,user);organizations.organization(org,true);var actor=organizations.member(org,user);var invitation=invitations.id(org,id);
    organizations.require(org,actor.id(),"membership.invite");delegation.validate(org,actor.id(),invitations.roles(org,id),List.of());
    if(invitation.acceptedAt()!=null)throw new DomainFailure(409,"STATE_CONFLICT");if(invitation.revokedAt()!=null)return;
    invitations.revoke(org,id,clock.instant());organizations.audit(org,user,"INVITATION_REVOKED",id,clock.instant());
 }
 private Invitation locked(String raw){
    if(!tokens.isWellFormed(raw))throw InvitationStore.rejected();var reference=invitations.hash(tokens.hash(raw));organizations.organization(reference.organizationId(),true);
    var invitation=invitations.id(reference.organizationId(),reference.id());
    if(invitation.acceptedAt()!=null || invitation.revokedAt()!=null || !clock.instant().isBefore(invitation.expiresAt()))throw InvitationStore.rejected();
    try{
        var issuer=organizations.byId(invitation.organizationId(),invitation.issuerMembership());
        if(!issuer.status().equals("ACTIVE") || !identities.active(issuer.userId()))throw InvitationStore.rejected();
        organizations.require(invitation.organizationId(),issuer.id(),"membership.invite");
        delegation.validate(invitation.organizationId(),issuer.id(),invitations.roles(invitation.organizationId(),invitation.id()),List.of());
    }catch(DomainFailure invalid){throw InvitationStore.rejected();}
    return invitation;
 }
}
