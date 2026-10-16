CREATE TABLE organizations (
 id uuid PRIMARY KEY,name varchar(120) NOT NULL,slug varchar(64) NOT NULL UNIQUE CHECK(slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
 status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE','ARCHIVED')),revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0),
 created_by uuid NOT NULL REFERENCES users(id),created_at timestamptz NOT NULL,updated_at timestamptz NOT NULL
);
CREATE TABLE organization_memberships (
 id uuid PRIMARY KEY,organization_id uuid NOT NULL REFERENCES organizations(id),user_id uuid NOT NULL REFERENCES users(id),
 status varchar(16) NOT NULL CHECK(status IN ('ACTIVE','SUSPENDED','REMOVED')),revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0),joined_at timestamptz NOT NULL,
 UNIQUE(organization_id,user_id),UNIQUE(organization_id,id)
);
CREATE INDEX memberships_user ON organization_memberships(user_id,organization_id);
CREATE TABLE roles (
 id uuid PRIMARY KEY,organization_id uuid NOT NULL REFERENCES organizations(id),
 name varchar(32) NOT NULL CHECK(name IN ('OWNER','ADMIN','BUILDER','OPERATOR','APPROVER','VIEWER')),
 UNIQUE(organization_id,name),UNIQUE(organization_id,id)
);
CREATE TABLE permissions(code varchar(64) PRIMARY KEY);
CREATE TABLE role_permissions (
 organization_id uuid NOT NULL,role_id uuid NOT NULL,permission_code varchar(64) NOT NULL REFERENCES permissions(code),
 PRIMARY KEY(organization_id,role_id,permission_code),FOREIGN KEY(organization_id,role_id) REFERENCES roles(organization_id,id)
);
CREATE TABLE membership_roles (
 organization_id uuid NOT NULL,membership_id uuid NOT NULL,role_id uuid NOT NULL,
 PRIMARY KEY(organization_id,membership_id,role_id),
 FOREIGN KEY(organization_id,membership_id) REFERENCES organization_memberships(organization_id,id),
 FOREIGN KEY(organization_id,role_id) REFERENCES roles(organization_id,id)
);
CREATE TABLE organization_audit_events (
 id uuid PRIMARY KEY,organization_id uuid NOT NULL REFERENCES organizations(id),actor_id uuid NOT NULL REFERENCES users(id),
 action varchar(64) NOT NULL,target_id uuid NOT NULL,occurred_at timestamptz NOT NULL
);
CREATE INDEX organization_audit_time ON organization_audit_events(organization_id,occurred_at,id);
INSERT INTO permissions(code) SELECT unnest(ARRAY['organization.read','membership.read','integration.read','workflow.read','execution.read','approval.read','capability.read',
'organization.manage','ownership.transfer','membership.invite','membership.manage','membership.grant_privileged','policy.manage','integration.manage','workflow.edit','workflow.publish',
'execution.run','execution.retry','execution.cancel','capability.write','approval.decide','audit.read','ai.use']);
DO $$ BEGIN
 IF EXISTS(SELECT FROM pg_roles WHERE rolname='bridgeops_app') THEN
  GRANT SELECT,INSERT ON organizations,roles,role_permissions,organization_memberships,membership_roles TO bridgeops_app;
  GRANT SELECT ON permissions TO bridgeops_app;
  GRANT SELECT,INSERT ON organization_audit_events TO bridgeops_app;
  REVOKE UPDATE,DELETE ON organization_audit_events FROM bridgeops_app;
 END IF;
END $$;
