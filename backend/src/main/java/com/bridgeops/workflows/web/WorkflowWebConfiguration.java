package com.bridgeops.workflows.web;

import com.bridgeops.workflows.domain.GraphDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;

@Configuration
@ConditionalOnProperty(name="bridgeops.auth.enabled", havingValue="true")
public class WorkflowWebConfiguration {
    @Bean FilterRegistrationBean<WorkflowRequestLimits> workflowBodyLimits(GraphDefinition graphs) {
        var registration = new FilterRegistrationBean<>(new WorkflowRequestLimits(graphs));
        // Spring Security's delegating filter runs first; no second servlet registration of this filter.
        registration.setOrder(Ordered.LOWEST_PRECEDENCE); registration.setName("workflowBodyLimits");
        return registration;
    }
}
