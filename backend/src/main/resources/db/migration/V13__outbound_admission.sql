-- Ephemeral admission counters, not operation receipts. Historical effects are never pruned here.
CREATE TABLE integration_outbound_slots (
 id uuid PRIMARY KEY, organization_id uuid NOT NULL, connection_id uuid NOT NULL,
 acquired_at timestamptz NOT NULL, lease_expires_at timestamptz NOT NULL, released_at timestamptz,
 CHECK(lease_expires_at>acquired_at AND lease_expires_at<=acquired_at+interval '15 seconds'),
 CHECK(released_at IS NULL OR released_at>=acquired_at),
 FOREIGN KEY(organization_id,connection_id) REFERENCES integration_connections(organization_id,id)
);
CREATE INDEX outbound_connection_window ON integration_outbound_slots(organization_id,connection_id,acquired_at);
CREATE INDEX outbound_active_leases ON integration_outbound_slots(lease_expires_at,organization_id) WHERE released_at IS NULL;
DO $$ BEGIN IF EXISTS(SELECT FROM pg_roles WHERE rolname='bridgeops_app') THEN
 GRANT SELECT,INSERT,DELETE ON integration_outbound_slots TO bridgeops_app;
 GRANT UPDATE(released_at) ON integration_outbound_slots TO bridgeops_app;
END IF; END $$;
