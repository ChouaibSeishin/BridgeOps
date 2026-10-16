package com.bridgeops.workflows.persistence;

import com.bridgeops.shared.web.DomainFailure;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WorkflowStore {
    public record Row(UUID id, UUID organizationId, String name, String description, String status,
        UUID currentVersion, long revision, String definition, Instant updatedAt) {}
    public record Version(UUID id, UUID workflowId, int number, long sourceRevision, int schemaVersion,
        int canonicalizationVersion, String name, String description, String hash, UUID actor, Instant publishedAt, String definition) {}
    private final JdbcTemplate jdbc;
    private static final String COLUMNS = "w.id,w.organization_id,w.name,w.description,w.status,w.current_published_version_id,w.updated_at,d.revision,";
    private static final String JOIN = " FROM workflows w JOIN workflow_drafts d ON d.organization_id=w.organization_id AND d.workflow_id=w.id";
    public WorkflowStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Row get(UUID organization, UUID id, boolean lock) {
        var rows = jdbc.query("SELECT " + COLUMNS + "d.definition_json" + JOIN + " WHERE w.organization_id=? AND w.id=?" + (lock ? " FOR UPDATE OF w,d" : ""), this::row, organization, id);
        if (rows.isEmpty()) throw new DomainFailure(404, "NOT_FOUND"); return rows.getFirst();
    }
    public List<Row> list(UUID organization, int page, int size) {
        return jdbc.query("SELECT " + COLUMNS + "NULL AS definition_json" + JOIN + " WHERE w.organization_id=? ORDER BY w.updated_at DESC,w.id LIMIT ? OFFSET ?", this::row, organization, size, (long) page * size);
    }
    public long count(UUID organization) { return jdbc.queryForObject("SELECT count(*) FROM workflows WHERE organization_id=?", Long.class, organization); }
    public UUID create(UUID organization, UUID user, String name, String description, String definition, Instant now) {
        UUID id = UUID.randomUUID(); var time = Timestamp.from(now);
        jdbc.update("INSERT INTO workflows(id,organization_id,name,description,created_by,created_at,updated_at) VALUES(?,?,?,?,?,?,?)", id, organization, name, description, user, time, time);
        jdbc.update("INSERT INTO workflow_drafts(organization_id,workflow_id,definition_json,updated_by,updated_at) VALUES(?,?,?,?,?)", organization, id, definition, user, time);
        return id;
    }
    public void save(Row row, UUID user, String name, String description, String definition, Instant now) {
        advance(row, user, definition, now);
        jdbc.update("UPDATE workflows SET name=?,description=?,updated_at=? WHERE organization_id=? AND id=?", name, description, Timestamp.from(now), row.organizationId(), row.id());
    }
    public void advance(Row row, UUID user, String definition, Instant now) {
        if (jdbc.update("UPDATE workflow_drafts SET revision=revision+1,definition_json=?,updated_by=?,updated_at=? WHERE organization_id=? AND workflow_id=? AND revision=?",
            definition, user, Timestamp.from(now), row.organizationId(), row.id(), row.revision()) != 1) throw new DomainFailure(409, "STALE_REVISION");
    }
    public void archive(Row row, Instant now) {
        jdbc.update("UPDATE workflows SET status='ARCHIVED',updated_at=? WHERE organization_id=? AND id=?", Timestamp.from(now), row.organizationId(), row.id());
    }
    public void point(Row row, UUID version, Instant now) {
        jdbc.update("UPDATE workflows SET current_published_version_id=?,updated_at=? WHERE organization_id=? AND id=?", version, Timestamp.from(now), row.organizationId(), row.id());
    }
    public UUID publish(Row row, UUID user, String definition, String hash, Instant now) {
        Integer previous = jdbc.queryForObject("SELECT coalesce(max(version_number),0) FROM workflow_versions WHERE organization_id=? AND workflow_id=?", Integer.class, row.organizationId(), row.id());
        if (previous == Integer.MAX_VALUE) throw new DomainFailure(409, "VERSION_LIMIT");
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO workflow_versions(id,organization_id,workflow_id,version_number,source_revision,schema_version,canonicalization_version,name,description,definition_json,definition_hash,published_by,published_at) VALUES(?,?,?,?,?,1,1,?,?,?,?,?,?)",
            id, row.organizationId(), row.id(), previous + 1, row.revision(), row.name(), row.description(), definition, hash, user, Timestamp.from(now));
        return id;
    }
    public Version version(UUID organization, UUID workflow, UUID id) {
        var rows = jdbc.query("SELECT * FROM workflow_versions WHERE organization_id=? AND workflow_id=? AND id=?", this::version, organization, workflow, id);
        if (rows.isEmpty()) throw new DomainFailure(404, "NOT_FOUND"); return rows.getFirst();
    }
    public List<Version> versions(UUID organization, UUID workflow, int page, int size) {
        return jdbc.query("SELECT id,workflow_id,version_number,source_revision,schema_version,canonicalization_version,name,description,definition_hash,published_by,published_at,NULL AS definition_json FROM workflow_versions WHERE organization_id=? AND workflow_id=? ORDER BY version_number DESC LIMIT ? OFFSET ?",
            this::version, organization, workflow, size, (long) page * size);
    }
    public long versionCount(UUID organization, UUID workflow) { return jdbc.queryForObject("SELECT count(*) FROM workflow_versions WHERE organization_id=? AND workflow_id=?", Long.class, organization, workflow); }
    private Row row(ResultSet rs, int index) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class), rs.getString("name"), rs.getString("description"), rs.getString("status"), rs.getObject("current_published_version_id", UUID.class), rs.getLong("revision"), rs.getString("definition_json"), rs.getTimestamp("updated_at").toInstant());
    }
    private Version version(ResultSet rs, int index) throws SQLException {
        return new Version(rs.getObject("id", UUID.class), rs.getObject("workflow_id", UUID.class), rs.getInt("version_number"), rs.getLong("source_revision"), rs.getInt("schema_version"), rs.getInt("canonicalization_version"), rs.getString("name"), rs.getString("description"), rs.getString("definition_hash"), rs.getObject("published_by", UUID.class), rs.getTimestamp("published_at").toInstant(), rs.getString("definition_json"));
    }
}
