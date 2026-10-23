package com.bridgeops.integrations.persistence;

import com.bridgeops.shared.web.DomainFailure;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WebhookStore {
    public record Row(UUID id,UUID organization,String name,UUID principal,UUID principalCreator,UUID workflow,UUID version,String hash,long revision,String status,UUID secret,UUID creator,Instant created,UUID author,Instant updated){@Override public String toString(){return "WebhookConfiguration[REDACTED]";}}
    private final JdbcTemplate jdbc;
    public WebhookStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Row get(UUID org,UUID id,boolean lock){return jdbc.query("SELECT * FROM webhook_endpoints WHERE organization_id=? AND id=?"+(lock?" FOR UPDATE":""),this::row,org,id).stream().findFirst().orElseThrow(()->new DomainFailure(404,"NOT_FOUND"));}
    public long count(UUID org){return jdbc.queryForObject("SELECT count(*) FROM webhook_endpoints WHERE organization_id=?",Long.class,org);}
    public List<Row> list(UUID org,int page,int size){return jdbc.query("SELECT * FROM webhook_endpoints WHERE organization_id=? ORDER BY created_at DESC,id LIMIT ? OFFSET ?",this::row,org,size,(long)page*size);}
    public void create(Row r){jdbc.update("INSERT INTO webhook_endpoints(id,organization_id,name,principal_id,principal_created_by,workflow_id,workflow_version_id,definition_hash,revision,status,signing_secret_id,created_by,created_at,updated_by,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",r.id(),r.organization(),r.name(),r.principal(),r.principalCreator(),r.workflow(),r.version(),r.hash(),r.revision(),r.status(),r.secret(),r.creator(),Timestamp.from(r.created()),r.author(),Timestamp.from(r.updated()));version(r);}
    public void change(Row old,UUID secret,UUID author,Instant now,boolean revoke){
        if(jdbc.update("UPDATE webhook_endpoints SET revision=revision+1,status=?,signing_secret_id=?,updated_by=?,updated_at=? WHERE organization_id=? AND id=? AND revision=? AND status='ACTIVE'",revoke?"REVOKED":"ACTIVE",secret,author,Timestamp.from(now),old.organization(),old.id(),old.revision())!=1)throw new DomainFailure(409,"WEBHOOK_CONFLICT");version(get(old.organization(),old.id(),false));
    }
    private void version(Row r){jdbc.update("INSERT INTO webhook_endpoint_versions(organization_id,endpoint_id,revision,principal_id,principal_created_by,workflow_id,workflow_version_id,definition_hash,signing_secret_id,status,authored_by,authored_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",r.organization(),r.id(),r.revision(),r.principal(),r.principalCreator(),r.workflow(),r.version(),r.hash(),r.secret(),r.status(),r.author(),Timestamp.from(r.updated()));}
    private Row row(ResultSet r,int index)throws SQLException{return new Row(r.getObject("id",UUID.class),r.getObject("organization_id",UUID.class),r.getString("name"),r.getObject("principal_id",UUID.class),r.getObject("principal_created_by",UUID.class),r.getObject("workflow_id",UUID.class),r.getObject("workflow_version_id",UUID.class),r.getString("definition_hash"),r.getLong("revision"),r.getString("status"),r.getObject("signing_secret_id",UUID.class),r.getObject("created_by",UUID.class),r.getTimestamp("created_at").toInstant(),r.getObject("updated_by",UUID.class),r.getTimestamp("updated_at").toInstant());}
}
