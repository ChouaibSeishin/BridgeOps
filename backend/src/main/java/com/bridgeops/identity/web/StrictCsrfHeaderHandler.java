package com.bridgeops.identity.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import org.springframework.security.web.csrf.*;

public final class StrictCsrfHeaderHandler extends CsrfTokenRequestAttributeHandler {
    @Override public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken token) {
        var values = Collections.list(request.getHeaders(token.getHeaderName()));
        return values.size() == 1 ? values.getFirst() : null;
    }
}