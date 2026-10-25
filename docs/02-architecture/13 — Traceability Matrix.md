# E7GZ PS
# TRACEABILITY MATRIX v1.1

**Updated:** 25 September 2026 — names and mechanics aligned to physical model v1.1.27 (`04`, `ADR-026`–`ADR-028`).

**Purpose.** Map all **357** approved functional requirements to an owning module, the tables that hold their state, and the endpoint or mechanism that implements them — so no requirement is unassigned and no component exists without a requirement behind it.

**Counts.** FRS v1.1 declares 357 requirements. This matrix contains **357 rows**, one per primary requirement ID. Unassigned: **0**.

**Legend.** *Mechanism* names the endpoint, worker, constraint or orchestration (`O1`–`O20` from `03 §2.4`) that carries the requirement. *Doc* names where the design is specified.

---

# 1. ACCOUNTS, IDENTITY AND VERIFICATION — 18

| FR | Module | Tables | Mechanism | Doc |
|---|---|---|---|---|
| FR-ACC-01 | Identity | `user_account` | `POST /customer/auth/register`; `ux_user_username_normalized`, `uq_user_account_phone_e164` | 03 §3 |
| FR-ACC-02 | Identity | `user_account.phone_verified` | `POST /customer/auth/verify-phone` — the only activation gate | 03 §3 |
| FR-ACC-03 | Identity | — | capability check at the command layer | 10 §4.2 |
| FR-ACC-04 | Identity | `iam.otp_challenge` (`issued_at`) | OTP limits, hashed codes, `ux_otp_active`, `ck_otp_validity`, `ck_otp_attempts`, guard `iam.guard_otp_challenge` | 10 §2.4 |
| FR-ACC-05 | Identity | `user_account.email_verified` | `POST /customer/profile/email/verify` — independent track | 03 §3 |
| FR-ACC-06 | Identity | `user_account.username citext` | normalised uniqueness index | 04 §9.1 |
| FR-ACC-07 | Identity | `iam.username_change` | `POST /customer/profile/username` — 30-day cooldown re-asserted by `iam.guard_user_account_update`; history written automatically | 03 §3 |
| FR-ACC-08 | Identity | `user_account.id` immutable | review projection joins the **current** username | 10 §5.3 |
| FR-ACC-09 | Identity | `otp_challenge` | `POST /customer/auth/password-reset/*` | 09 §3.1 |
| FR-ACC-10 | Identity + Tournament + Reviews | `account_deletion_request`, `account_anonymisation`, `tournament_participant` | `POST /customer/account/deletion-request` initiates; O19 completes anonymisation and `ACCOUNT_DELETED` consequences | 09 §3.1, 10 §5.4, ADR-024 |
| FR-ACC-11 | Trust | `trust_projection`, `verified_player_transition` | replay-derived qualification + operational status | 07 §6 |
| FR-ACC-12 | Trust | `verified_player_transition.transition_id UNIQUE` | one immutable ID per transition | 07 §6.2 |
| FR-ACC-13 | Trust | `verified_player_transition.occurred_at` | activates **now**, never back-dated | 07 §6.2 |
| FR-ACC-14 | Trust | `verified_player_transition.cause` | reconciler auto-restores only when cause = `TRUST_CORRECTION` | 07 §6.2 |
| FR-ACC-15 | Trust | `verified_player_transition` | admin SUSPENDED/REVOKED protected from auto-regain | 07 §6.2 |
| FR-ACC-16 | Trust | `trust_event` | manual bookings and tournaments emit no progress event | 07 §3.2 |
| FR-ACC-17 | Identity + Trust | `ban_instance` | **per-capability** matrix, not a blanket gate | 10 §4.2 |
| FR-ACC-18 | Trust | `trust_event`, `ban_instance` | two separate as-of queries | 07 §3.4 |

---

# 2. DISCOVERY AND BROWSING — 14

| FR | Module | Tables | Mechanism | Doc |
|---|---|---|---|---|
| FR-DIS-13 | Availability | `venue_activity`, `resource` | canonical discovery order enforced by the API shape | 09 §3.2 |
| FR-DIS-14 | Booking | — | canonical booking flow enforced by the create payload | 09 §3.3 |
| FR-DIS-01 | Geography + Venue | `venue.location`, `area.is_active` | `GET /customer/venues` + PostGIS KNN | 05 §5 |
| FR-DIS-02 | Geography | `area` | `areaId` is a **hard filter** even with location on | 09 §3.2 |
| FR-DIS-03 | Availability + Pricing | `venue_activity_availability` | venue card payload | 05 §5.3 |
| FR-DIS-04 | Availability | `venue_activity_availability` | Nearest Availability algorithm, 1-hour minimum | 05 §5.2 |
| FR-DIS-05 | Availability | `resource`, selling-block tables | checks 1–4 exclude ineligible resources from the projection | 05 §2.1 |
| FR-DIS-06 | Venue | `operating_session` | *Closed Now / Opens at*; booking disabled until a valid intake window | 09 §3.2 |
| FR-DIS-07 | Venue | `venue_activity` | *Also available at this venue* | 03 §5 |
| FR-DIS-08 | Venue | `venue.marketplace_status` | absent from lists; deep link returns `bookingEnabled:false`, not 404 | 09 §3.2 |
| FR-DIS-09 | Booking | `booking` | `ContactVisibilityResolver`; manual booking exempt | 10 §5.1 |
| FR-DIS-10 | Availability | `venue.location` | `GET /customer/venues/map` — same eligibility as the list | 09 §3.2 |
| FR-DIS-11 | Venue | `venue`, `venue_image`, `resource` | `GET /customer/venues/{id}` | 09 §3.2 |
| FR-DIS-12 | Availability | live predicate | `GET /customer/find-available` — whole interval, no price filter | 05 §5.4 |

---

# 3. AVAILABILITY AND INVENTORY — 15

