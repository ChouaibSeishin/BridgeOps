package com.bridgeops.organizations.persistence;

import com.bridgeops.shared.web.DomainFailure;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AutomationStore {
    public record Row(UUID id, UUID organization, String name, UUID workflow, UUID version, String hash, String status,
                      long revision, UUID createdBy, Instant createdAt, UUID revokedBy, Instant revokedAt) {}
    public record Grant(String node, UUID connection, Long connectionRevision, String operation, String classification) {}
    private final JdbcTemplate jdbc;
    public AutomationStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Row get(UUID org, UUID id, boolean lock) {
        var rows = jdbc.query("SELECT * FROM automation_principals WHERE organization_id=? AND id=?" + (lock ? " FOR UPDATE" : ""), this::row, org, id);
        if (rows.isEmpty()) throw new DomainFailure(404, "NOT_FOUND"); return rows.getFirst();
    }
    public List<Row> list(UUID org, int limit, long offset) { return jdbc.query("SELECT * FROM automation_principals WHERE organization_id=? ORDER BY created_at DESC,id LIMIT ? OFFSET ?", this::row, org, limit, offset); }
    public long count(UUID org) { return jdbc.queryForObject("SELECT count(*) FROM automation_principals WHERE organization_id=?", Long.class, org); }
    public UUID create(UUID actor, UUID org, String name, UUID workflow, UUID version, String hash, Instant now) {
        UUID id = UUID.randomUUID(); jdbc.update("INSERT INTO automation_principals(id,organization_id,name,workflow_id,workflow_version_id,definition_hash,created_by,created_at) VALUES(?,?,?,?,?,?,?,?)", id, org, name, workflow, version, hash, actor, Timestamp.from(now)); return id;
    }
    public void grant(UUID org, UUID principal, Grant grant) { jdbc.update("INSERT INTO automation_operation_grants(organization_id,principal_id,node_id,connection_id,connection_revision,operation,classification) VALUES(?,?,?,?,?,?,?)", org, principal, grant.node(), grant.connection(), grant.connectionRevision(), grant.operation(), grant.classification()); }
    public List<Grant> grants(UUID org, UUID principal) { return jdbc.query("SELECT * FROM automation_operation_grants WHERE organization_id=? AND principal_id=? ORDER BY node_id", (r, n) -> new Grant(r.getString("node_id"), r.getObject("connection_id", UUID.class), r.getObject("connection_revision", Long.class), r.getString("operation"), r.getString("classification")), org, principal); }
    public void revoke(Row row, UUID actor, Instant now) {
        if (jdbc.update("UPDATE automation_principals SET status='REVOKED',revision=revision+1,revoked_by=?,revoked_at=? WHERE organization_id=? AND id=? AND status='ACTIVE' AND revision=?", actor, Timestamp.from(now), row.organization(), row.id(), row.revision()) != 1) throw new DomainFailure(409, "AUTOMATION_CONFLICT");
    }
    private Row row(ResultSet r, int index) throws SQLException { var at = r.getTimestamp("revoked_at"); return new Row(r.getObject("id", UUID.class), r.getObject("organization_id", UUID.class), r.getString("name"), r.getObject("workflow_id", UUID.class), r.getObject("workflow_version_id", UUID.class), r.getString("definition_hash"), r.getString("status"), r.getLong("revision"), r.getObject("created_by", UUID.class), r.getTimestamp("created_at").toInstant(), r.getObject("revoked_by", UUID.class), at == null ? null : at.toInstant()); }
}
