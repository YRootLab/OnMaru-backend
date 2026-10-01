#!/bin/sh
set -eu

: "${ONMARU_DB_RUNTIME_USER:?runtime DB login name is required}"
: "${ONMARU_DB_RUNTIME_PASSWORD:?runtime DB password is required}"
: "${ONMARU_DB_MIGRATION_USER:?migration DB login name is required}"
: "${ONMARU_DB_MIGRATION_PASSWORD:?migration DB password is required}"
: "${ONMARU_DB_READONLY_USER:?read-only DB login name is required}"
: "${ONMARU_DB_READONLY_PASSWORD:?read-only DB password is required}"
: "${ONMARU_DB_BACKUP_USER:?backup DB login name is required}"
: "${ONMARU_DB_BACKUP_PASSWORD:?backup DB password is required}"

psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set ON_ERROR_STOP=1 <<'SQL'
CREATE EXTENSION IF NOT EXISTS postgis;

CREATE ROLE onmaru_migration NOLOGIN;
CREATE ROLE onmaru_runtime NOLOGIN;
CREATE ROLE onmaru_readonly NOLOGIN;
CREATE ROLE onmaru_backup NOLOGIN;

\getenv runtime_user ONMARU_DB_RUNTIME_USER
\getenv runtime_password ONMARU_DB_RUNTIME_PASSWORD
\getenv migration_user ONMARU_DB_MIGRATION_USER
\getenv migration_password ONMARU_DB_MIGRATION_PASSWORD
\getenv readonly_user ONMARU_DB_READONLY_USER
\getenv readonly_password ONMARU_DB_READONLY_PASSWORD
\getenv backup_user ONMARU_DB_BACKUP_USER
\getenv backup_password ONMARU_DB_BACKUP_PASSWORD

SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'runtime_user', :'runtime_password') \gexec
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'migration_user', :'migration_password') \gexec
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'readonly_user', :'readonly_password') \gexec
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'backup_user', :'backup_password') \gexec

SELECT format('GRANT onmaru_runtime TO %I', :'runtime_user') \gexec
SELECT format('GRANT onmaru_migration TO %I', :'migration_user') \gexec
SELECT format('GRANT onmaru_readonly TO %I', :'readonly_user') \gexec
SELECT format('GRANT onmaru_backup TO %I', :'backup_user') \gexec
SELECT format('GRANT CREATE ON SCHEMA public TO %I', :'migration_user') \gexec
GRANT pg_read_all_data TO onmaru_backup;
SQL
