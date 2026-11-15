-- Upgrade-path seed, loaded right after V202610251200 (PCR-010, v1.1.40): the per-hour Booking Fee mode with an hourly
-- fee, and a changed Points per fee unit, written as a Platform Admin change writes them (an effective version and the
-- current value, through the producers' write tokens), so every later migration also runs over a non-launch fee
-- configuration and the new keys' history. No Points payment row: it needs an Online Booking created through its full
-- intake (current Operating Session, reservation, Loyalty context), which a plain seed cannot write - as for v1.1.39's
-- Wallet payment, the integration tests cover those rows. Run as the owner, outside any command - test setup only.

DO $seed_v1_1_40$
DECLARE k text; val jsonb; oldv jsonb; vid uuid; tok uuid; at timestamptz := clock_timestamp();
 adm uuid := '5afe0000-0000-4000-8000-000000000051';
BEGIN
 FOREACH k IN ARRAY ARRAY['booking.fee_mode','booking.fee_per_hour','loyalty.points_per_fee_unit'] LOOP
   val := CASE k WHEN 'booking.fee_mode' THEN '"PER_HOUR"'::jsonb WHEN 'booking.fee_per_hour' THEN '10.00'::jsonb
                 ELSE '8000'::jsonb END;
   SELECT value INTO oldv FROM config.system_configuration WHERE key = k;
   vid := gen_random_uuid();
   IF config.is_loyalty_key(k) THEN
     tok := loyalty.enter_write('config.configuration_version', vid::text);
   ELSE
     tok := technical.enter_write('config.configuration_version', vid::text);
   END IF;
   INSERT INTO config.configuration_version (id, key, old_value, new_value, effective_from, admin_id, reason, changed_at)
   VALUES (vid, k, oldv, val, at, adm, 'Upgrade seed: per-hour fee', at);
   IF config.is_loyalty_key(k) THEN
     PERFORM loyalty.leave_write(tok);
     tok := loyalty.enter_write('config.system_configuration', k);
   ELSE
     PERFORM technical.leave_write(tok);
     tok := technical.enter_write('config.system_configuration', k);
   END IF;
   UPDATE config.system_configuration SET value = val, updated_at = at WHERE key = k;
   IF config.is_loyalty_key(k) THEN PERFORM loyalty.leave_write(tok); ELSE PERFORM technical.leave_write(tok); END IF;
 END LOOP;
END;
$seed_v1_1_40$;
