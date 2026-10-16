-- Definition lifecycle only. No executions, TEST snapshots or approval processing.
CREATE TABLE workflows (
 id uuid PRIMARY KEY,
 organization_id uuid NOT NULL REFERENCES organizations(id),
 name varchar(120) NOT NULL CHECK(length(btrim(name)) BETWEEN 1 AND 120),
 description varchar(2000) NOT NULL DEFAULT '',
 status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','ARCHIVED')),
 current_published_version_id uuid,
 created_by uuid NOT NULL REFERENCES users(id),
 created_at timestamptz NOT NULL, updated_at timestamptz NOT NULL,
 UNIQUE(organization_id,id)
);
CREATE INDEX workflows_tenant_updated ON workflows(organization_id,updated_at DESC,id);
CREATE TABLE workflow_drafts (
 organization_id uuid NOT NULL, workflow_id uuid NOT NULL,
 revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0),
 definition_json text NOT NULL CHECK(octet_length(definition_json)<=131072 AND jsonb_typeof(definition_json::jsonb)='object'),
 updated_by uuid NOT NULL REFERENCES users(id), updated_at timestamptz NOT NULL,
 PRIMARY KEY(organization_id,workflow_id),
 FOREIGN KEY(organization_id,workflow_id) REFERENCES workflows(organization_id,id)
);
CREATE TABLE workflow_versions (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL, workflow_id uuid NOT NULL,
 version_number integer NOT NULL CHECK(version_number>0),
 source_revision bigint NOT NULL CHECK(source_revision>=0),
 schema_version integer NOT NULL CHECK(schema_version=1),
 canonicalization_version integer NOT NULL CHECK(canonicalization_version=1),
 name varchar(120) NOT NULL, description varchar(2000) NOT NULL,
 definition_json text NOT NULL CHECK(octet_length(definition_json)<=131072 AND jsonb_typeof(definition_json::jsonb)='object'),
 definition_hash varchar(64) NOT NULL CHECK(definition_hash ~ '^[a-f0-9]{64}$' AND definition_hash=encode(sha256(convert_to(definition_json,'UTF8')),'hex')),
 published_by uuid NOT NULL REFERENCES users(id), published_at timestamptz NOT NULL,
 UNIQUE(organization_id,workflow_id,id),
 UNIQUE(organization_id,workflow_id,version_number),
 UNIQUE(organization_id,workflow_id,source_revision),
 FOREIGN KEY(organization_id,workflow_id) REFERENCES workflows(organization_id,id)
);
ALTER TABLE workflows ADD CONSTRAINT workflow_current_version_fk
 FOREIGN KEY(organization_id,id,current_published_version_id) REFERENCES workflow_versions(organization_id,workflow_id,id);

CREATE FUNCTION reject_workflow_version_mutation() RETURNS trigger LANGUAGE plpgsql
 SET search_path=pg_catalog,pg_temp AS $$
 BEGIN RAISE EXCEPTION 'Published workflow versions are immutable' USING ERRCODE='23514'; END;
$$;
REVOKE ALL ON FUNCTION reject_workflow_version_mutation() FROM PUBLIC;
CREATE TRIGGER immutable_workflow_version BEFORE UPDATE OR DELETE ON workflow_versions
 FOR EACH ROW EXECUTE FUNCTION reject_workflow_version_mutation();

-- Extend the existing append-only audit stream with safe, bounded workflow metadata.
ALTER TABLE organization_audit_events
 ADD COLUMN resource_type varchar(32) NOT NULL DEFAULT 'ORGANIZATION_RESOURCE',
 ADD COLUMN outcome varchar(16) NOT NULL DEFAULT 'SUCCESS' CHECK(outcome='SUCCESS'),
 ADD COLUMN revision bigint CHECK(revision>=0),
 ADD COLUMN version_id uuid,
 ADD COLUMN definition_hash varchar(64) CHECK(definition_hash ~ '^[a-f0-9]{64}$'),
 ADD COLUMN correlation_id varchar(64);

-- V5 and the existing fixed templates already include the intended workflow grants.
-- No role/permission/account/installation changes are necessary for existing tenants.
DO $$ BEGIN
 IF EXISTS(SELECT FROM pg_roles WHERE rolname='bridgeops_app') THEN
  GRANT SELECT,INSERT ON workflows,workflow_drafts,workflow_versions TO bridgeops_app;
  GRANT UPDATE(name,description,status,current_published_version_id,updated_at) ON workflows TO bridgeops_app;
  GRANT UPDATE(revision,definition_json,updated_by,updated_at) ON workflow_drafts TO bridgeops_app;
  REVOKE UPDATE,DELETE,TRUNCATE ON workflow_versions FROM bridgeops_app;
  REVOKE DELETE,TRUNCATE ON workflows,workflow_drafts FROM bridgeops_app;
  REVOKE UPDATE,DELETE,TRUNCATE ON organization_audit_events FROM bridgeops_app;
 END IF;
END $$;
