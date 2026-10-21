package com.bridgeops.integrations.application;

import com.bridgeops.identity.application.*;
import com.bridgeops.identity.domain.SessionOutcome.Granted;
import com.bridgeops.organizations.application.*;
import com.bridgeops.shared.web.DomainFailure;
import com.bridgeops.support.TestAuthMaterial;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"bridgeops.auth.enabled=true","bridgeops.auth.origin=https://bridgeops.invalid","bridgeops.auth.issuer=business-test","bridgeops.auth.key-id=business-test","bridgeops.executions.worker.enabled=false"})
@Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class IntegrationBusinessIT {
    static final TestAuthMaterial keys=new TestAuthMaterial();static final Path secretDirectory=secretDirectory();
    static class ControlledClock extends Clock {Instant now=Instant.now();public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}void add(long seconds){now=now.plusSeconds(seconds);}}
    @org.springframework.boot.test.context.TestConfiguration static class Configuration {@org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary ControlledClock controlledClock(){return new ControlledClock();}}
    static Path secretDirectory(){try{Path dir=Files.createTempDirectory("bridgeops-disposable-secret-");byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);Path file=dir.resolve("local-v1.key");Files.write(file,key);if(Files.getFileStore(file).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(file,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));Arrays.fill(key,(byte)0);return dir;}catch(Exception unavailable){throw new ExceptionInInitializerError(unavailable);}}
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer(DockerImageName.parse("postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873").asCompatibleSubstituteFor("postgres")).withInitScript("organization-runtime.sql");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",()->"bridgeops_app");r.add("spring.datasource.password",()->"synthetic-runtime-fixture");r.add("spring.flyway.user",postgres::getUsername);r.add("spring.flyway.password",postgres::getPassword);r.add("bridgeops.auth.private-key",()->keys.directory.resolve("private.pem").toString());r.add("bridgeops.auth.public-key",()->keys.directory.resolve("public.pem").toString());r.add("bridgeops.auth.csrf-key",()->keys.directory.resolve("csrf.key").toString());r.add("bridgeops.integrations.secrets.directory",secretDirectory::toString);}
    @Autowired BusinessService business;@Autowired SecretStore secrets;@Autowired AccountPasswords passwords;@Autowired PasswordSessionService sessions;@Autowired OrganizationService organizations;@Autowired TenantAuthorization authority;@Autowired PlatformTransactionManager manager;@Autowired JdbcTemplate runtime;
    @Autowired ConnectionService connections;@Autowired ConnectionChecks checks;@Autowired OutboundAdmission admission;
    @Autowired ControlledClock clock;
    JdbcTemplate operator;Actor owner;UUID org;static String passwordHash;
    record Actor(UUID id,UUID family) {}
    @BeforeEach void fixture(){
        clock.now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);operator=new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));operator.execute("TRUNCATE users,installation_state,authentication_throttles CASCADE");operator.update("INSERT INTO installation_state(singleton_id) VALUES(1)");
        if(passwordHash==null)passwordHash=passwords.hash("business-disposable-password");owner=account("owner@example.invalid");operator.update("INSERT INTO user_grants VALUES(?,'organization.create',?)",owner.id(),java.sql.Timestamp.from(Instant.now()));org=organizations.create(owner.id(),owner.family(),"Business examples","business-test").id();
    }
    Actor account(String email){UUID id=UUID.randomUUID();var at=java.sql.Timestamp.from(Instant.now());operator.update("INSERT INTO users(id,email,normalized_email,password_hash,display_name,account_status,created_at,email_verified_at) VALUES(?,?,?,?,?,'ACTIVE',?,?)",id,email,email,passwordHash,"Synthetic",at,at);var grant=(Granted)sessions.authenticate(email,"business-disposable-password");return new Actor(id,grant.familyId());}
    Actor member(String role){var a=account(role.toLowerCase(Locale.ROOT)+"@example.invalid");UUID membership=UUID.randomUUID();operator.update("INSERT INTO organization_memberships(id,organization_id,user_id,status,joined_at) VALUES(?,?,?,'ACTIVE',?)",membership,org,a.id(),java.sql.Timestamp.from(Instant.now()));operator.update("INSERT INTO membership_roles SELECT ?,?,id FROM roles WHERE organization_id=? AND name=?",org,membership,org,role);return a;}
    BusinessService.DemoResult seed(){return business.demo(owner.id(),owner.family(),org,true);}
    long count(String table){return operator.queryForObject("SELECT count(*) FROM "+table,Long.class);}
    void denied(int status,Runnable action){assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainFailure.class,e->assertThat(e.status()).isEqualTo(status));}
    @AfterEach void cleanupFailure(){operator.execute("DROP TRIGGER IF EXISTS test_demo_audit ON organization_audit_events");}
    @Test void startupCreatesNoDemoDataOrSecrets(){for(String table:List.of("support_teams","support_tickets","internal_notifications","integration_secrets","integration_connections","integration_demo_installations"))assertThat(count(table)).isZero();}
    @Test void seedRequiresExplicitConfirmation(){denied(422,()->business.demo(owner.id(),owner.family(),org,false));assertThat(count("integration_demo_installations")).isZero();assertThat(count("support_tickets")).isZero();}
    @Test void seedIsIdempotentAndExplicitlyProvenanced(){var first=seed();assertThat(first.created()).isTrue();assertThat(first.teams()).isEqualTo(3);assertThat(first.tickets()).isEqualTo(5);assertThat(first.notifications()).isEqualTo(1);assertThat(first.provenance()).isEqualTo("SEEDED_EXAMPLE");assertThat(seed().created()).isFalse();assertThat(count("support_tickets")).isEqualTo(5);assertThat(operator.queryForObject("SELECT count(*) FROM organization_audit_events WHERE action='DEMO_DATA_INSTALLED'",Long.class)).isEqualTo(1);}
    @Test void concurrentSeedCreatesOneSet()throws Exception{try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(this::seed);var b=pool.submit(this::seed);assertThat(List.of(a.get(15,TimeUnit.SECONDS).created(),b.get(15,TimeUnit.SECONDS).created())).containsExactlyInAnyOrder(true,false);}assertThat(count("support_tickets")).isEqualTo(5);}
    @Test void filtersAndPaginationUsePersistedData(){seed();var page=business.tickets(owner.id(),owner.family(),org,"OPEN","HIGH","BILLING",0,1);assertThat(page.totalElements()).isEqualTo(1);assertThat(page.items()).hasSize(1);assertThat(page.items().getFirst().revision()).isZero();assertThat(page.items().getFirst().assignedTeamId()).isNull();assertThat(business.tickets(owner.id(),owner.family(),org,"CLOSED",null,null,0,25).items()).isEmpty();assertThat(business.responsibilities(owner.id(),owner.family(),org)).hasSize(3);}
    @Test void invalidFiltersAndBoundsAreRejected(){for(String priority:List.of("high","INVALID",""))denied(400,()->business.tickets(owner.id(),owner.family(),org,null,priority,null,0,25));for(int size:new int[]{0,101})denied(400,()->business.tickets(owner.id(),owner.family(),org,null,null,null,0,size));denied(400,()->business.tickets(owner.id(),owner.family(),org,null,null,null,100001,25));}
    @Test void viewerCanInspectButCannotSeedAndRevocationIsLive(){seed();var viewer=member("VIEWER");assertThat(business.tickets(viewer.id(),viewer.family(),org,null,null,null,0,25).items()).hasSize(5);denied(403,()->business.demo(viewer.id(),viewer.family(),org,true));operator.update("UPDATE organization_memberships SET status='REMOVED',revision=revision+1 WHERE organization_id=? AND user_id=?",org,viewer.id());denied(404,()->business.teams(viewer.id(),viewer.family(),org));}
    @Test void foreignTenantAndObjectIdsAreConcealed(){seed();UUID foreign=organizations.create(owner.id(),owner.family(),"Other organization","other-org").id();var ticket=business.tickets(owner.id(),owner.family(),org,null,null,null,0,25).items().getFirst();var team=business.teams(owner.id(),owner.family(),org).getFirst();var notification=business.notifications(owner.id(),owner.family(),org,0,25).items().getFirst();denied(404,()->business.ticket(owner.id(),owner.family(),foreign,ticket.id()));denied(404,()->business.team(owner.id(),owner.family(),foreign,team.id()));denied(404,()->business.notification(owner.id(),owner.family(),foreign,notification.id()));var outsider=account("outsider@example.invalid");denied(404,()->business.notifications(outsider.id(),outsider.family(),org,0,25));}
    @Test void internalNotificationHasHonestStatusAndImmutableAttempt(){seed();var n=business.notifications(owner.id(),owner.family(),org,0,25).items().getFirst();assertThat(n.status()).isEqualTo("DELIVERED_INTERNAL");assertThat(n.message()).contains("no external message");assertThat(business.attempts(owner.id(),owner.family(),org,n.id())).hasSize(1).allSatisfy(a->assertThat(a.outcome()).isEqualTo("DELIVERED_INTERNAL"));assertThatThrownBy(()->runtime.update("UPDATE internal_notification_attempts SET outcome='FAILED'")).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThatThrownBy(()->operator.update("DELETE FROM internal_notification_attempts")).isInstanceOf(org.springframework.dao.DataAccessException.class);}
    @Test void auditFailureRollsBackAllSeedState(){operator.execute("CREATE OR REPLACE FUNCTION test_demo_audit_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action='DEMO_DATA_INSTALLED' THEN RAISE EXCEPTION 'Controlled audit failure'; END IF; RETURN NEW; END $$");operator.execute("CREATE TRIGGER test_demo_audit BEFORE INSERT ON organization_audit_events FOR EACH ROW EXECUTE FUNCTION test_demo_audit_failure()");assertThatThrownBy(this::seed).isInstanceOf(org.springframework.dao.DataAccessException.class);for(String table:List.of("integration_demo_installations","support_teams","team_responsibilities","support_tickets","internal_notifications","internal_notification_attempts"))assertThat(count(table)).isZero();}
    @Test void secretsAreEncryptedTenantPurposeBoundAndNotMutable(){
        byte[] canary="disposable-integration-canary-not-real".getBytes(StandardCharsets.UTF_8);var tx=new TransactionTemplate(manager);UUID ref=tx.execute(s->secrets.put(owner.id(),owner.family(),org,"REST_CREDENTIAL",canary));assertThat(ref).isNotNull();assertThat(secrets.resolve(org,ref,"REST_CREDENTIAL")).isEqualTo(canary);
        byte[] stored=operator.queryForObject("SELECT ciphertext FROM integration_secrets WHERE id=?",byte[].class,ref);assertThat(stored).isNotEqualTo(canary);assertThat(new String(stored,StandardCharsets.UTF_8)).doesNotContain("disposable-integration-canary");
        denied(503,()->secrets.resolve(UUID.randomUUID(),ref,"REST_CREDENTIAL"));denied(503,()->secrets.resolve(org,ref,"WEBHOOK_SIGNING"));assertThatThrownBy(()->runtime.update("UPDATE integration_secrets SET key_id='other'")).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThatThrownBy(()->operator.update("DELETE FROM integration_secrets")).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void missingKeyDoesNotPersistPlaintextAndWrongTenantFkIsDenied(){
        var unavailable=new SecretStore(runtime,authority,Clock.systemUTC(),"","local-v1");denied(503,()->new TransactionTemplate(manager).execute(s->unavailable.put(owner.id(),owner.family(),org,"REST_CREDENTIAL",new byte[]{1})));assertThat(count("integration_secrets")).isZero();
        seed();var team=business.teams(owner.id(),owner.family(),org).getFirst();UUID other=organizations.create(owner.id(),owner.family(),"Foreign","foreign").id();assertThatThrownBy(()->operator.update("INSERT INTO team_responsibilities(organization_id,category,team_id) VALUES(?,'OTHER',?)",other,team.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void connectionCatalogUsesLivePermissionsAndInternalConfigurationIsStrict(){
        assertThat(connections.definitions(owner.id(),owner.family(),org)).extracting(ConnectionService.Definition::type).containsExactly("SUPPORT","TEAMS","NOTIFICATIONS","REST");
        var json=tools.jackson.databind.json.JsonMapper.builder().build();var created=connections.create(owner.id(),owner.family(),org,"Local support","","SUPPORT",json.createObjectNode(),null);
        assertThat(created.connection().health().status()).isEqualTo("NOT_CHECKED");assertThat(created.credentialConfigured()).isFalse();
        assertThat(checks.check(owner.id(),owner.family(),org,created.connection().id(),0).health().code()).isEqualTo("INTERNAL_AVAILABLE");
        var viewer=member("VIEWER");assertThat(connections.list(viewer.id(),viewer.family(),org,0,25).items()).hasSize(1);
        denied(403,()->connections.configuration(viewer.id(),viewer.family(),org,created.connection().id()));denied(403,()->connections.create(viewer.id(),viewer.family(),org,"Forbidden","","SUPPORT",json.createObjectNode(),null));
        denied(422,()->connections.create(owner.id(),owner.family(),org,"Invalid","","SUPPORT",json.readTree("{\"url\":\"https://api.example.com\"}"),null));
    }
    @Test void connectionUpdatesPreserveHistoryAndRejectStaleRevision(){
        var json=tools.jackson.databind.json.JsonMapper.builder().build();var created=connections.create(owner.id(),owner.family(),org,"Local support","","SUPPORT",json.createObjectNode(),null);UUID id=created.connection().id();
        connections.update(owner.id(),owner.family(),org,id,0,"Updated support","",false,json.createObjectNode(),null,false);
        denied(409,()->connections.update(owner.id(),owner.family(),org,id,0,"Lost edit","",true,json.createObjectNode(),null,false));
        denied(409,()->checks.check(owner.id(),owner.family(),org,id,1));assertThat(count("integration_connection_versions")).isEqualTo(2);
        assertThatThrownBy(()->runtime.update("UPDATE integration_connection_versions SET definition_json='{}'")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        UUID other=organizations.create(owner.id(),owner.family(),"Other","other-connection").id();denied(404,()->connections.get(owner.id(),owner.family(),other,id));
    }
    @Test void outboundAdmissionHasConcurrencyBoundsAndRecordsRelease(){
        var json=tools.jackson.databind.json.JsonMapper.builder().build();UUID id=connections.create(owner.id(),owner.family(),org,"Bounded connection","","SUPPORT",json.createObjectNode(),null).connection().id();
        UUID first=admission.acquire(org,id),second=admission.acquire(org,id);denied(429,()->admission.acquire(org,id));admission.release(org,first);UUID third=admission.acquire(org,id);admission.release(org,second);admission.release(org,third);
        for(int i=0;i<7;i++){UUID slot=admission.acquire(org,id);admission.release(org,slot);}denied(429,()->admission.acquire(org,id));
        assertThat(operator.queryForObject("SELECT count(*) FROM integration_outbound_slots WHERE released_at IS NULL",Long.class)).isZero();assertThat(count("integration_outbound_slots")).isEqualTo(10);
        assertThatThrownBy(()->runtime.update("UPDATE integration_outbound_slots SET lease_expires_at=lease_expires_at+interval '1 hour'")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        clock.add(61);UUID recovered=admission.acquire(org,id);admission.release(org,recovered);
    }
    @Test void connectionAuditFailureRollsBackConfigurationAndVersion(){
        operator.execute("CREATE OR REPLACE FUNCTION test_demo_audit_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action='INTEGRATION_CREATED' THEN RAISE EXCEPTION 'Controlled audit failure'; END IF; RETURN NEW; END $$");operator.execute("CREATE TRIGGER test_demo_audit BEFORE INSERT ON organization_audit_events FOR EACH ROW EXECUTE FUNCTION test_demo_audit_failure()");
        assertThatThrownBy(()->connections.create(owner.id(),owner.family(),org,"Rolled back","","SUPPORT",tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode(),null)).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThat(count("integration_connections")).isZero();assertThat(count("integration_connection_versions")).isZero();
    }
    @Test void abandonedOutboundLeaseExpiresWithoutSleeping(){var json=tools.jackson.databind.json.JsonMapper.builder().build();UUID id=connections.create(owner.id(),owner.family(),org,"Recoverable admission","","SUPPORT",json.createObjectNode(),null).connection().id();admission.acquire(org,id);admission.acquire(org,id);denied(429,()->admission.acquire(org,id));clock.add(16);UUID recovered=admission.acquire(org,id);admission.release(org,recovered);assertThat(count("integration_operation_receipts")).isZero();}
}
