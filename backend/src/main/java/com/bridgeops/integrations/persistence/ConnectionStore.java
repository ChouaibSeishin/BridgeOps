package com.bridgeops.integrations.persistence;

import com.bridgeops.shared.web.DomainFailure;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ConnectionStore {
    public record Row(UUID id,UUID organization,String name,String description,String type,int implementationVersion,long revision,String status,String configuration,UUID credential,Instant updatedAt,String health,String healthCode,Instant checkedAt,Long checkedRevision) {}
    private static final String SELECT="SELECT c.*,h.status AS health_status,h.code AS health_code,h.checked_at,h.revision AS checked_revision FROM integration_connections c LEFT JOIN LATERAL (SELECT status,code,checked_at,revision FROM integration_health_checks WHERE organization_id=c.organization_id AND connection_id=c.id ORDER BY checked_at DESC,id DESC LIMIT 1) h ON true";
    private final JdbcTemplate jdbc;
    public ConnectionStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Row get(UUID org,UUID id,boolean lock){var rows=jdbc.query(SELECT+" WHERE c.organization_id=? AND c.id=?"+(lock?" FOR UPDATE OF c":""),this::row,org,id);if(rows.isEmpty())throw new DomainFailure(404,"NOT_FOUND");return rows.getFirst();}
    public List<Row> list(UUID org,int page,int size){return jdbc.query(SELECT+" WHERE c.organization_id=? ORDER BY c.updated_at DESC,c.id LIMIT ? OFFSET ?",this::row,org,size,page*size);}
    public long count(UUID org){return jdbc.queryForObject("SELECT count(*) FROM integration_connections WHERE organization_id=?",Long.class,org);}
    public UUID create(UUID org,UUID actor,String name,String description,String type,String configuration,UUID credential,Instant now){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO integration_connections(id,organization_id,name,description,integration_type,configuration_json,credential_reference,created_by,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",id,org,name,description,type,configuration,credential,actor,Timestamp.from(now),Timestamp.from(now));return id;}
    public void update(Row row,String name,String description,boolean enabled,String configuration,UUID credential,Instant now){if(jdbc.update("UPDATE integration_connections SET name=?,description=?,status=?,configuration_json=?,credential_reference=?,updated_at=?,revision=revision+1 WHERE organization_id=? AND id=? AND revision=?",name,description,enabled?"ENABLED":"DISABLED",configuration,credential,Timestamp.from(now),row.organization(),row.id(),row.revision())!=1)throw new DomainFailure(409,"STALE_REVISION");}
    public void version(Row row,String definition,String hash,Instant now){jdbc.update("INSERT INTO integration_connection_versions(organization_id,connection_id,revision,definition_json,definition_hash,created_at) VALUES(?,?,?,?,?,?)",row.organization(),row.id(),row.revision(),definition,hash,Timestamp.from(now));}
    public void health(Row row,long revision,String status,String code,Instant now){jdbc.update("INSERT INTO integration_health_checks(id,organization_id,connection_id,revision,checked_at,status,code) VALUES(?,?,?,?,?,?,?)",UUID.randomUUID(),row.organization(),row.id(),revision,Timestamp.from(now),status,code);}
    private Row row(ResultSet r,int index)throws SQLException{Timestamp check=r.getTimestamp("checked_at");Long revision=r.getObject("checked_revision",Long.class);return new Row(r.getObject("id",UUID.class),r.getObject("organization_id",UUID.class),r.getString("name"),r.getString("description"),r.getString("integration_type"),r.getInt("implementation_version"),r.getLong("revision"),r.getString("status"),r.getString("configuration_json"),r.getObject("credential_reference",UUID.class),r.getTimestamp("updated_at").toInstant(),r.getString("health_status"),r.getString("health_code"),check==null?null:check.toInstant(),revision);}
}
