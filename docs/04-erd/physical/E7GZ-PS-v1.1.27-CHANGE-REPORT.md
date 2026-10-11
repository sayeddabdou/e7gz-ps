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
| `test_privileges_v127.sh` | 21 checks that the runtime roles can do exactly what they should |

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

## Product Owner decisions (26 Sep 2026)

A1 overlapping Maintenance allowed · A2 booking horizon is a deployment setting, default 120 days · A4 rate-limit table approved · A5 reports FR-AUD-11/12 at launch · A6 venue alerts = live + Web Push + audible alert · A8 configurable = fees and points only · A9 Venue never returns to ONBOARDING · A10 login limits stored as `LOGIN` rate-limit rows · A11 read-only customer wallet screen (PCR-004, no DB change) · review self-reporting allowed, duplicate open reports blocked.

## Known limits of the evidence

The `.dbm` was exported with pgModeler 1.1; open and save it once in pgModeler 1.2.2 to confirm the round-trip. Tournament tests stub the command scope with a test clock. Two-session concurrency and full end-to-end cascades are covered by the application test packs in the Implementation Roadmap, not here.
