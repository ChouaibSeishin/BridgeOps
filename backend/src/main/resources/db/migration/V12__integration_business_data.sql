-- Additive, empty business tables. No tenant content, account or existing history is changed.
CREATE TABLE integration_secrets (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES organizations(id),
 purpose varchar(24) NOT NULL CHECK(purpose IN ('REST_CREDENTIAL','WEBHOOK_SIGNING')),
 key_id varchar(48) NOT NULL CHECK(key_id ~ '^[a-zA-Z0-9_-]{1,48}$'),
 nonce bytea NOT NULL CHECK(octet_length(nonce)=12), ciphertext bytea NOT NULL CHECK(octet_length(ciphertext) BETWEEN 17 AND 4112),
 created_at timestamptz NOT NULL, UNIQUE(organization_id,id), UNIQUE(organization_id,id,purpose)
);
CREATE TABLE integration_connections (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES organizations(id),
 name varchar(120) NOT NULL CHECK(length(btrim(name)) BETWEEN 1 AND 120),
 description varchar(1000) NOT NULL DEFAULT '', integration_type varchar(24) NOT NULL CHECK(integration_type IN ('SUPPORT','TEAMS','NOTIFICATIONS','REST')),
 implementation_version integer NOT NULL DEFAULT 1 CHECK(implementation_version=1), revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0),
 status varchar(16) NOT NULL DEFAULT 'ENABLED' CHECK(status IN ('ENABLED','DISABLED')),
 configuration_json text NOT NULL CHECK(octet_length(configuration_json)<=65536 AND jsonb_typeof(configuration_json::jsonb)='object'),
 credential_reference uuid, credential_purpose varchar(24) NOT NULL DEFAULT 'REST_CREDENTIAL' CHECK(credential_purpose='REST_CREDENTIAL'),
 created_by uuid NOT NULL REFERENCES users(id), created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
 UNIQUE(organization_id,id), FOREIGN KEY(organization_id,credential_reference,credential_purpose) REFERENCES integration_secrets(organization_id,id,purpose)
);
CREATE TABLE integration_connection_versions (
 organization_id uuid NOT NULL, connection_id uuid NOT NULL, revision bigint NOT NULL CHECK(revision>=0),
 definition_json text NOT NULL CHECK(octet_length(definition_json)<=65536 AND jsonb_typeof(definition_json::jsonb)='object'),
 definition_hash varchar(64) NOT NULL CHECK(definition_hash=encode(sha256(convert_to(definition_json,'UTF8')),'hex')),
 created_at timestamptz NOT NULL, PRIMARY KEY(organization_id,connection_id,revision),
 FOREIGN KEY(organization_id,connection_id) REFERENCES integration_connections(organization_id,id)
);
CREATE TABLE integration_health_checks (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL, connection_id uuid NOT NULL, revision bigint NOT NULL,
 checked_at timestamptz NOT NULL, status varchar(24) NOT NULL CHECK(status IN ('AVAILABLE','UNAVAILABLE','INVALID_CONFIGURATION')),
 code varchar(48) NOT NULL CHECK(code ~ '^[A-Z_]{1,48}$'),
 FOREIGN KEY(organization_id,connection_id,revision) REFERENCES integration_connection_versions(organization_id,connection_id,revision)
);
CREATE INDEX integration_connections_tenant ON integration_connections(organization_id,updated_at DESC,id);
CREATE INDEX integration_health_history ON integration_health_checks(organization_id,connection_id,checked_at DESC,id);
CREATE TABLE support_teams (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES organizations(id), name varchar(120) NOT NULL,
 provenance varchar(24) NOT NULL CHECK(provenance IN ('SEEDED_EXAMPLE','LIVE_EXECUTION')),
 UNIQUE(organization_id,id), UNIQUE(organization_id,name)
);
CREATE TABLE team_responsibilities (
 organization_id uuid NOT NULL REFERENCES organizations(id), category varchar(24) NOT NULL CHECK(category IN ('BILLING','PRODUCT','ACCESS','OTHER')),
 team_id uuid NOT NULL, PRIMARY KEY(organization_id,category), FOREIGN KEY(organization_id,team_id) REFERENCES support_teams(organization_id,id)
);
CREATE TABLE support_tickets (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES organizations(id), title varchar(200) NOT NULL CHECK(length(btrim(title)) BETWEEN 1 AND 200),
 status varchar(16) NOT NULL CHECK(status IN ('OPEN','IN_PROGRESS','RESOLVED','CLOSED')),
 priority varchar(16) NOT NULL CHECK(priority IN ('LOW','NORMAL','HIGH','URGENT')),
 category varchar(24) NOT NULL CHECK(category IN ('BILLING','PRODUCT','ACCESS','OTHER')),
 assigned_team_id uuid, revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0),
 provenance varchar(24) NOT NULL CHECK(provenance IN ('SEEDED_EXAMPLE','LIVE_EXECUTION')),
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL, UNIQUE(organization_id,id),
 FOREIGN KEY(organization_id,assigned_team_id) REFERENCES support_teams(organization_id,id)
);
CREATE INDEX support_ticket_filter ON support_tickets(organization_id,status,priority,category,id);
CREATE TABLE support_ticket_notes (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL, ticket_id uuid NOT NULL, logical_operation_id uuid NOT NULL,
 body varchar(500) NOT NULL CHECK(length(btrim(body)) BETWEEN 1 AND 500), created_at timestamptz NOT NULL,
 UNIQUE(organization_id,logical_operation_id,ticket_id), FOREIGN KEY(organization_id,ticket_id) REFERENCES support_tickets(organization_id,id)
);
CREATE TABLE internal_notifications (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES organizations(id), logical_operation_id uuid NOT NULL,
 message varchar(500) NOT NULL CHECK(length(btrim(message)) BETWEEN 1 AND 500),
 status varchar(24) NOT NULL CHECK(status IN ('DELIVERED_INTERNAL','FAILED')),
 provenance varchar(24) NOT NULL CHECK(provenance IN ('SEEDED_EXAMPLE','LIVE_EXECUTION')),
 created_at timestamptz NOT NULL, UNIQUE(organization_id,id), UNIQUE(organization_id,logical_operation_id)
);
CREATE TABLE internal_notification_attempts (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL, notification_id uuid NOT NULL, attempt_number integer NOT NULL CHECK(attempt_number BETWEEN 1 AND 3),
 outcome varchar(24) NOT NULL CHECK(outcome IN ('DELIVERED_INTERNAL','FAILED')), occurred_at timestamptz NOT NULL,
 UNIQUE(organization_id,notification_id,attempt_number), FOREIGN KEY(organization_id,notification_id) REFERENCES internal_notifications(organization_id,id)
);
CREATE INDEX internal_notification_history ON internal_notifications(organization_id,created_at DESC,id);
CREATE TABLE integration_operation_receipts (
 organization_id uuid NOT NULL, logical_operation_id uuid NOT NULL, operation varchar(64) NOT NULL,
 item_id varchar(64) NOT NULL, execution_id uuid NOT NULL, argument_hash varchar(64) NOT NULL CHECK(argument_hash ~ '^[a-f0-9]{64}$'),
 result_json text NOT NULL CHECK(octet_length(result_json)<=1048576 AND jsonb_typeof(result_json::jsonb)='object'),
 committed_at timestamptz NOT NULL, PRIMARY KEY(organization_id,logical_operation_id,operation,item_id),
 FOREIGN KEY(organization_id,execution_id) REFERENCES workflow_executions(organization_id,id)
);
CREATE TABLE integration_batch_items (
 organization_id uuid NOT NULL, execution_id uuid NOT NULL, logical_operation_id uuid NOT NULL, item_number integer NOT NULL CHECK(item_number BETWEEN 0 AND 49),
 ticket_id uuid NOT NULL, expected_revision bigint NOT NULL CHECK(expected_revision>=0), target_team_id uuid NOT NULL,
 outcome varchar(24) NOT NULL CHECK(outcome IN ('APPLIED','CONFLICT','FAILED','RECEIPT_REUSED')),
 code varchar(48) NOT NULL CHECK(code ~ '^[A-Z_]{1,48}$'), occurred_at timestamptz NOT NULL,
 PRIMARY KEY(organization_id,execution_id,logical_operation_id,item_number),
 FOREIGN KEY(organization_id,execution_id) REFERENCES workflow_executions(organization_id,id),
 FOREIGN KEY(organization_id,ticket_id) REFERENCES support_tickets(organization_id,id),
 FOREIGN KEY(organization_id,target_team_id) REFERENCES support_teams(organization_id,id)
);
CREATE TABLE integration_demo_installations (
 organization_id uuid PRIMARY KEY REFERENCES organizations(id), installed_by uuid NOT NULL REFERENCES users(id), installed_at timestamptz NOT NULL
);
DO $$ DECLARE tab text; BEGIN
 FOREACH tab IN ARRAY ARRAY['integration_secrets','integration_connection_versions','integration_health_checks','support_teams','team_responsibilities','support_ticket_notes','internal_notifications','internal_notification_attempts','integration_operation_receipts','integration_batch_items','integration_demo_installations'] LOOP
  EXECUTE format('CREATE TRIGGER immutable_integration_history BEFORE UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION reject_execution_history_mutation()',tab);
 END LOOP;
 IF EXISTS(SELECT FROM pg_roles WHERE rolname='bridgeops_app') THEN
  GRANT SELECT,INSERT ON integration_secrets,integration_connections,integration_connection_versions,integration_health_checks,support_teams,team_responsibilities,support_tickets,support_ticket_notes,internal_notifications,internal_notification_attempts,integration_operation_receipts,integration_batch_items,integration_demo_installations TO bridgeops_app;
  GRANT UPDATE(name,description,revision,status,configuration_json,credential_reference,updated_at) ON integration_connections TO bridgeops_app;
  GRANT UPDATE(assigned_team_id,status,revision,updated_at) ON support_tickets TO bridgeops_app;
  REVOKE DELETE,TRUNCATE ON integration_connections,support_tickets FROM bridgeops_app;
  REVOKE UPDATE,DELETE,TRUNCATE ON integration_secrets,integration_connection_versions,integration_health_checks,support_teams,team_responsibilities,support_ticket_notes,internal_notifications,internal_notification_attempts,integration_operation_receipts,integration_batch_items,integration_demo_installations FROM bridgeops_app;
 END IF;
END $$;
