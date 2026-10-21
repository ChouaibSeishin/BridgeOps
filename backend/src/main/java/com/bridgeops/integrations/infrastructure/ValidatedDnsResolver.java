package com.bridgeops.integrations.infrastructure;

import com.bridgeops.integrations.domain.DestinationPolicy;
import java.net.*;
import java.util.concurrent.*;
import org.apache.hc.client5.http.DnsResolver;

/** The HTTP connection operator uses these exact addresses; there is no second system lookup. */
final class ValidatedDnsResolver implements DnsResolver {
    @FunctionalInterface interface Lookup { InetAddress[] resolve(String host)throws UnknownHostException; }
    private final DestinationPolicy policy;private final Lookup lookup;private final ExecutorService pool;private final long deadline;
    ValidatedDnsResolver(DestinationPolicy policy,Lookup lookup,ExecutorService pool){this(policy,lookup,pool,System.nanoTime()+TimeUnit.SECONDS.toNanos(2));}
    ValidatedDnsResolver(DestinationPolicy policy,Lookup lookup,ExecutorService pool,long deadline){this.policy=policy;this.lookup=lookup;this.pool=pool;this.deadline=deadline;}
    @Override public InetAddress[] resolve(String host)throws UnknownHostException {
        Future<InetAddress[]> task=null;
        try{long remaining=Math.min(TimeUnit.SECONDS.toNanos(2),deadline-System.nanoTime());if(remaining<=0)throw new TimeoutException();task=pool.submit(()->policy.answers(host,lookup.resolve(host)));return task.get(remaining,TimeUnit.NANOSECONDS);}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw unavailable();}
        catch(ExecutionException|TimeoutException|RejectedExecutionException invalid){throw unavailable();}
        finally{if(task!=null&&!task.isDone())task.cancel(true);}
    }
    @Override public String resolveCanonicalHostname(String host){return host;}
    private static UnknownHostException unavailable(){return new UnknownHostException("Configured destination unavailable.");}
}
