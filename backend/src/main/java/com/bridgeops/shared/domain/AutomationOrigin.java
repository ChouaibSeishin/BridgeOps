package com.bridgeops.shared.domain;

import java.util.UUID;

/** Immutable accepted origin value only; it grants no authority to submit or dispatch work. */
public record AutomationOrigin(UUID principal,UUID principalCreator,UUID endpoint,long endpointRevision,UUID endpointAuthor){
    public AutomationOrigin{if(principal==null||principalCreator==null||endpoint==null||endpointRevision<0||endpointAuthor==null)throw new IllegalArgumentException("Invalid automation origin.");}
    public ActorReference actor(){return ActorReference.automation(principal,principalCreator,endpointAuthor);}
    @Override public String toString(){return "AutomationOrigin[REDACTED]";}
}
