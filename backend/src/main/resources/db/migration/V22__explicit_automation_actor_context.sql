-- Preserve every historical human identity and canonical intent. No work is submitted here.
ALTER TABLE webhook_endpoint_versions ADD CONSTRAINT webhook_accepted_actor_context
 UNIQUE(organization_id,endpoint_id,revision,principal_id,workflow_id,workflow_version_id,definition_hash,principal_created_by,authored_by,status);
ALTER TABLE workflow_executions ALTER COLUMN requested_by DROP NOT NULL;
ALTER TABLE workflow_executions ADD COLUMN requester_type varchar(10) NOT NULL DEFAULT 'USER';
ALTER TABLE workflow_executions ADD COLUMN automation_principal_id uuid;
ALTER TABLE workflow_executions ADD COLUMN principal_created_by uuid;
ALTER TABLE workflow_executions ADD COLUMN webhook_endpoint_id uuid;
ALTER TABLE workflow_executions ADD COLUMN webhook_endpoint_revision bigint;
ALTER TABLE workflow_executions ADD COLUMN endpoint_revision_author uuid;
ALTER TABLE workflow_executions ADD COLUMN accepted_endpoint_status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(accepted_endpoint_status='ACTIVE');
ALTER TABLE workflow_executions DROP CONSTRAINT workflow_executions_trigger_source_check;
ALTER TABLE workflow_executions ADD CONSTRAINT workflow_executions_trigger_source_check CHECK(trigger_source IN ('MANUAL','RETRY','WEBHOOK'));
ALTER TABLE workflow_executions ADD CONSTRAINT execution_actor_exactly_one CHECK(
 (requester_type='USER' AND requested_by IS NOT NULL AND trigger_source IN ('MANUAL','RETRY')
  AND automation_principal_id IS NULL AND principal_created_by IS NULL AND webhook_endpoint_id IS NULL AND webhook_endpoint_revision IS NULL AND endpoint_revision_author IS NULL)
 OR (requester_type='AUTOMATION' AND requested_by IS NULL AND trigger_source='WEBHOOK' AND graph_schema_version=2 AND retry_of_execution_id IS NULL
  AND automation_principal_id IS NOT NULL AND principal_created_by IS NOT NULL AND webhook_endpoint_id IS NOT NULL AND webhook_endpoint_revision IS NOT NULL AND webhook_endpoint_revision>=0 AND endpoint_revision_author IS NOT NULL));
ALTER TABLE workflow_executions ADD CONSTRAINT execution_accepted_endpoint_fk
 FOREIGN KEY(organization_id,webhook_endpoint_id,webhook_endpoint_revision,automation_principal_id,workflow_id,workflow_version_id,definition_hash,principal_created_by,endpoint_revision_author,accepted_endpoint_status)
 REFERENCES webhook_endpoint_versions(organization_id,endpoint_id,revision,principal_id,workflow_id,workflow_version_id,definition_hash,principal_created_by,authored_by,status);
ALTER TABLE workflow_executions ADD CONSTRAINT execution_automation_approval_context
 UNIQUE(organization_id,id,workflow_version_id,definition_hash,deadline_at,requester_type,automation_principal_id,principal_created_by,webhook_endpoint_id,webhook_endpoint_revision,endpoint_revision_author);
CREATE FUNCTION guard_execution_actor_context() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
 BEGIN
  IF ROW(OLD.requested_by,OLD.requester_type,OLD.automation_principal_id,OLD.principal_created_by,OLD.webhook_endpoint_id,OLD.webhook_endpoint_revision,OLD.endpoint_revision_author,OLD.accepted_endpoint_status)
   IS DISTINCT FROM ROW(NEW.requested_by,NEW.requester_type,NEW.automation_principal_id,NEW.principal_created_by,NEW.webhook_endpoint_id,NEW.webhook_endpoint_revision,NEW.endpoint_revision_author,NEW.accepted_endpoint_status) THEN
   RAISE EXCEPTION 'Execution request context is immutable' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
 END;
$$;
REVOKE ALL ON FUNCTION guard_execution_actor_context() FROM PUBLIC;
CREATE TRIGGER execution_actor_context_guard BEFORE UPDATE ON workflow_executions FOR EACH ROW EXECUTE FUNCTION guard_execution_actor_context();

