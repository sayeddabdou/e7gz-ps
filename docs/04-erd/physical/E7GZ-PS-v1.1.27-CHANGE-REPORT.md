# E7GZ PS Physical Model v1.1.27 — Build Baseline

**Status:** Ready for build · 26 September 2026
**Model:** `E7GZ-PS-Database-v1.1.27-REVIEW-FIXES.dbm` (pgModeler)
**Authority:** Product Specification v5.0 (PCR-001–003) › PRD v1.1 › FRS v1.1 › Architecture pack v1.1 › this model

## Files in this folder

| File | Purpose |
|---|---|
| `E7GZ-PS-Database-v1.1.27-REVIEW-FIXES.dbm` | The physical model — authority for every schema, table, column, constraint, index, trigger and function name |
| `V1__e7gz_ps_baseline_v1_1_27.sql` | Flyway `V1`: the complete schema exported from the model (includes every guard block) |
| `E7GZ-PS-security-baseline-v1.1.27.sql` | Roles `e7gz_app_runtime` / `e7gz_admin_runtime` and all grants; idempotent; re-run after every migration |
| `test_behaviour_v127.sh` | 86 behaviour checks of the database rules |
| `test_tournament_v127.sh` | 22 isolated checks of Tournament approval and Ban-boundary rules |
| `test_privileges_v127.sh` | 23 checks that the runtime roles can do exactly what they should |

## Build a database

```bash
createdb e7gz_ps
psql -d e7gz_ps -v ON_ERROR_STOP=1 -f V1__e7gz_ps_baseline_v1_1_27.sql
psql -d e7gz_ps -v ON_ERROR_STOP=1 -f E7GZ-PS-security-baseline-v1.1.27.sql
# tests (each needs its own fresh copy):  createdb t1 -T e7gz_ps && bash test_behaviour_v127.sh t1
```

The test scripts connect with `psql -h /tmp -p 5499 -U postgres`; change the `P=`/`S=` line for your environment.

## Shape

78 tables · 148 foreign keys (every one indexed) · 285 indexes · 241 CHECK constraints · 225 triggers · 18 module schemas.

## History

- **v1.1.0 – v1.1.25** — base model and hardening passes (inventory, operations, booking, attendance, tournament, trust, loyalty, platform).
- **v1.1.26** — PCR-001 exact Tournament participant cause; PCR-002 Customer Account Deletion request; PCR-003 Customer Review reporting.
- **v1.1.27** — review fixes against Product, FRS and Architecture:

| # | Defect | Fix | Source |
|---|---|---|---|
| F01 | New Users had no `user_commitment_guard` row, so every booking/deletion command failed | Row created automatically on User insert | NFR-01 |
| F02 | A Ban ending exactly at `startAt` restored the participant | Start-boundary checks use `ends_at >= startAt` | §37.11 |
| F03 | SECURITY DEFINER functions executable by any DB role | EXECUTE revoked from PUBLIC; entry points granted by the security baseline | 10 Security |
| F04 | Unlimited duplicate reports; reviews of deleted accounts reportable | One unresolved report per reporter per Review | PCR-003 |
| F05 | Maintenance/booking overlap check unsafe under REPEATABLE READ | Guard refuses REPEATABLE READ | I1 |
| F06 | O19 anonymisation unguarded; deleted account's Reviews stayed public | `iam.anonymise_user` producer; PII blanked; tokens revoked; Reviews removed; request completed; commit-time completeness check | PCR-002, §53.5 |
| F07 | Global Booking Fee / Tournament Creation Fee not configurable | Versioned config keys `booking.fee`, `tournament.creation_fee` (EGP 0); snapshots must equal the effective fee | §5.1, §5.3, FR-ADM-10 |
| F08 | Username 30-day cooldown not enforced | Cooldown guard; history recorded automatically, append-only | §7.5 |
| F09 | Area could be deactivated with ACTIVE/SUSPENDED Venues | Deactivation and assignment guards | FR-VEN-47 |
| F10 | OTP rules only in documents | `issued_at`; 5-min validity, 5 attempts, 60 s resend, 5/hour/destination, auto-supersede | §7.3 |
| F11 | Online intake rules not in DB | 15-min lead, +10-min response deadline, current session and not in its final 10 min, Area ACTIVE | §21–§23 |
| F12 | Referenced Operating Sessions editable; Owner Password secret deletable | Snapshot immutability once referenced; secret never deleted | §14.6, §10.2 |
| F13 | Player Tournament approval used a 24 h window, 25 h cutoff and reason-less REJECTED | `MIN(created+10 min, checkpoint)`; cutoff `startAt−2h`; Reject → `REJECTED/VENUE_REJECTED_TOURNAMENT`; timeout → `CANCELLED/VENUE_DID_NOT_APPROVE_TOURNAMENT_IN_TIME`; 5-min retry after either | FR-TRN-10, §36.3, §37.2–37.3 |
| F14 | Booking status could move anywhere | `booking.guard_booking_transition`: FRS §2.1 graph, reasons per source state, Customer cancel only before Start, No-show reversal rules, terminal immutability | FRS §2.1, §29 |
| F15 | Booking creation had no Venue eligibility / back-dating check | Venue Account ENABLED; Online needs Marketplace ACTIVE and starts PENDING; Manual never ONBOARDING, never back-dated | §22, §30.2, §30.5 |
| F16 | Access paths the architecture relies on were missing | Audit subject/correlation, business-date, response-due, reservation-history, OTP, venue-area, email indexes | 04 §8 |
| F17 | Marketplace status could return to ONBOARDING | Never back to ONBOARDING (Product Owner decision A9) | 01 §14 A9 |
| F18 | No store for Venue Dashboard Web Push; notification catalogue empty, so every notification was rejected | `notification.venue_push_subscription` (registered → seen → revoked, never deleted); 86 launch codes registered from Product §52 (Customer, Venue, Admin; SMS for OTP only). Device tokens are revoked, not deleted, so the runtime `DELETE` grant on `device_token` was removed | Product Owner decision A6, §52 |
| F19 | Login attempts had no place in the security rate-limit table | `iam.security_rate_limit_counter.limiter_type` also accepts `LOGIN` (key = hash of identifier + IP; `action_class` = audience) | Product Owner decision A10, arch 10 §7 |
| — | Unchecked columns | Non-negative prices; closed actor types; non-blank reasons; code-format checks | — |

- **v1.1.28** (29 Sep 2026, E7-12, approved by the Product Owner as option 1B) — stable identifiers on Product guard exceptions:

