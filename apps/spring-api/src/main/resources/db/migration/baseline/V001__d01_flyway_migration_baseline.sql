-- onmaru-checksum: d01-v001-20260914
-- Issue: #67 D01 Flyway migration ownership, version, and baseline system.

CREATE EXTENSION IF NOT EXISTS postgis;

CREATE SCHEMA IF NOT EXISTS onmaru;
CREATE SCHEMA IF NOT EXISTS onmaru_registry;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'onmaru_migration') THEN
        CREATE ROLE onmaru_migration NOLOGIN;
    ELSE
        ALTER ROLE onmaru_migration NOLOGIN PASSWORD NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'onmaru_runtime') THEN
        CREATE ROLE onmaru_runtime NOLOGIN;
    ELSE
        ALTER ROLE onmaru_runtime NOLOGIN PASSWORD NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'onmaru_readonly') THEN
        CREATE ROLE onmaru_readonly NOLOGIN;
    ELSE
        ALTER ROLE onmaru_readonly NOLOGIN PASSWORD NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'onmaru_backup') THEN
        CREATE ROLE onmaru_backup NOLOGIN;
    ELSE
        ALTER ROLE onmaru_backup NOLOGIN PASSWORD NULL;
    END IF;
END
$$;

CREATE TABLE IF NOT EXISTS onmaru_registry.migration_version_reservations (
    version text PRIMARY KEY,
    reserved_for text NOT NULL,
    issue_number integer NOT NULL,
    description text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT migration_version_reservations_version_format_ck CHECK (version ~ '^[0-9]{3}$'),
    CONSTRAINT migration_version_reservations_issue_ck CHECK (issue_number > 0)
);

INSERT INTO onmaru_registry.migration_version_reservations (
    version,
    reserved_for,
    issue_number,
    description
) VALUES (
    '001',
    'D01',
    67,
    'Flyway migration namespace, baseline registry, checksum policy, and database role grants'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON SCHEMA onmaru FROM PUBLIC;
REVOKE ALL ON SCHEMA onmaru_registry FROM PUBLIC;

GRANT USAGE ON SCHEMA onmaru TO onmaru_runtime, onmaru_readonly;
GRANT USAGE ON SCHEMA onmaru_registry TO onmaru_runtime, onmaru_readonly;
GRANT SELECT ON ALL TABLES IN SCHEMA onmaru TO onmaru_readonly;
GRANT SELECT ON ALL TABLES IN SCHEMA onmaru_registry TO onmaru_readonly;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA onmaru TO onmaru_runtime;
GRANT SELECT ON ALL TABLES IN SCHEMA onmaru_registry TO onmaru_runtime;
GRANT USAGE, CREATE ON SCHEMA onmaru TO onmaru_migration;
GRANT USAGE, CREATE ON SCHEMA onmaru_registry TO onmaru_migration;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA onmaru TO onmaru_migration;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA onmaru_registry TO onmaru_migration;

ALTER DEFAULT PRIVILEGES IN SCHEMA onmaru
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO onmaru_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA onmaru
    GRANT SELECT ON TABLES TO onmaru_readonly;
ALTER DEFAULT PRIVILEGES IN SCHEMA onmaru_registry
    GRANT SELECT ON TABLES TO onmaru_runtime, onmaru_readonly;

GRANT pg_read_all_data TO onmaru_backup;
