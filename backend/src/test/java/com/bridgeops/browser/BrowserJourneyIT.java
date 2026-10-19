package com.bridgeops.browser;
import com.bridgeops.identity.application.*;
import com.bridgeops.executions.application.SyntheticCapability;
import com.bridgeops.identity.bootstrap.BootstrapProvisioner;
import com.bridgeops.identity.domain.SessionOutcome.Granted;
import com.bridgeops.organizations.application.OrganizationService;
import com.bridgeops.shared.mail.OutboundEmail;
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
import org.springframework.jdbc.datasource.*;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
/** Real browser + real API; gateway, operator fixtures and mailbox exist only in test sources. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"server.address=127.0.0.1","bridgeops.auth.enabled=true","bridgeops.auth.local-http=true","bridgeops.auth.issuer=browser-test","bridgeops.auth.key-id=browser-test","bridgeops.executions.worker.enabled=true"})
@Testcontainers @Import(BrowserJourneyIT.Configuration.class)
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class BrowserJourneyIT {
 static final TestAuthMaterial keys=new TestAuthMaterial();static final HttpServer gateway=gateway();static final ExecutorService gatewayThreads=Executors.newFixedThreadPool(8);
 static final String ORIGIN="http://127.0.0.1:"+gateway.getAddress().getPort();static final String CONTROL=new RefreshTokens().generate();
 @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer(DockerImageName.parse("postgres@sha256:77f585114c32fbca283dc835b0596f4e52b51b4c6662d7810b2f4084f60a1873").asCompatibleSubstituteFor("postgres")).withInitScript("organization-runtime.sql");
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("bridgeops.auth.origin",()->ORIGIN);r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",()->"bridgeops_app");r.add("spring.datasource.password",()->"synthetic-runtime-fixture");r.add("spring.flyway.user",postgres::getUsername);r.add("spring.flyway.password",postgres::getPassword);r.add("bridgeops.auth.private-key",()->keys.directory.resolve("private.pem").toString());r.add("bridgeops.auth.public-key",()->keys.directory.resolve("public.pem").toString());r.add("bridgeops.auth.csrf-key",()->keys.directory.resolve("csrf.key").toString());}
 @Autowired com.bridgeops.executions.application.ExecutionWorker liveWorker;
 @AfterEach void stopLiveWorker(){liveWorker.stop();}
 @Autowired BrowserCapability browserCapability;@Autowired Environment environment;@Autowired MailCapture mailbox;@Autowired AccountPasswords passwords;@Autowired PasswordSessionService sessions;@Autowired OrganizationService organizations;
 static HttpServer gateway(){try{
  // Windows short-name TEMP paths can break the JDK selector wakeup socket. Test JVM only.
  if(System.getProperty("os.name").startsWith("Windows") && System.getProperty("jdk.net.unixdomain.tmpdir")==null){Path sockets=Files.createDirectories(Path.of("target","sockets")).toRealPath();System.setProperty("jdk.net.unixdomain.tmpdir",sockets.toString());}
  return HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);}catch(Exception failure){throw new IllegalStateException("Cannot bind disposable browser gateway.",failure);}}
 @Test @Timeout(240) void realBrowserOnboardingRecoveryAndIsolation()throws Exception{
  Path root=Path.of("").toAbsolutePath();if(root.getFileName().toString().equals("backend"))root=root.getParent();Path frontend=root.resolve("frontend/dist").normalize();assertThat(Files.isRegularFile(frontend.resolve("index.html"))).withFailMessage("Build frontend assets and install Playwright Chromium before backend verify.").isTrue();
  var operator=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());var jdbc=new JdbcTemplate(operator);String password="browser-disposable-password";
  new BootstrapProvisioner(jdbc,new TransactionTemplate(new DataSourceTransactionManager(operator)),passwords,Clock.systemUTC()).provision("owner@example.invalid","Alex Morgan",password.toCharArray());
  UUID outsider=UUID.randomUUID();var now=java.sql.Timestamp.from(Instant.now());jdbc.update("INSERT INTO users(id,email,normalized_email,password_hash,display_name,account_status,created_at,email_verified_at) VALUES(?,?,?,?,'Outside account','ACTIVE',?,?)",outsider,"outside@example.invalid","outside@example.invalid",passwords.hash(password),now,now);jdbc.update("INSERT INTO user_grants VALUES(?,'organization.create',?)",outsider,now);var grant=(Granted)sessions.authenticate("outside@example.invalid",password);var foreign=organizations.create(outsider,grant.familyId(),"Outside tenant","outside-browser-fixture");
  int backendPort=environment.getRequiredProperty("local.server.port",Integer.class);HttpClient proxy=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
  gateway.setExecutor(gatewayThreads);gateway.createContext("/",exchange->{try{
   String path=exchange.getRequestURI().getPath();exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");exchange.getResponseHeaders().set("Referrer-Policy","no-referrer");exchange.getResponseHeaders().set("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'");
   if(path.equals("/__test/execution-failure")){
    if(!exchange.getRequestMethod().equals("POST") || !java.security.MessageDigest.isEqual(CONTROL.getBytes(StandardCharsets.UTF_8),Objects.toString(exchange.getRequestHeaders().getFirst("X-Test-Control"),"").getBytes(StandardCharsets.UTF_8))){reply(exchange,404,"text/plain",new byte[0]);return;}
    browserCapability.remaining.set(3);reply(exchange,204,null,new byte[0]);return;
   }
   if(path.equals("/__test/mail")){
    if(!exchange.getRequestMethod().equals("POST") || !java.security.MessageDigest.isEqual(CONTROL.getBytes(StandardCharsets.UTF_8),Objects.toString(exchange.getRequestHeaders().getFirst("X-Test-Control"),"").getBytes(StandardCharsets.UTF_8))){reply(exchange,404,"text/plain",new byte[0]);return;}
    var body=new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes());var message=mailbox.messages.stream().filter(m->m.recipient().equals(body.path("recipient").asString()) && m.subject().equals(body.path("subject").asString())).reduce((a,b)->b);
    byte[] value=new ObjectMapper().writeValueAsBytes(message.<Object>map(m->Map.of("token",m.text().lines().filter(line->line.matches("[A-Za-z0-9_-]{43}")).findFirst().orElseThrow())).orElse(Map.of()));reply(exchange,200,"application/json",value);return;
   }
   if(path.startsWith("/api/") || path.startsWith("/health/")){
    String target=exchange.getRequestURI().toASCIIString();if(path.startsWith("/health/"))target="/actuator"+target;
    var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+backendPort+target)).timeout(Duration.ofSeconds(20));
    for(String name:List.of("Origin","Cookie","Authorization","X-CSRF-TOKEN","Content-Type","Accept","If-Match","Idempotency-Key","Sec-Fetch-Site")){var values=exchange.getRequestHeaders().get(name);if(values!=null)for(String value:values)request.header(name,value);}
    var response=proxy.send(request.method(exchange.getRequestMethod(),HttpRequest.BodyPublishers.ofByteArray(exchange.getRequestBody().readAllBytes())).build(),HttpResponse.BodyHandlers.ofByteArray());
    for(String name:List.of("Content-Type","Cache-Control","Pragma","Set-Cookie","X-Correlation-ID","Retry-After")){var values=response.headers().allValues(name);if(!values.isEmpty())exchange.getResponseHeaders().put(name,new ArrayList<>(values));}
    reply(exchange,response.statusCode(),null,response.body());return;
   }
   if(!exchange.getRequestMethod().equals("GET")){reply(exchange,404,"text/plain",new byte[0]);return;}
   Path asset=frontend.resolve(path.substring(1)).normalize();if(!asset.startsWith(frontend)){reply(exchange,404,"text/plain",new byte[0]);return;}if(!Files.isRegularFile(asset))asset=frontend.resolve("index.html");String name=asset.getFileName().toString();String mime=name.endsWith(".js")?"application/javascript":name.endsWith(".css")?"text/css":name.endsWith(".svg")?"image/svg+xml":"text/html";reply(exchange,200,mime,Files.readAllBytes(asset));
  }catch(Exception failure){if(exchange.getResponseCode()<0)reply(exchange,502,"text/plain","Disposable gateway unavailable.".getBytes(StandardCharsets.UTF_8));}finally{exchange.close();}});gateway.start();
  Path results=root.resolve("backend/target/browser-checks");Files.createDirectories(results);var command=new ProcessBuilder(System.getenv().getOrDefault("BRIDGEOPS_TEST_NODE","node"),root.resolve("frontend/e2e/journey.mjs").toString()).directory(root.toFile()).redirectErrorStream(true).redirectOutput(results.resolve("journey.log").toFile());command.environment().put("BRIDGEOPS_TEST_ORIGIN",ORIGIN);command.environment().put("BRIDGEOPS_TEST_CONTROL",CONTROL);command.environment().put("BRIDGEOPS_TEST_FOREIGN_ORG",foreign.id().toString());
  var process=command.start();try{
   boolean completed=process.waitFor(180,TimeUnit.SECONDS);if(!completed)process.destroyForcibly();assertThat(completed).withFailMessage("Browser journey timed out; see ignored browser-checks diagnostics.").isTrue();
   if(process.exitValue()!=0){try(var lines=Files.lines(results.resolve("journey.log"))){lines.filter(line->line.startsWith("STEP:") || line.startsWith("FAIL:") || line.startsWith("HTTP:") || line.startsWith("LAYOUT:")).forEach(System.out::println);}}
   assertThat(process.exitValue()).withFailMessage("Browser journey failed; inspect backend/target/browser-checks/journey.log (redacted step diagnostics).").isZero();
   assertThat(jdbc.queryForObject("SELECT count(*) FROM workflows WHERE name IN ('Ticket Dispatch','Independent dispatch draft')",Integer.class)).isEqualTo(2);
   assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_versions v JOIN workflows w ON w.organization_id=v.organization_id AND w.id=v.workflow_id WHERE w.name='Ticket Dispatch'",Integer.class)).isEqualTo(2);
   assertThat(jdbc.queryForList("SELECT v.source_revision FROM workflow_versions v JOIN workflows w ON w.organization_id=v.organization_id AND w.id=v.workflow_id WHERE w.name='Ticket Dispatch' ORDER BY v.version_number",Long.class)).containsExactly(2L,4L);
   assertThat(jdbc.queryForObject("SELECT count(*) FROM workflows WHERE status='ARCHIVED'",Integer.class)).isEqualTo(1);
   assertThat(jdbc.queryForObject("SELECT count(*) FROM organization_audit_events WHERE action='WORKFLOW_PUBLISHED'",Integer.class)).isEqualTo(4);
   assertThat(jdbc.queryForObject("SELECT count(*) FROM workflows w JOIN organizations o ON o.id=w.organization_id WHERE o.slug='outside-browser-fixture'",Integer.class)).isZero();
   // Phase 2 definition operations still create no execution; new runtime fixtures are asserted separately.
   assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_executions e JOIN workflows w ON w.organization_id=e.organization_id AND w.id=e.workflow_id WHERE w.name IN ('Ticket Dispatch','Independent dispatch draft','Approval definition only')",Integer.class)).isZero();
   assertThat(jdbc.queryForObject("SELECT count(*) FROM workflows",Integer.class)).isEqualTo(4);
   assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_versions",Integer.class)).isEqualTo(4);
   assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_executions",Integer.class)).isEqualTo(4);
   assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_executions WHERE status='SUCCEEDED'",Integer.class)).isEqualTo(2);
   assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_executions WHERE status='FAILED' AND error_code='TRANSIENT_EXHAUSTED'",Integer.class)).isEqualTo(1);
   assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_executions WHERE status='CANCELLED'",Integer.class)).isEqualTo(1);
   assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_executions WHERE retry_of_execution_id IS NOT NULL",Integer.class)).isEqualTo(1);
   assertThat(jdbc.queryForObject("SELECT count(*) FROM synthetic_records",Integer.class)).isEqualTo(4);
   assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_operation_receipts",Integer.class)).isEqualTo(4);
   assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_events WHERE code='RECEIPT_REUSED'",Integer.class)).isEqualTo(1);
   // V11 introduces approval tables; the retained Phase 1–3 journey must create no governance requests or decisions.
   assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_requests",Integer.class)).isZero();
   assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_decisions",Integer.class)).isZero();
   assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_signals",Integer.class)).isZero();
   assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='work_items'",Integer.class)).isZero();
  }finally{if(process.isAlive())process.destroyForcibly();}
 }
 static void reply(HttpExchange exchange,int status,String mime,byte[] bytes)throws java.io.IOException{if(mime!=null)exchange.getResponseHeaders().set("Content-Type",mime);exchange.sendResponseHeaders(status,status==204?-1:bytes.length);if(status!=204)exchange.getResponseBody().write(bytes);}
 @AfterAll static void cleanup()throws Exception{gateway.stop(0);gatewayThreads.shutdownNow();keys.close();}
 record Message(String recipient,String subject,String text){@Override public String toString(){return "Message[REDACTED]";}}
 static class MailCapture implements OutboundEmail{final List<Message> messages=new CopyOnWriteArrayList<>();public void send(String recipient,String subject,String text){messages.add(new Message(recipient,subject,text));}}
 static class BrowserCapability extends SyntheticCapability { final java.util.concurrent.atomic.AtomicInteger remaining=new java.util.concurrent.atomic.AtomicInteger(); @Override public void prepare(Invocation call){if(call.node().equals("retry-step") && remaining.getAndUpdate(n->Math.max(0,n-1))>0)throw new Failure(FailureKind.TRANSIENT);} }
 @TestConfiguration static class Configuration{@Bean @Primary BrowserCapability browserCapability(){return new BrowserCapability();}@Bean @Primary MailCapture browserMailCapture(){return new MailCapture();}}
}
