ALTER TABLE users ADD COLUMN email_verified_at timestamptz;
CREATE TABLE installation_state (
    singleton_id smallint PRIMARY KEY CHECK(singleton_id=1),
    bootstrap_consumed_at timestamptz,
    initial_user_id uuid UNIQUE REFERENCES users(id),
    CHECK ((bootstrap_consumed_at IS NULL) = (initial_user_id IS NULL))
);
INSERT INTO installation_state(singleton_id) VALUES(1);
CREATE TABLE user_grants (
    user_id uuid NOT NULL REFERENCES users(id),
    grant_code varchar(64) NOT NULL CHECK(grant_code IN ('organization.create')),
    granted_at timestamptz NOT NULL,
    PRIMARY KEY(user_id,grant_code)
);
DO $$ BEGIN
    IF EXISTS(SELECT FROM pg_roles WHERE rolname='bridgeops_app') THEN
        REVOKE ALL ON installation_state FROM bridgeops_app;
        REVOKE INSERT, UPDATE, DELETE ON user_grants FROM bridgeops_app;
        GRANT SELECT ON user_grants TO bridgeops_app;
    END IF;
END $$;
