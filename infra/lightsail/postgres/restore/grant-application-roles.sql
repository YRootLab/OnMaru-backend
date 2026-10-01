GRANT USAGE ON SCHEMA onmaru, onmaru_registry TO onmaru_runtime, onmaru_readonly;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA onmaru TO onmaru_runtime;
GRANT SELECT ON ALL TABLES IN SCHEMA onmaru_registry TO onmaru_runtime;
GRANT SELECT ON ALL TABLES IN SCHEMA onmaru, onmaru_registry TO onmaru_readonly;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA onmaru TO onmaru_runtime;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA onmaru_registry TO onmaru_runtime;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA onmaru, onmaru_registry TO onmaru_readonly;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

\getenv migration_user ONMARU_DB_MIGRATION_USER
SELECT format(
    'ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA onmaru GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO onmaru_runtime',
    :'migration_user'
) \gexec
SELECT format(
    'ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA onmaru GRANT SELECT ON TABLES TO onmaru_readonly',
    :'migration_user'
) \gexec
SELECT format(
    'ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA onmaru GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO onmaru_runtime',
    :'migration_user'
) \gexec
SELECT format(
    'ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA onmaru_registry GRANT SELECT ON TABLES TO onmaru_runtime, onmaru_readonly',
    :'migration_user'
) \gexec
SELECT format(
    'ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA onmaru_registry GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO onmaru_runtime',
    :'migration_user'
) \gexec