| FR | Module | Tables | Mechanism | Doc |
|---|---|---|---|---|
| FR-AVL-01 | Availability | all inventory tables | **one** `isFree` implementation; predicate-singularity test | 05 §2 |
| FR-AVL-02 | Availability | selling blocks, `resource`, `emergency`, `maintenance_window`, `venue_closure`, `resource_reservation` | the eight ordered checks | 05 §2.1 |
| FR-AVL-03 | Availability | same | the blocking set | 05 §2.3 |
| FR-AVL-04 | Ledger | `resource_reservation.kind` | status decides, source never does | 05 §2.3 |
| FR-AVL-05 | Ledger | `active_flag = false` | expiry releases the reservation | 05 §3.2 |
| FR-AVL-06 | Resource | `resource` | `ix_resource_eligible` partial index | 04 §9.4 |
| FR-AVL-07 | Availability | selling-block tables | checks 1–2 before any overlap check | 05 §2.1 |
| FR-AVL-08 | Ledger | `ex_no_double_booking` | **exclusion constraint** — invariant I1 | 05 §3 |
| FR-AVL-09 | Availability | — | `AvailabilityGuard.requireFree` at all 11 revalidation points | 05 §6 |
| FR-AVL-10 | all | — | one transaction per command | 05 §4.2 |
| FR-AVL-11 | Availability | `venue_activity_availability` | snapshot projection; `409 SLOT_NO_LONGER_AVAILABLE` on commit | 05 §5.3 |
| FR-AVL-12 | Ledger | immutable `planned_range` vs one-way release (`actual_reservation_release_at`) | release at the no-show instant | 05 §3.2 |
| FR-AVL-13 | Ledger | `planned_*`, `actual_reservation_start`, `actual_reservation_release_at`, `release_reason` | reservation history columns | 04 §4.1 |
| FR-AVL-14 | Booking + Tournament | `business_date NOT NULL` | computed at creation | 04 §4.3 |
| FR-AVL-15 | all | — | `now < deadline` permits; boundary pack at ±1 s | 08 §9 |

---

# 4. ONLINE BOOKING LIFECYCLE — 32

| FR | Module | Tables | Mechanism | Doc |
|---|---|---|---|---|
| FR-BOK-01 | Booking | `ck_booking_duration` | 1–6 h, 30-min increments | 04 §4.3 |
| FR-BOK-02 | Booking | — | lead-time condition 12 | 06 §1.3 |
| FR-BOK-03 | Booking | `commitment_period`, `ix_booking_customer_status_start` | count under `FOR UPDATE` | 05 §4.2 |
| FR-BOK-04 | Booking | `ex_customer_no_overlap` | **second exclusion constraint** | 04 §4.3 |
| FR-BOK-05 | Venue + Booking | `operating_session` | containment against one session row | 06 §1.3 |
| FR-BOK-06 | Pricing | `venue_price_total` | `NUMERIC` half-up per line item | 04 §1 P4 |
| FR-BOK-07 | Booking | `nominal_booked_minutes`, `start_at`, `end_at` | nominal for money, instants for inventory | 04 §4.3 |
| FR-BOK-08 | Venue | `operating_session` | condition 8 | 06 §1.3 |
| FR-BOK-09 | Venue | `operating_session` | intake cutoff, condition 9 | 06 §1.3 |
| FR-BOK-10 | Booking | — | future interval validated independently | 06 §1.3 |
| FR-BOK-11 | Booking | all | **O1** — the 20-condition list, count asserted | 06 §1.3 |
| FR-BOK-12 | Booking | `ck_booking_status_values` enum | nine states; VRE non-terminal | 04 §1 P5 |
| FR-BOK-13 | Booking + Scheduling | `venue_response_expires_at`, `scheduled_job` | 10-minute deadline, no auto-accept | 08 §1 |
| FR-BOK-14 | Booking | — | **O2** accept guard incl. customer ban revalidation | 06 §2.1 |
| FR-BOK-15 | Booking | `venue.marketplace_status` | category **B** declaration | 10 §4.3 |
| FR-BOK-16 | Booking | `terminal_reason` | operational cause wins; no non-response blame | 06 §2.1 |
| FR-BOK-17 | Booking | `venue_reject_reason_text` | mandatory text + immediate release | 06 §1.2 |
| FR-BOK-18 | Booking + Scheduling | — | T1 expiry; release immediately | 08 §1 |
| FR-BOK-19 | Booking | — | customer projection buckets | 06 §1.5 |
| FR-BOK-20 | Booking | — | shown under Cancelled, internally revivable | 06 §1.5 |
| FR-BOK-21 | Booking | — | `POST /venue/bookings/{id}/reject-after-expiry` | 09 §4.1 |
| FR-BOK-22 | Resource + Late Confirm | `booking_history.detail_code` | state change allowed; event recorded | 05 §8 |
| FR-BOK-23 | Booking + Scheduling | — | T6 five-level reason priority at original start | 06 §1.2 |
| FR-BOK-24 | Booking + Scheduling | — | T4/T5 automatic, backend-controlled | 08 §1 |
| FR-BOK-25 | Availability | — | **O11** start-gate revalidation, atomic | 05 §7 |
| FR-BOK-26 | Booking | `ck_booking_terminal_reason` | can never remain CONFIRMED past start | 05 §7 |
| FR-BOK-27 | Booking | `ck_booking_terminal_reason_values` | seven-level start-time priority | 05 §7 |
| FR-BOK-28 | Booking + Admin | `admin_alert` | `SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR` + Sev-1 | 12 §6.2 |
| FR-BOK-29 | Booking | — | no ordinary early end; four exceptions only | 06 §4 |
| FR-BOK-30 | Booking | — | four-level ACTIVE interruption priority | 06 §1.2 |
| FR-BOK-31 | Booking | — | `POST /customer/bookings/{id}/cancel`, `now < startAt` | 09 §3.3 |
| FR-BOK-32 | Booking | — | cancellable while the response timer is paused | 08 §2.5 |

---

# 5. LATE CONFIRMATION — 18

