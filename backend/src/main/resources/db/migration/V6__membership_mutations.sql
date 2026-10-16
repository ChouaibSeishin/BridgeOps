DO $$ BEGIN
 IF EXISTS(SELECT FROM pg_roles WHERE rolname='bridgeops_app') THEN
  GRANT UPDATE(revision) ON organizations TO bridgeops_app;
  GRANT UPDATE(status,revision) ON organization_memberships TO bridgeops_app;
  GRANT DELETE ON membership_roles TO bridgeops_app;
 END IF;
END $$;