| # | Change | Where | Source |
|---|---|---|---|
| G01 | A guard that re-asserts a Product rule now carries a stable identifier in the standard PostgreSQL diagnostic field **CONSTRAINT** (`RAISE … USING CONSTRAINT = 'gd_…'`). The API maps the identifier — never the message — to its `ApiErrorCode` (`ps.e7gz.common.error.DatabaseErrorCodes`). Message text, SQLSTATE (`P0001`), signatures, `SECURITY DEFINER`, `search_path`, owner and grants are unchanged. No table, column, index, constraint, trigger or data change. | Flyway `V202609291200__guard_error_identifiers.sql`; `.dbm` block `v1_1_28_guard_error_identifiers` (identical text) | arch 09 §1.5, ADR-026 §4 |

14 functions are restated from their final v1.1.27 definition; 31 raise sites change. Internal invariant raises get no identifier and stay unexpected failures (HTTP 500 + correlation ID).

| Function | Lane | Identifier → API code (HTTP) | Kind |
|---|---|---|---|
| `booking.validate_online_booking_intake` | Engine | `gd_online_booking_lead_time` → `BOOKING_LEAD_TIME_TOO_SHORT` (422) · `gd_online_booking_intake_window` → `ONLINE_INTAKE_WINDOW_CLOSED` (422) · `gd_online_booking_area_inactive` → **unmapped by design** (see below) | diagnostic |
| `booking.validate_booking_creation_venue` | Engine | `gd_online_booking_venue_not_active` → `VENUE_NOT_ELIGIBLE_FOR_NEW_ACTIVITY` (422) · `gd_manual_booking_backdated` → `MANUAL_BOOKING_BACKDATING_NOT_ALLOWED` (422) | diagnostic + branch split |
| `inventory.guard_resource_reservation_cross_class` | Engine | by `reservation_type`: `gd_reservation_conflict_booking` → `SLOT_NO_LONGER_AVAILABLE` (409) · `_late_confirm` → `LATE_CONFIRM_RESOURCE_CONFLICT` (409) · `_tournament` → `TOURNAMENT_RESOURCE_CONFLICT` (409) · `_maintenance` → `MAINTENANCE_OVERLAPS_RESERVATION` (422) · `_closure` → **unmapped by design** (see below) | diagnostic |
| `attendance.validate_attendance_action_write` | Engine | `gd_check_in_window` → `CHECK_IN_WINDOW_CLOSED` · `gd_late_present_window` → `LATE_PRESENT_NOT_AVAILABLE` · `gd_no_show_window` → `NO_SHOW_NOT_AVAILABLE` (422) | diagnostic |
| `tournament.prepare_tournament_insert` | Engine | `gd_tournament_venue_not_active` → `VENUE_NOT_ELIGIBLE_FOR_NEW_ACTIVITY` (422) · `gd_tournament_venue_selling_block` → `VENUE_SELLING_BLOCK_ACTIVE` (422) · `gd_tournament_creation_cutoff` → `TOURNAMENT_CREATION_CUTOFF_PASSED` (422) · `gd_tournament_creator_deleted` → `ACCOUNT_DELETED` (403) · `gd_tournament_creator_unverified` → `PHONE_VERIFICATION_REQUIRED` (403) · `gd_tournament_creator_not_verified_player` → `VERIFIED_PLAYER_REQUIRED` (403) · `gd_tournament_creator_banned` → `ACCOUNT_BANNED_FOR_THIS_ACTION` (403) · `gd_tournament_retry_cooldown` → `TOURNAMENT_RETRY_COOLDOWN_ACTIVE` (422) | diagnostic + branch split |
| `tournament.guard_tournament_participant` | Engine | `gd_tournament_capacity_full` (both capacity checks) → `TOURNAMENT_CAPACITY_REACHED` (422) | diagnostic |
| `iam.guard_user_account_update` | Platform | `gd_username_cooldown` → `USERNAME_COOLDOWN_ACTIVE` (422) | diagnostic |
| `iam.guard_otp_challenge` | Platform | `gd_otp_resend_too_soon` → `OTP_RESEND_TOO_SOON` (429) · `gd_otp_hourly_limit` → `OTP_HOURLY_LIMIT_REACHED` (429) | diagnostic |
| `geo.guard_area_deactivation` | Platform | `gd_area_has_active_venues` → `AREA_HAS_ACTIVE_VENUES` (422) | diagnostic |
| `venue.guard_venue_area` | Platform | `gd_venue_area_inactive_assignment`, `gd_venue_area_inactive_status` → `AREA_INACTIVE` (422) | diagnostic |
| `operations.guard_maintenance_window` | Platform | `gd_maintenance_overlaps_commitment` → `MAINTENANCE_OVERLAPS_RESERVATION` (422) · `gd_maintenance_owner_password_required` (both checks) → `OWNER_PASSWORD_REQUIRED` (423) | diagnostic |
| `review.create_review` | Platform | `gd_review_author_deleted` → `ACCOUNT_DELETED` (403) · `gd_review_author_unverified` → `PHONE_VERIFICATION_REQUIRED` (403) · `gd_review_already_exists` → `REVIEW_ALREADY_EXISTS` (422) · `gd_review_cooldown` → `REVIEW_COOLDOWN_ACTIVE` (422) | diagnostic + branch split |
| `review.report_review` | Platform | `gd_review_report_open` → `REVIEW_REPORT_ALREADY_OPEN` (422) | diagnostic |
| `technical.idempotency_begin` | Platform | `gd_idempotency_key_reused` → `IDEMPOTENCY_KEY_REUSED` (422) | diagnostic |

**Branch splits** (four combined conditions; same place, same conditions, same message, same accepted/rejected writes under three-valued logic): the defect part (row not found, server timestamp missing or in the future) keeps raising **without** an identifier; only the Product-rule part carries one. Where a deleted and an unverified account overlap, *deleted* is checked first — FR-ACC-10 / arch 09 `ACCOUNT_DELETED` applies to any command against an anonymised account.

**Left combined on purpose:** `iam.guard_otp_challenge` "OTP is no longer usable (expired, superseded, consumed or out of attempts)" has no identifier. A consumed or superseded code has no documented `ApiErrorCode`, and the documents set no precedence between expiry and attempts; the verification flow (E7-13) decides the code from the challenge row it has locked. Exclusion/unique constraints already report their names and need no change: `ex_no_double_booking` → `SLOT_NO_LONGER_AVAILABLE`, `ex_customer_no_overlap` → `OVERLAPPING_COMMITMENT`, `ux_user_username_normalized` → `USERNAME_TAKEN`.

