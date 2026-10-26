package com.bridgeops.integrations.infrastructure;

/** Provider failures carry only a fixed classification/code, never an underlying credential-bearing cause. */
public final class RestFailure extends RuntimeException {
    public enum Kind { TRANSIENT,PERMANENT,AMBIGUOUS }
    private final Kind kind;private final String code;private final java.time.Duration retryAfter;
    RestFailure(Kind kind,String code){this(kind,code,null);}
    RestFailure(Kind kind,String code,java.time.Duration retryAfter){super("Configured operation did not complete safely.");this.kind=kind;this.code=code;this.retryAfter=kind==Kind.TRANSIENT&&retryAfter!=null&&!retryAfter.isNegative()?(retryAfter.compareTo(java.time.Duration.ofSeconds(60))>0?java.time.Duration.ofSeconds(60):retryAfter):null;}
    public Kind kind(){return kind;}public String code(){return code;}public java.time.Duration retryAfter(){return retryAfter;}
}
