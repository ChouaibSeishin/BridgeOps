package com.bridgeops.integrations.infrastructure;

import com.bridgeops.executions.application.*;
import com.bridgeops.identity.application.*;
import com.bridgeops.identity.domain.SessionOutcome.Granted;
import com.bridgeops.integrations.application.ConnectionService;
import com.bridgeops.organizations.application.OrganizationService;
import com.bridgeops.support.TestAuthMaterial;
import com.bridgeops.workflows.application.WorkflowService;
import com.bridgeops.workflows.domain.GraphDefinition;
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
import static org.assertj.core.api.Assertions.*;

/** Browser, application, PostgreSQL and TLS provider are all isolated fixtures; no production traffic. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"server.address=127.0.0.1","bridgeops.auth.enabled=true","bridgeops.auth.local-http=true","bridgeops.auth.issuer=rest-browser","bridgeops.auth.key-id=rest-browser","bridgeops.executions.worker.enabled=true","bridgeops.integrations.rest.allowed-hosts=api.example.com"})
@Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class RestWorkflowBrowserIT {
    static final TestAuthMaterial keys=new TestAuthMaterial();static final Path encryptionKey=encryptionKey();
    static Path encryptionKey(){try{Path file=keys.directory.resolve("local-v1.key");byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);Files.write(file,key);Arrays.fill(key,(byte)0);if(Files.getFileStore(file).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(file,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));return file;}catch(Exception unavailable){throw new ExceptionInInitializerError(unavailable);}}
    static final RestTransport provider=provider();static RestTransport provider(){try{RestTransportTest.tlsFixture();return new RestTransportTest().fixture();}catch(Exception unavailable){throw new ExceptionInInitializerError(unavailable);}}
    static final HttpServer gateway=gateway();static final ExecutorService threads=Executors.newFixedThreadPool(8);static final String ORIGIN="http://127.0.0.1:"+gateway.getAddress().getPort();
    static HttpServer gateway(){try{return HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);}catch(Exception unavailable){throw new IllegalStateException("Cannot bind isolated browser gateway.");}}
    @TestConfiguration static class Configuration {@Bean @Primary RestTransport fixtureTransport(){return provider;}}
    @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer(DockerImageName.parse("postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873").asCompatibleSubstituteFor("postgres")).withInitScript("organization-runtime.sql");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("bridgeops.auth.origin",()->ORIGIN);r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",()->"bridgeops_app");r.add("spring.datasource.password",()->"synthetic-runtime-fixture");r.add("spring.flyway.user",postgres::getUsername);r.add("spring.flyway.password",postgres::getPassword);r.add("bridgeops.auth.private-key",()->keys.directory.resolve("private.pem").toString());r.add("bridgeops.auth.public-key",()->keys.directory.resolve("public.pem").toString());r.add("bridgeops.auth.csrf-key",()->keys.directory.resolve("csrf.key").toString());r.add("bridgeops.integrations.secrets.directory",()->keys.directory.toString());}
    @Autowired AccountPasswords passwords;@Autowired PasswordSessionService sessions;@Autowired OrganizationService organizations;@Autowired ConnectionService connections;@Autowired WorkflowService workflows;@Autowired GraphDefinition graphs;@Autowired Environment environment;@Autowired ExecutionWorker worker;
    @AfterEach void stopWorker(){worker.stop();}
    @AfterAll static void cleanup()throws Exception{gateway.stop(0);threads.shutdownNow();RestTransportTest.stop();Files.deleteIfExists(encryptionKey);keys.close();}
    @Test @Timeout(240) void realBrowserAcknowledgesAndApprovesTheExactExternalOperation()throws Exception{
        Path root=Path.of("").toAbsolutePath();if(root.getFileName().toString().equals("backend"))root=root.getParent();Path frontend=root.resolve("frontend/dist");assertThat(Files.isRegularFile(frontend.resolve("index.html"))).isTrue();var db=new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));String password="rest-browser-disposable-password",hash=passwords.hash(password);UUID owner=UUID.randomUUID(),reviewer=UUID.randomUUID();
        for(var actor:Map.of(owner,"owner@example.invalid",reviewer,"approver@example.invalid").entrySet())db.update("INSERT INTO users(id,email,normalized_email,password_hash,display_name,account_status,created_at,email_verified_at) VALUES(?,?,?,?,?,'ACTIVE',now(),now())",actor.getKey(),actor.getValue(),actor.getValue(),hash,actor.getKey().equals(owner)?"Morgan Requester":"Jamie Reviewer");db.update("INSERT INTO user_grants VALUES(?,'organization.create',now())",owner);var grant=(Granted)sessions.authenticate("owner@example.invalid",password);UUID org=organizations.create(owner,grant.familyId(),"Controlled provider operations","rest-browser").id(),membership=UUID.randomUUID();db.update("INSERT INTO organization_memberships(id,organization_id,user_id,status,joined_at) VALUES(?,?,?,'ACTIVE',now())",membership,org,reviewer);db.update("INSERT INTO membership_roles SELECT ?,?,id FROM roles WHERE organization_id=? AND name='APPROVER'",org,membership,org);
        var config=graphs.parse("""
          {"baseDestination":"https://api.example.com","healthOperation":null,"operations":[{"id":"operation","method":"POST","path":"/json","pathParameters":[],"requestFields":{"message":"STRING"},"responseFields":{"message":"STRING"},"classification":"WRITE","timeoutSeconds":5,"maximumResponseBytes":4096,"remoteIdempotencyKey":true}]}
          """);var connection=connections.create(owner,grant.familyId(),org,"Controlled TLS provider","Disposable provider only","REST",config,"disposable-browser-provider-credential").connection();
        var graph=graphs.parse("""
          {"schemaVersion":2,"nodes":[{"id":"start","type":"manualTrigger","typeVersion":1,"config":{"inputFields":{}},"inputs":{}},{"id":"http","type":"configuredHttp","typeVersion":1,"config":{"integrationId":"%s","revision":0,"operation":"operation"},"inputs":{"pathParameters":{"literal":{}},"fields":{"literal":{"message":"Approved browser payload"}}}},{"id":"end","type":"end","typeVersion":1,"config":{},"inputs":{}}],"edges":[{"source":"start","target":"http"},{"source":"http","target":"end"}]}
          """.formatted(connection.id()));UUID workflow=workflows.create(owner,grant.familyId(),org,"Controlled external browser workflow","",graph).workflow().id();workflows.publish(owner,grant.familyId(),org,workflow,0);
        int port=environment.getRequiredProperty("local.server.port",Integer.class);HttpClient proxy=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();gateway.setExecutor(threads);gateway.createContext("/",exchange->{try{
            String path=exchange.getRequestURI().getPath();exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");exchange.getResponseHeaders().set("Referrer-Policy","no-referrer");
            if(path.startsWith("/api/")||path.startsWith("/health/")){String target=exchange.getRequestURI().toASCIIString();if(path.startsWith("/health/"))target="/actuator"+target;var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+target)).timeout(Duration.ofSeconds(20));for(String name:List.of("Origin","Cookie","Authorization","X-CSRF-TOKEN","Content-Type","Accept","Idempotency-Key","Sec-Fetch-Site")){var values=exchange.getRequestHeaders().get(name);if(values!=null)for(String value:values)request.header(name,value);}var response=proxy.send(request.method(exchange.getRequestMethod(),HttpRequest.BodyPublishers.ofByteArray(exchange.getRequestBody().readAllBytes())).build(),HttpResponse.BodyHandlers.ofByteArray());for(String name:List.of("Content-Type","Cache-Control","Pragma","Set-Cookie","X-Correlation-ID","Retry-After","Location")){var values=response.headers().allValues(name);if(!values.isEmpty())exchange.getResponseHeaders().put(name,new ArrayList<>(values));}reply(exchange,response.statusCode(),null,response.body());return;}
            if(!exchange.getRequestMethod().equals("GET")){reply(exchange,404,null,new byte[0]);return;}Path asset=frontend.resolve(path.substring(1)).normalize();if(!asset.startsWith(frontend)){reply(exchange,404,null,new byte[0]);return;}if(!Files.isRegularFile(asset))asset=frontend.resolve("index.html");String name=asset.getFileName().toString();reply(exchange,200,name.endsWith(".js")?"application/javascript":name.endsWith(".css")?"text/css":name.endsWith(".svg")?"image/svg+xml":"text/html",Files.readAllBytes(asset));
        }catch(Exception failure){if(exchange.getResponseCode()<0)reply(exchange,502,"text/plain","Isolated browser gateway unavailable.".getBytes(StandardCharsets.UTF_8));}finally{exchange.close();}});gateway.start();
        Path results=root.resolve("backend/target/browser-checks");Files.createDirectories(results);var command=new ProcessBuilder(System.getenv().getOrDefault("BRIDGEOPS_TEST_NODE","node"),root.resolve("frontend/e2e/rest.mjs").toString()).directory(root.toFile()).redirectErrorStream(true).redirectOutput(results.resolve("rest.log").toFile());command.environment().put("BRIDGEOPS_TEST_ORIGIN",ORIGIN);command.environment().put("BRIDGEOPS_TEST_ORG",org.toString());command.environment().put("BRIDGEOPS_TEST_WORKFLOW",workflow.toString());command.environment().put("BRIDGEOPS_TEST_PASSWORD",password);RestTransportTest.requests.set(0);
        var process=command.start();try{assertThat(process.waitFor(180,TimeUnit.SECONDS)).withFailMessage("REST browser timed out; inspect ignored redacted diagnostics.").isTrue();if(process.exitValue()!=0)try(var lines=Files.lines(results.resolve("rest.log"))){lines.filter(l->l.startsWith("STEP:")||l.startsWith("FAIL:")||l.startsWith("AT:")).forEach(System.out::println);}assertThat(process.exitValue()).withFailMessage("REST browser failed; see ignored browser-checks/rest.log.").isZero();assertThat(RestTransportTest.requests.get()).isEqualTo(1);assertThat(db.queryForObject("SELECT count(*) FROM integration_outbound_dispatches",Long.class)).isEqualTo(1);assertThat(db.queryForObject("SELECT count(*) FROM integration_outbound_outcomes WHERE outcome='SUCCEEDED'",Long.class)).isEqualTo(1);assertThat(db.queryForObject("SELECT count(*) FROM integration_operation_receipts",Long.class)).isEqualTo(1);assertThat(db.queryForObject("SELECT count(*) FROM workflow_executions WHERE status='SUCCEEDED'",Long.class)).isEqualTo(1);assertThat(db.queryForObject("SELECT count(*) FROM approval_decisions WHERE decided_by=?",Long.class,reviewer)).isEqualTo(1);assertThat(db.queryForObject("SELECT result_json FROM integration_operation_receipts",String.class)).isEqualTo("{\"response\":{\"message\":\"ok\"}}");}finally{if(process.isAlive())process.destroyForcibly();}
    }
    static void reply(HttpExchange exchange,int status,String mime,byte[] body)throws java.io.IOException{if(mime!=null)exchange.getResponseHeaders().set("Content-Type",mime);exchange.sendResponseHeaders(status,status==204?-1:body.length);if(status!=204)exchange.getResponseBody().write(body);}
}
