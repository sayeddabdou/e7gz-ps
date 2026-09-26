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

## Product Owner decisions (26 Sep 2026)

A1 overlapping Maintenance allowed · A2 booking horizon is a deployment setting, default 120 days · A4 rate-limit table approved · A5 reports FR-AUD-11/12 at launch · A6 venue alerts = live + Web Push + audible alert · A8 configurable = fees and points only · A9 Venue never returns to ONBOARDING · A10 login limits stored as `LOGIN` rate-limit rows · A11 read-only customer wallet screen (PCR-004, no DB change) · review self-reporting allowed, duplicate open reports blocked.

## Known limits of the evidence

The `.dbm` was exported with pgModeler 1.1; open and save it once in pgModeler 1.2.2 to confirm the round-trip. Tournament tests stub the command scope with a test clock. Two-session concurrency and full end-to-end cascades are covered by the application test packs in the Implementation Roadmap, not here.
