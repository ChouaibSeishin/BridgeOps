-- Explicit non-human authority; configuring a principal does not create an execution or user.
CREATE TABLE automation_principals (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES organizations(id),
 name varchar(120) NOT NULL CHECK(length(btrim(name)) BETWEEN 1 AND 120),
 workflow_id uuid NOT NULL, workflow_version_id uuid NOT NULL, definition_hash varchar(64) NOT NULL,
 status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','REVOKED')),
 revision bigint NOT NULL DEFAULT 0 CHECK(revision BETWEEN 0 AND 1),
 created_by uuid NOT NULL REFERENCES users(id), created_at timestamptz NOT NULL,
 revoked_by uuid REFERENCES users(id), revoked_at timestamptz,
 CHECK((status='ACTIVE' AND revision=0 AND revoked_by IS NULL AND revoked_at IS NULL)
    OR (status='REVOKED' AND revision=1 AND revoked_by IS NOT NULL AND revoked_at>=created_at)),
 UNIQUE(organization_id,id),
 UNIQUE(organization_id,id,workflow_id,workflow_version_id,definition_hash),
 FOREIGN KEY(organization_id,workflow_id,workflow_version_id,definition_hash) REFERENCES workflow_versions(organization_id,workflow_id,id,definition_hash)
);
CREATE INDEX automation_tenant_history ON automation_principals(organization_id,created_at DESC,id);
CREATE TABLE automation_operation_grants (
 organization_id uuid NOT NULL, principal_id uuid NOT NULL,
 node_id varchar(64) NOT NULL CHECK(node_id ~ '^[A-Za-z][A-Za-z0-9_-]{0,63}$'),
 connection_id uuid, connection_revision bigint CHECK(connection_revision>=0),
 operation varchar(64) NOT NULL CHECK(operation ~ '^[A-Za-z][A-Za-z0-9_.-]{0,63}$'),
 classification varchar(8) NOT NULL CHECK(classification IN ('READ','WRITE')),
 CHECK((connection_id IS NULL AND connection_revision IS NULL AND operation='synthetic.record' AND classification='WRITE')
    OR (connection_id IS NOT NULL AND connection_revision IS NOT NULL)),
 PRIMARY KEY(organization_id,principal_id,node_id),
 FOREIGN KEY(organization_id,principal_id) REFERENCES automation_principals(organization_id,id),
 FOREIGN KEY(organization_id,connection_id,connection_revision) REFERENCES integration_connection_versions(organization_id,connection_id,revision)
);
CREATE TRIGGER immutable_automation_grant BEFORE UPDATE OR DELETE ON automation_operation_grants
 FOR EACH ROW EXECUTE FUNCTION reject_execution_history_mutation();
CREATE FUNCTION guard_automation_revocation() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
 BEGIN
  IF OLD.status<>'ACTIVE' OR NEW.status<>'REVOKED' OR NEW.revision<>OLD.revision+1 THEN
   RAISE EXCEPTION 'Automation revocation is terminal' USING ERRCODE='23514';
  END IF;
  IF (to_jsonb(OLD)-ARRAY['status','revision','revoked_by','revoked_at']) IS DISTINCT FROM
     (to_jsonb(NEW)-ARRAY['status','revision','revoked_by','revoked_at']) THEN
   RAISE EXCEPTION 'Automation identity and scope are immutable' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
 END;
$$;
REVOKE ALL ON FUNCTION guard_automation_revocation() FROM PUBLIC;
CREATE TRIGGER automation_revocation_guard BEFORE UPDATE ON automation_principals
 FOR EACH ROW EXECUTE FUNCTION guard_automation_revocation();
DO $$ BEGIN
 IF EXISTS(SELECT FROM pg_roles WHERE rolname='bridgeops_app') THEN
  GRANT SELECT,INSERT ON automation_principals,automation_operation_grants TO bridgeops_app;
  GRANT UPDATE(status,revision,revoked_by,revoked_at) ON automation_principals TO bridgeops_app;
  REVOKE DELETE,TRUNCATE ON automation_principals FROM bridgeops_app;
  REVOKE UPDATE,DELETE,TRUNCATE ON automation_operation_grants FROM bridgeops_app;
 END IF;
END $$;
