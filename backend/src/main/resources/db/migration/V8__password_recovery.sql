CREATE TABLE password_reset_tokens (
 id uuid PRIMARY KEY,user_id uuid NOT NULL REFERENCES users(id),token_hash varchar(64) NOT NULL UNIQUE CHECK(token_hash ~ '^[a-f0-9]{64}$'),
 created_at timestamptz NOT NULL,expires_at timestamptz NOT NULL CHECK(expires_at>created_at),consumed_at timestamptz
);
CREATE UNIQUE INDEX one_pending_password_reset ON password_reset_tokens(user_id) WHERE consumed_at IS NULL;
CREATE INDEX password_reset_expiry ON password_reset_tokens(expires_at);
DO $$ BEGIN IF EXISTS(SELECT FROM pg_roles WHERE rolname='bridgeops_app') THEN
 GRANT SELECT,INSERT ON password_reset_tokens TO bridgeops_app;
 GRANT UPDATE(consumed_at) ON password_reset_tokens TO bridgeops_app;
END IF; END $$;
