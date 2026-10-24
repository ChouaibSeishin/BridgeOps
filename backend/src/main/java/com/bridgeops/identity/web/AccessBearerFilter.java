package com.bridgeops.identity.web;

import com.bridgeops.identity.application.*;
import com.bridgeops.shared.web.ApiErrors;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.filter.OncePerRequestFilter;

public final class AccessBearerFilter extends OncePerRequestFilter {
    private final AccessTokens tokens;
    private final PasswordSessionService sessions;
    public AccessBearerFilter(AccessTokens tokens, PasswordSessionService sessions) { this.tokens = tokens; this.sessions = sessions; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (com.bridgeops.shared.web.WebhookCallbackPath.matches(request)) { chain.doFilter(request,response); return; }
        var headers = Collections.list(request.getHeaders("Authorization"));
        if (!headers.isEmpty()) {
            try {
                if (headers.size() != 1 || !headers.getFirst().startsWith("Bearer ")) throw new org.springframework.security.oauth2.jwt.BadJwtException("Invalid credential.");
                var identity = tokens.verify(headers.getFirst().substring(7));
                if (!sessions.isActive(identity.userId(),identity.familyId())) throw new org.springframework.security.oauth2.jwt.BadJwtException("Inactive session.");
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(identity,null,List.of()));
                SecurityContextHolder.setContext(context);
            } catch (JwtException invalid) {
                ApiErrors.write(request,response,401,"AUTHENTICATION_REQUIRED","Authentication is required."); return;
            } catch (DataAccessException | TransactionException unavailable) {
                ApiErrors.write(request,response,503,"DEPENDENCY_UNAVAILABLE","Service is temporarily unavailable."); return;
            }
        }
        chain.doFilter(request,response);
    }
}
