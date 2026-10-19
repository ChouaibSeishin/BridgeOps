-- Additive governance state; never update historical definitions, effects or identities.
ALTER TABLE workflow_executions DROP CONSTRAINT workflow_executions_status_check;
ALTER TABLE workflow_executions ADD CONSTRAINT workflow_executions_status_check CHECK(status IN ('QUEUED','RUNNING','WAITING_FOR_RETRY','WAITING_FOR_DELAY','WAITING_FOR_APPROVAL','SUCCEEDED','FAILED','CANCELLED'));
ALTER TABLE execution_steps DROP CONSTRAINT execution_steps_node_type_check;
ALTER TABLE execution_steps ADD CONSTRAINT execution_steps_node_type_check CHECK(node_type IN ('manualTrigger','syntheticAction','approval','end'));
ALTER TABLE execution_steps DROP CONSTRAINT execution_steps_status_check;
ALTER TABLE execution_steps ADD CONSTRAINT execution_steps_status_check CHECK(status IN ('PENDING','RUNNING','WAITING_FOR_RETRY','WAITING_FOR_APPROVAL','SUCCEEDED','FAILED','CANCELLED','SKIPPED'));
ALTER TABLE execution_work_items DROP CONSTRAINT execution_work_items_state_check;
ALTER TABLE execution_work_items ADD CONSTRAINT execution_work_items_state_check CHECK(state IN ('READY','CLAIMED','WAITING','DONE'));
CREATE OR REPLACE FUNCTION guard_execution_transition() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
 BEGIN
  IF OLD.status IN ('SUCCEEDED','FAILED','CANCELLED') THEN RAISE EXCEPTION 'Terminal execution is immutable' USING ERRCODE='23514'; END IF;
  IF NEW.revision<>OLD.revision+1 THEN RAISE EXCEPTION 'Execution revision must advance' USING ERRCODE='23514'; END IF;
  IF NEW.status<>OLD.status AND NOT (
   NEW.status IN ('FAILED','CANCELLED') OR (OLD.status='QUEUED' AND NEW.status='RUNNING') OR
   (OLD.status='RUNNING' AND NEW.status IN ('WAITING_FOR_RETRY','WAITING_FOR_DELAY','WAITING_FOR_APPROVAL','SUCCEEDED')) OR
   (OLD.status IN ('WAITING_FOR_RETRY','WAITING_FOR_DELAY','WAITING_FOR_APPROVAL') AND NEW.status='QUEUED')
  ) THEN RAISE EXCEPTION 'Invalid execution transition' USING ERRCODE='23514'; END IF;
  RETURN NEW;
 END;
$$;

