package com.bridgeops.shared.configuration;

import com.bridgeops.shared.web.ApiErrors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfiguration {
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="bridgeops.auth.enabled", havingValue="false", matchIfMissing=true)
    SecurityFilterChain securityFilterChain(HttpSecurity http,
            @Value("${bridgeops.api-docs.enabled:false}") boolean docsEnabled) throws Exception {
        http.authorizeHttpRequests(authorize -> {
            authorize.requestMatchers(HttpMethod.GET, "/api/v1/system/info",
                    "/actuator/health/liveness", "/actuator/health/readiness").permitAll();
            if (docsEnabled) {
                authorize.requestMatchers(HttpMethod.GET, "/v3/api-docs", "/v3/api-docs/**",
                        "/swagger-ui.html", "/swagger-ui/**").permitAll();
            }
            authorize.anyRequest().denyAll();
        });
        http.httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(Customizer.withDefaults())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, failure) ->
                                ApiErrors.write(request, response, 401, "AUTHENTICATION_REQUIRED", "Authentication is required."))
                        .accessDeniedHandler((request, response, failure) ->
                                ApiErrors.write(request, response, 403, "ACCESS_DENIED", "This operation is not permitted.")));
        return http.build();
    }

    @Bean
    UserDetailsService noDefaultUsers() {
        // No generated default password or implicit account before identity is implemented.
        return username -> { throw new UsernameNotFoundException("Authentication is not configured."); };
    }
}