| FR | Module | Tables | Mechanism | Doc |
|---|---|---|---|---|
| FR-LCF-01 | Late Confirm | — | category **B** declaration | 10 §4.3 |
| FR-LCF-02 | Late Confirm | `venue*`, `area` | venue eligibility list | 03 §12 |
| FR-LCF-03 | Late Confirm | `resource`, blocks, ledger | resource eligibility list | 03 §12 |
| FR-LCF-04 | Late Confirm | `commitment_period` | blocked at 4; **no hold created** | 06 §2.2 |
| FR-LCF-05 | Late Confirm | `booking_history` | neutral API code; internal event | 10 §5.2 |
| FR-LCF-06 | Late Confirm | — | **O3** single atomic initiation | 06 §2.2 |
| FR-LCF-07 | Late Confirm | — | not restricted by the intake window | 03 §12 |
| FR-LCF-08 | Late Confirm | `late_confirmation.booking_id` | **same Booking ID**, all values preserved | 06 §2.2 |
| FR-LCF-09 | Late Confirm | `late_confirmation.expires_at` | `MIN(now+5m, startAt)` | 08 §1 |
| FR-LCF-10 | Late Confirm | `ux_lc_open` | per-booking state; simultaneous LCs independent | 04 §4.3 |
| FR-LCF-11 | Late Confirm | — | customer copy code — surface-owned | 09 §1.3 |
| FR-LCF-12 | Scheduling | `scheduled_job` | T3 warning row **only if** window > 60 s | 08 §2.6 |
| FR-LCF-13 | Late Confirm | `customer_response` | three outcomes, same ID, no penalty | 06 §1.2 |
| FR-LCF-14 | Late Confirm | — | accept revalidation list | 05 §6 |
| FR-LCF-15 | Late Confirm | — | schedule conflict checked **only on accept** | 10 §5.2 |
| FR-LCF-16 | Late Confirm | `booking_history` | `LATE_CONFIRM_FAILED_CUSTOMER_CONFLICT`, non-terminal | 06 §2.2 |
| FR-LCF-17 | Late Confirm | — | validation / atomic failure / resource-conflict event | 05 §4.4 |
| FR-LCF-18 | Booking | — | contact visibility reopens for AWAITING, restored on accept | 10 §5.1 |

---

# 6. ATTENDANCE, CHECK-IN AND NO-SHOW — 28

| FR | Module | Tables | Mechanism | Doc |
|---|---|---|---|---|
| FR-ATT-01 | Attendance | `attendance_record`, `audit_event` | staff action, no proof mechanism, audited | 07 §7.1 |
| FR-ATT-02 | Attendance | — | `[startAt, startAt+15m)` | 07 §7.1 |
| FR-ATT-03 | Attendance | — | Late Present window and effects | 07 §7.1 |
| FR-ATT-04 | Attendance | `ck_attendance_active_evidence_exclusive` | no-show window and preconditions | 04 §9.9 |
| FR-ATT-05 | Ledger | release at the no-show instant | remaining interval released immediately | 05 §3.2 |
| FR-ATT-06 | Attendance | — | terminal; admin-only reversal; never evicts | 07 §7.1 |
| FR-ATT-07 | Attendance | `ck_attendance_result_values` enum | independent stored field; `ABSENT_NO_SHOW` ≠ `NO_SHOW` | 04 §1 P5 |
| FR-ATT-08 | Attendance | — | **O12** eight default generation rules | 07 §7.3 |
| FR-ATT-09 | Attendance | no cross-check constraint | post-correction combinations intentionally valid | 07 §7.3 |
| FR-ATT-10 | Attendance | `operational_interruption_record` | evidence decides at interruption | 07 §7.3 |
| FR-ATT-11 | Attendance + Admin | `audit_event` | **O17** admin-only reversals, mandatory reason | 09 §5 |
| FR-ATT-12 | Attendance | — | five reversal semantics | 07 §7.4 |
| FR-ATT-13 | Admin | — | correction requires the **chosen outcome** | 07 §7.4 |
| FR-ATT-14 | Attendance | — | disable does not terminate ACTIVE | 06 §2.8 |
| FR-ATT-15 | Attendance | — | prior evidence stays authoritative | 07 §7.5 |
| FR-ATT-16 | Attendance | `attendance_protection_candidate` | six cumulative conditions | 07 §7.5 |
| FR-ATT-17 | Attendance | `venue_account_restriction_interval` | window-overlap computation | 07 §7.5 |
| FR-ATT-18 | Attendance | — | applies to early termination too | 07 §7.5 |
| FR-ATT-19 | Trust | `trust_event` | protection event resets counter only; `effectiveAt = startAt` | 07 §3.2 |
| FR-ATT-20 | Attendance | — | created at outcome resolution, not disable time | 07 §7.5 |
| FR-ATT-21 | Attendance | ordered intervals | all evaluated; at most one event | 07 §7.5 |
| FR-ATT-22 | Trust | `ux_trust_booking_account_disable_protection` | exactly one counter-reset mechanism | 04 §4.5 |
| FR-ATT-23 | Attendance | — | later evidence governs; window never reopened | 07 §7.5 |
| FR-ATT-24 | Trust | — | correction to `ABSENT_NO_SHOW` neutralises the reset | 07 §7.5 |
| FR-ATT-25 | Notifications | `notification.code` | `ACTIVE_SESSION_...AFFECTED`, no cancellation wording | 08 §4.5 |
| FR-ATT-26 | Admin | `admin_alert` | operational alert payload | 11 §5.4 |
| FR-ATT-27 | Admin | `admin_alert` | eight non-blocking abuse signals | 11 §5.6 |
| FR-ATT-28 | Admin | — | **no write path** from signals into Trust/Venue/Booking | 11 §5.6 |

---

# 7. MANUAL BOOKING — 11

| FR | Module | Tables | Mechanism | Doc |
|---|---|---|---|---|
| FR-MAN-01 | Booking | `ck_booking_manual_states` | category **C**, `source=MANUAL`, no PENDING | 06 §1.4 |
| FR-MAN-02 | Booking | — | **O4** 15-condition predicate; ONBOARDING refused | 06 §1.4 |
| FR-MAN-03 | Booking | — | published hours and area status do not block | 06 §1.4 |
| FR-MAN-04 | Booking | `ck_booking_manual_states` | four reachable states | 04 §4.3 |
| FR-MAN-05 | Booking | — | **no back-dating**, ever | 06 §1.4 |
| FR-MAN-06 | Trust + Loyalty | — | no progress, no points, no no-show; attendance NOT_APPLICABLE | 07 §3.2 |
| FR-MAN-07 | Booking | `commitment_period` NULL for MANUAL | excluded from the 4-limit | 04 §4.3 |
| FR-MAN-08 | Booking | — | cancel without Owner Password; fee NOT_APPLICABLE | 10 §3.2 |
| FR-MAN-09 | Booking | `business_date NOT NULL`, nullable session fields | always has a businessDate | 04 §4.3 |
| FR-MAN-10 | Booking | — | `MANUAL_BOOKING_PRICING_CONFIGURATION_MISSING` — **error code only** | 09 §1.5 |
| FR-MAN-11 | Booking | `venue.operating_session` | no product horizon; 120-day deployment setting (A2 decided) | 01 §14 |

