package com.bridgeops.organizations.persistence;

import com.bridgeops.organizations.application.RoleTemplates;
import com.bridgeops.shared.web.DomainFailure;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OrganizationStore {
    public record Organization(UUID id,String name,String slug,long revision){}
    public record Membership(UUID id,UUID userId,String status,long revision){}
    public record Role(UUID id,String name){}
    private final JdbcTemplate jdbc;
    public OrganizationStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Organization organization(UUID id,boolean lock){
        var rows=jdbc.query("SELECT id,name,slug,revision FROM organizations WHERE id=? AND status='ACTIVE'"+(lock?" FOR UPDATE":""),
            (rs,n)->new Organization(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getLong(4)),id);
        if(rows.isEmpty())throw new DomainFailure(404,"NOT_FOUND");return rows.getFirst();
    }
    public Membership member(UUID org,UUID user){
        var rows=jdbc.query("SELECT id,user_id,status,revision FROM organization_memberships WHERE organization_id=? AND user_id=? AND status='ACTIVE'",
            (rs,n)->new Membership(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getLong(4)),org,user);
        if(rows.isEmpty())throw new DomainFailure(404,"NOT_FOUND");return rows.getFirst();
    }
    public Set<String> permissions(UUID org,UUID membership){
        return Set.copyOf(jdbc.queryForList("SELECT DISTINCT p.permission_code FROM membership_roles m JOIN role_permissions p ON p.organization_id=m.organization_id AND p.role_id=m.role_id WHERE m.organization_id=? AND m.membership_id=?",String.class,org,membership));
    }
    public void require(UUID org,UUID membership,String permission){if(!permissions(org,membership).contains(permission))throw new DomainFailure(403,"PERMISSION_DENIED");}
    public List<Role> roles(UUID org,UUID membership){return jdbc.query("SELECT r.id,r.name FROM roles r JOIN membership_roles m ON m.organization_id=r.organization_id AND m.role_id=r.id WHERE m.organization_id=? AND m.membership_id=? ORDER BY r.name",
        (rs,n)->new Role(rs.getObject(1,UUID.class),rs.getString(2)),org,membership);}
    public List<Role> catalog(UUID org){return jdbc.query("SELECT id,name FROM roles WHERE organization_id=? ORDER BY name",(rs,n)->new Role(rs.getObject(1,UUID.class),rs.getString(2)),org);}
    public Organization insert(UUID actor,String name,String slug,Instant now){
        UUID org=UUID.randomUUID(),member=UUID.randomUUID();var time=java.sql.Timestamp.from(now);
        jdbc.update("INSERT INTO organizations(id,name,slug,created_by,created_at,updated_at) VALUES(?,?,?,?,?,?)",org,name,slug,actor,time,time);
        jdbc.update("INSERT INTO organization_memberships(id,organization_id,user_id,status,joined_at) VALUES(?,?,?,'ACTIVE',?)",member,org,actor,time);
        RoleTemplates.ALL.forEach((role,codes)->{
            UUID id=UUID.randomUUID();jdbc.update("INSERT INTO roles(id,organization_id,name) VALUES(?,?,?)",id,org,role);
            codes.forEach(code->jdbc.update("INSERT INTO role_permissions(organization_id,role_id,permission_code) VALUES(?,?,?)",org,id,code));
            if(role.equals("OWNER"))jdbc.update("INSERT INTO membership_roles(organization_id,membership_id,role_id) VALUES(?,?,?)",org,member,id);
        });
        audit(org,actor,"ORGANIZATION_CREATED",org,now);return new Organization(org,name,slug,0);
    }
    public void audit(UUID org,UUID actor,String action,UUID target,Instant now){jdbc.update("INSERT INTO organization_audit_events(id,organization_id,actor_id,action,target_id,occurred_at) VALUES(?,?,?,?,?,?)",UUID.randomUUID(),org,actor,action,target,java.sql.Timestamp.from(now));}
    public List<Organization> list(UUID user,int page,int size){return jdbc.query("SELECT o.id,o.name,o.slug,o.revision FROM organizations o JOIN organization_memberships m ON m.organization_id=o.id WHERE m.user_id=? AND m.status='ACTIVE' AND o.status='ACTIVE' ORDER BY o.id LIMIT ? OFFSET ?",
        (rs,n)->new Organization(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getLong(4)),user,size,(long)page*size);}
    public long count(UUID user){return jdbc.queryForObject("SELECT count(*) FROM organizations o JOIN organization_memberships m ON m.organization_id=o.id WHERE m.user_id=? AND m.status='ACTIVE' AND o.status='ACTIVE'",Long.class,user);}
    public List<Membership> members(UUID org,int page,int size){return jdbc.query("SELECT id,user_id,status,revision FROM organization_memberships WHERE organization_id=? AND status<>'REMOVED' ORDER BY id LIMIT ? OFFSET ?",
        (rs,n)->new Membership(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getLong(4)),org,size,(long)page*size);}
    public long memberCount(UUID org){return jdbc.queryForObject("SELECT count(*) FROM organization_memberships WHERE organization_id=? AND status<>'REMOVED'",Long.class,org);}
}
