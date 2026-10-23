-- Empty, opt-in artifact manifest. Installation is an explicit authorized application operation.
CREATE TABLE integration_demo_workflows (
 organization_id uuid PRIMARY KEY REFERENCES organizations(id),
 support_connection_id uuid NOT NULL, support_revision bigint NOT NULL CHECK(support_revision>=0),
 teams_connection_id uuid NOT NULL, teams_revision bigint NOT NULL CHECK(teams_revision>=0),
 notification_connection_id uuid NOT NULL, notification_revision bigint NOT NULL CHECK(notification_revision>=0),
 manual_workflow_id uuid NOT NULL, manual_version_id uuid NOT NULL, manual_hash varchar(64) NOT NULL,
 webhook_workflow_id uuid NOT NULL, webhook_version_id uuid NOT NULL, webhook_hash varchar(64) NOT NULL,
 graph_schema_version integer NOT NULL DEFAULT 2 CHECK(graph_schema_version=2),
 created_by uuid NOT NULL REFERENCES users(id), created_at timestamptz NOT NULL,
 provenance varchar(24) NOT NULL DEFAULT 'SEEDED_EXAMPLE' CHECK(provenance='SEEDED_EXAMPLE'),
 CHECK(support_connection_id<>teams_connection_id AND support_connection_id<>notification_connection_id AND teams_connection_id<>notification_connection_id),
 CHECK(manual_workflow_id<>webhook_workflow_id),
 FOREIGN KEY(organization_id,support_connection_id,support_revision) REFERENCES integration_connection_versions(organization_id,connection_id,revision),
 FOREIGN KEY(organization_id,teams_connection_id,teams_revision) REFERENCES integration_connection_versions(organization_id,connection_id,revision),
 FOREIGN KEY(organization_id,notification_connection_id,notification_revision) REFERENCES integration_connection_versions(organization_id,connection_id,revision),
 FOREIGN KEY(organization_id,manual_workflow_id,manual_version_id,manual_hash) REFERENCES workflow_versions(organization_id,workflow_id,id,definition_hash),
 FOREIGN KEY(organization_id,webhook_workflow_id,webhook_version_id,webhook_hash) REFERENCES workflow_versions(organization_id,workflow_id,id,definition_hash),
 FOREIGN KEY(organization_id,manual_workflow_id,manual_version_id,graph_schema_version) REFERENCES workflow_versions(organization_id,workflow_id,id,schema_version),
 FOREIGN KEY(organization_id,webhook_workflow_id,webhook_version_id,graph_schema_version) REFERENCES workflow_versions(organization_id,workflow_id,id,schema_version)
);
CREATE TRIGGER immutable_demo_workflow_manifest BEFORE UPDATE OR DELETE ON integration_demo_workflows
 FOR EACH ROW EXECUTE FUNCTION reject_execution_history_mutation();
DO $$ BEGIN
 IF EXISTS(SELECT FROM pg_roles WHERE rolname='bridgeops_app') THEN
  GRANT SELECT,INSERT ON integration_demo_workflows TO bridgeops_app;
  REVOKE UPDATE,DELETE,TRUNCATE ON integration_demo_workflows FROM bridgeops_app;
 END IF;
END $$;
