-- =====================================================================================================
-- E7-71 / physical model v1.1.41 — the customer notification of a Ban extension (FR-TRS-22, arch 07 section 4;
-- Amr Heiba's delegated decision for E7-71, 18 Oct 2026; Sayed remains the Product Owner). For Product Owner review.
--
-- Before: the launch notification catalogue (Product v5.0 section 52.2) has TEMPORARY_BAN_ACTIVATED and BAN_EXPIRED
-- but no code for an extension, so a further qualification during an ACTIVE Ban (FR-TRS-22: the same instance, the
-- stage advanced, endsAt = MAX(currentEndsAt, now + newStageDuration)) could tell the customer nothing, or only by
-- misusing the activation code.
--
-- 1. One catalogue row, BAN_EXTENDED: a customer notification about the account (reference NONE, as the other two Ban
--    codes), In-App + Push required and allowed, as TEMPORARY_BAN_ACTIVATED.
--
-- Additive only: notification.notification_catalogue is append-only (tr_125_catalogue_append_only refuses UPDATE and
-- DELETE, not INSERT), the code is new, and no existing row, table, column, constraint, trigger, function or grant
-- changes. No backfill: no Ban extension can have happened before this migration (E7-71 adds the first producer).
-- Platform-lane table (Notification): needs its Code Owner's review.
-- =====================================================================================================
INSERT INTO notification.notification_catalogue(code,reference_type,recipient_types,required_channels,allowed_channels,otp_only,source_reference) VALUES
 ('BAN_EXTENDED','NONE',ARRAY['CUSTOMER']::text[],ARRAY['IN_APP','PUSH']::text[],ARRAY['IN_APP','PUSH']::text[],false,'FR-TRS-22; E7-71 delegated decision (Amr Heiba, 18 Oct 2026)');