---

# 8. VENUE MODEL, CONFIGURATION AND DASHBOARD — 47

| FR | Module | Tables | Mechanism | Doc |
|---|---|---|---|---|
| FR-VEN-01 | Venue | `venue_account.venue_id UNIQUE` | one shared account | 04 §9.3 |
| FR-VEN-02 | Venue | `owner_password_hash` | per action; **no unlock window** | 10 §3.1 |
| FR-VEN-03 | Venue | — | protected action list, `@OwnerProtected` | 10 §3.2 |
| FR-VEN-04 | Venue | `audit_event` | unprotected list — still audited | 10 §3.2 |
| FR-VEN-05 | Venue | `audit_event`, rate-limit counters | success and failure audited, rate-limited | 10 §3.4 |
| FR-VEN-46 | Venue | `venue_owner_secret.changed_at` | immediate effect; old value dies | 10 §3.4 |
| FR-VEN-06 | Venue | two independent status columns | independence enforced by schema | 04 §9.3 |
| FR-VEN-07 | Venue | `marketplace_status` | ONBOARDING / INACTIVE / SUSPENDED semantics | 03 §5 |
| FR-VEN-08 | Venue | `account_status` | login blocked; SSE closed; access restriction ≠ outage | 08 §5 |
| FR-VEN-09 | Venue + Booking | — | **O10** five per-state effects | 06 §2.8 |
| FR-VEN-10 | Booking | `ck_booking_terminal_reason_values` | scoped to pre-confirmation lifecycle only | 06 §2.8 |
| FR-VEN-11 | Booking | — | SUSPENDED/INACTIVE creates no blame | 06 §2.8 |
| FR-VEN-12 | Geography | `area.is_active` | discovery only; never blocks manual booking | 03 §4 |
| FR-VEN-47 | Geography | `geo.area`, `venue.venue` | deactivation guard `geo.guard_area_deactivation`; assignment guard `venue.guard_venue_area` | 03 §4 |
| FR-VEN-13 | Resource | two independent columns | ACTIVE + unsellable is representable | 04 §9.4 |
| FR-VEN-14 | Resource + Ledger | blocking-set query | **O18** blocked, never auto-cancelled | 05 §8 |
| FR-VEN-15 | Resource + Pricing | `resource_price_override` | console change clears overrides; history untouched | 03 §7 |
| FR-VEN-16 | Resource | `resource_state_change` | audited state changes | 04 §9.4 |
| FR-VEN-41 | Resource | `ck_console_iff_playstation`, `uq_resource_name` | fixed naming; PS4/PS5 only | 04 §9.4 |
| FR-VEN-42 | Resource | `play_mode` enum | pricing mode, never a player count | 04 §9.4 |
| FR-VEN-43 | Pricing | `ck_price_dimensions` | console × mode + room override | 04 §9.4 |
| FR-VEN-44 | Pricing | same | hourly only; no table override | 04 §9.4 |
| FR-VEN-45 | Pricing | — | one configuration for all three flows; creation blocked if absent | 03 §7 |
| FR-VEN-17 | Venue | `venue_opening_day` | default + weekday overrides, cross-midnight | 04 §9.3 |
| FR-VEN-18 | Venue | `uq_operating_session_day`, `ck_session_len`, `ex_session_no_overlap` | one session per operating day; touch but not overlap | 04 §9.3 |
| FR-VEN-19 | Venue | `ex_session_no_overlap` | cyclical wrap handled by absolute instants | 04 §9.3 |
| FR-VEN-20 | Venue | `operating_session` | closed weekday blocks a new session, not an overnight one | 03 §5 |
| FR-VEN-21 | Venue | session generator | DST resolved once at generation | 11 §2 |
| FR-VEN-22 | Booking | `session_local_*`, `session_timezone` | immutable snapshot | 04 §4.3 |
| FR-VEN-23 | Venue | — | **O5** nine ordered steps, all-or-nothing | 06 §2.4 |
| FR-VEN-24 | Booking | — | affected set definition | 06 §2.4 |
| FR-VEN-25 | Venue | — | Owner Password only for protected cancellations | 06 §2.4 |
| FR-VEN-26 | Venue | `previewToken` checksum | commit aborts if the set grew | 09 §4.2 |
| FR-VEN-27 | Tournament | — | tournament conflict blocks before any preview | 06 §2.4 |
| FR-VEN-28 | Late Confirm | `scheduled_job` | AWAITING timer stops; hold released; customer notified | 06 §2.4 |
| FR-VEN-29 | Operations | `venue_closure` | full-day or partial; blocks all resources | 04 §9.7 |
| FR-VEN-30 | Operations | — | **O6** Owner Password only for protected cancellations | 03 §10 |
| FR-VEN-31 | Operations | — | no retroactive closure over an ACTIVE session | 09 §1.5 |
| FR-VEN-32 | Operations | — | tournament conflict blocks the save | 03 §10 |
| FR-VEN-33 | Operations | Layer 2 non-exclusive | closure may overlap maintenance; reverse is blocked | 05 §3.1 |
| FR-VEN-34 | — | — | Dashboard module set | 09 §4 |
| FR-VEN-35 | Projections | revenue aggregates | `GET /venue/overview` | 08 §7 |
| FR-VEN-36 | Projections | `business_date` | revenue on businessDate; **no POS fields** | 09 §4.1 |
| FR-VEN-37 | Availability | — | nine display states | 09 §4.1 |
| FR-VEN-38 | Booking | `ix_booking_venue_status_start` | history filters and search | 09 §4.1 |
| FR-VEN-39 | Reviews | — | venue **view only** | 03 §17.1 |
| FR-VEN-40 | — | `venue_account.language_preference` | EN/AR RTL; free text unchanged; resolution order | 11 §6 |

---

# 9. MAINTENANCE, EMERGENCIES AND OPERATIONAL RESTRICTIONS — 44

