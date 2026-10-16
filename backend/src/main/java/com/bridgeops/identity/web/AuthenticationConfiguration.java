package com.bridgeops.identity.web;

import com.bridgeops.identity.application.*;
import com.bridgeops.shared.web.ApiErrors;
import java.nio.file.*;
import java.security.interfaces.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.*;

@Configuration
@ConditionalOnProperty(name="bridgeops.auth.enabled", havingValue="true")
public class AuthenticationConfiguration {
    @Bean BrowserPolicy browserPolicy(@Value("${bridgeops.auth.origin}") String origin,
            @Value("${bridgeops.auth.local-http:false}") boolean localHttp) { return new BrowserPolicy(origin,localHttp); }
    @Bean BrowserCookies browserCookies(BrowserPolicy policy, Clock clock) { return new BrowserCookies(policy,clock); }
    @Bean AccessTokens accessTokens(@Value("${bridgeops.auth.private-key}") String privatePath,
            @Value("${bridgeops.auth.public-key}") String publicPath, @Value("${bridgeops.auth.key-id}") String keyId,
            @Value("${bridgeops.auth.issuer}") String issuer, @Value("${bridgeops.auth.audience:bridgeops-browser}") String audience,
            @Value("${bridgeops.auth.previous-public-key:}") String previousPath,
            @Value("${bridgeops.auth.previous-key-id:}") String previousId,
            @Value("${bridgeops.auth.previous-accept-until:}") String previousUntil, Clock clock) {
        try (var privateInput = Files.newInputStream(Path.of(privatePath)); var publicInput = Files.newInputStream(Path.of(publicPath))) {
            var privateKey = RsaKeyConverters.pkcs8().convert(privateInput);
            var publicKey = RsaKeyConverters.x509().convert(publicInput);
            Map<String,AccessTokens.PreviousKey> previous = Map.of();
            if (!previousPath.isBlank() || !previousId.isBlank() || !previousUntil.isBlank()) {
                try (var previousInput = Files.newInputStream(Path.of(previousPath))) {
                    previous = Map.of(previousId,new AccessTokens.PreviousKey(RsaKeyConverters.x509().convert(previousInput),Instant.parse(previousUntil)));
                }
            }
            return new AccessTokens(keyId, publicKey, privateKey,issuer,audience,previous,clock);
        } catch (Exception invalid) { throw new IllegalStateException("Access signing configuration is unavailable or invalid."); }
    }
    @Bean SignedCsrfTokens signedCsrfTokens(@Value("${bridgeops.auth.csrf-key}") String path, BrowserCookies cookies, RefreshTokens random, Clock clock) {
        try { return new SignedCsrfTokens(Files.readAllBytes(Path.of(path)),cookies,random,clock); }
        catch (Exception invalid) { throw new IllegalStateException("CSRF signing configuration is unavailable or invalid."); }
    }
    @Bean SecurityFilterChain authenticatedHttp(HttpSecurity http, BrowserPolicy policy, AccessTokens access,
            PasswordSessionService sessions, SignedCsrfTokens csrf, @Value("${bridgeops.api-docs.enabled:false}") boolean docsEnabled) throws Exception {
        http.authorizeHttpRequests(authorize -> {
            authorize.requestMatchers(HttpMethod.GET,"/api/v1/system/info","/actuator/health/liveness","/actuator/health/readiness","/api/v1/auth/csrf").permitAll();
            authorize.requestMatchers(HttpMethod.POST,"/api/v1/auth/login","/api/v1/auth/refresh","/api/v1/auth/logout").permitAll();
            authorize.requestMatchers(HttpMethod.GET,"/api/v1/auth/me").authenticated();
            authorize.requestMatchers(HttpMethod.POST,"/api/v1/auth/logout-all").authenticated();
            if (docsEnabled) authorize.requestMatchers(HttpMethod.GET,"/v3/api-docs","/v3/api-docs/**","/swagger-ui.html","/swagger-ui/**").permitAll();
            authorize.requestMatchers(HttpMethod.GET,"/api/v1/organizations","/api/v1/organizations/*","/api/v1/organizations/*/members","/api/v1/organizations/*/roles").authenticated();
            authorize.requestMatchers(HttpMethod.POST,"/api/v1/organizations").authenticated();
            authorize.anyRequest().denyAll();
        });
        http.httpBasic(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable).cors(AbstractHttpConfigurer::disable).requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(config -> config.csrfTokenRepository(csrf).csrfTokenRequestHandler(new StrictCsrfHeaderHandler()))
                .addFilterBefore(new BrowserOriginFilter(policy),CsrfFilter.class)
                .addFilterAfter(new AccessBearerFilter(access,sessions),CsrfFilter.class)
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request,response,failure) ->
                        ApiErrors.write(request,response,401,"AUTHENTICATION_REQUIRED","Authentication is required."))
                    .accessDeniedHandler((request,response,failure) -> ApiErrors.write(request,response,403,
                        failure instanceof CsrfException ? "CSRF_REJECTED" : "ACCESS_DENIED","This operation is not permitted.")));
        return http.build();
    }
}