**Unmapped by design** (identified, but an HTTP 500 + correlation ID + error log): these two guards cannot be reached by a valid user action, only by a defect in the application decision that runs first under the command's locks; proven against the database in `GuardErrorIdentifiersTest` ("reachability").
- `gd_online_booking_area_inactive`: an inactive Area never holds an ACTIVE Venue, even when activation and deactivation race (FR-VEN-47). The Online Booking command locks its Venue and checks FR-BOK-11 condition 5 (Venue ACTIVE) before condition 7 (Area ACTIVE, arch 06 §1.3). A booking that passes condition 5 never meets an inactive Area; one that fails it is refused as `VENUE_NOT_ELIGIBLE_FOR_NEW_ACTIVITY` first.
- `gd_reservation_conflict_closure`: a Closure save locks its Venue and every Resource before it writes CLOSURE rows, so a racing commitment waits and loses as `SLOT_NO_LONGER_AVAILABLE`. A commitment already present must be cancelled (FR-VEN-30), abort the save (`AFFECTED_SET_CHANGED_REFRESH_PREVIEW`, FR-VEN-26) or block it (`TOURNAMENT_CONFLICT_BLOCKS_SAVE`, FR-VEN-32) before any CLOSURE row exists; the identifier cannot tell which was skipped, so no single code is true.

**Review:** the six Engine-lane functions need the Engine owner's review before merge (CLAUDE.md §6).

### v1.1.29 — admin tables closed to the public API's role (E7-23, 2 Oct 2026)

**Reviewed and approved by the Product Owner, 2 Oct 2026:** the grant change, the trigger change (approved for the Engine lane as well), and keeping the grants in the security baseline instead of a versioned migration.

Sprint 0 review S-6, Product Owner decision 2 Oct 2026. Two parts:

1. **Grants** in `E7GZ-PS-security-baseline-v1.1.27.sql` and its Flyway copy `R__security_baseline.sql`. The `.dbm` holds no grants, and the baseline re-applies after every migration, so the revokes live there (a versioned migration would be undone by the next baseline run).
2. **One function attribute**, `.dbm` block `v1_1_29_admin_alert_trigger_security_definer` = Flyway `V202610021800__admin_alert_trigger_security_definer.sql`: `operations.raise_resource_emergency_tournament_abuse_alert()` becomes SECURITY DEFINER with `search_path = pg_catalog, pg_temp`. Body, signature, owner, grants and trigger unchanged (every name in the body is schema-qualified). Engine-lane trigger: Engine owner's review.

| Table (owner: Platform Administration, arch 03 §1) | `e7gz_app_runtime` before | after |
|---|---|---|
| `iam.admin_account` | SELECT, INSERT, UPDATE | SELECT on `(id, is_active)` only |
| `admin.admin_alert` | SELECT, INSERT, UPDATE | SELECT |

- The kept column grant is what the SECURITY INVOKER triggers read to check an ADMIN actor (`… NOT EXISTS (SELECT 1 FROM iam.admin_account WHERE id = …)`); every function that reads other columns or locks admin rows (`FOR SHARE`) is SECURITY DEFINER.
- Admin alerts are now written only by SECURITY DEFINER functions: the O11 abuse-signal trigger (part 2) and `admin.acknowledge_alert()` (admin role only).
- `e7gz_admin_runtime` is unchanged. Admin login and the per-request active-admin check now read on that connection (`AdminLoginAccounts`); the first Platform Admin is created by the audited one-off `admin-bootstrap` profile.
- Tests: five new checks in `test_privileges_v127.sh`; `AdminAccountPrivilegesTest` (including the trigger now running as its owner) and the `.dbm`/migration identity check in `MigrationFilesTest`.
### v1.1.30 — venue creation time and unique area names (E7-23 PR 2, 2 Oct 2026)

Product Owner decisions from the E7-23 PR 2 review. Modelled in the `.dbm` (column on `venue.venue`, two index objects); Flyway `V202610022000__venue_created_at_area_name_unique.sql`. **For Product Owner review.**

| Object | Change | Why |
|---|---|---|
| `venue.venue.created_at` | new `timestamptz NOT NULL DEFAULT clock_timestamp()`; existing rows get the migration instant; the create command writes the command instant | the admin venue list is ordered newest first, then id, so a rename cannot move a venue between cursor pages |
| `ix_venue_created_at` | btree `(created_at, id)` | that list order |
| `ux_area_name_normalized` | unique on `lower(btrim(name))` | two areas may not share a name (trimmed, case-insensitive); the API answers `AREA_NAME_TAKEN` |

No trigger, function or grant change: the baseline's table-level grants cover the column, and no trigger on these tables compares whole rows.

### v1.1.31 — venue photo life cycle (E7-23 PR 3, 3 Oct 2026)

PCR-005 and the Product Owner decisions of 2 and 3 Oct 2026 (including `processed_at`, approved 3 Oct 2026). Modelled in the `.dbm` (four columns and a check on `venue.venue_image`, one index object); Flyway `V202610031200__venue_image_upload_and_removal.sql`. **Reviewed and approved by the Product Owner, 3 Oct 2026**, including `created_at`.

| Object | Change | Why |
|---|---|---|
| `venue.venue_image.created_at` | new `timestamptz NOT NULL DEFAULT clock_timestamp()` | when the upload started; the nightly clean-up hides uploads not confirmed within 24 hours |
| `venue.venue_image.uploaded_at` | new, nullable | confirm checked the stored file; the photo is visible from here (PCR-005) |
| `venue.venue_image.processed_at` | new, nullable | the small and medium copies without GPS/EXIF exist; customers are shown only these |
| `venue.venue_image.removed_at` | new, nullable | removal hides the photo; the row and the original are kept (PCR-005) |
| `ck_venue_image_life_cycle` | `processed_at` needs `uploaded_at`; a confirmed photo has a `sort_order` | the copies are made only from a confirmed photo, and every visible photo has a place in the order |
| `ix_venue_image_visible` | btree `(venue_id, sort_order)` where confirmed and not removed | the photo lists |

No trigger, function or grant change: the baseline's table-level grants cover the columns. `created_at` is added beyond PCR-005's list because the 24-hour clean-up (PO decision 3 Oct 2026) needs the upload start time.

### v1.1.32 — Emergency selling-block guard fix (E7-30, 3 Oct 2026)

Product Owner decision 3 Oct 2026 (Sayed), from the E7-30 review. One function, `.dbm` block `v1_1_32_emergency_selling_block_guard_fix` = Flyway `V202610031800__fix_emergency_selling_block_guard.sql`. **For Product Owner review.**

