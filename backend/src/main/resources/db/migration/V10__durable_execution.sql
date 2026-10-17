-- Additive durable sequential runtime. Existing definitions and identities are unchanged.
CREATE TABLE workflow_executions (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL, workflow_id uuid NOT NULL,
 workflow_version_id uuid NOT NULL, definition_hash varchar(64) NOT NULL CHECK(definition_hash ~ '^[a-f0-9]{64}$'),
 requested_by uuid NOT NULL REFERENCES users(id), trigger_source varchar(16) NOT NULL CHECK(trigger_source IN ('MANUAL','RETRY')),
 mode varchar(16) NOT NULL DEFAULT 'LIVE_EXECUTION' CHECK(mode='LIVE_EXECUTION'),
 input_json text NOT NULL CHECK(input_json='{}'), idempotency_key varchar(128) NOT NULL,
 request_fingerprint varchar(64) NOT NULL CHECK(request_fingerprint ~ '^[a-f0-9]{64}$'),
 retry_of_execution_id uuid,
 status varchar(24) NOT NULL CHECK(status IN ('QUEUED','RUNNING','WAITING_FOR_RETRY','WAITING_FOR_DELAY','SUCCEEDED','FAILED','CANCELLED')),
 revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0), current_position integer NOT NULL DEFAULT 0 CHECK(current_position BETWEEN 0 AND 50),
 created_at timestamptz NOT NULL, started_at timestamptz, completed_at timestamptz,
 deadline_at timestamptz NOT NULL, not_before timestamptz NOT NULL,
 error_code varchar(48) CHECK(error_code ~ '^[A-Z_]{1,48}$'),
 CHECK(deadline_at=created_at+interval '24 hours'),
 CHECK(not_before>=created_at AND not_before<=created_at+interval '1 hour'),
 CHECK((status IN ('SUCCEEDED','FAILED','CANCELLED'))=(completed_at IS NOT NULL)),
 UNIQUE(organization_id,id), UNIQUE(organization_id,workflow_id,trigger_source,idempotency_key),
 FOREIGN KEY(organization_id,workflow_id,workflow_version_id) REFERENCES workflow_versions(organization_id,workflow_id,id),
 FOREIGN KEY(organization_id,retry_of_execution_id) REFERENCES workflow_executions(organization_id,id)
);
CREATE INDEX execution_tenant_history ON workflow_executions(organization_id,created_at DESC,id);
CREATE INDEX execution_active_deadline ON workflow_executions(deadline_at) WHERE completed_at IS NULL;
CREATE TABLE execution_steps (
 organization_id uuid NOT NULL, execution_id uuid NOT NULL, node_id varchar(64) NOT NULL,
 position integer NOT NULL CHECK(position BETWEEN 0 AND 49), node_type varchar(24) NOT NULL CHECK(node_type IN ('manualTrigger','syntheticAction','end')),
 logical_operation_id uuid NOT NULL, argument_hash varchar(64) NOT NULL CHECK(argument_hash ~ '^[a-f0-9]{64}$'),
 status varchar(24) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','RUNNING','WAITING_FOR_RETRY','SUCCEEDED','FAILED','CANCELLED','SKIPPED')),
 attempt_count integer NOT NULL DEFAULT 0 CHECK(attempt_count BETWEEN 0 AND 3),
 attempt_started_at timestamptz, completed_at timestamptz, output_json text CHECK(octet_length(output_json)<=4096 AND jsonb_typeof(output_json::jsonb)='object'),
 error_code varchar(48) CHECK(error_code ~ '^[A-Z_]{1,48}$'),
 PRIMARY KEY(organization_id,execution_id,node_id), UNIQUE(organization_id,execution_id,position),
 FOREIGN KEY(organization_id,execution_id) REFERENCES workflow_executions(organization_id,id)
);
CREATE TABLE execution_attempts (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL, execution_id uuid NOT NULL, node_id varchar(64) NOT NULL,
 attempt_number integer NOT NULL CHECK(attempt_number BETWEEN 1 AND 3),
 started_at timestamptz NOT NULL, completed_at timestamptz NOT NULL CHECK(completed_at>=started_at),
 outcome varchar(24) NOT NULL CHECK(outcome IN ('SUCCEEDED','TRANSIENT_FAILURE','PERMANENT_FAILURE','INTERRUPTED','CANCELLED','DEADLINE_EXCEEDED','RECONCILIATION_REQUIRED')),
 error_code varchar(48) CHECK(error_code ~ '^[A-Z_]{1,48}$'),
 UNIQUE(organization_id,execution_id,node_id,attempt_number),
 FOREIGN KEY(organization_id,execution_id,node_id) REFERENCES execution_steps(organization_id,execution_id,node_id)
);
CREATE TABLE execution_events (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL, execution_id uuid NOT NULL,
 event_sequence bigint NOT NULL CHECK(event_sequence>0), occurred_at timestamptz NOT NULL,
 code varchar(48) NOT NULL CHECK(code ~ '^[A-Z_]{1,48}$'), node_id varchar(64),
 UNIQUE(organization_id,execution_id,event_sequence),
 FOREIGN KEY(organization_id,execution_id) REFERENCES workflow_executions(organization_id,id),
 FOREIGN KEY(organization_id,execution_id,node_id) REFERENCES execution_steps(organization_id,execution_id,node_id)
);
CREATE TABLE execution_work_items (
 organization_id uuid NOT NULL, execution_id uuid NOT NULL,
 due_at timestamptz NOT NULL, state varchar(16) NOT NULL CHECK(state IN ('READY','CLAIMED','DONE')),
 lease_owner uuid, lease_expires_at timestamptz, fencing_token bigint NOT NULL DEFAULT 0 CHECK(fencing_token>=0),
 CHECK((state='CLAIMED')=(lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)),
 PRIMARY KEY(organization_id,execution_id), FOREIGN KEY(organization_id,execution_id) REFERENCES workflow_executions(organization_id,id)
);
CREATE INDEX execution_work_due ON execution_work_items(due_at,organization_id) WHERE state<>'DONE';
CREATE TABLE synthetic_records (
 organization_id uuid NOT NULL REFERENCES organizations(id), logical_operation_id uuid NOT NULL,
 message varchar(500) NOT NULL CHECK(length(message) BETWEEN 1 AND 500), recorded_at timestamptz NOT NULL,
 PRIMARY KEY(organization_id,logical_operation_id)
);
CREATE TABLE execution_operation_receipts (
 organization_id uuid NOT NULL, logical_operation_id uuid NOT NULL,
 capability varchar(24) NOT NULL CHECK(capability='synthetic.record'), item_id varchar(64) NOT NULL,
 argument_hash varchar(64) NOT NULL CHECK(argument_hash ~ '^[a-f0-9]{64}$'),
 result_json text NOT NULL CHECK(octet_length(result_json)<=4096 AND jsonb_typeof(result_json::jsonb)='object'),
 committed_at timestamptz NOT NULL,
 PRIMARY KEY(organization_id,capability,logical_operation_id,item_id),
 FOREIGN KEY(organization_id,logical_operation_id) REFERENCES synthetic_records(organization_id,logical_operation_id)
);
CREATE FUNCTION reject_execution_history_mutation() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
 BEGIN RAISE EXCEPTION 'Execution history is append only' USING ERRCODE='23514'; END;