ALTER TABLE approval_requests ALTER COLUMN requested_by DROP NOT NULL;
ALTER TABLE approval_requests ALTER COLUMN requester_type TYPE varchar(10);
ALTER TABLE approval_requests DROP CONSTRAINT approval_requests_requester_type_check;
ALTER TABLE approval_requests ADD COLUMN automation_principal_id uuid;
ALTER TABLE approval_requests ADD COLUMN principal_created_by uuid;
ALTER TABLE approval_requests ADD COLUMN webhook_endpoint_id uuid;
ALTER TABLE approval_requests ADD COLUMN webhook_endpoint_revision bigint;
ALTER TABLE approval_requests ADD COLUMN endpoint_revision_author uuid;
ALTER TABLE approval_requests ADD CONSTRAINT approval_actor_exactly_one CHECK(
 (requester_type='USER' AND requested_by IS NOT NULL
  AND automation_principal_id IS NULL AND principal_created_by IS NULL AND webhook_endpoint_id IS NULL AND webhook_endpoint_revision IS NULL AND endpoint_revision_author IS NULL)
 OR (requester_type='AUTOMATION' AND requested_by IS NULL AND intent_schema_version=2
  AND automation_principal_id IS NOT NULL AND principal_created_by IS NOT NULL AND webhook_endpoint_id IS NOT NULL AND webhook_endpoint_revision IS NOT NULL AND webhook_endpoint_revision>=0 AND endpoint_revision_author IS NOT NULL));
ALTER TABLE approval_requests ADD CONSTRAINT approval_automation_execution_fk
 FOREIGN KEY(organization_id,execution_id,workflow_version_id,definition_hash,execution_deadline_at,requester_type,automation_principal_id,principal_created_by,webhook_endpoint_id,webhook_endpoint_revision,endpoint_revision_author)
 REFERENCES workflow_executions(organization_id,id,workflow_version_id,definition_hash,deadline_at,requester_type,automation_principal_id,principal_created_by,webhook_endpoint_id,webhook_endpoint_revision,endpoint_revision_author);
ALTER TABLE approval_requests ADD CONSTRAINT approval_event_actor_context UNIQUE(organization_id,id,requester_type,automation_principal_id);

-- Same schema-2 checks as V18, with the exact actor selected from persisted typed context.
ALTER TABLE approval_requests DROP CONSTRAINT approval_operation_canonical_check;
ALTER TABLE approval_requests ADD CONSTRAINT approval_operation_canonical_check CHECK(intent_schema_version<>2 OR ((
 octet_length(canonical_intent)<=278528 AND jsonb_typeof(canonical_intent::jsonb)='object'
 AND canonical_intent::jsonb ?& ARRAY['schemaVersion','requestRevision','operationRuleVersion','organization','execution','gateNode','actionNode','version','definitionHash','actor','logicalOperation','preparedIntent','capability','operation','classification','connection','arguments','policyVersion','policySnapshot','prompt','createdAt','expiresAt','deadlineAt']
 AND canonical_intent::jsonb-ARRAY['schemaVersion','requestRevision','operationRuleVersion','organization','execution','gateNode','actionNode','version','definitionHash','actor','logicalOperation','preparedIntent','capability','operation','classification','connection','arguments','policyVersion','policySnapshot','prompt','createdAt','expiresAt','deadlineAt']='{}'::jsonb
 AND canonical_intent::jsonb->'schemaVersion'='2'::jsonb AND canonical_intent::jsonb->'requestRevision'='0'::jsonb AND canonical_intent::jsonb->'operationRuleVersion'='1'::jsonb
 AND canonical_intent::jsonb->>'organization'=organization_id::text AND canonical_intent::jsonb->>'execution'=execution_id::text
 AND canonical_intent::jsonb->>'gateNode'=gate_node_id AND canonical_intent::jsonb->>'actionNode'=action_node_id
 AND canonical_intent::jsonb->>'version'=workflow_version_id::text AND canonical_intent::jsonb->>'definitionHash'=definition_hash
 AND canonical_intent::jsonb->'actor'=CASE WHEN requester_type='USER' THEN jsonb_build_object('type','USER','id',requested_by::text,'configuredBy',jsonb_build_array()) ELSE
  jsonb_build_object('type','AUTOMATION','id',automation_principal_id::text,'configuredBy',CASE WHEN principal_created_by=endpoint_revision_author THEN jsonb_build_array(principal_created_by::text) ELSE jsonb_build_array(least(principal_created_by::text,endpoint_revision_author::text),greatest(principal_created_by::text,endpoint_revision_author::text)) END) END
 AND canonical_intent::jsonb->>'logicalOperation'=logical_operation_id::text AND canonical_intent::jsonb->>'preparedIntent'=prepared_intent_id::text
 AND canonical_intent::jsonb->>'capability'=capability AND canonical_intent::jsonb->>'operation'=operation
 AND canonical_intent::jsonb->>'classification' IN ('READ','WRITE')
 AND canonical_intent::jsonb->'arguments'=arguments_json::jsonb
 AND canonical_intent::jsonb->>'policyVersion'=policy_version::text AND canonical_intent::jsonb->'policySnapshot'=policy_snapshot::jsonb
 AND canonical_intent::jsonb->>'prompt'=prompt
 AND (canonical_intent::jsonb->>'createdAt')::timestamptz=created_at
 AND (canonical_intent::jsonb->>'expiresAt')::timestamptz=expires_at
 AND (canonical_intent::jsonb->>'deadlineAt')::timestamptz=execution_deadline_at
) IS TRUE));