| FR | Module | Tables | Mechanism | Doc |
|---|---|---|---|---|
| FR-OPS-01 | Operations | `maintenance_window`, `audit_event` | CRUD audited | 03 §10 |
| FR-OPS-02 | Operations + Ledger | `ex_no_double_booking` | maintenance is Layer 1 → overlap impossible | 05 §3.1 |
| FR-OPS-03 | Operations | `operations.maintenance_union_minutes_in_window` | 24h-in-48h per resource over the union of (possibly overlapping) windows | 04 §9.7 |
| FR-OPS-04 | Operations | — | extending past the threshold requires Owner Password | 03 §10 |
| FR-OPS-05 | Operations | `created_from_emergency_id` | emergency-created maintenance counts | 04 §9.7 |
| FR-OPS-06 | Operations | `emergency` | NOW only, no password, mandatory reason, notify | 06 §2.5 |
| FR-OPS-07 | Operations | `ck` on expected end | mandatory, within the current session | 04 §9.7 |
| FR-OPS-08 | Operations | `declared_period` | cascade scope | 06 §2.5 |
| FR-OPS-09 | Operations | — | **O7** all-or-nothing before notifications | 06 §2.5 |
| FR-OPS-10 | Operations | — | irreversible; ending early restores nothing | 06 §2.5 |
| FR-OPS-44 | Operations + Late Confirm | — | emergency reason wins over reconfirm timeout | 06 §2.5 |
| FR-OPS-11 | Operations | `emergency` scope RESOURCE | mandatory return time; longer → maintenance | 03 §10 |
| FR-OPS-12 | Operations | — | **O8** resource-scoped cascade | 06 §2.5 |
| FR-OPS-13 | — | — | **no** emergency rate limit — deliberate non-control | 10 §7 |
| FR-OPS-14 | Admin | `admin_alert` | ≥3 tournament cancellations / 30 days, non-blocking | 11 §5.6 |
| FR-OPS-15 | Operations | selling-block tables | emergency **must** create the matching block | 05 §3.1 |
| FR-OPS-16 | Availability | — | what a block prevents | 05 §2.1 |
| FR-OPS-17 | Operations | block as **state** | persists across sessions without extending the cascade | 05 §3.1 |
| FR-OPS-18 | Operations | `ck_emergency_status` enum | lifecycle; expected end never auto-reopens | 04 §1 P5 |
| FR-OPS-19 | Operations | — | ENDED requires operational assertion or replacement | 03 §10 |
| FR-OPS-20 | Operations | — | stays AWAITING_CONFIRMATION until resolved | 03 §10 |
| FR-OPS-21 | Operations | integrity sweep | **no sellable gap**; Sev-1 | 11 §4 |
| FR-OPS-22 | Operations | — | extension previews and applies atomically | 03 §10 |
| FR-OPS-23 | Operations | `ck_emergency_conversion_target` | resource emergency cannot become an hours change | 04 §9.7 |
| FR-OPS-24 | Operations | — | block ends in the same commit that activates the replacement | 03 §10 |
| FR-OPS-25 | Notifications | `notification` | three operational reminders | 11 §5.5 |
| FR-OPS-42 | Operations + Resource | — | emergency blocks availability, not state change | 05 §8 |
| FR-OPS-43 | Operations | blocking-set query | Dashboard shows blocking commitments, never a fake action | 05 §8 |
| FR-OPS-26 | Operations | `owner_review` | entry at expected/actual end over threshold | 03 §10 |
| FR-OPS-27 | Operations | — | threshold evaluated **before** ending | 03 §10 |
| FR-OPS-28 | Operations | — | **O9** system cascade across bookings and tournaments | 06 §2.7 |
| FR-OPS-29 | Operations | — | no Owner Password anywhere; partial cascade invalid | 06 §2.7 |
| FR-OPS-30 | Operations | — | not a pause state; PENDING timer stops, no VRE | 06 §2.7 |
| FR-OPS-31 | Loyalty + Trust | `booking_fee_refund` | no penalty, full refund, NOT_APPLICABLE attendance | 06 §2.7 |
| FR-OPS-32 | Late Confirm | `booking_history.detail_code` | LC blocked; eligibility restorable, never automatic | 03 §12 |
| FR-OPS-33 | Operations | `owner_review.resolution` | Owner Password + four actions | 09 §4.2 |
| FR-OPS-34 | Scheduling | `response_pause_started_at` | venue or affecting-resource emergency pauses | 08 §2.5 |
| FR-OPS-35 | Scheduling | `late_confirm_pause_started_at` | LC countdown pause | 08 §2.5 |
| FR-OPS-36 | Scheduling | — | remaining duration preserved | 08 §2.5 |
| FR-OPS-37 | Scheduling | four named booking columns | two 30-minute caps; shared response counter | 08 §2.5 |
| FR-OPS-38 | Booking | three terminal reasons | cap outcomes; no blame; no penalty | 08 §2.5 |
| FR-OPS-39 | Scheduling + Venue | `operating_session` | five resume conditions | 08 §2.5 |
| FR-OPS-40 | Scheduling | — | optimistic feasibility, pure over stored state | 08 §2.5 |
| FR-OPS-41 | Operations | `booking_timer_pause_history` | pause/resume notifications; Decline works while paused | 08 §2.5 |

---

# 10. TOURNAMENTS — 33