**What was fixed.** `operations.guard_emergency_selling_block()` is the BEFORE INSERT/UPDATE/DELETE trigger function of both `venue.venue_selling_block` and `resource.resource_selling_block`. Its UPDATE branch tested `TG_TABLE_SCHEMA = 'venue' AND NEW.venue_id IS DISTINCT FROM OLD.venue_id` and `TG_TABLE_SCHEMA = 'resource' AND NEW.resource_id IS DISTINCT FROM OLD.resource_id`. The fix scopes every table-specific field access explicitly by its table: each `NEW`/`OLD.venue_id` sits inside `IF TG_TABLE_SCHEMA = 'venue' THEN`, each `NEW`/`OLD.resource_id` inside `IF TG_TABLE_SCHEMA = 'resource' THEN` (the Emergency scope check's `ELSE` becomes an explicit resource `IF`, and the two subject-immutability tests are nested). No business rule or error message changed.

**Why it was necessary.** PL/pgSQL resolves every record field of an expression before evaluating it, so the `AND` did not protect the other table's column: on the venue table `NEW.resource_id`, and on the resource table `NEW.venue_id`, failed with SQLSTATE `42703` (`record "new" has no field …`). No Emergency-linked selling block could be updated, and since `assert_emergency_block_consistency` requires an ENDED or CONVERTED Emergency's block to close at the same instant, no Emergency could be ended or converted.

| Object | Change |
|---|---|
| `operations.guard_emergency_selling_block()` | `CREATE OR REPLACE` with every table-specific field access scoped by its table (Emergency scope check and subject-immutability tests); every rule and error message unchanged |

The fix is limited to `operations.guard_emergency_selling_block()`. Signature, language, SECURITY INVOKER, owner and grants are unchanged; both triggers (`tr_guard_venue_emergency_selling_block`, `tr_guard_resource_emergency_selling_block`) are not recreated and keep pointing at the function. No table, column, index, constraint, trigger or data change. Engine-lane function (Operations): needs the Engine owner's review.

- Tests: `EmergencySellingBlockGuardTest` (application role, every trigger on; the DELETE trigger refusal also as the table owner) — an Emergency-linked venue and resource block close with their Emergency; a closed block cannot reopen; subject, start and active-with-end rules unchanged on both tables; DELETE refused; a plain block still opens and closes. The `.dbm`/migration identity check in `MigrationFilesTest`.

### v1.1.33 — scheduled_job outcomes and correlation ID (E7-40, 9 Oct 2026)

Product Owner decisions 4 Oct 2026 (E7-32 review) and 9 Oct 2026 (one moving row per frequent system job), built with the deadline engine (E7-40). Modelled in the `.dbm` (one column and the table comment on `technical.scheduled_job`, block `v1_1_33_scheduled_job_finish_and_correlation` for the two functions); Flyway `V202610091200__scheduled_job_finish_and_correlation.sql` = the column, its backfill and comments, then that block byte for byte. **For Product Owner review.**

| Object | Change | Why |
|---|---|---|
| `technical.scheduled_job.correlation_id` | new `uuid NOT NULL`; existing rows take their own `id` | the worker opens the job's correlation ID around every attempt, so every retry logs under the ID of the command that created the job (NFR-32, arch 11 §5.1); a nightly system job uses its own id |
| `technical.job_finish(uuid,text,text,text,timestamptz,boolean)` | replaces the four-argument function (dropped first, so no call is ambiguous): also `SUPERSEDED` (sets `completed_at`), `PENDING` with an optional later due time `p_due_at`, and `p_next_run` (attempt back to 0) | ADR-006 / arch 08 §2.1: a fired job that is no longer applicable is marked `SUPERSEDED` in the same transaction; a job that fired before its authoritative deadline, or must back off after an error, is sent back to `PENDING` later. `p_due_at` goes with `PENDING` only and must be later than the current `due_at`. `p_next_run` (PO decision 9 Oct 2026): a frequent system job (photo copies every 30 s, Nearest Availability refresh every minute) keeps one row that moves to its next run, instead of one new row per run (about 4,300 rows a day), so nothing piles up and nothing is ever deleted; only with `PENDING` and a later `p_due_at` |
| `technical.guard_scheduled_job()` | `correlation_id` immutable like the rest of the job's identity; `due_at` may change on `LEASED → PENDING`, and `attempt` go back to 0 on a later next run, only inside `job_finish` (its private write token, the Outbox's pattern); every other rule unchanged | a job's status, due time and attempt count still never change through a plain `UPDATE` |
| table comment | names the new `job_finish` path and the immutable `correlation_id` | |

**Backfill.** The lifecycle guards refuse any `UPDATE` of a `DONE` or `SUPERSEDED` job and a `PENDING → PENDING` rewrite, so the migration disables the three row triggers (`tr_125_scheduled_job_guard`, `tr_guard_tournament_scheduled_job`, `tr_tournament_job_final_state`) for the one backfill statement and enables them again, inside its own transaction; it needs only the table owner.

