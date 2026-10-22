package com.bridgeops.executions.persistence;

import com.bridgeops.approvals.application.OperationApprovalIntent;
import com.bridgeops.executions.domain.ExecutionPlan;
import com.bridgeops.shared.web.DomainFailure;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import static com.bridgeops.executions.persistence.ExecutionStore.time;

/** Persistence primitive, not authorization. Caller holds the execution and current work fence. */
public final class OperationInputStore {
    public record Prepared(UUID id,UUID organization,UUID execution,String node,UUID logicalOperation,
        String blueprintHash,String capability,String operation,String classification,
        OperationApprovalIntent.Connection connection,String arguments,String argumentHash) {
        @Override public String toString(){return "PreparedOperation[REDACTED]";}
    }
    private final JdbcTemplate jdbc;
    public OperationInputStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Prepared prepare(UUID org,UUID execution,String node,UUID logicalOperation,String blueprintHash,
        String capability,String operation,String classification,OperationApprovalIntent.Connection connection,
        JsonNode arguments,Instant now){
        if(!TransactionSynchronizationManager.isActualTransactionActive()||jdbc.getDataSource()==null||!TransactionSynchronizationManager.hasResource(jdbc.getDataSource()))throw new IllegalStateException("Operation preparation requires its database transaction.");
        if(connection!=null&&!connection.operationId().equals(operation))throw mismatch();
        String text=OperationApprovalIntent.canonicalArguments(arguments),hash=ExecutionPlan.hash(text);
        jdbc.update("INSERT INTO execution_operation_bindings(organization_id,logical_operation_id,origin_execution_id,origin_node_id,blueprint_hash,capability,operation,classification,connection_id,connection_revision,implementation_version,arguments_json,argument_hash,prepared_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(organization_id,logical_operation_id) DO NOTHING",
            org,logicalOperation,execution,node,blueprintHash,capability,operation,classification,connection==null?null:connection.id(),connection==null?null:connection.revision(),connection==null?null:connection.implementationVersion(),text,hash,time(now));
        var binding=jdbc.queryForMap("SELECT * FROM execution_operation_bindings WHERE organization_id=? AND logical_operation_id=?",org,logicalOperation);
        var actual=connection(binding);
        if(!Objects.equals(blueprintHash,binding.get("blueprint_hash"))||!Objects.equals(capability,binding.get("capability"))||!Objects.equals(operation,binding.get("operation"))||!Objects.equals(classification,binding.get("classification"))
            ||!Objects.equals(connection,actual)||!text.equals(binding.get("arguments_json"))||!hash.equals(binding.get("argument_hash")))throw mismatch();
        jdbc.update("INSERT INTO execution_operation_inputs(organization_id,execution_id,node_id,id,logical_operation_id,blueprint_hash,argument_hash,capability,operation,classification,prepared_at) VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(organization_id,execution_id,node_id) DO NOTHING",
            org,execution,node,UUID.randomUUID(),logicalOperation,blueprintHash,hash,capability,operation,classification,time(now));
        var row=jdbc.queryForMap("SELECT * FROM execution_operation_inputs WHERE organization_id=? AND execution_id=? AND node_id=?",org,execution,node);
        if(!Objects.equals(logicalOperation,row.get("logical_operation_id"))||!hash.equals(row.get("argument_hash"))||!Objects.equals(blueprintHash,row.get("blueprint_hash"))||!Objects.equals(capability,row.get("capability"))||!Objects.equals(operation,row.get("operation"))||!Objects.equals(classification,row.get("classification")))throw mismatch();
        return new Prepared((UUID)row.get("id"),org,execution,node,logicalOperation,blueprintHash,capability,operation,classification,actual,text,hash);
    }
    private OperationApprovalIntent.Connection connection(Map<String,Object> binding){
        return binding.get("connection_id")==null?null:new OperationApprovalIntent.Connection((UUID)binding.get("connection_id"),((Number)binding.get("connection_revision")).longValue(),((Number)binding.get("implementation_version")).intValue(),(String)binding.get("operation"));
    }
    private static DomainFailure mismatch(){return new DomainFailure(409,"OPERATION_INTENT_MISMATCH");}
}
