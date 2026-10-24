package com.bridgeops.identity;

import com.bridgeops.shared.web.WebhookCallbackPath;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;

class WebhookCallbackPathTest {
    private static final String PATH="/api/v1/webhooks/0e9ef47c-86a9-47cc-bc1a-d1b0d1431df0";
    @Test void onlyExactRawPostRequestGetsTheSignatureBoundary(){
        assertThat(WebhookCallbackPath.matches(new MockHttpServletRequest("POST",PATH))).isTrue();
        for(String method:new String[]{"GET","HEAD","OPTIONS","PUT","DELETE","PATCH"})assertThat(WebhookCallbackPath.matches(new MockHttpServletRequest(method,PATH))).as(method).isFalse();
        for(String path:new String[]{PATH+"/",PATH+"/extra",PATH+";x=1",PATH.toUpperCase(),PATH.replace("0e","%30e"),PATH.replace("webhooks/","webhooks//"),PATH.replace("webhooks/","organizations/")})assertThat(WebhookCallbackPath.matches(new MockHttpServletRequest("POST",path))).as(path).isFalse();
        var forward=new MockHttpServletRequest("POST",PATH);forward.setDispatcherType(DispatcherType.FORWARD);assertThat(WebhookCallbackPath.matches(forward)).isFalse();
    }
}