Unchanged on purpose: every other column, constraint, index and trigger; `job_lease` and `job_rearm`; the Tournament guards on top (a Tournament job's `due_at` stays immutable, so a Tournament job is never moved by `job_finish`). Grants: PUBLIC is revoked in the migration; the security baseline grants `EXECUTE` on the new `job_finish` to both runtime roles after every migration, like every other technical function.

- Tests: `ScheduledJobLifecycleTest` (application role, every trigger on) — `SUPERSEDED`, moved-later `PENDING` and a next run (attempt back to 0) through `job_finish`, an earlier or non-`PENDING` due time and a next run without a due time refused, a plain `UPDATE` of `due_at`, `attempt` or `correlation_id` refused, only the lease holder finishes; `MigrationSafetyTest` with `upgrade-seed/V1.sql` (`DONE`, `SUPERSEDED`, `FAILED`, `LEASED` and `PENDING` jobs before the backfill) and `upgrade-seed/V202610091200.sql`; the `.dbm`/migration identity check in `MigrationFilesTest`.

### v1.1.34 — PCR-007: a venue cancels an ACTIVE Manual Booking (E7-48, 10 Oct 2026)

Product Owner decision 10 Oct 2026 (Sayed), E7-48 Part A2; PCR-007 — Cancel an ACTIVE Manual Booking. `.dbm` block `v1_1_34_manual_booking_active_cancel` = Flyway `V202610102240__manual_booking_active_cancel.sql`, byte for byte. **For Product Owner review.**

**Why.** PCR-007 lets venue staff cancel a Manual Booking that is CONFIRMED or ACTIVE, without the Owner Password, reason `VENUE_CANCELLED_MANUAL_BOOKING`. `booking.guard_booking_transition` (F14) refused ACTIVE → CANCELLED with that reason.

| Object | Change |
|---|---|
| `booking.guard_booking_transition()` | The V1 definition copied verbatim, with one branch widened: ACTIVE → CANCELLED also accepts `VENUE_CANCELLED_MANUAL_BOOKING` when `source = 'MANUAL'`. Every other transition, reason list, time rule and message unchanged; `tr_03_booking_transition_guard` is not recreated |
| `booking.assert_manual_active_cancel_provenance()` (new) + `tr_booking_manual_active_cancel_provenance` (new constraint trigger, `AFTER UPDATE OF status`, `DEFERRABLE INITIALLY DEFERRED`, `WHEN` the booking is MANUAL and goes ACTIVE → CANCELLED / `VENUE_CANCELLED_MANUAL_BOOKING`) | At commit: exactly one ACTIVE → CANCELLED status-transition row in `booking.booking_history` for the booking, and it carries the same reason, `occurred_at` = the booking's `terminal_at`, `actor_type` VENUE and an `actor_id` that is an `iam.venue_account` of the booking's own venue. Missing, duplicate or mismatched rows and any other actor (ADMIN, SYSTEM, NULL, another venue's account) roll the command back. The guard cannot do this itself: it runs before the update, and the history row is written after it (same commit-time pattern as the attendance and Ban-cascade history checks) |

**Security limitation.** The check verifies the *recorded* actor provenance, not an independently authenticated identity: the venue API and the worker share `e7gz_app_runtime` and the database never sees the venue's access token, so application code that wrote a false VENUE history row would pass. The API layer stays the authority for "this is the signed-in venue"; the database guarantees that the cancellation and its history agree, with a real account of that venue. No session marker is introduced; same assurance level as the attendance VENUE checks.

Unchanged on purpose: every table, column, index, constraint, grant and data row; `begin_command` and every other trigger. PUBLIC is revoked on the new function in the migration; the security baseline grants it after every migration like every booking function. Its refusal carries no guard identifier (an internal invariant: HTTP 500 if the application ever reaches it). No upgrade seed: no column is added, and the V1 seed already holds a CONFIRMED Manual Booking. Engine-lane functions (Booking): need the Engine owner's review.

- Tests: `ManualActiveCancelProvenanceTest` (application role, every trigger on; the cancellation committed through `CommandRunner`) — a VENUE actor of the booking's venue commits and the reservation is released at the command instant; ADMIN, SYSTEM, NULL actor, another venue's account, no history row, two history rows, another reason and another `occurred_at` are refused at commit and change nothing; the full transition matrix (every from × to × reason × source) gives the V1 guard's answers except ACTIVE → CANCELLED / `VENUE_CANCELLED_MANUAL_BOOKING` / MANUAL; an ONLINE booking stays refused. `MigrationSafetyTest` (the migration on the seeded database); the `.dbm`/migration identity check in `MigrationFilesTest`.

### v1.1.35 — a Late Confirm may wait on the customer despite a schedule conflict (E7-58, 13 Oct 2026)

Product Owner decision D1, 12 Oct 2026 (Sayed), E7-58 discovery; FR-LCF-15, FR-LCF-16. `.dbm` block `v1_1_35_late_confirm_awaiting_overlap` = Flyway `V202610131300__late_confirm_awaiting_overlap.sql`, byte for byte. **For Product Owner review.**

**Why.** FR-LCF-15/16: the venue may always send a Late Confirm, and the customer's schedule conflict is checked only when the customer accepts. `booking.derive_commitment_period` gives an AWAITING_CUSTOMER_CONFIRMATION booking its `commitment_period`, so V1's `ex_customer_no_overlap` refused VENUE_RESPONSE_EXPIRED → AWAITING (23P01) whenever the interval overlapped another counted commitment of the customer.

| Object | Change |
|---|---|
| `ex_customer_no_overlap` on `booking.booking` | Dropped and re-added under the same name, same columns, operators and method (`gist`: `customer_id WITH =`, `commitment_period WITH &&`); predicate `commitment_period IS NOT NULL` → `commitment_period IS NOT NULL AND status <> 'AWAITING_CUSTOMER_CONFIRMATION'`. A constraint comment records the rule |

An AWAITING booking may now overlap another counted commitment of the same customer. Every other pair of counted commitments is refused as before, including AWAITING → CONFIRMED (an Accept puts the row back under the predicate). The narrower predicate only removes rows from the constraint, so existing data always satisfies it; no backfill.

**Moved to the application** (under the `iam.user_commitment_guard` lock that `tournament.begin_command` takes for the customer): FR-BOK-04 (O1 condition 4) — a new Online Booking overlapping an AWAITING one is still refused; the creation predicate already checks every counted commitment, AWAITING included (`OnlineCreationPredicate`, unchanged). FR-LCF-15 — Accept checks the overlap itself and records `LATE_CONFIRM_FAILED_CUSTOMER_CONFLICT` (E7-58, implemented in this same change).

Unchanged on purpose: `booking.derive_commitment_period` and `tr_00_booking_commitment_period` (AWAITING keeps its `commitment_period`), `ck_booking_commitment_period_shape`, the 4-commitment count (FR-BOK-03, FR-LCF-05), the 23P01 → `OVERLAPPING_COMMITMENT` mapping, and every other table, column, index, constraint, trigger, function, grant and data row. No upgrade seed: no column is added. Engine-lane constraint (Booking): needs the Engine owner's review.

- Tests: `LateConfirmAwaitingOverlapTest` (migrated database, application commands through `CommandRunner`) — an AWAITING booking coexists with an overlapping counted commitment of the same customer and keeps its `commitment_period`; it still counts towards the 4-commitment limit; a new Online Booking overlapping it is refused `OVERLAPPING_COMMITMENT` by the creation predicate (and the database alone would accept it); back-to-back stays allowed; two CONFIRMED bookings, and an AWAITING → CONFIRMED move, that overlap are still refused by the constraint; concurrent creations against an AWAITING booking all fail. `MigrationSafetyTest` (the migration on the seeded database); the `.dbm`/migration identity check in `MigrationFilesTest`.

### v1.1.36 — admin.raise_alert, the one producer of Admin Alerts (E7-56, 15 Oct 2026)

Product Owner decisions 4 Oct 2026 (E7-32 review) and 15 Oct 2026 (E7-56); NFR-29, arch 11 §5.4. `.dbm` block `v1_1_36_admin_raise_alert` = Flyway `V202610150400__admin_raise_alert.sql`, byte for byte. **For Product Owner review.**

**Why.** An alert must be able to commit in the same transaction as the command that found the problem (for example `SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR` at startAt, E7-60), on either runtime connection, but `e7gz_app_runtime` has no INSERT or UPDATE on `admin.admin_alert` (v1.1.29).

| Object | Change |
|---|---|
| `admin.raise_alert(p_kind, p_severity, p_subject_type, p_subject_id, p_payload, p_correlation_id) → uuid` | New. `SECURITY DEFINER`, `search_path = pg_catalog, pg_temp`; inserts one open alert and returns its id. EXECUTE for both runtime roles through the security baseline (neither internal nor admin-only); none for PUBLIC |

Every rule stays with the table: `admin.guard_admin_alert` refuses a pre-acknowledged row, a future `created_at` and a credential/secret payload key; `ck_admin_alert_values` refuses a blank kind, severity or subject type and a non-object payload. `kind` and `severity` stay open text: `SEV_1`/`SEV_2`/`SEV_3` and the existing `RISK` abuse signal are all valid. A new function only: every existing row and object is unaffected; no backfill.

Unchanged on purpose: `admin.admin_alert` (columns, constraints, indexes, triggers, grants - still no INSERT or UPDATE for `e7gz_app_runtime`), `admin.acknowledge_alert`, every other object and data row.

- Tests: `AdminAlertsIntegrationTest` (both runtime roles raise through the producer; a direct INSERT by `e7gz_app_runtime` is still refused; a credential payload key is refused), `MigrationSafetyTest` (the migration on the seeded database), the `.dbm`/migration identity check in `MigrationFilesTest`.

### No schema change — values written by E7-57 (16 Oct 2026)

E7-57 adds no migration; it writes new values in existing open-text columns, recorded here as the task asks (Sprint 0 review S-3 and S-7; PO decisions 2 and 16 Oct 2026).

| Column | New values | Meaning |
|---|---|---|
| `iam.security_rate_limit_counter.action_class` (with `limiter_type = LOGIN`) | `CUSTOMER_ACCOUNT`, `VENUE_ACCOUNT`, `ADMIN_ACCOUNT` | The per-account login limit: `subject_key_hash` = SHA-256 of the account id; 50 failures in 1 hour set `cooldown_until` = now + 15 minutes. The existing (identifier + IP) rows keep `CUSTOMER` / `VENUE` / `ADMIN`. |
| `iam.user_account.phone_e164` | `released: || id` | An account that never verified its phone, released when a new registration or a verified phone change needed its phone or username. As anonymisation, a placeholder that is never a phone. Its `username` becomes `released_<id>` (unless inside its 30-day username cooldown), and `full_name`, `email` and `password_hash` are emptied; `anonymised_at` stays NULL. |

### v1.1.37 — a superseded referenced Operating Session beside its day's current session (E7-113, 17 Oct 2026)

Amr's engineering decision for E7-113, 17 Oct 2026 (not yet a Product Owner decision); FR-VEN-22, FR-VEN-23 step 8, FR-VEN-24, FR-BOK-05, FR-BOK-08/09, FR-MAN-03. `.dbm` block `v1_1_37_operating_session_supersession` = Flyway `V202610171400__operating_session_supersession.sql`, byte for byte; the `operating_session` table model gains `superseded_at` and `ck_operating_session_superseded_after_generation`, and `uq_operating_session_day` is modelled as a partial unique index. **For Product Owner review.**

**Why.** A session a booking references is a historical snapshot that never changes (`venue.guard_operating_session`). With one session per venue and operating day (`uq_operating_session_day`) and no two overlapping sessions (`ex_session_no_overlap`), an hours change left such a day with its OLD session: no session for the new hours could exist, so new Online bookings inside the new hours were refused; and a Manual walk-in inside the old window had to snapshot the old session, so a later unrelated hours change counted it as affected and would have cancelled it.

| Object | Change |
|---|---|
| `venue.operating_session.superseded_at` | New, nullable. Set once, at the hours change that stops generating the session, on a referenced session only; NULL = the current session of its day. Column comment |
| `ck_operating_session_superseded_after_generation` | New check: `superseded_at IS NULL OR superseded_at >= generated_at` |
| `uq_operating_session_day` | Constraint dropped; partial unique index under the same name on `(venue_id, operating_day) WHERE superseded_at IS NULL` |
| `ex_session_no_overlap` | Dropped and re-added under the same name, columns, operators and method (`gist`: `venue_id WITH =`, `session_period WITH &&`); predicate `superseded_at IS NULL`. Constraint comment |
| `venue.guard_operating_session()` | V1 definition plus: a superseded session never changes again; only a referenced session can be superseded |
| `booking.validate_booking_insert_snapshot()` | V1 definition plus: a new booking never snapshots a superseded session; a Manual Booking is inside a published session only when a CURRENT session contains it |
| `booking.validate_online_booking_intake()` | v1.1.28 definition plus: the intake window is read from current sessions only |
| `operations.guard_emergency_lifecycle()` | V1 definition plus: an Emergency's current session is a current session |

**No backfill.** `superseded_at` starts NULL on every existing row. A pinned row written before this change cannot be told safely from a referenced row that still matches the hours by SQL alone (before E7-113 such a row was not restamped), so none is guessed. The application's next regeneration of the venue (the nightly horizon extension or the next hours change) supersedes a pinned row through the guarded path and creates the current session; until then such a row behaves as before. The narrower uniqueness and exclusion only remove rows from the constraints, so existing data satisfies them.

**Application (same change).** `SessionPlan` supersedes a referenced future session the hours no longer generate and inserts the day's current session, in the hours change's or the nightly run's own transaction; started sessions are left as they are. Every reader of a venue's sessions (intake, Late Confirm, Find Available, Nearest Availability, open now, business dates, the schedule, the regeneration itself) reads current sessions only; a booking keeps reading its own historical session by `operating_session_id`.

Unchanged on purpose: every other table, column, constraint, trigger and grant; booking reads by `operating_session_id`; `tournament.assert_tournament_aggregate` (reads its tournament's session by id); the guard identifier mapping (`ex_session_no_overlap` keeps its name). Upgrade seeds: `upgrade-seed/V202610131300.sql` (sessions and a referenced booking as they exist before the migration) and `upgrade-seed/V202610171400.sql` (a superseded session beside its day's current one, with a booking on each). Platform-lane table (Venue) with Engine-lane booking guards: needs both owners' review.

- Tests: `MigrationSafetyTest` (the migration on the seeded database; both seeds); the `.dbm`/migration identity check in `MigrationFilesTest`; `SessionPlanTest`; `OpeningHoursChangeIntegrationTest` (a closed day and a shortened day after an hours change: the historical row and its bookings stay, the current session is created, Online intake inside the new hours succeeds and outside it is refused, availability readers agree, a Manual walk-in in the old window has no snapshot and is not cancelled by a later unrelated hours change); `OperatingSessionSupersessionDbTest` (the database guards: supersession once, only on referenced rows, superseded history never snapshotted, current-only uniqueness and overlap).

### v1.1.38 — the Booking Fee receipt is deferred until the payment gateway (E7-66, 23 Oct 2026)

**Product Owner decision, 23 Oct 2026 (Sayed):** solve the mismatch and deactivate `loyalty.record_fee_receipt` until payment collection ships (ADR-018). Grants only, in `E7GZ-PS-security-baseline-v1.1.27.sql` and its Flyway copy `R__security_baseline.sql`, as v1.1.29: the `.dbm` holds no grants, and the baseline re-applies after every migration. No versioned migration.

**Why.** The function's own comment says it is "granted ONLY to the authenticated settlement adapter role", but no such role exists and the baseline granted it to both runtime roles like any other producer. A receipt is what turns a refund decision into Wallet money (`loyalty.settle_refund` credits only a collected fee), so whoever can record one can create Wallet credit. Nothing calls it at launch: the Booking Fee is EGP 0, the `PaymentProvider` port has no implementation and no caller, and the function itself refuses while `loyalty.wallet_refunds_enabled` is false.

| Function | `e7gz_app_runtime` before | after | `e7gz_admin_runtime` before | after |
|---|---|---|---|---|
| `loyalty.record_fee_receipt(uuid, numeric, text, text, timestamptz)` | EXECUTE | none | EXECUTE | none |

The baseline gains a `deferred` list, handled like `internal` (EXECUTE revoked from PUBLIC and from both runtime roles). When payments ship, that task creates the payment gateway's settlement-adapter role, grants it this function, and removes it from `deferred`. Body, signature, owner and every other grant unchanged; `record_refund` and `settle_refund` stay callable by both runtime roles (`settle_refund` credits nothing without a receipt).

- Tests: two new checks in `test_privileges_v127.sh`; `FeeReceiptPrivilegesTest` (neither runtime role may execute it, the other fee producers still may); `BookingFeeNonZeroIntegrationTest` records the receipt on the owner connection, as the future adapter role will; the copy check in `MigrationFilesTest`.

### v1.1.39 — the Wallet pays a future Booking's Booking Fee (E7-66 PR 3, 24 Oct 2026)

**Product Owner decision, 24 Oct 2026 (Sayed), PCR-009** (amends PCR-004 "no payment with the wallet", FR-PTS-10, FR-PTS-11): a refund of a collected Booking Fee is credited to the in-app Wallet, never back to the card, and the customer spends it on the fee of a later booking. Automatic: when a new Online Booking's stored fee is above zero and the Wallet holds at least that amount, the command that creates the Booking pays the whole fee from the Wallet; otherwise nothing is paid and the fee stays uncollected until a payment provider exists (ADR-018). Never a partial payment, a top-up, a withdrawal or the venue gaming charge. `.dbm` block `v1_1_39_wallet_booking_fee_payment` = Flyway `V202610241200__wallet_booking_fee_payment.sql`, byte for byte; the `wallet_transaction` table model has the nullable `refund_id` and the new check. **For Product Owner review.**

| Object | Change |
|---|---|
| `loyalty.wallet_transaction.refund_id` | Now nullable: set on a `BOOKING_FEE_REFUND` credit, NULL on a `BOOKING_FEE_PAYMENT` debit. Column comment |
| `ck_wallet_tx_launch_source` → `ck_wallet_tx_source` | Same arithmetic (`balance_after = balance_before + amount`, no NaN) and either a refund credit (`amount > 0`, `refund_id` set) or a fee payment (`amount < 0`, no `refund_id`, `balance_after >= 0`) |
| `loyalty.pay_fee_from_wallet(p_booking) → uuid` | New, `SECURITY DEFINER`, the one producer of a Wallet payment. In the Loyalty context of the command that creates the Booking (PENDING, `created_at` = the command instant): pays the stored fee when the Wallet covers it, writing the Booking's collection receipt (provider `WALLET`, reference = the Wallet transaction), the debit, the balance, two audits (`BOOKING_FEE_COLLECTION_RECORDED`, `BOOKING_FEE_WALLET_PAYMENT`) and `LOYALTY_WALLET_LEDGER_COMMITTED`. Returns NULL for a FREE booking or an uncovered fee; a second call answers the same payment. EXECUTE for both runtime roles through the security baseline |
| `loyalty.assert_user(p_user)` | V1 definition plus one block in the Wallet loop: a `BOOKING_FEE_PAYMENT` row is bound to its `WALLET` receipt for the same Booking and amount, its audit and its outbox event |
| `loyalty.guard_audit_origin()` | V1 definition plus `BOOKING_FEE_WALLET_PAYMENT` as a private-producer action |

**Refund of a Wallet-paid fee.** The `WALLET` receipt is an ordinary collection receipt, so `loyalty.record_refund` → `loyalty.settle_refund` credit the Wallet back exactly as for a card-collected fee: full refund for operational causes, the 15-minute customer policy (FR-PTS-14), nothing for a late customer cancellation. Unchanged: `settle_refund`, `record_refund`, `record_fee_receipt` (still deferred, v1.1.38), `refund_basis`, `assert_booking`.

**Existing data.** Every existing Wallet row is a refund credit with its `refund_id`, so it satisfies the new check; no backfill. No upgrade seed: no column is added, and a Wallet payment needs a collected fee, which no launch database has.

- Tests: `WalletBookingFeePaymentIntegrationTest` (pay at creation, refund back to the Wallet, late cancellation keeps the fee, uncovered and FREE fees pay nothing, only the creating command may pay); `MigrationSafetyTest` (the migration on the seeded database); the `.dbm`/migration identity and added-lines checks in `MigrationFilesTest`; `ClosedValueContract` (two causes).

### v1.1.40 — Points pay the Booking Fee; the Booking Fee is per booking or per hour (E7-68, 25 Oct 2026)

**Product Owner decisions, 25 Oct 2026 (Sayed), PCR-010** (amends FR-PTS-12, FR-PTS-13, FR-ADM-10 and PCR-009): the Platform Admin charges the Booking Fee per booking or per hour (by the half hour); Points pay it directly - one fee unit (`loyalty.points_per_fee_unit`, 10,000 at launch, even) pays one hour of fee in PER_HOUR mode (a half hour costs half) or the whole fee in PER_BOOKING mode (fewer Points pay nothing) - automatically, before the Wallet, which then pays all of the rest or nothing; a refund gives back exactly the Points used. Nothing changes at the launch values (PER_BOOKING, EGP 0). `.dbm` block `v1_1_40_points_pay_booking_fee` = Flyway `V202610251200__points_pay_booking_fee.sql`, byte for byte; the `.dbm` models the new table `booking_fee_points_payment`, the refund's `points_transaction_id` and `ck_fee_refund_points`, the two Points causes, the indexes, foreign keys and relationships under the migration's names. **Reviewed and approved by the Product Owner, 30 Oct 2026 (Sayed), including the widened `loyalty.configure` rule below.**

| Object | Change |
|---|---|
| `config.configuration_key` | Three keys: `booking.fee_mode` (TEXT, `PER_BOOKING` · `PER_HOUR`), `booking.fee_per_hour` (NUMERIC from 0, EGP), `loyalty.points_per_fee_unit` (INTEGER 2 … 2147483646, points). Each gets its `-infinity` technical bootstrap version at the launch value (`PER_BOOKING`, 0, 10000) |
| `config.effective_text(key, at)`, `config.booking_fee_for(minutes, at)` | New. `booking_fee_for` is the one definition of a booking's fee: the per-booking fee, or the per-hour fee × nominal minutes ÷ 60, half-up to 2 places |
| `booking.validate_online_booking_intake()` | v1.1.37 definition; the fee check compares the snapshot with `config.booking_fee_for(nominal_booked_minutes, created_at)` (same message) |
| `config.is_loyalty_key`, `loyalty.config_write_guard`, `loyalty.configuration_at` | The Points per fee unit is a Loyalty key |
| `loyalty.configure(...)` | v1.1.25 definition plus the new key, an odd or below-2 Points per fee unit refused, and the "separate Admin transaction" rule widened: a Platform Admin command whose shared lock scope is empty (no Users, Venues, Resources or Tournaments) may change configuration, so `PUT /admin/points/settings` commits its Idempotency-Key record with the change; a business command still may not |
| `ck_points_transaction_cause`, `ck_points_tx_origin` | Two causes: `BOOKING_FEE_PAYMENT` (an exact debit: `amount = requested_amount < 0`, no admin, no reward columns) and `BOOKING_FEE_REFUND` (a credit compensating the payment). Neither sets `booking_id`, which stays the Booking a reward belongs to, so every reward rule that sums by Booking is unchanged; a fee movement's Booking is its payment row's or its refund decision's |
| `loyalty.booking_fee_points_payment` | New, append-only (`tr_123_write_guard`, `tr_123_no_truncate`, `tr_123_final_integrity`): one row per Booking whose fee Points paid in part or in full - the mode, rate and fee of the creation, the unit and its configuration version, the half hours (PER_HOUR), the Points used, the EGP they paid, the Points transaction. `ck_fee_points_payment_shape` holds the automatic rule's arithmetic |
| `loyalty.booking_fee_refund.points_transaction_id` | New, nullable, unique when set: the `BOOKING_FEE_REFUND` that gave the Points back, written with the decision (`ck_fee_refund_points`: only on a refund above zero). Immutable by the existing `loyalty.refund_mutation_guard` |
| `loyalty.write_guard()` | The new table is append-only |
| `loyalty.post_points(...)` | V1 definition plus: a fee movement names no reward column; a fee payment needs a balance that covers all of it (never the zero-floor cap); a fee refund returns exactly the Points its payment used; a compensation may not reference a fee movement |
| `loyalty.fee_points_cover`, `loyalty.fee_money_due`, `loyalty.preview_fee_payment` | New: the automatic rule (pure), the money part of a Booking's fee, and the quote |
| `loyalty.pay_booking_fee(p_booking)` | New, the one producer of a Booking Fee payment: in the creating command, Points first (debit + payment row), then the Wallet for all of the remainder (its `WALLET` receipt for the remainder only); a second call answers the payment it made |
| `loyalty.pay_fee_from_wallet(p_booking)` | Now the Wallet part of `pay_booking_fee`, so Points always pay first |
| `loyalty.record_refund`, `loyalty.settle_refund`, `loyalty.record_fee_receipt` | A refund above zero gives the Points back with the decision; the Wallet credit, the receipt check and a provider receipt are on the money part (`fee_money_due`) only. When a refund is due is unchanged (`refund_basis`) |
| `loyalty.assert_booking`, `loyalty.assert_user` | Previous definitions plus: the Points payment is the exact automatic payment of the creation (rule replayed from the payment's `balance_before`); a refund above zero of a Points-paid fee carries the exact give-back, a refund of zero none; every fee Points movement is bound to its payment or refund; the Wallet bindings use the money part |

**Existing data.** The checks only widen, so every existing row satisfies them; no backfill. The new keys start at their launch values, so every existing Booking's stored fee still equals `config.booking_fee_for` at its creation. Upgrade seed `upgrade-seed/V202610251200.sql`: a per-hour mode, an hourly fee and a changed unit, written as an Admin change writes them, so later migrations run over a non-launch fee configuration. No Points-payment row is seeded: it needs an Online Booking created through its full intake, which a plain seed cannot write (as for v1.1.39).

**Not covered end to end.** A late customer cancellation of a Points-paid fee (no refund, so the Points stay spent): the test database clock cannot move, and backdating the booking makes its fee disagree with the fee history. The rule is enforced by `assert_booking` (no give-back on a zero refund) and the refund decision itself is unchanged.

- Tests: `PointsBookingFeePaymentIntegrationTest` (both PO examples; the Wallet paying what Points leave and a refund giving both back; the quote, the booking detail and the Points history; a fee change affects only later bookings; a higher fee; a Manual Booking uses no Points; nothing at the launch values); `AdminConfigurationHttpTest` (both settings routes); `BookingFeeModeTest`; `MigrationSafetyTest`; the `.dbm`/migration identity check in `MigrationFilesTest`; `ClosedValueContract` (two Points causes, the fee mode).

## Product Owner decisions (26 Sep 2026)

A1 overlapping Maintenance allowed · A2 booking horizon is a deployment setting, default 120 days · A4 rate-limit table approved · A5 reports FR-AUD-11/12 at launch · A6 venue alerts = live + Web Push + audible alert · A8 configurable = fees and points only · A9 Venue never returns to ONBOARDING · A10 login limits stored as `LOGIN` rate-limit rows · A11 read-only customer wallet screen (PCR-004, no DB change) · review self-reporting allowed, duplicate open reports blocked.

## Known limits of the evidence

The `.dbm` was exported with pgModeler 1.1; open and save it once in pgModeler 1.2.2 to confirm the round-trip. Tournament tests stub the command scope with a test clock. Two-session concurrency and full end-to-end cascades are covered by the application test packs in the Implementation Roadmap, not here.
