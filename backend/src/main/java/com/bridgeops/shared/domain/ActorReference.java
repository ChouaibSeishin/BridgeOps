package com.bridgeops.shared.domain;

import java.util.*;

/** Identity representation only; application services must still authorize live tenant/operation context. */
public record ActorReference(Type type, UUID id, List<UUID> configuringHumans) {
    public enum Type { USER, AUTOMATION }
    public ActorReference {
        if(type==null||id==null||configuringHumans==null||configuringHumans.stream().anyMatch(Objects::isNull)
            ||configuringHumans.size()>2||type==Type.USER&&!configuringHumans.isEmpty()||type==Type.AUTOMATION&&configuringHumans.isEmpty())throw new IllegalArgumentException("Invalid actor reference.");
        configuringHumans=configuringHumans.stream().distinct().sorted(Comparator.comparing(UUID::toString)).toList();
    }
    public static ActorReference user(UUID user){return new ActorReference(Type.USER,user,List.of());}
    public static ActorReference automation(UUID principal,UUID principalConfigurer,UUID endpointConfigurer){return new ActorReference(Type.AUTOMATION,principal,Arrays.asList(principalConfigurer,endpointConfigurer));}
    public boolean excludes(UUID human){return human==null||type==Type.USER&&id.equals(human)||configuringHumans.contains(human);}
    @Override public String toString(){return "ActorReference["+type+"]";}
}
