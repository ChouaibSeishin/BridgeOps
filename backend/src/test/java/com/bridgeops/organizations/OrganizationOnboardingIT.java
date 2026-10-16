package com.bridgeops.organizations;

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
@Import(OrganizationOnboardingIT.TimeConfiguration.class)
class OrganizationOnboardingIT {
    private static final String ORIGIN="https://bridgeops.invalid";
    private static final Instant NOW=Instant.parse("2026-10-15T12:00:00Z");
    private static final Path KEY_DIRECTORY=keys();
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer(DockerImageName.parse(
        "postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873").asCompatibleSubstituteFor("postgres")).withInitScript("organization-runtime.sql");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",postgres::getJdbcUrl); registry.add("spring.datasource.username",()->"bridgeops_app");
        registry.add("spring.datasource.password",()->"synthetic-runtime-fixture");
        registry.add("spring.flyway.user",postgres::getUsername);registry.add("spring.flyway.password",postgres::getPassword);
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
    @Autowired PasswordSessionService sessions;
    @Autowired com.bridgeops.organizations.application.OrganizationService organizations;
    private UUID userId;
    private static String passwordHash;
    private record Browser(Cookie csrf,Cookie refresh,String access) {
        @Override public String toString() { return "Browser[REDACTED]"; }
    }
    @BeforeEach void fixture() {
        jdbc=new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));
        clock.now=NOW;
        jdbc.execute("TRUNCATE users,installation_state,authentication_throttles CASCADE");
        jdbc.update("INSERT INTO installation_state(singleton_id) VALUES(1)");
        if (passwordHash==null) passwordHash=passwords.hash("synthetic-http-fixture");
        userId=UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,email,normalized_email,password_hash,display_name,account_status,created_at) VALUES(?,?,?,?,?,'ACTIVE',?)",
            userId,"owner@example.invalid","owner@example.invalid",passwordHash,"Synthetic Owner",java.sql.Timestamp.from(NOW));
        jdbc.update("UPDATE users SET email_verified_at=? WHERE id=?",java.sql.Timestamp.from(NOW),userId);
        jdbc.update("INSERT INTO user_grants VALUES(?,\'organization.create\',?)",userId,java.sql.Timestamp.from(NOW));
    }
    @Test void createIsAtomicAndListIncludesOnlyOwnMemberships() throws Exception {
        Browser owner=login(bootstrap(null));UUID org=create(owner,"alpha-tenant");
        mvc.perform(get("/api/v1/organizations").header("Authorization","Bearer "+owner.access())).andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].id").value(org.toString()));
        mvc.perform(get("/api/v1/organizations/"+org+"/roles").header("Authorization","Bearer "+owner.access())).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(6));
        assertThat(count("roles")).isEqualTo(6);assertThat(count("organization_memberships")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT r.name FROM membership_roles m JOIN roles r ON r.organization_id=m.organization_id AND r.id=m.role_id",String.class)).isEqualTo("OWNER");
        mvc.perform(get("/api/v1/organizations/"+org+"/members").header("Authorization","Bearer "+owner.access())).andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].userId").value(userId.toString())).andExpect(jsonPath("$.items[0].roles[0].name").value("OWNER"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM organization_audit_events WHERE action='ORGANIZATION_CREATED'",Integer.class)).isEqualTo(1);
    }
    @Test void creationRequiresCurrentAccountGrantAndUnrelatedRoutesStayDenied() throws Exception {
        Browser owner=login(bootstrap(null));
        jdbc.update("DELETE FROM user_grants WHERE user_id=?",userId);
        createRequest(owner,"forbidden-tenant").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        assertThat(count("organizations")).isZero();
        mvc.perform(get("/api/v1/workflows").header("Authorization","Bearer "+owner.access())).andExpect(status().isForbidden());
    }
    @Test void foreignOrganizationAndMembershipIdsAreConcealed() throws Exception {
        Browser owner=login(bootstrap(null));UUID alpha=create(owner,"alpha-tenant");
        Browser other=other();UUID beta=create(other,"beta-tenant");
        mvc.perform(get("/api/v1/organizations").header("Authorization","Bearer "+other.access())).andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].id").value(beta.toString()));
        for(String path:List.of("/api/v1/organizations/"+alpha,"/api/v1/organizations/"+alpha+"/members","/api/v1/organizations/"+alpha+"/roles","/api/v1/organizations/"+UUID.randomUUID()))
            mvc.perform(get(path).header("Authorization","Bearer "+other.access())).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
    @Test void compositeForeignKeysRejectCrossTenantRoleAssignments() throws Exception {
        UUID alpha=create(login(bootstrap(null)),"alpha-tenant"),beta=create(other(),"beta-tenant");
        UUID member=jdbc.queryForObject("SELECT id FROM organization_memberships WHERE organization_id=?",UUID.class,alpha);
        UUID foreignRole=jdbc.queryForObject("SELECT id FROM roles WHERE organization_id=? AND name='VIEWER'",UUID.class,beta);
        assertThatThrownBy(()->jdbc.update("INSERT INTO membership_roles VALUES(?,?,?)",alpha,member,foreignRole)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("INSERT INTO organization_memberships(id,organization_id,user_id,status,joined_at) VALUES(?,?,?,'ACTIVE',?)",UUID.randomUUID(),alpha,userId,java.sql.Timestamp.from(NOW)))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void unknownAuthorityFieldsInvalidNamesAndPaginationAreRejected() throws Exception {
        Browser owner=login(bootstrap(null));
        for(String body:List.of("{\"name\":\"Tenant\",\"slug\":\"valid-tenant\",\"userId\":\"forged\"}","{\"name\":\"Tenant\",\"slug\":\"valid-tenant\",\"role\":\"OWNER\"}","{\"name\":\"Tenant\",\"slug\":\"BAD!\"}"))
            authorizedPost(owner,"/api/v1/organizations",body).andExpect(status().isBadRequest());
        for(String query:List.of("?page=-1","?size=101","?size=bad"))mvc.perform(get("/api/v1/organizations"+query).header("Authorization","Bearer "+owner.access())).andExpect(status().isBadRequest());
        assertThat(count("organizations")).isZero();
    }
    @Test void originCsrfAndLiveAccountAreRequiredForCreation() throws Exception {
        Browser owner=login(bootstrap(null));
        mvc.perform(post("/api/v1/organizations").header("Authorization","Bearer "+owner.access()).contentType("application/json").content("{\"name\":\"Tenant\",\"slug\":\"valid-tenant\"}"))
            .andExpect(status().isForbidden());
        jdbc.update("UPDATE users SET account_status='DISABLED' WHERE id=?",userId);
        createRequest(owner,"disabled-tenant").andExpect(status().isUnauthorized());
        assertThat(count("organizations")).isZero();
    }
    @Test void creationFailureRollsBackOrganizationRolesAndOwnerMembership() throws Exception {
        Browser owner=login(bootstrap(null));
        jdbc.execute("CREATE FUNCTION reject_org_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic rollback'; END $$");
        jdbc.execute("CREATE TRIGGER reject_org_audit BEFORE INSERT ON organization_audit_events FOR EACH ROW EXECUTE FUNCTION reject_org_audit()");
        try{createRequest(owner,"rollback-tenant").andExpect(status().isServiceUnavailable());}
        finally{jdbc.execute("DROP TRIGGER reject_org_audit ON organization_audit_events");jdbc.execute("DROP FUNCTION reject_org_audit()");}
        assertThat(count("organizations")).isZero();assertThat(count("roles")).isZero();assertThat(count("organization_memberships")).isZero();
    }
    @Test void concurrentDuplicateSlugCreatesOneCompleteOrganization() throws Exception {
        Browser owner=login(bootstrap(null));var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            Callable<Integer> task=()->{gate.await();return createRequest(owner,"unique-tenant").andReturn().getResponse().getStatus();};
            var a=pool.submit(task);var b=pool.submit(task);gate.countDown();assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(201,409);
        }
        assertThat(count("organizations")).isEqualTo(1);assertThat(count("roles")).isEqualTo(6);assertThat(count("organization_memberships")).isEqualTo(1);
    }
    @Test void serviceEntryPointsRejectForgedFamiliesAndTenantPermissionsAreLive() throws Exception {
        assertThatThrownBy(()->organizations.create(userId,UUID.randomUUID(),"Tenant","forged-tenant"))
            .isInstanceOf(com.bridgeops.shared.web.DomainFailure.class).extracting(failure->((com.bridgeops.shared.web.DomainFailure)failure).status()).isEqualTo(401);
        Browser owner=login(bootstrap(null));UUID org=create(owner,"live-tenant");
        jdbc.update("DELETE FROM role_permissions WHERE organization_id=? AND permission_code='organization.read'",org);
        mvc.perform(get("/api/v1/organizations/"+org).header("Authorization","Bearer "+owner.access())).andExpect(status().isForbidden());
        jdbc.update("UPDATE organization_memberships SET status='SUSPENDED' WHERE organization_id=? AND user_id=?",org,userId);
        mvc.perform(get("/api/v1/organizations/"+org+"/members").header("Authorization","Bearer "+owner.access())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/organizations").header("Authorization","Bearer "+owner.access())).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
    }
    @Test void lastOwnerCannotBeDemotedSuspendedOrRemoved() throws Exception {
        Browser owner=login(bootstrap(null));UUID org=create(owner,"owner-tenant"),member=memberId(org,userId);
        patchMember(owner,org,member,0,"ACTIVE",List.of(roleId(org,"VIEWER"))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LAST_OWNER"));
        patchMember(owner,org,member,0,"SUSPENDED",List.of(roleId(org,"OWNER"))).andExpect(status().isConflict());
        deleteMember(owner,org,member,"\"0\"").andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT revision FROM organization_memberships WHERE id=?",Long.class,member)).isZero();
    }
    @Test void memberChangesRequireCurrentPermissionAndRevision() throws Exception {
        Browser owner=login(bootstrap(null));UUID org=create(owner,"member-tenant");Browser viewer=other();UUID member=addMember(org,accessTokens.verify(viewer.access()).userId(),"VIEWER");
        patchMember(viewer,org,member,0,"ACTIVE",List.of(roleId(org,"OWNER"))).andExpect(status().isForbidden());
        patchMember(owner,org,member,0,"ACTIVE",List.of(roleId(org,"BUILDER"))).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1)).andExpect(jsonPath("$.roles[0].name").value("BUILDER"));
        patchMember(owner,org,member,0,"ACTIVE",List.of(roleId(org,"VIEWER"))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_REVISION"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM organization_audit_events WHERE action='MEMBERSHIP_CHANGED'",Integer.class)).isEqualTo(1);
    }
    @Test void administratorsCannotEscalateOrModifyPrivilegedMembers() throws Exception {
        Browser owner=login(bootstrap(null));UUID org=create(owner,"admin-tenant");Browser admin=other();UUID adminMember=addMember(org,accessTokens.verify(admin.access()).userId(),"ADMIN");
        patchMember(admin,org,adminMember,0,"ACTIVE",List.of(roleId(org,"OWNER"))).andExpect(status().isForbidden());
        patchMember(admin,org,memberId(org,userId),0,"ACTIVE",List.of(roleId(org,"VIEWER"))).andExpect(status().isForbidden());
        UUID viewer=fixtureUser("viewer@example.invalid"),target=addMember(org,viewer,"VIEWER");
        patchMember(admin,org,target,0,"ACTIVE",List.of(roleId(org,"ADMIN"))).andExpect(status().isForbidden());
        patchMember(admin,org,target,0,"ACTIVE",List.of(roleId(org,"APPROVER"))).andExpect(status().isForbidden());
        patchMember(admin,org,target,0,"ACTIVE",List.of(roleId(org,"BUILDER"))).andExpect(status().isOk());
    }
    @Test void foreignMembershipAndRoleIdentifiersCannotCrossTenants() throws Exception {
        Browser owner=login(bootstrap(null));UUID org=create(owner,"alpha-tenant");Browser other=other();UUID foreign=create(other,"beta-tenant");
        UUID target=memberId(foreign,accessTokens.verify(other.access()).userId());
        patchMember(owner,org,target,0,"ACTIVE",List.of(roleId(org,"VIEWER"))).andExpect(status().isNotFound());
        patchMember(owner,org,memberId(org,userId),0,"ACTIVE",List.of(roleId(foreign,"OWNER"))).andExpect(status().isNotFound());
        deleteMember(owner,org,target,"\"0\"").andExpect(status().isNotFound());
    }
    @Test void concurrentOwnerDemotionsLeaveOneActiveOwner() throws Exception {
        Browser first=login(bootstrap(null));UUID org=create(first,"concurrent-tenant"),a=memberId(org,userId);Browser second=other();UUID b=addMember(org,accessTokens.verify(second.access()).userId(),"OWNER"),viewer=roleId(org,"VIEWER");
        var gate=new CountDownLatch(1);try(var pool=Executors.newFixedThreadPool(2)){
            var left=pool.submit(()->{gate.await();return patchMember(first,org,a,0,"ACTIVE",List.of(viewer)).andReturn().getResponse().getStatus();});
            var right=pool.submit(()->{gate.await();return patchMember(second,org,b,0,"ACTIVE",List.of(viewer)).andReturn().getResponse().getStatus();});gate.countDown();
            assertThat(List.of(left.get(20,TimeUnit.SECONDS),right.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM membership_roles m JOIN roles r ON r.organization_id=m.organization_id AND r.id=m.role_id WHERE m.organization_id=? AND r.name='OWNER'",Integer.class,org)).isEqualTo(1);
    }
    @Test void disabledOwnersDoNotMakeLastOwnerRemovalSafe() throws Exception {
        Browser owner=login(bootstrap(null));UUID org=create(owner,"disabled-owner-tenant");Browser second=other();UUID otherId=accessTokens.verify(second.access()).userId();addMember(org,otherId,"OWNER");jdbc.update("UPDATE users SET account_status='DISABLED' WHERE id=?",otherId);
        patchMember(owner,org,memberId(org,userId),0,"ACTIVE",List.of(roleId(org,"VIEWER"))).andExpect(status().isConflict());
    }
    @Test void removalRevokesTenantAccessWithoutDisablingIdentity() throws Exception {
        Browser owner=login(bootstrap(null));UUID org=create(owner,"remove-tenant");Browser viewer=other();UUID target=addMember(org,accessTokens.verify(viewer.access()).userId(),"VIEWER");
        deleteMember(owner,org,target,"\"0\"").andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/organizations/"+org).header("Authorization","Bearer "+viewer.access())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+viewer.access())).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT status FROM organization_memberships WHERE id=?",String.class,target)).isEqualTo("REMOVED");
    }
    @Test void missingOrMalformedRevisionsAndUnknownAuthorityFieldsFailSafely() throws Exception {
        Browser owner=login(bootstrap(null));UUID org=create(owner,"revision-tenant"),target=memberId(org,userId);
        deleteMember(owner,org,target,"*").andExpect(status().isBadRequest());
        deleteMember(owner,org,target,"\"999\"").andExpect(status().isConflict());
        var request=patch("/api/v1/organizations/"+org+"/members/"+target).header("Authorization","Bearer "+owner.access()).header("Origin",ORIGIN).cookie(owner.csrf(),owner.refresh()).header("X-CSRF-TOKEN",owner.csrf().getValue())
            .contentType("application/json").content("{\"expectedRevision\":0,\"status\":\"ACTIVE\",\"roleIds\":[],\"permission\":\"forged\"}");
        mvc.perform(request).andExpect(status().isBadRequest());
    }
    @Test void failedMemberAuditRollsBackRolesAndRevision() throws Exception {
        Browser owner=login(bootstrap(null));UUID org=create(owner,"audit-tenant"),target=addMember(org,fixtureUser("member@example.invalid"),"VIEWER");
        jdbc.execute("CREATE FUNCTION reject_member_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic rollback'; END $$");
        jdbc.execute("CREATE TRIGGER reject_member_audit BEFORE INSERT ON organization_audit_events FOR EACH ROW EXECUTE FUNCTION reject_member_audit()");
        try{patchMember(owner,org,target,0,"ACTIVE",List.of(roleId(org,"BUILDER"))).andExpect(status().isServiceUnavailable());}
        finally{jdbc.execute("DROP TRIGGER reject_member_audit ON organization_audit_events");jdbc.execute("DROP FUNCTION reject_member_audit()");}
        assertThat(jdbc.queryForObject("SELECT revision FROM organization_memberships WHERE id=?",Long.class,target)).isZero();
        assertThat(jdbc.queryForObject("SELECT r.name FROM roles r JOIN membership_roles m ON m.organization_id=r.organization_id AND m.role_id=r.id WHERE m.membership_id=?",String.class,target)).isEqualTo("VIEWER");
    }
    private UUID fixtureUser(String email){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO users(id,email,normalized_email,password_hash,display_name,account_status,created_at,email_verified_at) VALUES(?,?,?,?,?,'ACTIVE',?,?)",id,email,email,passwordHash,"Synthetic Member",java.sql.Timestamp.from(NOW),java.sql.Timestamp.from(NOW));return id;}
    private UUID addMember(UUID org,UUID user,String role){UUID member=UUID.randomUUID();jdbc.update("INSERT INTO organization_memberships(id,organization_id,user_id,status,joined_at) VALUES(?,?,?,'ACTIVE',?)",member,org,user,java.sql.Timestamp.from(NOW));jdbc.update("INSERT INTO membership_roles VALUES(?,?,?)",org,member,roleId(org,role));return member;}
    private UUID roleId(UUID org,String name){return jdbc.queryForObject("SELECT id FROM roles WHERE organization_id=? AND name=?",UUID.class,org,name);}
    private UUID memberId(UUID org,UUID user){return jdbc.queryForObject("SELECT id FROM organization_memberships WHERE organization_id=? AND user_id=?",UUID.class,org,user);}
    private ResultActions patchMember(Browser actor,UUID org,UUID member,long revision,String state,List<UUID> roles)throws Exception{
        return mvc.perform(patch("/api/v1/organizations/"+org+"/members/"+member).header("Authorization","Bearer "+actor.access()).header("Origin",ORIGIN).cookie(actor.csrf(),actor.refresh()).header("X-CSRF-TOKEN",actor.csrf().getValue())
            .contentType("application/json").content(new ObjectMapper().writeValueAsString(Map.of("expectedRevision",revision,"status",state,"roleIds",roles))));
    }
    private ResultActions deleteMember(Browser actor,UUID org,UUID member,String revision)throws Exception{
        return mvc.perform(delete("/api/v1/organizations/"+org+"/members/"+member).header("If-Match",revision).header("Authorization","Bearer "+actor.access()).header("Origin",ORIGIN).cookie(actor.csrf(),actor.refresh()).header("X-CSRF-TOKEN",actor.csrf().getValue()));
    }
    private ResultActions authorizedPost(Browser browser,String path,String body) throws Exception {
        return mvc.perform(post(path).header("Authorization","Bearer "+browser.access()).header("Origin",ORIGIN).cookie(browser.csrf(),browser.refresh())
            .header("X-CSRF-TOKEN",browser.csrf().getValue()).contentType("application/json").content(body));
    }
    private ResultActions createRequest(Browser browser,String slug)throws Exception{return authorizedPost(browser,"/api/v1/organizations","{\"name\":\"Synthetic Tenant\",\"slug\":\""+slug+"\"}");}
    private UUID create(Browser browser,String slug)throws Exception{
        var result=createRequest(browser,slug).andExpect(status().isCreated()).andExpect(header().string("Location",org.hamcrest.Matchers.startsWith("/api/v1/organizations/"))).andReturn();
        return UUID.fromString(new ObjectMapper().readTree(result.getResponse().getContentAsString()).path("id").asString());
    }
    private Browser other()throws Exception{
        UUID id=UUID.randomUUID();jdbc.update("INSERT INTO users(id,email,normalized_email,password_hash,display_name,account_status,created_at,email_verified_at) VALUES(?,?,?,?,?,'ACTIVE',?,?)",
            id,"other@example.invalid","other@example.invalid",passwordHash,"Other",java.sql.Timestamp.from(NOW),java.sql.Timestamp.from(NOW));
        jdbc.update("INSERT INTO user_grants VALUES(?,'organization.create',?)",id,java.sql.Timestamp.from(NOW));
        var grant=(com.bridgeops.identity.domain.SessionOutcome.Granted)sessions.authenticate("other@example.invalid","synthetic-http-fixture");
        var refresh=new Cookie("__Host-bridgeops-refresh",grant.refreshToken());var anonymous=bootstrap(refresh);
        return new Browser(anonymous.csrf(),refresh,accessTokens.issue(grant).value());
    }
    private Browser bootstrap(Cookie refresh) throws Exception {
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