CREATE TABLE approval_policy_versions (
 organization_id uuid NOT NULL REFERENCES organizations(id), version bigint NOT NULL CHECK(version>=0),
 require_record_approval boolean NOT NULL, lifetime_seconds integer NOT NULL CHECK(lifetime_seconds BETWEEN 60 AND 86400),
 created_by uuid REFERENCES users(id), created_at timestamptz NOT NULL,
 CHECK((version=0 AND created_by IS NULL) OR (version>0 AND created_by IS NOT NULL)),
 PRIMARY KEY(organization_id,version)
);
CREATE TABLE organization_approval_policies (
 organization_id uuid PRIMARY KEY REFERENCES organizations(id), version bigint NOT NULL CHECK(version>=0),
 FOREIGN KEY(organization_id,version) REFERENCES approval_policy_versions(organization_id,version)
);
ALTER TABLE workflow_executions ADD CONSTRAINT execution_approval_context_key UNIQUE(organization_id,id,workflow_version_id,definition_hash,requested_by,deadline_at);
ALTER TABLE execution_steps ADD CONSTRAINT step_approval_operation_key UNIQUE(organization_id,execution_id,node_id,logical_operation_id,argument_hash);
CREATE TABLE approval_requests (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL, execution_id uuid NOT NULL,
 gate_node_id varchar(64) NOT NULL, action_node_id varchar(64) NOT NULL,
 workflow_version_id uuid NOT NULL, definition_hash varchar(64) NOT NULL,
 requested_by uuid NOT NULL, requester_type varchar(8) NOT NULL DEFAULT 'USER' CHECK(requester_type='USER'),
 logical_operation_id uuid NOT NULL, operation_argument_hash varchar(64) NOT NULL,
 capability varchar(16) NOT NULL CHECK(capability='synthetic'), operation varchar(16) NOT NULL CHECK(operation='record'),
 arguments_json text NOT NULL CHECK(octet_length(arguments_json)<=4096 AND jsonb_typeof(arguments_json::jsonb)='object'
  AND arguments_json::jsonb ? 'message' AND arguments_json::jsonb-'message'='{}'::jsonb AND jsonb_typeof(arguments_json::jsonb->'message')='string'
  AND length(arguments_json::jsonb->>'message') BETWEEN 1 AND 500),
 argument_hash varchar(64) NOT NULL CHECK(argument_hash=encode(sha256(convert_to(arguments_json,'UTF8')),'hex')),
 policy_version bigint NOT NULL, policy_snapshot text NOT NULL CHECK(octet_length(policy_snapshot)<=1024 AND jsonb_typeof(policy_snapshot::jsonb)='object'),
 prompt varchar(500) NOT NULL CHECK(length(prompt) BETWEEN 1 AND 500),
 canonical_intent text NOT NULL CHECK(octet_length(canonical_intent)<=16384 AND jsonb_typeof(canonical_intent::jsonb)='object'),
 intent_hash varchar(64) NOT NULL CHECK(intent_hash=encode(sha256(convert_to(canonical_intent,'UTF8')),'hex')),
 created_at timestamptz NOT NULL, expires_at timestamptz NOT NULL, execution_deadline_at timestamptz NOT NULL,
 CHECK(expires_at>created_at AND expires_at<=execution_deadline_at AND expires_at<=created_at+interval '24 hours'),
 status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','APPROVED','REJECTED','EXPIRED','INVALIDATED','CANCELLED')),
 revision bigint NOT NULL DEFAULT 0 CHECK(revision BETWEEN 0 AND 1), finalized_at timestamptz,
 error_code varchar(48) CHECK(error_code ~ '^[A-Z_]{1,48}$'),
 CHECK((status='PENDING')=(finalized_at IS NULL)),
 CHECK((status='PENDING' AND revision=0) OR (status<>'PENDING' AND revision=1)),
 CHECK(canonical_intent::jsonb ?& ARRAY['schemaVersion','requestRevision','organization','execution','gateNode','actionNode','version','definitionHash','requester','logicalOperation','capability','operation','arguments','policyVersion','policySnapshot','prompt','createdAt','expiresAt','deadlineAt']
  AND (canonical_intent::jsonb->>'schemaVersion')='1' AND (canonical_intent::jsonb->>'requestRevision')='0'
  AND (canonical_intent::jsonb->>'createdAt')::timestamptz=created_at
  AND (canonical_intent::jsonb->>'expiresAt')::timestamptz=expires_at
  AND (canonical_intent::jsonb->>'deadlineAt')::timestamptz=execution_deadline_at
  AND (canonical_intent::jsonb->>'organization')=organization_id::text
  AND (canonical_intent::jsonb->>'execution')=execution_id::text
  AND (canonical_intent::jsonb->>'gateNode')=gate_node_id
  AND (canonical_intent::jsonb->>'actionNode')=action_node_id
  AND (canonical_intent::jsonb->>'version')=workflow_version_id::text
  AND (canonical_intent::jsonb->>'definitionHash')=definition_hash
  AND (canonical_intent::jsonb->>'requester')=requested_by::text
  AND (canonical_intent::jsonb->>'logicalOperation')=logical_operation_id::text
  AND (canonical_intent::jsonb->>'policyVersion')=policy_version::text
  AND canonical_intent::jsonb->'arguments'=arguments_json::jsonb
  AND canonical_intent::jsonb->'policySnapshot'=policy_snapshot::jsonb
  AND (canonical_intent::jsonb->>'prompt')=prompt
  AND (canonical_intent::jsonb->>'capability')=capability AND (canonical_intent::jsonb->>'operation')=operation),
 UNIQUE(organization_id,id), UNIQUE(organization_id,execution_id,action_node_id),
 UNIQUE(organization_id,id,execution_id,status), UNIQUE(organization_id,id,execution_id),
 FOREIGN KEY(organization_id,execution_id,workflow_version_id,definition_hash,requested_by,execution_deadline_at)
  REFERENCES workflow_executions(organization_id,id,workflow_version_id,definition_hash,requested_by,deadline_at),
 FOREIGN KEY(organization_id,execution_id,gate_node_id) REFERENCES execution_steps(organization_id,execution_id,node_id),
 FOREIGN KEY(organization_id,execution_id,action_node_id,logical_operation_id,operation_argument_hash)
  REFERENCES execution_steps(organization_id,execution_id,node_id,logical_operation_id,argument_hash),
 FOREIGN KEY(organization_id,policy_version) REFERENCES approval_policy_versions(organization_id,version)
);
CREATE INDEX approval_inbox ON approval_requests(organization_id,status,created_at DESC,id);
CREATE INDEX approval_pending_expiry ON approval_requests(expires_at) WHERE status='PENDING';
CREATE TABLE approval_decisions (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL, request_id uuid NOT NULL, decided_by uuid NOT NULL REFERENCES users(id),
 decision varchar(8) NOT NULL CHECK(decision IN ('APPROVE','REJECT')), decided_at timestamptz NOT NULL,
 expected_revision bigint NOT NULL CHECK(expected_revision=0), reason varchar(200),
 UNIQUE(organization_id,request_id), FOREIGN KEY(organization_id,request_id) REFERENCES approval_requests(organization_id,id)
);
CREATE TABLE approval_signals (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL, request_id uuid NOT NULL, execution_id uuid NOT NULL,
 outcome varchar(16) NOT NULL CHECK(outcome IN ('APPROVED','REJECTED','EXPIRED','INVALIDATED','CANCELLED')),
 created_at timestamptz NOT NULL, UNIQUE(organization_id,request_id), UNIQUE(organization_id,id,request_id,execution_id),
 FOREIGN KEY(organization_id,request_id,execution_id,outcome) REFERENCES approval_requests(organization_id,id,execution_id,status)
);
CREATE TABLE approval_signal_receipts (
 organization_id uuid NOT NULL, signal_id uuid NOT NULL, request_id uuid NOT NULL, execution_id uuid NOT NULL,
 consumed_at timestamptz NOT NULL, PRIMARY KEY(organization_id,signal_id),
 FOREIGN KEY(organization_id,signal_id,request_id,execution_id) REFERENCES approval_signals(organization_id,id,request_id,execution_id)
);
CREATE TABLE approval_invalidations (
 organization_id uuid NOT NULL, request_id uuid NOT NULL, occurred_at timestamptz NOT NULL,
 code varchar(48) NOT NULL CHECK(code IN ('POLICY_CHANGED','APPROVER_REVOKED','INTENT_MISMATCH','APPROVAL_EXPIRED','EXECUTION_TERMINAL')),
 PRIMARY KEY(organization_id,request_id), FOREIGN KEY(organization_id,request_id) REFERENCES approval_requests(organization_id,id)
);
CREATE TABLE approval_events (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL, request_id uuid NOT NULL,
 event_sequence bigint NOT NULL CHECK(event_sequence>0), occurred_at timestamptz NOT NULL,
 actor_id uuid NOT NULL REFERENCES users(id), code varchar(48) NOT NULL CHECK(code ~ '^[A-Z_]{1,48}$'),
 UNIQUE(organization_id,request_id,event_sequence), FOREIGN KEY(organization_id,request_id) REFERENCES approval_requests(organization_id,id)
);
CREATE FUNCTION guard_approval_initial_intent() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,public,pg_temp AS $$
 BEGIN
  IF NEW.status<>'PENDING' OR NEW.revision<>0 OR NOT EXISTS(
   SELECT 1 FROM public.approval_policy_versions p WHERE p.organization_id=NEW.organization_id AND p.version=NEW.policy_version
    AND NEW.policy_snapshot::jsonb=jsonb_build_object('required',p.require_record_approval,'lifetimeSeconds',p.lifetime_seconds)
  ) THEN RAISE EXCEPTION 'Invalid initial approval intent' USING ERRCODE='23514'; END IF;
  RETURN NEW;
 END;
