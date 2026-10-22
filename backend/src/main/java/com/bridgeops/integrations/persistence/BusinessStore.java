package com.bridgeops.integrations.persistence;

import com.bridgeops.shared.web.DomainFailure;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class BusinessStore {
    public record Ticket(UUID id,String title,String status,String priority,String category,UUID assignedTeamId,long revision,String provenance,Instant updatedAt) {}
    public record Team(UUID id,String name,String provenance) {}
    public record Responsibility(String category,UUID teamId) {}
    public record Notification(UUID id,String message,String status,String provenance,Instant createdAt) {}
    public record NotificationAttempt(int number,String outcome,Instant occurredAt) {}
    private final JdbcTemplate jdbc;
    public BusinessStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public List<Ticket> tickets(UUID org,String status,String priority,String category,int limit,int offset){return jdbc.query("SELECT * FROM support_tickets WHERE organization_id=? AND (?::text IS NULL OR status=?) AND (?::text IS NULL OR priority=?) AND (?::text IS NULL OR category=?) ORDER BY id LIMIT ? OFFSET ?",(r,i)->new Ticket(r.getObject("id",UUID.class),r.getString("title"),r.getString("status"),r.getString("priority"),r.getString("category"),r.getObject("assigned_team_id",UUID.class),r.getLong("revision"),r.getString("provenance"),r.getTimestamp("updated_at").toInstant()),org,status,status,priority,priority,category,category,limit,offset);}
    public long count(UUID org,String status,String priority,String category){return jdbc.queryForObject("SELECT count(*) FROM support_tickets WHERE organization_id=? AND (?::text IS NULL OR status=?) AND (?::text IS NULL OR priority=?) AND (?::text IS NULL OR category=?)",Long.class,org,status,status,priority,priority,category,category);}
    public Ticket ticket(UUID org,UUID id){return ticketsById(org,id,false);}
    public List<Ticket> filteredTickets(UUID org,List<String> priorities,List<String> statuses,int limit){
        return jdbc.query("SELECT * FROM support_tickets WHERE organization_id=? AND priority=ANY(?::text[]) AND status=ANY(?::text[]) ORDER BY id LIMIT ?",(r,i)->new Ticket(r.getObject("id",UUID.class),r.getString("title"),r.getString("status"),r.getString("priority"),r.getString("category"),r.getObject("assigned_team_id",UUID.class),r.getLong("revision"),r.getString("provenance"),r.getTimestamp("updated_at").toInstant()),org,"{"+String.join(",",priorities)+"}","{"+String.join(",",statuses)+"}",limit);
    }
    public Ticket lockTicket(UUID org,UUID id){return ticketsById(org,id,true);}
    public Map<UUID,Ticket> snapshotTickets(UUID org,List<UUID> ids){
        if(ids.isEmpty())return Map.of();if(ids.size()>50)throw new IllegalArgumentException("Ticket snapshot bound exceeded.");
        var rows=jdbc.query("SELECT * FROM support_tickets WHERE organization_id=? AND id=ANY(?::uuid[])",(r,i)->new Ticket(r.getObject("id",UUID.class),r.getString("title"),r.getString("status"),r.getString("priority"),r.getString("category"),r.getObject("assigned_team_id",UUID.class),r.getLong("revision"),r.getString("provenance"),r.getTimestamp("updated_at").toInstant()),org,"{"+String.join(",",ids.stream().map(UUID::toString).toList())+"}");
        var result=new HashMap<UUID,Ticket>();rows.forEach(t->result.put(t.id(),t));return Map.copyOf(result);
    }
    private Ticket ticketsById(UUID org,UUID id,boolean lock){var result=jdbc.query("SELECT * FROM support_tickets WHERE organization_id=? AND id=?"+(lock?" FOR UPDATE":""),(r,i)->new Ticket(r.getObject("id",UUID.class),r.getString("title"),r.getString("status"),r.getString("priority"),r.getString("category"),r.getObject("assigned_team_id",UUID.class),r.getLong("revision"),r.getString("provenance"),r.getTimestamp("updated_at").toInstant()),org,id);if(result.isEmpty())throw new DomainFailure(404,"NOT_FOUND");return result.getFirst();}
    public List<Team> teams(UUID org){return jdbc.query("SELECT * FROM support_teams WHERE organization_id=? ORDER BY name,id LIMIT 100",(r,i)->new Team(r.getObject("id",UUID.class),r.getString("name"),r.getString("provenance")),org);}
    public Set<UUID> teamIds(UUID org,List<UUID> ids){if(ids.isEmpty())return Set.of();if(ids.size()>50)throw new IllegalArgumentException("Team snapshot bound exceeded.");return Set.copyOf(jdbc.queryForList("SELECT id FROM support_teams WHERE organization_id=? AND id=ANY(?::uuid[])",UUID.class,org,"{"+String.join(",",ids.stream().map(UUID::toString).toList())+"}"));}
    public Team team(UUID org,UUID id){return jdbc.query("SELECT * FROM support_teams WHERE organization_id=? AND id=?",(r,i)->new Team(r.getObject("id",UUID.class),r.getString("name"),r.getString("provenance")),org,id).stream().findFirst().orElseThrow(()->new DomainFailure(404,"NOT_FOUND"));}
    public List<Responsibility> responsibilities(UUID org){return jdbc.query("SELECT category,team_id FROM team_responsibilities WHERE organization_id=? ORDER BY category",(r,i)->new Responsibility(r.getString(1),r.getObject(2,UUID.class)),org);}
    public List<Notification> notifications(UUID org,int limit,int offset){return jdbc.query("SELECT * FROM internal_notifications WHERE organization_id=? ORDER BY created_at DESC,id LIMIT ? OFFSET ?",(r,i)->new Notification(r.getObject("id",UUID.class),r.getString("message"),r.getString("status"),r.getString("provenance"),r.getTimestamp("created_at").toInstant()),org,limit,offset);}
    public long notificationCount(UUID org){return jdbc.queryForObject("SELECT count(*) FROM internal_notifications WHERE organization_id=?",Long.class,org);}
    public Notification notification(UUID org,UUID id){var rows=jdbc.query("SELECT * FROM internal_notifications WHERE organization_id=? AND id=?",(r,i)->new Notification(r.getObject("id",UUID.class),r.getString("message"),r.getString("status"),r.getString("provenance"),r.getTimestamp("created_at").toInstant()),org,id);if(rows.isEmpty())throw new DomainFailure(404,"NOT_FOUND");return rows.getFirst();}
    public List<NotificationAttempt> attempts(UUID org,UUID id){notification(org,id);return jdbc.query("SELECT attempt_number,outcome,occurred_at FROM internal_notification_attempts WHERE organization_id=? AND notification_id=? ORDER BY attempt_number",(r,i)->new NotificationAttempt(r.getInt(1),r.getString(2),r.getTimestamp(3).toInstant()),org,id);}
    public boolean seed(UUID org,UUID actor,Instant now){
        if(jdbc.update("INSERT INTO integration_demo_installations(organization_id,installed_by,installed_at) VALUES(?,?,?) ON CONFLICT DO NOTHING",org,actor,java.sql.Timestamp.from(now))==0)return false;
        var time=java.sql.Timestamp.from(now);
        for(String category:List.of("BILLING","PRODUCT","ACCESS")){
            UUID team=UUID.randomUUID();jdbc.update("INSERT INTO support_teams(id,organization_id,name,provenance) VALUES(?,?,?,'SEEDED_EXAMPLE')",team,org,category+" support");
            jdbc.update("INSERT INTO team_responsibilities(organization_id,category,team_id) VALUES(?,?,?)",org,category,team);
        }
        String[][] examples={{"Invoice cannot be downloaded","OPEN","HIGH","BILLING"},{"Workspace access blocked","OPEN","URGENT","ACCESS"},{"Export feature question","IN_PROGRESS","NORMAL","PRODUCT"},{"Previous billing issue resolved","RESOLVED","HIGH","BILLING"},{"No matching responsibility","OPEN","LOW","OTHER"}};
        for(String[] example:examples)jdbc.update("INSERT INTO support_tickets(id,organization_id,title,status,priority,category,provenance,created_at,updated_at) VALUES(?,?,?,?,?,?,'SEEDED_EXAMPLE',?,?)",UUID.randomUUID(),org,example[0],example[1],example[2],example[3],time,time);
        UUID notification=UUID.randomUUID();jdbc.update("INSERT INTO internal_notifications(id,organization_id,logical_operation_id,message,status,provenance,created_at) VALUES(?,?,?,?,'DELIVERED_INTERNAL','SEEDED_EXAMPLE',?)",notification,org,UUID.randomUUID(),"Example internal notification; no external message was sent.",time);
        jdbc.update("INSERT INTO internal_notification_attempts(id,organization_id,notification_id,attempt_number,outcome,occurred_at) VALUES(?,?,?,1,'DELIVERED_INTERNAL',?)",UUID.randomUUID(),org,notification,time);return true;
    }
}
