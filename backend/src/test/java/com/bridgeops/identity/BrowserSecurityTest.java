package com.bridgeops.identity;

import com.bridgeops.identity.application.RefreshTokens;
import com.bridgeops.identity.web.*;
import jakarta.servlet.http.Cookie;
import java.security.SecureRandom;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.assertj.core.api.Assertions.*;

class BrowserSecurityTest {
    @Test void productionAndExplicitLocalCookiesHaveDistinctNamesAndCorrectFlags() {
        var clock=Clock.fixed(Instant.parse("2026-10-15T12:00:00Z"),ZoneOffset.UTC);
        var production=new BrowserCookies(new BrowserPolicy("https://bridgeops.invalid",false),clock);
        var response=new MockHttpServletResponse(); production.refresh(response,"synthetic",clock.instant().plusSeconds(100)); production.clear(response);
        assertThat(response.getHeaders("Set-Cookie")).allSatisfy(value->{
            assertThat(value).contains("__Host-bridgeops-","Path=/","Secure","SameSite=Lax").doesNotContain("Domain=");
        });
        var local=new BrowserCookies(new BrowserPolicy("http://localhost:8088",true),clock);
        var localResponse=new MockHttpServletResponse(); local.refresh(localResponse,"synthetic",clock.instant().plusSeconds(100));
        assertThat(localResponse.getHeader("Set-Cookie")).contains("bridgeops-dev-refresh","HttpOnly","SameSite=Lax").doesNotContain("Secure");
        for(String origin:java.util.List.of("http://bridgeops.invalid","https://bridgeops.invalid/","https://bridgeops.invalid?query=1","https://user@bridgeops.invalid")) {
            assertThatThrownBy(()->new BrowserPolicy(origin,false)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(()->new BrowserPolicy("http://bridgeops.invalid",true)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void duplicateCookiesAreNotAcceptedAsCredentials() {
        var cookies=new BrowserCookies(new BrowserPolicy("https://bridgeops.invalid",false),Clock.systemUTC());
        var request=new MockHttpServletRequest(); request.setCookies(new Cookie("__Host-bridgeops-refresh","one"),new Cookie("__Host-bridgeops-refresh","two"));
        assertThat(cookies.refresh(request)).isNull();
    }
    @Test void csrfSignatureCannotBeForgedBySubmittingMatchingAttackerChosenCookieAndHeader() {
        byte[] key=new byte[32]; new SecureRandom().nextBytes(key);
        var clock=Clock.fixed(Instant.parse("2026-10-15T12:00:00Z"),ZoneOffset.UTC);
        var cookies=new BrowserCookies(new BrowserPolicy("https://bridgeops.invalid",false),clock);
        var csrf=new SignedCsrfTokens(key,cookies,new RefreshTokens(),clock);
        var request=new MockHttpServletRequest(); String token=csrf.generateToken(request).getToken();
        request.setCookies(new Cookie("__Host-bridgeops-csrf",token)); assertThat(csrf.loadToken(request)).isNotNull();
        String[] fields=token.split("\\."); fields[0]=new RefreshTokens().generate();
        request.setCookies(new Cookie("__Host-bridgeops-csrf",String.join(".",fields)));
        request.addHeader("X-CSRF-TOKEN",String.join(".",fields)); assertThat(csrf.loadToken(request)).isNull();
        assertThatThrownBy(()->new SignedCsrfTokens(new byte[8],cookies,new RefreshTokens(),clock)).isInstanceOf(IllegalArgumentException.class);
    }
}