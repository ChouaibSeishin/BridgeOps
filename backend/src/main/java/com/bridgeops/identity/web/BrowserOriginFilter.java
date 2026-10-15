package com.bridgeops.identity.web;

import com.bridgeops.shared.web.ApiErrors;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

public final class BrowserOriginFilter extends OncePerRequestFilter {
    private final BrowserPolicy policy;
    public BrowserOriginFilter(BrowserPolicy policy) { this.policy = policy; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean unsafe = !Set.of("GET", "HEAD", "OPTIONS", "TRACE").contains(request.getMethod());
        String path = request.getServletPath().isEmpty()
                ? request.getRequestURI().substring(request.getContextPath().length()) : request.getServletPath();
        if (unsafe || path.startsWith("/api/v1/auth/")) {
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Pragma", "no-cache");
            var origins = Collections.list(request.getHeaders("Origin"));
            if ((unsafe && origins.size() != 1) || origins.size() > 1
                    || (!origins.isEmpty() && !policy.origin().equals(origins.getFirst()))
                    || "cross-site".equals(request.getHeader("Sec-Fetch-Site"))) {
                ApiErrors.write(request,response,403,"ORIGIN_REJECTED","Request origin is not permitted."); return;
            }
        }
        chain.doFilter(request,response);
    }
}