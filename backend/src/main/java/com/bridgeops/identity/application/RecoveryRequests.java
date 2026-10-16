package com.bridgeops.identity.application;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;
/** Generic acknowledgement precedes lookup/delivery; bounded volatile work, no durable queue or retries. */
@Component
public class RecoveryRequests {
 private final PasswordRecoveryService recovery;
 private final ThreadPoolExecutor executor=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),new ThreadPoolExecutor.DiscardPolicy());
 private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(RecoveryRequests.class);
 public RecoveryRequests(PasswordRecoveryService recovery){this.recovery=recovery;}
 public void submit(String email){executor.execute(()->{try{recovery.forgot(email);}catch(RuntimeException unavailable){log.warn("Password recovery delivery did not complete.");}});}
 @PreDestroy public void stop(){executor.shutdownNow();}
}
