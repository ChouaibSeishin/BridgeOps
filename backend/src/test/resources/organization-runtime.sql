-- Disposable Testcontainers role only; never used by deployment setup.
CREATE ROLE bridgeops_app LOGIN PASSWORD 'synthetic-runtime-fixture';
GRANT USAGE ON SCHEMA public TO bridgeops_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO bridgeops_app;
CREATE ROLE invitation_untrusted NOLOGIN;
