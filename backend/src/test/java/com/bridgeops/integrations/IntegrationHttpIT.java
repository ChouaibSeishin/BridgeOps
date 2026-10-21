package com.bridgeops.integrations;

import com.bridgeops.integrations.application.BusinessService;
import com.bridgeops.identity.application.*;
import com.bridgeops.identity.domain.SessionOutcome.Granted;
import com.bridgeops.organizations.application.OrganizationService;
import com.bridgeops.support.TestAuthMaterial;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"bridgeops.auth.enabled=true","bridgeops.auth.origin=https://bridgeops.invalid","bridgeops.auth.issuer=business-http","bridgeops.auth.key-id=business-http","bridgeops.executions.worker.enabled=false"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class IntegrationHttpIT {
    static final String ORIGIN="https://bridgeops.invalid";static final TestAuthMaterial keys=new TestAuthMaterial();
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer(DockerImageName.parse("postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873").asCompatibleSubstituteFor("postgres")).withInitScript("organization-runtime.sql");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",()->"bridgeops_app");r.add("spring.datasource.password",()->"synthetic-runtime-fixture");r.add("spring.flyway.user",postgres::getUsername);r.add("spring.flyway.password",postgres::getPassword);r.add("bridgeops.auth.private-key",()->keys.directory.resolve("private.pem").toString());r.add("bridgeops.auth.public-key",()->keys.directory.resolve("public.pem").toString());r.add("bridgeops.auth.csrf-key",()->keys.directory.resolve("csrf.key").toString());}
    @Autowired MockMvc mvc;@Autowired AccountPasswords passwords;@Autowired PasswordSessionService sessions;@Autowired AccessTokens tokens;@Autowired OrganizationService organizations;
    @MockitoSpyBean BusinessService business;
    JdbcTemplate operator;Browser owner;UUID org;static String hash;
    record Browser(UUID user,UUID family,Cookie refresh,Cookie csrf,String access){@Override public String toString(){return "Browser[REDACTED]";}}
    @BeforeEach void fixture()throws Exception{operator=new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));operator.execute("TRUNCATE users,installation_state,authentication_throttles CASCADE");operator.update("INSERT INTO installation_state(singleton_id) VALUES(1)");if(hash==null)hash=passwords.hash("business-http-disposable");owner=account("owner@example.invalid");operator.update("INSERT INTO user_grants VALUES(?,'organization.create',?)",owner.user(),java.sql.Timestamp.from(Instant.now()));org=organizations.create(owner.user(),owner.family(),"HTTP business","http-business").id();}
    Browser account(String email)throws Exception{UUID id=UUID.randomUUID();var now=java.sql.Timestamp.from(Instant.now());operator.update("INSERT INTO users(id,email,normalized_email,password_hash,display_name,account_status,created_at,email_verified_at) VALUES(?,?,?,?,?,'ACTIVE',?,?)",id,email,email,hash,"HTTP synthetic",now,now);var grant=(Granted)sessions.authenticate(email,"business-http-disposable");var refresh=new Cookie("__Host-bridgeops-refresh",grant.refreshToken());var csrf=mvc.perform(get("/api/v1/auth/csrf").cookie(refresh)).andExpect(status().isOk()).andReturn().getResponse().getCookie("__Host-bridgeops-csrf");return new Browser(id,grant.familyId(),refresh,csrf,tokens.issue(grant).value());}
    Browser member(String role)throws Exception{var a=account(role.toLowerCase(Locale.ROOT)+"@example.invalid");UUID membership=UUID.randomUUID();operator.update("INSERT INTO organization_memberships(id,organization_id,user_id,status,joined_at) VALUES(?,?,?,'ACTIVE',?)",membership,org,a.user(),java.sql.Timestamp.from(Instant.now()));operator.update("INSERT INTO membership_roles SELECT ?,?,id FROM roles WHERE organization_id=? AND name=?",org,membership,org,role);return a;}
    String base(){return "/api/v1/organizations/"+org;}
    ResultActions send(Browser a,MockHttpServletRequestBuilder request)throws Exception{return mvc.perform(request.header("Authorization","Bearer "+a.access()).header("Origin",ORIGIN).cookie(a.refresh(),a.csrf()).header("X-CSRF-TOKEN",a.csrf().getValue()).contentType("application/json"));}
    @Test void anonymousBusinessReadsRequireAuthentication()throws Exception{for(String path:List.of("/support/tickets","/teams","/teams/responsibilities","/notifications"))mvc.perform(get(base()+path)).andExpect(status().isUnauthorized());}
    @Test void setupRequiresCsrfAndExactOrigin()throws Exception{mvc.perform(post(base()+"/demo-data").header("Authorization","Bearer "+owner.access()).header("Origin",ORIGIN).contentType("application/json").content("{\"confirmed\":true}")).andExpect(status().isForbidden());mvc.perform(post(base()+"/demo-data").header("Authorization","Bearer "+owner.access()).header("Origin","https://hostile.invalid").cookie(owner.refresh(),owner.csrf()).header("X-CSRF-TOKEN",owner.csrf().getValue()).contentType("application/json").content("{\"confirmed\":true}")).andExpect(status().isForbidden());assertThat(operator.queryForObject("SELECT count(*) FROM support_tickets",Long.class)).isZero();}
    @Test void setupBodyIsStrictBoundedAndExplicitlyConfirmed()throws Exception{for(String invalid:List.of("{}","{\"confirmed\":\"true\"}","{\"confirmed\":1}","{\"confirmed\":true,\"actor\":\"other\"}","{\"confirmed\":true,\"confirmed\":false}"))send(owner,post(base()+"/demo-data").content(invalid)).andExpect(status().isBadRequest());send(owner,post(base()+"/demo-data").content("{\"confirmed\":false}")).andExpect(status().isUnprocessableContent());send(owner,post(base()+"/demo-data").content(" ".repeat(262145))).andExpect(status().isContentTooLarge());assertThat(operator.queryForObject("SELECT count(*) FROM integration_demo_installations",Long.class)).isZero();}
    @Test void setupAndPersistedReadsReportActualProvenanceAndInternalDelivery()throws Exception{send(owner,post(base()+"/demo-data").content("{\"confirmed\":true}")).andExpect(status().isOk()).andExpect(jsonPath("$.created").value(true)).andExpect(jsonPath("$.tickets").value(5));send(owner,post(base()+"/demo-data").content("{\"confirmed\":true}")).andExpect(status().isOk()).andExpect(jsonPath("$.created").value(false));send(owner,get(base()+"/support/tickets?priority=HIGH&status=OPEN")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.items[0].provenance").value("SEEDED_EXAMPLE"));send(owner,get(base()+"/teams/responsibilities")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));var result=send(owner,get(base()+"/notifications")).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].status").value("DELIVERED_INTERNAL")).andReturn();String id=JsonMapper.builder().build().readTree(result.getResponse().getContentAsString()).path("items").get(0).path("id").asString();send(owner,get(base()+"/notifications/"+id+"/attempts")).andExpect(status().isOk()).andExpect(jsonPath("$[0].outcome").value("DELIVERED_INTERNAL"));}
    @Test void viewerWritesAndForeignReadsAreDeniedAndFiltersBounded()throws Exception{var viewer=member("VIEWER");send(viewer,post(base()+"/demo-data").content("{\"confirmed\":true}")).andExpect(status().isForbidden());send(owner,post(base()+"/demo-data").content("{\"confirmed\":true}")).andExpect(status().isOk());send(viewer,get(base()+"/support/tickets")).andExpect(status().isOk());var foreign=account("foreign@example.invalid");send(foreign,get(base()+"/support/tickets")).andExpect(status().isNotFound());for(String query:List.of("size=101","page=-1","priority=invalid","category=invalid"))send(owner,get(base()+"/support/tickets?"+query)).andExpect(status().isBadRequest());send(owner,get(base()+"/support/tickets/"+UUID.randomUUID())).andExpect(status().isNotFound());}
    @Test void dependencyErrorIsSafeAndDoesNotExposeCanary()throws Exception{UUID id=UUID.randomUUID();doThrow(new org.springframework.dao.DataAccessResourceFailureException("disposable-sensitive-provider-canary")).when(business).ticket(owner.user(),owner.family(),org,id);var result=send(owner,get(base()+"/support/tickets/"+id)).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE")).andReturn();assertThat(result.getResponse().getContentAsString()).doesNotContain("disposable-sensitive-provider-canary","jdbc:","Exception");}
}
