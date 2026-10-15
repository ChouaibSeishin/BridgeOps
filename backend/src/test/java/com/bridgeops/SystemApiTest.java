package com.bridgeops;

import com.bridgeops.shared.configuration.SecurityConfiguration;
import com.bridgeops.shared.web.CorrelationFilter;
import com.bridgeops.shared.web.SystemController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SystemController.class)
@Import({SecurityConfiguration.class, CorrelationFilter.class})
class SystemApiTest {
    @Autowired MockMvc mvc;

    @Test
    void publicInfoContainsOnlySafeFieldsAndServerCorrelationId() throws Exception {
        mvc.perform(get("/api/v1/system/info").header("X-Correlation-ID", "untrusted"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"name\":\"BridgeOps\",\"apiVersion\":\"v1\"}"))
                .andExpect(header().string("X-Correlation-ID", org.hamcrest.Matchers.matchesPattern("[0-9a-f-]{36}")));
    }

    @Test
    void unknownBusinessApiIsDeniedWithSafeError() throws Exception {
        mvc.perform(get("/api/v1/organizations"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.correlationId").isString())
                .andExpect(jsonPath("$.timestamp").isString())
                .andExpect(jsonPath("$.exception").doesNotExist());
    }

    @Test
    void mutationWithoutCsrfIsDeniedAndDoesNotCreateSession() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void docsArePrivateByDefault() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
    }
}
