-- Immutable actual arguments are separate from mapped inputs and static graph hashes.
ALTER TABLE execution_steps ADD CONSTRAINT execution_step_operation_context_key
 UNIQUE(organization_id,execution_id,node_id,logical_operation_id,argument_hash,graph_schema_version);
CREATE TABLE execution_operation_bindings (
 organization_id uuid NOT NULL, logical_operation_id uuid NOT NULL,
 origin_execution_id uuid NOT NULL, origin_node_id varchar(64) NOT NULL,
 graph_schema_version integer NOT NULL DEFAULT 2 CHECK(graph_schema_version=2),
 blueprint_hash varchar(64) NOT NULL CHECK(blueprint_hash ~ '^[a-f0-9]{64}$'),
 capability varchar(24) NOT NULL CHECK(capability IN ('synthetic','support','teams','notifications','rest')),
 operation varchar(64) NOT NULL CHECK(operation ~ '^[A-Za-z][A-Za-z0-9_-]{0,63}$'),
 classification varchar(5) NOT NULL CHECK(classification IN ('READ','WRITE')),
 connection_id uuid, connection_revision bigint, implementation_version integer,
 arguments_json text NOT NULL CHECK(octet_length(arguments_json)<=262144 AND jsonb_typeof(arguments_json::jsonb)='object'),
 argument_hash varchar(64) NOT NULL CHECK(argument_hash=encode(sha256(convert_to(arguments_json,'UTF8')),'hex')),
 prepared_at timestamptz NOT NULL,
 PRIMARY KEY(organization_id,logical_operation_id),
 UNIQUE(organization_id,logical_operation_id,blueprint_hash,argument_hash,capability,operation,classification),
 FOREIGN KEY(organization_id,origin_execution_id,origin_node_id,logical_operation_id,blueprint_hash,graph_schema_version)
  REFERENCES execution_steps(organization_id,execution_id,node_id,logical_operation_id,argument_hash,graph_schema_version),
 FOREIGN KEY(organization_id,connection_id,connection_revision)
  REFERENCES integration_connection_versions(organization_id,connection_id,revision),
 CHECK((capability='synthetic' AND operation='record' AND classification='WRITE' AND connection_id IS NULL AND connection_revision IS NULL AND implementation_version IS NULL)
  OR (capability<>'synthetic' AND connection_id IS NOT NULL AND connection_revision IS NOT NULL AND connection_revision>=0 AND implementation_version IS NOT NULL AND implementation_version=1)),
 CHECK(capability NOT IN ('support','teams','notifications') OR
  (capability='support' AND ((operation IN ('list','get') AND classification='READ') OR (operation IN ('assignBatch','addNote') AND classification='WRITE'))) OR
  (capability='teams' AND operation IN ('list','get','resolve') AND classification='READ') OR
  (capability='notifications' AND operation='create' AND classification='WRITE'))
);
CREATE TABLE execution_operation_inputs (
 organization_id uuid NOT NULL, execution_id uuid NOT NULL, node_id varchar(64) NOT NULL,
 id uuid NOT NULL, logical_operation_id uuid NOT NULL,
 graph_schema_version integer NOT NULL DEFAULT 2 CHECK(graph_schema_version=2),
 blueprint_hash varchar(64) NOT NULL, argument_hash varchar(64) NOT NULL,
 capability varchar(24) NOT NULL, operation varchar(64) NOT NULL, classification varchar(5) NOT NULL,
 prepared_at timestamptz NOT NULL,
 PRIMARY KEY(organization_id,execution_id,node_id), UNIQUE(organization_id,id),
 UNIQUE(organization_id,execution_id,node_id,logical_operation_id,id,argument_hash,capability,operation),
 FOREIGN KEY(organization_id,execution_id,node_id,logical_operation_id,blueprint_hash,graph_schema_version)
  REFERENCES execution_steps(organization_id,execution_id,node_id,logical_operation_id,argument_hash,graph_schema_version),
 FOREIGN KEY(organization_id,logical_operation_id,blueprint_hash,argument_hash,capability,operation,classification)
  REFERENCES execution_operation_bindings(organization_id,logical_operation_id,blueprint_hash,argument_hash,capability,operation,classification)
);
DO $$ DECLARE tab text; BEGIN
 FOREACH tab IN ARRAY ARRAY['execution_operation_bindings','execution_operation_inputs'] LOOP
  EXECUTE format('CREATE TRIGGER immutable_operation_intent BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION reject_execution_history_mutation()',tab);
  IF EXISTS(SELECT FROM pg_roles WHERE rolname='bridgeops_app') THEN
   EXECUTE format('GRANT SELECT,INSERT ON %I TO bridgeops_app',tab);
   EXECUTE format('REVOKE UPDATE,DELETE,TRUNCATE ON %I FROM bridgeops_app',tab);
  END IF;
 END LOOP;
END $$;
