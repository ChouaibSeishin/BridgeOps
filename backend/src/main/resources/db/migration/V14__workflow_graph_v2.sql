-- Add schema-v2 publication without rewriting any historical definition, hash or row.
ALTER TABLE workflow_versions DROP CONSTRAINT workflow_versions_schema_version_check;
ALTER TABLE workflow_versions DROP CONSTRAINT workflow_versions_canonicalization_version_check;
ALTER TABLE workflow_versions ADD CONSTRAINT workflow_version_schema_pair
 CHECK ((schema_version=1 AND canonicalization_version=1) OR (schema_version=2 AND canonicalization_version=2));
ALTER TABLE workflow_versions ADD CONSTRAINT workflow_version_declared_schema
 CHECK (jsonb_typeof(definition_json::jsonb->'schemaVersion')='number'
        AND definition_json::jsonb->>'schemaVersion'=schema_version::text);
-- Immutable-version trigger and narrow runtime grants from V9 remain unchanged.
