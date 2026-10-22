-- Existing schema-1 approval bytes/relationships remain valid. Schema 2 is additionally
-- bound to a durable prepared operation; automation acceptance is a later actor migration.
ALTER TABLE approval_requests ADD COLUMN intent_schema_version integer NOT NULL DEFAULT 1 CHECK(intent_schema_version IN (1,2));
ALTER TABLE approval_requests ADD COLUMN prepared_intent_id uuid;
ALTER TABLE approval_requests ALTER COLUMN capability TYPE varchar(24);
ALTER TABLE approval_requests ALTER COLUMN operation TYPE varchar(64);
ALTER TABLE approval_requests ADD CONSTRAINT approval_prepared_context_check CHECK(
 (intent_schema_version=1 AND prepared_intent_id IS NULL) OR (intent_schema_version=2 AND prepared_intent_id IS NOT NULL));
ALTER TABLE approval_requests ADD CONSTRAINT approval_prepared_operation_fk
 FOREIGN KEY(organization_id,execution_id,action_node_id,logical_operation_id,prepared_intent_id,argument_hash,capability,operation)
 REFERENCES execution_operation_inputs(organization_id,execution_id,node_id,logical_operation_id,id,argument_hash,capability,operation);

-- Preserve the complete legacy checks verbatim within the schema-1 branch.
DO $$ DECLARE legacy text; constraint_name text; BEGIN
 FOREACH constraint_name IN ARRAY ARRAY['approval_requests_arguments_json_check','approval_requests_canonical_intent_check','approval_requests_check5'] LOOP
  SELECT substring(pg_get_constraintdef(oid) FROM 7) INTO STRICT legacy FROM pg_constraint
   WHERE conrelid='approval_requests'::regclass AND conname=constraint_name;
  EXECUTE format('ALTER TABLE approval_requests DROP CONSTRAINT %I',constraint_name);
  EXECUTE format('ALTER TABLE approval_requests ADD CONSTRAINT %I CHECK(intent_schema_version<>1 OR %s)',constraint_name,legacy);
 END LOOP;
END $$;
ALTER TABLE approval_requests DROP CONSTRAINT approval_requests_capability_check;
ALTER TABLE approval_requests ADD CONSTRAINT approval_requests_capability_check CHECK(
 (intent_schema_version=1 AND capability='synthetic') OR (intent_schema_version=2 AND capability IN ('synthetic','support','teams','notifications','rest')));
ALTER TABLE approval_requests DROP CONSTRAINT approval_requests_operation_check;
ALTER TABLE approval_requests ADD CONSTRAINT approval_requests_operation_check CHECK(
 (intent_schema_version=1 AND operation='record') OR (intent_schema_version=2 AND operation ~ '^[A-Za-z][A-Za-z0-9_-]{0,63}$'));
ALTER TABLE approval_requests ADD CONSTRAINT approval_operation_arguments_check CHECK(intent_schema_version<>2 OR
 (octet_length(arguments_json)<=262144 AND jsonb_typeof(arguments_json::jsonb)='object'));
ALTER TABLE approval_requests ADD CONSTRAINT approval_operation_canonical_check CHECK(intent_schema_version<>2 OR ((
 octet_length(canonical_intent)<=278528 AND jsonb_typeof(canonical_intent::jsonb)='object'
 AND canonical_intent::jsonb ?& ARRAY['schemaVersion','requestRevision','operationRuleVersion','organization','execution','gateNode','actionNode','version','definitionHash','actor','logicalOperation','preparedIntent','capability','operation','classification','connection','arguments','policyVersion','policySnapshot','prompt','createdAt','expiresAt','deadlineAt']
 AND canonical_intent::jsonb-ARRAY['schemaVersion','requestRevision','operationRuleVersion','organization','execution','gateNode','actionNode','version','definitionHash','actor','logicalOperation','preparedIntent','capability','operation','classification','connection','arguments','policyVersion','policySnapshot','prompt','createdAt','expiresAt','deadlineAt']='{}'::jsonb
 AND canonical_intent::jsonb->'schemaVersion'='2'::jsonb AND canonical_intent::jsonb->'requestRevision'='0'::jsonb AND canonical_intent::jsonb->'operationRuleVersion'='1'::jsonb
 AND canonical_intent::jsonb->>'organization'=organization_id::text AND canonical_intent::jsonb->>'execution'=execution_id::text
 AND canonical_intent::jsonb->>'gateNode'=gate_node_id AND canonical_intent::jsonb->>'actionNode'=action_node_id
 AND canonical_intent::jsonb->>'version'=workflow_version_id::text AND canonical_intent::jsonb->>'definitionHash'=definition_hash
 AND canonical_intent::jsonb->'actor'=jsonb_build_object('type','USER','id',requested_by::text,'configuredBy',jsonb_build_array())
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
CREATE OR REPLACE FUNCTION guard_approval_initial_intent() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,public,pg_temp AS $$
 BEGIN
  IF NEW.status<>'PENDING' OR NEW.revision<>0 OR NOT EXISTS(
   SELECT 1 FROM public.approval_policy_versions p WHERE p.organization_id=NEW.organization_id AND p.version=NEW.policy_version
    AND NEW.policy_snapshot::jsonb=jsonb_build_object('required',p.require_record_approval,'lifetimeSeconds',p.lifetime_seconds)
  ) THEN RAISE EXCEPTION 'Invalid initial approval intent' USING ERRCODE='23514'; END IF;
  IF NEW.intent_schema_version=2 AND NOT EXISTS(
   SELECT 1 FROM public.execution_operation_inputs i
   JOIN public.execution_operation_bindings b USING(organization_id,logical_operation_id)
   JOIN public.approval_policy_versions p ON p.organization_id=i.organization_id AND p.version=NEW.policy_version
   WHERE i.organization_id=NEW.organization_id AND i.execution_id=NEW.execution_id AND i.node_id=NEW.action_node_id AND i.id=NEW.prepared_intent_id
    AND i.logical_operation_id=NEW.logical_operation_id AND i.blueprint_hash=NEW.operation_argument_hash
    AND i.argument_hash=NEW.argument_hash AND i.capability=NEW.capability AND i.operation=NEW.operation
    AND b.arguments_json=NEW.arguments_json AND b.argument_hash=NEW.argument_hash
    AND NEW.canonical_intent::jsonb->>'classification'=b.classification
    AND NEW.canonical_intent::jsonb->'connection'=CASE WHEN b.connection_id IS NULL THEN 'null'::jsonb ELSE
     jsonb_build_object('id',b.connection_id::text,'revision',b.connection_revision,'implementationVersion',b.implementation_version,'operationId',b.operation) END
    AND NEW.expires_at<=NEW.created_at+make_interval(secs=>p.lifetime_seconds)
  ) THEN RAISE EXCEPTION 'Invalid prepared approval context' USING ERRCODE='23514'; END IF;
  RETURN NEW;
 END;
$$;
REVOKE ALL ON FUNCTION guard_approval_initial_intent() FROM PUBLIC;