| FR | Module | Tables | Mechanism | Doc |
|---|---|---|---|---|
| FR-TRN-01 | Tournament | `tournament` | separate entity, never modelled as a booking | 06 §3 |
| FR-TRN-02 | Tournament + Trust | — | VERIFIED creator + venue approval for player-hosted | 06 §3.4 |
| FR-TRN-03 | Tournament | `ck` capacity, duration, checkpoint | all parameters | 04 §4.4 |
| FR-TRN-04 | Tournament | `ck_tournament_play_mode` | mode required; interval fits one session | 04 §4.4 |
| FR-TRN-05 | Tournament | `creator_participates` | Organizer Only vs Organizer + Participant | 04 §4.4 |
| FR-TRN-06 | Tournament | `visibility`, `invite_code` | public Discover vs private code/link | 03 §14 |
| FR-TRN-07 | Tournament | — | invite inactive while pending; nobody may join | 03 §14 |
| FR-TRN-08 | Tournament + Ledger | `ex_no_double_booking` | **existing reservations always win** | 05 §4.4 |
| FR-TRN-33 | Tournament | — | per-resource creation checks | 05 §6 |
| FR-TRN-09 | Tournament + Ledger | `TOURNAMENT_APPROVAL_HOLD` → `TOURNAMENT_HOLD` | hold lifecycle | 05 §3.2 |
| FR-TRN-10 | Tournament + Scheduling | `approval_expires_at`, `ck_tournament_approval_deadline`, `ck_tournament_rejected_reason` | T7 approval window `MIN(created + 10 min, checkpoint)`; Reject → `REJECTED/VENUE_REJECTED_TOURNAMENT`; timeout → `CANCELLED/VENUE_DID_NOT_APPROVE_TOURNAMENT_IN_TIME` | 08 §1, 04 §4.4 |
| FR-TRN-11 | Tournament | `ux_tournament_one_pending_creator`, `ix_tournament_retry_cooldown` | one pending platform-wide + 5-min cooldown after REJECTED or approval timeout | 04 §4.4 |
| FR-TRN-12 | Tournament | `tournament_participant` | join/leave rules; Join uses `capacityOccupiedCount(now)`; below 8 Active Participants before checkpoint is fine | 06 §3.4, §3.7 |
| FR-TRN-13 | Tournament | `ck_tournament_participant_status`, `ineligible_reason` | Active Participant definition; separate Capacity Occupancy; history retained | 04 §4.4, 06 §3.7 |
| FR-TRN-14 | Tournament + Scheduling | — | T8 checkpoint; **no Run Anyway** | 06 §3.4 |
| FR-TRN-15 | Tournament | — | checkpoint revalidates every resource | 05 §6 |
| FR-TRN-16 | Tournament | category **B** | operational eligibility, not marketplace | 10 §4.3 |
| FR-TRN-17 | Tournament | — | start revalidation; owner review cancels before play | 05 §6 |
| FR-TRN-18 | Tournament | — | disable during pending: immediate, no cooldown, no blame | 06 §2.8 |
| FR-TRN-19 | Tournament | — | disable alone does not cancel later states | 06 §2.8 |
| FR-TRN-20 | Trust + Tournament | `verified_player_transition` | cascade once per transition ID | 06 §3.6 |
| FR-TRN-21 | Tournament | — | cancellation authority matrix | 06 §3.3 |
| FR-TRN-22 | Tournament | — | four early-end paths only | 06 §3.3 |
| FR-TRN-23 | Tournament | `audit_event` | Operational Termination with Owner Password | 09 §4.2 |
| FR-TRN-24 | Tournament | `@OwnerProtected` | the four-row password matrix | 10 §3.2 |
| FR-TRN-25 | Tournament | `tournament_history` | every cancellation releases, records, notifies | 03 §14 |
| FR-TRN-26 | Tournament + Trust + Identity | `ck_tournament_participant_status`, `ineligible_reason` | `BAN` vs `ACCOUNT_DELETED` cause precedence, BAN-only pre-start slot reservation, boundary-time restoration, no restore at/after Start | 06 §3.7, 07 §5.7, ADR-023 |
| FR-TRN-27 | Tournament | `tournament_resource.removed_at` | resource-loss rules before and during | 06 §3.5 |
| FR-TRN-28 | Pricing + Tournament | `venue_cost_total`, per-resource snapshot | cost snapshot, never changed by loss | 03 §7 |
| FR-TRN-29 | Tournament | `entry_fee`, `prize` | informational only; E7GZ collects nothing | 04 §4.4 |
| FR-TRN-30 | Projections | `business_date` | revenue only on COMPLETED | 09 §4.1 |
| FR-TRN-31 | Tournament | `ck_tournament_terminal_reason` | seven-level priority, exactly one reason | 06 §3.2 |
| FR-TRN-32 | Tournament | `ck_tournament_terminal_reason_values` enum | closed 12-member enum | 04 §1 P5 |

---

# 11. TRUST, PENALTIES AND BANS — 27

| FR | Module | Tables | Mechanism | Doc |
|---|---|---|---|---|
| FR-TRS-01 | Trust | `trust_event` vs `trust_projection` | derived, never authoritative | 07 §1 |
| FR-TRS-02 | Trust | `ck_trust_event_type` enum | closed catalogue | 07 §2.1 |
| FR-TRS-03 | Trust | `booking_history` | evidence enters only via a canonical outcome | 07 §1 |
| FR-TRS-04 | Trust | `trust_event` columns | event shape | 07 §2.2 |
| FR-TRS-05 | Trust | `effective_at` | closed effective-time rules | 07 §2.3 |
| FR-TRS-06 | Trust | `ix_trust_replay` | exclusive ordering | 07 §2.4 |
| FR-TRS-07 | Trust | `ck_trust_event_corrected_outcome` | neutralise and substitute; never delete | 07 §3.3 |
| FR-TRS-08 | Trust + Loyalty | `granted_value_snapshot` | original-date points value on correction | 07 §3.3 |
| FR-TRS-09 | Trust | `superseded_correction_event_id`, `event_sequence` | latest non-superseded wins; one outcome per booking | 07 §3.3 |
| FR-TRS-10 | Trust | — | as-of replay | 07 §3.4 |
| FR-TRS-11 | Trust | — | counter effects; three reset events | 07 §3.2 |
| FR-TRS-12 | Trust | — | what does not count; global ordering by booking start | 07 §3.2 |
| FR-TRS-13 | Trust | Java constants | the ladder — **not** admin-configurable | 11 §7 |
| FR-TRS-14 | Trust | — | trigger on increment **to** the threshold | 07 §5.1 |
| FR-TRS-15 | Trust | `trust_projection`, `ban_instance` | Account Standing shows exactly one current ban | 09 §3.1 |
| FR-TRS-16 | Trust | — | reset and stage advance at ban creation | 07 §5.1 |
| FR-TRS-17 | Trust | `ban_qualification`, `ban_instance` | two distinct tables | 07 §5.2 |
| FR-TRS-18 | Trust | `activated_at` | never back-dated; one instance | 07 §5.2 |
| FR-TRS-19 | Trust | — | operational ban as-of from the instance | 07 §3.4 |
| FR-TRS-20 | Trust + Booking | — | **O14** cascade; ACTIVE continues | 07 §5.4 |
| FR-TRS-21 | Trust | `cascade_executed_at` | once only, under replay | 07 §4.1 |
| FR-TRS-22 | Trust | `ux_one_active_ban` | extension via MAX formula; `BAN_EXTENDED` audit | 07 §5.3 |
| FR-TRS-23 | Trust | `ck_ban_instance_status` | four statuses; history retained | 04 §4.5 |
| FR-TRS-24 | Trust | pure `replay` | never rewinds external side effects | 07 §3.1 |
| FR-TRS-25 | Trust | — | invalid ban corrected; bookings do not return | 07 §5.6 |
| FR-TRS-26 | Admin | — | **no** admin booking-creation endpoint | 09 §5 |
| FR-TRS-27 | Admin | `audit_event` | remediation with reason, identity, timestamp; **no** wallet adjust | 09 §5 |

