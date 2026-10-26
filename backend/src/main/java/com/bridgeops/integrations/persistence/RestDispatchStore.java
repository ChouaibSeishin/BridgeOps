package com.bridgeops.integrations.persistence;

import com.bridgeops.shared.web.DomainFailure;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Append-only evidence. A marker is not authority, a receipt or proof of a remote effect. */
@Repository
public class RestDispatchStore {
    public record Dispatch(UUID id,UUID organization,UUID execution,String node,UUID logicalOperation,int attempt,UUID owner,long fence,
        UUID connection,long connectionRevision,int implementationVersion,String operation,String classification,String argumentHash,String blueprintHash,Instant dispatchedAt,Instant deadline){
        @Override public String toString(){return "ExternalDispatch[REDACTED]";}
    }
    public record Outcome(String outcome,String code,String resultHash,Instant completedAt){@Override public String toString(){return "ExternalOutcome[REDACTED]";}}
    private final JdbcTemplate jdbc;
    public RestDispatchStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Optional<Dispatch> latest(UUID org,UUID logical){return jdbc.query("SELECT * FROM integration_outbound_dispatches WHERE organization_id=? AND logical_operation_id=? ORDER BY attempt_number DESC LIMIT 1",this::dispatch,org,logical).stream().findFirst();}
    public Optional<Outcome> outcome(UUID org,UUID dispatch){return jdbc.query("SELECT outcome,error_code,result_hash,completed_at FROM integration_outbound_outcomes WHERE organization_id=? AND dispatch_id=?",(r,i)->new Outcome(r.getString(1),r.getString(2),r.getString(3),r.getTimestamp(4).toInstant()),org,dispatch).stream().findFirst();}
    public void begin(Dispatch d){
        transaction();jdbc.update("INSERT INTO integration_outbound_dispatches(id,organization_id,execution_id,node_id,logical_operation_id,attempt_number,worker_owner,fencing_token,connection_id,connection_revision,implementation_version,operation,classification,argument_hash,blueprint_hash,dispatched_at,deadline_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",d.id(),d.organization(),d.execution(),d.node(),d.logicalOperation(),d.attempt(),d.owner(),d.fence(),d.connection(),d.connectionRevision(),d.implementationVersion(),d.operation(),d.classification(),d.argumentHash(),d.blueprintHash(),Timestamp.from(d.dispatchedAt()),Timestamp.from(d.deadline()));
    }
    public void finish(Dispatch d,Outcome outcome){
        transaction();jdbc.update("INSERT INTO integration_outbound_outcomes(organization_id,dispatch_id,dispatched_at,completed_at,outcome,error_code,result_hash) VALUES(?,?,?,?,?,?,?)",d.organization(),d.id(),Timestamp.from(d.dispatchedAt()),Timestamp.from(outcome.completedAt()),outcome.outcome(),outcome.code(),outcome.resultHash());
    }
    public Dispatch get(UUID org,UUID id){return jdbc.query("SELECT * FROM integration_outbound_dispatches WHERE organization_id=? AND id=?",this::dispatch,org,id).stream().findFirst().orElseThrow(()->new DomainFailure(409,"RECONCILIATION_REQUIRED"));}
    private void transaction(){if(!TransactionSynchronizationManager.isActualTransactionActive()||jdbc.getDataSource()==null||!TransactionSynchronizationManager.hasResource(jdbc.getDataSource()))throw new IllegalStateException("Outbound evidence requires its database transaction.");}
    private Dispatch dispatch(ResultSet r,int index)throws SQLException{return new Dispatch(r.getObject("id",UUID.class),r.getObject("organization_id",UUID.class),r.getObject("execution_id",UUID.class),r.getString("node_id"),r.getObject("logical_operation_id",UUID.class),r.getInt("attempt_number"),r.getObject("worker_owner",UUID.class),r.getLong("fencing_token"),r.getObject("connection_id",UUID.class),r.getLong("connection_revision"),r.getInt("implementation_version"),r.getString("operation"),r.getString("classification"),r.getString("argument_hash"),r.getString("blueprint_hash"),r.getTimestamp("dispatched_at").toInstant(),r.getTimestamp("deadline_at").toInstant());}
}