$$;
REVOKE ALL ON FUNCTION reject_execution_history_mutation() FROM PUBLIC;
DO $$ DECLARE tab text; BEGIN
 FOREACH tab IN ARRAY ARRAY['execution_attempts','execution_events','synthetic_records','execution_operation_receipts'] LOOP
  EXECUTE format('CREATE TRIGGER immutable_execution_history BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION reject_execution_history_mutation()',tab);
 END LOOP;
 IF EXISTS(SELECT FROM pg_roles WHERE rolname='bridgeops_app') THEN
  GRANT SELECT,INSERT ON workflow_executions,execution_steps,execution_attempts,execution_events,execution_work_items,synthetic_records,execution_operation_receipts TO bridgeops_app;
  GRANT UPDATE(status,revision,current_position,started_at,completed_at,error_code) ON workflow_executions TO bridgeops_app;
  GRANT UPDATE(status,attempt_count,attempt_started_at,completed_at,output_json,error_code) ON execution_steps TO bridgeops_app;
  GRANT UPDATE(due_at,state,lease_owner,lease_expires_at,fencing_token) ON execution_work_items TO bridgeops_app;
  REVOKE DELETE,TRUNCATE ON workflow_executions,execution_steps,execution_work_items FROM bridgeops_app;
  REVOKE UPDATE,DELETE,TRUNCATE ON execution_attempts,execution_events,synthetic_records,execution_operation_receipts FROM bridgeops_app;
 END IF;
END $$;
-- Bind the pinned hash to the same immutable tenant/workflow/version row.
ALTER TABLE workflow_versions ADD CONSTRAINT workflow_version_hash_key UNIQUE(organization_id,workflow_id,id,definition_hash);
ALTER TABLE workflow_executions ADD CONSTRAINT execution_pinned_hash_fk FOREIGN KEY(organization_id,workflow_id,workflow_version_id,definition_hash) REFERENCES workflow_versions(organization_id,workflow_id,id,definition_hash);
ALTER TABLE workflow_executions ADD CONSTRAINT execution_retry_snapshot_key UNIQUE(organization_id,workflow_id,workflow_version_id,id);
ALTER TABLE workflow_executions ADD CONSTRAINT execution_retry_snapshot_fk FOREIGN KEY(organization_id,workflow_id,workflow_version_id,retry_of_execution_id) REFERENCES workflow_executions(organization_id,workflow_id,workflow_version_id,id);
CREATE FUNCTION guard_execution_transition() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
 BEGIN
  IF OLD.status IN ('SUCCEEDED','FAILED','CANCELLED') THEN RAISE EXCEPTION 'Terminal execution is immutable' USING ERRCODE='23514'; END IF;
  IF NEW.revision<>OLD.revision+1 THEN RAISE EXCEPTION 'Execution revision must advance' USING ERRCODE='23514'; END IF;
  IF NEW.status<>OLD.status AND NOT (
   NEW.status IN ('FAILED','CANCELLED') OR
   (OLD.status='QUEUED' AND NEW.status='RUNNING') OR
   (OLD.status='RUNNING' AND NEW.status IN ('WAITING_FOR_RETRY','WAITING_FOR_DELAY','SUCCEEDED')) OR
   (OLD.status IN ('WAITING_FOR_RETRY','WAITING_FOR_DELAY') AND NEW.status='QUEUED')
  ) THEN RAISE EXCEPTION 'Invalid execution transition' USING ERRCODE='23514'; END IF;
  RETURN NEW;
 END;
$$;
REVOKE ALL ON FUNCTION guard_execution_transition() FROM PUBLIC;
CREATE TRIGGER execution_transition_guard BEFORE UPDATE ON workflow_executions FOR EACH ROW EXECUTE FUNCTION guard_execution_transition();
