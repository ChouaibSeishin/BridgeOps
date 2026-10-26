package com.bridgeops.integrations.infrastructure;

import com.bridgeops.integrations.domain.*;
import com.bridgeops.shared.web.DomainFailure;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.SSLContext;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SchemePortResolver;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.*;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.*;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.ssl.SSLContexts;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Component;
import tools.jackson.core.*;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** No database transaction may span call(). Public web adapters use authorized configuration services. */
@Component
public class RestTransport implements DisposableBean {
    private final java.time.Clock clock;private final DestinationPolicy policy;private final SSLContext tls;private final DnsResolver fixtureResolver;private final SchemePortResolver fixturePorts;
    private final ExecutorService dns=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),r->{var t=new Thread(r,"bridgeops-rest-dns");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledThreadPoolExecutor timer=new ScheduledThreadPoolExecutor(1,r->{var t=new Thread(r,"bridgeops-rest-deadline");t.setDaemon(true);return t;});
    private final Semaphore capacity=new Semaphore(8);
    private final JsonMapper json=JsonMapper.builder(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16).maxStringLength(1048576).maxNumberLength(20).build()).build()).build();
    @Autowired public RestTransport(@Value("${bridgeops.integrations.rest.allowed-hosts:}")String hosts,java.time.Clock clock){
        this(new DestinationPolicy(Arrays.stream(hosts.split(",")).map(String::trim).filter(s->!s.isEmpty()).toList()),SSLContexts.createSystemDefault(),null,null,clock);
    }
    public RestTransport(String hosts){this(hosts,java.time.Clock.systemUTC());}
    /** Package-private seam for disposable TLS fixtures; never configurable in production. */
    RestTransport(DestinationPolicy policy,SSLContext tls,DnsResolver fixtureResolver,SchemePortResolver fixturePorts){this(policy,tls,fixtureResolver,fixturePorts,java.time.Clock.systemUTC());}
    RestTransport(DestinationPolicy policy,SSLContext tls,DnsResolver fixtureResolver,SchemePortResolver fixturePorts,java.time.Clock clock){this.clock=Objects.requireNonNull(clock);this.policy=policy;this.tls=tls;this.fixtureResolver=fixtureResolver;this.fixturePorts=fixturePorts;timer.setRemoveOnCancelPolicy(true);}
    public URI destination(String base){return policy.base(base);}
    public JsonNode call(String base,RestOperation operation,JsonNode arguments,byte[] credential,UUID logicalOperation){
        URI destination=policy.base(base);operation.validateArguments(arguments);
        if(!capacity.tryAcquire())throw new RestFailure(RestFailure.Kind.TRANSIENT,"OUTBOUND_CAPACITY");
        HttpUriRequestBase request=null;ScheduledFuture<?> deadlineTask=null;boolean write=operation.classification().equals("WRITE");
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(operation.timeoutSeconds());
        try {
            String path=operation.resolvedPath(arguments);request=new HttpUriRequestBase(operation.method(),destination.resolve(path));
            request.setHeader("Accept","application/json");request.setHeader("Accept-Encoding","identity");request.setHeader("Connection","close");
            if(credential!=null){String value=new String(credential,StandardCharsets.UTF_8);if(value.length()<8||value.length()>4096||!value.matches("[A-Za-z0-9._~+/-]+=*"))throw new RestFailure(RestFailure.Kind.PERMANENT,"INVALID_CREDENTIAL_FORMAT");request.setHeader("Authorization","Bearer "+value);}
            if(write&&operation.remoteIdempotencyKey())request.setHeader("Idempotency-Key",Objects.requireNonNull(logicalOperation).toString());
            if(!operation.method().equals("GET"))request.setEntity(new ByteArrayEntity(json.writeValueAsBytes(arguments.path("fields")),ContentType.APPLICATION_JSON));
            var strategy=ClientTlsStrategyBuilder.create().setSslContext(tls).setHostnameVerifier(new DefaultHostnameVerifier()).setTlsVersions("TLSv1.3","TLSv1.2").buildClassic();
            DnsResolver resolver=fixtureResolver!=null?fixtureResolver:new ValidatedDnsResolver(policy,InetAddress::getAllByName,dns,deadline);
            var builder=PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(resolver).setTlsSocketStrategy(strategy).setMaxConnTotal(1).setMaxConnPerRoute(1)
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(Math.min(3,operation.timeoutSeconds()))).setSocketTimeout(Timeout.ofSeconds(operation.timeoutSeconds())).build());
            if(fixturePorts!=null)builder.setSchemePortResolver(fixturePorts);
            var connectionManager=builder.build();
            HttpUriRequestBase active=request;deadlineTask=timer.schedule(active::cancel,operation.timeoutSeconds(),TimeUnit.SECONDS);
            try(var client=HttpClients.custom().setConnectionManager(connectionManager).disableAutomaticRetries().disableRedirectHandling().disableCookieManagement().disableAuthCaching().disableContentCompression()
                .setDefaultRequestConfig(RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofSeconds(1)).setResponseTimeout(Timeout.ofSeconds(operation.timeoutSeconds())).build()).build()){
                return client.execute(request,response->{
                    int status=response.getCode();
                    if(status>=300&&status<400)throw new RestFailure(RestFailure.Kind.PERMANENT,"REDIRECT_NOT_ALLOWED");
                    if(status<200||status>=300){var kind=write&&status>=500?RestFailure.Kind.AMBIGUOUS:!write&&Set.of(408,429,500,502,503,504).contains(status)?RestFailure.Kind.TRANSIENT:RestFailure.Kind.PERMANENT;var headers=response.getHeaders("Retry-After");throw new RestFailure(kind,"PROVIDER_REJECTED",headers.length==1?retryAfter(headers[0].getValue(),clock.instant()):null);}
                    var entity=response.getEntity();if(entity==null){if(operation.responseFields().isEmpty())return json.createObjectNode();throw responseFailure(write,"INVALID_PROVIDER_RESPONSE");}
                    String type=entity.getContentType();if(type==null||!type.toLowerCase(Locale.ROOT).matches("application/(?:json|[a-z0-9.+_-]+\\+json)(?:\\s*;.*)?"))throw responseFailure(write,"INVALID_PROVIDER_CONTENT_TYPE");
                    String encoding=entity.getContentEncoding();if(encoding!=null&&!encoding.equalsIgnoreCase("identity"))throw responseFailure(write,"PROVIDER_ENCODING_REJECTED");
                    if(entity.getContentLength()>operation.maximumResponseBytes())throw responseFailure(write,"PROVIDER_RESPONSE_LIMIT");
                    byte[] bytes;try(var stream=entity.getContent()){bytes=stream.readNBytes(operation.maximumResponseBytes()+1);}
                    if(bytes.length>operation.maximumResponseBytes()||System.nanoTime()>=deadline)throw responseFailure(write,"PROVIDER_RESPONSE_LIMIT");
                    try{
                        JsonNode projected=operation.projectResponse(json.readTree(bytes));String text=json.writeValueAsString(projected);
                        if(credential!=null&&text.contains(new String(credential,StandardCharsets.UTF_8)))throw responseFailure(write,"PROVIDER_SECRET_DISCLOSURE");
                        return projected;
                    }catch(RestFailure safe){throw safe;}catch(RuntimeException invalid){throw responseFailure(write,"INVALID_PROVIDER_RESPONSE");}
                });
            }
        }catch(RestFailure safe){throw safe;}
        catch(IOException unavailable){throw new RestFailure(write?RestFailure.Kind.AMBIGUOUS:RestFailure.Kind.TRANSIENT,"PROVIDER_UNAVAILABLE");}
        catch(RuntimeException invalid){if(invalid instanceof DomainFailure safe)throw safe;throw new RestFailure(write?RestFailure.Kind.AMBIGUOUS:RestFailure.Kind.PERMANENT,"PROVIDER_UNAVAILABLE");}
        finally{if(deadlineTask!=null)deadlineTask.cancel(false);if(request!=null)request.cancel();capacity.release();}
    }
    private static RestFailure responseFailure(boolean write,String code){return new RestFailure(write?RestFailure.Kind.AMBIGUOUS:RestFailure.Kind.PERMANENT,code);}
    @Override public void destroy(){timer.shutdownNow();dns.shutdownNow();}
    /** Bounded provider hint, evaluated with a controllable clock; never an unbounded scheduler instruction. */
    static java.time.Duration retryAfter(String value,java.time.Instant now){
        if(value==null||value.length()>128)return null;
        try{java.time.Duration duration=value.trim().matches("[0-9]{1,10}")?java.time.Duration.ofSeconds(Long.parseLong(value.trim())):java.time.Duration.between(now,java.time.ZonedDateTime.parse(value.trim(),java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
            if(duration.isNegative())return java.time.Duration.ZERO;return duration.compareTo(java.time.Duration.ofSeconds(60))>0?java.time.Duration.ofSeconds(60):duration;
        }catch(RuntimeException invalid){return null;}
    }
}
