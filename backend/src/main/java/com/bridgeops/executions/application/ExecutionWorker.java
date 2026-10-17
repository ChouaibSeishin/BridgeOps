package com.bridgeops.executions.application;

import com.bridgeops.executions.persistence.ExecutionStore.Claim;
import com.bridgeops.shared.web.DomainFailure;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="bridgeops.executions.worker.enabled",havingValue="true",matchIfMissing=true)
public class ExecutionWorker implements SmartLifecycle {
    private final ExecutionRuntime runtime;private final SyntheticCapability capability;
    private final int parallel,tenantLimit,leaseSeconds,pollMillis;private final UUID owner=UUID.randomUUID();
    private final Set<Claim> active=ConcurrentHashMap.newKeySet();private final AtomicBoolean running=new AtomicBoolean();
    private ScheduledExecutorService timer;private ExecutorService pool;private long lastUnavailableLog;
    public ExecutionWorker(ExecutionRuntime runtime,SyntheticCapability capability,
        @Value("${bridgeops.executions.worker.parallel:4}")int parallel,@Value("${bridgeops.executions.worker.per-organization:2}")int tenantLimit,
        @Value("${bridgeops.executions.worker.lease-seconds:30}")int leaseSeconds,@Value("${bridgeops.executions.worker.poll-millis:1000}")int pollMillis){
        if(parallel<1||parallel>16||tenantLimit<1||tenantLimit>parallel||leaseSeconds<5||leaseSeconds>120||pollMillis<250||pollMillis>5000||pollMillis*3>=leaseSeconds*1000)throw new IllegalArgumentException("Invalid execution worker bounds.");
        this.runtime=runtime;this.capability=capability;this.parallel=parallel;this.tenantLimit=tenantLimit;this.leaseSeconds=leaseSeconds;this.pollMillis=pollMillis;
    }
    @Override public void start(){if(!running.compareAndSet(false,true))return;
        pool=new ThreadPoolExecutor(parallel,parallel,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(parallel),r->{var t=new Thread(r,"bridgeops-execution");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
        timer=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"bridgeops-execution-poll");t.setDaemon(true);return t;});
        timer.scheduleWithFixedDelay(this::poll,0,pollMillis,TimeUnit.MILLISECONDS);
    }
    private void poll(){if(!running.get())return;try{
        for(var claim:active)runtime.renew(claim,leaseSeconds);
        runtime.reconcile();
        for(int i=active.size();i<parallel&&running.get();i++){
            var claim=runtime.claim(owner,parallel,tenantLimit,leaseSeconds);if(claim==null)break;active.add(claim);
            try{pool.execute(()->{try{dispatch(claim);}finally{active.remove(claim);}});}catch(RejectedExecutionException stopping){active.remove(claim);break;}
        }
    }catch(RuntimeException unavailable){safeUnavailable();}}
    public void dispatch(Claim claim){
        try{
            var task=runtime.prepare(claim);if(task==null)return;
            var invocation=new SyntheticCapability.Invocation(claim.organization(),task.actor(),task.operation(),task.node().id(),task.node().message());
            try{
                if(task.node().type().equals("syntheticAction"))capability.prepare(invocation);
            }catch(SyntheticCapability.Failure failure){runtime.failure(task,failure.kind());return;}
            catch(RuntimeException unknown){runtime.failure(task,SyntheticCapability.FailureKind.AMBIGUOUS);return;}
            if(runtime.complete(task)){
                // If interrupted after commit, recovery sees the successful step and stable receipt.
                if(task.node().type().equals("syntheticAction"))capability.afterCommit(invocation);
                runtime.acknowledge(claim);
            }
        }catch(DomainFailure revoked){try{runtime.permissionDenied(claim);}catch(RuntimeException unavailable){safeUnavailable();}}
        catch(RuntimeException unavailable){safeUnavailable();}
    }
    private void safeUnavailable(){long now=System.nanoTime();if(now-lastUnavailableLog>TimeUnit.SECONDS.toNanos(30)){lastUnavailableLog=now;LoggerFactory.getLogger(ExecutionWorker.class).warn("Execution worker paused a cycle; durable state will be reconciled.");}}
    @Override public void stop(){running.set(false);if(timer!=null)timer.shutdownNow();if(pool!=null){pool.shutdown();try{if(!pool.awaitTermination(5,TimeUnit.SECONDS))pool.shutdownNow();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();pool.shutdownNow();}}}
    @Override public void stop(Runnable callback){stop();callback.run();}
    @Override public boolean isRunning(){return running.get();}
    @Override public int getPhase(){return Integer.MAX_VALUE-100;}
}
