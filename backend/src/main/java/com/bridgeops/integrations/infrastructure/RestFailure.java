package com.bridgeops.integrations.infrastructure;

/** Provider failures carry only a fixed classification/code, never an underlying credential-bearing cause. */
public final class RestFailure extends RuntimeException {
    public enum Kind { TRANSIENT,PERMANENT,AMBIGUOUS }
    private final Kind kind;private final String code;
    RestFailure(Kind kind,String code){super("Configured operation did not complete safely.");this.kind=kind;this.code=code;}
    public Kind kind(){return kind;}public String code(){return code;}
}
