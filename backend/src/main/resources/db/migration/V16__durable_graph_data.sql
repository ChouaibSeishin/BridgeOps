-- Additive v2 data-flow persistence; existing v1 rows retain their original byte limits and types.
ALTER TABLE workflow_versions ADD CONSTRAINT workflow_version_schema_key UNIQUE(organization_id,workflow_id,id,schema_version);
ALTER TABLE workflow_executions ADD COLUMN graph_schema_version integer NOT NULL DEFAULT 1 CHECK(graph_schema_version IN (1,2));
ALTER TABLE workflow_executions ADD CONSTRAINT execution_schema_fk FOREIGN KEY(organization_id,workflow_id,workflow_version_id,graph_schema_version) REFERENCES workflow_versions(organization_id,workflow_id,id,schema_version);
ALTER TABLE workflow_executions ADD CONSTRAINT execution_schema_key UNIQUE(organization_id,id,graph_schema_version);
ALTER TABLE workflow_executions DROP CONSTRAINT workflow_executions_input_json_check;
ALTER TABLE workflow_executions ADD CONSTRAINT execution_trigger_input_check CHECK(octet_length(input_json)<=262144 AND jsonb_typeof(input_json::jsonb)='object' AND (graph_schema_version=2 OR input_json='{}'));

ALTER TABLE execution_steps ADD COLUMN graph_schema_version integer NOT NULL DEFAULT 1 CHECK(graph_schema_version IN (1,2));
ALTER TABLE execution_steps ADD CONSTRAINT execution_step_schema_fk FOREIGN KEY(organization_id,execution_id,graph_schema_version) REFERENCES workflow_executions(organization_id,id,graph_schema_version);
ALTER TABLE execution_steps ADD CONSTRAINT execution_step_schema_key UNIQUE(organization_id,execution_id,node_id,graph_schema_version);
ALTER TABLE execution_steps DROP CONSTRAINT execution_steps_node_type_check;
ALTER TABLE execution_steps ADD CONSTRAINT execution_step_node_type CHECK(
 (graph_schema_version=1 AND node_type IN ('manualTrigger','syntheticAction','approval','end')) OR
 (graph_schema_version=2 AND node_type IN ('manualTrigger','webhookTrigger','syntheticAction','capabilityAction','configuredHttp','condition','approval','delay','transform','notification','end')));
ALTER TABLE execution_steps DROP CONSTRAINT execution_steps_status_check;
ALTER TABLE execution_steps ADD CONSTRAINT execution_steps_status_check CHECK(status IN ('PENDING','RUNNING','WAITING_FOR_RETRY','WAITING_FOR_DELAY','WAITING_FOR_APPROVAL','SUCCEEDED','FAILED','CANCELLED','SKIPPED'));
ALTER TABLE execution_steps DROP CONSTRAINT execution_steps_output_json_check;
ALTER TABLE execution_steps ADD CONSTRAINT execution_step_output_check CHECK(jsonb_typeof(output_json::jsonb)='object' AND octet_length(output_json)<=CASE WHEN graph_schema_version=1 THEN 4096 ELSE 1048576 END);

CREATE TABLE execution_step_inputs (
 organization_id uuid NOT NULL, execution_id uuid NOT NULL, node_id varchar(64) NOT NULL,
 graph_schema_version integer NOT NULL DEFAULT 2 CHECK(graph_schema_version=2),
 previous_node_id varchar(64), arguments_json text NOT NULL CHECK(octet_length(arguments_json)<=1048576 AND jsonb_typeof(arguments_json::jsonb)='object'),
 argument_hash varchar(64) NOT NULL CHECK(argument_hash=encode(sha256(convert_to(arguments_json,'UTF8')),'hex')),
 prepared_at timestamptz NOT NULL, PRIMARY KEY(organization_id,execution_id,node_id),
 FOREIGN KEY(organization_id,execution_id,node_id,graph_schema_version) REFERENCES execution_steps(organization_id,execution_id,node_id,graph_schema_version),
 FOREIGN KEY(organization_id,execution_id,previous_node_id) REFERENCES execution_steps(organization_id,execution_id,node_id)
);
CREATE TABLE execution_step_routes (
 organization_id uuid NOT NULL, execution_id uuid NOT NULL, node_id varchar(64) NOT NULL,
 next_node_id varchar(64), selected_branch boolean, committed_at timestamptz NOT NULL,
 PRIMARY KEY(organization_id,execution_id,node_id),
 FOREIGN KEY(organization_id,execution_id,node_id) REFERENCES execution_step_inputs(organization_id,execution_id,node_id),
 FOREIGN KEY(organization_id,execution_id,next_node_id) REFERENCES execution_steps(organization_id,execution_id,node_id),
 CHECK(node_id IS DISTINCT FROM next_node_id)
);
CREATE UNIQUE INDEX execution_selected_predecessor ON execution_step_routes(organization_id,execution_id,next_node_id) WHERE next_node_id IS NOT NULL;
CREATE TABLE execution_step_delays (
 organization_id uuid NOT NULL, execution_id uuid NOT NULL, node_id varchar(64) NOT NULL,
 started_at timestamptz NOT NULL, due_at timestamptz NOT NULL CHECK(due_at>=started_at AND due_at<=started_at+interval '1 hour'),
 PRIMARY KEY(organization_id,execution_id,node_id),
 FOREIGN KEY(organization_id,execution_id,node_id) REFERENCES execution_step_inputs(organization_id,execution_id,node_id)
);
DO $$ DECLARE tab text; BEGIN
 FOREACH tab IN ARRAY ARRAY['execution_step_inputs','execution_step_routes','execution_step_delays'] LOOP
  EXECUTE format('CREATE TRIGGER immutable_execution_graph_data BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION reject_execution_history_mutation()',tab);
  IF EXISTS(SELECT FROM pg_roles WHERE rolname='bridgeops_app') THEN
   EXECUTE format('GRANT SELECT,INSERT ON %I TO bridgeops_app',tab);
   EXECUTE format('REVOKE UPDATE,DELETE,TRUNCATE ON %I FROM bridgeops_app',tab);
  END IF;
 END LOOP;
END $$;
