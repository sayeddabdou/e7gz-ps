-- Upgrade-path seed, loaded right after V202611171400 (PCR-013, v1.1.43): a Customer registered after the change, with
-- the Terms acceptance their registering command wrote (the same command instant as joined_at), so every later
-- migration also runs over an acceptance row. The V1 seed Customer (…041) stays without one: nothing is invented for
-- an account from before the change (PCR-013 item 6). Run as the owner, in one transaction - test setup only.

DO $seed_v1_1_43$
DECLARE at timestamptz; uid uuid := '5afe0000-0000-4000-8000-000000000241';
BEGIN
 at := tournament.begin_command(ARRAY[]::uuid[], ARRAY[]::uuid[], ARRAY[]::uuid[], ARRAY[]::uuid[]);
 INSERT INTO iam.user_account (id, full_name, username, phone_e164, email, password_hash, phone_verified,
                               phone_verified_at, email_verified, referral_code, joined_at)
 VALUES (uid, 'Seed Terms Customer', 'seedterms', '+201001234568', 'seed-terms@example.com', 'x', false, NULL, false,
         'SEEDREF2', at);
 INSERT INTO iam.terms_acceptance (user_id, terms_version, accepted_at) VALUES (uid, '2026-11-15', at);
END;
$seed_v1_1_43$;
