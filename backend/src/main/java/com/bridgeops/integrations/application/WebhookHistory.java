package com.bridgeops.integrations.application;

import com.bridgeops.integrations.persistence.WebhookStore;
import com.bridgeops.organizations.application.TenantAuthorization;
import com.bridgeops.shared.web.DomainFailure;
import java.util.*;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Authenticated delivery metadata, without payloads, signatures, hashes or secret references. */
@Service
public class WebhookHistory {
    public record Delivery(String deliveryId,UUID executionId,long endpointRevision,UUID principalId,Instant acceptedAt){}
    public record Page(List<Delivery> items,int page,int size,long totalElements,long totalPages){}
    private final TenantAuthorization authority;private final WebhookStore endpoints;private final JdbcTemplate jdbc;
    public WebhookHistory(TenantAuthorization authority,WebhookStore endpoints,JdbcTemplate jdbc){this.authority=authority;this.endpoints=endpoints;this.jdbc=jdbc;}
    @Transactional(readOnly=true,timeout=5)
    public Page list(UUID actor,UUID family,UUID org,UUID endpoint,int page,int size){
        authority.read(actor,family,org,"integration.read");authority.read(actor,family,org,"execution.read");endpoints.get(org,endpoint,false);
        if(page<0||page>100000||size<1||size>100)throw new DomainFailure(400,"INVALID_REQUEST");
        long count=jdbc.queryForObject("SELECT count(*) FROM webhook_delivery_receipts WHERE organization_id=? AND endpoint_id=?",Long.class,org,endpoint);
        var items=jdbc.query("SELECT delivery_id,execution_id,endpoint_revision,principal_id,accepted_at FROM webhook_delivery_receipts WHERE organization_id=? AND endpoint_id=? ORDER BY accepted_at DESC,delivery_id LIMIT ? OFFSET ?",(r,i)->new Delivery(r.getString(1),r.getObject(2,UUID.class),r.getLong(3),r.getObject(4,UUID.class),r.getTimestamp(5).toInstant()),org,endpoint,size,(long)page*size);
        return new Page(items,page,size,count,(count+size-1)/size);
    }
}