$$;
CREATE TRIGGER approval_initial_intent BEFORE INSERT ON approval_requests FOR EACH ROW EXECUTE FUNCTION guard_approval_initial_intent();
CREATE FUNCTION guard_approval_policy_revision() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
 BEGIN
  IF NEW.organization_id<>OLD.organization_id OR NEW.version<>OLD.version+1
   THEN RAISE EXCEPTION 'Policy revision must advance' USING ERRCODE='23514'; END IF;
  RETURN NEW;
 END;
$$;
CREATE TRIGGER approval_policy_revision BEFORE UPDATE ON organization_approval_policies FOR EACH ROW EXECUTE FUNCTION guard_approval_policy_revision();
CREATE FUNCTION guard_approval_request() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,public,pg_temp AS $$
 BEGIN
  IF OLD.status<>'PENDING' OR NEW.status='PENDING' OR NEW.revision<>OLD.revision+1
   OR (to_jsonb(NEW)-ARRAY['status','revision','finalized_at','error_code'])<>(to_jsonb(OLD)-ARRAY['status','revision','finalized_at','error_code'])
  THEN RAISE EXCEPTION 'Invalid approval transition' USING ERRCODE='23514'; END IF;
  IF NEW.status IN ('APPROVED','REJECTED') AND NOT EXISTS(
   SELECT 1 FROM public.approval_decisions d WHERE d.organization_id=NEW.organization_id AND d.request_id=NEW.id
    AND d.decision=CASE WHEN NEW.status='APPROVED' THEN 'APPROVE' ELSE 'REJECT' END
  ) THEN RAISE EXCEPTION 'Approval decision required' USING ERRCODE='23514'; END IF;
  RETURN NEW;
 END;
