CREATE TABLE users (
    id uuid PRIMARY KEY,
    email varchar(254) NOT NULL,
    normalized_email varchar(254) NOT NULL UNIQUE,
    password_hash varchar(100) NOT NULL,
    display_name varchar(120) NOT NULL,
    account_status varchar(16) NOT NULL CHECK (account_status IN ('ACTIVE', 'DISABLED')),
    created_at timestamptz NOT NULL,
    CONSTRAINT ascii_email CHECK (email ~ '^[\x21-\x7E]+$'),
    CONSTRAINT normalized_email_matches CHECK (normalized_email = lower(btrim(email) COLLATE "C"))
);
CREATE TABLE session_families (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id),
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL CHECK (expires_at > created_at),
    revoked_at timestamptz,
    UNIQUE (id, expires_at)
);
CREATE INDEX session_families_user ON session_families(user_id);
CREATE TABLE refresh_sessions (
    id uuid PRIMARY KEY,
    family_id uuid NOT NULL,
    parent_session_id uuid UNIQUE,
    token_hash varchar(64) NOT NULL UNIQUE CHECK (token_hash ~ '^[a-f0-9]{64}$'),
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL CHECK (expires_at > created_at),
    consumed_at timestamptz,
    UNIQUE (family_id, id),
    FOREIGN KEY (family_id, expires_at) REFERENCES session_families(id, expires_at),
    FOREIGN KEY (family_id, parent_session_id) REFERENCES refresh_sessions(family_id, id)
);
CREATE INDEX refresh_sessions_family ON refresh_sessions(family_id);
CREATE TABLE identity_security_events (
    id uuid PRIMARY KEY,
    user_id uuid REFERENCES users(id),
    family_id uuid REFERENCES session_families(id),
    event_type varchar(48) NOT NULL,
    occurred_at timestamptz NOT NULL
);
CREATE INDEX identity_security_events_user_time ON identity_security_events(user_id, occurred_at DESC);
-- Testcontainers has no Compose roles. In Compose, grant only this increment's DML.
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'bridgeops_app') THEN
        GRANT SELECT, UPDATE ON users TO bridgeops_app;
        GRANT SELECT, INSERT, UPDATE ON session_families, refresh_sessions TO bridgeops_app;
        GRANT SELECT, INSERT ON identity_security_events TO bridgeops_app;
        REVOKE UPDATE, DELETE ON identity_security_events FROM bridgeops_app;
    END IF;
END $$;
