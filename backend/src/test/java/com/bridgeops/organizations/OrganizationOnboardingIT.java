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
    private static final Instant NOW=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
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
    @Autowired TestInbox inbox;
    @Autowired javax.sql.DataSource runtimeSource;
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
        inbox.messages.clear();inbox.fail=false;
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
    @Test void browserCapabilityAndTenantContextReflectCurrentAuthority() throws Exception {
        Browser owner=login(bootstrap(null));UUID org=create(owner,"browser-context");
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+owner.access())).andExpect(status().isOk()).andExpect(jsonPath("$.user.canCreateOrganizations").value(true));
        jdbc.update("DELETE FROM user_grants WHERE user_id=?",userId);
        mvc.perform(get("/api/v1/auth/me").header("Authorization","Bearer "+owner.access())).andExpect(status().isOk()).andExpect(jsonPath("$.user.canCreateOrganizations").value(false));
        mvc.perform(get("/api/v1/organizations/"+org+"/access").header("Authorization","Bearer "+owner.access())).andExpect(status().isOk()).andExpect(jsonPath("$.membershipId").value(memberId(org,userId).toString())).andExpect(jsonPath("$.permissions").isArray());
        var other=other();for(String suffix:List.of("access","invitations"))mvc.perform(get("/api/v1/organizations/"+org+"/"+suffix).header("Authorization","Bearer "+other.access())).andExpect(status().isNotFound());
        jdbc.update("DELETE FROM role_permissions WHERE organization_id=? AND permission_code='membership.grant_privileged'",org);
        mvc.perform(get("/api/v1/organizations/"+org+"/roles").header("Authorization","Bearer "+owner.access())).andExpect(status().isOk()).andExpect(jsonPath("$[4].name").value("OWNER")).andExpect(jsonPath("$[4].assignable").value(false));
    }
    @Test void invitationListingIsBoundedTenantAuthorizedAndNeverReturnsProof() throws Exception {
        Browser owner=login(bootstrap(null));UUID org=create(owner,"browser-invitations");UUID id=invite(owner,org,"listed@example.invalid","VIEWER");
        var result=mvc.perform(get("/api/v1/organizations/"+org+"/invitations?size=1").header("Authorization","Bearer "+owner.access())).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.items[0].status").value("PENDING")).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(inbox.messages.getLast().token(),"token_hash","tokenHash");
        mvc.perform(delete("/api/v1/organizations/"+org+"/invitations/"+id).header("Authorization","Bearer "+owner.access()).header("Origin",ORIGIN).cookie(owner.csrf(),owner.refresh()).header("X-CSRF-TOKEN",owner.csrf().getValue())).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/organizations/"+org+"/invitations").header("Authorization","Bearer "+owner.access())).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].status").value("REVOKED"));
        for(String query:List.of("?page=-1","?size=101"))mvc.perform(get("/api/v1/organizations/"+org+"/invitations"+query).header("Authorization","Bearer "+owner.access())).andExpect(status().isBadRequest());
        jdbc.update("DELETE FROM role_permissions WHERE organization_id=? AND permission_code='membership.invite'",org);
        mvc.perform(get("/api/v1/organizations/"+org+"/invitations").header("Authorization","Bearer "+owner.access())).andExpect(status().isForbidden());
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
    @Test void invitationEnrollmentCreatesVerifiedTenantOwnerWithoutGlobalAccountGrant() throws Exception {
        Browser owner=login(bootstrap(null));UUID org=create(owner,"invite-tenant");String email="new@example.invalid";
        UUID invitation=invite(owner,org,email,"OWNER");String token=inbox.messages.getLast().token();
        var enrolled=enroll(bootstrap(null),token,email,"synthetic-enrollment-fixture").andExpect(status().isCreated()).andExpect(jsonPath("$.accessToken").doesNotExist()).andExpect(jsonPath("$.passwordHash").doesNotExist()).andReturn();
        UUID user=UUID.fromString(new ObjectMapper().readTree(enrolled.getResponse().getContentAsString()).path("userId").asString());
        assertThat(jdbc.queryForObject("SELECT email_verified_at IS NOT NULL FROM users WHERE id=?",Boolean.class,user)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_grants WHERE user_id=?",Integer.class,user)).isZero();
        assertThat(jdbc.queryForObject("SELECT accepted_at IS NOT NULL FROM invitations WHERE id=?",Boolean.class,invitation)).isTrue();
        assertThat(jdbc.queryForObject("SELECT token_hash FROM invitations WHERE id=?",String.class,invitation)).isEqualTo(new RefreshTokens().hash(token));
        Browser member=loginAs(email,"synthetic-enrollment-fixture");
        mvc.perform(get("/api/v1/organizations/"+org).header("Authorization","Bearer "+member.access())).andExpect(status().isOk());
        createRequest(member,"not-an-account-grant").andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT bootstrap_consumed_at IS NULL FROM installation_state",Boolean.class)).isTrue();
    }
    @Test void existingRecipientsMustAuthenticateAndInvitationsCannotResetPasswords() throws Exception {
        Browser owner=login(bootstrap(null));UUID org=create(owner,"existing-tenant");Browser recipient=other();UUID recipientId=accessTokens.verify(recipient.access()).userId();
        invite(owner,org,"other@example.invalid","VIEWER");String token=inbox.messages.getLast().token();String original=jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?",String.class,recipientId);
        enroll(bootstrap(null),token,"other@example.invalid","synthetic-replacement-fixture").andExpect(status().isBadRequest());
        accept(owner,token).andExpect(status().isBadRequest());
        assertThatThrownBy(()->runtimeJdbc().queryForList("SELECT * FROM public.enroll_invitation(?,?,?,?)",token,"other@example.invalid",passwords.hash("synthetic-replacement-fixture"),"Wrong"))
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
        accept(recipient,token).andExpect(status().isOk());accept(recipient,token).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?",String.class,recipientId)).isEqualTo(original);
        assertThat(count("users")).isEqualTo(2);assertThat(count("organization_memberships")).isEqualTo(2);
    }
    @Test void invalidRecipientUnknownTokenAndWeakPasswordCannotConsumeInvitation() throws Exception {
        Browser owner=login(bootstrap(null));UUID org=create(owner,"proof-tenant");UUID id=invite(owner,org,"new@example.invalid","VIEWER");String token=inbox.messages.getLast().token();
        enroll(bootstrap(null),token,"wrong@example.invalid","synthetic-enrollment-fixture").andExpect(status().isBadRequest());
        enroll(bootstrap(null),new RefreshTokens().generate(),"new@example.invalid","synthetic-enrollment-fixture").andExpect(status().isBadRequest());
        enroll(bootstrap(null),token,"new@example.invalid","short").andExpect(status().isBadRequest());
        assertThat(count("users")).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT accepted_at IS NULL FROM invitations WHERE id=?",Boolean.class,id)).isTrue();
    }
    @Test void invitationExpiryIsStrictAndRevokedProofCannotEnroll() throws Exception {
        Browser owner=login(bootstrap(null));UUID org=create(owner,"expiry-tenant");UUID id=invite(owner,org,"new@example.invalid","VIEWER");String token=inbox.messages.getLast().token();
        clock.now=NOW.plusSeconds(172800);enroll(bootstrap(null),token,"new@example.invalid","synthetic-enrollment-fixture").andExpect(status().isBadRequest());
        clock.now=NOW;revokeInvite(owner,org,id).andExpect(status().isNoContent());revokeInvite(owner,org,id).andExpect(status().isNoContent());
        enroll(bootstrap(null),token,"new@example.invalid","synthetic-enrollment-fixture").andExpect(status().isBadRequest());
        invite(owner,org,"new@example.invalid","VIEWER");assertThat(inbox.messages.getLast().token()).isNotEqualTo(token);
    }
    @Test void onePendingInvitationAndTenantForeignKeysPreventAmbiguousOrForeignGrants() throws Exception {
        Browser owner=login(bootstrap(null));UUID org=create(owner,"alpha-tenant");Browser other=other();UUID foreign=create(other,"beta-tenant");
        UUID id=invite(owner,org,"new@example.invalid","VIEWER");
        invitationRequest(owner,org,"new@example.invalid",List.of(roleId(org,"VIEWER"))).andExpect(status().isConflict());
        invitationRequest(owner,org,"other-new@example.invalid",List.of(roleId(foreign,"OWNER"))).andExpect(status().isNotFound());
        assertThatThrownBy(()->jdbc.update("INSERT INTO invitation_roles VALUES(?,?,?)",org,id,roleId(foreign,"OWNER"))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("INSERT INTO invitation_roles VALUES(?,?,?)",foreign,id,roleId(foreign,"VIEWER"))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(inbox.messages).hasSize(1);assertThat(count("invitations")).isEqualTo(1);
    }
    @Test void invitationDelegationRejectsViewerAndAdministratorEscalation() throws Exception {
        Browser owner=login(bootstrap(null));UUID org=create(owner,"delegate-tenant");Browser actor=other();UUID membership=addMember(org,accessTokens.verify(actor.access()).userId(),"VIEWER");
        invitationRequest(actor,org,"new@example.invalid",List.of(roleId(org,"VIEWER"))).andExpect(status().isForbidden());
        patchMember(owner,org,membership,0,"ACTIVE",List.of(roleId(org,"ADMIN"))).andExpect(status().isOk());
        for(String role:List.of("OWNER","ADMIN","APPROVER"))invitationRequest(actor,org,"new@example.invalid",List.of(roleId(org,role))).andExpect(status().isForbidden());
        invite(actor,org,"new@example.invalid","BUILDER");assertThat(inbox.messages).hasSize(1);
    }
    @Test void acceptanceRevalidatesIssuerDelegationAndDatabaseCapabilityDoesToo() throws Exception {
        Browser owner=login(bootstrap(null));UUID org=create(owner,"changed-issuer-tenant");Browser second=other();addMember(org,accessTokens.verify(second.access()).userId(),"OWNER");
        invite(owner,org,"new@example.invalid","OWNER");String token=inbox.messages.getLast().token();
        patchMember(second,org,memberId(org,userId),0,"ACTIVE",List.of(roleId(org,"ADMIN"))).andExpect(status().isOk());
        enroll(bootstrap(null),token,"new@example.invalid","synthetic-enrollment-fixture").andExpect(status().isBadRequest());
        assertThatThrownBy(()->runtimeJdbc().queryForList("SELECT * FROM public.enroll_invitation(?,?,?,?)",token,"new@example.invalid",passwords.hash("synthetic-enrollment-fixture"),"New"))
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(count("users")).isEqualTo(2);
    }
    @Test void disabledIssuerCannotOnboardARecipient() throws Exception {
        Browser owner=login(bootstrap(null));UUID org=create(owner,"disabled-issuer-tenant");invite(owner,org,"new@example.invalid","VIEWER");String token=inbox.messages.getLast().token();
        jdbc.update("UPDATE users SET account_status='DISABLED' WHERE id=?",userId);
        enroll(bootstrap(null),token,"new@example.invalid","synthetic-enrollment-fixture").andExpect(status().isBadRequest());assertThat(count("users")).isEqualTo(1);
    }
    @Test void concurrentInvitationEnrollmentHasOneWinner() throws Exception {
        Browser owner=login(bootstrap(null));UUID org=create(owner,"enrollment-race-tenant");invite(owner,org,"new@example.invalid","VIEWER");String token=inbox.messages.getLast().token();Browser anonymous=bootstrap(null);
        var gate=new CountDownLatch(1);try(var pool=Executors.newFixedThreadPool(2)){
            Callable<Integer> task=()->{gate.await();return enroll(anonymous,token,"new@example.invalid","synthetic-enrollment-fixture").andReturn().getResponse().getStatus();};
            var a=pool.submit(task);var b=pool.submit(task);gate.countDown();assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(201,400);
        }
        assertThat(count("users")).isEqualTo(2);assertThat(count("organization_memberships")).isEqualTo(2);
    }
    @Test void concurrentExistingRecipientAcceptanceIsOneUse() throws Exception {
        Browser owner=login(bootstrap(null));UUID org=create(owner,"accept-race-tenant");Browser recipient=other();invite(owner,org,"other@example.invalid","VIEWER");String token=inbox.messages.getLast().token();
        var gate=new CountDownLatch(1);try(var pool=Executors.newFixedThreadPool(2)){
            Callable<Integer> task=()->{gate.await();return accept(recipient,token).andReturn().getResponse().getStatus();};
            var a=pool.submit(task);var b=pool.submit(task);gate.countDown();assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,400);
        }
        assertThat(count("organization_memberships")).isEqualTo(2);
    }
    @Test void deliveryFailureRollsBackInvitationAndRoles() throws Exception {
        Browser owner=login(bootstrap(null));UUID org=create(owner,"delivery-tenant");inbox.fail=true;
        invitationRequest(owner,org,"new@example.invalid",List.of(roleId(org,"VIEWER"))).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("DELIVERY_UNAVAILABLE"));
        assertThat(count("invitations")).isZero();assertThat(count("invitation_roles")).isZero();inbox.fail=false;invite(owner,org,"new@example.invalid","VIEWER");
    }
    @Test void enrollmentAuditFailureRollsBackAccountMembershipAndConsumption() throws Exception {
        Browser owner=login(bootstrap(null));UUID org=create(owner,"enroll-rollback-tenant");UUID id=invite(owner,org,"new@example.invalid","VIEWER");String token=inbox.messages.getLast().token();
        jdbc.execute("CREATE FUNCTION reject_enroll_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action='INVITATION_ACCEPTED' THEN RAISE EXCEPTION 'synthetic rollback'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER reject_enroll_audit BEFORE INSERT ON organization_audit_events FOR EACH ROW EXECUTE FUNCTION reject_enroll_audit()");
        try{enroll(bootstrap(null),token,"new@example.invalid","synthetic-enrollment-fixture").andExpect(status().isServiceUnavailable());}
        finally{jdbc.execute("DROP TRIGGER reject_enroll_audit ON organization_audit_events");jdbc.execute("DROP FUNCTION reject_enroll_audit()");}
        assertThat(count("users")).isEqualTo(1);assertThat(count("organization_memberships")).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT accepted_at IS NULL FROM invitations WHERE id=?",Boolean.class,id)).isTrue();
        enroll(bootstrap(null),token,"new@example.invalid","synthetic-enrollment-fixture").andExpect(status().isCreated());
    }
    @Test void enrollmentRequiresOriginCsrfAndRejectsBodyAuthorityClaims() throws Exception {
        Browser owner=login(bootstrap(null));UUID org=create(owner,"request-proof-tenant");invite(owner,org,"new@example.invalid","VIEWER");String token=inbox.messages.getLast().token();Browser anonymous=bootstrap(null);
        String body=new ObjectMapper().writeValueAsString(Map.of("token",token,"email","new@example.invalid","displayName","New","password","synthetic-enrollment-fixture"));
        mvc.perform(post("/api/v1/invitations/enroll").contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/invitations/enroll").header("Origin","https://evil.invalid").cookie(anonymous.csrf()).header("X-CSRF-TOKEN",anonymous.csrf().getValue()).contentType("application/json").content(body)).andExpect(status().isForbidden());
        invitationPost(anonymous,"/api/v1/invitations/enroll",body.substring(0,body.length()-1)+",\"roleIds\":[]}").andExpect(status().isBadRequest());
        assertThat(count("users")).isEqualTo(1);
    }
    @Test void enrollmentQuotaCannotBeBypassedByValidInvitationProof() throws Exception {
        Browser owner=login(bootstrap(null));UUID org=create(owner,"quota-tenant");invite(owner,org,"new@example.invalid","VIEWER");String token=inbox.messages.getLast().token();Browser anonymous=bootstrap(null);
        jdbc.update("INSERT INTO authentication_throttles VALUES(?,10,?)",new RefreshTokens().hash("enroll-account:new@example.invalid"),java.sql.Timestamp.from(NOW.plusSeconds(900)));
        enroll(anonymous,token,"new@example.invalid","synthetic-enrollment-fixture").andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After","900"));assertThat(count("users")).isEqualTo(1);
        clock.now=NOW.plusSeconds(900);enroll(anonymous,token,"new@example.invalid","synthetic-enrollment-fixture").andExpect(status().isCreated());
    }
    @Test void enrollmentCapabilityKeepsGenericUserInsertDeniedAndRequiresRawProof() throws Exception {
        var runtime=runtimeJdbc();assertThat(runtime.queryForObject("SELECT current_user",String.class)).isEqualTo("bridgeops_app");
        assertThat(runtime.queryForObject("SELECT has_table_privilege(current_user,'users','INSERT') OR has_table_privilege(current_user,'user_grants','INSERT') OR has_table_privilege(current_user,'installation_state','UPDATE')",Boolean.class)).isFalse();
        assertThat(runtime.queryForObject("SELECT has_function_privilege(current_user,'public.enroll_invitation(text,text,text,text)','EXECUTE')",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT has_function_privilege(\'invitation_untrusted\',\'public.enroll_invitation(text,text,text,text)\',\'EXECUTE\')",Boolean.class)).isFalse();
        Browser owner=login(bootstrap(null));UUID org=create(owner,"capability-tenant");UUID id=invite(owner,org,"new@example.invalid","VIEWER");String token=inbox.messages.getLast().token(),hash=passwords.hash("synthetic-enrollment-fixture");
        for(String proof:List.of(new RefreshTokens().generate(),new RefreshTokens().hash(token)))assertThatThrownBy(()->runtime.queryForList("SELECT * FROM public.enroll_invitation(?,?,?,?)",proof,"new@example.invalid",hash,"New")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->runtime.queryForList("SELECT * FROM public.enroll_invitation(?,?,?,?)",token,"wrong@example.invalid",hash,"New")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->runtime.queryForList("SELECT * FROM public.enroll_invitation(?,?,?,?)",token,"new@example.invalid","not-a-hash","New")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        jdbc.update("UPDATE invitations SET created_at=?,expires_at=? WHERE id=?",java.sql.Timestamp.from(NOW.minusSeconds(3600)),java.sql.Timestamp.from(NOW.minusSeconds(1)),id);
        assertThatThrownBy(()->runtime.queryForList("SELECT * FROM public.enroll_invitation(?,?,?,?)",token,"new@example.invalid",hash,"New")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(count("users")).isEqualTo(1);
    }
    @Test void invitationRevokeAndIssueCannotUseForeignTenantIdentifiers() throws Exception {
        Browser owner=login(bootstrap(null));UUID org=create(owner,"local-tenant");Browser foreignOwner=other();UUID foreign=create(foreignOwner,"foreign-tenant");
        UUID invitation=invite(foreignOwner,foreign,"new@example.invalid","OWNER");
        invitationRequest(owner,foreign,"another@example.invalid",List.of(roleId(foreign,"VIEWER"))).andExpect(status().isNotFound());
        revokeInvite(owner,org,invitation).andExpect(status().isNotFound());
        UUID actor=addMember(foreign,userId,"ADMIN");
        revokeInvite(owner,foreign,invitation).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT revoked_at IS NULL FROM invitations WHERE id=?",Boolean.class,invitation)).isTrue();
    }
    @Test void deliveredButRolledBackInvitationCannotCreateAnAccount() throws Exception {
        Browser owner=login(bootstrap(null));UUID org=create(owner,"delivery-rollback-tenant");
        jdbc.execute("CREATE FUNCTION reject_issued_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action='INVITATION_ISSUED' THEN RAISE EXCEPTION 'synthetic rollback'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER reject_issued_audit BEFORE INSERT ON organization_audit_events FOR EACH ROW EXECUTE FUNCTION reject_issued_audit()");
        try{invitationRequest(owner,org,"new@example.invalid",List.of(roleId(org,"VIEWER"))).andExpect(status().isServiceUnavailable());}
        finally{jdbc.execute("DROP TRIGGER reject_issued_audit ON organization_audit_events");jdbc.execute("DROP FUNCTION reject_issued_audit()");}
        assertThat(inbox.messages).hasSize(1);assertThat(count("invitations")).isZero();
        enroll(bootstrap(null),inbox.messages.getLast().token(),"new@example.invalid","synthetic-enrollment-fixture").andExpect(status().isBadRequest());assertThat(count("users")).isEqualTo(1);
    }
    private JdbcTemplate runtimeJdbc(){return new JdbcTemplate(runtimeSource);}
    private UUID invite(Browser actor,UUID org,String email,String role)throws Exception{
        var result=invitationRequest(actor,org,email,List.of(roleId(org,role))).andExpect(status().isCreated()).andExpect(jsonPath("$.token").doesNotExist()).andExpect(jsonPath("$.tokenHash").doesNotExist()).andReturn();
        return UUID.fromString(new ObjectMapper().readTree(result.getResponse().getContentAsString()).path("id").asString());
    }
    private ResultActions invitationRequest(Browser actor,UUID org,String email,List<UUID> roles)throws Exception{return invitationPost(actor,"/api/v1/organizations/"+org+"/invitations",new ObjectMapper().writeValueAsString(Map.of("email",email,"roleIds",roles)));}
    private ResultActions enroll(Browser actor,String token,String email,String password)throws Exception{return invitationPost(actor,"/api/v1/invitations/enroll",new ObjectMapper().writeValueAsString(Map.of("token",token,"email",email,"displayName","Synthetic Invitee","password",password)));}
    private ResultActions accept(Browser actor,String token)throws Exception{return invitationPost(actor,"/api/v1/invitations/accept",new ObjectMapper().writeValueAsString(Map.of("token",token)));}
    private ResultActions revokeInvite(Browser actor,UUID org,UUID invitation)throws Exception{return mvc.perform(delete("/api/v1/organizations/"+org+"/invitations/"+invitation).header("Authorization","Bearer "+actor.access()).header("Origin",ORIGIN).cookie(actor.csrf(),actor.refresh()).header("X-CSRF-TOKEN",actor.csrf().getValue()));}
    private ResultActions invitationPost(Browser actor,String path,String body)throws Exception{
        var request=post(path).header("Origin",ORIGIN).cookie(actor.csrf()).header("X-CSRF-TOKEN",actor.csrf().getValue()).contentType("application/json").content(body);
        if(actor.refresh()!=null)request.cookie(actor.refresh());if(actor.access()!=null)request.header("Authorization","Bearer "+actor.access());return mvc.perform(request);
    }
    private Browser loginAs(String email,String password)throws Exception{
        var anonymous=bootstrap(null);return browser(invitationPost(anonymous,"/api/v1/auth/login",new ObjectMapper().writeValueAsString(Map.of("email",email,"password",password))).andExpect(status().isOk()).andReturn());
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
    record Delivery(String email,String token,Instant expires){@Override public String toString(){return "Delivery[REDACTED]";}}
    static class TestInbox implements com.bridgeops.organizations.application.InvitationDelivery {
        final List<Delivery> messages=new java.util.concurrent.CopyOnWriteArrayList<>();volatile boolean fail;
        @Override public void deliver(String email,String token,Instant expires){if(fail)throw new com.bridgeops.shared.web.DomainFailure(503,"DELIVERY_UNAVAILABLE");messages.add(new Delivery(email,token,expires));}
    }
    @TestConfiguration static class TimeConfiguration {
        @Bean @Primary TestClock httpTestClock(){return new TestClock();}
        @Bean @Primary TestInbox testInbox(){return new TestInbox();}
    }
}