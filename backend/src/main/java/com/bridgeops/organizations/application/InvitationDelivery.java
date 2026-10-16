package com.bridgeops.organizations.application;
import java.time.Instant;
public interface InvitationDelivery {void deliver(String recipient,String oneUseToken,Instant expiresAt);}
