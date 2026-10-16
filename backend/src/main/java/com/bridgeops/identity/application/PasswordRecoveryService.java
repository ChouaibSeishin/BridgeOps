package com.bridgeops.identity.application;
import com.bridgeops.identity.persistence.*;
import com.bridgeops.shared.mail.OutboundEmail;
import com.bridgeops.shared.web.DomainFailure;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
public class PasswordRecoveryService {
 private final UserAccountRepository users;private final PasswordResetStore resets;private final PasswordSessionService sessions;
 private final AccountPasswords passwords;private final RefreshTokens tokens;private final IdentitySecurityEventRepository events;private final OutboundEmail email;private final Clock clock;
 public PasswordRecoveryService(UserAccountRepository users,PasswordResetStore resets,PasswordSessionService sessions,AccountPasswords passwords,RefreshTokens tokens,IdentitySecurityEventRepository events,OutboundEmail email,Clock clock){this.users=users;this.resets=resets;this.sessions=sessions;this.passwords=passwords;this.tokens=tokens;this.events=events;this.email=email;this.clock=clock;}
 @Transactional(timeout=20) public void forgot(String address){
  var user=users.lockByEmail(AccountIdentifiers.email(address)).orElse(null);if(user==null || !user.isActive() || !user.isVerified())return;
  var now=clock.instant();String raw=tokens.generate();resets.invalidate(user.getId(),now);resets.create(user.getId(),tokens.hash(raw),now);
  email.send(user.getEmail(),"BridgeOps account recovery","A password reset was requested. Open the BridgeOps reset form and submit this one-use token in the form body before "+now.plusSeconds(900)+". Never put it in a URL or logs.\n\n"+raw+"\n\nIgnore this message if unexpected. Delivery and automatic retries are not guaranteed.");
  events.save(new IdentitySecurityEvent(user.getId(),null,"PASSWORD_RECOVERY_REQUESTED",now));
 }
 @Transactional(timeout=10) public void change(UUID userId,UUID family,String current,String replacement){
  var user=users.lockById(userId).orElseThrow(PasswordRecoveryService::rejected);
  if(!user.isActive() || !sessions.isActive(userId,family) || !passwords.matches(current,user.getPasswordHash()))throw rejected();
  String hash=hash(replacement);user.changePasswordHash(hash);resets.invalidate(userId,clock.instant());sessions.revokeAll(userId);events.save(new IdentitySecurityEvent(userId,null,"PASSWORD_CHANGED",clock.instant()));
 }
 @Transactional(timeout=10) public void reset(String raw,String replacement){
  if(!tokens.isWellFormed(raw))throw rejected();String hash=hash(replacement);var reference=resets.find(tokens.hash(raw),false).orElseThrow(PasswordRecoveryService::rejected);
  // Account -> proof -> families order serializes reset/change/forgot and existing session operations.
  var user=users.lockById(reference.userId()).orElseThrow(PasswordRecoveryService::rejected);
  var proof=resets.find(tokens.hash(raw),true).orElseThrow(PasswordRecoveryService::rejected);
  if(!user.isActive() || !user.isVerified() || proof.consumedAt()!=null || !resets.consume(proof.id(),clock.instant()))throw rejected();
  user.changePasswordHash(hash);resets.invalidate(user.getId(),clock.instant());sessions.revokeAll(user.getId());events.save(new IdentitySecurityEvent(user.getId(),null,"PASSWORD_RESET",clock.instant()));
 }
 private String hash(String value){try{return passwords.hash(value);}catch(IllegalArgumentException invalid){throw rejected();}}
 private static DomainFailure rejected(){return new DomainFailure(400,"PASSWORD_OPERATION_REJECTED");}
}
