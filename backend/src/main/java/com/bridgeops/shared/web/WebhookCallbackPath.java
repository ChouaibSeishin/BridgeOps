package com.bridgeops.shared.web;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;

/** One exact raw path/method is signed instead of cookie-authenticated. All other paths retain browser controls. */
public final class WebhookCallbackPath {
    private WebhookCallbackPath(){}
    public static boolean matches(HttpServletRequest request){
        return request.getDispatcherType()==DispatcherType.REQUEST&&"POST".equals(request.getMethod())
            &&request.getRequestURI().matches("/api/v1/webhooks/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
}
