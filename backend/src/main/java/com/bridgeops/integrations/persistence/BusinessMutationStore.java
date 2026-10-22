package com.bridgeops.integrations.persistence;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

/** Narrow SQL mutations; only the fenced, approved application gateway may invoke the handlers that own these calls. */
@Repository
@Transactional(propagation=Propagation.MANDATORY)
public class BusinessMutationStore {
    private final JdbcTemplate jdbc;
    public BusinessMutationStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public boolean assign(UUID org,UUID ticket,long expected,UUID previousTeam,UUID targetTeam,Instant now){
        return jdbc.update("UPDATE support_tickets SET assigned_team_id=?,revision=revision+1,updated_at=? WHERE organization_id=? AND id=? AND revision=? AND assigned_team_id IS NOT DISTINCT FROM ?::uuid AND status IN ('OPEN','IN_PROGRESS')",targetTeam,java.sql.Timestamp.from(now),org,ticket,expected,previousTeam)==1;
    }
    public boolean advanceNoteRevision(UUID org,UUID ticket,long expected,Instant now){return jdbc.update("UPDATE support_tickets SET revision=revision+1,updated_at=? WHERE organization_id=? AND id=? AND revision=?",java.sql.Timestamp.from(now),org,ticket,expected)==1;}
    public boolean hasNote(UUID org,UUID logical,UUID ticket){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT FROM support_ticket_notes WHERE organization_id=? AND logical_operation_id=? AND ticket_id=?)",Boolean.class,org,logical,ticket));}
    public UUID note(UUID org,UUID logical,UUID ticket,String body,Instant now){UUID id=UUID.randomUUID();jdbc.update("INSERT INTO support_ticket_notes(id,organization_id,ticket_id,logical_operation_id,body,created_at) VALUES(?,?,?,?,?,?)",id,org,ticket,logical,body,java.sql.Timestamp.from(now));return id;}
    public boolean hasNotification(UUID org,UUID logical){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT FROM internal_notifications WHERE organization_id=? AND logical_operation_id=?)",Boolean.class,org,logical));}
    public UUID notification(UUID org,UUID logical,String message,Instant now){
        UUID id=UUID.randomUUID();var at=java.sql.Timestamp.from(now);jdbc.update("INSERT INTO internal_notifications(id,organization_id,logical_operation_id,message,status,provenance,created_at) VALUES(?,?,?,?,'DELIVERED_INTERNAL','LIVE_EXECUTION',?)",id,org,logical,message,at);jdbc.update("INSERT INTO internal_notification_attempts(id,organization_id,notification_id,attempt_number,outcome,occurred_at) VALUES(?,?,?,1,'DELIVERED_INTERNAL',?)",UUID.randomUUID(),org,id,at);return id;
    }
    public void batchItem(UUID org,UUID execution,UUID logical,int number,UUID ticket,long expected,UUID target,String outcome,String code,Instant now){
        jdbc.update("INSERT INTO integration_batch_items(organization_id,execution_id,logical_operation_id,item_number,ticket_id,expected_revision,target_team_id,outcome,code,occurred_at) VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(organization_id,execution_id,logical_operation_id,item_number) DO NOTHING",org,execution,logical,number,ticket,expected,target,outcome,code,java.sql.Timestamp.from(now));
    }
}
