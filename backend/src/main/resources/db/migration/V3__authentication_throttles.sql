CREATE TABLE authentication_throttles (
    bucket_hash varchar(64) PRIMARY KEY CHECK (bucket_hash ~ '^[a-f0-9]{64}$'),
    attempts integer NOT NULL CHECK (attempts >= 0),
    expires_at timestamptz NOT NULL
);
CREATE INDEX authentication_throttles_expiry ON authentication_throttles(expires_at);
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'bridgeops_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON authentication_throttles TO bridgeops_app;
    END IF;
END $$;