---

# 12. POINTS, REFERRALS, WALLET AND FEES — 21

| FR | Module | Tables | Mechanism | Doc |
|---|---|---|---|---|
| FR-PTS-01 | Loyalty | `points_transaction.granted_value_snapshot`, `ux_points_*` | values read at the named moment; no double grant | 04 §4.6 |
| FR-PTS-02 | Loyalty | — | points and trust separate | 07 §8 |
| FR-PTS-03 | Loyalty | `CHECK (balance >= 0)` | never negative; MIN rule | 04 §4.6 |
| FR-PTS-04 | Loyalty + Audit | `unrecovered_reversal_amount` | not debt; never auto-repaid | 07 §8 |
| FR-PTS-05 | Loyalty | append-only triggers | compensating transactions only | 04 §1 P7 |
| FR-PTS-06 | Loyalty | `referral`, `ck_referral_not_self` | eligible at **OTP verification**; one reward | 03 §16 |
| FR-PTS-07 | Loyalty | `reward_value_applied` | value read at verification; no progress | 03 §16 |
| FR-PTS-08 | Loyalty | `fraud_signals jsonb` | phone/email hard; device/IP signal only | 03 §16 |
| FR-PTS-09 | Loyalty | compensating row | exact historical reward; shortfall recorded | 07 §8 |
| FR-PTS-10 | Loyalty | `wallet_account` | EGP 0; never pays the venue charge | 03 §16 |
| FR-PTS-11 | Loyalty | — | ledger exists, **no** wallet screen, no admin adjust | 09 §3.4 |
| FR-PTS-12 | Loyalty | `loyalty.points_per_fee_unit`, `booking_fee_points_payment`, `pay_booking_fee` | PCR-010: no conversion; Points pay the Booking Fee (10,000 per fee unit, even, configurable) | 03 §16 |
| FR-PTS-13 | Loyalty + Booking | `booking.booking_fee_snapshot`, `config.booking_fee_for` | EGP 0, per booking or per hour (PCR-010), displayed FREE, LC reuses original | 04 §4.3 |
| FR-PTS-14 | Loyalty | `fee_treatment` enum | 15-minute rule vs full refund | 03 §16 |
| FR-PTS-15 | Loyalty | — | closed 18-member full-refund list | 03 §16 |
| FR-PTS-16 | Loyalty | exhaustive switch, no default | one treatment per reachable reason | 03 §16 |
| FR-PTS-17 | Loyalty | — | LC decline/timeout is not ordinary cancellation | 03 §16 |
| FR-PTS-18 | Loyalty | — | reclassification is not a refund trigger | 03 §16 |
| FR-PTS-19 | Loyalty | — | post-start interruption → full refund | 03 §16 |
| FR-PTS-20 | Booking | `terminal_at`, `end_at` | contact retention rules | 10 §5.1 |
| FR-PTS-21 | Admin + Audit | `audit_event` | audited support access; VRE stays revivable | 10 §5.1 |

---

# 13. REVIEWS — 10

| FR | Module | Tables | Mechanism | Doc |
|---|---|---|---|---|
| FR-REV-01 | Reviews | — | verified reviewer; **no** completed booking required | 03 §17.1 |
| FR-REV-02 | Reviews | `rating CHECK 1..5` | current username public; full name admin-only | 10 §5.3 |
| FR-REV-03 | Reviews | `ux_review_current` | one current review per user per venue | 04 §4.7 |
| FR-REV-04 | Reviews | `ck_review_status` enum | four states; REMOVED terminal | 04 §4.7 |
| FR-REV-05 | Reviews | — | created ACTIVE; **no auto-publish** of pending | 03 §17.1 |
| FR-REV-06 | Reviews | `review_moderation_event` | author delete keeps moderation history | 03 §17.1 |
| FR-REV-07 | Reviews | `review_cooldown` | 30-day cooldown; re-entry is PENDING_MODERATION | 04 §4.7 |
| FR-REV-08 | Reviews | `review_moderation_event` | venue view-only; admin actions need a reason | 09 §5 |
| FR-REV-09 | Reviews | — | SUSPENDED/INACTIVE venues keep and receive reviews | 03 §17.1 |
| FR-REV-10 | Notifications | `notification` | author notified at each moderation step | 08 §4.4 |
| FR-REV-11 | Reviews + Admin | `review_report`, `review_moderation_event` | `POST /customer/reviews/{id}/report`; O20 Admin resolution; reporting itself never mutates Review | 03 §17.1, 09 §3, ADR-025 |

---

# 14. PLATFORM ADMIN — 11

| FR | Module | Tables | Mechanism | Doc |
|---|---|---|---|---|
| FR-ADM-01 | Admin | — | 20 admin modules, English only | 09 §5 |
| FR-ADM-02 | Admin + Venue | `venue_image` | admin-managed onboarding; images persist | 12 §2 |
| FR-ADM-03 | Admin + Venue | `audit_event` | disable/enable, password resets, venue notified | 09 §5 |
| FR-ADM-04 | Admin | `user_account`, `trust_projection` | user management fields | 09 §5 |
| FR-ADM-05 | Admin + Trust | `verified_player_transition` | Suspend/Revoke/Restore with reason | 07 §6.2 |
| FR-ADM-06 | Admin + Booking | `booking`, `booking_history` | filters, search, full lifecycle history | 09 §5 |
| FR-ADM-07 | Admin + Trust | `trust_event`, `ban_instance` | no-show records view | 09 §5 |
| FR-ADM-08 | Admin + Tournament | `tournament*` | tournament inspection | 09 §5 |
| FR-ADM-09 | Admin + Loyalty | `wallet_*`, `points_*`, `referral` | wallet never shows venue revenue | 09 §5 |
| FR-ADM-10 | Config | `config.configuration_key` · `configuration_version` · `system_configuration` (`booking.fee`, `tournament.creation_fee`) | booking fee and tournament creation fee; snapshots checked at creation (`ADR-028`) | 11 §7 |
| FR-ADM-11 | Config | `configuration_version.effective_from` | future events only | 11 §7 |

