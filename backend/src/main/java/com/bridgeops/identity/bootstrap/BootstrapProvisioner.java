package com.bridgeops.identity.bootstrap;

import com.bridgeops.identity.application.*;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Operator-only service: deliberately not a component in the web application. */
public final class BootstrapProvisioner {
    public static final class Unavailable extends RuntimeException {
        public Unavailable(){super("Installation bootstrap is unavailable.");}
    }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AccountPasswords passwords;
    private final Clock clock;
    public BootstrapProvisioner(JdbcTemplate jdbc,TransactionTemplate transactions,AccountPasswords passwords,Clock clock) {
        this.jdbc=jdbc;this.transactions=transactions;this.passwords=passwords;this.clock=clock;
        transactions.setTimeout(10);
    }
    public UUID provision(String email,String displayName,char[] secret) {
        try {
            String normalized=AccountIdentifiers.email(email), name=AccountIdentifiers.displayName(displayName);
            // BCrypt accepts String; keep no field/reference after this call and erase the input array in finally.
            String hash=passwords.hash(new String(secret));
            return transactions.execute(status->{
                var state=jdbc.queryForList("SELECT bootstrap_consumed_at FROM installation_state WHERE singleton_id=1 FOR UPDATE");
                if(state.size()!=1 || state.getFirst().get("bootstrap_consumed_at")!=null
                        || jdbc.queryForObject("SELECT count(*) FROM users",Integer.class)!=0) throw new Unavailable();
                UUID id=UUID.randomUUID(); var now=java.sql.Timestamp.from(clock.instant());
                jdbc.update("INSERT INTO users(id,email,normalized_email,password_hash,display_name,account_status,created_at,email_verified_at) VALUES(?,?,?,?,?,'ACTIVE',?,?)",
                    id,normalized,normalized,hash,name,now,now);
                jdbc.update("INSERT INTO user_grants(user_id,grant_code,granted_at) VALUES(?,'organization.create',?)",id,now);
                jdbc.update("UPDATE installation_state SET bootstrap_consumed_at=?,initial_user_id=? WHERE singleton_id=1",now,id);
                jdbc.update("INSERT INTO identity_security_events(id,user_id,event_type,occurred_at) VALUES(?,?,'BOOTSTRAP_COMPLETED',?)",UUID.randomUUID(),id,now);
                return id;
            });
        } finally {if(secret!=null)Arrays.fill(secret,'\0');}
    }
}
