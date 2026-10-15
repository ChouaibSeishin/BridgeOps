package com.bridgeops.identity;

import com.bridgeops.identity.application.*;
import jakarta.servlet.http.Cookie;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"bridgeops.auth.enabled=true","bridgeops.auth.origin=https://bridgeops.invalid",
        "bridgeops.auth.issuer=bridgeops-test","bridgeops.auth.key-id=test-current","bridgeops.api-docs.enabled=true"})
@AutoConfigureMockMvc
@Testcontainers
@Import(AuthenticationHttpIT.TimeConfiguration.class)
class AuthenticationHttpIT {
    private static final String ORIGIN="https://bridgeops.invalid";
    private static final Instant NOW=Instant.parse("2026-10-15T12:00:00Z");
    private static final Path KEY_DIRECTORY=keys();
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer(DockerImageName.parse(
        "postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",postgres::getJdbcUrl); registry.add("spring.datasource.username",postgres::getUsername);
        registry.add("spring.datasource.password",postgres::getPassword);
        registry.add("bridgeops.auth.private-key",()->KEY_DIRECTORY.resolve("private.pem").toString());
        registry.add("bridgeops.auth.public-key",()->KEY_DIRECTORY.resolve("public.pem").toString());
        registry.add("bridgeops.auth.csrf-key",()->KEY_DIRECTORY.resolve("csrf.key").toString());
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountPasswords passwords;
    @Autowired TestClock clock;
    @Autowired AccessTokens accessTokens;
    @Autowired AuthenticationThrottle throttles;
    private UUID userId;
    private static String passwordHash;
    private record Browser(Cookie csrf,Cookie refresh,String access) {
        @Override public String toString() { return "Browser[REDACTED]"; }
    }
    @BeforeEach void fixture() {
        clock.now=NOW;
        jdbc.update("DELETE FROM authentication_throttles"); jdbc.update("DELETE FROM identity_security_events");
        jdbc.update("DELETE FROM refresh_sessions"); jdbc.update("DELETE FROM session_families"); jdbc.update("DELETE FROM users");
        if (passwordHash==null) passwordHash=passwords.hash("synthetic-http-fixture");
        userId=UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,email,normalized_email,password_hash,display_name,account_status,created_at) VALUES(?,?,?,?,?,'ACTIVE',?)",
            userId,"owner@example.invalid","owner@example.invalid",passwordHash,"Synthetic Owner",java.sql.Timestamp.from(NOW));
    }
    @Test void bootstrapAndLoginIssueOnlySafeDtoAndProductionHostCookies() throws Exception {
        Browser anonymous=bootstrap(null);
        Browser logged=login(anonymous);
        assertThat(logged.refresh().isHttpOnly()).isTrue(); assertThat(logged.refresh().getSecure()).isTrue();
        assertThat(logged.refresh().getPath()).isEqualTo("/"); assertThat(logged.refresh().getDomain()).isNull();
        assertThat(logged.refresh().getAttribute("SameSite")).isEqualTo("Lax");
        assertThat(logged.refresh().getMaxAge()).isEqualTo(604800);
        assertThat(logged.csrf().isHttpOnly()).isFalse(); assertThat(logged.csrf().getSecure()).isTrue();
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+logged.access())).andExpect(status().isOk())
            .andExpect(jsonPath("$.user.id").value(userId.toString())).andExpect(jsonPath("$.user.passwordHash").doesNotExist())
            .andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(get("/api/v1/organizations").header("Authorization","Bearer "+logged.access())).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refresh_sessions WHERE token_hash=?",Integer.class,logged.refresh().getValue())).isZero();
    }
    @Test void exactOriginIsRequiredBeforeLoginOrCookieMutation() throws Exception {
        Browser browser=bootstrap(null);
        for (String origin:List.of("null","https://bridgeops.invalid.evil","https://bridgeops.invalid/","http://bridgeops.invalid","https://bridgeops.invalid:443")) {
            mvc.perform(post("/api/v1/auth/login").header("Origin",origin).cookie(browser.csrf()).header("X-CSRF-TOKEN",browser.csrf().getValue())
                .contentType("application/json").content(loginJson())).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ORIGIN_REJECTED"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        }
        mvc.perform(post("/api/v1/auth/login").cookie(browser.csrf()).header("X-CSRF-TOKEN",browser.csrf().getValue())
                .contentType("application/json").content(loginJson())).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/login").header("Origin",ORIGIN,ORIGIN).cookie(browser.csrf()).header("X-CSRF-TOKEN",browser.csrf().getValue())
                .contentType("application/json").content(loginJson())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/auth/csrf").header("Origin","https://evil.invalid")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/auth/csrf").header("Sec-Fetch-Site","cross-site")).andExpect(status().isForbidden());
        mvc.perform(post(java.net.URI.create("/api/v1/%61uth/login")).cookie(browser.csrf()).header("X-CSRF-TOKEN",browser.csrf().getValue())
            .contentType("application/json").content(loginJson())).andExpect(status().isForbidden());
        assertThat(count("session_families")).isZero();
    }
    @Test void csrfHeaderAndSignedCookieAreBothRequired() throws Exception {
        Browser browser=bootstrap(null);
        mvc.perform(post("/api/v1/auth/login").header("Origin",ORIGIN).contentType("application/json").content(loginJson())).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/login").header("Origin",ORIGIN).cookie(browser.csrf()).contentType("application/json").content(loginJson())).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/login").header("Origin",ORIGIN).cookie(browser.csrf()).header("X-CSRF-TOKEN","wrong")
                .contentType("application/json").content(loginJson())).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/login").header("Origin",ORIGIN).cookie(browser.csrf()).param("_csrf",browser.csrf().getValue())
                .contentType("application/json").content(loginJson())).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/login").header("Origin",ORIGIN).cookie(browser.csrf()).header("X-CSRF-TOKEN",browser.csrf().getValue(),browser.csrf().getValue())
                .contentType("application/json").content(loginJson())).andExpect(status().isForbidden());
        assertThat(count("session_families")).isZero();
    }
    @Test void rotationRefreshesBothCookiesAndReplayRevokesAlreadyIssuedAccess() throws Exception {
        Browser first=login(bootstrap(null));
        MvcResult result=send("refresh",first).andExpect(status().isOk()).andExpect(jsonPath("$.refreshToken").doesNotExist()).andReturn();
        Browser next=browser(result);
        assertThat(next.refresh().getValue()).isNotEqualTo(first.refresh().getValue());
        assertThat(next.csrf().getValue()).isNotEqualTo(first.csrf().getValue());
        send("refresh",first).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+next.access())).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_security_events WHERE event_type='REFRESH_REUSE_DETECTED'",Integer.class)).isEqualTo(1);
    }
    @Test void anonymousOrOtherSessionCsrfCannotBeTransplanted() throws Exception {
        Browser anonymous=bootstrap(null), first=login(anonymous), second=login(bootstrap(null));
        send("refresh",new Browser(anonymous.csrf(),first.refresh(),null)).andExpect(status().isForbidden());
        send("refresh",new Browser(second.csrf(),first.refresh(),null)).andExpect(status().isForbidden());
        var forged=new Cookie("__Host-bridgeops-csrf",first.csrf().getValue().substring(0,60)+"x");
        send("refresh",new Browser(forged,first.refresh(),null)).andExpect(status().isForbidden());
        assertThat(count("refresh_sessions")).isEqualTo(2);
    }
    @Test void csrfExpiryAndSafeBootstrapRecovery() throws Exception {
        Browser browser=login(bootstrap(null)); clock.now=NOW.plusSeconds(1800);
        send("refresh",browser).andExpect(status().isForbidden());
        Browser recovered=bootstrap(browser.refresh());
        send("refresh",recovered).andExpect(status().isOk());
    }
    @Test void logoutAndLogoutAllInvalidateAccessAndClearCookieAttributes() throws Exception {
        Browser first=login(bootstrap(null));
        MvcResult logout=send("logout",first).andExpect(status().isNoContent()).andReturn();
        assertThat(logout.getResponse().getCookie("__Host-bridgeops-refresh").getMaxAge()).isZero();
        assertThat(logout.getResponse().getCookie("__Host-bridgeops-refresh").getSecure()).isTrue();
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+first.access())).andExpect(status().isUnauthorized());
        Browser second=login(bootstrap(null)), third=login(bootstrap(null));
        send("logout-all",new Browser(third.csrf(),third.refresh(),null)).andExpect(status().isUnauthorized());
        send("logout-all",third).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+second.access())).andExpect(status().isUnauthorized());
        send("refresh",second).andExpect(status().isUnauthorized());
    }
    @Test void credentialsErrorsDoNotEnumerateAccounts() throws Exception {
        Browser anonymous=bootstrap(null);
        for (String email:List.of("owner@example.invalid","missing@example.invalid")) {
            mvc.perform(post("/api/v1/auth/login").header("Origin",ORIGIN).cookie(anonymous.csrf()).header("X-CSRF-TOKEN",anonymous.csrf().getValue())
                .contentType("application/json").content("{\"email\":\""+email+"\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("Authentication was not accepted.")).andExpect(header().doesNotExist("Set-Cookie"));
        }
        jdbc.update("UPDATE users SET account_status='DISABLED'");
        mvc.perform(post("/api/v1/auth/login").header("Origin",ORIGIN).cookie(anonymous.csrf()).header("X-CSRF-TOKEN",anonymous.csrf().getValue())
            .contentType("application/json").content(loginJson())).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }
    @Test void loginThrottlesAreAccountBoundAndProxyHeadersCannotBypassThem() throws Exception {
        Browser anonymous=bootstrap(null);
        String hash=new RefreshTokens().hash("login-account:owner@example.invalid");
        jdbc.update("INSERT INTO authentication_throttles VALUES(?,10,?)",hash,java.sql.Timestamp.from(NOW.plusSeconds(900)));
        mvc.perform(post("/api/v1/auth/login").header("Origin",ORIGIN).header("X-Forwarded-For","1.2.3.4")
            .cookie(anonymous.csrf()).header("X-CSRF-TOKEN",anonymous.csrf().getValue()).contentType("application/json").content(loginJson()))
            .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After","900"));
        assertThat(count("session_families")).isZero(); clock.now=NOW.plusSeconds(900); login(anonymous);
    }
    @Test void peerThrottleIsSharedAndExpiryAllowsRecovery() throws Exception {
        String hash=new RefreshTokens().hash("peer:127.0.0.1");
        jdbc.update("INSERT INTO authentication_throttles VALUES(?,120,?)",hash,java.sql.Timestamp.from(NOW.plusSeconds(60)));
        mvc.perform(get("/api/v1/auth/csrf").header("X-Forwarded-For","5.6.7.8")).andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After","60"));
        clock.now=NOW.plusSeconds(60); bootstrap(null);
    }
    @Test void accessRequiresLiveAccountFamilyOwnershipAndStrictExpiry() throws Exception {
        Browser browser=login(bootstrap(null));
        var identity=accessTokens.verify(browser.access());
        var mismatched=accessTokens.issue(new com.bridgeops.identity.domain.SessionOutcome.Granted(UUID.randomUUID(),identity.familyId(),NOW.plusSeconds(600),"unused"));
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+mismatched.value())).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE users SET account_status='DISABLED'");
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+browser.access())).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE users SET account_status='ACTIVE'"); clock.now=NOW.plusSeconds(600);
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+browser.access())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").cookie(browser.refresh())).andExpect(status().isUnauthorized());
    }
    @Test void malformedRequestsAndUnknownSecurityFieldsHaveSafeErrors() throws Exception {
        Browser anonymous=bootstrap(null);
        for (String body:List.of("{}","{","{\"email\":\"owner@example.invalid\",\"password\":\"synthetic-http-fixture\",\"userId\":\"forged\"}")) {
            mvc.perform(post("/api/v1/auth/login").header("Origin",ORIGIN).cookie(anonymous.csrf()).header("X-CSRF-TOKEN",anonymous.csrf().getValue())
                .contentType("application/json").content(body)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.exception").doesNotExist());
        }
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer malformed")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer one","Bearer two")).andExpect(status().isUnauthorized());
        assertThat(count("session_families")).isZero();
    }
    @Test void corsIsNotEnabledAndPublicRegistrationStaysUnavailable() throws Exception {
        mvc.perform(options("/api/v1/auth/login").header("Origin","https://evil.invalid").header("Access-Control-Request-Method","POST"))
            .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(get("/api/v1/auth/register")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andExpect(jsonPath("$.paths['/api/v1/auth/login'].post").exists());
    }
    @Test void concurrentHttpRefreshHasOneSuccessAndOneReplayRejection() throws Exception {
        Browser browser=login(bootstrap(null)); var gate=new CountDownLatch(1);
        try (var pool=Executors.newFixedThreadPool(2)) {
            Callable<Integer> task=()->{gate.await(5,TimeUnit.SECONDS); return send("refresh",browser).andReturn().getResponse().getStatus();};
            var a=pool.submit(task); var b=pool.submit(task); gate.countDown();
            assertThat(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,401);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM session_families WHERE revoked_at IS NOT NULL",Integer.class)).isEqualTo(1);
    }
    @Test void databaseFailureDuringProtectedRequestFailsClosed() throws Exception {
        Browser browser=login(bootstrap(null));
        jdbc.execute("ALTER TABLE session_families RENAME TO unavailable_families");
        try { mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+browser.access())).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE")).andExpect(jsonPath("$.exception").doesNotExist()); }
        finally { jdbc.execute("ALTER TABLE unavailable_families RENAME TO session_families"); }
    }
    @Test void everyCookieMutationRequiresOriginAndCsrfWithoutRevokingOnForbiddenRequests() throws Exception {
        Browser browser=login(bootstrap(null));
        for(String route:List.of("refresh","logout","logout-all")) {
            mvc.perform(post("/api/v1/auth/"+route).cookie(browser.csrf(),browser.refresh()).header("X-CSRF-TOKEN",browser.csrf().getValue())
                .header("Authorization","Bearer "+browser.access())).andExpect(status().isForbidden()).andExpect(header().doesNotExist("Set-Cookie"));
            mvc.perform(post("/api/v1/auth/"+route).header("Origin",ORIGIN).cookie(browser.refresh()).header("Authorization","Bearer "+browser.access()))
                .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+browser.access())).andExpect(status().isOk());
    }
    @Test void unknownRefreshIsGenericAndExpiredFamilyCannotBeRenewed() throws Exception {
        Cookie unknown=new Cookie("__Host-bridgeops-refresh",new RefreshTokens().generate());
        send("refresh",bootstrap(unknown)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        Browser browser=login(bootstrap(null)); clock.now=NOW.plusSeconds(604800);
        send("refresh",bootstrap(browser.refresh())).andExpect(status().isUnauthorized());
        assertThat(count("refresh_sessions")).isEqualTo(1);
    }
    @Test void throttleHasAHardStorageBoundAndSerializesConcurrentAccountAttempts() throws Exception {
        var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(4)) {
            List<Future<Long>> results=new ArrayList<>();
            for(int i=0;i<12;i++) {
                String peer="peer-"+i;
                results.add(pool.submit(()->{gate.await(5,TimeUnit.SECONDS); return throttles.acquire("login",peer,"owner@example.invalid");}));
            }
            gate.countDown(); int permitted=0;
            for(var result:results) if(result.get(15,TimeUnit.SECONDS)==0) permitted++;
            assertThat(permitted).isEqualTo(10);
        }
        jdbc.update("DELETE FROM authentication_throttles");
        jdbc.update("INSERT INTO authentication_throttles SELECT md5(i::text)||md5(i::text),1,? FROM generate_series(1,10000) i",java.sql.Timestamp.from(NOW.plusSeconds(60)));
        mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isTooManyRequests());
        assertThat(count("authentication_throttles")).isEqualTo(10000);
        clock.now=NOW.plusSeconds(60); bootstrap(null); assertThat(count("authentication_throttles")).isEqualTo(2);
    }    private Browser bootstrap(Cookie refresh) throws Exception {
        var request=get("/api/v1/auth/csrf"); if(refresh!=null) request.cookie(refresh);
        var result=mvc.perform(request).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn();
        return new Browser(result.getResponse().getCookie("__Host-bridgeops-csrf"),refresh,null);
    }
    private Browser login(Browser browser) throws Exception {
        var request=post("/api/v1/auth/login").header("Origin",ORIGIN).cookie(browser.csrf()).header("X-CSRF-TOKEN",browser.csrf().getValue())
            .contentType("application/json").content(loginJson()); if(browser.refresh()!=null) request.cookie(browser.refresh());
        return browser(mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.refreshToken").doesNotExist())
            .andExpect(jsonPath("$.user.passwordHash").doesNotExist()).andReturn());
    }
    private ResultActions send(String route,Browser browser) throws Exception {
        var request=post("/api/v1/auth/"+route).header("Origin",ORIGIN).cookie(browser.csrf()).header("X-CSRF-TOKEN",browser.csrf().getValue());
        if(browser.refresh()!=null) request.cookie(browser.refresh()); if(browser.access()!=null) request.header("Authorization","Bearer "+browser.access());
        return mvc.perform(request);
    }
    private Browser browser(MvcResult result) throws Exception {
        String access=new ObjectMapper().readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("accessToken").asString();
        return new Browser(result.getResponse().getCookie("__Host-bridgeops-csrf"),result.getResponse().getCookie("__Host-bridgeops-refresh"),access);
    }
    private String loginJson() { return "{\"email\":\"owner@example.invalid\",\"password\":\"synthetic-http-fixture\"}"; }
    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class); }
    @AfterAll static void removeKeys() throws Exception {
        for(String file:List.of("private.pem","public.pem","csrf.key")) Files.deleteIfExists(KEY_DIRECTORY.resolve(file)); Files.deleteIfExists(KEY_DIRECTORY);
    }
    private static Path keys() {
        try {
            Path directory=Files.createTempDirectory("bridgeops-auth-tests");
            var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); var pair=generator.generateKeyPair();
            Files.writeString(directory.resolve("private.pem"),pem("PRIVATE KEY",pair.getPrivate().getEncoded()));
            Files.writeString(directory.resolve("public.pem"),pem("PUBLIC KEY",pair.getPublic().getEncoded()));
            byte[] bytes=new byte[32]; new SecureRandom().nextBytes(bytes); Files.write(directory.resolve("csrf.key"),bytes); return directory;
        } catch(Exception e) { throw new IllegalStateException("Cannot create disposable test keys."); }
    }
    private static String pem(String label,byte[] bytes) { return "-----BEGIN "+label+"-----\n"+Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(bytes)+"\n-----END "+label+"-----\n"; }
    static class TestClock extends Clock {
        volatile Instant now=NOW;
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return now;}
    }
    @TestConfiguration static class TimeConfiguration { @Bean @Primary TestClock httpTestClock(){return new TestClock();} }
}