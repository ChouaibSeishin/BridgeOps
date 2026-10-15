package com.bridgeops.identity.web;

import java.net.URI;

public record BrowserPolicy(String origin, boolean localHttp) {
    public BrowserPolicy {
        URI uri = URI.create(origin);
        boolean loopback = "localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()) || "[::1]".equals(uri.getHost());
        if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !uri.getRawPath().isEmpty() || uri.getPort() > 65535 || uri.getPort() == 0
                || !("https".equals(uri.getScheme()) || (localHttp && loopback && "http".equals(uri.getScheme())))
                || (localHttp && !loopback)) throw new IllegalArgumentException("Configure one exact HTTPS public origin, or explicit loopback development HTTP.");
    }
    public String refreshName() { return localHttp ? "bridgeops-dev-refresh" : "__Host-bridgeops-refresh"; }
    public String csrfName() { return localHttp ? "bridgeops-dev-csrf" : "__Host-bridgeops-csrf"; }
    public boolean secure() { return !localHttp; }
}