---

# 15. NOTIFICATIONS — 12

| FR | Module | Tables | Mechanism | Doc |
|---|---|---|---|---|
| FR-NTF-01 | Notifications | `notification_delivery.channel` | four channels; **SMS for OTP only** | 08 §4.4 |
| FR-NTF-02 | Notifications | — | In-App + Push for time-sensitive events | 08 §4.4 |
| FR-NTF-03 | Notifications | — | In-App + Email for account/security/moderation | 08 §4.4 |
| FR-NTF-04 | Notifications | `notification.booking_id` | required field on the payload **type** | 08 §4.5 |
| FR-NTF-05 | Notifications | distinct code | attendance-unconfirmed explains no reward | 08 §4.5 |
| FR-NTF-06 | Notifications | three distinct codes | cause distinction; internal codes authoritative | 08 §4.5 |
| FR-NTF-07 | Notifications | — | cancellation wording only from a terminal handler | 08 §4.5 |
| FR-NTF-08 | Notifications | — | `VenueAccountEnabled` has no customer consumer | 08 §4.5 |
| FR-NTF-09 | Notifications | `notification` + SSE | venue catalogue; SSE + Web Push | 08 §5 |
| FR-NTF-10 | Admin | `admin_alert` | admin catalogue | 11 §5.4 |
| FR-NTF-11 | Admin + Notifications | — | every material admin change notifies the customer | 09 §5 |
| FR-NTF-12 | Messaging | `outbox_event` | dispatch after commit, never before | 08 §4.2 |

---

# 16. AUDIT AND HISTORICAL INTEGRITY / REPORTING — 15

| FR | Module | Tables | Mechanism | Doc |
|---|---|---|---|---|
| FR-AUD-01 | all | snapshot columns | configuration affects future events only | 04 §1 P6 |
| FR-AUD-02 | Audit | snapshot tables | the eight snapshot sets | 04 §9 |
| FR-AUD-03 | Audit | `audit_event` | canonical audit list | 10 §8.1 |
| FR-AUD-04 | Audit | append-only triggers | data-layer enforcement | 04 §1 P7 |
| FR-AUD-05 | Booking | `booking_history` | nothing removed; *Auto Cancelled* survives revival | 04 §4.3 |
| FR-AUD-06 | Audit | `actor_*`, `reason`, `correlation_id` | record shape | 10 §8.2 |
| FR-AUD-13 | Booking | `ck_booking_history_entry_shape` + three closed `CHECK` lists (`ck_booking_terminal_reason_values`, `ck_booking_history_event_type_values`) and the Java `ApiErrorCode` | reason codes and events never conflate | 04 §1 P5 |
| FR-AUD-14 | Booking | `ck_booking_history_event_type_values` enum, 12 members | canonical event list | 04 §1 P5 |
| FR-AUD-15 | — | CI test | five removed tokens must not reappear | 09 §7 |
| FR-AUD-07 | Booking + Tournament | `business_date` | single revenue-day key | 04 §4.3 |
| FR-AUD-08 | Projections | `ix_booking_business` | revenue aggregation | 09 §4.1 |
| FR-AUD-09 | Tournament | — | tournament revenue only on COMPLETED | 09 §4.1 |
| FR-AUD-10 | Booking | `booking_fee_snapshot` | historical fee exposed alongside price and total | 09 §5 |
| FR-AUD-11 | Reporting | `booking.terminal_reason` | scheduled query; **in launch scope** (A5 decided) | 01 §14 A5 |
| FR-AUD-12 | Reporting | `attendance_record` | scheduled query; **in launch scope** (A5 decided) | 01 §14 A5 |

---

# 17. COVERAGE SUMMARY

| Domain | FRs | Rows here | Unassigned |
|---|---:|---:|---:|
| Accounts, Identity and Verification | 18 | 18 | 0 |
| Discovery and Browsing | 14 | 14 | 0 |
| Availability and Inventory | 15 | 15 | 0 |
| Online Booking Lifecycle | 32 | 32 | 0 |
| Late Confirmation | 18 | 18 | 0 |
| Attendance, Check-in and No-show | 28 | 28 | 0 |
| Manual Booking | 11 | 11 | 0 |
| Venue Model, Configuration and Dashboard | 47 | 47 | 0 |
| Maintenance, Emergencies and Operational Restrictions | 44 | 44 | 0 |
| Tournaments | 33 | 33 | 0 |
| Trust, Penalties and Bans | 27 | 27 | 0 |
| Points, Referrals, Wallet and Fees | 21 | 21 | 0 |
| Reviews | 11 | 11 | 0 |
| Platform Admin | 11 | 11 | 0 |
| Notifications | 12 | 12 | 0 |
| Audit and Historical Integrity / Reporting | 15 | 15 | 0 |
| **TOTAL** | **357** | **357** | **0** |

## 17.1 Reverse check — components without a requirement

Every table, module and worker in this pack traces to at least one requirement above, except the following **technical** components, which exist to satisfy non-functional requirements rather than functional ones:

| Component | Justified by |
|---|---|
| `scheduled_job` + deadline worker | `NFR-04`, `NFR-14` |
| `outbox_event` + dispatcher | `FR-NTF-12`, `NFR-03`, `NFR-13` |
| `idempotency_record` | `NFR-04` |
| `venue_activity_availability` projection | `NFR-11` |
| `operating_session` materialization | `NFR-07`, `NFR-08`, `FR-VEN-18`…`FR-VEN-21` |
| `auth_refresh_token` | `NFR-16` context, session security |
| SSE endpoint | `FR-NTF-09`, `R2` |

Nothing else exists. There is no table, endpoint or worker in this architecture that is not answerable to a citation.

## 17.2 Requirements with an implementation caveat

| FR | Caveat | Where |
|---|---|---|
| FR-MAN-11 | 120-day horizon as a deployment setting | A2 — decided |
| FR-AUD-11, FR-AUD-12 | built as scheduled reporting queries, in launch scope | A5 — decided |
| FR-NTF-09 | venue channel SSE + Web Push + audible alert | A6 — decided |
| FR-VEN-05, NFR-16 | rate-limit values as approved in `10 §7` | A4 — decided |

---

*End of Traceability Matrix v1.0. Continue to `14 — Implementation Roadmap.md`.*
