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
| `test_privileges_v127.sh` | 16 checks that the runtime roles can do exactly what they should |

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

## Product Owner decisions (26 Sep 2026)

A1 overlapping Maintenance allowed · A2 booking horizon is a deployment setting, default 120 days · A4 rate-limit table approved · A5 reports FR-AUD-11/12 at launch · A6 venue alerts = live + Web Push + audible alert · A8 configurable = fees and points only · A9 Venue never returns to ONBOARDING · A10 login limits stored as `LOGIN` rate-limit rows · A11 read-only customer wallet screen (PCR-004, no DB change) · review self-reporting allowed, duplicate open reports blocked.

## Known limits of the evidence

The `.dbm` was exported with pgModeler 1.1; open and save it once in pgModeler 1.2.2 to confirm the round-trip. Tournament tests stub the command scope with a test clock. Two-session concurrency and full end-to-end cascades are covered by the application test packs in the Implementation Roadmap, not here.
