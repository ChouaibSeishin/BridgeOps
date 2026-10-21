package com.bridgeops.integrations.application;

import com.bridgeops.shared.web.DomainFailure;
import java.time.*;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
class OutboundAdmission {
    private final JdbcTemplate jdbc;private final Clock clock;
    public OutboundAdmission(JdbcTemplate jdbc,Clock clock){this.jdbc=jdbc;this.clock=clock;}
    @Transactional(timeout=5)
    public UUID acquire(UUID org,UUID connection){
        Instant now=clock.instant();var at=java.sql.Timestamp.from(now);
        jdbc.execute("SELECT pg_advisory_xact_lock(834721905)");
        jdbc.update("DELETE FROM integration_outbound_slots WHERE id IN (SELECT id FROM integration_outbound_slots WHERE lease_expires_at<? ORDER BY lease_expires_at LIMIT 500)",java.sql.Timestamp.from(now.minusSeconds(300)));
        if(jdbc.queryForList("SELECT id FROM integration_connections WHERE organization_id=? AND id=? AND status='ENABLED' FOR UPDATE",org,connection).isEmpty())throw new DomainFailure(409,"INTEGRATION_DISABLED");
        long total=jdbc.queryForObject("SELECT count(*) FROM integration_outbound_slots",Long.class);
        long global=jdbc.queryForObject("SELECT count(*) FROM integration_outbound_slots WHERE released_at IS NULL AND lease_expires_at>?",Long.class,at);
        long tenant=jdbc.queryForObject("SELECT count(*) FROM integration_outbound_slots WHERE organization_id=? AND released_at IS NULL AND lease_expires_at>?",Long.class,org,at);
        long active=jdbc.queryForObject("SELECT count(*) FROM integration_outbound_slots WHERE organization_id=? AND connection_id=? AND released_at IS NULL AND lease_expires_at>?",Long.class,org,connection,at);
        long recent=jdbc.queryForObject("SELECT count(*) FROM integration_outbound_slots WHERE organization_id=? AND connection_id=? AND acquired_at>?",Long.class,org,connection,java.sql.Timestamp.from(now.minusSeconds(60)));
        if(total>=10000||global>=8||tenant>=4||active>=2||recent>=10)throw new DomainFailure(429,"OUTBOUND_RATE_LIMITED");
        UUID id=UUID.randomUUID();jdbc.update("INSERT INTO integration_outbound_slots(id,organization_id,connection_id,acquired_at,lease_expires_at) VALUES(?,?,?,?,?)",id,org,connection,at,java.sql.Timestamp.from(now.plusSeconds(15)));return id;
    }
    @Transactional(timeout=5) public void release(UUID org,UUID id){jdbc.update("UPDATE integration_outbound_slots SET released_at=? WHERE organization_id=? AND id=? AND released_at IS NULL",java.sql.Timestamp.from(clock.instant()),org,id);}
}