-- Nullable USER columns must never turn the decision rejection predicate into SQL NULL.
CREATE OR REPLACE FUNCTION guard_approval_decision() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,public,pg_temp AS $$
 DECLARE r public.approval_requests;
 BEGIN
  SELECT * INTO STRICT r FROM public.approval_requests WHERE organization_id=NEW.organization_id AND id=NEW.request_id FOR UPDATE;
  IF r.status<>'PENDING' OR r.revision<>NEW.expected_revision
   OR (r.requester_type='USER' AND r.requested_by=NEW.decided_by) IS TRUE
   OR (r.requester_type='AUTOMATION' AND (r.principal_created_by=NEW.decided_by OR r.endpoint_revision_author=NEW.decided_by)) IS TRUE
   OR NEW.decided_at<r.created_at OR NEW.decided_at>=r.expires_at
  THEN RAISE EXCEPTION 'Invalid approval decision' USING ERRCODE='23514'; END IF;
  RETURN NEW;
 END;
$$;
REVOKE ALL ON FUNCTION guard_approval_decision() FROM PUBLIC;

ALTER TABLE organization_audit_events ALTER COLUMN actor_id DROP NOT NULL;
ALTER TABLE organization_audit_events ADD COLUMN actor_type varchar(10) NOT NULL DEFAULT 'USER';
ALTER TABLE organization_audit_events ADD COLUMN automation_actor_id uuid;
ALTER TABLE organization_audit_events ADD CONSTRAINT audit_actor_exactly_one CHECK(
 (actor_type='USER' AND actor_id IS NOT NULL AND automation_actor_id IS NULL) OR (actor_type='AUTOMATION' AND actor_id IS NULL AND automation_actor_id IS NOT NULL));
ALTER TABLE organization_audit_events ADD CONSTRAINT audit_automation_actor_fk FOREIGN KEY(organization_id,automation_actor_id) REFERENCES automation_principals(organization_id,id);
ALTER TABLE approval_events ALTER COLUMN actor_id DROP NOT NULL;
ALTER TABLE approval_events ADD COLUMN actor_type varchar(10) NOT NULL DEFAULT 'USER';
ALTER TABLE approval_events ADD COLUMN automation_actor_id uuid;
ALTER TABLE approval_events ADD CONSTRAINT approval_event_actor_exactly_one CHECK(
 (actor_type='USER' AND actor_id IS NOT NULL AND automation_actor_id IS NULL) OR (actor_type='AUTOMATION' AND actor_id IS NULL AND automation_actor_id IS NOT NULL));
ALTER TABLE approval_events ADD CONSTRAINT approval_event_automation_actor_fk FOREIGN KEY(organization_id,automation_actor_id) REFERENCES automation_principals(organization_id,id);
ALTER TABLE approval_events ADD CONSTRAINT approval_event_request_actor_fk FOREIGN KEY(organization_id,request_id,actor_type,automation_actor_id) REFERENCES approval_requests(organization_id,id,requester_type,automation_principal_id);
-- New actor/context columns inherit INSERT/SELECT, never UPDATE. Existing grants remain narrow.
