package com.bridgeops.integrations.persistence;

import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

/** Immutable references to dedicated opt-in artifacts; never repairs or overwrites edited resources. */
@Repository
public class DemoWorkflowStore {
    public record Connection(UUID id,long revision){}
    public record Version(UUID workflowId,UUID versionId,String definitionHash){}
    public record Manifest(Connection support,Connection teams,Connection notifications,Version manual,Version webhook,Instant createdAt,String provenance){}
    private final JdbcTemplate jdbc;
    public DemoWorkflowStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Optional<Manifest> read(UUID org){return jdbc.query("SELECT * FROM integration_demo_workflows WHERE organization_id=?",(r,i)->new Manifest(new Connection(r.getObject("support_connection_id",UUID.class),r.getLong("support_revision")),new Connection(r.getObject("teams_connection_id",UUID.class),r.getLong("teams_revision")),new Connection(r.getObject("notification_connection_id",UUID.class),r.getLong("notification_revision")),new Version(r.getObject("manual_workflow_id",UUID.class),r.getObject("manual_version_id",UUID.class),r.getString("manual_hash")),new Version(r.getObject("webhook_workflow_id",UUID.class),r.getObject("webhook_version_id",UUID.class),r.getString("webhook_hash")),r.getTimestamp("created_at").toInstant(),r.getString("provenance")),org).stream().findFirst();}
    @Transactional(propagation=Propagation.MANDATORY)
    public void insert(UUID org,UUID actor,Manifest m){jdbc.update("INSERT INTO integration_demo_workflows(organization_id,support_connection_id,support_revision,teams_connection_id,teams_revision,notification_connection_id,notification_revision,manual_workflow_id,manual_version_id,manual_hash,webhook_workflow_id,webhook_version_id,webhook_hash,created_by,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",org,m.support().id(),m.support().revision(),m.teams().id(),m.teams().revision(),m.notifications().id(),m.notifications().revision(),m.manual().workflowId(),m.manual().versionId(),m.manual().definitionHash(),m.webhook().workflowId(),m.webhook().versionId(),m.webhook().definitionHash(),actor,java.sql.Timestamp.from(m.createdAt()));}
}
