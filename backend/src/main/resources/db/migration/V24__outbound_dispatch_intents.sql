-- An external dispatch is committed before network I/O. Missing outcome means unknown, never success.
ALTER TABLE execution_operation_bindings ADD CONSTRAINT rest_dispatch_binding_context
 UNIQUE(organization_id,logical_operation_id,origin_execution_id,origin_node_id,capability,operation,classification,
        connection_id,connection_revision,implementation_version,argument_hash,blueprint_hash);
ALTER TABLE workflow_executions ADD CONSTRAINT execution_absolute_deadline_context
 UNIQUE(organization_id,id,deadline_at);
CREATE TABLE integration_outbound_dispatches (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL, execution_id uuid NOT NULL,
 node_id varchar(64) NOT NULL, logical_operation_id uuid NOT NULL,
 attempt_number integer NOT NULL CHECK(attempt_number BETWEEN 1 AND 3),
 worker_owner uuid NOT NULL, fencing_token bigint NOT NULL CHECK(fencing_token>0),
 capability varchar(24) NOT NULL DEFAULT 'rest' CHECK(capability='rest'),
 operation varchar(64) NOT NULL, classification varchar(5) NOT NULL CHECK(classification IN ('READ','WRITE')),
 connection_id uuid NOT NULL, connection_revision bigint NOT NULL, implementation_version integer NOT NULL,
 argument_hash varchar(64) NOT NULL, blueprint_hash varchar(64) NOT NULL,
 dispatched_at timestamptz NOT NULL, deadline_at timestamptz NOT NULL CHECK(dispatched_at<deadline_at),
 UNIQUE(organization_id,id,dispatched_at), UNIQUE(organization_id,logical_operation_id,attempt_number),
 FOREIGN KEY(organization_id,logical_operation_id,execution_id,node_id,capability,operation,classification,
             connection_id,connection_revision,implementation_version,argument_hash,blueprint_hash)
 REFERENCES execution_operation_bindings(organization_id,logical_operation_id,origin_execution_id,origin_node_id,
             capability,operation,classification,connection_id,connection_revision,implementation_version,argument_hash,blueprint_hash),
 FOREIGN KEY(organization_id,execution_id,deadline_at)
 REFERENCES workflow_executions(organization_id,id,deadline_at)
);
CREATE INDEX outbound_dispatch_execution ON integration_outbound_dispatches(organization_id,execution_id,node_id,attempt_number);
CREATE TABLE integration_outbound_outcomes (
 organization_id uuid NOT NULL, dispatch_id uuid NOT NULL, dispatched_at timestamptz NOT NULL,
 completed_at timestamptz NOT NULL CHECK(completed_at>=dispatched_at),
 outcome varchar(32) NOT NULL CHECK(outcome IN ('SUCCEEDED','TRANSIENT_FAILURE','PERMANENT_FAILURE','RECONCILIATION_REQUIRED')),
 error_code varchar(48) CHECK(error_code ~ '^[A-Z_]{1,48}$'),
 result_hash varchar(64) CHECK(result_hash ~ '^[a-f0-9]{64}$'),
 CHECK((outcome='SUCCEEDED' AND error_code IS NULL AND result_hash IS NOT NULL)
    OR (outcome<>'SUCCEEDED' AND error_code IS NOT NULL AND result_hash IS NULL)),
 PRIMARY KEY(organization_id,dispatch_id),
 FOREIGN KEY(organization_id,dispatch_id,dispatched_at)
 REFERENCES integration_outbound_dispatches(organization_id,id,dispatched_at)
);
CREATE TRIGGER immutable_outbound_dispatch BEFORE UPDATE OR DELETE ON integration_outbound_dispatches FOR EACH ROW EXECUTE FUNCTION reject_execution_history_mutation();
CREATE TRIGGER immutable_outbound_outcome BEFORE UPDATE OR DELETE ON integration_outbound_outcomes FOR EACH ROW EXECUTE FUNCTION reject_execution_history_mutation();
DO $$ BEGIN IF EXISTS(SELECT FROM pg_roles WHERE rolname='bridgeops_app') THEN
 GRANT SELECT,INSERT ON integration_outbound_dispatches,integration_outbound_outcomes TO bridgeops_app;
 REVOKE UPDATE,DELETE,TRUNCATE ON integration_outbound_dispatches,integration_outbound_outcomes FROM bridgeops_app;
END IF; END $$;
