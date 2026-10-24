package com.bridgeops.integrations.persistence;

import com.bridgeops.shared.domain.AutomationOrigin;
import com.bridgeops.shared.web.DomainFailure;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WebhookDeliveryStore {
    public record Route(UUID organization,UUID endpoint,long revision,UUID signingReference){@Override public String toString(){return "WebhookRoute[REDACTED]";}}
    public record Receipt(String payloadHash,UUID execution,Instant acceptedAt){}
    private final JdbcTemplate jdbc;
    public WebhookDeliveryStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    /** Unknown/revoked IDs allocate nothing and disclose no tenant/configuration metadata. */
    public Route route(UUID endpoint){return jdbc.query("SELECT organization_id,id,revision,signing_secret_id FROM webhook_endpoints WHERE id=? AND status='ACTIVE'",(r,i)->new Route(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getLong(3),r.getObject(4,UUID.class)),endpoint).stream().findFirst().orElseThrow(()->new DomainFailure(401,"INVALID_WEBHOOK_SIGNATURE"));}
    public Optional<Receipt> receipt(UUID org,UUID endpoint,String delivery){return jdbc.query("SELECT payload_hash,execution_id,accepted_at FROM webhook_delivery_receipts WHERE organization_id=? AND endpoint_id=? AND delivery_id=?",(r,i)->new Receipt(r.getString(1),r.getObject(2,UUID.class),r.getTimestamp(3).toInstant()),org,endpoint,delivery).stream().findFirst();}
    public void insert(UUID org,AutomationOrigin origin,UUID workflow,UUID version,String hash,String delivery,String payloadHash,long signedSeconds,UUID execution,Instant accepted){jdbc.update("INSERT INTO webhook_delivery_receipts(organization_id,endpoint_id,delivery_id,payload_hash,execution_id,endpoint_revision,principal_id,workflow_id,workflow_version_id,definition_hash,principal_created_by,endpoint_revision_author,signed_at_seconds,accepted_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",org,origin.endpoint(),delivery,payloadHash,execution,origin.endpointRevision(),origin.principal(),workflow,version,hash,origin.principalCreator(),origin.endpointAuthor(),signedSeconds,Timestamp.from(accepted));}
}
