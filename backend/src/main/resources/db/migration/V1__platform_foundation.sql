-- Technical migration only. Identity/installation bootstrap tables arrive in Phase 1B.
CREATE TABLE platform_metadata (
    singleton_id smallint PRIMARY KEY CHECK (singleton_id = 1),
    schema_purpose varchar(64) NOT NULL,
    initialized_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO platform_metadata (singleton_id, schema_purpose)
VALUES (1, 'bridgeops-foundation');
