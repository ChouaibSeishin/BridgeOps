package com.bridgeops.integrations.infrastructure;

import com.bridgeops.integrations.domain.*;
import com.bridgeops.shared.web.DomainFailure;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.*;
import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** All private sockets and custom trust are disposable fixtures, not production configuration. */
@ExtendWith(OutputCaptureExtension.class)
class RestTransportTest {
    static HttpsServer server;static ExecutorService serverPool;static SSLContext tls;static Path directory;
    static final AtomicInteger resolverCalls=new AtomicInteger(),requests=new AtomicInteger(),redirectTargets=new AtomicInteger();
    static final JsonMapper json=JsonMapper.builder().build();
    @BeforeAll static void tlsFixture()throws Exception{
        if(System.getProperty("os.name").startsWith("Windows")&&System.getProperty("jdk.net.unixdomain.tmpdir")==null)System.setProperty("jdk.net.unixdomain.tmpdir",Files.createDirectories(Path.of("target","rest-sockets")).toRealPath().toString());
        directory=Files.createTempDirectory(Path.of("target"),"rest-tls-");Path store=directory.resolve("fixture.p12"),log=directory.resolve("keytool.log");
        String keytool=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"keytool.exe":"keytool").toString();
        Process process=new ProcessBuilder(keytool,"-genkeypair","-alias","fixture","-keyalg","RSA","-keysize","2048","-storetype","PKCS12","-keystore",store.toString(),"-storepass","disposable-fixture-password","-keypass","disposable-fixture-password","-dname","CN=api.example.com","-ext","SAN=dns:api.example.com","-validity","2","-noprompt").redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if(!process.waitFor(20,TimeUnit.SECONDS)||process.exitValue()!=0){process.destroyForcibly();throw new IllegalStateException("Disposable TLS fixture generation failed.");}
        var keys=KeyStore.getInstance("PKCS12");try(var input=Files.newInputStream(store)){keys.load(input,"disposable-fixture-password".toCharArray());}
        var km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keys,"disposable-fixture-password".toCharArray());var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(keys);
        tls=SSLContext.getInstance("TLS");tls.init(km.getKeyManagers(),tm.getTrustManagers(),null);
        server=HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);server.setHttpsConfigurator(new HttpsConfigurator(tls));serverPool=Executors.newFixedThreadPool(3);server.setExecutor(serverPool);
        server.createContext("/",exchange->{
            requests.incrementAndGet();String path=exchange.getRequestURI().getPath();exchange.getRequestBody().readNBytes(4097);
            if(path.equals("/drop")){exchange.close();return;}
            if(path.equals("/slow")){try{new CountDownLatch(1).await(3,TimeUnit.SECONDS);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}}
            if(path.equals("/redirect")){exchange.getResponseHeaders().set("Location","https://api.example.com/target");exchange.sendResponseHeaders(302,-1);exchange.close();return;}
            if(path.equals("/target"))redirectTargets.incrementAndGet();
            String message=path.equals("/echo")?exchange.getRequestHeaders().getFirst("Authorization"):"ok";
            byte[] body=path.equals("/bad-json")?"{invalid".getBytes(StandardCharsets.UTF_8):path.equals("/large")?"x".repeat(5000).getBytes(StandardCharsets.UTF_8):json.writeValueAsBytes(Map.of("message",message,"ignoredSecret","not-projected"));
            exchange.getResponseHeaders().set("Content-Type",path.equals("/text")?"text/plain":"application/json");
            try{exchange.sendResponseHeaders(path.equals("/unavailable")?503:200,path.equals("/large")?0:body.length);try(var output=exchange.getResponseBody()){output.write(body);}}catch(java.io.IOException cancelled){exchange.close();}
        });server.start();
    }
    @AfterAll static void stop(){if(server!=null)server.stop(0);if(serverPool!=null)serverPool.shutdownNow();}
    @BeforeEach void counters(){resolverCalls.set(0);requests.set(0);redirectTargets.set(0);}
    RestTransport fixture(){var dns=new DnsResolver(){public InetAddress[] resolve(String host)throws UnknownHostException{resolverCalls.incrementAndGet();return new InetAddress[]{InetAddress.getLoopbackAddress()};}public String resolveCanonicalHostname(String host){return host;}};return new RestTransport(new DestinationPolicy(List.of("api.example.com","other.example.com")),tls,dns,host->server.getAddress().getPort());}
    RestOperation operation(String method,String path,int timeout,int limit){return new RestOperation("fixture",method,path,Set.of(),Map.of(),Map.of("message","STRING"),method.equals("GET")?"READ":"WRITE",timeout,limit,true);}
    JsonNode arguments(){return json.readTree("{\"pathParameters\":{},\"fields\":{}}");}
    @Test void actualTlsConnectionUsesProvidedAddressAndOriginalHostname()throws Exception{var transport=fixture();try{var result=transport.call("https://api.example.com/",operation("GET","/json",5,4096),arguments(),null,UUID.randomUUID());assertThat(result.path("message").asString()).isEqualTo("ok");assertThat(result.has("ignoredSecret")).isFalse();assertThat(resolverCalls.get()).isEqualTo(1);assertThat(requests.get()).isEqualTo(1);}finally{transport.destroy();}}
    @Test void certificateHostnameMismatchCannotBeBypassed(){var transport=fixture();try{assertThatThrownBy(()->transport.call("https://other.example.com/",operation("GET","/json",5,4096),arguments(),null,UUID.randomUUID())).isInstanceOfSatisfying(RestFailure.class,f->assertThat(f.code()).isEqualTo("PROVIDER_UNAVAILABLE"));assertThat(requests.get()).isZero();}finally{transport.destroy();}}
    @Test void untrustedCertificateIsRejectedEvenWithTheCorrectHostname(){var dns=new DnsResolver(){public InetAddress[] resolve(String host){return new InetAddress[]{InetAddress.getLoopbackAddress()};}public String resolveCanonicalHostname(String host){return host;}};var transport=new RestTransport(new DestinationPolicy(List.of("api.example.com")),org.apache.hc.core5.ssl.SSLContexts.createSystemDefault(),dns,host->server.getAddress().getPort());try{assertThatThrownBy(()->transport.call("https://api.example.com/",operation("GET","/json",5,4096),arguments(),null,UUID.randomUUID())).isInstanceOf(RestFailure.class);assertThat(requests.get()).isZero();}finally{transport.destroy();}}
    @Test void proxySystemPropertiesCannotOverrideTheConnectionRoute(){String beforeHost=System.getProperty("https.proxyHost"),beforePort=System.getProperty("https.proxyPort");System.setProperty("https.proxyHost","127.0.0.1");System.setProperty("https.proxyPort","1");var transport=fixture();try{assertThat(transport.call("https://api.example.com/",operation("GET","/json",5,4096),arguments(),null,UUID.randomUUID()).path("message").asString()).isEqualTo("ok");assertThat(requests.get()).isEqualTo(1);}finally{transport.destroy();if(beforeHost==null)System.clearProperty("https.proxyHost");else System.setProperty("https.proxyHost",beforeHost);if(beforePort==null)System.clearProperty("https.proxyPort");else System.setProperty("https.proxyPort",beforePort);}}
    @Test void redirectsAreNotFollowedAndCredentialsCannotReachAnotherRequest(){var transport=fixture();try{assertThatThrownBy(()->transport.call("https://api.example.com/",operation("GET","/redirect",5,4096),arguments(),"disposable-provider-credential".getBytes(StandardCharsets.UTF_8),UUID.randomUUID())).isInstanceOfSatisfying(RestFailure.class,f->assertThat(f.code()).isEqualTo("REDIRECT_NOT_ALLOWED"));assertThat(redirectTargets.get()).isZero();assertThat(requests.get()).isEqualTo(1);}finally{transport.destroy();}}
    @Test void credentialEchoIsRejectedAndNotLogged(CapturedOutput logs){String canary="disposable-provider-canary-0123456789";var transport=fixture();try{assertThatThrownBy(()->transport.call("https://api.example.com/",operation("GET","/echo",5,4096),arguments(),canary.getBytes(StandardCharsets.UTF_8),UUID.randomUUID())).isInstanceOfSatisfying(RestFailure.class,f->{assertThat(f.code()).isEqualTo("PROVIDER_SECRET_DISCLOSURE");assertThat(f.getMessage()).doesNotContain(canary);assertThat(f.getCause()).isNull();});assertThat(logs.getAll()).doesNotContain(canary);}finally{transport.destroy();}}
    @Test void bodyLimitContentTypeAndJsonSchemaAreEnforced(){var transport=fixture();try{for(String path:List.of("/large","/text","/bad-json"))assertThatThrownBy(()->transport.call("https://api.example.com/",operation("GET",path,5,256),arguments(),null,UUID.randomUUID())).isInstanceOf(RestFailure.class);}finally{transport.destroy();}}
    @Test void readsHaveFiniteTimeoutAndSafeTransientClassification(){var transport=fixture();long start=System.nanoTime();try{assertThatThrownBy(()->transport.call("https://api.example.com/",operation("GET","/slow",1,4096),arguments(),null,UUID.randomUUID())).isInstanceOfSatisfying(RestFailure.class,f->assertThat(f.kind()).isEqualTo(RestFailure.Kind.TRANSIENT));assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-start)).isLessThan(4);}finally{transport.destroy();}}
    @Test void acceptedWriteWithLostResponseIsAmbiguousAndNeverRetried(){var transport=fixture();try{assertThatThrownBy(()->transport.call("https://api.example.com/",operation("POST","/drop",5,4096),arguments(),null,UUID.randomUUID())).isInstanceOfSatisfying(RestFailure.class,f->assertThat(f.kind()).isEqualTo(RestFailure.Kind.AMBIGUOUS));assertThat(requests.get()).isEqualTo(1);}finally{transport.destroy();}}
    @Test void provider503ReadIsEligibleButTransportDoesNotRetryIt(){var transport=fixture();try{assertThatThrownBy(()->transport.call("https://api.example.com/",operation("GET","/unavailable",5,4096),arguments(),null,UUID.randomUUID())).isInstanceOfSatisfying(RestFailure.class,f->assertThat(f.kind()).isEqualTo(RestFailure.Kind.TRANSIENT));assertThat(requests.get()).isEqualTo(1);}finally{transport.destroy();}}
    @Test void productionConstructorHasNoPrivateNetworkOrProxyBypass(){var transport=new RestTransport("api.example.com");try{assertThatThrownBy(()->transport.call("https://127.0.0.1/",operation("GET","/json",5,4096),arguments(),null,UUID.randomUUID())).isInstanceOf(DomainFailure.class);assertThatThrownBy(()->transport.call("http://api.example.com/",operation("GET","/json",5,4096),arguments(),null,UUID.randomUUID())).isInstanceOf(DomainFailure.class);assertThat(requests.get()).isZero();}finally{transport.destroy();}}
    @Test void connectionResolverRejectsRebindingAndMixedAnswersWithoutSecondLookup()throws Exception{var calls=new AtomicInteger();var policy=new DestinationPolicy(List.of("api.example.com"));try(var pool=Executors.newFixedThreadPool(1)){var resolver=new ValidatedDnsResolver(policy,host->{calls.incrementAndGet();return new InetAddress[]{InetAddress.getByName("8.8.8.8"),InetAddress.getByName("127.0.0.1")};},pool);assertThatThrownBy(()->resolver.resolve("api.example.com")).isInstanceOf(UnknownHostException.class);assertThat(calls.get()).isEqualTo(1);assertThat(resolver.resolveCanonicalHostname("api.example.com")).isEqualTo("api.example.com");assertThat(calls.get()).isEqualTo(1);}}
    @Test void dnsLookupHasABoundedDeadline()throws Exception{var latch=new CountDownLatch(1);try(var pool=Executors.newFixedThreadPool(1)){var resolver=new ValidatedDnsResolver(new DestinationPolicy(List.of("api.example.com")),host->{try{latch.await();}catch(InterruptedException cancelled){Thread.currentThread().interrupt();}return new InetAddress[]{InetAddress.getByName("8.8.8.8")};},pool,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(100));assertThatThrownBy(()->resolver.resolve("api.example.com")).isInstanceOf(UnknownHostException.class);}finally{latch.countDown();}}
    @Test void operationSchemaRejectsArbitraryPathsFieldsAndCoercion(){
        for(String path:List.of("//evil","/../private","/%2e%2e/private","/a?x=1","/a#fragment"))assertThatThrownBy(()->operation("GET",path,5,4096)).isInstanceOf(DomainFailure.class);
        var op=new RestOperation("ticket","GET","/tickets/{id}",Set.of("id"),Map.of(),Map.of("message","STRING"),"READ",5,4096,false);
        assertThat(op.resolvedPath(json.readTree("{\"pathParameters\":{\"id\":\"safe-id_1\"},\"fields\":{}}"))).isEqualTo("/tickets/safe-id_1");
        for(String id:List.of("../private","a/b","%2fprivate","a?query"))assertThatThrownBy(()->op.resolvedPath(json.valueToTree(Map.of("pathParameters",Map.of("id",id),"fields",Map.of())))).isInstanceOf(DomainFailure.class);
        assertThatThrownBy(()->op.projectResponse(json.readTree("{\"message\":7}"))).isInstanceOf(DomainFailure.class);assertThatThrownBy(()->op.validateArguments(json.readTree("{\"pathParameters\":{\"id\":\"x\"},\"fields\":{},\"headers\":{}}"))).isInstanceOf(DomainFailure.class);
    }
}
