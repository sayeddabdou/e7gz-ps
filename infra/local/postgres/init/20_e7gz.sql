-- E7GZ PS — local development and test database setup (E7-10).
-- Runs ONCE, as the postgres superuser, when the database volume is first created
-- (docker compose, and the Testcontainers base class in backend/src/test/.../support).
-- Passwords below are for local use only; staging/production passwords come from the secret store (arch 12 §3.1).

-- Roles (arch 04 §5). The security baseline (R__security_baseline.sql) grants their rights.
-- e7gz_migrator is SUPERUSER locally because V1__baseline.sql creates the postgis/btree_gist extensions and sets
-- every object's owner to "postgres" (ALTER ... OWNER TO postgres) — both need superuser rights.
CREATE ROLE e7gz_migrator      LOGIN SUPERUSER PASSWORD 'e7gz_migrator';
CREATE ROLE e7gz_app_runtime   LOGIN PASSWORD 'e7gz_app_runtime';
CREATE ROLE e7gz_admin_runtime LOGIN PASSWORD 'e7gz_admin_runtime';

-- The application database, created EMPTY (template0): V1__baseline.sql creates the extensions itself and
-- fails if they already exist. (The postgis image installs PostGIS only into its own default databases.)
CREATE DATABASE e7gz_ps TEMPLATE template0 ENCODING 'UTF8';
