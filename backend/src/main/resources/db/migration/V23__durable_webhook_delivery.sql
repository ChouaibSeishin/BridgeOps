-- Durable delivery history is append-only. Admission rows are bounded, expiring coordination metadata.
ALTER TABLE workflow_executions ADD CONSTRAINT execution_delivery_origin_context
 UNIQUE(organization_id,id,webhook_endpoint_id,webhook_endpoint_revision,automation_principal_id,workflow_id,workflow_version_id,definition_hash,principal_created_by,endpoint_revision_author);
CREATE TABLE webhook_delivery_receipts (
 organization_id uuid NOT NULL, endpoint_id uuid NOT NULL,
 delivery_id varchar(128) NOT NULL CHECK(delivery_id ~ '^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$'),
 payload_hash varchar(64) NOT NULL CHECK(payload_hash ~ '^[a-f0-9]{64}$'),
 execution_id uuid NOT NULL, endpoint_revision bigint NOT NULL CHECK(endpoint_revision>=0),
 principal_id uuid NOT NULL, workflow_id uuid NOT NULL, workflow_version_id uuid NOT NULL,
 definition_hash varchar(64) NOT NULL, principal_created_by uuid NOT NULL, endpoint_revision_author uuid NOT NULL,
 signed_at_seconds bigint NOT NULL CHECK(signed_at_seconds>0), accepted_at timestamptz NOT NULL,
 PRIMARY KEY(organization_id,endpoint_id,delivery_id), UNIQUE(organization_id,execution_id),
 FOREIGN KEY(organization_id,execution_id,endpoint_id,endpoint_revision,principal_id,workflow_id,workflow_version_id,definition_hash,principal_created_by,endpoint_revision_author)
 REFERENCES workflow_executions(organization_id,id,webhook_endpoint_id,webhook_endpoint_revision,automation_principal_id,workflow_id,workflow_version_id,definition_hash,principal_created_by,endpoint_revision_author)
);
CREATE INDEX webhook_delivery_history ON webhook_delivery_receipts(organization_id,endpoint_id,accepted_at DESC,delivery_id);
CREATE TRIGGER immutable_webhook_delivery BEFORE UPDATE OR DELETE ON webhook_delivery_receipts FOR EACH ROW EXECUTE FUNCTION reject_execution_history_mutation();
CREATE TABLE webhook_ingress_quotas (
 organization_id uuid NOT NULL, endpoint_id uuid NOT NULL,
 window_start timestamptz NOT NULL, attempts integer NOT NULL CHECK(attempts BETWEEN 0 AND 60),
 PRIMARY KEY(organization_id,endpoint_id),
 FOREIGN KEY(organization_id,endpoint_id) REFERENCES webhook_endpoints(organization_id,id)
);
CREATE TABLE webhook_ingress_leases (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL, endpoint_id uuid NOT NULL,
 created_at timestamptz NOT NULL, expires_at timestamptz NOT NULL CHECK(expires_at=created_at+interval '15 seconds'),
 FOREIGN KEY(organization_id,endpoint_id) REFERENCES webhook_ingress_quotas(organization_id,endpoint_id)
);
CREATE INDEX webhook_ingress_expiry ON webhook_ingress_leases(expires_at);
CREATE INDEX webhook_ingress_endpoint ON webhook_ingress_leases(organization_id,endpoint_id);
DO $$ BEGIN
 IF EXISTS(SELECT FROM pg_roles WHERE rolname='bridgeops_app') THEN
  GRANT SELECT,INSERT ON webhook_delivery_receipts TO bridgeops_app;
  REVOKE UPDATE,DELETE,TRUNCATE ON webhook_delivery_receipts FROM bridgeops_app;
  GRANT SELECT,INSERT,DELETE ON webhook_ingress_quotas,webhook_ingress_leases TO bridgeops_app;
  GRANT UPDATE(window_start,attempts) ON webhook_ingress_quotas TO bridgeops_app;
  REVOKE UPDATE,TRUNCATE ON webhook_ingress_leases FROM bridgeops_app;
  REVOKE TRUNCATE ON webhook_ingress_quotas FROM bridgeops_app;
 END IF;
END $$;
