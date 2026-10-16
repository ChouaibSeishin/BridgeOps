package com.bridgeops.shared.web;

public final class DomainFailure extends RuntimeException {
    private final int status;private final String code;
    public DomainFailure(int status,String code){super("Operation was not accepted.");this.status=status;this.code=code;}
    public int status(){return status;}public String code(){return code;}
}
