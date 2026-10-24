package com.bridgeops.integrations.application;

import com.bridgeops.integrations.persistence.WebhookDeliveryStore.Route;
import com.bridgeops.shared.web.DomainFailure;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** PostgreSQL admission only. Expiring coordination rows are separate from immutable delivery history. */
@Service
public class WebhookAdmission {
    public record Lease(UUID id,UUID organization,UUID endpoint){@Override public String toString(){return "WebhookAdmissionLease[REDACTED]";}}
    private final JdbcTemplate jdbc;private final Clock clock;
    public WebhookAdmission(JdbcTemplate jdbc,Clock clock){this.jdbc=jdbc;this.clock=clock;}
    @Transactional(timeout=5)
    public Lease acquire(Route route){
        // Keep ingress coordination independent of the outbound connector admission lock (834721905).
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(834721907)",Boolean.class)))throw busy();var now=clock.instant().truncatedTo(ChronoUnit.MICROS);
        jdbc.update("DELETE FROM webhook_ingress_leases WHERE expires_at<=?",Timestamp.from(now));
        jdbc.update("DELETE FROM webhook_ingress_quotas q WHERE (q.organization_id,q.endpoint_id) IN (SELECT x.organization_id,x.endpoint_id FROM webhook_ingress_quotas x JOIN webhook_endpoints e ON e.organization_id=x.organization_id AND e.id=x.endpoint_id WHERE e.status='REVOKED' AND NOT EXISTS(SELECT FROM webhook_ingress_leases l WHERE l.organization_id=x.organization_id AND l.endpoint_id=x.endpoint_id) ORDER BY x.window_start LIMIT 200)");
        var rows=jdbc.queryForList("SELECT window_start,attempts FROM webhook_ingress_quotas WHERE organization_id=? AND endpoint_id=? FOR UPDATE",route.organization(),route.endpoint());
        Instant window;int attempts;
        if(rows.isEmpty()){
            if(jdbc.queryForObject("SELECT count(*) FROM webhook_ingress_quotas",Long.class)>=10000)throw busy();window=now.truncatedTo(ChronoUnit.MINUTES);attempts=0;
            jdbc.update("INSERT INTO webhook_ingress_quotas(organization_id,endpoint_id,window_start,attempts) VALUES(?,?,?,0)",route.organization(),route.endpoint(),Timestamp.from(window));
        }else{window=((Timestamp)rows.getFirst().get("window_start")).toInstant();attempts=(Integer)rows.getFirst().get("attempts");if(!now.isBefore(window.plusSeconds(60))){window=now.truncatedTo(ChronoUnit.MINUTES);attempts=0;}}
        if(attempts>=60)throw new DomainFailure(429,"WEBHOOK_RATE_LIMITED");
        if(jdbc.queryForObject("SELECT count(*) FROM webhook_ingress_leases",Long.class)>=64||jdbc.queryForObject("SELECT count(*) FROM webhook_ingress_leases WHERE organization_id=? AND endpoint_id=?",Long.class,route.organization(),route.endpoint())>=2)throw busy();
        jdbc.update("UPDATE webhook_ingress_quotas SET window_start=?,attempts=? WHERE organization_id=? AND endpoint_id=?",Timestamp.from(window),attempts+1,route.organization(),route.endpoint());
        UUID id=UUID.randomUUID();jdbc.update("INSERT INTO webhook_ingress_leases(id,organization_id,endpoint_id,created_at,expires_at) VALUES(?,?,?,?,?)",id,route.organization(),route.endpoint(),Timestamp.from(now),Timestamp.from(now.plusSeconds(15)));return new Lease(id,route.organization(),route.endpoint());
    }
    @Transactional(propagation=Propagation.MANDATORY,noRollbackFor=DomainFailure.class)
    public void consume(Lease lease){if(jdbc.queryForList("DELETE FROM webhook_ingress_leases WHERE id=? AND organization_id=? AND endpoint_id=? AND expires_at>? RETURNING id",lease.id(),lease.organization(),lease.endpoint(),Timestamp.from(clock.instant())).isEmpty())throw busy();}
    @Transactional(timeout=5)
    public void release(Lease lease){jdbc.update("DELETE FROM webhook_ingress_leases WHERE id=? AND organization_id=? AND endpoint_id=?",lease.id(),lease.organization(),lease.endpoint());}
    private DomainFailure busy(){return new DomainFailure(429,"WEBHOOK_BUSY");}
}
