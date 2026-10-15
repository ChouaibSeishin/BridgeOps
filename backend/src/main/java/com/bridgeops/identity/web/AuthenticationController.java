package com.bridgeops.identity.web;

import com.bridgeops.identity.application.*;
import com.bridgeops.identity.application.IdentityQueries.UserSummary;
import com.bridgeops.identity.domain.SessionOutcome;
import com.bridgeops.identity.domain.SessionOutcome.Granted;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import com.bridgeops.shared.web.ApiErrors;
import java.io.IOException;

@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnProperty(name="bridgeops.auth.enabled",havingValue="true")
public class AuthenticationController {
    public record LoginRequest(@NotBlank @Size(max=254) String email, @NotBlank @Size(max=256) String password) {
        @Override public String toString() { return "LoginRequest[REDACTED]"; }
    }
    public record AuthenticationResponse(String accessToken, String tokenType, Instant expiresAt, UserSummary user) {
        @Override public String toString() { return "AuthenticationResponse[REDACTED]"; }
    }
    public record CsrfResponse(String headerName, String token) { @Override public String toString() { return "CsrfResponse[REDACTED]"; } }
    public record MeResponse(UserSummary user, UUID familyId, Instant accessExpiresAt) { }
    private final PasswordSessionService sessions;
    private final AccessTokens access;
    private final IdentityQueries identities;
    private final BrowserCookies cookies;
    private final SignedCsrfTokens csrf;
    private final AuthenticationThrottle throttle;
    public AuthenticationController(PasswordSessionService sessions, AccessTokens access, IdentityQueries identities,
            BrowserCookies cookies, SignedCsrfTokens csrf, AuthenticationThrottle throttle) {
        this.sessions=sessions; this.access=access; this.identities=identities; this.cookies=cookies; this.csrf=csrf; this.throttle=throttle;
    }
    @GetMapping("/csrf")
    public CsrfResponse csrf(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!allow("csrf",request,response,"")) return null;
        // Resolve after throttling: unauthenticated bootstrap cannot allocate an unbounded server-side session.
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        return new CsrfResponse(token.getHeaderName(),token.getToken());
    }
    @PostMapping("/login")
    public AuthenticationResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!allow("login",request,response,body.email())) return null;
        SessionOutcome result = sessions.authenticate(body.email(),body.password());
        if (!(result instanceof Granted grant)) { reject(request,response); return null; }
        sessions.revokePresented(cookies.refresh(request));
        return granted(grant,request,response);
    }
    @PostMapping("/refresh")
    public AuthenticationResponse refresh(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!allow("refresh",request,response,"")) return null;
        SessionOutcome result = sessions.rotate(cookies.refresh(request));
        if (!(result instanceof Granted grant)) { cookies.clear(response); reject(request,response); return null; }
        return granted(grant,request,response);
    }
    @PostMapping("/logout")
    public void logout(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!allow("logout",request,response,"")) return;
        sessions.revokePresented(cookies.refresh(request)); cookies.clear(response); response.setStatus(204);
    }
    @PostMapping("/logout-all")
    public void logoutAll(@AuthenticationPrincipal AccessTokens.Identity identity, HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!allow("logout-all",request,response,"")) return;
        sessions.revokeAll(identity.userId()); cookies.clear(response); response.setStatus(204);
    }
    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal AccessTokens.Identity identity) {
        return new MeResponse(identities.summary(identity.userId()),identity.familyId(),identity.expiresAt());
    }
    private AuthenticationResponse granted(Granted grant, HttpServletRequest request, HttpServletResponse response) {
        var token = access.issue(grant);
        var user = identities.summary(grant.userId());
        cookies.refresh(response,grant.refreshToken(),grant.expiresAt());
        csrf.rotate(request,response,grant.refreshToken());
        return new AuthenticationResponse(token.value(),"Bearer",token.expiresAt(),user);
    }
    private boolean allow(String operation,HttpServletRequest request,HttpServletResponse response,String email) throws IOException {
        // Deliberately ignore X-Forwarded-For: the current edge shares a conservative peer limit.
        long retry = throttle.acquire(operation,request.getRemoteAddr(),email);
        if (retry == 0) return true;
        response.setHeader("Retry-After",Long.toString(retry));
        ApiErrors.write(request,response,429,"RATE_LIMITED","Too many authentication requests."); return false;
    }
    private void reject(HttpServletRequest request,HttpServletResponse response) throws IOException {
        ApiErrors.write(request,response,401,"INVALID_CREDENTIALS","Authentication was not accepted.");
    }
}