$$;
CREATE TRIGGER approval_transition_guard BEFORE UPDATE ON approval_requests FOR EACH ROW EXECUTE FUNCTION guard_approval_request();
CREATE FUNCTION guard_approval_decision() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,public,pg_temp AS $$
 DECLARE r public.approval_requests;
 BEGIN
  SELECT * INTO STRICT r FROM public.approval_requests WHERE organization_id=NEW.organization_id AND id=NEW.request_id FOR UPDATE;
  IF r.status<>'PENDING' OR r.revision<>NEW.expected_revision OR r.requested_by=NEW.decided_by
    OR NEW.decided_at<r.created_at OR NEW.decided_at>=r.expires_at
  THEN RAISE EXCEPTION 'Invalid approval decision' USING ERRCODE='23514'; END IF;
  RETURN NEW;
 END;
$$;
CREATE TRIGGER distinct_final_approval BEFORE INSERT ON approval_decisions FOR EACH ROW EXECUTE FUNCTION guard_approval_decision();
REVOKE ALL ON FUNCTION guard_approval_request(),guard_approval_decision(),guard_approval_initial_intent(),guard_approval_policy_revision() FROM PUBLIC;
DO $$ DECLARE tab text; BEGIN
 FOREACH tab IN ARRAY ARRAY['approval_policy_versions','approval_decisions','approval_signals','approval_signal_receipts','approval_invalidations','approval_events'] LOOP
  EXECUTE format('CREATE TRIGGER immutable_approval_history BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION reject_execution_history_mutation()',tab);
 END LOOP;
 IF EXISTS(SELECT FROM pg_roles WHERE rolname='bridgeops_app') THEN
  GRANT SELECT,INSERT ON approval_policy_versions,organization_approval_policies,approval_requests,approval_decisions,approval_signals,approval_signal_receipts,approval_invalidations,approval_events TO bridgeops_app;
  GRANT UPDATE(version) ON organization_approval_policies TO bridgeops_app;
  GRANT UPDATE(status,revision,finalized_at,error_code) ON approval_requests TO bridgeops_app;
  REVOKE UPDATE,DELETE,TRUNCATE ON approval_policy_versions,approval_decisions,approval_signals,approval_signal_receipts,approval_invalidations,approval_events FROM bridgeops_app;
  REVOKE DELETE,TRUNCATE ON organization_approval_policies,approval_requests FROM bridgeops_app;
 END IF;
END $$;
