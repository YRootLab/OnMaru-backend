#!/bin/sh
set -eu

: "${ONMARU_DB_AI_RUNTIME_USER:?ai runtime DB login name is required}"
: "${ONMARU_DB_AI_RUNTIME_PASSWORD:?ai runtime DB password is required}"
: "${ONMARU_DB_AI_MIGRATION_USER:?ai migration DB login name is required}"
: "${ONMARU_DB_AI_MIGRATION_PASSWORD:?ai migration DB password is required}"

psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set ON_ERROR_STOP=1 <<'SQL'
CREATE EXTENSION IF NOT EXISTS vector;

CREATE SCHEMA IF NOT EXISTS ai;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'onmaru_ai_runtime') THEN
        CREATE ROLE onmaru_ai_runtime NOLOGIN;
    ELSE
        ALTER ROLE onmaru_ai_runtime NOLOGIN;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'onmaru_ai_migration') THEN
        CREATE ROLE onmaru_ai_migration NOLOGIN;
    ELSE
        ALTER ROLE onmaru_ai_migration NOLOGIN;
    END IF;
END
$$;

\getenv ai_runtime_user ONMARU_DB_AI_RUNTIME_USER
\getenv ai_runtime_password ONMARU_DB_AI_RUNTIME_PASSWORD
\getenv ai_migration_user ONMARU_DB_AI_MIGRATION_USER
\getenv ai_migration_password ONMARU_DB_AI_MIGRATION_PASSWORD

CREATE OR REPLACE FUNCTION pg_temp.ensure_login_role(role_name text, role_pass text) RETURNS void AS $f$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = role_name) THEN
        EXECUTE format('CREATE ROLE %I LOGIN PASSWORD %L', role_name, role_pass);
    ELSE
        EXECUTE format('ALTER ROLE %I WITH LOGIN PASSWORD %L', role_name, role_pass);
    END IF;
END;
$f$ LANGUAGE plpgsql;

SELECT pg_temp.ensure_login_role(:'ai_runtime_user', :'ai_runtime_password');
SELECT pg_temp.ensure_login_role(:'ai_migration_user', :'ai_migration_password');

SELECT format('GRANT onmaru_ai_runtime TO %I', :'ai_runtime_user') \gexec
SELECT format('GRANT onmaru_ai_migration TO %I', :'ai_migration_user') \gexec

REVOKE ALL ON SCHEMA public FROM onmaru_ai_runtime, onmaru_ai_migration;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

GRANT USAGE ON SCHEMA ai TO onmaru_ai_runtime;
GRANT USAGE, CREATE ON SCHEMA ai TO onmaru_ai_migration;

ALTER DEFAULT PRIVILEGES IN SCHEMA ai
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO onmaru_ai_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA ai
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO onmaru_ai_runtime;

SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA ai GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO onmaru_ai_runtime', :'ai_migration_user') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA ai GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO onmaru_ai_runtime', :'ai_migration_user') \gexec
SQL
