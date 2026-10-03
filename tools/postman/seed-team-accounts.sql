-- E7GZ PS — team accounts for trying the apps by hand (LOCAL DEVELOPMENT DATABASE ONLY, never staging or production).
--
--   docker compose exec -T postgres psql -U postgres -d e7gz_ps < tools/postman/seed-team-accounts.sql
--
-- Each of Abdallah, Amr and Sayed has three accounts - a venue (Dashboard login + Owner PIN), a Platform Admin and
-- a customer - all with the same password:
--
--   Abdallah  venue login: abdallah   admin: abdallah@e7gz.test   customer: abdallah / +201000000001   password: Abdallah123
--   Amr       venue login: amr        admin: amr@e7gz.test        customer: amr      / +201000000002   password: Amr12345
--   Sayed     venue login: sayed      admin: sayed@e7gz.test      customer: sayed    / +201000000003   password: Sayed123
--
-- Every venue's Owner Password is the 4-digit PIN 1234 (PCR-006). Hashes are Argon2id m=64 MiB, t=3, p=1 (the backend's
-- settings).
-- Safe to run more than once. The Postman collections use their own accounts: seed-test-accounts.sql.

\set abdallah '''$argon2id$v=19$m=65536,t=3,p=1$m7NFZ7XXzU8nJZglSAQlCg$CpGktjoNujYL4I8W0JgwzVELaWeO4gOl1t/3X7VRFPQ'''
\set amr      '''$argon2id$v=19$m=65536,t=3,p=1$2M4gnewn686UMMV8CpNs/Q$f8uEllX5UVa08iVC6DvVPxHAJQl0OAmTmVCJBmvhAf0'''
\set pin      '''$argon2id$v=19$m=65536,t=3,p=1$Bm42UK5CWeSFnz5rVPty5g$6a50bcwryA/CBKfJZXT3Mus/6Totg7dxQL8TPkgEoSo'''
\set sayed    '''$argon2id$v=19$m=65536,t=3,p=1$TMg1gZNdxk7CTnWD+7SLeA$glmg2Xlk1OWA8mc7JkgQsZPiR4POe8sasIxj7OOoba0'''

-- One area for the three venues.
INSERT INTO geo.area (id, name, is_active)
VALUES ('0192f100-0000-7000-8000-000000000001', 'Nasr City', true)
ON CONFLICT (id) DO NOTHING;

-- One venue each, ONBOARDING like every new venue (the admin moves it on through the API).
INSERT INTO venue.venue (id, area_id, name, address, location, marketplace_status, timezone, phone)
VALUES ('0192f100-0000-7000-8000-000000000011', '0192f100-0000-7000-8000-000000000001', 'Abdallah Gaming Lounge',
        '12 Abbas El Akkad St, Nasr City', 'SRID=4326;POINT(31.3301 30.0561)'::geography, 'ONBOARDING',
        'Africa/Cairo', '+201001110001'),
       ('0192f100-0000-7000-8000-000000000012', '0192f100-0000-7000-8000-000000000001', 'Amr PlayStation Club',
        '5 Makram Ebeid St, Nasr City', 'SRID=4326;POINT(31.3420 30.0600)'::geography, 'ONBOARDING',
        'Africa/Cairo', '+201001110002'),
       ('0192f100-0000-7000-8000-000000000013', '0192f100-0000-7000-8000-000000000001', 'Sayed Arena',
        '30 Mostafa El Nahas St, Nasr City', 'SRID=4326;POINT(31.3380 30.0500)'::geography, 'ONBOARDING',
        'Africa/Cairo', '+201001110003')
ON CONFLICT (id) DO NOTHING;

INSERT INTO venue.venue_activity (id, venue_id, activity)
VALUES ('0192f100-0000-7000-8000-000000000041', '0192f100-0000-7000-8000-000000000011', 'PLAYSTATION'),
       ('0192f100-0000-7000-8000-000000000042', '0192f100-0000-7000-8000-000000000012', 'PLAYSTATION'),
       ('0192f100-0000-7000-8000-000000000043', '0192f100-0000-7000-8000-000000000013', 'PLAYSTATION')
ON CONFLICT DO NOTHING;

-- Venue accounts (Venue Dashboard logins).
INSERT INTO iam.venue_account (id, venue_id, login_identifier, password_hash, account_status, language_preference)
VALUES ('0192f100-0000-7000-8000-000000000021', '0192f100-0000-7000-8000-000000000011', 'abdallah', :abdallah, 'ENABLED', 'en'),
       ('0192f100-0000-7000-8000-000000000022', '0192f100-0000-7000-8000-000000000012', 'amr', :amr, 'ENABLED', 'en'),
       ('0192f100-0000-7000-8000-000000000023', '0192f100-0000-7000-8000-000000000013', 'sayed', :sayed, 'ENABLED', 'en')
ON CONFLICT (id) DO NOTHING;

-- Owner PINs: 1234 for every venue. Kept as they are on re-runs: a change must move version forward.
INSERT INTO iam.venue_owner_secret (venue_account_id, owner_password_hash, changed_at, version)
VALUES ('0192f100-0000-7000-8000-000000000021', :pin, now(), 0),
       ('0192f100-0000-7000-8000-000000000022', :pin, now(), 0),
       ('0192f100-0000-7000-8000-000000000023', :pin, now(), 0)
ON CONFLICT (venue_account_id) DO NOTHING;

-- Platform Admins.
INSERT INTO iam.admin_account (id, email, password_hash, full_name)
VALUES ('0192f100-0000-7000-8000-000000000031', 'abdallah@e7gz.test', :abdallah, 'Abdallah'),
       ('0192f100-0000-7000-8000-000000000032', 'amr@e7gz.test', :amr, 'Amr Heiba'),
       ('0192f100-0000-7000-8000-000000000033', 'sayed@e7gz.test', :sayed, 'Sayed Abdo')
ON CONFLICT (id) DO NOTHING;

-- Customers, phone already verified (no SMS needed to try booking flows later).
INSERT INTO iam.user_account (id, full_name, username, phone_e164, email, password_hash, phone_verified,
                              phone_verified_at, email_verified, referral_code, joined_at)
VALUES ('0192f100-0000-7000-8000-000000000051', 'Abdallah', 'abdallah', '+201000000001', 'abdallah.customer@e7gz.test',
        :abdallah, true, now(), false, 'ABDA2K7Q', now()),
       ('0192f100-0000-7000-8000-000000000052', 'Amr Heiba', 'amr', '+201000000002', 'amr.customer@e7gz.test',
        :amr, true, now(), false, 'AMRH4P9T', now()),
       ('0192f100-0000-7000-8000-000000000053', 'Sayed Abdo', 'sayed', '+201000000003', 'sayed.customer@e7gz.test',
        :sayed, true, now(), false, 'SAYD6M3W', now())
ON CONFLICT (id) DO NOTHING;

-- Clear login and Owner Password lockouts so you can always sign in again.
DELETE FROM iam.security_rate_limit_counter WHERE limiter_type IN ('LOGIN', 'OWNER_PASSWORD');
