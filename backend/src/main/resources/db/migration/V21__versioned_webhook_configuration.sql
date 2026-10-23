-- Configuration only. No callback or execution authority is granted by this migration.
ALTER TABLE automation_principals ADD CONSTRAINT automation_creator_context UNIQUE(organization_id,id,workflow_id,workflow_version_id,definition_hash,created_by);
CREATE TABLE webhook_endpoints (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL REFERENCES organizations(id),
 name varchar(120) NOT NULL CHECK(length(btrim(name)) BETWEEN 1 AND 120),
 principal_id uuid NOT NULL, principal_created_by uuid NOT NULL REFERENCES users(id),
 workflow_id uuid NOT NULL, workflow_version_id uuid NOT NULL, definition_hash varchar(64) NOT NULL,
 graph_schema_version integer NOT NULL DEFAULT 2 CHECK(graph_schema_version=2),
 revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0), status varchar(16) NOT NULL CHECK(status IN ('ACTIVE','REVOKED')),
 signing_secret_id uuid NOT NULL, created_by uuid NOT NULL REFERENCES users(id), created_at timestamptz NOT NULL,
 updated_by uuid NOT NULL REFERENCES users(id), updated_at timestamptz NOT NULL CHECK(updated_at>=created_at),
 UNIQUE(organization_id,id),
 UNIQUE(organization_id,id,principal_id,workflow_id,workflow_version_id,definition_hash,principal_created_by),
 FOREIGN KEY(organization_id,principal_id,workflow_id,workflow_version_id,definition_hash,principal_created_by) REFERENCES automation_principals(organization_id,id,workflow_id,workflow_version_id,definition_hash,created_by),
 FOREIGN KEY(organization_id,workflow_id,workflow_version_id,graph_schema_version) REFERENCES workflow_versions(organization_id,workflow_id,id,schema_version),
 FOREIGN KEY(organization_id,signing_secret_id) REFERENCES integration_secrets(organization_id,id)
);
CREATE INDEX webhook_tenant_history ON webhook_endpoints(organization_id,created_at DESC,id);
CREATE TABLE webhook_endpoint_versions (
 organization_id uuid NOT NULL, endpoint_id uuid NOT NULL, revision bigint NOT NULL CHECK(revision>=0),
 principal_id uuid NOT NULL, principal_created_by uuid NOT NULL, workflow_id uuid NOT NULL, workflow_version_id uuid NOT NULL, definition_hash varchar(64) NOT NULL,
 signing_secret_id uuid NOT NULL, status varchar(16) NOT NULL CHECK(status IN ('ACTIVE','REVOKED')),
 authored_by uuid NOT NULL REFERENCES users(id), authored_at timestamptz NOT NULL,
 PRIMARY KEY(organization_id,endpoint_id,revision),
 UNIQUE(organization_id,endpoint_id,revision,signing_secret_id,status,authored_by,authored_at),
 UNIQUE(organization_id,endpoint_id,revision,principal_id,workflow_id,workflow_version_id,definition_hash,principal_created_by,authored_by),
 FOREIGN KEY(organization_id,endpoint_id,principal_id,workflow_id,workflow_version_id,definition_hash,principal_created_by) REFERENCES webhook_endpoints(organization_id,id,principal_id,workflow_id,workflow_version_id,definition_hash,principal_created_by),
 FOREIGN KEY(organization_id,signing_secret_id) REFERENCES integration_secrets(organization_id,id)
);
ALTER TABLE webhook_endpoints ADD CONSTRAINT webhook_current_version FOREIGN KEY(organization_id,id,revision,signing_secret_id,status,updated_by,updated_at)
 REFERENCES webhook_endpoint_versions(organization_id,endpoint_id,revision,signing_secret_id,status,authored_by,authored_at) DEFERRABLE INITIALLY DEFERRED;
CREATE TRIGGER immutable_webhook_version BEFORE UPDATE OR DELETE ON webhook_endpoint_versions FOR EACH ROW EXECUTE FUNCTION reject_execution_history_mutation();
CREATE FUNCTION guard_webhook_revision() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,pg_temp AS $$
 BEGIN
  IF TG_OP='INSERT' THEN
   IF NEW.revision<>0 OR NEW.status<>'ACTIVE' OR NEW.created_by<>NEW.updated_by OR NEW.created_at<>NEW.updated_at THEN
    RAISE EXCEPTION 'Invalid initial webhook configuration' USING ERRCODE='23514';
   END IF;
   RETURN NEW;
  END IF;
  IF OLD.status<>'ACTIVE' OR NEW.revision<>OLD.revision+1
    OR (NEW.status='ACTIVE' AND NEW.signing_secret_id=OLD.signing_secret_id)
    OR (NEW.status='REVOKED' AND NEW.signing_secret_id<>OLD.signing_secret_id)
    OR (to_jsonb(OLD)-ARRAY['revision','status','signing_secret_id','updated_by','updated_at']) IS DISTINCT FROM
       (to_jsonb(NEW)-ARRAY['revision','status','signing_secret_id','updated_by','updated_at']) THEN
   RAISE EXCEPTION 'Invalid webhook configuration transition' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
 END;
$$;
REVOKE ALL ON FUNCTION guard_webhook_revision() FROM PUBLIC;
CREATE TRIGGER webhook_revision_guard BEFORE INSERT OR UPDATE ON webhook_endpoints FOR EACH ROW EXECUTE FUNCTION guard_webhook_revision();
CREATE FUNCTION check_webhook_secret_purpose() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog,public,pg_temp AS $$
 BEGIN
  IF NOT EXISTS(SELECT FROM public.integration_secrets WHERE organization_id=NEW.organization_id AND id=NEW.signing_secret_id AND purpose='WEBHOOK_SIGNING' AND octet_length(ciphertext)=48) THEN
   RAISE EXCEPTION 'Invalid webhook secret context' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
 END;
$$;
REVOKE ALL ON FUNCTION check_webhook_secret_purpose() FROM PUBLIC;
CREATE TRIGGER webhook_secret_context BEFORE INSERT OR UPDATE ON webhook_endpoints FOR EACH ROW EXECUTE FUNCTION check_webhook_secret_purpose();
CREATE TRIGGER webhook_version_secret_context BEFORE INSERT ON webhook_endpoint_versions FOR EACH ROW EXECUTE FUNCTION check_webhook_secret_purpose();
DO $$ BEGIN
 IF EXISTS(SELECT FROM pg_roles WHERE rolname='bridgeops_app') THEN
  GRANT SELECT,INSERT ON webhook_endpoints,webhook_endpoint_versions TO bridgeops_app;
  GRANT UPDATE(revision,status,signing_secret_id,updated_by,updated_at) ON webhook_endpoints TO bridgeops_app;
  REVOKE DELETE,TRUNCATE ON webhook_endpoints FROM bridgeops_app;
  REVOKE UPDATE,DELETE,TRUNCATE ON webhook_endpoint_versions FROM bridgeops_app;
 END IF;
END $$;
