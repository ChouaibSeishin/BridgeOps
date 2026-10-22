package com.bridgeops.integrations.persistence;

import com.bridgeops.shared.web.DomainFailure;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

/** Append-only logical receipts. Authorization and the current execution fence belong to the gateway. */
@Repository
public class IntegrationWorkStore {
    private final JdbcTemplate jdbc;
    public IntegrationWorkStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Optional<String> receipt(UUID org,UUID logical,String operation,String item,String hash){
        var rows=jdbc.queryForList("SELECT argument_hash,result_json FROM integration_operation_receipts WHERE organization_id=? AND logical_operation_id=? AND operation=? AND item_id=?",org,logical,operation,item);
        if(rows.isEmpty())return Optional.empty();if(!hash.equals(rows.getFirst().get("argument_hash")))throw new DomainFailure(409,"OPERATION_INTENT_MISMATCH");return Optional.of((String)rows.getFirst().get("result_json"));
    }
    @Transactional(propagation=Propagation.MANDATORY,noRollbackFor=DomainFailure.class)
    public String commit(UUID org,UUID execution,UUID logical,String operation,String item,String hash,String output,Instant now){
        jdbc.update("INSERT INTO integration_operation_receipts(organization_id,logical_operation_id,operation,item_id,execution_id,argument_hash,result_json,committed_at) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(organization_id,logical_operation_id,operation,item_id) DO NOTHING",org,logical,operation,item,execution,hash,output,java.sql.Timestamp.from(now));
        return receipt(org,logical,operation,item,hash).orElseThrow(()->new DomainFailure(409,"RECONCILIATION_REQUIRED"));
    }
}
