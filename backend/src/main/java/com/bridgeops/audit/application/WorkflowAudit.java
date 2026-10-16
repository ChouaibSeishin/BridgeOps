package com.bridgeops.audit.application;

import com.bridgeops.shared.web.CorrelationFilter;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.context.request.*;

/** Narrow writer for the existing append-only tenant audit stream. No arbitrary payload JSON. */
@Service
public class WorkflowAudit {
    public enum Action { WORKFLOW_CREATED, DRAFT_SAVED, WORKFLOW_PUBLISHED, WORKFLOW_ARCHIVED, WORKFLOW_DUPLICATED }
    private final JdbcTemplate jdbc;
    public WorkflowAudit(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Transactional(propagation = Propagation.MANDATORY)
    public void write(UUID organization, UUID actor, Action action, UUID workflow, long revision, UUID version, String hash, Instant now) {
        var attributes = RequestContextHolder.getRequestAttributes();
        Object correlation = attributes instanceof ServletRequestAttributes servlet
            ? servlet.getRequest().getAttribute(CorrelationFilter.ATTRIBUTE) : null;
        String reference = correlation instanceof String value && value.matches("[a-f0-9-]{36}") ? value : UUID.randomUUID().toString();
        jdbc.update("INSERT INTO organization_audit_events(id,organization_id,actor_id,action,target_id,occurred_at,resource_type,revision,version_id,definition_hash,correlation_id) VALUES(?,?,?,?,?,?,'WORKFLOW',?,?,?,?)",
            UUID.randomUUID(), organization, actor, action.name(), workflow, java.sql.Timestamp.from(now), revision, version, hash, reference);
    }
}
