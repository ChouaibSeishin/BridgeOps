package com.bridgeops.browser;

import com.bridgeops.executions.application.*;
import com.bridgeops.identity.application.*;
import com.bridgeops.identity.domain.SessionOutcome.Granted;
import com.bridgeops.organizations.application.OrganizationService;
import com.bridgeops.support.TestAuthMaterial;
import com.sun.net.httpserver.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

/** Independent real two-user Chromium journey. Gateway/clock control exist in test sources only. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"server.address=127.0.0.1","bridgeops.auth.enabled=true","bridgeops.auth.local-http=true","bridgeops.auth.issuer=approval-browser","bridgeops.auth.key-id=approval-browser","bridgeops.executions.worker.enabled=true"})
@Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class ApprovalBrowserIT {
    static final TestAuthMaterial keys=new TestAuthMaterial();static final HttpServer gateway=gateway();static final ExecutorService threads=Executors.newFixedThreadPool(8);
    static final String ORIGIN="http://127.0.0.1:"+gateway.getAddress().getPort();static final String CONTROL=new RefreshTokens().generate();
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer(DockerImageName.parse("postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873").asCompatibleSubstituteFor("postgres")).withInitScript("organization-runtime.sql");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("bridgeops.auth.origin",()->ORIGIN);r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",()->"bridgeops_app");r.add("spring.datasource.password",()->"synthetic-runtime-fixture");r.add("spring.flyway.user",postgres::getUsername);r.add("spring.flyway.password",postgres::getPassword);r.add("bridgeops.auth.private-key",()->keys.directory.resolve("private.pem").toString());r.add("bridgeops.auth.public-key",()->keys.directory.resolve("public.pem").toString());r.add("bridgeops.auth.csrf-key",()->keys.directory.resolve("csrf.key").toString());}
    static class TestClock extends Clock {volatile Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}}
    @TestConfiguration static class Configuration {@Bean @Primary TestClock approvalBrowserClock(){return new TestClock();}}
    @Autowired TestClock clock;@Autowired ExecutionWorker worker;@Autowired ExecutionRuntime runtime;@Autowired AccountPasswords passwords;@Autowired PasswordSessionService sessions;@Autowired OrganizationService organizations;@Autowired Environment environment;
    static HttpServer gateway(){try{if(System.getProperty("os.name").startsWith("Windows")&&System.getProperty("jdk.net.unixdomain.tmpdir")==null)System.setProperty("jdk.net.unixdomain.tmpdir",Files.createDirectories(Path.of("target","approval-sockets")).toRealPath().toString());return HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);}catch(Exception unavailable){throw new IllegalStateException("Cannot bind disposable approval browser gateway.",unavailable);}}
    @AfterEach void stopWorker(){worker.stop();}
    @AfterAll static void cleanup()throws Exception{gateway.stop(0);threads.shutdownNow();keys.close();}
    @Test @Timeout(240) void realDistinctHumanApprovalPolicyAndDurableResumption()throws Exception{
        Path root=Path.of("").toAbsolutePath();if(root.getFileName().toString().equals("backend"))root=root.getParent();Path frontend=root.resolve("frontend/dist").normalize();assertThat(Files.isRegularFile(frontend.resolve("index.html"))).withFailMessage("Build production frontend and install Chromium before verify.").isTrue();
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));String password="approval-browser-disposable";var at=java.sql.Timestamp.from(clock.instant());String hash=passwords.hash(password);UUID owner=UUID.randomUUID(),approver=UUID.randomUUID();
        for(var actor:Map.of(owner,"owner@example.invalid",approver,"approver@example.invalid").entrySet())jdbc.update("INSERT INTO users(id,email,normalized_email,password_hash,display_name,account_status,created_at,email_verified_at) VALUES(?,?,?,?,?,'ACTIVE',?,?)",actor.getKey(),actor.getValue(),actor.getValue(),hash,actor.getKey().equals(owner)?"Morgan Requester":"Jamie Reviewer",at,at);
        jdbc.update("INSERT INTO user_grants VALUES(?,'organization.create',?)",owner,at);var grant=(Granted)sessions.authenticate("owner@example.invalid",password);UUID org=organizations.create(owner,grant.familyId(),"Governed operations","governed-browser").id();UUID foreign=organizations.create(owner,grant.familyId(),"Foreign operations","foreign-approval-browser").id();UUID membership=UUID.randomUUID();jdbc.update("INSERT INTO organization_memberships(id,organization_id,user_id,status,joined_at) VALUES(?,?,?,'ACTIVE',?)",membership,org,approver,at);jdbc.update("INSERT INTO membership_roles SELECT ?,?,id FROM roles WHERE organization_id=? AND name='APPROVER'",org,membership,org);
        int port=environment.getRequiredProperty("local.server.port",Integer.class);var proxy=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();gateway.setExecutor(threads);gateway.createContext("/",exchange->{try{
            String path=exchange.getRequestURI().getPath();exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");exchange.getResponseHeaders().set("Referrer-Policy","no-referrer");exchange.getResponseHeaders().set("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'");
            if(path.equals("/__test/approval-clock")){
                if(!exchange.getRequestMethod().equals("POST")||!java.security.MessageDigest.isEqual(CONTROL.getBytes(StandardCharsets.UTF_8),Objects.toString(exchange.getRequestHeaders().getFirst("X-Test-Control"),"").getBytes(StandardCharsets.UTF_8))){reply(exchange,404,null,new byte[0]);return;}
                var value=new ObjectMapper().readTree(exchange.getRequestBody().readNBytes(1025));long seconds=value.path("seconds").asLong(-1);if(seconds<0||seconds>86400){reply(exchange,400,null,new byte[0]);return;}clock.now=clock.now.plusSeconds(seconds);runtime.reconcile();reply(exchange,204,null,new byte[0]);return;
            }
            if(path.startsWith("/api/")||path.startsWith("/health/")){
                String target=exchange.getRequestURI().toASCIIString();if(path.startsWith("/health/"))target="/actuator"+target;var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+target)).timeout(Duration.ofSeconds(20));
                for(String name:List.of("Origin","Cookie","Authorization","X-CSRF-TOKEN","Content-Type","Accept","If-Match","Idempotency-Key","Sec-Fetch-Site")){var values=exchange.getRequestHeaders().get(name);if(values!=null)for(String value:values)request.header(name,value);}
                var response=proxy.send(request.method(exchange.getRequestMethod(),HttpRequest.BodyPublishers.ofByteArray(exchange.getRequestBody().readAllBytes())).build(),HttpResponse.BodyHandlers.ofByteArray());for(String name:List.of("Content-Type","Cache-Control","Pragma","Set-Cookie","X-Correlation-ID","Retry-After")){var values=response.headers().allValues(name);if(!values.isEmpty())exchange.getResponseHeaders().put(name,new ArrayList<>(values));}reply(exchange,response.statusCode(),null,response.body());return;
            }
            if(!exchange.getRequestMethod().equals("GET")){reply(exchange,404,null,new byte[0]);return;}Path asset=frontend.resolve(path.substring(1)).normalize();if(!asset.startsWith(frontend)){reply(exchange,404,null,new byte[0]);return;}if(!Files.isRegularFile(asset))asset=frontend.resolve("index.html");String name=asset.getFileName().toString();reply(exchange,200,name.endsWith(".js")?"application/javascript":name.endsWith(".css")?"text/css":name.endsWith(".svg")?"image/svg+xml":"text/html",Files.readAllBytes(asset));
        }catch(Exception failure){if(exchange.getResponseCode()<0)reply(exchange,502,"text/plain","Disposable approval gateway unavailable.".getBytes(StandardCharsets.UTF_8));}finally{exchange.close();}});gateway.start();
        Path results=root.resolve("backend/target/browser-checks");Files.createDirectories(results);var command=new ProcessBuilder(System.getenv().getOrDefault("BRIDGEOPS_TEST_NODE","node"),root.resolve("frontend/e2e/approvals.mjs").toString()).directory(root.toFile()).redirectErrorStream(true).redirectOutput(results.resolve("approvals.log").toFile());command.environment().put("BRIDGEOPS_TEST_ORIGIN",ORIGIN);command.environment().put("BRIDGEOPS_TEST_CONTROL",CONTROL);command.environment().put("BRIDGEOPS_TEST_ORG",org.toString());command.environment().put("BRIDGEOPS_TEST_FOREIGN_ORG",foreign.toString());command.environment().put("BRIDGEOPS_TEST_PASSWORD",password);
        var process=command.start();try{boolean finished=process.waitFor(180,TimeUnit.SECONDS);if(!finished)process.destroyForcibly();assertThat(finished).withFailMessage("Approval browser timed out; inspect ignored redacted diagnostics.").isTrue();if(process.exitValue()!=0)try(var lines=Files.lines(results.resolve("approvals.log"))){lines.filter(l->l.startsWith("STEP:")||l.startsWith("FAIL:")||l.startsWith("AT:")).forEach(System.out::println);}assertThat(process.exitValue()).withFailMessage("Approval browser failed; see ignored browser-checks/approvals.log.").isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM workflows",Long.class)).isEqualTo(2);assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_versions",Long.class)).isEqualTo(2);assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_executions",Long.class)).isEqualTo(6);assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_executions WHERE status='SUCCEEDED'",Long.class)).isEqualTo(2);assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_executions WHERE status='FAILED'",Long.class)).isEqualTo(3);assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_executions WHERE status='CANCELLED'",Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_requests",Long.class)).isEqualTo(6);assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_decisions",Long.class)).isEqualTo(3);assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_signals",Long.class)).isEqualTo(6);assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_signal_receipts",Long.class)).isEqualTo(6);assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_policy_versions",Long.class)).isEqualTo(4);assertThat(jdbc.queryForObject("SELECT count(*) FROM synthetic_records",Long.class)).isEqualTo(7);assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_operation_receipts",Long.class)).isEqualTo(7);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_decisions d JOIN approval_requests r ON r.organization_id=d.organization_id AND r.id=d.request_id WHERE d.decided_by=r.requested_by",Long.class)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM organization_audit_events WHERE action='APPROVAL_SIGNAL_CONSUMED'",Long.class)).isEqualTo(6);
        }finally{if(process.isAlive())process.destroyForcibly();}
    }
    static void reply(HttpExchange exchange,int status,String mime,byte[] bytes)throws java.io.IOException{if(mime!=null)exchange.getResponseHeaders().set("Content-Type",mime);exchange.sendResponseHeaders(status,status==204?-1:bytes.length);if(status!=204)exchange.getResponseBody().write(bytes);}
}
