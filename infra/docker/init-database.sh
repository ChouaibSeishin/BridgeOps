#!/bin/sh
set -eu
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set=ON_ERROR_STOP=1 \
  --set=app_password="$DATABASE_PASSWORD" --set=migration_password="$MIGRATION_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE bridgeops_app LOGIN PASSWORD %L', :'app_password') \gexec
SELECT format('CREATE ROLE bridgeops_migrator LOGIN PASSWORD %L', :'migration_password') \gexec
GRANT CONNECT ON DATABASE bridgeops TO bridgeops_app, bridgeops_migrator;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO bridgeops_migrator;
GRANT USAGE ON SCHEMA public TO bridgeops_app;
ALTER DEFAULT PRIVILEGES FOR ROLE bridgeops_migrator IN SCHEMA public GRANT SELECT ON TABLES TO bridgeops_app;
SQL
