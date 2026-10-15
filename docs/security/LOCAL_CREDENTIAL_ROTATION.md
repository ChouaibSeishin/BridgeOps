# Rotate potentially disclosed local database credentials

The review archive reportedly included non-empty .env database passwords. Treat those values as potentially disclosed if the archive was shared. Git ignores .env, but ignoring a file does not revoke a password or remove historical disclosure. Do not paste old or new values into chat, issues, shell commands, logs or Git.

The installation has three roles: postgres (local database administrator), bridgeops_migrator (Flyway) and bridgeops_app (runtime). Changing .env alone does not change PostgreSQL role passwords stored in an existing volume. Do not delete the database volume to rotate credentials.

## Data-preserving operator procedure

From the repository root, stop only the application clients, leaving PostgreSQL running:

```powershell
docker compose --env-file .env -f infra/compose.yaml stop backend frontend
docker compose --env-file .env -f infra/compose.yaml exec postgres psql -U postgres -d bridgeops
```

In the interactive psql session use the password prompts (new passwords are not echoed):

```text
\password bridgeops_app
\password bridgeops_migrator
\password postgres
\q
```

Use unique random values from a local password manager. Update only the corresponding ignored .env entries using a local editor: DATABASE_PASSWORD, MIGRATION_PASSWORD and POSTGRES_PASSWORD. Then recreate containers so their environment no longer contains old values; keep the named database volume:

```powershell
docker compose --env-file .env -f infra/compose.yaml up -d --force-recreate --wait --wait-timeout 180
Invoke-RestMethod http://localhost:8088/health/readiness
```

If update/startup fails, retain the database and repair .env to match the passwords already installed in PostgreSQL. Do not retry scripts/setup-local.ps1 over an existing file; it intentionally refuses overwrite. Do not use `down --volumes` for credential rotation. Remove obsolete archives/backups containing old values according to your retention needs, and never publish them.

This procedure is documented but was not executed automatically in this increment. Password changes are an operator action; no values were printed or published. Production deployments need managed secret rotation and authenticated operator access, not the local Compose administrator procedure.
