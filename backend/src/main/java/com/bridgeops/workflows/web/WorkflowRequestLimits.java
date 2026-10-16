package com.bridgeops.workflows.web;

import com.bridgeops.shared.web.ApiErrors;
import com.bridgeops.workflows.domain.GraphDefinition;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.springframework.web.filter.OncePerRequestFilter;

/** Applies after security; bounds even chunked direct-backend bodies before MVC binding. */
public class WorkflowRequestLimits extends OncePerRequestFilter {
    private static final int MAX_REQUEST = 256 * 1024;
    private final GraphDefinition graphs;
    public WorkflowRequestLimits(GraphDefinition graphs) { this.graphs = graphs; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().matches("/api/v1/organizations/[^/]+/workflows(?:/.*)?") || !(request.getMethod().equals("POST") || request.getMethod().equals("PATCH"));
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        if (request.getContentLengthLong() > MAX_REQUEST) { tooLarge(request, response); return; }
        byte[] bytes = request.getInputStream().readNBytes(MAX_REQUEST + 1);
        if (bytes.length > MAX_REQUEST) { tooLarge(request, response); return; }
        try {
            var body = graphs.checkRequest(bytes);
            if (body != null && body.isObject()) {
                for (String field : java.util.List.of("name", "description"))
                    if (body.has(field) && !body.get(field).isString()) throw new IllegalArgumentException("String metadata required.");
                var revision = body.get("expectedRevision");
                if (revision != null && (!revision.isIntegralNumber() || revision.bigIntegerValue().signum() < 0
                    || revision.bigIntegerValue().compareTo(java.math.BigInteger.valueOf(Long.MAX_VALUE)) > 0))
                    throw new IllegalArgumentException("An exact nonnegative revision is required.");
            }
        }
        catch (RuntimeException invalid) { ApiErrors.write(request, response, 400, "INVALID_REQUEST", "Request is invalid."); return; }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public int getContentLength() { return bytes.length; }
            @Override public long getContentLengthLong() { return bytes.length; }
            @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)); }
            @Override public ServletInputStream getInputStream() {
                var input = new ByteArrayInputStream(bytes);
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public int read(byte[] target, int offset, int length) { return input.read(target, offset, length); }
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous JSON endpoint."); }
                };
            }
        }, response);
    }
    private void tooLarge(HttpServletRequest request, HttpServletResponse response) throws IOException { ApiErrors.write(request, response, 413, "REQUEST_TOO_LARGE", "Request exceeds the size limit."); }
}
