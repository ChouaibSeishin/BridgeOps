package com.bridgeops.identity.web;

import jakarta.servlet.http.*;
import java.time.*;
import java.util.Arrays;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

public final class BrowserCookies {
    private final BrowserPolicy policy;
    private final Clock clock;
    public BrowserCookies(BrowserPolicy policy, Clock clock) { this.policy = policy; this.clock = clock; }
    public String refresh(HttpServletRequest request) { return read(request, policy.refreshName()); }
    public String csrf(HttpServletRequest request) { return read(request, policy.csrfName()); }
    private String read(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        var matches = Arrays.stream(request.getCookies()).filter(cookie -> name.equals(cookie.getName())).toList();
        return matches.size() == 1 ? matches.getFirst().getValue() : null;
    }
    public void refresh(HttpServletResponse response, String value, Instant expires) {
        write(response, policy.refreshName(), value, true, Math.max(0, Duration.between(clock.instant(), expires).getSeconds()));
    }
    public void csrf(HttpServletResponse response, String value, long seconds) { write(response, policy.csrfName(), value, false, seconds); }
    public void clear(HttpServletResponse response) {
        write(response, policy.refreshName(), "", true, 0); write(response, policy.csrfName(), "", false, 0);
    }
    private void write(HttpServletResponse response, String name, String value, boolean httpOnly, long seconds) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(name, value).path("/").secure(policy.secure())
                .httpOnly(httpOnly).sameSite("Lax").maxAge(seconds).build().toString());
    }
}