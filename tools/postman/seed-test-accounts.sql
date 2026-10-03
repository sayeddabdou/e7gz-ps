-- E7GZ PS — Postman test accounts (LOCAL DEVELOPMENT DATABASE ONLY, never staging or production).
--
-- Venue and admin accounts cannot be created through the API yet (E7-23 / E7-24), so the Postman collection
-- needs these rows. Safe to run more than once.
--
--   docker compose exec -T postgres psql -U postgres -d e7gz_ps < tools/postman/seed-test-accounts.sql
--
-- Every account's password is:  Postman-Test-2026
-- (the hash below is Argon2id m=64 MiB, t=3, p=1 — the backend's own PasswordEncoder settings)

\set hash '''$argon2id$v=19$m=65536,t=3,p=1$p+339e4A2WwtfGg0COJ/UA$LWvpp2f3aUellfC0pQyApPXOaX//tCdQydRITuHOiOo'''

INSERT INTO geo.area (id, name)
VALUES ('0192f000-0000-7000-8000-000000000001', 'Postman Test Area')
ON CONFLICT (id) DO NOTHING;

INSERT INTO venue.venue (id, area_id, name, address, location, marketplace_status, timezone, phone)
VALUES ('0192f000-0000-7000-8000-000000000011', '0192f000-0000-7000-8000-000000000001', 'Postman Venue',
        'address', 'SRID=4326;POINT(31.2 30.0)'::geography, 'ONBOARDING', 'Africa/Cairo', '+20223456789'),
       ('0192f000-0000-7000-8000-000000000012', '0192f000-0000-7000-8000-000000000001', 'Postman Disabled Venue',
        'address', 'SRID=4326;POINT(31.2 30.0)'::geography, 'ONBOARDING', 'Africa/Cairo', '+20223456789')
ON CONFLICT (id) DO NOTHING;

-- E7-23: every venue offers at least one activity; phones are E.164 Egyptian numbers.
INSERT INTO venue.venue_activity (id, venue_id, activity)
VALUES ('0192f000-0000-7000-8000-000000000041', '0192f000-0000-7000-8000-000000000011', 'PLAYSTATION'),
       ('0192f000-0000-7000-8000-000000000042', '0192f000-0000-7000-8000-000000000012', 'PLAYSTATION')
ON CONFLICT DO NOTHING;

-- Enabled venue account: login "postman.venue"
INSERT INTO iam.venue_account (id, venue_id, login_identifier, password_hash, account_status, language_preference)
VALUES ('0192f000-0000-7000-8000-000000000021', '0192f000-0000-7000-8000-000000000011', 'postman.venue', :hash,
        'ENABLED', 'en')
ON CONFLICT (id) DO NOTHING;

-- Disabled venue account: login "postman.venue.disabled" (right password must still be refused with 403)
INSERT INTO iam.venue_account (id, venue_id, login_identifier, password_hash, account_status, language_preference)
VALUES ('0192f000-0000-7000-8000-000000000022', '0192f000-0000-7000-8000-000000000012', 'postman.venue.disabled',
        :hash, 'ENABLED', 'en')
ON CONFLICT (id) DO NOTHING;
UPDATE iam.venue_account SET account_status = 'DISABLED'
 WHERE id = '0192f000-0000-7000-8000-000000000022' AND account_status <> 'DISABLED';

-- E7-24: the enabled venue's Owner Password, the 4-digit PIN 1234 (PCR-006). Kept as is on re-runs: the database only
-- lets a change move changed_at and version forward.
\set pin '''$argon2id$v=19$m=65536,t=3,p=1$Bm42UK5CWeSFnz5rVPty5g$6a50bcwryA/CBKfJZXT3Mus/6Totg7dxQL8TPkgEoSo'''
INSERT INTO iam.venue_owner_secret (venue_account_id, owner_password_hash, changed_at, version)
VALUES ('0192f000-0000-7000-8000-000000000021', :pin, now(), 0)
ON CONFLICT (venue_account_id) DO NOTHING;

-- Active admin: postman.admin@e7gz.test
INSERT INTO iam.admin_account (id, email, password_hash, full_name)
VALUES ('0192f000-0000-7000-8000-000000000031', 'postman.admin@e7gz.test', :hash, 'Postman Admin')
ON CONFLICT (id) DO NOTHING;

-- Inactive admin: postman.inactive@e7gz.test (right password must still be refused with 403)
INSERT INTO iam.admin_account (id, email, password_hash, full_name)
VALUES ('0192f000-0000-7000-8000-000000000032', 'postman.inactive@e7gz.test', :hash, 'Postman Inactive Admin')
ON CONFLICT (id) DO NOTHING;
UPDATE iam.admin_account SET is_active = false
 WHERE id = '0192f000-0000-7000-8000-000000000032' AND is_active;

-- Optional: clear the login and Owner Password rate-limit counters so the collection can be re-run straight away
-- (each run makes a few logins per account; the limit is 10 per 15 minutes per identifier + IP).
DELETE FROM iam.security_rate_limit_counter WHERE limiter_type IN ('LOGIN', 'OWNER_PASSWORD');
