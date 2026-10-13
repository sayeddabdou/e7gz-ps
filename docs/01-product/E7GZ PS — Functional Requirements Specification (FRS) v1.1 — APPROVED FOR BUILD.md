# E7GZ PS
# FUNCTIONAL REQUIREMENTS SPECIFICATION (FRS) v1.1 — APPROVED FOR BUILD

**Status:** APPROVED FOR BUILD
**Date:** 18 September 2026
**Tournament participant-cause alignment:** 25 September 2026 — synchronized to Product Specification v5.0 §§37.4 and 37.11 without changing requirement IDs or priorities.
**Product behavioural authority:** `E7GZ PS — Product Specification v5.0 — FROZEN BASELINE`
**Approved requirements authority:** `E7GZ PS — Product Requirements Document (PRD) v1.1 — APPROVED FOR BUILD`
**Functional requirements represented:** **357 / 357**
**Derivation audit:** `E7GZ PS — FRS-to-PRD-v5.0 Derivation Audit v1.0` — all findings closed

> **Authority rule.** v5.0 governs Product behaviour. PRD v1.1 governs approved scope, requirement IDs, priorities and acceptance intent. This FRS explains how the approved functions behave from trigger to postcondition without choosing database schema, service boundaries, frameworks, infrastructure, locking technology or API shape. If this FRS conflicts with v5.0, v5.0 wins; if it omits an approved PRD requirement, the FRS must be corrected.

---

# 0. DOCUMENT PURPOSE AND BOUNDARIES

## 0.1 Purpose

This FRS is the functional bridge between the frozen Product and Technical Architecture. It is written for Product, UX/UI, Backend, Mobile, Venue Dashboard, Platform Admin, QA and Architecture teams. It provides end-to-end flows, state/authorization/timer matrices, acceptance obligations and a complete 357-requirement catalogue.

## 0.2 What this FRS does not do

- Does not add or remove Product behaviour.
- Does not create new states, enums, reasons, events, timers, authorization roles or eligibility predicates.
- Does not decide database tables, microservices, queues, schedulers, cache, infrastructure or framework choices.
- Does not turn PRD-authored TBD/non-product targets into frozen Product rules.
- Does not use v4.x as implementation authority; v5.0 is the behavioural source of truth.

## 0.3 Actors

| Actor | Functional role |
|---|---|
| Customer | Registers, discovers, books, responds to Late Confirm, participates in tournaments, reviews venues and views standing/rewards. |
| Venue Staff | Operates the shared Venue Dashboard: requests, manual bookings, attendance, normal operations and emergencies. |
| Venue Owner authorization | Owner Password is a second secret used per protected action; it is not a separate user/session. |
| Platform Admin | Marketplace onboarding/control, moderation, trust remediation, account/status controls and global configuration. |
| System / Scheduler | Executes timers, state transitions, cascades, replay, historical derivations and notifications. |

## 0.4 Independent status domains

| Domain | Canonical values |
|---|---|
| Booking Status | PENDING · VENUE_RESPONSE_EXPIRED · AWAITING_CUSTOMER_CONFIRMATION · CONFIRMED · ACTIVE · COMPLETED · REJECTED · CANCELLED · NO_SHOW |
| Booking Source | ONLINE · MANUAL |
| Attendance Result | ATTENDED · ATTENDANCE_UNCONFIRMED · ATTENDED_INTERRUPTED · ABSENT_NO_SHOW · NOT_APPLICABLE |
| Tournament Status | PENDING_VENUE_APPROVAL · WAITING_FOR_PLAYERS · CONFIRMED · ACTIVE · COMPLETED · REJECTED · CANCELLED |
| Tournament Participant Status | JOINED · LEFT · REMOVED · INELIGIBLE |
| Venue Marketplace Status | ONBOARDING · ACTIVE · INACTIVE · SUSPENDED |
| Venue Account Status | ENABLED · DISABLED |
| Resource Base State | ACTIVE · DISABLED · ARCHIVED |
| Resource Operational Restriction | NONE · OWNER_REVIEW_REQUIRED |
| Emergency Status | ACTIVE · AWAITING_CONFIRMATION · ENDED · CONVERTED |
| Ban Instance Status | ACTIVE · EXPIRED · LIFTED · CORRECTED |
| Verified Player Qualification | NOT_QUALIFIED · QUALIFIED |
| Verified Player Operational Status | NOT_VERIFIED · VERIFIED · SUSPENDED · REVOKED |
| Review Status | PENDING_MODERATION · ACTIVE · HIDDEN · REMOVED |
| Referral Status | PENDING · REWARDED · INVALID |

When `Tournament Participant Status = INELIGIBLE`, the explicit cause is `BAN` or `ACCOUNT_DELETED`. This cause is not another status domain; `ACCOUNT_DELETED` has precedence.

No domain may be inferred from another. Booking Status does not determine Attendance Result; Marketplace Status does not determine Account Status; Resource Base State does not determine Operational Restriction; qualification facts do not by themselves determine operational privileges.

## 0.5 Derivation audit closure

FRS v1.1 closes the functional-derivation audit against Product Specification v5.0 and PRD v1.1. The normative 357-row requirement catalogue remains equivalent to the approved PRD in requirement ID, priority and v5.0 reference. On 25 September 2026, PCR-001 synchronized Tournament participant cause/capacity wording; PCR-002 amended `FR-ACC-10` to add Customer-initiated deletion requests; PCR-003 added `FR-REV-11` for Customer Review reporting. Prior requirement IDs and priorities are unchanged except for the new mandatory `FR-REV-11`. The earlier v1.1 corrections remain limited to FRS decomposition/matrices: Manual Booking lifecycle precision, canonical reason grouping, transition-matrix completeness, timer coverage, authorization representation, and explicit notification/audit matrices.

---

# 1. END-TO-END FUNCTIONAL FLOWS

## F-01 — Customer registration and phone verification

**Requirement coverage:** `FR-ACC-01` · `FR-ACC-02` · `FR-ACC-04` · `FR-ACC-05` · `FR-ACC-06`

1. Customer submits Full Name, Username, Phone, Email and Password.
2. System validates mandatory fields and normalized Username/Phone uniqueness.
3. Account is created but marketplace actions remain gated until phone verification.
4. System issues SMS OTP under the frozen validity, attempt, resend and hourly-send limits.
5. A valid phone OTP sets the account to Account Verified; email verification remains independent.
6. Marketplace-gated actions use phone verification, not email verification, as the activation condition.

**Functional boundary:** Any branch, copy, validation, state transition, notification, audit consequence or financial/Trust effect not explicitly supported by the cited PRD requirements or their v5.0 sections is out of scope for this FRS.

### F-01A — Customer initiates Account Deletion

**Requirement coverage:** `FR-ACC-10`

1. An authenticated Customer selects Delete Account inside the Customer Mobile App.
2. The system records one durable Account Deletion Request for that immutable User ID. If an unresolved request already exists, that request remains authoritative rather than creating another deletion operation.
3. Initiation does not hard-delete retained history and does not itself fabricate the Admin anonymisation audit record.
4. Support / Platform Admin completes the existing deletion/anonymisation process, linking the completion to the Customer request.
5. Completion disables login, removes/anonymises PII where appropriate, preserves required Booking/financial/audit history, removes Reviews from public display and applies the Tournament `ACCOUNT_DELETED` consequence.

**Functional boundary:** No self-service cancellation deadline, automatic completion deadline or destructive hard-delete path is introduced by PCR-002.

## F-02 — Venue discovery and venue details

**Requirement coverage:** `FR-DIS-13` · `FR-DIS-01` · `FR-DIS-02` · `FR-DIS-03` · `FR-DIS-04` · `FR-DIS-06` · `FR-DIS-08` · `FR-DIS-10` · `FR-DIS-11`

1. Customer resolves Area/Location and selects Activity before venue discovery.
2. System lists discoverable venues under marketplace and Area rules; manual Area selection is a hard filter.
3. Venue cards show the frozen field set including starting price and Nearest Availability.
4. Closed venues remain browseable with future availability but cannot accept a new request until a valid intake window.
5. Map and Venue Details use the same eligibility/availability truth as list discovery.

**Functional boundary:** Any branch, copy, validation, state transition, notification, audit consequence or financial/Trust effect not explicitly supported by the cited PRD requirements or their v5.0 sections is out of scope for this FRS.

## F-03 — Find Available

**Requirement coverage:** `FR-DIS-12` · `FR-AVL-01` · `FR-AVL-02` · `FR-AVL-03`

1. Customer supplies Area, Activity, Date, Start Time and Duration.
2. System evaluates the entire requested interval with the single canonical availability predicate.
3. Only resources available for the whole interval are returned.
4. No price filter is available in this release.

**Functional boundary:** Any branch, copy, validation, state transition, notification, audit consequence or financial/Trust effect not explicitly supported by the cited PRD requirements or their v5.0 sections is out of scope for this FRS.

## F-04 — Create online booking

**Requirement coverage:** `FR-BOK-01` · `FR-BOK-02` · `FR-BOK-03` · `FR-BOK-04` · `FR-BOK-08` · `FR-BOK-09` · `FR-BOK-11` · `FR-BOK-13` · `FR-AVL-09` · `FR-AVL-10`

1. Customer selects resource, start time and nominal duration and confirms the request.
2. System checks the twenty cumulative creation conditions, including account, ban, commitment count, overlap, venue/account/Area, operating-session/intake, selling blocks, timing, resource state/restriction, reservation conflicts and compatibility.
3. Validation and commitment creation are atomic; displayed availability is not a commit guarantee.
4. On success, one Booking is created in PENDING, the resource interval is reserved and the 10-minute venue response timer begins.
5. No Auto Accept exists.

**Functional boundary:** Any branch, copy, validation, state transition, notification, audit consequence or financial/Trust effect not explicitly supported by the cited PRD requirements or their v5.0 sections is out of scope for this FRS.

## F-05 — Venue accepts a pending booking

**Requirement coverage:** `FR-BOK-14` · `FR-BOK-15` · `FR-BOK-16` · `FR-AVL-09` · `FR-AVL-10`

1. Venue staff selects Accept on an existing PENDING request.
2. System applies Existing-Commitment Operational eligibility; marketplace ACTIVE is not required.
3. System revalidates customer account/ban, venue and resource operational gates, reservation and interval atomically.
4. If valid, PENDING becomes CONFIRMED and the reservation remains held.
5. If a defined operational cause prevents confirmation, the booking follows that cause rather than venue non-response blame.

**Functional boundary:** Any branch, copy, validation, state transition, notification, audit consequence or financial/Trust effect not explicitly supported by the cited PRD requirements or their v5.0 sections is out of scope for this FRS.

## F-06 — Venue rejects a pending booking

**Requirement coverage:** `FR-BOK-17`

1. Venue staff enters a mandatory operational reject reason and confirms Reject.
2. PENDING becomes REJECTED with terminal reason VENUE_REJECTED.
3. The venue-supplied reason is stored separately from the terminal reason code.
4. The resource reservation is released immediately and required notifications/history are produced.

**Functional boundary:** Any branch, copy, validation, state transition, notification, audit consequence or financial/Trust effect not explicitly supported by the cited PRD requirements or their v5.0 sections is out of scope for this FRS.

## F-07 — Venue response expiry

**Requirement coverage:** `FR-BOK-18` · `FR-BOK-19` · `FR-BOK-20` · `FR-BOK-23`

1. At the venue response deadline, if no valid response owns the state, PENDING becomes VENUE_RESPONSE_EXPIRED.
2. The resource reservation and customer commitment slot are released; the state remains non-terminal.
3. Customer projection shows the request under Cancelled with venue-nonresponse copy while the internal Booking remains eligible for Late Confirm until startAt.
4. If still expired at startAt, the terminal reason is chosen by the frozen priority: owner review, resource emergency, venue emergency, account disabled, otherwise venue did not respond.

**Functional boundary:** Any branch, copy, validation, state transition, notification, audit consequence or financial/Trust effect not explicitly supported by the cited PRD requirements or their v5.0 sections is out of scope for this FRS.

## F-08 — Reject after expiry

**Requirement coverage:** `FR-BOK-21`

1. Before startAt, Venue staff may act on a VENUE_RESPONSE_EXPIRED request.
2. Reject After Expiry changes the booking to REJECTED with REJECTED_AFTER_EXPIRY.
3. No reservation exists to release because expiry already released it.

**Functional boundary:** Any branch, copy, validation, state transition, notification, audit consequence or financial/Trust effect not explicitly supported by the cited PRD requirements or their v5.0 sections is out of scope for this FRS.

## F-09 — Late Confirmation revival

**Requirement coverage:** `FR-LCF-01` · `FR-LCF-02` · `FR-LCF-03` · `FR-LCF-04` · `FR-LCF-06` · `FR-LCF-08` · `FR-LCF-09` · `FR-LCF-13` · `FR-LCF-14` · `FR-LCF-15` · `FR-LCF-16` · `FR-LCF-17`

1. Venue initiates Late Confirm on an eligible VENUE_RESPONSE_EXPIRED Booking.
2. System treats the action as Existing-Commitment Operational management and checks venue, resource and customer eligibility.
3. Commitment-count check, resource validation, temporary Hold creation and transition to AWAITING_CUSTOMER_CONFIRMATION occur atomically.
4. The same Booking ID and original commercial/resource snapshots are retained; expiry is MIN(now + 5 minutes, startAt).
5. Customer Accept revalidates commitment count, customer schedule, ban/account, resource and operational eligibility then moves to CONFIRMED.
6. Customer Decline cancels under CUSTOMER_DECLINED_LATE_CONFIRM; timeout cancels under CUSTOMER_RECONFIRM_TIMEOUT; both are penalty-free and use full future fee refund classification.
7. Failed accept due to customer/resource conflict records the canonical non-terminal event rather than inventing a terminal reason.

**Functional boundary:** Any branch, copy, validation, state transition, notification, audit consequence or financial/Trust effect not explicitly supported by the cited PRD requirements or their v5.0 sections is out of scope for this FRS.

## F-10 — Customer cancellation

**Requirement coverage:** `FR-BOK-31` · `FR-PTS-14` · `FR-PTS-16`

1. Customer requests cancellation while now < startAt.
2. The booking is cancelled under CUSTOMER_CANCELLED, inventory/commitment are released and there is no no-show/trust penalty.
3. After startAt, ordinary customer cancellation is disabled.
4. If a non-zero Booking Fee exists in the future, refundability follows the frozen 15-minute-from-creation and before-start rule.

**Functional boundary:** Any branch, copy, validation, state transition, notification, audit consequence or financial/Trust effect not explicitly supported by the cited PRD requirements or their v5.0 sections is out of scope for this FRS.

## F-11 — Booking start-time transition

**Requirement coverage:** `FR-BOK-24` · `FR-BOK-25` · `FR-BOK-26` · `FR-BOK-27` · `FR-BOK-28`

1. At startAt, System revalidates operational gates immediately before CONFIRMED → ACTIVE.
2. If all gates pass, the Booking becomes ACTIVE automatically.
3. If a gate fails, the Booking cannot remain CONFIRMED past startAt; it terminates under exactly one frozen reason according to priority.
4. Resource-state anomalies that should have been impossible use SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR, with no customer penalty and the required admin alert.

**Functional boundary:** Any branch, copy, validation, state transition, notification, audit consequence or financial/Trust effect not explicitly supported by the cited PRD requirements or their v5.0 sections is out of scope for this FRS.

## F-12 — Check-in

**Requirement coverage:** `FR-ATT-01` · `FR-ATT-02`

1. Venue staff records Check-in only while startAt <= now < startAt + 15 minutes.
2. At exactly +15 minutes Check-in is refused.
3. The action needs no Owner Password and is audited as operational evidence.
4. Check-in affects final Trust only through the later canonical attendance outcome.

**Functional boundary:** Any branch, copy, validation, state transition, notification, audit consequence or financial/Trust effect not explicitly supported by the cited PRD requirements or their v5.0 sections is out of scope for this FRS.

## F-13 — Mark Present — Late

**Requirement coverage:** `FR-ATT-03`

1. From startAt + 15 minutes until before endAt, Venue staff may mark the customer present if no valid Check-in and no committed No-show exist.
2. The evidence blocks No-show for the Booking.
3. It grants no points, no Verified Player progress and no counter reset by itself; the final outcome is resolved under the attendance rules.

**Functional boundary:** Any branch, copy, validation, state transition, notification, audit consequence or financial/Trust effect not explicitly supported by the cited PRD requirements or their v5.0 sections is out of scope for this FRS.

## F-14 — No-show

**Requirement coverage:** `FR-ATT-04` · `FR-ATT-05` · `FR-ATT-06`

1. From startAt + 15 minutes until before endAt, Venue staff may commit No-show only if no Check-in and no Late Present evidence exists.
2. The Booking becomes terminal status NO_SHOW; the `NO_SHOW_RECORDED` Booking Event is written and the default Attendance Result is ABSENT_NO_SHOW. The remaining resource interval is released immediately.
3. Venue staff cannot undo a committed No-show; Platform Admin correction is required.
4. A later reversal never evicts a new reservation that validly used the released interval.

**Functional boundary:** Any branch, copy, validation, state transition, notification, audit consequence or financial/Trust effect not explicitly supported by the cited PRD requirements or their v5.0 sections is out of scope for this FRS.

## F-15 — Booking completion and attendance resolution

**Requirement coverage:** `FR-BOK-24` · `FR-ATT-07` · `FR-ATT-08` · `FR-TRS-01` · `FR-TRS-02`

1. At endAt, an ACTIVE Booking that has not been operationally terminated becomes COMPLETED automatically.
2. Attendance Result is resolved independently from Booking Status using the frozen evidence/default table.
3. The resulting canonical Trust input, points, progress and no-show-counter effect are applied once according to corrected history.

**Functional boundary:** Any branch, copy, validation, state transition, notification, audit consequence or financial/Trust effect not explicitly supported by the cited PRD requirements or their v5.0 sections is out of scope for this FRS.

## F-16 — Create Manual Booking

**Requirement coverage:** `FR-MAN-01` · `FR-MAN-02` · `FR-MAN-03` · `FR-MAN-04` · `FR-MAN-05` · `FR-MAN-06` · `FR-MAN-09` · `FR-MAN-10` · `FR-VEN-45`

1. Venue staff creates a NOW or FUTURE internal booking; past backdating is not allowed.
2. Venue Account must be ENABLED and Marketplace may be ACTIVE, INACTIVE or SUSPENDED, never ONBOARDING.
3. System applies the same resource conflict and operational restrictions as other reservation flows; published Opening Hours do not gate Manual Booking.
4. Required pricing configuration must exist or creation fails before any Booking/reservation record.
5. Future Manual starts CONFIRMED; current walk-in starts ACTIVE.
6. Manual Booking has attendance NOT_APPLICABLE, no Trust/points/progress/no-show effect, and always stores businessDate under the frozen rule.
7. Venue staff may cancel a CONFIRMED or ACTIVE Manual Booking under `VENUE_CANCELLED_MANUAL_BOOKING`, without the Owner Password. On an ACTIVE one: status ACTIVE → CANCELLED, the room is free from the cancel instant (reservation released at the command instant), the scheduled `BOOKING_COMPLETE` job is cancelled, Attendance Result remains NOT_APPLICABLE, fee treatment NOT_APPLICABLE, no Trust/points effect. An ACTIVE Manual Booking may also end early through a defined operational/system interruption; that interruption uses its actual operational reason and Attendance Result remains NOT_APPLICABLE. *(Amended by PCR-007.)*

**Functional boundary:** Any branch, copy, validation, state transition, notification, audit consequence or financial/Trust effect not explicitly supported by the cited PRD requirements or their v5.0 sections is out of scope for this FRS.

## F-17 — Opening Hours change

**Requirement coverage:** `FR-VEN-17` · `FR-VEN-18` · `FR-VEN-19` · `FR-VEN-20` · `FR-VEN-23` · `FR-VEN-24` · `FR-VEN-25` · `FR-VEN-26` · `FR-VEN-27` · `FR-VEN-28`

1. Venue edits Default or per-day Opening Hours and requests Save.
2. System validates the cyclic weekly session model and evaluates affected reservations in the frozen order.
3. Tournament conflict blocks the save.
4. If protected Customer Bookings would be cancelled, the final save requires Owner Password; otherwise it does not.
5. The change and its affected set commit atomically; affected bookings use VENUE_OPENING_HOURS_CHANGED and required notifications/refunds.
6. ACTIVE bookings are not auto-cancelled merely by an ordinary Hours edit.

**Functional boundary:** Any branch, copy, validation, state transition, notification, audit consequence or financial/Trust effect not explicitly supported by the cited PRD requirements or their v5.0 sections is out of scope for this FRS.

## F-18 — Maintenance

**Requirement coverage:** `FR-OPS-01` · `FR-OPS-02` · `FR-OPS-03` · `FR-OPS-04` · `FR-OPS-05`

1. Venue creates/edits/deletes resource Maintenance.
2. Maintenance may not overlap an existing blocking reservation and never silently cancels one.
3. Maintenance exceeding more than 24 hours inside a rolling 48-hour window for the resource requires Owner Password; emergency-created Maintenance counts toward the same total.
4. All maintenance changes are audited and affect canonical availability.

**Functional boundary:** Any branch, copy, validation, state transition, notification, audit consequence or financial/Trust effect not explicitly supported by the cited PRD requirements or their v5.0 sections is out of scope for this FRS.

## F-19 — Venue Emergency Closure

**Requirement coverage:** `FR-OPS-06` · `FR-OPS-07` · `FR-OPS-08` · `FR-OPS-09` · `FR-OPS-10` · `FR-OPS-44`

1. Venue staff declares an Emergency Closure starting NOW with mandatory reason and expected end; no Owner Password is required.
2. System turns on the venue operational block and atomically applies the defined cascade to overlapping commitments.
3. PENDING/AWAITING/CONFIRMED and affected tournaments are cancelled; ACTIVE sessions are operationally interrupted under the emergency reason.
4. An AWAITING booking caught by the cascade uses the emergency reason immediately; CUSTOMER_RECONFIRM_TIMEOUT must not win.
5. Ending an emergency early never resurrects already-cancelled commitments.

**Functional boundary:** Any branch, copy, validation, state transition, notification, audit consequence or financial/Trust effect not explicitly supported by the cited PRD requirements or their v5.0 sections is out of scope for this FRS.

## F-20 — Resource Emergency Outage

**Requirement coverage:** `FR-OPS-11` · `FR-OPS-12` · `FR-OPS-13` · `FR-OPS-14` · `FR-OPS-20` · `FR-OPS-21`

1. Venue staff declares an outage on one resource, with mandatory reason and expected return time; no Owner Password is required for the emergency declaration.
2. Only the affected resource is blocked; other resources continue normally.
3. Affected commitments follow the frozen operational cascade, including ACTIVE interruption and tournament resource handling.
4. Emergency-generated maintenance and later conversion/end behavior follow the resource-emergency lifecycle; repeated tournament cancellations may raise the non-punitive admin risk alert.

**Functional boundary:** Any branch, copy, validation, state transition, notification, audit consequence or financial/Trust effect not explicitly supported by the cited PRD requirements or their v5.0 sections is out of scope for this FRS.

## F-21 — Emergency timer pause, resume and cap

**Requirement coverage:** `FR-OPS-34` · `FR-OPS-35` · `FR-OPS-36` · `FR-OPS-37` · `FR-OPS-38` · `FR-OPS-39` · `FR-OPS-40` · `FR-OPS-41`

1. Relevant venue/resource emergencies pause the PENDING response timer or AWAITING Late Confirm countdown only under the frozen affected-scope rules.
2. Remaining duration is preserved; repeated pauses accumulate rather than reset.
3. Venue-response and Late-Confirm pauses each have their own 30-minute cumulative cap.
4. On cap, the Booking cancels under the specific interrupted-by-emergency reason and releases reservation/hold and commitment with no venue blame or customer penalty.
5. Response timer resumes only after all five response-resumption conditions hold; Late Confirm resumes only while the booking remains before startAt and all parties/resources remain eligible.
6. The optimistic feasibility test may cancel a paused response early only when no valid future intake window can fit the remaining response duration even assuming immediate block resolution.
7. Customer may Decline a paused Late Confirm immediately; pause history is immutable.

**Functional boundary:** Any branch, copy, validation, state transition, notification, audit consequence or financial/Trust effect not explicitly supported by the cited PRD requirements or their v5.0 sections is out of scope for this FRS.

## F-22 — OWNER_REVIEW_REQUIRED lifecycle

**Requirement coverage:** `FR-OPS-22` · `FR-OPS-23` · `FR-OPS-24` · `FR-OPS-25` · `FR-OPS-26` · `FR-OPS-27` · `FR-OPS-28` · `FR-OPS-29` · `FR-OPS-30` · `FR-OPS-31` · `FR-OPS-32` · `FR-OPS-33`

1. When the rolling maintenance threshold logic requires owner review at emergency resolution, the resource enters OWNER_REVIEW_REQUIRED rather than silently returning to sale.
2. System applies the owner-review cascade atomically to affected commitments; it is a system operational consequence and needs no Owner Password to cancel them.
3. The restriction is not a pause state and blocks new sellable/commit flows and Late Confirm on the resource.
4. Resolution itself requires Owner Password and offers Return to Service, Extend Maintenance, Disable or Archive under the frozen prerequisites.

**Functional boundary:** Any branch, copy, validation, state transition, notification, audit consequence or financial/Trust effect not explicitly supported by the cited PRD requirements or their v5.0 sections is out of scope for this FRS.

## F-23 — Venue Account Disable

**Requirement coverage:** `FR-ATT-11` · `FR-ATT-12` · `FR-ATT-13` · `FR-TRN-29` · `FR-TRN-30` · `FR-ADM-03`

1. Platform Admin disables a Venue Account without changing Marketplace Status or deleting venue data.
2. Dashboard operational actions stop. PENDING response is interrupted into VENUE_RESPONSE_EXPIRED with a non-terminal account-disable event and no venue blame; AWAITING cancels under VENUE_ACCOUNT_DISABLED.
3. Existing CONFIRMED and ACTIVE commitments are not cancelled solely by Account Disable; their operational lifecycle continues where deliverable.
4. A Tournament still PENDING_VENUE_APPROVAL cancels immediately under tournament reason VENUE_ACCOUNT_DISABLED and releases approval holds and creator pending slot.
5. Re-enable restores Dashboard capability prospectively; it does not resurrect cancelled commitments or notify customers by itself.

**Functional boundary:** Any branch, copy, validation, state transition, notification, audit consequence or financial/Trust effect not explicitly supported by the cited PRD requirements or their v5.0 sections is out of scope for this FRS.

## F-24 — Platform Attendance Protection

**Requirement coverage:** `FR-ATT-14` · `FR-ATT-15` · `FR-ATT-16` · `FR-ATT-17` · `FR-ATT-18` · `FR-ATT-19` · `FR-ATT-20` · `FR-ATT-21` · `FR-ATT-22` · `FR-ATT-23` · `FR-ATT-24` · `FR-ATT-25` · `FR-ATT-26`

1. When Account Disable overlaps an ACTIVE Booking, the system records access-restriction intervals and evaluates protection only when effective attendance is resolved.
2. Protection may apply to normal completion or eligible early operational termination where the platform restriction materially prevented reliable attendance recording and no authoritative disqualifying outcome exists.
3. The protection Trust event has effectiveAt = booking.startAt, occurredAt = attendance-resolution time and at most one effective event per Booking.
4. Protection resets the consecutive no-show counter but never creates attendance, points or Verified Player progress and does not alter Booking Status/Attendance Result.
5. Later Admin attendance correction deterministically supersedes/neutralizes the protection effect where appropriate.

**Functional boundary:** Any branch, copy, validation, state transition, notification, audit consequence or financial/Trust effect not explicitly supported by the cited PRD requirements or their v5.0 sections is out of scope for this FRS.

## F-25 — Player-hosted Tournament

**Requirement coverage:** `FR-TRN-01` · `FR-TRN-02` · `FR-TRN-03` · `FR-TRN-04` · `FR-TRN-05` · `FR-TRN-06` · `FR-TRN-07` · `FR-TRN-08` · `FR-TRN-09` · `FR-TRN-10` · `FR-TRN-33`

1. Only an operationally VERIFIED player may create a player-hosted tournament, at least two hours before start and subject to the one pending approval request platform-wide rule.
2. Creator selects activity, duration, 1–4 eligible resources and public/private participation settings under the frozen rules.
3. System creates PENDING_VENUE_APPROVAL plus temporary approval holds; the tournament is not public/joinable yet.
4. Venue approval/rejection uses the frozen approval window and revalidation.
5. On approval it becomes WAITING_FOR_PLAYERS and uses Tournament Holds; on rejection/expiry it terminates under the proper reason and releases holds.

**Functional boundary:** Any branch, copy, validation, state transition, notification, audit consequence or financial/Trust effect not explicitly supported by the cited PRD requirements or their v5.0 sections is out of scope for this FRS.

## F-26 — Venue-hosted Tournament

**Requirement coverage:** `FR-TRN-01` · `FR-TRN-11` · `FR-TRN-12`

1. Eligible Venue creates a tournament directly as WAITING_FOR_PLAYERS; Verified Player creator qualification does not apply.
2. Resources are validated/held using the canonical tournament resource rules.
3. Public/private and participant rules then follow the shared tournament lifecycle.

**Functional boundary:** Any branch, copy, validation, state transition, notification, audit consequence or financial/Trust effect not explicitly supported by the cited PRD requirements or their v5.0 sections is out of scope for this FRS.

## F-27 — Tournament checkpoint

**Requirement coverage:** `FR-TRN-13` · `FR-TRN-14` · `FR-TRN-15` · `FR-TRN-16` · `FR-TRN-26`

1. At exactly T-1 hour, System first applies any BAN-only participant restoration that was already effective at the checkpoint boundary, then counts **Active Participants** and revalidates the tournament/resources under Existing-Commitment Operational eligibility.
2. BAN-only `INELIGIBLE` participants whose Ban is still effective at the checkpoint do **not** count toward the minimum even though their pre-start capacity slot remains reserved. `ACCOUNT_DELETED` participants count toward neither the minimum nor capacity occupancy.
3. At least 8 active participants moves WAITING_FOR_PLAYERS to CONFIRMED.
4. Fewer than 8 cancels under MINIMUM_PARTICIPANTS_NOT_REACHED and releases resource holds.
5. After a successful checkpoint, later participant count falling below 8 does not auto-cancel the tournament.

**Functional boundary:** Any branch, copy, validation, state transition, notification, audit consequence or financial/Trust effect not explicitly supported by the cited PRD requirements or their v5.0 sections is out of scope for this FRS.

## F-28 — Tournament start and completion

**Requirement coverage:** `FR-TRN-17` · `FR-TRN-18` · `FR-TRN-19`

1. At startAt, a CONFIRMED tournament that remains operationally eligible becomes ACTIVE automatically.
2. At endAt, an ACTIVE tournament completes unless already operationally cancelled.
3. Tournament venue cost/revenue uses stored informational values and is recognized only on COMPLETED under the frozen rule.

**Functional boundary:** Any branch, copy, validation, state transition, notification, audit consequence or financial/Trust effect not explicitly supported by the cited PRD requirements or their v5.0 sections is out of scope for this FRS.

## F-29 — Tournament operational interruption/resource loss

**Requirement coverage:** `FR-TRN-20` · `FR-TRN-21` · `FR-TRN-22` · `FR-TRN-23` · `FR-TRN-31` · `FR-TRN-32`

1. Operational resource loss while ACTIVE removes only the affected resource first.
2. If one or more operational assigned resources remain, the Tournament stays ACTIVE and participants are notified of resource availability change.
3. If zero operational resources remain, the Tournament is cancelled under the applicable frozen operational reason.
4. Normal venue Operational Termination is distinct, Owner-Password protected and uses TOURNAMENT_OPERATIONAL_TERMINATION.
5. Exactly one terminal Tournament reason is selected under the canonical priority.

**Functional boundary:** Any branch, copy, validation, state transition, notification, audit consequence or financial/Trust effect not explicitly supported by the cited PRD requirements or their v5.0 sections is out of scope for this FRS.

## F-30 — Trust replay

**Requirement coverage:** `FR-TRS-01` · `FR-TRS-02` · `FR-TRS-03` · `FR-TRS-04` · `FR-TRS-05` · `FR-TRS-06` · `FR-TRS-07`

1. System stores immutable Trust input events and orders replay by effectiveAt ASC then eventSequence ASC.
2. Derived counter, penalty stage, ban qualification and Verified Player qualification/status effects are recalculated from corrected history rather than treated as authoritative counters.
3. As-of replay must reproduce the state at a requested historical point.
4. Only the canonical outcome/correction/protection event types affect Trust; raw operational evidence does not directly reward or penalize.

**Functional boundary:** Any branch, copy, validation, state transition, notification, audit consequence or financial/Trust effect not explicitly supported by the cited PRD requirements or their v5.0 sections is out of scope for this FRS.

## F-31 — Attendance correction

**Requirement coverage:** `FR-TRS-08` · `FR-TRS-09` · `FR-TRS-10` · `FR-TRS-11` · `FR-TRS-12`

1. Platform Admin records an attendance correction with reason and corrected canonical outcome.
2. Correction is compensating/append-only and becomes the authoritative latest non-superseded outcome for Trust replay.
3. Prior events remain visible in history and rewards/penalties are recalculated without compounding.
4. Corrections may reverse or create downstream Ban/Verified Player effects according to replay, but never resurrect released inventory.

**Functional boundary:** Any branch, copy, validation, state transition, notification, audit consequence or financial/Trust effect not explicitly supported by the cited PRD requirements or their v5.0 sections is out of scope for this FRS.

## F-32 — Ban qualification, activation, extension and expiry

**Requirement coverage:** `FR-TRS-13` · `FR-TRS-14` · `FR-TRS-15` · `FR-TRS-16` · `FR-TRS-17` · `FR-TRS-18` · `FR-TRS-19` · `FR-TRS-20` · `FR-TRS-21` · `FR-TRS-22` · `FR-TRS-23`

1. Eligible no-shows increment the consecutive counter and reset events reset it according to the canonical Trust catalogue.
2. When a stage threshold is reached, a Ban Qualification exists at the Trust position and an operational Ban Instance activates now; retroactive qualification never backdates operational punishment.
3. Activation applies the booking/tournament cascade defined for a banned customer and resets the no-show counter.
4. Only one ACTIVE Ban Instance may exist; another qualification while active advances stage and extends the same instance to MAX(currentEndsAt, now + newStageDuration), with BAN_EXTENDED audit.
5. At endsAt the Ban expires unless lifted/corrected earlier; historical qualification and operational-ban as-of queries remain distinct.

**Functional boundary:** Any branch, copy, validation, state transition, notification, audit consequence or financial/Trust effect not explicitly supported by the cited PRD requirements or their v5.0 sections is out of scope for this FRS.

## F-33 — Verified Player qualification, revoke and restore

**Requirement coverage:** `FR-ACC-11` · `FR-ACC-12` · `FR-ACC-13` · `FR-ACC-14` · `FR-ACC-15` · `FR-ACC-18` · `FR-TRS-24` · `FR-TRS-25` · `FR-TRS-26`

1. Qualification is derived from corrected Trust history after 10 qualifying completed attended bookings.
2. First qualification creates an operational transition to VERIFIED at processing time, never backdated.
3. Loss of qualification through Trust correction moves operational status from VERIFIED to REVOKED and triggers the defined pending/waiting tournament cascade.
4. If REVOKED solely because of TRUST_CORRECTION and qualification is later regained, System automatically restores VERIFIED with a new transition ID.
5. Admin SUSPENDED/REVOKED cannot be automatically overridden; Admin Restore requires current qualification to return VERIFIED.

**Functional boundary:** Any branch, copy, validation, state transition, notification, audit consequence or financial/Trust effect not explicitly supported by the cited PRD requirements or their v5.0 sections is out of scope for this FRS.

## F-34 — Points earning and reversal

**Requirement coverage:** `FR-PTS-01` · `FR-PTS-02` · `FR-PTS-03` · `FR-PTS-04` · `FR-PTS-05`

1. Qualifying completed attended bookings grant the configured historical points amount once; non-qualifying outcomes grant zero.
2. Points use an append-only ledger and balance never falls below zero.
3. Reversal/adjustment records requested deduction, applied deduction and unrecovered amount; unrecovered amount is audit information, not future debt.

**Functional boundary:** Any branch, copy, validation, state transition, notification, audit consequence or financial/Trust effect not explicitly supported by the cited PRD requirements or their v5.0 sections is out of scope for this FRS.

## F-35 — Referral reward and invalidation

**Requirement coverage:** `FR-PTS-06` · `FR-PTS-07` · `FR-PTS-08` · `FR-PTS-09`

1. Referral qualifies only under the frozen OTP/signup rule and may reward once per referred user.
2. Reward configuration is read at OTP verification and the historical applied value is stored.
3. Self-referral is hard-blocked by normalized phone/email; device/IP are fraud signals only in MVP.
4. Invalidation changes referral state to INVALID and writes an exact compensating points transaction subject to the zero-floor rule.

**Functional boundary:** Any branch, copy, validation, state transition, notification, audit consequence or financial/Trust effect not explicitly supported by the cited PRD requirements or their v5.0 sections is out of scope for this FRS.

## F-36 — Review creation and moderation

**Requirement coverage:** `FR-REV-01` · `FR-REV-02` · `FR-REV-03` · `FR-REV-04` · `FR-REV-05` · `FR-REV-06` · `FR-REV-07` · `FR-REV-08` · `FR-REV-09` · `FR-REV-10` · `FR-REV-11`

1. Any Account-Verified user may review a venue without a completed booking, one current logical review per venue/user.
2. Normal eligible review starts ACTIVE except the 30-day post-admin-removal re-entry flow, which starts PENDING_MODERATION and requires Admin approval.
3. Admin may hide, restore hidden, remove or approve pending reviews under the frozen state rules; REMOVED is terminal and cannot be restored.
4. Public display uses current Username and only public states contribute to public content/rating under the frozen rule.
5. Venue may view reviews but has no moderation authority.
6. A signed-in Customer may report a public Review visible to them with a mandatory reason and optional details; reporting records the report for Platform Admin and does not change Review state by itself.

**Functional boundary:** Any branch, copy, validation, state transition, notification, audit consequence or financial/Trust effect not explicitly supported by the cited PRD requirements or their v5.0 sections is out of scope for this FRS.

## F-37 — Platform Admin correction and marketplace control

**Requirement coverage:** `FR-ADM-01` · `FR-ADM-02` · `FR-ADM-03` · `FR-ADM-04` · `FR-ADM-05` · `FR-ADM-06` · `FR-ADM-07` · `FR-ADM-08` · `FR-ADM-09` · `FR-ADM-10` · `FR-ADM-11` · `FR-AUD-01` · `FR-AUD-02` · `FR-AUD-03`

1. Platform Admin performs onboarding, status/account control, moderation, global booking/tournament inspection, trust remediation, points/referral controls and global configuration only through the frozen Admin surfaces/actions.
2. Admin actions that materially alter customer state are audited and notified according to the canonical catalogues.
3. Historical snapshots and append-only histories remain immutable; corrections are explicit compensating actions, not silent rewrites.
4. Global configuration changes affect future events only unless the Product specification explicitly states another behavior.

**Functional boundary:** Any branch, copy, validation, state transition, notification, audit consequence or financial/Trust effect not explicitly supported by the cited PRD requirements or their v5.0 sections is out of scope for this FRS.

---

# 2. FUNCTIONAL STATE, AUTHORIZATION AND TIMER MATRICES

## 2.1 Booking transition matrix

| From | Trigger / action | To | Reason / event | Inventory / commitment effect |
|---|---|---|---|---|
| — | Customer creates valid Online Booking | PENDING | — | Reserve resource; consumes commitment |
| PENDING | Venue Accept | CONFIRMED | — | Reservation/commitment preserved |
| PENDING | Venue Reject | REJECTED | `VENUE_REJECTED` | Release resource and commitment |
| PENDING | Venue response deadline | VENUE_RESPONSE_EXPIRED | non-terminal expiry | Release resource and commitment |
| PENDING | Venue Account disabled | VENUE_RESPONSE_EXPIRED | Event `VENUE_RESPONSE_INTERRUPTED_BY_ACCOUNT_DISABLE` | Release resource and commitment; response timer stops |
| PENDING | Customer cancellation before Start | CANCELLED | `CUSTOMER_CANCELLED` | Release resource and commitment |
| PENDING | Ban cascade | CANCELLED | `USER_BANNED` | Release resource and commitment |
| PENDING | Opening Hours change | CANCELLED | `VENUE_OPENING_HOURS_CHANGED` | Release resource and commitment |
| PENDING | Normal Venue Closure | CANCELLED | `VENUE_CLOSURE` | Release resource and commitment |
| PENDING | Overlapping Venue Emergency cascade | CANCELLED | `VENUE_EMERGENCY_CLOSURE` | Release resource and commitment |
| PENDING | Affected Resource Emergency cascade | CANCELLED | `RESOURCE_EMERGENCY_OUTAGE` | Release resource and commitment |
| PENDING | OWNER_REVIEW_REQUIRED cascade | CANCELLED | `RESOURCE_OWNER_REVIEW_REQUIRED` | Release resource and commitment |
| PENDING | Emergency response-pause cap / optimistic-feasibility cancellation | CANCELLED | `VENUE_RESPONSE_INTERRUPTED_BY_EMERGENCY` or `RESOURCE_RESPONSE_INTERRUPTED_BY_EMERGENCY` | Release resource and commitment |
| PENDING | Platform Admin cancellation/correction | CANCELLED | Applicable Admin reason | Release resource and commitment |
| VENUE_RESPONSE_EXPIRED | Venue Reject After Expiry | REJECTED | `REJECTED_AFTER_EXPIRY` | No held reservation |
| VENUE_RESPONSE_EXPIRED | Venue initiates valid Late Confirm | AWAITING_CUSTOMER_CONFIRMATION | — | Create temporary hold; consumes commitment |
| VENUE_RESPONSE_EXPIRED | Original Start reached | CANCELLED | Priority §24.3 | No held reservation |
| AWAITING_CUSTOMER_CONFIRMATION | Customer Accept | CONFIRMED | — | Temporary hold becomes/continues as reservation; commitment preserved |
| AWAITING_CUSTOMER_CONFIRMATION | Customer Decline | CANCELLED | `CUSTOMER_DECLINED_LATE_CONFIRM` | Release hold and commitment |
| AWAITING_CUSTOMER_CONFIRMATION | Confirmation timeout | CANCELLED | `CUSTOMER_RECONFIRM_TIMEOUT` | Release hold and commitment |
| AWAITING_CUSTOMER_CONFIRMATION | Venue Account disabled | CANCELLED | `VENUE_ACCOUNT_DISABLED` | Release hold and commitment |
| AWAITING_CUSTOMER_CONFIRMATION | Ban cascade | CANCELLED | `USER_BANNED` | Release hold and commitment |
| AWAITING_CUSTOMER_CONFIRMATION | Opening Hours / Closure / Emergency / OWNER_REVIEW_REQUIRED cascade | CANCELLED | Applicable closed operational reason | Release hold and commitment |
| AWAITING_CUSTOMER_CONFIRMATION | Late Confirm emergency-pause cap | CANCELLED | `LATE_CONFIRM_INTERRUPTED_BY_EMERGENCY` | Release hold and commitment |
| AWAITING_CUSTOMER_CONFIRMATION | Platform Admin cancellation/correction | CANCELLED | Applicable Admin reason | Release hold and commitment |
| CONFIRMED | `startAt` + all start gates pass | ACTIVE | — | Reservation remains blocking |
| CONFIRMED | Customer cancellation before Start | CANCELLED | `CUSTOMER_CANCELLED` | Release resource and commitment |
| CONFIRMED | Venue cancellation with Owner Password | CANCELLED | `VENUE_CANCELLED_CONFIRMED_BOOKING` | Release resource and commitment |
| CONFIRMED | Ban cascade | CANCELLED | `USER_BANNED` | Release resource and commitment |
| CONFIRMED | Opening Hours / Closure / Emergency / OWNER_REVIEW_REQUIRED / start-time integrity failure | CANCELLED | Applicable closed reason under frozen priority | Release resource and commitment |
| CONFIRMED | Platform Admin cancellation/correction | CANCELLED | Applicable Admin reason | Release resource and commitment |
| ACTIVE | Normal `endAt` | COMPLETED | — | Reservation releases at end; Attendance resolves |
| ACTIVE or scheduler-race CONFIRMED | Venue records No-show inside the No-show window | NO_SHOW | Event `NO_SHOW_RECORDED`; default Attendance `ABSENT_NO_SHOW` | Release remaining interval |
| ACTIVE | Venue Emergency / Resource Emergency / OWNER_REVIEW_REQUIRED / Platform Admin early termination | CANCELLED | Priority §31.8 / applicable Admin reason | Release remaining interval; Attendance from evidence |
| NO_SHOW | Admin reverses No-show before original `endAt` | CANCELLED | `ADMIN_NO_SHOW_REVERSAL` | Never restores already released inventory |
| NO_SHOW | Admin reverses No-show after original `endAt` | COMPLETED | Attendance defaults to ATTENDANCE_UNCONFIRMED unless separately corrected | Never restores already released inventory |
| — | Future Manual Booking created | CONFIRMED | source = MANUAL | Reserve resource; no Customer commitment slot |
| — | Current Manual Booking created | ACTIVE | source = MANUAL | Reserve resource; no Customer commitment slot |
| CONFIRMED Manual, before Start | Venue cancellation | CANCELLED | `VENUE_CANCELLED_MANUAL_BOOKING` | Release resource; Booking Fee treatment NOT_APPLICABLE |
| CONFIRMED/ACTIVE Manual | Defined system operational interruption | CANCELLED | Applicable operational reason | Release resource; Attendance NOT_APPLICABLE |
| ACTIVE Manual | Normal `endAt` | COMPLETED | — | Release resource; Attendance NOT_APPLICABLE |

## 2.2 Tournament transition matrix

| From | Trigger / action | To | Reason / effect |
|---|---|---|---|
| — | Valid Player-hosted request | PENDING_VENUE_APPROVAL | Approval Holds created; creator pending slot consumed |
| PENDING_VENUE_APPROVAL | Venue accepts inside approval window | WAITING_FOR_PLAYERS | Approval Holds become Tournament Holds |
| PENDING_VENUE_APPROVAL | Venue rejects | REJECTED | `VENUE_REJECTED_TOURNAMENT`; release Holds |
| PENDING_VENUE_APPROVAL | Approval window expires | CANCELLED | `VENUE_DID_NOT_APPROVE_TOURNAMENT_IN_TIME`; release Holds |
| PENDING_VENUE_APPROVAL | Venue Account disabled | CANCELLED | `VENUE_ACCOUNT_DISABLED`; release Holds and creator slot; no 5-minute cooldown |
| PENDING_VENUE_APPROVAL | Creator cancels before checkpoint | CANCELLED | `CREATOR_CANCELLED`; release Holds and creator slot |
| PENDING_VENUE_APPROVAL | Creator Verified status revoked / qualification lost by Trust correction | CANCELLED | `CREATOR_VERIFIED_STATUS_REVOKED`; cascade once per transition ID |
| PENDING_VENUE_APPROVAL | Venue/Resource emergency or OWNER_REVIEW_REQUIRED makes assigned Resource unavailable | CANCELLED | Applicable operational reason; release Holds |
| PENDING_VENUE_APPROVAL | Platform Admin cancels | CANCELLED | `ADMIN_CANCELLED`; release Holds and creator slot |
| — | Valid Venue-hosted create | WAITING_FOR_PLAYERS | Tournament Holds created |
| WAITING_FOR_PLAYERS | Creator cancels before checkpoint (Player-hosted) | CANCELLED | `CREATOR_CANCELLED`; release Holds |
| WAITING_FOR_PLAYERS | Venue cancels | CANCELLED | `VENUE_CANCELLED`; no Owner Password |
| WAITING_FOR_PLAYERS | Creator Verified status revoked / qualification lost by Trust correction | CANCELLED | `CREATOR_VERIFIED_STATUS_REVOKED` |
| WAITING_FOR_PLAYERS | Venue/Resource emergency or OWNER_REVIEW_REQUIRED prevents fulfilment | CANCELLED | Applicable operational reason; release Holds |
| WAITING_FOR_PLAYERS | Platform Admin cancels | CANCELLED | `ADMIN_CANCELLED`; release Holds |
| WAITING_FOR_PLAYERS | Checkpoint has >=8 Active Participants and all revalidation passes | CONFIRMED | Resource eligibility revalidated; Holds preserved |
| WAITING_FOR_PLAYERS | Checkpoint has <8 Active Participants | CANCELLED | `MINIMUM_PARTICIPANTS_NOT_REACHED`; release Holds |
| CONFIRMED | `startAt` + all operational/resource checks pass | ACTIVE | Assigned Resources remain committed |
| CONFIRMED | Venue cancels | CANCELLED | `VENUE_CANCELLED`; Owner Password required |
| CONFIRMED | Venue/Resource emergency or OWNER_REVIEW_REQUIRED prevents fulfilment | CANCELLED | Applicable operational reason |
| CONFIRMED | Platform Admin cancels | CANCELLED | `ADMIN_CANCELLED` |
| CONFIRMED | Creator tries to cancel | CONFIRMED | Not permitted through E7GZ after checkpoint |
| CONFIRMED | Venue Account disabled alone, while operationally deliverable | CONFIRMED | No state change |
| ACTIVE | Normal `endAt` | COMPLETED | Stored informational Venue Cost becomes recognized Dashboard tournament revenue |
| ACTIVE | Venue Emergency Closure | CANCELLED | `EMERGENCY_CLOSURE` |
| ACTIVE | Resource loss with >=1 operational assigned Resource remaining | ACTIVE | Failed Resource removed; Creator/Participants notified; stored Venue Cost unchanged |
| ACTIVE | Resource loss leaves zero operational assigned Resources | CANCELLED | `RESOURCE_EMERGENCY_OUTAGE` or `RESOURCE_OWNER_REVIEW_REQUIRED` as applicable |
| ACTIVE | Tournament Operational Termination | CANCELLED | `TOURNAMENT_OPERATIONAL_TERMINATION`; Owner Password required |
| ACTIVE | Platform Admin cancellation/correction | CANCELLED | `ADMIN_CANCELLED` |
| ACTIVE | Venue Account disabled alone, while operationally deliverable | ACTIVE | No state change |

## 2.3 Eligibility matrix

| Category | Examples | Marketplace ACTIVE required? | Venue Account | Core rule |
|---|---|---|---|---|
| New-Activity Marketplace | New Online Booking, new Player-hosted Tournament request, new Venue-hosted Tournament | Yes | ENABLED where a venue action is required | Creates a new marketplace commitment. |
| Existing-Commitment Operational | Venue Accept, Late Confirm, Tournament checkpoint/start, Booking start, management of existing commitments | No | Must satisfy the specific action rule | INACTIVE/SUSPENDED alone does not prevent honouring an existing commitment. |
| Internal Venue Operation | Manual Booking and internal resource scheduling/management | No | ENABLED | Manual Booking allowed for ACTIVE/INACTIVE/SUSPENDED; never ONBOARDING. |

## 2.4 Owner Password authorization

| Rule | Actions |
|---|---|
| Owner Password required | Venue pricing changes; room override set/reset; cancel CONFIRMED Online Booking; Hours/Closure save cancelling protected bookings; resource disable/re-enable/archive/restore; PS console change; protected resource configuration; over-threshold maintenance; venue cancel CONFIRMED Tournament; Tournament Operational Termination; resolve OWNER_REVIEW_REQUIRED |
| Owner Password NOT required | Accept/Reject/Reject-after-expiry; Late Confirm; Create Manual Booking; cancel a Manual Booking **before Start**; Check-in; Late Present; No-show; under-threshold Maintenance; Hours/Closure with no protected cancellation; Emergency Closure; Resource Emergency Outage; cancel WAITING_FOR_PLAYERS Tournament; system operational cascades |
| Per-action mechanics | Every protected action validates Owner Password afresh; no unlock/session mode; success and failure audited; exact rate limit is Security Architecture latitude. |

## 2.5 Timer and boundary matrix

| Timer / boundary | Starts / applies | Frozen rule | Pause / resume | Outcome / functional effect |
|---|---|---|---|---|
| New Online Booking lead time | New request | `now <= startAt - 15 min`; exactly 15 min allowed | Not pausable | Reject request if less than 15 minutes ahead |
| Online intake cutoff | Current Operating Session | Intake stops 10 minutes before session end | Not pausable; next session has its own intake window | New Online Booking requests blocked inside final 10 minutes |
| Venue response | Booking enters PENDING | 10 minutes | Pauses only for relevant venue emergency or affected-resource emergency; resumes only when all §44.4 conditions hold | Normal timeout → VENUE_RESPONSE_EXPIRED; 30-minute cumulative pause cap / optimistic infeasibility → interrupted-by-emergency cancellation |
| Late Confirm customer window | Booking enters AWAITING_CUSTOMER_CONFIRMATION | `MIN(now + 5 min, startAt)` | Relevant venue/resource emergency or Selling Block; resume only when resolved, before Start, and all eligibility still passes | Decline → `CUSTOMER_DECLINED_LATE_CONFIRM`; timeout → `CUSTOMER_RECONFIRM_TIMEOUT`; 30-minute cumulative pause cap → `LATE_CONFIRM_INTERRUPTED_BY_EMERGENCY` |
| Late Confirm expiring warning | Late Confirm window >60 seconds | Approximately 1 minute before expiry, once per Booking | Follows the Booking's Late Confirm timing | Suppressed entirely when total window <=60 seconds |
| Customer cancellation boundary | Eligible Online Booking | Allowed only while `now < startAt` | PENDING remains cancellable even while response timer is emergency-paused | At/after Start ordinary customer cancellation disabled |
| Customer Booking Fee refund window | Future non-zero fee + customer-caused cancellation | Within 15 minutes of Booking creation **and** before Start | Not a pausable timer | Outside the window pre-Start cancellation remains penalty-free but fee is non-refundable |
| Booking start scheduler boundary | CONFIRMED Booking | At `startAt` | Not paused; start-time operational revalidation decides | Pass → ACTIVE; fail → deterministic cancellation; cannot remain CONFIRMED past Start because a check failed |
| Check-in window | Booking Start | `startAt <= now < startAt + 15 min` | No | At exactly +15m Check-in is refused |
| Mark Present — Late window | After Check-in closes | `startAt + 15 min <= now < endAt` | No | Available only with no valid Check-in and no committed No-show |
| No-show window | After Check-in closes | `startAt + 15 min <= now < endAt` | No | Available only with no valid Check-in and no Late Present; releases remaining interval |
| Booking completion | ACTIVE Booking | At `endAt` | Not paused | If not cancelled/no-show, ACTIVE → COMPLETED and Attendance resolves |
| Venue/Resource emergency expected-end boundary | Active Emergency | Expected end/return no later than current Operating Session for an unprotected emergency | Selling Block persists until staff actually end/convert; scheduler does not predict duration | If expected end is reached while still active, operational reminder / AWAITING_CONFIRMATION rules apply |
| Maintenance owner threshold | Per Resource rolling window | More than 24 Maintenance hours inside any rolling 48 hours | Rolling accumulation; emergency-created Maintenance counts | Owner Password required; at Resource Emergency end the threshold may produce OWNER_REVIEW_REQUIRED |
| Phone visibility retention — terminal before Start | Terminal Booking before Start | Until `terminalAt + 2h` | — | Counterpart phone hidden after retention point |
| Phone visibility retention — VENUE_RESPONSE_EXPIRED | Response expiry | Until `venueResponseExpiredAt + 2h`, unless valid Late Confirm reopens visibility | Late Confirm AWAITING reopens for its period | Expiry of visibility does not make the Booking terminal |
| Phone visibility retention — started / NO_SHOW | Booking reached Start or NO_SHOW | Until original `endAt + 2h` | — | Counterpart phone hidden after retention point |
| Tournament creation cutoff | Tournament create | At least 2 hours before Start | No | Request/create refused inside the cutoff |
| Tournament Venue approval | Player-hosted request | `MIN(requestCreatedAt + 10 min, checkpointAt)` | No Product pause defined | Timeout → `VENUE_DID_NOT_APPROVE_TOURNAMENT_IN_TIME` |
| Tournament creator retry cooldown | Player-hosted rejection/normal approval expiry | 5 minutes | Venue Account Disable cancellation explicitly has no cooldown | New qualifying request blocked during cooldown where applicable |
| Tournament checkpoint | Tournament schedule | Exactly 1 hour before Start | No | >=8 active participants and valid resources → CONFIRMED; otherwise deterministic cancellation |
| Tournament join / leave boundary | WAITING/CONFIRMED participation | Allowed only while `now < startAt` under the participant rules | No | At/after Start join/leave boundary is closed |
| Tournament start / end | CONFIRMED / ACTIVE Tournament | Start at `startAt`; complete at `endAt` | Not paused | Start requires operational/resource revalidation; end → COMPLETED if not cancelled |
| OTP validity | OTP issue | 5 minutes | No | Expired OTP invalid |
| OTP resend delay | OTP send | 60 seconds | No | Resend unavailable before delay |
| OTP send-rate window | Per destination | Maximum 5 sends per hour | Rolling window | Further send refused during the window |
| Username change cooldown | Username change | 30 days | No | Second change refused during cooldown |
| Review post-removal cooldown | Admin removes Review | 30 days | No | New Review prohibited until cooldown ends; next eligible Review enters PENDING_MODERATION |
| Ban Instance expiry | Ban activation/extension | `endsAt` from the active Ban Instance and stage duration | Extension may move `endsAt` forward using frozen MAX formula | At `endsAt` status expires unless lifted/corrected earlier |

## 2.6 Booking terminal reason matrix

| Reason | Cause category | Primary producer | Booking Fee treatment when non-zero |
|---|---|---|---|
| CUSTOMER_CANCELLED | Customer | Customer pre-start cancellation | Customer cancellation policy |
| CUSTOMER_DECLINED_LATE_CONFIRM | Customer action after venue expiry | Customer declines Late Confirm | FULL |
| CUSTOMER_RECONFIRM_TIMEOUT | Customer response timeout after venue expiry | Late Confirm timer expires | FULL |
| VENUE_REJECTED | Venue | Venue rejects PENDING | FULL |
| REJECTED_AFTER_EXPIRY | Venue | Venue rejects VENUE_RESPONSE_EXPIRED | FULL |
| VENUE_CANCELLED_CONFIRMED_BOOKING | Venue | Venue cancels CONFIRMED Online Booking | FULL |
| VENUE_CANCELLED_MANUAL_BOOKING | Venue/Internal | Venue cancels CONFIRMED Manual Booking before Start | NOT_APPLICABLE |
| VENUE_DID_NOT_RESPOND_IN_TIME | Venue non-response | Expired booking reaches startAt with no overriding cause | FULL |
| VENUE_OPENING_HOURS_CHANGED | Venue configuration | Opening Hours change cascade | FULL |
| VENUE_CLOSURE | Venue operation | Normal Closure cascade | FULL |
| VENUE_EMERGENCY_CLOSURE | Venue-caused | Venue Emergency cascade / interruption | FULL |
| RESOURCE_EMERGENCY_OUTAGE | Venue-caused | Resource Emergency cascade / interruption | FULL |
| VENUE_RESPONSE_INTERRUPTED_BY_EMERGENCY | Operational | Venue-response pause cap/feasibility cancellation | FULL |
| RESOURCE_RESPONSE_INTERRUPTED_BY_EMERGENCY | Operational | Resource-response pause cap/feasibility cancellation | FULL |
| LATE_CONFIRM_INTERRUPTED_BY_EMERGENCY | Operational | Late Confirm emergency pause cap | FULL |
| RESOURCE_OWNER_REVIEW_REQUIRED | Operational | OWNER_REVIEW_REQUIRED cascade | FULL |
| VENUE_ACCOUNT_DISABLED | Platform | Account-disable lifecycle | FULL |
| SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR | System integrity | Start-time resource anomaly | FULL |
| USER_BANNED | Trust/account | Ban cascade | Customer cancellation policy |
| ADMIN_CANCELLED | Admin | Admin cancellation where customer not at fault | FULL when customer not at fault |
| ADMIN_CORRECTION | Admin | Admin correction | Context: refund only when service failure is established; customer-fault uses real code |
| ADMIN_NO_SHOW_REVERSAL | Admin | Wrongful no-show reversal | FULL only if wrongful no-show caused service loss; otherwise no refund from attendance correction alone |

## 2.7 Canonical Booking Event matrix

| Booking Event | Producer / functional use |
|---|---|
| CHECK_IN_RECORDED | Venue Check-in action |
| CHECK_IN_REVERSED | Admin/authorized reversal before completion under Product rule |
| LATE_PRESENT_RECORDED | Venue Mark Present — Late |
| LATE_PRESENT_REVERSED | Admin reversal |
| NO_SHOW_RECORDED | Venue No-show action |
| NO_SHOW_REVERSED | Platform Admin reversal |
| ATTENDANCE_CORRECTED | Platform Admin attendance correction |
| LATE_CONFIRM_FAILED_RESOURCE_CONFLICT | Late Confirm Accept/initiation resource conflict |
| LATE_CONFIRM_FAILED_CUSTOMER_CONFLICT | Customer accepts Late Confirm with schedule conflict |
| LATE_CONFIRM_FAILED_BOOKING_LIMIT | Late Confirm initiation blocked by 4-commitment limit |
| LATE_CONFIRM_ELIGIBILITY_LOST_RESOURCE_STATE_CHANGED | Expired booking loses Late Confirm eligibility because resource state/config changes |
| VENUE_RESPONSE_INTERRUPTED_BY_ACCOUNT_DISABLE | PENDING response interrupted by Venue Account disable |

`LATE_CONFIRM_ELIGIBILITY_LOST_RESOURCE_STATE_CHANGED` permits only these immutable event-specific `detailCode` values: `RESOURCE_DISABLED`, `RESOURCE_ARCHIVED`, `RESOURCE_CONSOLE_CHANGED`, `RESOURCE_OWNER_REVIEW_REQUIRED`. `MANUAL_BOOKING_PRICING_CONFIGURATION_MISSING` is a validation/API error only and is not a Booking Event or terminal reason.

## 2.8 Canonical Tournament terminal reasons

`CREATOR_CANCELLED` · `VENUE_CANCELLED` · `ADMIN_CANCELLED` · `VENUE_REJECTED_TOURNAMENT` · `VENUE_DID_NOT_APPROVE_TOURNAMENT_IN_TIME` · `MINIMUM_PARTICIPANTS_NOT_REACHED` · `CREATOR_VERIFIED_STATUS_REVOKED` · `TOURNAMENT_OPERATIONAL_TERMINATION` · `EMERGENCY_CLOSURE` · `RESOURCE_EMERGENCY_OUTAGE` · `RESOURCE_OWNER_REVIEW_REQUIRED` · `VENUE_ACCOUNT_DISABLED`.

Where causes compete, exactly one reason is stored in this priority: RESOURCE_OWNER_REVIEW_REQUIRED → RESOURCE_EMERGENCY_OUTAGE → EMERGENCY_CLOSURE → VENUE_ACCOUNT_DISABLED → MINIMUM_PARTICIPANTS_NOT_REACHED → CREATOR_VERIFIED_STATUS_REVOKED → explicit actor cancellation.

## 2.9 Trust input catalogue

**Booking outcomes:** `BOOKING_ATTENDED`, `BOOKING_ATTENDANCE_UNCONFIRMED`, `BOOKING_ATTENDED_INTERRUPTED`, `BOOKING_NO_SHOW`.  
**Corrections:** `NO_SHOW_REVERSED`, `ATTENDANCE_CORRECTED`.  
**Administration:** `ADMIN_TRUST_ADJUSTMENT`, `VERIFIED_PLAYER_SUSPENDED`, `VERIFIED_PLAYER_REVOKED`, `VERIFIED_PLAYER_RESTORED`.  
**Platform protection:** `BOOKING_ACCOUNT_DISABLE_ATTENDANCE_PROTECTION`.

Replay order is `effectiveAt ASC, eventSequence ASC`. Check-in/Late-Present evidence enters Trust only through the resulting canonical outcome or correction. Ban creation/expiry are derived outputs rather than Trust inputs.

## 2.10 Actor / action authorization matrix

| Actor / authority | Functional capabilities | Functional prohibitions / limits |
|---|---|---|
| Unverified Customer | Browse, view Venues, history and standing where available | Cannot create Online Booking, accept Late Confirm, write Review, join Tournament or create Tournament |
| Account-Verified Customer | Online Booking, eligible cancellation, Late Confirm response, Reviews, Tournament join and other verified-customer actions | Tournament creation still requires Operational Verified Player = VERIFIED; Ban restrictions still apply |
| Banned Customer | Browse/history/standing/profile; cancel existing future Booking; decline Late Confirm; write/edit/delete Reviews | Cannot create Booking, accept Late Confirm, join Tournament or create Tournament |
| Venue Staff / shared Venue Account | Accept/Reject, Reject After Expiry, Late Confirm, Manual Booking, attendance actions, under-threshold Maintenance, Opening Hours/Closure actions with no protected cancellation, emergencies, WAITING_FOR_PLAYERS tournament cancellation | No attendance reversal; no protected action without fresh Owner Password; no Dashboard operational action while Venue Account DISABLED |
| Venue Owner authorization | Supplies Owner Password for each protected Venue action | Owner Password is not a separate user, session or timed unlock |
| Platform Admin | Reserved marketplace, trust/remediation, moderation, account/status, fee and correction actions in v5.0 §54.4 | Does not bypass Product state/availability invariants by inventing a special Booking path |
| System / Scheduler | Timers, automatic state transitions, cascades, Trust replay, qualification/ban derivation, notifications | May execute only the deterministic Product transitions defined by v5.0 |

### Platform Admin reserved actions

Reverse Check-in / Mark Present — Late / No-show · Attendance Correction · Trust adjustment · Ban lift/shortening/counter/stage correction · Points adjustment · Referral invalidation · Administrative cancellation/correction of a non-terminal Booking · Exceptional termination of an ACTIVE Booking · Cancellation of any non-completed Tournament · Verified Player Suspend/Revoke/Restore · Review moderation · Venue Account disable/enable and password resets · Global Booking Fee and Tournament Creation Fee configuration · Area activation/deactivation · Account deletion/anonymisation.

## 2.11 Customer capability matrix by account state

| Capability | Unverified | Verified | Banned |
|---|---|---|---|
| Browse, view Venues, view history, view standing | Yes | Yes | Yes |
| Create Online Booking | No | Yes | No |
| Accept Late Confirmation | No | Yes | No |
| Decline Late Confirmation | — | Yes | Yes |
| Cancel an existing future Booking | — | Yes | Yes |
| Write / edit / delete Reviews | No | Yes | Yes |
| Join Tournament | No | Yes | No |
| Create Tournament | No | Verified Player only | No |

## 2.12 Notification matrix

| Recipient / class | Required channel(s) | Canonical event coverage |
|---|---|---|
| Customer — time-sensitive booking/tournament/Trust events | In-App + Push | Full catalogue in v5.0 §52.2, including booking lifecycle, Late Confirm, attendance/no-show, Ban, Points, Verified Player and Tournament events, except the customer schedule-conflict failure *(Amended by PCR-008.)* |
| Customer — operational/platform causes | In-App + Push | Emergency/owner-review/account-disable/system-integrity interruptions and cancellations, Attendance Recording Affected, Tournament Resource Availability Changed, and operational refund confirmations where applicable |
| Customer — account/security/moderation | In-App + Email | Full catalogue in v5.0 §52.3; Admin material changes to Ban, No-show, Attendance Result, Points, Booking status or Referral reward also notify affected Customer |
| Phone verification | SMS | Mandatory phone OTP only |
| Email verification | Email OTP or link | Separate from phone-verification activation |
| Venue / Owner | Venue notification surface; channel implementation must preserve the Product notification requirement | Full catalogue in v5.0 §52.5: booking requests/expiry, Late Confirm responses, tournament requests/checkpoint/cancellation, maintenance/emergency reminders and Owner emergency/owner-review notices |
| Platform Admin | Admin operational notification surface | Full catalogue in v5.0 §52.6: onboarding/account, fee, Verified Player, ban, emergency, owner-review, abuse and integrity/account-disable alerts |

Every Booking notification references the specific Booking ID. Attendance-Unconfirmed completion explains that no attendance reward was applied. SMS is not used for non-OTP launch notifications.

## 2.13 Audit and historical-integrity matrix

| Area | Required functional audit/history |
|---|---|
| Booking lifecycle | Append-only Booking History; terminal reason/event history; reservation planned/actual intervals and release reason |
| Attendance | Check-in, Late Present and No-show record/reversal; Attendance Correction with mandatory reason/admin identity; immutable Attendance Result subject only to audited correction |
| Venue operations | Maintenance create/edit/delete; Emergency declaration/extension/end/conversion; Resource state changes; console change; Opening Hours/Closure affected set |
| Owner authorization | Successful and failed Owner Password attempts; protected action and actor |
| Operational interruption | ACTIVE Booking, ACTIVE Manual Booking and ACTIVE Tournament interruption/resource-removal records with cause, time, attendance evidence and refund where applicable |
| Platform / integrity | Venue Account disable/enable; PENDING response interruption; ACTIVE attendance-access restriction; protection evaluation/event; SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR; OWNER_REVIEW_REQUIRED entry/resolution |
| Trust / enforcement | Immutable Trust Input Events; replay ordering; BAN_EXTENDED; admin Trust adjustment; correction chains; Verified Player transition IDs |
| Ledgers | Points/Wallet append-only transactions and compensating entries; Referral invalidation; Booking Fee refund corrections |
| Moderation | Review moderation action, reason, date, previous status and new status |
| Historical snapshots | Booking, Tournament, Referral, Reservation, Operational Interruption, Emergency Pause, Account Access Restriction and Owner Review snapshot sets in v5.0 §53.2 |

The full audit-required action catalogue in v5.0 §53.3 is incorporated by reference and remains mandatory.

---

# 3. PRODUCT-SURFACE FUNCTIONAL BEHAVIOR

## 3.1 Customer Mobile App

- Registration and phone OTP verification; email collected and optionally verified separately.
- Area/Location → Activity → Venue discovery, List and Map, Venue Details and Find Available.
- Online Booking creation, My Bookings projection, Late Confirmation, customer cancellation and status explanations.
- Account Standing, ban visibility, Verified Player progress/status, Points, Referrals and hidden-at-launch Wallet model.
- Public/private tournament discovery/join/leave and player-hosted creation where eligible.
- Reviews using current Username and moderation-state visibility.
- In-App + Push for time-sensitive events; Email for account/security/moderation; SMS only for OTP.
- English at launch and localization-ready; no Arabic Customer UI is part of this baseline.

## 3.2 Venue Dashboard

- Overview; Pending Requests; Missed/Expired Requests; Accept/Reject; Late Confirmation; Bookings; Manual Booking.
- Rooms/Resource Management, pricing, availability, Opening Hours, Maintenance, Closures and Emergencies.
- Check-in, Mark Present — Late and No-show within frozen windows.
- Reviews view-only; tournament creation/approval/management; booking history and revenue summary.
- One shared Venue Account for MVP. Owner Password is required per protected action and never becomes a session unlock.
- English and full Arabic RTL across launch modules; free-text stored data remains as entered.

## 3.3 Platform Admin

- Dashboard; Areas; Venues; Venue Onboarding; Venue Accounts; Activities; Users; Verified Players; Reviews Moderation.
- Global Bookings; No-show records; Tournaments; Wallet/Points/Referrals; Platform Fees; Global Rules; Notifications; Global Search; Settings.
- Account enable/disable, Owner Password reset, moderation, Trust/attendance/no-show/points/referral remediation and operational alerts.
- English only.

---

# 4. COMPLETE FUNCTIONAL REQUIREMENT CATALOGUE — 357 / 357

The following catalogue is normative. Every row preserves the approved PRD requirement ID, priority, requirement wording and governing v5.0 reference. The **Type** and **Actor / Surface** columns are navigation aids only; they do not add Product behavior.

## 4.1 Accounts, Identity and Verification — 18 requirements

| ID | Pri | Type | Actor / Surface | Functional requirement | Governing v5.0 |
|---|---:|---|---|---|---|
| `FR-ACC-01` | M | Authorization, Notification, UI/Presentation | Customer, Platform Admin<br>Customer Mobile App, Platform Admin | Registration captures **Full Name**, **Username**, **Phone**, **Email** and **Password**, all required. Username and Phone are unique; Full Name and Email are not constrained to be unique. Public reviews display **username only**; Platform Admin may see both full name and username. | §7.1 |
| `FR-ACC-02` | M | State/Lifecycle, Validation/Eligibility, Authorization, Notification | Customer<br>Customer Mobile App | An account becomes **Account Verified** only when `phone_verified = true` via SMS OTP. Email OTP alone must never activate an account for marketplace actions. | §7 |
| `FR-ACC-03` | M | Validation/Eligibility, UI/Presentation | Customer, Venue Staff<br>Customer Mobile App | An unverified user may browse and view venues, and may not create a booking, join or create a tournament, or write a review. | §7 |
| `FR-ACC-04` | M | Validation/Eligibility, Timer/Scheduler | Customer<br>Customer Mobile App | OTP rules: valid 5 minutes · max 5 attempts per code · resend after 60 seconds · max 5 sends per hour per destination · a new OTP invalidates the previous one. | §7 |
| `FR-ACC-05` | M | Notification | Customer<br>Customer Mobile App | Email is mandatory registration data. Email verification may occur separately and supports ownership verification, account recovery and email notifications; an unverified email never blocks booking once the phone is verified. | §7 |
| `FR-ACC-06` | M | Functional rule | Customer<br>Customer Mobile App | Username uniqueness is evaluated on a normalised form — trimmed and case-insensitive. | §7 |
| `FR-ACC-07` | M | Timer/Scheduler, Notification | Customer<br>Customer Mobile App | Username is editable with a **30-day cooldown**; Full Name is editable freely. Phone and email changes each require verification of the new value. | §7 |
| `FR-ACC-08` | M | Audit/History, UI/Presentation | Customer<br>Customer Mobile App | Historical entities reference the immutable User ID. Reviews display the **current** username. | §7, §49.2 |
| `FR-ACC-09` | M | Notification | Customer<br>Customer Mobile App | Password reset uses verified phone OTP or email verification and does not require the old password. | §7 |
| `FR-ACC-10` | M | State/Lifecycle, Authorization, Audit/History, UI/Presentation | Customer, Platform Admin<br>Customer Mobile App, Platform Admin | The Customer Mobile App provides an in-app **Delete Account** action that creates one durable deletion request. Support / Platform Admin completes the existing audited anonymisation process, disabling login, removing/anonymising PII where appropriate, preserving required booking/financial/audit history, removing Reviews from public display and applying the existing Tournament `ACCOUNT_DELETED` consequence. The Customer route never hard-deletes retained history. | §7.7, §53.5 |
| `FR-ACC-11` | M | State/Lifecycle, Trust/Enforcement, Audit/History, UI/Presentation | Customer, Venue Staff, System<br>Customer Mobile App | **Verified Player Qualification** is derived from corrected trust history — **10 completed attended bookings, all of which may be at the same venue** — and is displayed as progress, for example *"6 / 10 Completed Bookings"*. It is separate from **Operational Verified Player Status** (NOT_VERIFIED / VERIFIED / SUSPENDED / REVOKED). Only VERIFIED may create a player-hosted tournament. | §8.1, §8.2, §8.3 |
| `FR-ACC-12` | M | Trust/Enforcement, Audit/History | Customer<br>Customer Mobile App | Every operational Verified Player transition creates an immutable `verifiedPlayerTransitionId`, including a retroactively discovered first qualification. | §8 |
| `FR-ACC-13` | M | State/Lifecycle | Customer<br>Customer Mobile App | A retroactively discovered qualification activates operational status **now**, never back-dated. | §8 |
| `FR-ACC-14` | M | State/Lifecycle, Authorization, Trust/Enforcement | Customer, Platform Admin, System<br>Customer Mobile App, Platform Admin | Where operational status was REVOKED **solely** by `TRUST_CORRECTION` and a later replay restores qualification, the system **must** transition REVOKED → VERIFIED automatically with a new transition ID. No admin action is required in this case. | §8 |
| `FR-ACC-15` | M | Authorization, Trust/Enforcement | Customer, Platform Admin, System<br>Customer Mobile App, Platform Admin | Admin SUSPENDED and admin REVOKED are protected: automatic trust regain never overrides them. Admin Restore yields VERIFIED if currently qualified, otherwise NOT_VERIFIED. | §8 |
| `FR-ACC-16` | M | Trust/Enforcement | Customer<br>Customer Mobile App | Manual bookings and tournament participation contribute **nothing** to Verified Player progress. | §8, §30.6 |
| `FR-ACC-17` | M | Validation/Eligibility, Authorization, Trust/Enforcement, Audit/History, UI/Presentation | Customer, Venue Staff<br>Customer Mobile App | A banned user may log in, browse, view venues, view history, view account standing and profile, cancel an existing future booking, decline a Late Confirmation, and write, edit or delete reviews. They may **not** create a booking, accept a Late Confirmation, join a tournament or create a tournament. | §9 |
| `FR-ACC-18` | M | State/Lifecycle, Audit/History | Customer<br>Customer Mobile App | **Qualification as-of** and **operational status as-of** are separate historical queries and may legitimately differ after a correction. Historical qualification never implies historical operational tournament privileges. | §8.10 |

**Domain acceptance obligations from PRD v1.1**

- An unverified account is rejected at every one of the four gated actions, with a message that names phone verification as the requirement
- A 6th OTP send within an hour to the same destination is refused
- Changing a username twice within 30 days is refused; the error states the remaining cooldown
- A user who loses Verified Player status through a trust correction, then regains qualification through a later correction, is VERIFIED again with **no** admin action, and carries two distinct transition IDs
- A user suspended by an admin who then reaches qualification remains SUSPENDED
- Accepting a Late Confirmation while banned is refused; declining it succeeds

## 4.2 Discovery and Browsing — 14 requirements

| ID | Pri | Type | Actor / Surface | Functional requirement | Governing v5.0 |
|---|---:|---|---|---|---|
| `FR-DIS-13` | M | State/Lifecycle, Inventory/Reservation, Configuration | Customer, Venue Staff<br>Customer Mobile App | The canonical discovery order is **Area / Location → Activity → Venue → Resource → Availability → Booking**. **Activity selection happens before venue discovery**, which is what makes Nearest Availability computable per selected activity. | §20.1 |
| `FR-DIS-14` | M | State/Lifecycle, Inventory/Reservation, Configuration | Customer, Venue Staff<br>Customer Mobile App | The canonical booking flow is **Area → Activity → Venue → Resource → (Single / Multiplayer, PlayStation only) → Date → Start Time → Duration → Booking Summary → Confirm**. | §20.8 |
| `FR-DIS-01` | M | Validation/Eligibility, Authorization, Configuration | Customer, Venue Staff<br>Customer Mobile App | With location enabled and no manual area selection, show eligible venues across **active areas** sorted primarily by distance. | §20.2 |
| `FR-DIS-02` | M | State/Lifecycle, Authorization, Configuration | Customer<br>Customer Mobile App | A manually selected area becomes a **hard filter**, even while location permission remains enabled. | §20.2 |
| `FR-DIS-03` | M | Inventory/Reservation, Money/Points, UI/Presentation, Configuration | Customer, Venue Staff<br>Customer Mobile App | The **venue card** shows: venue image · venue name · area · distance where available · **starting price** · **Nearest Availability** · average rating · review count. | §20.3 |
| `FR-DIS-04` | M | Validation/Eligibility, Timer/Scheduler, Inventory/Reservation, Configuration | Customer, System<br>Customer Mobile App | **Nearest Availability** is the earliest valid start time for the currently selected activity at the **minimum booking duration of 1 hour**, computed from opening hours, eligible resources, the canonical availability predicate, the current time and the 15-minute lead-time rule. For PlayStation, before any room or console filter, use the earliest valid PlayStation resource. | §16.5 |
| `FR-DIS-05` | M | Validation/Eligibility, Inventory/Reservation, UI/Presentation | Customer, Venue Staff<br>Customer Mobile App | Resources that are DISABLED, ARCHIVED, under `OWNER_REVIEW_REQUIRED`, or under an operational selling block at venue or resource level must **never** contribute to Nearest Availability or appear as available. | §16.5, §43.7 |
| `FR-DIS-06` | M | State/Lifecycle, Validation/Eligibility, Timer/Scheduler, Inventory/Reservation, Money/Points | Customer, Venue Staff<br>Customer Mobile App | A currently closed venue remains discoverable. The customer may view the venue, activities, future availability, prices and Nearest Availability, and sees state such as *Closed Now* and *Opens at 12:00 PM*. They may not submit a request until the next valid online intake window. | §20.7 |
| `FR-DIS-07` | M | Functional rule | Customer, Venue Staff<br>Customer Mobile App | Cross-activity discovery surfaces the venue's other activities — *Also available at this venue*. | §20.6 |
| `FR-DIS-08` | M | State/Lifecycle, UI/Presentation | Customer, Venue Staff<br>Customer Mobile App | Venues with marketplace status ONBOARDING, INACTIVE or SUSPENDED are not shown in normal customer discovery. A deep-linked venue page may remain viewable with booking actions disabled. | §11 |
| `FR-DIS-09` | M | State/Lifecycle, Trust/Enforcement, UI/Presentation | Customer, Venue Staff<br>Customer Mobile App | The venue phone is hidden before a booking exists. Counterpart phone access **opens when the booking becomes PENDING**: the venue may see the customer phone and the customer may see the venue phone. **Manual booking is exempt**, because the venue entered any contact data itself. | §20.10, §34.5 |
| `FR-DIS-10` | M | Validation/Eligibility, Inventory/Reservation, Money/Points, UI/Presentation | Customer, Venue Staff<br>Customer Mobile App | **Map view** shows venue markers positioned by coordinates under the same eligibility rules as the list. A marker preview contains venue name, distance, starting price, Nearest Availability, average rating, review count and a **View Venue** action. | §20.4, §2.1 |
| `FR-DIS-11` | M | Timer/Scheduler, Inventory/Reservation, UI/Presentation, Configuration | Customer, Venue Staff<br>Customer Mobile App | **Venue Details** shows images, venue name, area, address, distance, opening hours, rating, reviews, a **View Location** action, the selected activity, its resources, pricing and availability. | §20.5 |
| `FR-DIS-12` | M | Inventory/Reservation, Money/Points, Configuration | Customer<br>Customer Mobile App | **Find Available** search takes area, activity, date, start time and duration, and returns only resources available for the **entire** requested interval under the canonical availability predicate. There is **no price filter** in this release. | §20.9 |

**Domain acceptance obligations from PRD v1.1**

- A venue whose only PlayStation room is under owner review shows no PlayStation nearest availability, and does not appear in a PlayStation-filtered list as bookable
- Selecting an area while location is on returns only that area's venues
- A closed venue is browsable and priced, and its book action is disabled with copy explaining when it opens
- A suspended venue disappears from the list but its deep link still renders, with booking disabled

## 4.3 Availability and Inventory — 15 requirements

| ID | Pri | Type | Actor / Surface | Functional requirement | Governing v5.0 |
|---|---:|---|---|---|---|
| `FR-AVL-01` | M | Inventory/Reservation | System<br>System / cross-cutting | A single canonical availability predicate governs every flow — online booking, manual booking, Late Confirm, tournament creation, tournament holds and Nearest Availability. | §16 |
| `FR-AVL-02` | M | State/Lifecycle, Inventory/Reservation, Configuration | Venue Staff, System<br>System / cross-cutting | Canonical availability evaluates, **in this order**: **1.** venue operational selling block · **2.** resource operational selling block · **3.** resource base state · **4.** resource operational restriction · **5.** emergency type, status, affected scope and declared interval · **6.** maintenance · **7.** closure · **8.** blocking reservations. An operational selling block is evaluated **explicitly before** any resource reservation check and needs no fabricated future interval; an unresolved emergency is **never** treated as an infinitely extended reservation. | §16.2 |
| `FR-AVL-03` | M | Inventory/Reservation, UI/Presentation, Configuration | Customer, Venue Staff, System<br>System / cross-cutting | A resource interval is **not free** when **any** of: the resource is DISABLED or ARCHIVED · `operationalRestriction = OWNER_REVIEW_REQUIRED` · a relevant operational selling block is active at venue or resource level · the interval overlaps a blocking reservation from a PENDING, AWAITING_CUSTOMER_CONFIRMATION, CONFIRMED or ACTIVE booking of either source, a Player Tournament Approval Hold, a Tournament Hold, Maintenance, a Venue Closure, an Emergency Closure or a Resource Emergency Outage. | §16.3 |
| `FR-AVL-04` | M | State/Lifecycle, Inventory/Reservation | System<br>System / cross-cutting | **Booking status decides whether a booking blocks inventory; booking source does not.** For `source = MANUAL` only CONFIRMED and ACTIVE are normally reachable blocking states. | §16.3 |
| `FR-AVL-05` | M | State/Lifecycle, Inventory/Reservation | Venue Staff, System<br>System / cross-cutting | `VENUE_RESPONSE_EXPIRED` does **not** block inventory, because its reservation has been released. Terminal bookings never block. | §16.3 |
| `FR-AVL-06` | M | State/Lifecycle, Validation/Eligibility, Inventory/Reservation | System<br>System / cross-cutting | A resource requires **both** `baseState = ACTIVE` **and** `operationalRestriction = NONE` to be eligible for any flow. | §13.3, §16.2, §43 |
| `FR-AVL-07` | M | Validation/Eligibility, Inventory/Reservation | Venue Staff, System<br>System / cross-cutting | Every creation and commitment predicate requires `Operational Selling Block = FALSE` for the affected venue **and** resource. Interval-overlap checks alone are never sufficient. | §38.4 |
| `FR-AVL-08` | M | Validation/Eligibility, Inventory/Reservation | System<br>System / cross-cutting | **Double booking is never allowed.** Two active reservations must never overlap on one resource. | §16.4 |
| `FR-AVL-09` | M | State/Lifecycle, Validation/Eligibility, Inventory/Reservation | Venue Staff, System<br>System / cross-cutting | Eligibility is revalidated immediately before **every** commitment-strengthening transition — creation, Venue Accept, Late Confirm initiation, Late Confirm Accept, manual booking creation, tournament creation, tournament approval, tournament checkpoint confirmation, booking CONFIRMED → ACTIVE, tournament CONFIRMED → ACTIVE, and resource or hold assignment, **and before any future transition whose effect is to obtain or strengthen a resource or venue commitment**. No lifecycle boundary is exempt from operational eligibility. | §31.9, §31.10 |
| `FR-AVL-10` | M | State/Lifecycle, Validation/Eligibility | System<br>System / cross-cutting | Revalidation and the state transition commit in **one atomic transaction**. The first successfully committed valid transition wins; a state change between validation and commit causes revalidation or failure. | §31.11 |
| `FR-AVL-11` | M | State/Lifecycle, Validation/Eligibility, Inventory/Reservation, Audit/History, UI/Presentation | System<br>System / cross-cutting | Displayed availability is a snapshot, not a commit guarantee. An action is offered only when the latest known state satisfies its predicate; execution always revalidates atomically and may fail. | §31.12 |
| `FR-AVL-12` | M | Validation/Eligibility, Timer/Scheduler, Inventory/Reservation, Trust/Enforcement, Audit/History | System<br>System / cross-cutting | **Historical planned intervals may overlap; active reservation occupancy may not.** A no-show releases the remainder of an interval, so a later valid booking may start inside the original planned window. | §17.2 |
| `FR-AVL-13` | M | Inventory/Reservation, Audit/History | System<br>System / cross-cutting | Reservation history stores `plannedStart`, `plannedEnd`, `actualReservationStart`, `actualReservationReleaseAt` and `releaseReason`, so historical availability can be replayed and an apparent overlap explained. | §17.3 |
| `FR-AVL-14` | M | Timer/Scheduler, Money/Points, Audit/History | Venue Staff, System<br>System / cross-cutting | Every revenue-producing record — online booking, manual booking, tournament — stores a `businessDate`, which is the canonical revenue-day key and is historical. | §17.5 |
| `FR-AVL-15` | M | State/Lifecycle, Validation/Eligibility, Timer/Scheduler | Customer, Venue Staff, System<br>System / cross-cutting | Canonical timer boundary: an action allowed "before" a deadline requires `now < deadline`; at `now >= deadline` the expiry owns the state. This applies to the venue response window, Late Confirmation, customer cancellation at start, tournament join and leave at start, and every equivalent boundary. | §17.6 |

**Domain acceptance obligations from PRD v1.1**

- A concurrency test issuing N simultaneous bookings for one slot produces exactly one CONFIRMED path and N−1 clean failures, with no partial reservation
- A resource with `baseState = ACTIVE` and `operationalRestriction = OWNER_REVIEW_REQUIRED` is rejected by all six flows in FR-AVL-01
- A venue under an emergency selling block rejects a manual booking whose interval does not overlap the declared emergency interval
- After a no-show at 8:16 on an 8:00–9:00 booking, a new booking starting 8:20 succeeds, and both records survive with overlapping planned intervals and non-overlapping reservation intervals
- A manual booking created outside published opening hours still carries a `businessDate`

## 4.4 Online Booking Lifecycle — 32 requirements

| ID | Pri | Type | Actor / Surface | Functional requirement | Governing v5.0 |
|---|---:|---|---|---|---|
| `FR-BOK-01` | M | Timer/Scheduler | System<br>Customer Mobile App, Venue Dashboard | Duration: minimum **1 hour**, increments of **30 minutes**, maximum **6 hours** for online bookings. | §19 |
| `FR-BOK-02` | M | Validation/Eligibility, Timer/Scheduler | System<br>Customer Mobile App, Venue Dashboard | A new online booking requires `now <= startAt − 15 minutes`. Exactly 15 minutes is permitted; less is rejected. | §19 |
| `FR-BOK-03` | M | State/Lifecycle, Inventory/Reservation | Customer, Venue Staff<br>Customer Mobile App, Venue Dashboard | A customer may hold at most **4** active/upcoming online commitments — counting PENDING, AWAITING_CUSTOMER_CONFIRMATION, CONFIRMED and ACTIVE across all venues. VENUE_RESPONSE_EXPIRED and terminal states do not count; manual bookings do not count. The limit is checked on **every** action that would increase the count. | §19.1 |
| `FR-BOK-04` | M | Validation/Eligibility, Inventory/Reservation | Customer<br>Customer Mobile App, Venue Dashboard | A customer may not hold **overlapping** online commitments. The 4-booking limit is a total-count limit **in addition to** the no-overlap rule. | §19.2 |
| `FR-BOK-05` | M | Validation/Eligibility, Configuration | System<br>Customer Mobile App, Venue Dashboard | The booking interval must fit entirely inside **one** future operating session. It may cross midnight where the session does. It may not cross a closure, a closed gap, or the end of one session into another. | §14.5 |
| `FR-BOK-06` | M | Timer/Scheduler, Money/Points, Audit/History | System<br>Customer Mobile App, Venue Dashboard | Price = `hourlyPrice × nominalBookedMinutes ÷ 60`, rounded **half-up to 2 decimal places** per line item; totals are sums of rounded line items. The stored historical amount is immutable. | §4.3 |
| `FR-BOK-07` | M | Timer/Scheduler, Inventory/Reservation, Money/Points | Customer<br>Customer Mobile App, Venue Dashboard | Price and duration use the customer-selected **nominal** duration, never a DST-anomalous elapsed duration. Inventory and `endAt` use real elapsed time: `endAt = startAt + nominalBookedMinutes`. | §4.4, §14.3 |
| `FR-BOK-08` | M | Functional rule | Venue Staff<br>Customer Mobile App, Venue Dashboard | A request may be submitted only while the venue is **currently open** under its current operating session — even for a future date and time. | §21 |
| `FR-BOK-09` | M | Timer/Scheduler | Venue Staff<br>Customer Mobile App, Venue Dashboard | New online booking intake **stops 10 minutes before the current operating session ends**, guaranteeing the venue a full response window while operating. | §21 |
| `FR-BOK-10` | M | Validation/Eligibility | System<br>Customer Mobile App, Venue Dashboard | Being open at submission does not make every future slot valid; the requested future interval is validated independently. | §21 |
| `FR-BOK-11` | M | State/Lifecycle, Validation/Eligibility, Timer/Scheduler, Inventory/Reservation, Authorization | Customer, Venue Staff<br>Customer Mobile App, Venue Dashboard | Creation requires **all** of: account verified by phone OTP · customer not banned · customer below the 4-commitment limit · no overlapping customer commitment · venue marketplace **ACTIVE** · venue account **ENABLED** · area **ACTIVE** · venue inside an operating session · at least 10 minutes before session end · no venue operational selling block · no resource operational selling block · requested start at least 15 minutes ahead · duration 1–6 hours in 30-minute increments · interval fits one future operating session · resource base state **ACTIVE** · resource operational restriction **NONE** · no overlapping blocking reservation · activity/resource compatibility · **all other required product validation** (§22 item 20). The twenty conditions above are cumulative and are the complete canonical list. Note: the explicit *"required pricing configuration exists"* condition belongs to the **manual** booking predicate (§30.2 item 15, FR-MAN-02) and is **not** one of §22's twenty. Where a venue's pricing configuration for the activity is absent, online creation fails under §22 item 20. Stating it as an explicit twenty-first condition of §22 would be a Product Change Request against v5.0 §22, not a PRD edit; the Product Owner has ruled that **no twenty-first condition is added**. | §22 |
| `FR-BOK-12` | M | State/Lifecycle | Customer, Venue Staff<br>Customer Mobile App, Venue Dashboard | Canonical states: PENDING · VENUE_RESPONSE_EXPIRED · AWAITING_CUSTOMER_CONFIRMATION · CONFIRMED · ACTIVE · COMPLETED · REJECTED · CANCELLED · NO_SHOW. Terminal: COMPLETED, REJECTED, CANCELLED, NO_SHOW. **`VENUE_RESPONSE_EXPIRED` is non-terminal.** | §18.2 |
| `FR-BOK-13` | M | Timer/Scheduler, Inventory/Reservation | Venue Staff<br>Customer Mobile App, Venue Dashboard | On creation the booking is **PENDING** and reserves the resource. The venue has **10 minutes** to Accept or Reject. There is **no auto-accept**. | §23.1 |
| `FR-BOK-14` | M | State/Lifecycle, Validation/Eligibility, Inventory/Reservation, Trust/Enforcement | Customer, Venue Staff<br>Customer Mobile App, Venue Dashboard | Venue Accept revalidates atomically: venue existing-commitment eligibility, venue selling block, resource base state, resource operational restriction, resource selling block, reservation validity, interval validity, and **always** customer account and ban eligibility. | §23.2 |
| `FR-BOK-15` | M | Validation/Eligibility | Venue Staff<br>Customer Mobile App, Venue Dashboard | Venue Accept uses **existing-commitment** eligibility (category B) and therefore does **not** require marketplace ACTIVE. A venue that became INACTIVE or SUSPENDED after the request may still accept it. | §35.2 |
| `FR-BOK-16` | M | Validation/Eligibility, Inventory/Reservation, UI/Presentation | Venue Staff<br>Customer Mobile App, Venue Dashboard | If Accept fails because the venue or resource became unavailable through a venue emergency, a resource emergency or owner review, the booking may **not** become CONFIRMED; it is CANCELLED under the applicable operational reason, and **no venue non-response blame is applied**. | §23.3 |
| `FR-BOK-17` | M | State/Lifecycle, Inventory/Reservation, Configuration | Venue Staff<br>Customer Mobile App, Venue Dashboard | Venue Reject: PENDING → **REJECTED** under terminal reason code `VENUE_REJECTED`. A **venue-supplied reject reason is mandatory** and is recorded separately from the reason code — examples: resource unavailable, maintenance, venue issue, other. The **resource reservation is released immediately**. | §23.4 |
| `FR-BOK-18` | M | State/Lifecycle, Timer/Scheduler, Inventory/Reservation | Venue Staff<br>Customer Mobile App, Venue Dashboard | At `venueResponseExpiresAt` with no response, PENDING → **VENUE_RESPONSE_EXPIRED** and the resource reservation releases immediately. | §23.5 |
| `FR-BOK-19` | M | UI/Presentation | Customer, Venue Staff<br>Customer Mobile App, Venue Dashboard | The customer-facing projection maps: **Upcoming** = PENDING, AWAITING_CUSTOMER_CONFIRMATION, CONFIRMED, ACTIVE · **Completed** = COMPLETED · **Cancelled** = VENUE_RESPONSE_EXPIRED, CANCELLED, REJECTED, NO_SHOW. | §18.3 |
| `FR-BOK-20` | M | Validation/Eligibility, Timer/Scheduler, UI/Presentation | Customer, Venue Staff, System<br>Customer Mobile App, Venue Dashboard | A VENUE_RESPONSE_EXPIRED booking is displayed under Cancelled with *"Venue did not respond in time"*, while remaining internally eligible for Late Confirmation until `startAt`. There is no pre-emptive customer opt-out, and no automatic reactivation. | §18.3, §24.1 |
| `FR-BOK-21` | M | State/Lifecycle, Timer/Scheduler | Venue Staff<br>Customer Mobile App, Venue Dashboard | The venue may **Reject After Expiry** before the start time: VENUE_RESPONSE_EXPIRED → REJECTED, reason `REJECTED_AFTER_EXPIRY`. | §24.2 |
| `FR-BOK-22` | M | State/Lifecycle, Validation/Eligibility, Inventory/Reservation, Notification | Customer, Venue Staff, System<br>Customer Mobile App, Venue Dashboard | Resource Disable, Archive and Console Change may proceed while a booking is only VENUE_RESPONSE_EXPIRED, since no reservation is held. The system records `LATE_CONFIRM_ELIGIBILITY_LOST_RESOURCE_STATE_CHANGED` with the matching `detailCode`; no additional customer push is required. | §24.4 |
| `FR-BOK-23` | M | State/Lifecycle, Validation/Eligibility, Timer/Scheduler, Inventory/Reservation, UI/Presentation | Venue Staff<br>Customer Mobile App, Venue Dashboard | A booking still VENUE_RESPONSE_EXPIRED at its original `startAt` becomes CANCELLED, with the reason chosen by this priority: **1.** `RESOURCE_OWNER_REVIEW_REQUIRED` · **2.** `RESOURCE_EMERGENCY_OUTAGE` · **3.** `VENUE_EMERGENCY_CLOSURE` · **4.** `VENUE_ACCOUNT_DISABLED` · **5.** `VENUE_DID_NOT_RESPOND_IN_TIME`. The last applies only when no overriding operational or platform cause exists. | §24.3 |
| `FR-BOK-24` | M | State/Lifecycle, Timer/Scheduler | Venue Staff, System<br>Customer Mobile App, Venue Dashboard | CONFIRMED → ACTIVE at `startAt` and ACTIVE → COMPLETED at `endAt` are automatic and backend-controlled. No venue action is required. | §26.1 |
| `FR-BOK-25` | M | State/Lifecycle, Validation/Eligibility, Inventory/Reservation, Configuration | Venue Staff, System<br>Customer Mobile App, Venue Dashboard | Immediately before CONFIRMED → ACTIVE the system revalidates venue selling block, resource base state, resource operational restriction, resource selling block, and applicable closure or emergency conditions — atomically with the transition. | §31.2 |
| `FR-BOK-26` | M | State/Lifecycle, Timer/Scheduler, Trust/Enforcement, Money/Points, Notification | System<br>Customer Mobile App, Venue Dashboard | Every start-time failure produces a deterministic terminal transition, a closed reason code, an attendance result, a notification and a refund classification. **A booking can never remain CONFIRMED past `startAt` because a check failed.** | §31.3 |
| `FR-BOK-27` | M | State/Lifecycle, Inventory/Reservation, Authorization, UI/Presentation, Configuration | Venue Staff, Platform Admin, System<br>Customer Mobile App, Venue Dashboard, Platform Admin | Start-time reason priority: **1.** `RESOURCE_OWNER_REVIEW_REQUIRED` · **2.** `RESOURCE_EMERGENCY_OUTAGE` · **3.** `VENUE_EMERGENCY_CLOSURE` · **4.** `VENUE_CLOSURE` · **5.** `VENUE_ACCOUNT_DISABLED` where it applies to an unresolved pre-confirmation lifecycle · **6.** `SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR` · **7.** other explicit admin or system correction. Exactly one reason is stored. | §31.6 |
| `FR-BOK-28` | M | State/Lifecycle, Timer/Scheduler, Inventory/Reservation, Authorization, Trust/Enforcement | Customer, Platform Admin, System<br>Customer Mobile App, Venue Dashboard, Platform Admin | If a resource base-state anomaly survives to `startAt`, the booking is cancelled immediately under `SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR`, with no customer penalty, a full future fee refund, a customer notification and a Platform Admin alert recording booking ID, resource ID, the unexpected state, `startAt`, detection time, the conflicting source where known, and a correlation identifier. | §31.4 |
| `FR-BOK-29` | M | Timer/Scheduler, Inventory/Reservation, Authorization, UI/Presentation, Configuration | Venue Staff, Platform Admin<br>Customer Mobile App, Venue Dashboard, Platform Admin | There is **no ordinary early session end**. An ACTIVE booking blocks its resource until `endAt`. The only early-termination paths are venue emergency closure, resource emergency outage, entry into `OWNER_REVIEW_REQUIRED`, and Platform Admin support correction. | §26.3 |
| `FR-BOK-30` | M | Inventory/Reservation, Authorization, UI/Presentation, Configuration | Venue Staff, Platform Admin<br>Customer Mobile App, Venue Dashboard, Platform Admin | ACTIVE interruption reason priority: **1.** `RESOURCE_OWNER_REVIEW_REQUIRED` · **2.** `RESOURCE_EMERGENCY_OUTAGE` · **3.** `VENUE_EMERGENCY_CLOSURE` · **4.** explicit Platform Admin correction. | §31.8 |
| `FR-BOK-31` | M | Timer/Scheduler, Trust/Enforcement | Customer<br>Customer Mobile App, Venue Dashboard | The customer may cancel while `now < startAt`, free of charge, with no no-show and no trust penalty, reason `CUSTOMER_CANCELLED`. After start time, customer cancellation is disabled. | §29.1 |
| `FR-BOK-32` | M | Timer/Scheduler | Customer<br>Customer Mobile App, Venue Dashboard | The customer retains the right to cancel a PENDING booking while its response timer is paused by an emergency. | §29.2 |

**Domain acceptance obligations from PRD v1.1**

- A request submitted 9 minutes before the session ends is refused with the intake-window reason; one submitted 11 minutes before succeeds
- A request for `startAt` exactly 15 minutes ahead succeeds; 14 minutes 59 seconds is refused
- A 5th commitment is refused at creation, and also at Late Confirm initiation and at Late Confirm Accept
- A venue that accepts at `venueResponseExpiresAt − 1s` succeeds; at the boundary the expiry wins
- A booking expiring at a venue whose account is disabled resolves at `startAt` under `VENUE_ACCOUNT_DISABLED`, never `VENUE_DID_NOT_RESPOND_IN_TIME`
- A SUSPENDED venue can still accept a PENDING booking created before suspension
- Forcing a resource into DISABLED behind the predicate and letting a CONFIRMED booking reach `startAt` produces `SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR`, a terminal booking, and an admin alert
- Every terminal booking in a seeded end-to-end run carries exactly one reason code from the closed enum

## 4.5 Late Confirmation — 18 requirements

| ID | Pri | Type | Actor / Surface | Functional requirement | Governing v5.0 |
|---|---:|---|---|---|---|
| `FR-LCF-01` | M | Functional rule | Customer<br>Customer Mobile App, Venue Dashboard | Late Confirm is **existing-commitment operational management** (category B), not new-activity acquisition. | §35.2 |
| `FR-LCF-02` | M | State/Lifecycle, Validation/Eligibility, Timer/Scheduler, Authorization, Configuration | Customer, Venue Staff<br>Customer Mobile App, Venue Dashboard | Venue eligibility: marketplace status **ACTIVE, INACTIVE or SUSPENDED** · venue account **ENABLED** · area **ACTIVE** · venue currently inside a valid operating session · no venue operational selling block · the original interval still fits valid opening hours · original start still in the future. | §25.2 |
| `FR-LCF-03` | M | State/Lifecycle, Validation/Eligibility, Inventory/Reservation | Customer<br>Customer Mobile App, Venue Dashboard | Resource eligibility: base state **ACTIVE** · operational restriction **NONE** · no resource operational selling block · the interval still passes the canonical conflict predicate. | §25.3 |
| `FR-LCF-04` | M | Validation/Eligibility, Inventory/Reservation, Authorization, Trust/Enforcement | Customer, Venue Staff<br>Customer Mobile App, Venue Dashboard | Customer eligibility at initiation: not banned · below the 4-commitment limit. If the customer is already at 4, initiation is **blocked**, the booking stays VENUE_RESPONSE_EXPIRED, and **no temporary hold is created**. | §25.4 |
| `FR-LCF-05` | M | Validation/Eligibility, Audit/History | Customer, Venue Staff<br>Customer Mobile App, Venue Dashboard | When initiation is blocked by the limit, the venue sees a neutral message — *"Customer is not currently eligible for Late Confirmation"* — and **never** learns about the customer's other bookings. The internal event is `LATE_CONFIRM_FAILED_BOOKING_LIMIT`. | §25.4 |
| `FR-LCF-06` | M | State/Lifecycle, Validation/Eligibility, Inventory/Reservation | Customer, Venue Staff<br>Customer Mobile App, Venue Dashboard | Initiation is **one atomic transaction**: read commitment count · validate the limit · validate the resource · create the temporary hold · transition VENUE_RESPONSE_EXPIRED → AWAITING_CUSTOMER_CONFIRMATION. Concurrent initiations can never produce a temporary fifth commitment. | §25.5 |
| `FR-LCF-07` | M | Timer/Scheduler | Customer, Venue Staff<br>Customer Mobile App, Venue Dashboard | Late Confirm is **not** restricted by the customer intake window; the venue may initiate it even with fewer than 10 minutes left in the session, because it is a venue-initiated action needing no new venue response window. | §25.6 |
| `FR-LCF-08` | M | Inventory/Reservation, Money/Points, Audit/History | Customer, Venue Staff<br>Customer Mobile App, Venue Dashboard | Late Confirmation keeps the **same Booking ID**, venue, activity, resource, console, play mode, date, time, duration, venue price, booking fee and total. All prior history is preserved. | §25.7 |
| `FR-LCF-09` | M | Validation/Eligibility, Timer/Scheduler | Customer<br>Customer Mobile App, Venue Dashboard | `lateConfirmExpiresAt = MIN(now + 5 minutes, booking.startAt)`. The window must never extend beyond the original start time. | §25.8 |
| `FR-LCF-10` | M | State/Lifecycle, Timer/Scheduler, Inventory/Reservation | Customer<br>Customer Mobile App, Venue Dashboard | Late Confirmation state is stored **per booking** — its own expiry, warning flag, customer response and resource hold. One user may hold several simultaneous Late Confirmations that never interfere. | §25.8 |
| `FR-LCF-11` | M | UI/Presentation | Customer<br>Customer Mobile App, Venue Dashboard | Customer copy must communicate that the original request expired — *"Game Zone is asking to reactivate your expired booking. Do you still want it?"* — with **Accept Booking** and **Decline** actions. | §25.9 |
| `FR-LCF-12` | M | Timer/Scheduler, Notification | Customer<br>Customer Mobile App, Venue Dashboard | If the window exceeds 60 seconds, send the Late Confirmation notification plus one *Late Confirmation Expiring* notification about a minute before expiry, once per booking, linked to the exact Booking ID. If the window is 60 seconds or less, send no second notification and show the real short countdown in the first. | §25.9 |
| `FR-LCF-13` | M | State/Lifecycle, Trust/Enforcement | Customer<br>Customer Mobile App, Venue Dashboard | Responses: **Accept** → CONFIRMED · **Decline** → CANCELLED `CUSTOMER_DECLINED_LATE_CONFIRM` · **No response** → CANCELLED `CUSTOMER_RECONFIRM_TIMEOUT`. All keep the same Booking ID and carry **no penalty**. | §25.10 |
| `FR-LCF-14` | M | Validation/Eligibility, Inventory/Reservation, Trust/Enforcement | Customer, Venue Staff<br>Customer Mobile App, Venue Dashboard | Accept revalidates: commitment count still valid · no customer schedule conflict · resource reservation still valid · ban and account eligibility · venue and resource operational eligibility including selling block. | §25.11 |
| `FR-LCF-15` | M | Validation/Eligibility | Customer, Venue Staff<br>Customer Mobile App, Venue Dashboard | For privacy, the customer schedule conflict is checked **only when the customer attempts to accept**. The venue is never told the customer has another booking and may freely send the request. | §25.12 |
| `FR-LCF-16` | M | State/Lifecycle | Customer<br>Customer Mobile App, Venue Dashboard | A conflicting Accept **fails** and records `LATE_CONFIRM_FAILED_CUSTOMER_CONFLICT`. The booking does not terminate under a generic schedule-conflict reason; any later terminal outcome uses its real reason. | §25.12 |
| `FR-LCF-17` | M | State/Lifecycle, Validation/Eligibility, Inventory/Reservation | Customer<br>Customer Mobile App, Venue Dashboard | Resource unavailability during Late Confirm is expressed as validation failure, atomic transaction failure, or `LATE_CONFIRM_FAILED_RESOURCE_CONFLICT`. There is **no** terminal "late confirm resource unavailable" reason. | §25.13 |
| `FR-LCF-18` | M | Trust/Enforcement | Customer<br>Customer Mobile App, Venue Dashboard | Counterpart phone visibility **must reopen** for the AWAITING_CUSTOMER_CONFIRMATION period, and is fully restored on Accept. | §34.7 |

**Domain acceptance obligations from PRD v1.1**

- Initiating at 7:59 PM for an 8:00 PM booking gives the customer exactly 60 seconds and **no** second notification
- Initiating at 7:50 PM gives 5 minutes and one expiring notification at roughly 7:54 PM
- A customer at 4 commitments cannot have a Late Confirm initiated for them; the venue message reveals nothing, and no hold is created
- Two venues initiating Late Confirm simultaneously for a customer at 3 commitments produce exactly one AWAITING booking
- A customer accepting a Late Confirmation that overlaps another commitment gets a clear failure, the booking stays AWAITING until its own expiry, and the event is recorded
- A declined Late Confirmation costs the customer nothing and, under a future non-zero fee, refunds in full

## 4.6 Attendance, Check-in and No-show — 28 requirements

| ID | Pri | Type | Actor / Surface | Functional requirement | Governing v5.0 |
|---|---:|---|---|---|---|
| `FR-ATT-01` | M | Authorization, Audit/History | Customer, Venue Staff, Venue Owner authorization<br>Customer Mobile App, Venue Dashboard | **Check-in** is a venue staff action requiring no QR code, PIN, customer device confirmation or biometrics. It does not require the Owner Password, and it is audited with venue account identity, timestamp and Booking ID. | §27.1, §27.2 |
| `FR-ATT-02` | M | Validation/Eligibility, Timer/Scheduler | System<br>Customer Mobile App, Venue Dashboard | Check-in window: `startAt <= now < startAt + 15 minutes`. At exactly `+15 minutes` check-in is refused. | §27.2 |
| `FR-ATT-03` | M | Validation/Eligibility, Timer/Scheduler, Authorization, Trust/Enforcement, Money/Points | Venue Owner authorization<br>Customer Mobile App, Venue Dashboard | **Mark Present — Late** is available while `startAt + 15 min <= now < endAt`, only when no valid check-in exists and no no-show has been committed. It blocks no-show, grants no points, grants no Verified Player progress, and does **not** reset the counter. It requires no Owner Password and is audited. | §27.3 |
| `FR-ATT-04` | M | Validation/Eligibility, Timer/Scheduler, Authorization, Trust/Enforcement, Audit/History | Customer, Venue Owner authorization<br>Customer Mobile App, Venue Dashboard | **No-show** is available while `startAt + 15 min <= now < endAt`, only when no valid check-in and no Mark Present — Late exist. It requires no Owner Password and is audited. A customer marked Late Present can never afterwards be marked no-show for the same booking. | §27.4, §27.5 |
| `FR-ATT-05` | M | Inventory/Reservation, Trust/Enforcement | System<br>Customer Mobile App, Venue Dashboard | A no-show **releases the remaining interval immediately**; the resource may then be sold online, manually or otherwise under normal rules. | §27.7 |
| `FR-ATT-06` | M | State/Lifecycle, Validation/Eligibility, Inventory/Reservation, Authorization, Trust/Enforcement | Customer, Venue Staff, Platform Admin<br>Customer Mobile App, Venue Dashboard, Platform Admin | A committed no-show is terminal and **venue staff cannot undo it**. Reversal requires Platform Admin. Reversing a no-show never evicts a later customer who validly obtained the released slot. | §27.7 |
| `FR-ATT-07` | M | State/Lifecycle, Trust/Enforcement | System<br>Customer Mobile App, Venue Dashboard | **Attendance Result is an independent stored field**, never permanently derived from booking status: ATTENDED · ATTENDANCE_UNCONFIRMED · ATTENDED_INTERRUPTED · ABSENT_NO_SHOW · NOT_APPLICABLE. The token is `ABSENT_NO_SHOW`, deliberately distinct from booking status `NO_SHOW`. | §28.1 |
| `FR-ATT-08` | M | State/Lifecycle, Validation/Eligibility, Trust/Enforcement | System<br>Customer Mobile App, Venue Dashboard | Default generation: COMPLETED after valid check-in → ATTENDED · COMPLETED without verified attendance and without no-show → ATTENDANCE_UNCONFIRMED · status NO_SHOW → ABSENT_NO_SHOW · valid check-in then emergency interruption → ATTENDED_INTERRUPTED · manual booking → NOT_APPLICABLE · cancelled or rejected before attendance → NOT_APPLICABLE · interruption with Late Present only → ATTENDANCE_UNCONFIRMED · interruption with no evidence → NOT_APPLICABLE. These are defaults, not permanent formulas. | §28.2 |
| `FR-ATT-09` | M | State/Lifecycle, Validation/Eligibility, Trust/Enforcement, Audit/History | System<br>Customer Mobile App, Venue Dashboard | After audited correction the following are intentionally valid: COMPLETED with ABSENT_NO_SHOW · CANCELLED with ATTENDED_INTERRUPTED · CANCELLED with ATTENDED. An `ATTENDANCE_CORRECTED` may replace the result **without** changing an already-terminal status. | §28.3 |
| `FR-ATT-10` | M | State/Lifecycle, Trust/Enforcement, Money/Points | Customer<br>Customer Mobile App, Venue Dashboard | Evidence at interruption decides the outcome: qualifying check-in → ATTENDED_INTERRUPTED with counter reset · Late Present only → ATTENDANCE_UNCONFIRMED, counter unchanged · nothing → NOT_APPLICABLE, counter unchanged. In every case there is no customer penalty and, under a future non-zero fee, a full refund. | §28.5, §31.7 |
| `FR-ATT-11` | M | Validation/Eligibility, Authorization, Trust/Enforcement, Audit/History | Venue Staff, Platform Admin<br>Customer Mobile App, Venue Dashboard, Platform Admin | Platform Admin may reverse a check-in, reverse a Mark Present — Late, reverse a no-show, and issue an attendance correction. Each requires a mandatory reason and is audited. **Venue staff can reverse none of them.** | §27.8 |
| `FR-ATT-12` | M | Validation/Eligibility, Timer/Scheduler, Authorization, Trust/Enforcement, Money/Points | Platform Admin<br>Customer Mobile App, Venue Dashboard, Platform Admin | Reversal semantics: check-in reversed while ACTIVE keeps the booking ACTIVE and re-opens no-show eligibility within the window · check-in reversed after an ATTENDED completion creates `ATTENDANCE_CORRECTED` and reverses points by compensating transaction · Late Present reversed before `endAt` re-opens no-show eligibility · no-show reversed before original `endAt` leaves the booking CANCELLED under `ADMIN_NO_SHOW_REVERSAL` · no-show reversed after original `endAt` yields COMPLETED / ATTENDANCE_UNCONFIRMED unless attendance is separately established. | §27.8 |
| `FR-ATT-13` | M | State/Lifecycle, Authorization, Trust/Enforcement | Platform Admin<br>Customer Mobile App, Venue Dashboard, Platform Admin | After a wrong no-show the admin chooses explicitly: **attendance confirmed with reallocation** → ATTENDED_INTERRUPTED · **attendance confirmed, session actually completed** → ATTENDED · **penalty removal only** → ATTENDANCE_UNCONFIRMED. | §27.9 |
| `FR-ATT-14` | M | Functional rule | Venue Staff<br>Customer Mobile App, Venue Dashboard | Disabling the venue account does **not** terminate an ACTIVE session. The physical venue may still be operating. | §32.1 |
| `FR-ATT-15` | M | Trust/Enforcement | System<br>Customer Mobile App, Venue Dashboard | Attendance evidence recorded **before** the disable stays authoritative — check-in, Late Present and no-show alike. Account disable never erases or reverses earlier evidence. | §32.5 |
| `FR-ATT-16` | M | Validation/Eligibility, Inventory/Reservation, Trust/Enforcement, Audit/History | System<br>Customer Mobile App, Venue Dashboard | Protection applies only when **all** hold: the booking was ACTIVE · the account became DISABLED during the session · the disable materially prevented reliable attendance recording · no authoritative check-in existed beforehand · no authoritative no-show existed beforehand · the final effective outcome is **not** ATTENDED, ATTENDED_INTERRUPTED or ABSENT_NO_SHOW. Eligible outcomes are ATTENDANCE_UNCONFIRMED and NOT_APPLICABLE from operational early termination. | §32.6 |
| `FR-ATT-17` | M | Timer/Scheduler, Inventory/Reservation, Authorization, Trust/Enforcement, Audit/History | Platform Admin<br>Customer Mobile App, Venue Dashboard, Platform Admin | "Materially prevented reliable recording" is evaluated from the booking's `accountAccessRestrictionIntervals[]` against the check-in window and the no-show window: a disable interval overlapping the window in which the missing evidence would have been recorded is what makes recording unreliable. Genuinely ambiguous cases resolve through admin correction, never an inferred outcome. | §32.6 |
| `FR-ATT-18` | M | Timer/Scheduler, Inventory/Reservation, Trust/Enforcement, Audit/History, UI/Presentation | Venue Staff<br>Customer Mobile App, Venue Dashboard | Protection is **not** limited to sessions reaching normal `endAt`; it still applies when the session terminates early through a venue emergency, a resource emergency or owner review. The attendance result of an early termination remains governed by the normal interruption model — the protection event never changes it. | §32.7 |
| `FR-ATT-19` | M | State/Lifecycle, Timer/Scheduler, Trust/Enforcement, Money/Points, Audit/History | System<br>Customer Mobile App, Venue Dashboard | The trust event `BOOKING_ACCOUNT_DISABLE_ATTENDANCE_PROTECTION` resets the consecutive no-show counter to 0 and does nothing else: no points, no progress, no qualification, no change to attendance result or booking status. `effectiveAt = booking.startAt`; `occurredAt` = attendance-resolution time. | §32.8 |
| `FR-ATT-20` | M | Trust/Enforcement, Audit/History | System<br>Customer Mobile App, Venue Dashboard | The event is created **when the effective attendance outcome resolves**, not at disable time. At disable time the system records only a protection candidate and audit context. | §32.9 |
| `FR-ATT-21` | M | Authorization, Audit/History | Platform Admin<br>Customer Mobile App, Venue Dashboard, Platform Admin | A booking may span several access intervals, stored as an ordered `accountAccessRestrictionIntervals[]` with `disabledAt`, nullable `reenabledAt`, admin actor or source, reason and correlation ID. All intervals are evaluated at resolution, and **at most one** effective protection event is emitted per booking. | §32.9 |
| `FR-ATT-22` | M | Trust/Enforcement, Audit/History | System<br>Customer Mobile App, Venue Dashboard | If the booking ultimately produces ATTENDED or ATTENDED_INTERRUPTED, the protection event is **not** additionally applied — exactly one counter-reset mechanism per booking. | §32.12 |
| `FR-ATT-23` | M | State/Lifecycle, Validation/Eligibility, Timer/Scheduler, Trust/Enforcement | System<br>Customer Mobile App, Venue Dashboard | Later evidence governs: a valid check-in after re-enable within the window → normal ATTENDED · a Late Present after re-enable → ATTENDANCE_UNCONFIRMED with the protection reset still applicable · a valid no-show after re-enable within the window → authoritative. **An expired check-in window is never retroactively reopened.** | §32.10 |
| `FR-ATT-24` | M | Validation/Eligibility, Authorization, Trust/Enforcement | Customer, Platform Admin<br>Customer Mobile App, Venue Dashboard, Platform Admin | An admin correction to ABSENT_NO_SHOW **neutralises** the protection reset. No customer ever receives both a protected reset and a valid no-show increment for the same effective outcome. | §32.11 |
| `FR-ATT-25` | M | Inventory/Reservation, Trust/Enforcement, Notification | Customer<br>Customer Mobile App, Venue Dashboard | When a disable overlaps an ACTIVE booking, the customer receives `ACTIVE_SESSION_ATTENDANCE_RECORDING_AFFECTED` (In-App + Push). The notification **must not** say the booking was cancelled unless another rule actually cancels it. | §32.13 |
| `FR-ATT-26` | M | Authorization, Trust/Enforcement | Venue Staff, Platform Admin<br>Customer Mobile App, Venue Dashboard, Platform Admin | Platform Admin receives an operational alert with venue, Booking ID, session interval, disable time, current attendance evidence, and whether protection may be required. | §32.13 |
| `FR-ATT-27` | M | Timer/Scheduler, Authorization, Trust/Enforcement | Customer, Venue Staff, Platform Admin<br>Customer Mobile App, Venue Dashboard, Platform Admin | Platform Admin receives **non-blocking** operational signals: unusually high check-in rate · high check-in reversal rate · unusual concentration of Verified Player progress from one venue · repeated check-ins immediately before the 15-minute deadline · high Late Present rate · repeated Late Present on the same users · Late Present reversals · suspicious repeated customer/venue patterns. | §27.10 |
| `FR-ATT-28` | M | State/Lifecycle, Validation/Eligibility, Authorization, Trust/Enforcement, UI/Presentation | Venue Staff, Platform Admin, System<br>Customer Mobile App, Venue Dashboard, Platform Admin | Monitoring may flag, alert and surface for review. It must **never** automatically revoke Verified Player status, suspend a venue, reverse check-ins or ban users. Every consequence requires explicit admin action. | §27.10 |

**Domain acceptance obligations from PRD v1.1**

- Check-in at `startAt + 14:59` succeeds; at `+15:00` it is refused and Mark Present — Late becomes available
- A booking with Mark Present — Late cannot be marked no-show, and completes as ATTENDANCE_UNCONFIRMED with no points
- A no-show at 8:16 frees 8:16–9:00 for immediate resale; reversing it at 8:40 does not evict the new booking
- Two disable/re-enable cycles inside one active session produce **one** protection event and **one** counter reset
- A session where the account was disabled only outside both attendance windows does not qualify for protection
- An admin correcting a protected booking to ABSENT_NO_SHOW leaves the customer with a no-show increment and no reset
- The customer notification for an overlapping disable never contains the word "cancelled"

## 4.7 Manual Booking — 11 requirements

| ID | Pri | Type | Actor / Surface | Functional requirement | Governing v5.0 |
|---|---:|---|---|---|---|
| `FR-MAN-01` | M | Inventory/Reservation | Customer, Venue Staff<br>Venue Dashboard | Manual booking is an **internal venue operation** (category C) with `source = MANUAL`. It does not pass through PENDING, reserves the resource immediately, and may have no registered customer. Optional customer name and phone may be captured. | §30.1 |
| `FR-MAN-02` | M | State/Lifecycle, Validation/Eligibility, Timer/Scheduler, Inventory/Reservation, Authorization | Venue Staff<br>Venue Dashboard | Creation requires: venue account **ENABLED** · marketplace **ACTIVE, INACTIVE or SUSPENDED** — **ONBOARDING is explicitly not allowed** · resource base state ACTIVE · operational restriction NONE · no venue or resource selling block · resource supports the activity · no blocking reservation · no overlap with closure, emergency closure, resource emergency outage or maintenance · start NOW or FUTURE · duration 1–6 hours in 30-minute increments · required pricing configuration present. | §30.2 |
| `FR-MAN-03` | M | State/Lifecycle, Timer/Scheduler, Configuration | Venue Staff<br>Venue Dashboard | Manual booking **does not require the interval to fit published opening hours**, and area marketplace status does not block it. | §30.3 |
| `FR-MAN-04` | M | State/Lifecycle, Timer/Scheduler | Customer, Venue Staff<br>Venue Dashboard | Reachable states: future → CONFIRMED → ACTIVE → COMPLETED · current walk-in with `startAt` = server time → ACTIVE → COMPLETED · before start → CONFIRMED → CANCELLED. Manual bookings never use PENDING, VENUE_RESPONSE_EXPIRED, AWAITING_CUSTOMER_CONFIRMATION, REJECTED, NO_SHOW or check-in. | §30.4 |
| `FR-MAN-05` | M | State/Lifecycle, Audit/History | Venue Staff<br>Venue Dashboard | **No back-dating.** A manual booking may never be created over a historical interval, even where the original occupant is now terminal. | §30.5 |
| `FR-MAN-06` | M | Trust/Enforcement, Money/Points | Venue Staff<br>Venue Dashboard | Manual bookings contribute nothing to Verified Player progress, completed-booking points or no-show progression. Attendance is NOT_APPLICABLE, including when an ACTIVE manual booking is terminated operationally. | §30.6 |
| `FR-MAN-07` | M | Functional rule | Customer, Venue Staff<br>Venue Dashboard | Manual bookings do not count against the customer's 4-commitment limit. | §30.7 |
| `FR-MAN-08` | M | Authorization, Money/Points | Customer, Venue Staff, Venue Owner authorization<br>Venue Dashboard | Venue staff may cancel a manual booking that is **CONFIRMED or ACTIVE** **without** the Owner Password, reason `VENUE_CANCELLED_MANUAL_BOOKING`. Its booking-fee treatment is **NOT_APPLICABLE**, because it carries no customer E7GZ fee. *(Amended by PCR-007.)* | §30.8; PCR-007 |
| `FR-MAN-09` | M | Timer/Scheduler, Audit/History | Venue Staff<br>Venue Dashboard | A manual booking inside a published session stores the normal operating-session snapshot and `businessDate` = operating day date. Outside published hours, session fields are NULL, timezone is Africa/Cairo, and `businessDate` = the Africa/Cairo local calendar date of `startAt`. **Every manual booking always has a `businessDate`.** | §30.9 |
| `FR-MAN-10` | M | Validation/Eligibility, Inventory/Reservation, Audit/History, Configuration | Venue Staff<br>Venue Dashboard | Missing pricing configuration blocks creation with the API/validation error `MANUAL_BOOKING_PRICING_CONFIGURATION_MISSING`. No reservation and no booking record are created, so the token is neither a reason code nor a booking event. | §30.2 |
| `FR-MAN-11` | M | UI/Presentation | Venue Staff<br>Venue Dashboard | There is **no additional product maximum future horizon** for manual booking beyond the date range the Dashboard supports. The product rule remains **NOW or FUTURE, never past**. A technical date-picker bound may be applied consistently as a UI matter without changing the product rule. | §30.10, §57.2 |

**Domain acceptance obligations from PRD v1.1**

- A manual booking at an ONBOARDING venue is refused; at a SUSPENDED venue it succeeds
- A manual booking overlapping a PENDING online booking is refused
- A manual booking at 3 AM for a venue closing at 2 AM succeeds and carries a `businessDate`
- A walk-in created at the current server time begins ACTIVE, not CONFIRMED
- A completed manual booking grants no points and no Verified Player progress

## 4.8 Venue Model, Configuration and Dashboard — 47 requirements

| ID | Pri | Type | Actor / Surface | Functional requirement | Governing v5.0 |
|---|---:|---|---|---|---|
| `FR-VEN-01` | M | Functional rule | Venue Staff, Venue Owner authorization<br>Venue Dashboard, Platform Admin | One shared venue account per venue. No individual cashier, manager or separate owner accounts. | §10 |
| `FR-VEN-02` | M | Validation/Eligibility, Timer/Scheduler, Authorization | Venue Staff, Venue Owner authorization<br>Venue Dashboard, Platform Admin | The Owner Password is required **per protected action**. There is no 15-minute unlock, no 30-minute unlock and no owner session mode; each action revalidates. | §54.3 |
| `FR-VEN-03` | M | Timer/Scheduler, Inventory/Reservation, Authorization, Money/Points, UI/Presentation | Customer, Venue Staff, Venue Owner authorization<br>Venue Dashboard, Platform Admin | Owner Password is **required** for: venue pricing changes · room price override set and reset · cancelling a CONFIRMED online booking · an opening-hours save that cancels protected customer bookings · a normal-closure save that cancels protected customer bookings · disable, re-enable, archive and restore resource · console change · protected resource configuration where defined · maintenance exceeding the rolling threshold · venue cancellation of a CONFIRMED tournament · tournament operational termination · resolving `OWNER_REVIEW_REQUIRED`. | §54.1 |
| `FR-VEN-04` | M | Timer/Scheduler, Inventory/Reservation, Authorization, Trust/Enforcement, Audit/History | Venue Staff, Venue Owner authorization, System<br>Venue Dashboard, Platform Admin | Owner Password is **not required** for: accept · reject · reject after expiry · late confirm · manual booking creation and pre-start cancellation · check-in · Mark Present — Late · no-show · maintenance within the threshold · opening-hours or closure edits with no protected cancellation · emergency closure · resource emergency outage · venue cancellation of a WAITING_FOR_PLAYERS tournament · any system operational consequence cascade. All remain audited. | §54.2 |
| `FR-VEN-05` | M | Validation/Eligibility, Authorization, Audit/History | Venue Staff, Venue Owner authorization, Platform Admin<br>Venue Dashboard, Platform Admin | Successful and failed Owner Password attempts are audited; validation is rate-limited. A Platform Admin reset notifies the venue. | §54.3 |
| `FR-VEN-46` | M | State/Lifecycle, Validation/Eligibility, Authorization, Audit/History | Venue Staff, Venue Owner authorization, Platform Admin<br>Venue Dashboard, Platform Admin | The venue may **change its own Owner Password**. The new password becomes active **immediately**, the old one stops working at once, and every subsequent protected action validates against the new one. The change is audited. This is distinct from a Platform Admin reset, which is covered by FR-ADM-03. | §10.2 |
| `FR-VEN-06` | M | State/Lifecycle, Authorization | Venue Staff<br>Venue Dashboard, Platform Admin | Marketplace status (ONBOARDING / ACTIVE / INACTIVE / SUSPENDED) and account status (ENABLED / DISABLED) are **independent**. Disabling the account does not rewrite marketplace status, and suspending the marketplace does not disable the account. | §11 |
| `FR-VEN-07` | M | Inventory/Reservation, Authorization, UI/Presentation, Configuration | Venue Staff, Platform Admin<br>Venue Dashboard, Platform Admin | ONBOARDING: not discoverable, no online booking, no player tournament requests, configuration may continue, not operational marketplace inventory. **INACTIVE**: hidden from discovery, no new online booking or tournament requests, Dashboard available, manual booking available, existing bookings and tournaments remain — non-punitive. **SUSPENDED**: as INACTIVE plus no new marketplace tournament creation, reviews remain visible and new reviews remain allowed — administrative and punitive. | §11 |
| `FR-VEN-08` | M | Trust/Enforcement, UI/Presentation | Venue Staff<br>Venue Dashboard, Platform Admin | Account **DISABLED** blocks Dashboard login and therefore every ordinary Dashboard action, including accept, reject, late confirm, check-in, Mark Present — Late and no-show. It is an **access restriction, not a physical venue outage**; existing commitments remain governed by their own lifecycle. | §11, §32.1 |
| `FR-VEN-09` | M | State/Lifecycle, Timer/Scheduler, Audit/History | Customer, Venue Staff, System<br>Venue Dashboard, Platform Admin | Effects of a disable by booking state: PENDING → VENUE_RESPONSE_EXPIRED immediately with event `VENUE_RESPONSE_INTERRUPTED_BY_ACCOUNT_DISABLE` · AWAITING_CUSTOMER_CONFIRMATION → CANCELLED `VENUE_ACCOUNT_DISABLED` · VENUE_RESPONSE_EXPIRED still disabled at `startAt` → CANCELLED `VENUE_ACCOUNT_DISABLED` · CONFIRMED unaffected and may still start · ACTIVE unaffected and continues. | §32.2 |
| `FR-VEN-10` | M | State/Lifecycle | Venue Staff<br>Venue Dashboard, Platform Admin | `VENUE_ACCOUNT_DISABLED` applies only to an unresolved **pre-confirmation** response lifecycle. It is never a general CONFIRMED → ACTIVE cancellation reason. | §32.3 |
| `FR-VEN-11` | M | Timer/Scheduler | Venue Staff<br>Venue Dashboard, Platform Admin | Marketplace SUSPENDED or INACTIVE creates **no venue blame**: it never causes `VENUE_DID_NOT_RESPOND_IN_TIME` and needs no new expiry reason. | §32.3 |
| `FR-VEN-12` | M | State/Lifecycle, Authorization, Audit/History, Configuration | Customer, Venue Staff, Platform Admin<br>Venue Dashboard, Platform Admin | **Areas** are controlled by Platform Admin and carry status **Active** or **Inactive**. Only Active areas are available for new venue assignment. Inactive areas remain in historical data and historical references are never rewritten. Area status governs **customer discovery only** and never blocks manual booking. | §12 |
| `FR-VEN-47` | M | State/Lifecycle, Validation/Eligibility, Audit/History, Configuration | Venue Staff<br>Venue Dashboard, Platform Admin | An area may be deactivated **only** when no venue assigned to it has marketplace status ACTIVE or SUSPENDED. Such venues must first move area or become INACTIVE. Historical references are unchanged. | §12, §51.11 |
| `FR-VEN-13` | M | State/Lifecycle, Inventory/Reservation, UI/Presentation | Venue Staff, Venue Owner authorization<br>Venue Dashboard, Platform Admin | Resource Base State (ACTIVE / DISABLED / ARCHIVED) is separate from Resource Operational Restriction (NONE / OWNER_REVIEW_REQUIRED). A resource may be ACTIVE and still unsellable. | §13 |
| `FR-VEN-14` | M | Validation/Eligibility, Inventory/Reservation, Configuration | Venue Staff, System<br>Venue Dashboard, Platform Admin | Disable, Archive and Console Change are **blocked** by any current or future blocking reservation — CONFIRMED, ACTIVE, PENDING, AWAITING, Player Tournament Approval Hold, Tournament Hold or resource Maintenance. Existing reservations are **never** automatically cancelled; the venue must resolve them first. A normal venue closure alone does not block these actions, and `VENUE_RESPONSE_EXPIRED` does not block them. | §13.4 |
| `FR-VEN-15` | M | Validation/Eligibility, Authorization, Money/Points, Audit/History, Configuration | Venue Staff, Venue Owner authorization<br>Venue Dashboard, Platform Admin | A console change (PS4 ↔ PS5) requires the Owner Password and **clears all existing room price overrides**; the room returns to venue default pricing for the new console. Historical bookings retain their stored console, mode and price. | §13.5 |
| `FR-VEN-16` | M | Inventory/Reservation, Audit/History, Configuration | Venue Staff, Venue Owner authorization<br>Venue Dashboard, Platform Admin | Resource disable, re-enable, archive and restore are audited. Maintenance is not owner-protected within the threshold, and is audited. | §53.3 |
| `FR-VEN-41` | M | Inventory/Reservation | Venue Staff<br>Venue Dashboard, Platform Admin | Resource naming is fixed: PlayStation resources are **Room 1, Room 2, …** · Billiards resources are **Pool Table 1, Pool Table 2, …** · Ping Pong resources are **Table 1, Table 2, …**. Supported PlayStation console types are **PS5 and PS4**, and only these. The console type is shown **wherever resource information appears**, across all three products. | §3.2 |
| `FR-VEN-42` | M | Validation/Eligibility, Configuration | Venue Staff, System<br>Venue Dashboard, Platform Admin | PlayStation requires a play mode of **Single** or **Multiplayer**. This is a **pricing mode, not an exact player count**, and the system must never validate, capture or enforce a party size against it. Play mode does **not** apply to Billiards or Ping Pong. | §3.3 |
| `FR-VEN-43` | M | Money/Points, Configuration | Venue Staff<br>Venue Dashboard, Platform Admin | **PlayStation pricing** is configured as **venue-level defaults per console type × play mode** — PS5 Single, PS5 Multiplayer, PS4 Single, PS4 Multiplayer. A PlayStation room may carry a **Room Price Override** that replaces the venue default for that room; **with no override the room inherits the venue console default**. | §4.1 |
| `FR-VEN-44` | M | Timer/Scheduler, Inventory/Reservation, Money/Points | Venue Staff<br>Venue Dashboard, Platform Admin | **Billiards and Ping Pong** are priced at a **venue-level hourly rate only** — no console type, no play mode, and **no table-level override** in this release. | §4.2 |
| `FR-VEN-45` | M | Validation/Eligibility, Money/Points, Configuration | Venue Staff<br>Venue Dashboard, Platform Admin | Manual booking, online booking and tournament all price from this same configuration, and creation is **blocked** where the configuration required for the activity does not exist. | §4.1, §4.2, §30.2 |
| `FR-VEN-17` | M | Timer/Scheduler, Configuration | Venue Staff<br>Venue Dashboard, Platform Admin | Opening hours use a default schedule plus weekday overrides; each weekday may use the default, use custom hours or be closed. Cross-midnight schedules are supported. | §14 |
| `FR-VEN-18` | M | Validation/Eligibility, Timer/Scheduler, Inventory/Reservation | Venue Staff<br>Venue Dashboard, Platform Admin | Each **operating day** creates at most **one operating session**, identified by operating day, local start, local end and the Africa/Cairo timezone. Session length is greater than 0 and no more than 24 hours. Sessions may touch at boundaries but **may not overlap**. | §14 |
| `FR-VEN-19` | M | State/Lifecycle, Validation/Eligibility, Timer/Scheduler, Inventory/Reservation | Venue Staff<br>Venue Dashboard, Platform Admin | Non-overlap validation is **cyclical across the full week**, including the Sunday → Monday wrap. A cross-midnight session may not overlap the next session. | §14 |
| `FR-VEN-20` | M | Timer/Scheduler, Audit/History | Venue Staff<br>Venue Dashboard, Platform Admin | A closed weekday prevents a **new** session starting that day, but an overnight session from the previous day legitimately continues into it. | §14 |
| `FR-VEN-21` | M | Validation/Eligibility, Timer/Scheduler, Inventory/Reservation | Venue Staff<br>Venue Dashboard, Platform Admin | DST: non-existent local start times are not offered; ambiguous repeated local times are not offered as duplicate slots; a declared full-day session stays valid when real elapsed time is 23 or 25 hours; absolute instants remain authoritative for inventory. | §14.4, §14.3 |
| `FR-VEN-22` | M | Timer/Scheduler, Audit/History | Venue Staff<br>Venue Dashboard, Platform Admin | Booking history stores the operating day, local session start, local session end and timezone as a **historical snapshot**; later opening-hours changes never rewrite it. | §14 |
| `FR-VEN-23` | M | Validation/Eligibility, Timer/Scheduler, Inventory/Reservation, Authorization, Notification | Venue Staff, Venue Owner authorization<br>Venue Dashboard, Platform Admin | An opening-hours change is **all-or-nothing** and evaluates in this order: **1.** validate proposed sessions · **2.** check tournament conflicts · **3.** block immediately on any tournament conflict · **4.** compute affected ordinary reservations · **5.** show a complete impact preview · **6.** determine the Owner Password requirement · **7.** request the Owner Password where protected cancellations exist · **8.** commit the change and all cancellations atomically · **9.** notify. If any step fails, **nothing changes**. | §14.7 |
| `FR-VEN-24` | M | Inventory/Reservation | Customer, Venue Staff<br>Venue Dashboard, Platform Admin | Affected reservations are future PENDING, AWAITING_CUSTOMER_CONFIRMATION, CONFIRMED and MANUAL. ACTIVE sessions are not cancelled. VENUE_RESPONSE_EXPIRED holds no reservation and is not cancelled. | §14.8 |
| `FR-VEN-25` | M | Validation/Eligibility, Timer/Scheduler, Authorization, Configuration | Venue Staff, Venue Owner authorization<br>Venue Dashboard, Platform Admin | Editing opening hours needs no Owner Password, but a save that cancels a CONFIRMED online booking or an AWAITING booking requires it. Pending online and manual cancellations alone do not. Cancellation reason: `VENUE_OPENING_HOURS_CHANGED`. | §14.9, §14.8 |
| `FR-VEN-26` | M | Validation/Eligibility, Inventory/Reservation, Authorization, UI/Presentation | Venue Staff, Venue Owner authorization<br>Venue Dashboard, Platform Admin | Before commit the complete affected set is **revalidated**. If new affected reservations appeared since the preview, the commit is **aborted** and a refreshed preview is shown; any Owner Password must be entered against the refreshed set. **No booking outside the approved preview may be silently cancelled.** | §14.7 |
| `FR-VEN-27` | M | Inventory/Reservation | Venue Staff<br>Venue Dashboard, Platform Admin | A tournament conflict **blocks** the save, including a PENDING_VENUE_APPROVAL tournament holding approval holds. The venue must resolve or cancel the tournament first. | §14.10 |
| `FR-VEN-28` | M | Timer/Scheduler, Inventory/Reservation | Customer, Venue Staff<br>Venue Dashboard, Platform Admin | An AWAITING booking cancelled by an hours change stops its Late Confirmation timer immediately, releases the hold, and notifies the customer explicitly. | §14.9 |
| `FR-VEN-29` | M | Timer/Scheduler, Inventory/Reservation, Configuration | Venue Staff<br>Venue Dashboard, Platform Admin | A closure may be full-day or partial-time, applies to the whole venue, and blocks all resources for its interval. | §15 |
| `FR-VEN-30` | M | Validation/Eligibility, Inventory/Reservation, Authorization, Configuration | Customer, Venue Staff, Venue Owner authorization<br>Venue Dashboard, Platform Admin | Creating or editing a closure needs no Owner Password unless it cancels protected customer reservations, in which case the final **Save & Cancel Affected Bookings** requires it. Cancellation reason `VENUE_CLOSURE`; customers are notified. | §15 |
| `FR-VEN-31` | M | Validation/Eligibility, Configuration | Venue Staff<br>Venue Dashboard, Platform Admin | A closure may **not** be created retroactively over an ACTIVE session, and must begin after existing active sessions end unless those have been resolved through an allowed flow. | §15 |
| `FR-VEN-32` | M | Configuration | Venue Staff<br>Venue Dashboard, Platform Admin | A proposed closure conflicting with an existing tournament **blocks the save**. | §15 |
| `FR-VEN-33` | M | Validation/Eligibility, Inventory/Reservation, Configuration | Venue Staff<br>Venue Dashboard, Platform Admin | A closure may overlap existing maintenance; creating maintenance over an existing closure is blocked for the overlapping period. | §39.4 |
| `FR-VEN-34` | M | Timer/Scheduler, Inventory/Reservation, Money/Points, Audit/History, UI/Presentation | Venue Staff<br>Venue Dashboard, Platform Admin | Dashboard modules: Overview · Pending Requests · Missed / Expired Requests · **Accept / Reject** · **Late Confirmation** · Bookings · Manual Booking · Rooms / Resource Management · Pricing · Availability · Maintenance & Closures · **Opening Hours** · Reviews (view only) · Tournaments · Booking History · **Revenue Summary** · Settings, in English and العربية. §50.1 names the core module set; §2.2 names the full launch surface set and both are frozen. | §2.2, §50.1 |
| `FR-VEN-35` | M | Timer/Scheduler, Money/Points, UI/Presentation | Venue Staff<br>Venue Dashboard, Platform Admin | Overview shows today's revenue, this month's revenue, today's bookings, pending requests, today's schedule, quick manual booking and quick create tournament. | §50.2 |
| `FR-VEN-36` | M | Money/Points, UI/Presentation | Venue Staff, System<br>Venue Dashboard, Platform Admin | Revenue covers online, manual and tournament revenue with an optional activity breakdown, aggregated on **`businessDate`**. The Dashboard is explicitly not a POS, accounting system, ERP, tax system or reconciliation system, and tracks no payment method. | §50.3 |
| `FR-VEN-37` | M | Inventory/Reservation, UI/Presentation, Configuration | Customer, Venue Staff<br>Venue Dashboard, Platform Admin | The availability schedule displays Free · Pending · Awaiting Customer Confirmation · Confirmed · Active · Manual · Tournament Hold · Maintenance · Closure. | §50.4 |
| `FR-VEN-38` | M | State/Lifecycle, Timer/Scheduler, Inventory/Reservation, Audit/History | Venue Staff<br>Venue Dashboard, Platform Admin | Booking history filters: date range (today, yesterday, last 7 days, this month, custom) · activity · source · resource · status · search. | §50.5 |
| `FR-VEN-39` | M | UI/Presentation | Venue Staff<br>Venue Dashboard, Platform Admin | The venue is **view only** on reviews, seeing average rating, total reviews, rating distribution, and per review the username, rating, comment and date. | §49.7 |
| `FR-VEN-40` | M | State/Lifecycle, UI/Presentation | Venue Staff<br>Venue Dashboard, Platform Admin | Full English (LTR) and العربية (RTL) support across every listed surface element. Free-text stored data is unchanged. Language resolves: venue account preference → browser storage → English default. | §50.6 |

**Domain acceptance obligations from PRD v1.1**

- Two protected actions in a row each demand the Owner Password separately
- Disabling a resource with a future confirmed booking is refused and names the blocking reservation
- A console change clears the room override and leaves historical bookings untouched
- An hours change conflicting with a PENDING_VENUE_APPROVAL tournament is blocked before any preview of booking impact
- A new confirmed booking created between preview and commit aborts the commit and forces a refreshed preview
- A closure overlapping an active session is refused
- The Dashboard renders correctly in Arabic RTL across all modules, with stored free text unchanged

## 4.9 Maintenance, Emergencies and Operational Restrictions — 44 requirements

| ID | Pri | Type | Actor / Surface | Functional requirement | Governing v5.0 |
|---|---:|---|---|---|---|
| `FR-OPS-01` | M | Inventory/Reservation, Audit/History, Configuration | Venue Staff<br>Venue Dashboard, Platform Admin | Maintenance is normal venue operation at resource level. Create, edit and delete are audited. | §39.1 |
| `FR-OPS-02` | M | Validation/Eligibility, Inventory/Reservation, Configuration | Venue Staff<br>Venue Dashboard, Platform Admin | Maintenance may **never** overlap an existing blocking reservation; creation is blocked with a clear message and the reservations must be resolved through their normal flow. **Maintenance may never silently cancel a booking.** | §39.1 |
| `FR-OPS-03` | M | Timer/Scheduler, Inventory/Reservation, Authorization, Configuration | Venue Staff, Venue Owner authorization<br>Venue Dashboard, Platform Admin | Owner Password is required when total maintenance for a resource exceeds **24 hours inside any rolling 48-hour window**. Back-to-back or overlapping windows forming one continuous period over 24 hours count as one period. The threshold is **per resource**. | §39.2 |
| `FR-OPS-04` | M | Validation/Eligibility, Inventory/Reservation, Authorization | Venue Staff, Venue Owner authorization<br>Venue Dashboard, Platform Admin | An edit that extends a continuous period past the threshold requires the Owner Password. | §39.2 |
| `FR-OPS-05` | M | Inventory/Reservation, Configuration | Venue Staff<br>Venue Dashboard, Platform Admin | Maintenance created from a resource emergency outage **counts** toward the same rolling total. There is no bypass between emergency-generated and manual maintenance. | §39.3 |
| `FR-OPS-06` | M | Validation/Eligibility, Authorization, Audit/History, Configuration | Venue Staff, Venue Owner authorization, Platform Admin<br>Venue Dashboard, Platform Admin | Emergency Closure requires **no Owner Password**, may start only **NOW**, cannot be scheduled, requires a mandatory reason and full audit, and notifies the venue owner and Platform Admin. | §40.1 |
| `FR-OPS-07` | M | Timer/Scheduler, Configuration | Venue Staff<br>Venue Dashboard, Platform Admin | Expected End Time is **mandatory**, later than start and no later than the end of the current operating session. Longer outages must use a normal closure, an opening-hours change or another future mechanism. | §40.2 |
| `FR-OPS-08` | M | Inventory/Reservation, Configuration | Venue Staff<br>Venue Dashboard, Platform Admin | The cascade covers only reservations overlapping `[emergencyStart, expectedEnd)`. It stops new online and manual bookings for the period, cancels affected PENDING, AWAITING and CONFIRMED bookings, terminates affected ACTIVE sessions, cancels affected tournaments and releases future holds, under `VENUE_EMERGENCY_CLOSURE`. | §40.3, §40.4 |
| `FR-OPS-09` | M | Inventory/Reservation, Notification, Audit/History, Configuration | Venue Staff<br>Venue Dashboard, Platform Admin | The cascade is **all-or-nothing**: the closure, cancellations, active terminations, tournament cancellations, releases and history persist atomically **before** notifications dispatch. A partial cascade is not an acceptable outcome. | §40.5 |
| `FR-OPS-10` | M | Functional rule | Venue Staff<br>Venue Dashboard, Platform Admin | Emergency cancellation is immediate and irreversible; ending the emergency early never restores what was cancelled. | §40.6 |
| `FR-OPS-44` | M | Timer/Scheduler | Customer, Venue Staff<br>Venue Dashboard, Platform Admin | An AWAITING_CUSTOMER_CONFIRMATION booking caught by an emergency cascade is cancelled **immediately under the emergency reason**. Its Late Confirmation timer stops and the customer **never** receives `CUSTOMER_RECONFIRM_TIMEOUT`: the emergency reason wins. | §40.4 |
| `FR-OPS-11` | M | Validation/Eligibility, Inventory/Reservation, Authorization, Notification, Audit/History | Venue Staff, Venue Owner authorization, Platform Admin<br>Venue Dashboard, Platform Admin | A resource emergency outage requires **no Owner Password** and carries a mandatory reason, timestamp, affected resource, venue account identity, full audit, owner notification and Platform Admin visibility. Staff must supply an **expected return-to-service time**. For an **unprotected** emergency outage this may extend only to the end of the current operating session; a longer resource outage must be carried as **maintenance under normal maintenance authorization**, including the Owner Password where the rolling threshold is exceeded. | §41.2, §41.4, §39.2 |
| `FR-OPS-12` | M | Inventory/Reservation, Configuration | Venue Staff<br>Venue Dashboard, Platform Admin | It may terminate the ACTIVE booking on that resource, cancel future overlapping reservations, affect tournament use, block new availability and transition the resource into maintenance — under `RESOURCE_EMERGENCY_OUTAGE`. Other venue resources keep operating. | §41.3 |
| `FR-OPS-13` | M | Functional rule | Venue Staff<br>Venue Dashboard, Platform Admin | There is **no hard venue-wide emergency rate limit**, because such a limit could block a genuine emergency. | §41.2 |
| `FR-OPS-14` | M | Validation/Eligibility, Timer/Scheduler, Inventory/Reservation, Authorization | Venue Staff, Venue Owner authorization, Platform Admin<br>Venue Dashboard, Platform Admin | Platform Admin receives a risk alert when one venue causes **3 or more tournament cancellations through resource emergency outage within a rolling 30 days**. The alert is investigative and non-blocking; it never suspends the venue, requires no Owner Password for future emergencies, and reverses nothing. | §41.6 |
| `FR-OPS-15` | M | State/Lifecycle, Inventory/Reservation | Venue Staff<br>Venue Dashboard, Platform Admin | A relevant emergency **must** create the corresponding operational selling block — venue emergency → venue block; resource emergency → that resource's block only. It is mandatory, not optional. | §38.2, §38.3 |
| `FR-OPS-16` | M | State/Lifecycle, Inventory/Reservation, Audit/History | Venue Staff<br>Venue Dashboard, Platform Admin | A selling block prevents new online bookings, new manual bookings, new tournament holds and approval holds, Late Confirm requiring the affected venue or resource, venue accept, and CONFIRMED → ACTIVE — for the affected venue or resource. | §38.4 |
| `FR-OPS-17` | M | Inventory/Reservation | Venue Staff, System<br>Venue Dashboard, Platform Admin | Reservations **outside** the declared emergency interval are not automatically cancelled when the emergency enters AWAITING_CONFIRMATION. A block may persist across a session end and into the next session without extending the earlier cascade. | §38.5 |
| `FR-OPS-18` | M | State/Lifecycle | Venue Staff<br>Venue Dashboard, Platform Admin | Emergency status is ACTIVE → (expected end passes) → AWAITING_CONFIRMATION → ENDED or CONVERTED. Blocks are ON for ACTIVE and AWAITING_CONFIRMATION. **Expected end never auto-reopens anything**; staff resolution is mandatory. | §42.1, §42.2 |
| `FR-OPS-19` | M | State/Lifecycle, Validation/Eligibility, Inventory/Reservation | Venue Staff<br>Venue Dashboard, Platform Admin | On ENDED the emergency selling block **must** end. Staff may only end an emergency when the venue or resource is operational again, or when a valid replacement operational state or block commits atomically. | §42.3 |
| `FR-OPS-20` | M | Inventory/Reservation, UI/Presentation, Configuration | Venue Staff, Venue Owner authorization<br>Venue Dashboard, Platform Admin | If a resource is still unusable but below the owner-review threshold, the emergency **must not** move to ENDED with no replacement block; it stays AWAITING_CONFIRMATION until repair, or a successful maintenance conversion, disable or archive. | §42.3 |
| `FR-OPS-21` | M | Validation/Eligibility, Inventory/Reservation, Audit/History | Venue Staff<br>Venue Dashboard, Platform Admin | **No sellable gap**: there must never be a period where the emergency block has ended, the resource is still unusable, and no replacement restriction exists. The enforceable guarantee is that E7GZ will not release the block unless staff assert the resource is operational or a replacement commits. END EMERGENCY is that audited, attributable staff assertion. | §38.6 |
| `FR-OPS-22` | M | Validation/Eligibility, Inventory/Reservation, UI/Presentation | Venue Staff<br>Venue Dashboard, Platform Admin | Extending an emergency computes the newly affected interval and reservations, previews the impact, takes confirmation, and applies the new cancellations atomically. Previously cancelled reservations stay cancelled. Emergency mode may not be extended beyond the current operating session as an unprotected cancellation mechanism. | §42.4 |
| `FR-OPS-23` | M | State/Lifecycle, Validation/Eligibility, Timer/Scheduler, Inventory/Reservation, Configuration | Venue Staff<br>Venue Dashboard, Platform Admin | Conversion targets: venue emergency → normal closure or opening-hours change; resource emergency → maintenance, disabled or archived. A **resource emergency may not convert directly into an opening-hours change**. Each conversion obeys the target action's own authorization and impact rules and never bypasses the resource state-change predicate. | §42.5 |
| `FR-OPS-24` | M | Inventory/Reservation | Venue Staff<br>Venue Dashboard, Platform Admin | On CONVERTED, the emergency block ends **at the same atomic commit** that activates the replacement. No availability gap is allowed. | §42.5 |
| `FR-OPS-25` | M | UI/Presentation | Venue Staff, Venue Owner authorization<br>Venue Dashboard, Platform Admin | The Dashboard and owner must be notified when an expected end is reached with the emergency still active, when a return-to-service time is reached with the outage still active, and when manual confirmation is required — so operational blocks are not forgotten. | §42.7 |
| `FR-OPS-42` | M | State/Lifecycle, Inventory/Reservation, Authorization, Configuration | Venue Staff, Venue Owner authorization<br>Venue Dashboard, Platform Admin | An active emergency closure or resource emergency outage blocks **availability** but does **not** by itself forbid Disable, Archive or Console Change, provided the Owner Password requirement passes, all remaining future reservations are resolved, and applicable maintenance restrictions pass. Where a resource emergency has created maintenance, that **maintenance** becomes the continuing resource-level block and normal state-change rules evaluate the maintenance interval. | §13.6, §42.8 |
| `FR-OPS-43` | M | State/Lifecycle, Validation/Eligibility, Inventory/Reservation, UI/Presentation, Configuration | Venue Staff<br>Venue Dashboard, Platform Admin | Where a resource cannot return to service but still holds **future protected reservations**, the operational selling block **remains** and the venue must resolve those commitments through the normal flows before Disable, Archive or extended Maintenance becomes available. The Dashboard must not present an unavailable conversion as executable: it displays the blocking commitments and states that they must be resolved first. | §42.6, §31.12 |
| `FR-OPS-26` | M | Timer/Scheduler, Inventory/Reservation, Authorization, UI/Presentation, Configuration | Venue Staff, Venue Owner authorization<br>Venue Dashboard, Platform Admin | When a resource has exceeded 24 maintenance hours in a rolling 48-hour window, it enters `OWNER_REVIEW_REQUIRED` at the emergency's expected or actual end. A genuine new emergency may always still be declared immediately without the Owner Password. | §43.1 |
| `FR-OPS-27` | M | State/Lifecycle, Inventory/Reservation, UI/Presentation | Venue Staff, Venue Owner authorization<br>Venue Dashboard, Platform Admin | The threshold is evaluated **before** ending an emergency. Ending an emergency can never return an over-threshold resource to sellable state: **owner review takes precedence over availability**. | §43.2 |
| `FR-OPS-28` | M | State/Lifecycle, Inventory/Reservation, UI/Presentation | Venue Staff, Venue Owner authorization, System<br>Venue Dashboard, Platform Admin | Entry triggers a **system operational cascade** — not a manual venue cancellation — resolving atomically: PENDING, AWAITING, future CONFIRMED, ACTIVE and future manual bookings → CANCELLED `RESOURCE_OWNER_REVIEW_REQUIRED`; pre-start tournaments → CANCELLED with the same reason; an ACTIVE tournament loses that resource and is cancelled only if none remains. | §43.3 |
| `FR-OPS-29` | M | Validation/Eligibility, Authorization, Audit/History | Customer, Venue Staff, Venue Owner authorization, Platform Admin, System<br>Venue Dashboard, Platform Admin | The cascade requires **no Owner Password anywhere**, including the manual-booking and tournament cancellations, requires system audit, and notifies the venue owner, Platform Admin, affected customers and participants. **A partial cascade is not a valid outcome.** | §43.3, §43.4 |
| `FR-OPS-30` | M | State/Lifecycle, Timer/Scheduler, UI/Presentation | Customer, Venue Staff, Venue Owner authorization<br>Venue Dashboard, Platform Admin | Owner review is **not a pause state**: affected bookings are cancelled, not held, so customers are never left in indefinite commitments. A PENDING booking cancelled this way stops its timer immediately and never becomes VENUE_RESPONSE_EXPIRED or uses `VENUE_DID_NOT_RESPOND_IN_TIME`. | §43.5 |
| `FR-OPS-31` | M | Trust/Enforcement, Money/Points | Customer, Venue Staff, System<br>Venue Dashboard, Platform Admin | An affected customer receives no penalty, an explicit operational reason, the ability to rebook normally, and a full future fee refund. No automatic replacement booking is created. Attendance is NOT_APPLICABLE with no trust effect. | §43.5 |
| `FR-OPS-32` | M | State/Lifecycle, Validation/Eligibility, Timer/Scheduler, Inventory/Reservation, UI/Presentation | Venue Staff, Venue Owner authorization, System<br>Venue Dashboard, Platform Admin | While the restriction holds, the venue cannot initiate Late Confirm on that resource; the system records `LATE_CONFIRM_ELIGIBILITY_LOST_RESOURCE_STATE_CHANGED` with `detailCode = RESOURCE_OWNER_REVIEW_REQUIRED`. Clearing the restriction before `startAt` restores eligibility, but never triggers an automatic Late Confirm. | §43.6 |
| `FR-OPS-33` | M | Validation/Eligibility, Inventory/Reservation, Authorization, Configuration | Venue Staff, Venue Owner authorization<br>Venue Dashboard, Platform Admin | Resolution requires the Owner Password and offers **Return to Service**, **Extend Maintenance**, **Disable Resource** or **Archive Resource**. Return to Service requires no blocking maintenance, no emergency selling block and an otherwise operational resource; it then sets `operationalRestriction = NONE`. | §43.8 |
| `FR-OPS-34` | M | Timer/Scheduler, Inventory/Reservation, Configuration | Venue Staff<br>Venue Dashboard, Platform Admin | The **venue response timer** on a PENDING booking pauses while a venue emergency closure, **or** a resource emergency outage affecting that booking's resource, is in force. A resource emergency on any other resource pauses nothing. While a relevant block is active, Venue Accept is disabled. | §44.1 |
| `FR-OPS-35` | M | Inventory/Reservation | Customer, Venue Staff<br>Venue Dashboard, Platform Admin | The **customer Late Confirmation countdown** pauses while an active emergency or selling block affects the venue **or** the booked resource, and the booking has not already been cancelled by the emergency cascade. | §44.1 |
| `FR-OPS-36` | M | Timer/Scheduler | Venue Staff<br>Venue Dashboard, Platform Admin | Remaining duration is **preserved** across a pause: a 10-minute timer with 3 minutes elapsed resumes with 7 minutes remaining. | §44.1 |
| `FR-OPS-37` | M | Timer/Scheduler, Inventory/Reservation, Trust/Enforcement | Venue Staff<br>Venue Dashboard, Platform Admin | Two **separate** cumulative caps of **30 minutes** each: venue-response emergency pause per booking, and Late Confirmation emergency pause per AWAITING phase. Venue and resource emergency pauses contribute to the **same** response-pause counter, so switching emergency type can never reset the clock. Repeated pauses accumulate and never reset. Stored per booking: `responsePauseStartedAt`, `cumulativeEmergencyPauseDuration`, `lateConfirmPauseStartedAt`, `cumulativeLateConfirmEmergencyPauseDuration`. | §44.2 |
| `FR-OPS-38` | M | State/Lifecycle, Inventory/Reservation, Trust/Enforcement | Customer, Venue Staff<br>Venue Dashboard, Platform Admin | On reaching a cap: venue response under a venue-level emergency → PENDING CANCELLED `VENUE_RESPONSE_INTERRUPTED_BY_EMERGENCY` · venue response under a resource-level emergency → PENDING CANCELLED `RESOURCE_RESPONSE_INTERRUPTED_BY_EMERGENCY` · Late Confirmation → AWAITING CANCELLED `LATE_CONFIRM_INTERRUPTED_BY_EMERGENCY`. In every case the reservation or temporary hold releases, the customer commitment slot releases, the customer is notified, no customer penalty applies and **no venue non-response blame is recorded**. | §44.3 |
| `FR-OPS-39` | M | Validation/Eligibility, Timer/Scheduler, Inventory/Reservation, Authorization | Customer, Venue Staff<br>Venue Dashboard, Platform Admin | A paused venue response timer resumes only when **all** hold: the emergency has ended · venue account ENABLED · venue marketplace permits the action · the venue is inside a valid operating session · the current time is inside the online intake window. Ending an emergency while the venue is closed does not restart the timer; it waits for the **next** valid intake window. A paused Late Confirmation countdown resumes only when the affecting emergency or block is resolved, the booking is still before `startAt`, and venue, resource and customer all remain eligible. | §44.4 |
| `FR-OPS-40` | M | State/Lifecycle, Validation/Eligibility, Timer/Scheduler | Venue Staff, System<br>Venue Dashboard, Platform Admin | Early cancellation is deterministic through the **optimistic feasibility test**: assuming the current selling block could resolve immediately, could the remaining venue-response duration fit inside a valid future online intake window before `startAt`? The system evaluates the remaining response duration, future valid operating sessions, the final-10-minute intake cutoff, `startAt` and the active selling block. **No such window even under immediate resolution** → cancel now under the applicable interrupted-by-emergency reason. **A window could exist but the emergency is unresolved** → the booking stays paused only until the 30-minute cap, which is the deterministic outer bound. Block duration is never predicted by the scheduler. | §44.5 |
| `FR-OPS-41` | M | Inventory/Reservation, Audit/History | Customer, Venue Staff<br>Venue Dashboard, Platform Admin | The customer is notified when a Late Confirmation countdown is paused, and sees *"Confirmation temporarily paused due to venue/resource issue."* On resume the remaining time is shown. **While paused the customer may still Decline**, which takes effect immediately under `CUSTOMER_DECLINED_LATE_CONFIRM`. Both pause records are immutable operational history storing `pauseStartedAt`, `pauseEndedAt`, `pauseCause` and `accumulatedDuration`. | §44.6, §44.7, §29.3 |

**Domain acceptance obligations from PRD v1.1**

- Splitting a 30-hour maintenance into two windows with a one-minute gap still demands the Owner Password
- A venue emergency cascade interrupted by an injected fault leaves **no** partial state — either all cancellations and the closure exist, or none do
- A resource emergency does not block bookings on the venue's other resources
- END EMERGENCY on an over-threshold resource leaves it unsellable under owner review
- The owner-review cascade cancels an ACTIVE booking automatically with no admin action
- No test scenario produces a window where the block is off and no replacement restriction exists
- A booking paused by a venue emergency for 12 minutes, resumed, then paused by a resource emergency for 19 minutes, cancels at the 30-minute cumulative mark under the resource reason — the counter does not reset when the emergency type changes
- A PENDING booking whose remaining response time cannot fit any future intake window before `startAt` is cancelled immediately, not held to the cap
- A customer declines a Late Confirmation while its countdown is paused and the booking becomes CANCELLED `CUSTOMER_DECLINED_LATE_CONFIRM` at once
- Every one of the three interrupted-by-emergency reason codes is reachable in a seeded end-to-end run

## 4.10 Tournaments — 33 requirements

| ID | Pri | Type | Actor / Surface | Functional requirement | Governing v5.0 |
|---|---:|---|---|---|---|
| `FR-TRN-01` | M | Timer/Scheduler, Audit/History | System<br>Customer Mobile App, Venue Dashboard | Tournaments are Day-1 across PlayStation, Billiards and Ping Pong, and are a **separate entity from a booking**. Booking history and tournament history are separate; a tournament is never modelled as a booking. | §36.1 |
| `FR-TRN-02` | M | Functional rule | Venue Staff<br>Customer Mobile App, Venue Dashboard | Player-hosted tournaments require an operational **VERIFIED** creator and **venue approval**. Venue-hosted tournaments require neither. | §36.2 |
| `FR-TRN-03` | M | Timer/Scheduler, Inventory/Reservation, Money/Points | System<br>Customer Mobile App, Venue Dashboard | Parameters: 8–32 participants · 1–4 resources · minimum 1 hour in 30-minute increments · no global maximum below the operating-session limit · creation at `requestTime <= startAt − 2 hours` · checkpoint at exactly `startAt − 1 hour` · creation fee EGP 0. Capacity and resource count are independent; E7GZ manages no brackets, per-match scheduling or per-match resource assignment. | §36.3 |
| `FR-TRN-04` | M | Validation/Eligibility, Timer/Scheduler, Inventory/Reservation, Configuration | System<br>Customer Mobile App, Venue Dashboard | A PlayStation tournament must select Single or Multiplayer, which drives resource pricing. The interval must fit **one** valid operating session, so a tournament may exceed the 6-hour online booking maximum. | §36.3 |
| `FR-TRN-05` | M | Functional rule | System<br>Customer Mobile App, Venue Dashboard | The player creator chooses **Organizer Only** or **Organizer + Participant**; if participating, they count in the total. | §36.4 |
| `FR-TRN-06` | M | UI/Presentation | System<br>Customer Mobile App, Venue Dashboard | Public tournaments appear in Discover and any account-verified user may join. Private tournaments are hidden and require an invite code or link; the Dashboard supports copy code and copy/share link. | §36.5 |
| `FR-TRN-07` | M | UI/Presentation | Venue Staff<br>Customer Mobile App, Venue Dashboard | While PENDING_VENUE_APPROVAL a tournament is not in public Discover, its invite code and link are inactive, and no one may join. Only the creator and the venue may view it. Discovery rules activate on venue accept. | §36.5 |
| `FR-TRN-08` | M | Validation/Eligibility, Inventory/Reservation, Configuration | System<br>Customer Mobile App, Venue Dashboard | **Existing reservations always win.** Tournament creation cannot override any existing booking, tournament, maintenance or closure, and **a tournament may never automatically cancel an existing booking**; creation fails for conflicting resources. | §36.7 |
| `FR-TRN-33` | M | State/Lifecycle, Validation/Eligibility, Inventory/Reservation | Venue Staff<br>Customer Mobile App, Venue Dashboard | At creation, **every** assigned resource requires base state **ACTIVE**, operational restriction **NONE**, no operational selling block at venue or resource level, no conflicting reservation or hold, and activity compatibility. | §36.6 |
| `FR-TRN-09` | M | Timer/Scheduler, Inventory/Reservation | Venue Staff<br>Customer Mobile App, Venue Dashboard | Created tournaments hold their resources. During PENDING_VENUE_APPROVAL these are temporary **Approval Holds** that block other reservations and release on reject, expiry or cancellation; on accept they become standard Tournament Holds. | §36.8 |
| `FR-TRN-10` | M | State/Lifecycle, Timer/Scheduler, Inventory/Reservation | Venue Staff<br>Customer Mobile App, Venue Dashboard | Player-hosted approval window: `MIN(requestCreatedAt + 10 minutes, checkpointAt)`. Accept → WAITING_FOR_PLAYERS · Reject → REJECTED `VENUE_REJECTED_TOURNAMENT` · No response → CANCELLED `VENUE_DID_NOT_APPROVE_TOURNAMENT_IN_TIME`. Holds release on both failure paths. | §37.2 |
| `FR-TRN-11` | M | Timer/Scheduler, Inventory/Reservation | Venue Staff<br>Customer Mobile App, Venue Dashboard | A creator may hold only **one** player-hosted tournament in PENDING_VENUE_APPROVAL platform-wide, and must wait **5 minutes** after a rejection or approval expiry before re-requesting the same venue and time interval. | §37.3 |
| `FR-TRN-12` | M | Validation/Eligibility, Timer/Scheduler, Authorization, Trust/Enforcement | System<br>Customer Mobile App, Venue Dashboard | Users may join while WAITING_FOR_PLAYERS or CONFIRMED and `now < startAt`, with capacity remaining, not banned, and with valid invite access if private. Capacity remaining counts JOINED participants plus still-valid pre-start `INELIGIBLE/BAN` reserved slots. Participants may leave, and organizers may remove, until `startAt`. Falling below 8 before the checkpoint does **not** cancel early. | §37.4 |
| `FR-TRN-13` | M | State/Lifecycle, Validation/Eligibility, Authorization, Trust/Enforcement, Audit/History | System<br>Customer Mobile App, Venue Dashboard | An **Active Participant** is JOINED, not BANNED, not DELETED, and has neither left nor been removed. Participant states remain JOINED / LEFT / REMOVED / INELIGIBLE. `INELIGIBLE` carries cause `BAN` or `ACCOUNT_DELETED`; deletion has precedence. Before Start, BAN-only ineligibility reserves one capacity slot while restoration is possible but never counts as Active. Historical participation and cause history are always retained. | §37.4 |
| `FR-TRN-14` | M | State/Lifecycle, Timer/Scheduler, Inventory/Reservation | System<br>Customer Mobile App, Venue Dashboard | At `startAt − 1 hour`: ≥ 8 active participants → CONFIRMED · < 8 → CANCELLED `MINIMUM_PARTICIPANTS_NOT_REACHED` with resources released. After CONFIRMED a later drop below 8 does **not** auto-cancel. There is **no "Run Anyway" override**. | §37.5 |
| `FR-TRN-15` | M | State/Lifecycle, Validation/Eligibility, Inventory/Reservation | Venue Staff<br>Customer Mobile App, Venue Dashboard | The checkpoint revalidates every assigned resource — base state ACTIVE, restriction NONE, no selling block, hold still valid, venue eligible, tournament not otherwise cancelled. | §37.5 |
| `FR-TRN-16` | M | Validation/Eligibility, Configuration | Venue Staff<br>Customer Mobile App, Venue Dashboard | At the checkpoint "venue eligible" means **operationally able to honour the tournament**, not eligible for new marketplace activity. Marketplace SUSPENDED or INACTIVE therefore never fails a checkpoint by itself; only a venue selling block, a venue emergency affecting the tournament, or an applicable closure does. A tournament **never** remains indefinitely in WAITING_FOR_PLAYERS after a failed mandatory checkpoint. | §37.5 |
| `FR-TRN-17` | M | State/Lifecycle, Validation/Eligibility, Inventory/Reservation, UI/Presentation | Venue Owner authorization, System<br>Customer Mobile App, Venue Dashboard | CONFIRMED → ACTIVE at start, ACTIVE → COMPLETED at end. Before CONFIRMED → ACTIVE every resource is revalidated; any resource under owner review cancels the tournament **before gameplay begins**. | §37.6 |
| `FR-TRN-18` | M | Timer/Scheduler, Inventory/Reservation, Notification | Venue Staff<br>Customer Mobile App, Venue Dashboard | A venue account disable during PENDING_VENUE_APPROVAL resolves the tournament **immediately** to CANCELLED `VENUE_ACCOUNT_DISABLED`: the timer stops with no pause or resumption, holds release, the creator's platform-wide pending slot releases, the venue is **not** recorded as failing to approve, the **5-minute cooldown does not apply**, and the creator notification must not use venue non-response blame. A later re-enable never restores it. | §37.7 |
| `FR-TRN-19` | M | Inventory/Reservation | Venue Staff<br>Customer Mobile App, Venue Dashboard | A venue account disable alone does **not** cancel WAITING_FOR_PLAYERS, CONFIRMED or ACTIVE tournaments while the venue and resources can still deliver. | §37.7 |
| `FR-TRN-20` | M | State/Lifecycle, Trust/Enforcement | Venue Staff<br>Customer Mobile App, Venue Dashboard | Verified Player cascade: **REVOKED** or qualification lost through trust correction cancels PENDING_VENUE_APPROVAL and WAITING_FOR_PLAYERS under `CREATOR_VERIFIED_STATUS_REVOKED`, while CONFIRMED and ACTIVE remain. **SUSPENDED** cancels nothing existing but blocks new creation. Restoration never resurrects a cancelled tournament. The cascade executes **once per `verifiedPlayerTransitionId`**. | §37.8 |
| `FR-TRN-21` | M | Authorization | Venue Staff, Platform Admin<br>Customer Mobile App, Venue Dashboard, Platform Admin | Cancellation authority: venue may cancel before ACTIVE (player-hosted, after its own approval) · creator may cancel PENDING_VENUE_APPROVAL and WAITING_FOR_PLAYERS before the checkpoint · Platform Admin may cancel any non-completed tournament. **After CONFIRMED the creator may no longer cancel through E7GZ** and must contact the venue or support. | §37.9 |
| `FR-TRN-22` | M | Inventory/Reservation, Authorization, Configuration | Venue Staff, Platform Admin<br>Customer Mobile App, Venue Dashboard, Platform Admin | An ACTIVE tournament may end early only through venue emergency closure, an applicable resource emergency outage, **Tournament Operational Termination**, or Platform Admin correction. There is no unrestricted "venue operational termination" power. | §37.9 |
| `FR-TRN-23` | M | State/Lifecycle, Validation/Eligibility, Authorization, Notification, Audit/History | Venue Staff, Venue Owner authorization<br>Customer Mobile App, Venue Dashboard | Tournament Operational Termination covers a tournament-specific incident not requiring venue closure, requires the **Owner Password**, a mandatory reason, audit and participant and organizer notification, and yields ACTIVE → CANCELLED `TOURNAMENT_OPERATIONAL_TERMINATION`. | §37.9 |
| `FR-TRN-24` | M | Authorization, UI/Presentation | Venue Staff, Venue Owner authorization, System<br>Customer Mobile App, Venue Dashboard | Owner Password matrix: cancelling WAITING_FOR_PLAYERS **no** · cancelling CONFIRMED **yes** · operational termination **yes** · any emergency or owner-review consequence **no**, because those are system consequences rather than the venue's cancel action. | §37.10 |
| `FR-TRN-25` | M | Inventory/Reservation, Audit/History | System<br>Customer Mobile App, Venue Dashboard | Every cancellation releases holds, updates history, notifies creator and participants, and stores the cancelling actor and reason. No resource booking is silently created or removed outside that transaction. | §37.9 |
| `FR-TRN-26` | M | State/Lifecycle, Validation/Eligibility, Timer/Scheduler, Authorization, Trust/Enforcement | System<br>Customer Mobile App, Venue Dashboard | Participant ban/deletion handling is cause-specific. Ban → `INELIGIBLE/BAN`: zero Active count but one reserved capacity slot remains while the account is active, Tournament non-terminal and `now < startAt`. Account deletion → `INELIGIBLE/ACCOUNT_DELETED`: zero Active count, no reserved slot and no auto-restoration. Deletion overrides any BAN-only cause in the same logical operation and remains authoritative if Ban/deletion overlap. A BAN-only Participant automatically returns to JOINED only when the Ban ends/lifts before `startAt` while the account is active; boundary-effective restoration must be applied before an exact checkpoint/start decision when the Ban was already ineffective at that boundary. At/after `startAt` there is no restoration, and no later change resurrects a checkpoint-cancelled Tournament. | §37.11 |
| `FR-TRN-27` | M | State/Lifecycle, Inventory/Reservation, UI/Presentation | Venue Staff, Venue Owner authorization<br>Customer Mobile App, Venue Dashboard | Resource loss during an ACTIVE tournament: at least one operational resource remaining → the tournament continues, the failed resources are removed, and creator and participants receive **Tournament Resource Availability Changed** · none remaining → CANCELLED under the applicable resource reason. Before the start the rule is stricter: **any** assigned resource lost to a resource emergency or owner review cancels the entire tournament, including one still in PENDING_VENUE_APPROVAL. | §37.12 |
| `FR-TRN-28` | M | Timer/Scheduler, Inventory/Reservation, Money/Points, Audit/History | Venue Staff<br>Customer Mobile App, Venue Dashboard | Tournament venue cost = `historical resource hourly price × booked nominal duration`, summed over resources, with the same half-up 2-decimal rounding. Snapshot is taken at **request creation** for player-hosted and at **creation** for venue-hosted. Later price changes never alter a stored cost, and **a resource loss never changes it**. | §36.9, §37.12 |
| `FR-TRN-29` | M | Validation/Eligibility, Inventory/Reservation, Trust/Enforcement, Money/Points | Venue Staff<br>Customer Mobile App, Venue Dashboard | Tournament venue cost, entry fee and prize are **informational only**. E7GZ collects nothing, holds nothing and distributes nothing, and never imposes a penalty, debt, wallet deduction or financial obligation on a creator who cannot continue. | §36.9, §36.11 |
| `FR-TRN-30` | M | Money/Points, UI/Presentation | Venue Staff<br>Customer Mobile App, Venue Dashboard | A COMPLETED tournament contributes its stored cost to Dashboard tournament revenue on its `businessDate`. Cancelled and rejected tournaments contribute nothing. | §36.10 |
| `FR-TRN-31` | M | State/Lifecycle, Inventory/Reservation, UI/Presentation, Configuration | Venue Staff, Venue Owner authorization<br>Customer Mobile App, Venue Dashboard | Exactly one terminal tournament reason is stored, by priority: **1.** `RESOURCE_OWNER_REVIEW_REQUIRED` · **2.** `RESOURCE_EMERGENCY_OUTAGE` · **3.** `EMERGENCY_CLOSURE` · **4.** `VENUE_ACCOUNT_DISABLED` · **5.** `MINIMUM_PARTICIPANTS_NOT_REACHED` · **6.** `CREATOR_VERIFIED_STATUS_REVOKED` · **7.** explicit actor cancellation. | §37.14 |
| `FR-TRN-32` | M | State/Lifecycle, Validation/Eligibility, Inventory/Reservation, Authorization, UI/Presentation | Venue Staff, Venue Owner authorization, Platform Admin<br>Customer Mobile App, Venue Dashboard, Platform Admin | The closed **Tournament Reason Code** enum is `CREATOR_CANCELLED` · `VENUE_CANCELLED` · `ADMIN_CANCELLED` · `VENUE_REJECTED_TOURNAMENT` · `VENUE_DID_NOT_APPROVE_TOURNAMENT_IN_TIME` · `MINIMUM_PARTICIPANTS_NOT_REACHED` · `CREATOR_VERIFIED_STATUS_REVOKED` · `TOURNAMENT_OPERATIONAL_TERMINATION` · `EMERGENCY_CLOSURE` · `RESOURCE_EMERGENCY_OUTAGE` · `RESOURCE_OWNER_REVIEW_REQUIRED` · `VENUE_ACCOUNT_DISABLED`. Every terminal tournament carries exactly one, and a new member requires Product Change Control. | §37.13, §55.7, §57.3 |

**Domain acceptance obligations from PRD v1.1**

- Creation at `startAt − 1h59m` is refused; at exactly `startAt − 2h` it succeeds
- A player request against a resource already holding a confirmed booking fails, and the booking is untouched
- A creator with one pending request cannot open a second anywhere on the platform
- A tournament at 7 participants at the checkpoint cancels and releases resources within the same transaction
- A tournament at a venue suspended after creation still passes its checkpoint
- Disabling the venue account during PENDING_VENUE_APPROVAL frees the creator's slot immediately with **no** cooldown
- A pre-checkpoint Ban moves the participant to `INELIGIBLE/BAN`: they leave the Active count but keep a pre-start capacity slot reserved.
- A Ban that was already ineffective at the exact checkpoint is restored before the checkpoint count, even if the scheduler runs late.
- Account deletion overrides `INELIGIBLE/BAN` to `INELIGIBLE/ACCOUNT_DELETED`, frees any reserved slot immediately and can never be auto-restored by later Ban expiry/lift.
- At or after `startAt`, a BAN-only participant is not automatically restored and no capacity reservation survives.
- An active tournament losing 3 of 4 resources continues and notifies; losing the 4th cancels it
- A tournament losing a resource keeps its original stored cost

## 4.11 Trust, Penalties and Bans — 27 requirements

| ID | Pri | Type | Actor / Surface | Functional requirement | Governing v5.0 |
|---|---:|---|---|---|---|
| `FR-TRS-01` | M | State/Lifecycle, Trust/Enforcement, Audit/History | System<br>Platform Admin | Trust is calculated from **immutable trust input events**. The no-show counter, penalty stage, ban state, Verified Player progress and Verified Player status are **derived** and never authoritative. No counter may be inferred from a stored derived value. | §45.1 |
| `FR-TRS-02` | M | Authorization, Trust/Enforcement | Platform Admin, System<br>Platform Admin | The canonical catalogue is: **Booking outcomes** `BOOKING_ATTENDED`, `BOOKING_ATTENDANCE_UNCONFIRMED`, `BOOKING_ATTENDED_INTERRUPTED`, `BOOKING_NO_SHOW` · **Corrections** `NO_SHOW_REVERSED`, `ATTENDANCE_CORRECTED` · **Administration** `ADMIN_TRUST_ADJUSTMENT`, `VERIFIED_PLAYER_SUSPENDED`, `VERIFIED_PLAYER_REVOKED`, `VERIFIED_PLAYER_RESTORED` · **System protection** `BOOKING_ACCOUNT_DISABLE_ATTENDANCE_PROTECTION`. | §45.2 |
| `FR-TRS-03` | M | Trust/Enforcement, Money/Points, Audit/History | System<br>Platform Admin | Operational evidence — `CHECK_IN_RECORDED`, a pre-completion `CHECK_IN_REVERSED`, `LATE_PRESENT_RECORDED`, `LATE_PRESENT_REVERSED` — stays in booking and audit history and enters trust **only** through a resulting canonical outcome or correction. Pressing check-in is not itself a reward. | §45.2 |
| `FR-TRS-04` | M | Audit/History | System<br>Platform Admin | Every event carries `eventId`, `eventType`, `occurredAt`, `effectiveAt`, `eventSequence`, `subjectUserId`, optional `bookingId`, optional `targetEventId` and `eventPayload`. | §45.3 |
| `FR-TRS-05` | M | Timer/Scheduler, Authorization, Trust/Enforcement, Audit/History | Platform Admin, System<br>Platform Admin | Effective-time rules are closed: any booking-derived event and the protection event use `booking.startAt` · any correction of a booking event uses the **same** `effectiveAt` as the corrected event · admin trust adjustment and the three Verified Player admin events use `occurredAt`. | §45.4 |
| `FR-TRS-06` | M | Audit/History | System<br>Platform Admin | Replay orders **exclusively** by `effectiveAt` ASC then `eventSequence` ASC. There is no competing ordering. | §45.5 |
| `FR-TRS-07` | M | Trust/Enforcement | System<br>Platform Admin | Corrections never delete. Replay **neutralises** the targeted effect and, where appropriate, **substitutes** the corrected effect. `ATTENDANCE_CORRECTED` carries a mandatory `correctedOutcome` of ATTENDED, ATTENDED_INTERRUPTED, ATTENDANCE_UNCONFIRMED or ABSENT_NO_SHOW. | §45.6 |
| `FR-TRS-08` | M | Authorization, Money/Points, Audit/History, Configuration | Platform Admin, System<br>Platform Admin | A correction to ATTENDED uses the points value applicable to the booking's **original** completion date and configuration, posts the transaction at correction time, and records the historical value, correction time, reason and admin identity. | §45.6 |
| `FR-TRS-09` | M | Trust/Enforcement, Money/Points, Audit/History | System<br>Platform Admin | The **latest non-superseded** attendance correction by `eventSequence` is authoritative. Each correction references the booking, the superseded correction if any, the original outcome event and the corrected outcome. Only **one** effective outcome per booking contributes to replay, so sequential corrections can never double a reward, a reset or a penalty. | §45.7 |
| `FR-TRS-10` | M | State/Lifecycle, Audit/History | System<br>Platform Admin | Replay can compute state **as of any time**, reproducing both historical and current state from the same inputs. | §45.8 |
| `FR-TRS-11` | M | Inventory/Reservation, Trust/Enforcement, Money/Points, Audit/History | System<br>Platform Admin | Counter effects: ATTENDED resets and grants points and +1 progress · ATTENDED_INTERRUPTED resets, no reward · protection resets, no reward · ATTENDANCE_UNCONFIRMED does nothing · NO_SHOW increments. The **three canonical counter-reset trust events** are exactly ATTENDED, ATTENDED_INTERRUPTED and the protection event. Separately, and not as a trust event, reaching a penalty threshold also resets the counter at ban creation or extension. | §45.9 |
| `FR-TRS-12` | M | Trust/Enforcement | Venue Staff, System<br>Platform Admin | Cancelled, rejected, venue-response-expired, attendance-unconfirmed, manual bookings and tournament participation neither increment nor reset the counter. Consecutive tracking is **global across E7GZ PS**, ordered by booking start time. | §45.9, §45.10 |
| `FR-TRS-13` | M | State/Lifecycle, Timer/Scheduler, Inventory/Reservation | System<br>Platform Admin | The ladder: stage 0 — 3 consecutive → 7 days · stage 1 — 2 consecutive → 1 month · stage 2 — 2 consecutive → 2 months · recurring maximum — every 3 consecutive → 2 months. The threshold intentionally returns to 3 at the recurring stage, and 2 months is the maximum. | §48.2 |
| `FR-TRS-14` | M | Validation/Eligibility, Inventory/Reservation, Trust/Enforcement | System<br>Platform Admin | A penalty triggers when a new eligible no-show **increments the counter to the current threshold** — never merely because a stored number exceeds a later threshold. | §48.2 |
| `FR-TRS-15` | M | Inventory/Reservation, Trust/Enforcement, UI/Presentation | System<br>Platform Admin | Account Standing stores and displays penalty stage, current consecutive count, current threshold, ban start, ban until and remaining time, showing exactly **one** current ban. | §48.3 |
| `FR-TRS-16` | M | Timer/Scheduler, Trust/Enforcement, Audit/History, UI/Presentation | System<br>Platform Admin | On ban creation the counter resets to 0 and the stage advances; on expiry the counter stays 0 unless new qualifying no-shows occurred after return. A completed booking resets only the current counter and never erases the historical stage. | §48.4 |
| `FR-TRS-17` | M | State/Lifecycle, Trust/Enforcement, Audit/History | System<br>Platform Admin | **Ban Qualification** (historical, derived, with `qualificationAt`) and **Ban Instance** (operational, with `banInstanceId`, `activatedAt`, `endsAt`, `penaltyStage`, `duration`, `status`, `activationReason`) are distinct and must not be conflated. | §48.5 |
| `FR-TRS-18` | M | Trust/Enforcement, Audit/History | System<br>Platform Admin | A retroactively discovered ban activates at **correction time**, never back-dated, and produces **one** ban instance carrying a historical `qualificationAt` and a current `activatedAt`. Ban end is always `activatedAt + duration`. | §48.5 |
| `FR-TRS-19` | M | Authorization, Trust/Enforcement | System<br>Platform Admin | "Was this user banned then?" is answered from the ban instance's `activatedAt`/`endsAt`, not from the qualification point. Trust Qualification As-Of and Operational Ban As-Of are separate queries and may legitimately differ after a correction. | §48.5 |
| `FR-TRS-20` | M | State/Lifecycle, Inventory/Reservation, Authorization, Trust/Enforcement | Customer, System<br>Platform Admin | Ban cascade: PENDING → CANCELLED `USER_BANNED` · AWAITING → CANCELLED `USER_BANNED` with hold release · future CONFIRMED → CANCELLED `USER_BANNED` · **ACTIVE continues**. Future holds release and the customer is notified. | §48.6 |
| `FR-TRS-21` | M | Validation/Eligibility, Trust/Enforcement | System<br>Platform Admin | Each ban instance's cancellation cascade executes **once only**; repeated replay must never repeat it. | §48.7 |
| `FR-TRS-22` | M | Authorization, Trust/Enforcement, Audit/History | System<br>Platform Admin | At most **one ACTIVE ban instance** per user. A further qualification while banned advances the stage as a full trigger, resets the counter to 0, and **extends** the existing instance: `newEndsAt = MAX(currentEndsAt, now + durationForNewStage)`. Extension never repeats an already-executed cascade. `BAN_EXTENDED` is audit-required with instance ID, triggering qualification and event, previous and new stage, old and new end, and timestamp. | §48.8 |
| `FR-TRS-23` | M | State/Lifecycle, Trust/Enforcement, Audit/History | System<br>Platform Admin | Ban instance status is ACTIVE / EXPIRED / LIFTED / CORRECTED, and the historical instance always remains in audit. | §48.9 |
| `FR-TRS-24` | M | State/Lifecycle, Validation/Eligibility, Inventory/Reservation, Trust/Enforcement | System<br>Platform Admin | Replay corrects **trust state** but **never rewinds external booking side-effects into the past**, so it cannot destroy valid later reservations or restore slots already sold. | §47.3 |
| `FR-TRS-25` | M | State/Lifecycle, Validation/Eligibility, Trust/Enforcement | System<br>Platform Admin | A ban proved invalid is CORRECTED/ENDED with restrictions stopped, the user notified and state recalculated — but **previously cancelled bookings do not return**. | §47.3 |
| `FR-TRS-26` | M | Validation/Eligibility, Authorization | Customer, Platform Admin, System<br>Platform Admin | There is **no Support Replacement Booking** and no special admin booking-creation path. All customer booking creation uses the same online predicate. Support may assist but cannot bypass validation. | §47.4 |
| `FR-TRS-27` | M | Authorization, Trust/Enforcement, Money/Points, Audit/History | Platform Admin, System<br>Platform Admin | Admin remediation covers trust, points, referrals and bookings as listed in v5.0 §47.1, each requiring a reason, admin identity, timestamp and audit record. Wallet adjustment is **not** a launch action. | §47.1 |

**Domain acceptance obligations from PRD v1.1**

- A seeded history replayed from events reproduces the stored counter, stage, ban and Verified Player status exactly
- Reversing a Monday no-show on Friday changes the Monday position in the sequence, not the Friday position
- Three sequential attendance corrections on one booking yield exactly one effective outcome and one counter effect
- A correction that newly qualifies a user for a ban starts it today and cancels only bookings existing now
- A second qualification during an active ban produces one instance, an advanced stage, a reset counter and a `BAN_EXTENDED` record
- Replaying trust twice never cancels the same booking twice

## 4.12 Points, Referrals, Wallet and Fees — 21 requirements

| ID | Pri | Type | Actor / Surface | Functional requirement | Governing v5.0 |
|---|---:|---|---|---|---|
| `FR-PTS-01` | M | Trust/Enforcement, Money/Points, Audit/History, UI/Presentation, Configuration | Customer<br>Customer Mobile App, Platform Admin | Points at launch: completed booking with qualifying attendance **+200** and **+1** Verified Player progress · referral signup **+1,000** and **0** progress · no-show, cancelled, rejected and **review** all **0** points and **0** progress. The configured value is read at the **successful attended completion event** and the granted amount is stored in the transaction; later configuration never rewrites history. | §5.6, §46.1 |
| `FR-PTS-02` | M | Validation/Eligibility, Trust/Enforcement, Money/Points | Customer, System<br>Customer Mobile App, Platform Admin | Points and trust are separate systems. Points are a reward currency; trust governs standing and eligibility. | §46.1 |
| `FR-PTS-03` | M | Money/Points | Customer<br>Customer Mobile App, Platform Admin | **Points balance may never be negative**; the minimum effective balance is 0. A compensating deduction applies `MIN(currentBalance, requestedDeduction)`. | §46.2 |
| `FR-PTS-04` | M | Money/Points, Audit/History | Customer, System<br>Customer Mobile App, Platform Admin | Any shortfall is recorded in audit as `UNRECOVERED_REVERSAL_AMOUNT`. It does not create a negative balance, does not become future debt, and future earned points are **not** automatically used to repay it. | §46.2 |
| `FR-PTS-05` | M | Money/Points, Audit/History | Customer<br>Customer Mobile App, Platform Admin | Points and wallet history is **append-only**. Corrections create compensating transactions; no ledger row is ever deleted. | §46.3 |
| `FR-PTS-06` | M | State/Lifecycle, Validation/Eligibility, Money/Points | Customer<br>Customer Mobile App, Platform Admin | A referral becomes eligible only on **successful account OTP verification**, not on record creation. Statuses: PENDING / REWARDED / INVALID. Only one reward per referred user. Self-referral is not allowed. | §46.4 |
| `FR-PTS-07` | M | Trust/Enforcement, Money/Points, Configuration | Customer<br>Customer Mobile App, Platform Admin | The reward configuration is read at OTP verification and the applied value is stored permanently. Referrals grant **no** Verified Player progress. | §46.4 |
| `FR-PTS-08` | M | Notification | Customer, System<br>Customer Mobile App, Platform Admin | Hard self-referral checks cover same phone and same email. Same device and same IP may be stored as **fraud signals** but are not automatic hard blocks in MVP. | §46.4 |
| `FR-PTS-09` | M | Validation/Eligibility, Money/Points, Audit/History | Customer<br>Customer Mobile App, Platform Admin | Invalidating a REWARDED referral sets it INVALID and creates a compensating transaction for the **exact historical reward**. A referral may become INVALID even when the full amount cannot be deducted; the ledger records historical reward, requested reversal, applied reversal and unrecovered amount. | §46.5 |
| `FR-PTS-10` | M | Money/Points | Customer, Venue Staff<br>Customer Mobile App, Platform Admin | Every user has a wallet with an initial balance of EGP 0, supporting E7GZ booking-fee refunds and a future points conversion. **The wallet never pays the venue gaming charge.** | §46.6 |
| `FR-PTS-11` | M | Authorization, Money/Points, UI/Presentation | Customer, Platform Admin<br>Customer Mobile App, Platform Admin | The wallet ledger exists in the backend; the customer UI shows **no active wallet screen** at launch. Platform Admin wallet adjustment is **not** a launch action. Points adjustment **is**, because points are active. | §46.6 |
| `FR-PTS-12` | W | Money/Points, Configuration | Customer<br>Customer Mobile App, Platform Admin | Points-to-wallet conversion is deferred; the ratio will be configurable. | §46.6 |
| `FR-PTS-13` | M | Timer/Scheduler, Authorization, Money/Points, Audit/History, UI/Presentation | Customer, Venue Staff, Platform Admin<br>Customer Mobile App, Platform Admin | The global booking fee is **EGP 0**, charged **per booking** — not per hour, per venue or per activity — is configurable by Platform Admin, and is **displayed to the customer as FREE**. Each booking stores the fee applied at creation; historical values are immutable and a later configuration change never alters them. Late Confirm reuses the **original stored** booking fee. | §5.1, §51.10 |
| `FR-PTS-14` | M | Timer/Scheduler, Inventory/Reservation, Authorization, Money/Points | Customer, Venue Staff<br>Customer Mobile App, Platform Admin | Refund classification is binding the moment a non-zero fee is enabled. **Customer-caused** cancellation follows the 15-minute rule — refundable only within 15 minutes of creation **and** before start. **Venue, platform or resource operationally caused** cancellation receives a **full refund**. | §34.1 |
| `FR-PTS-15` | M | State/Lifecycle, Timer/Scheduler, Inventory/Reservation, Authorization, Trust/Enforcement | Customer, Venue Staff, Platform Admin, System<br>Customer Mobile App, Platform Admin | Full refund applies to: `VENUE_REJECTED` · `VENUE_DID_NOT_RESPOND_IN_TIME` · `REJECTED_AFTER_EXPIRY` · `CUSTOMER_DECLINED_LATE_CONFIRM` · `CUSTOMER_RECONFIRM_TIMEOUT` · `VENUE_CANCELLED_CONFIRMED_BOOKING` · `VENUE_CLOSURE` · `VENUE_OPENING_HOURS_CHANGED` · `VENUE_EMERGENCY_CLOSURE` · `RESOURCE_EMERGENCY_OUTAGE` · `RESOURCE_OWNER_REVIEW_REQUIRED` · `VENUE_RESPONSE_INTERRUPTED_BY_EMERGENCY` · `RESOURCE_RESPONSE_INTERRUPTED_BY_EMERGENCY` · `LATE_CONFIRM_INTERRUPTED_BY_EMERGENCY` · `VENUE_ACCOUNT_DISABLED` · `SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR` · admin cancellation or correction where the customer is not at fault · `ADMIN_NO_SHOW_REVERSAL` where the wrongful no-show caused service loss. | §34.2 |
| `FR-PTS-16` | M | State/Lifecycle, Authorization, Trust/Enforcement, Money/Points | Customer, Venue Staff<br>Customer Mobile App, Platform Admin | Customer cancellation policy applies to `CUSTOMER_CANCELLED` and `USER_BANNED`. `VENUE_CANCELLED_MANUAL_BOOKING` is **NOT_APPLICABLE** because no customer fee exists. **Every reachable terminal reason has exactly one fee treatment.** | §34.2 |
| `FR-PTS-17` | M | Timer/Scheduler, Money/Points | Customer, Venue Staff<br>Customer Mobile App, Platform Admin | Declining or timing out a Late Confirmation is **not** an ordinary customer cancellation for fee purposes, because Late Confirm exists only through the venue's own expiry. | §34.3 |
| `FR-PTS-18` | M | Authorization, Trust/Enforcement, Money/Points | Customer, Platform Admin<br>Customer Mobile App, Platform Admin | Correcting an attendance classification is **not** a refund trigger. Refund follows **failure to deliver the booked service**, not reclassification. A customer-fault case must use its real reason rather than a generic `ADMIN_CORRECTION`. | §34.3 |
| `FR-PTS-19` | M | Timer/Scheduler, Money/Points | Customer<br>Customer Mobile App, Platform Admin | An operational interruption **after service start** receives a full refund regardless of the 15-minute rule. | §34.4 |
| `FR-PTS-20` | M | State/Lifecycle, Timer/Scheduler, Trust/Enforcement | Customer, Venue Staff<br>Customer Mobile App, Platform Admin | Counterpart phone visibility **ends**: for a booking terminal **before** start time, at `terminalAt + 2 hours` · for a booking terminal **at or after** start time, including NO_SHOW, at the original `endAt + 2 hours` · for VENUE_RESPONSE_EXPIRED, mandatorily at `venueResponseExpiredAt + 2 hours` unless the booking is revived before then. After the retention point the normal booking UI hides counterpart phone data. | §34.6 |
| `FR-PTS-21` | M | State/Lifecycle, Timer/Scheduler, Authorization, Audit/History | Customer, Venue Staff, Platform Admin<br>Customer Mobile App, Platform Admin | Retention past a started session exists to support operational disputes and late-arrival contact. Platform Admin and authorised support may retain controlled, audited access where operationally required. Expiry of phone visibility after VENUE_RESPONSE_EXPIRED **never** makes that booking terminal; it remains revivable until `startAt`. | §34.6, §34.7 |

**Domain acceptance obligations from PRD v1.1**

- Reversing a +1,000 referral for a user holding 300 points leaves the balance at 0 and records a 700 unrecovered amount that is never later deducted
- Changing the referral reward between signup and OTP verification grants the value in force **at verification**
- Every terminal reason in the closed enum maps to exactly one of the three fee treatments, verified by an exhaustive test over the enum
- No customer UI surface exposes a wallet screen

## 4.13 Reviews — 11 requirements

| ID | Pri | Type | Actor / Surface | Functional requirement | Governing v5.0 |
|---|---:|---|---|---|---|
| `FR-REV-01` | M | UI/Presentation | Customer<br>Customer Mobile App, Venue Dashboard, Platform Admin | A reviewer must be an **account-verified** user. A completed booking is **not** required. | §49.1 |
| `FR-REV-02` | M | Authorization, UI/Presentation | Customer, Platform Admin<br>Customer Mobile App, Venue Dashboard, Platform Admin | A review has a 1–5 star rating and a comment. Public reviews display the **current username only**; Platform Admin may see both full name and username. | §49.2 |
| `FR-REV-03` | M | Audit/History, UI/Presentation | Customer, Venue Staff<br>Customer Mobile App, Venue Dashboard, Platform Admin | One active review per user per venue, editable and deletable. Only one current record per user/venue at a time; historical removed records persist. | §49.3 |
| `FR-REV-04` | M | State/Lifecycle, Authorization, Audit/History, UI/Presentation | Customer, Venue Staff, Platform Admin<br>Customer Mobile App, Venue Dashboard, Platform Admin | States: PENDING_MODERATION (visible to author as *Pending Review* and to admin, not public, excluded from venue rating) · ACTIVE · HIDDEN (not public, editable, stays hidden after edit, restorable) · REMOVED (**terminal**, never restorable, moderation history permanent). | §49.4 |
| `FR-REV-05` | M | State/Lifecycle, Validation/Eligibility, Authorization, UI/Presentation | Customer, Platform Admin, System<br>Customer Mobile App, Venue Dashboard, Platform Admin | A normal eligible review is created **ACTIVE**, except in the moderation re-entry flow. There is **no auto-publish** of a pending review, however much time passes; admin reminders never change status automatically. | §49.4 |
| `FR-REV-06` | M | Audit/History, UI/Presentation | Customer<br>Customer Mobile App, Venue Dashboard, Platform Admin | Author deletion removes the public review but never destroys moderation audit and history. | §49.5 |
| `FR-REV-07` | M | Validation/Eligibility, Timer/Scheduler, Authorization, UI/Presentation | Customer, Venue Staff, Platform Admin<br>Customer Mobile App, Venue Dashboard, Platform Admin | After an admin removal the user may not review that venue for **30 days**; the next review starts PENDING_MODERATION. A further removal starts a new 30-day cooldown. | §49.6 |
| `FR-REV-08` | M | State/Lifecycle, Validation/Eligibility, Authorization, UI/Presentation | Customer, Venue Staff, Platform Admin<br>Customer Mobile App, Venue Dashboard, Platform Admin | The venue is **view only**. Platform Admin may hide, restore, remove, approve a pending review and remove a pending review; every action requires a reason and records action, reason, date, previous status and new status. | §49.7 |
| `FR-REV-09` | M | UI/Presentation | Customer, Venue Staff<br>Customer Mobile App, Venue Dashboard, Platform Admin | A SUSPENDED or INACTIVE venue keeps its reviews visible and may still receive new reviews. | §49.8 |
| `FR-REV-10` | M | Functional rule | Customer<br>Customer Mobile App, Venue Dashboard, Platform Admin | The author is notified on submission for moderation, approval, hiding and removal, with the moderation reason where appropriate. | §49.9 |
| `FR-REV-11` | M | Authorization, Audit/History, UI/Presentation | Customer, Platform Admin<br>Customer Mobile App, Platform Admin | A signed-in Customer may report a public Review visible to them with a mandatory reason and optional details. Reporting creates a durable Review Report, does not automatically change Review state and gives the Venue no moderation authority. Platform Admin may resolve the report and link an otherwise-valid existing moderation action. | §49.10 |

**Domain acceptance obligations from PRD v1.1**

- An unverified user cannot post a review
- Editing a hidden review leaves it hidden
- A removed review cannot be restored by any admin action
- A second review for the same venue 29 days after a removal is refused; on day 31 it is accepted as pending
- A pending review does not move the venue's average rating
- Reporting a visible Review creates a durable report while the Review remains unchanged unless an Admin separately performs a valid moderation action

## 4.14 Platform Admin — 11 requirements

| ID | Pri | Type | Actor / Surface | Functional requirement | Governing v5.0 |
|---|---:|---|---|---|---|
| `FR-ADM-01` | M | Authorization, Trust/Enforcement, Money/Points, Notification, UI/Presentation | Venue Staff, Platform Admin<br>Platform Admin | Platform Admin modules: Dashboard · Areas · Venues · **Venue Onboarding** · Venue Accounts · Activities · Users · Verified Players · Reviews Moderation · Global Bookings · No-show Records · Tournaments · Wallet · Points · Referrals · Platform Fees · **Global Rules** · **Notifications** · **Global Search** · Settings. English only. §51.1 names the core module set; §2.3 names the full launch surface set and both are frozen. | §2.3, §51.1 |
| `FR-ADM-02` | M | Inventory/Reservation, Authorization, Configuration | Venue Staff, Venue Owner authorization, Platform Admin<br>Platform Admin | Venue onboarding is **Platform-Admin managed**, not self-service, covering venue information, activities, account credentials including the initial Owner Password, resources and pricing. Venue images persist through onboarding, venue details, edit and navigation. | §51.2 |
| `FR-ADM-03` | M | Authorization | Venue Staff, Venue Owner authorization, Platform Admin<br>Platform Admin | Admin may disable and re-enable a venue account and reset the venue login and Owner Passwords **without deleting venue data**; an Owner Password reset notifies the venue. There is no venue self-service forgot-password flow in MVP. | §51.3 |
| `FR-ADM-04` | M | State/Lifecycle, Trust/Enforcement, Notification, UI/Presentation | Platform Admin<br>Platform Admin | User management displays name, username, phone, email, OTP verification, Verified Player status, completed bookings, no-show standing, account status and joined date. | §51.4 |
| `FR-ADM-05` | M | Authorization, Trust/Enforcement, UI/Presentation | Platform Admin, System<br>Platform Admin | Verified Player management supports View, Suspend, Revoke and Restore, each requiring a reason. Admin SUSPENDED and REVOKED are protected from automatic trust regain. | §51.5 |
| `FR-ADM-06` | M | State/Lifecycle, Timer/Scheduler, Inventory/Reservation, Money/Points, Audit/History | Customer, Venue Staff, Platform Admin<br>Platform Admin | Global bookings support filters (date range, area, venue, activity, status, source) and search (Booking ID, user name, phone, venue), exposing booking detail with Booking ID, user, venue, activity, resource, **console**, **play mode**, date, time, duration, venue price, **historical booking fee**, total, source, status and **full lifecycle history** — including Pending, Venue Response Expired, Auto Cancelled, Late Confirm Requested, Awaiting Customer Confirmation, Customer Accepted, Customer Declined, Customer Confirmation Expired, Rejected After Expiry, Venue Opening Hours Changed, and every operational and platform reason in v5.0 §33.2. | §51.6 |
| `FR-ADM-07` | M | Inventory/Reservation, Trust/Enforcement | Venue Staff, Platform Admin<br>Platform Admin | No-show records show user, venue, booking, resource, booking date, consecutive count, penalty stage, penalty result and ban information. | §51.7 |
| `FR-ADM-08` | M | State/Lifecycle, Timer/Scheduler, Inventory/Reservation | Venue Staff, Platform Admin<br>Platform Admin | Tournament inspection covers tournament, host, host type, activity, play mode, venue, resources, participants, capacity, entry, prize, visibility, date and time, deadline and status. | §51.8 |
| `FR-ADM-09` | M | State/Lifecycle, Money/Points, Audit/History, UI/Presentation | Venue Staff, Platform Admin<br>Platform Admin | Wallet module shows user, phone, balance and transaction history, and **never** venue gaming revenue. Points module shows user, current points, booking rewards, referral rewards and transaction history. Referral records show referrer, code, referred user, signup date, reward applied and status. | §51.9 |
| `FR-ADM-10` | M | Authorization, Money/Points | Platform Admin<br>Platform Admin | Admin controls the **Global Booking Fee** (EGP 0) and the **Tournament Creation Fee** (EGP 0). | §51.10 |
| `FR-ADM-11` | M | Audit/History, Configuration | Platform Admin<br>Platform Admin | Every configuration change affects **future events only**. | §53.1 |

**Domain acceptance obligations from PRD v1.1**

- Disabling a venue account preserves all venue data and every existing booking
- A global fee change leaves historical bookings' stored fee untouched
- An area with one SUSPENDED venue cannot be deactivated

## 4.15 Notifications — 12 requirements

| ID | Pri | Type | Actor / Surface | Functional requirement | Governing v5.0 |
|---|---:|---|---|---|---|
| `FR-NTF-01` | M | Notification | System<br>Platform Admin | Launch channels: In-App · Push · Email · **SMS for OTP only**. | §52.1 |
| `FR-NTF-02` | M | Inventory/Reservation, Trust/Enforcement, Money/Points, Notification, Audit/History | System<br>Platform Admin | Time-sensitive booking, tournament and trust events use **In-App + Push**, covering the full catalogue in v5.0 §52.2 — including every operational and platform cause, `ACTIVE_SESSION_ATTENDANCE_RECORDING_AFFECTED`, tournament resource-availability changes, and operational refund confirmations. The §52.2 item "Customer schedule-conflict failure" is withdrawn: a conflicting Late Confirm Accept is the immediate `409 LATE_CONFIRM_CUSTOMER_CONFLICT` error, with no notification. *(Amended by PCR-008.)* | §52.2; PCR-008 |
| `FR-NTF-03` | M | Notification, Audit/History | System<br>Platform Admin | Account, security and moderation events use **In-App + Email**, per v5.0 §52.3. | §52.3 |
| `FR-NTF-04` | M | Notification | System<br>Platform Admin | Every booking notification internally references the **specific Booking ID**, so navigation always lands on the correct booking. | §52.2 |
| `FR-NTF-05` | M | Trust/Enforcement, Money/Points, Notification | System<br>Platform Admin | An attendance-unconfirmed notification must explain that **no attendance reward was applied**. | §52.2 |
| `FR-NTF-06` | M | Timer/Scheduler, UI/Presentation | Customer, Venue Staff, System<br>Platform Admin | Customer copy must distinguish causes: ordinary expiry *"Venue did not respond in time"* · platform account disable *"Venue is temporarily unable to confirm this booking"* · operational issue the corresponding reason. Internal codes remain authoritative. | §33.6 |
| `FR-NTF-07` | M | State/Lifecycle, Validation/Eligibility, Notification | System<br>Platform Admin | A notification must **never** state that a booking was cancelled unless a rule actually cancelled it. | §32.13 |
| `FR-NTF-08` | M | Validation/Eligibility, Authorization, Notification | Customer, Venue Staff, System<br>Platform Admin | There is **no** mandatory customer notification merely because a venue account is re-enabled; the customer is notified only when a customer-relevant action occurs. | §32.13 |
| `FR-NTF-09` | M | Notification, UI/Presentation, Configuration | Venue Staff, System<br>Platform Admin | Venue notifications cover new requests, expiring requests, auto-cancellations, all Late Confirm outcomes, tournament requests and outcomes, maintenance starting soon, emergency operational reminders, and owner notification of emergencies and owner-review entry. | §52.5 |
| `FR-NTF-10` | M | Inventory/Reservation, Authorization, Trust/Enforcement, Money/Points, Notification | Platform Admin, System<br>Platform Admin | Platform Admin notifications cover onboarding completion, account disable, fee updates, Verified Player revocation, tournament auto-cancellation, ban activation, emergency declarations, owner-review entry, the tournament-cancellation abuse alert, check-in and Late-Present abuse signals, integrity-error alerts, and account disable overlapping an active booking. | §52.6 |
| `FR-NTF-11` | M | State/Lifecycle, Authorization, Trust/Enforcement, Money/Points | Customer, Platform Admin, System<br>Platform Admin | The customer is notified whenever an admin materially changes a ban, a no-show, an attendance result, points, a booking status or a referral reward in a way that affects them. | §52.3 |
| `FR-NTF-12` | M | Notification | System<br>Platform Admin | Notifications dispatch **after** the transaction commits, never before. | §40.5 |

**Domain acceptance obligations from PRD v1.1**

- A push for any booking event deep-links to that exact Booking ID, including a revived Late Confirm booking
- The account-disable notification contains no cancellation language
- Re-enabling an account sends nothing by itself
- No notification is delivered for a transaction that rolled back

## 4.16 Audit and Historical Integrity / Reporting — 15 requirements

| ID | Pri | Type | Actor / Surface | Functional requirement | Governing v5.0 |
|---|---:|---|---|---|---|
| `FR-AUD-01` | M | Inventory/Reservation, Money/Points, Audit/History, Configuration | System<br>Platform Admin | Configuration changes affect **future events only**. Historical transactions retain their original values: booking fee · referral reward · booking price · tournament values applied at creation · Late Confirm original totals · **points actually granted** · **console type** · **play mode** · **resource identity** · **resource name snapshot**. | §5.9, §53.1 |
| `FR-AUD-02` | M | Inventory/Reservation, Audit/History, UI/Presentation | System<br>Platform Admin | Historical snapshots are stored for bookings, tournaments, referrals, reservations, operational interruptions, emergency pause history, account access restriction intervals and owner-review cascades, per v5.0 §53.2. | §53.2 |
| `FR-AUD-03` | M | Trust/Enforcement, Audit/History | Venue Staff, System<br>Platform Admin | Every action in the canonical audit list of v5.0 §53.3 writes an audit record — venue operational actions, operational interruptions, platform and integrity events, trust and ledger actions, and moderation. | §53.3 |
| `FR-AUD-04` | M | Trust/Enforcement, Money/Points, Audit/History | System<br>Platform Admin | Append-only guarantees: booking history · points and wallet history · trust input events · attendance result (audited correction only) · `businessDate` and operating-session snapshots. | §53.4 |
| `FR-AUD-05` | M | Audit/History, UI/Presentation | System<br>Platform Admin | Booking history is append-only: previous lifecycle events never disappear. *Auto Cancelled* stays visible even after a Late Confirm revival. | §33.7 |
| `FR-AUD-06` | M | Validation/Eligibility, Authorization, Audit/History | Venue Staff, Platform Admin, System<br>Platform Admin | Every audit record carries the actor identity (venue account, admin or system), a timestamp and, where the action requires one, the mandatory reason. | §53.3 |
| `FR-AUD-13` | M | State/Lifecycle, Validation/Eligibility, Audit/History, UI/Presentation | System<br>Platform Admin | **Booking Reason Codes** and **Booking Events** are two **separate closed enums**. A reason code explains a **terminal** booking outcome; an event records an action or a **non-terminal** failure in append-only history. A token belongs to exactly one of the two. Adding a product-visible member to either requires Product Change Control. | §33.1, §57.3 |
| `FR-AUD-14` | M | State/Lifecycle, Validation/Eligibility, Inventory/Reservation, Trust/Enforcement, Audit/History | Customer, Venue Staff, System<br>Platform Admin | The canonical **Booking Event** list is `CHECK_IN_RECORDED` · `CHECK_IN_REVERSED` · `LATE_PRESENT_RECORDED` · `LATE_PRESENT_REVERSED` · `NO_SHOW_RECORDED` · `NO_SHOW_REVERSED` · `ATTENDANCE_CORRECTED` · `LATE_CONFIRM_FAILED_RESOURCE_CONFLICT` · `LATE_CONFIRM_FAILED_CUSTOMER_CONFLICT` · `LATE_CONFIRM_FAILED_BOOKING_LIMIT` · `LATE_CONFIRM_ELIGIBILITY_LOST_RESOURCE_STATE_CHANGED` · `VENUE_RESPONSE_INTERRUPTED_BY_ACCOUNT_DISABLE`. **No API validation-only token belongs in this enum**, which is why `MANUAL_BOOKING_PRICING_CONFIGURATION_MISSING` is an error code only. | §33.4 |
| `FR-AUD-15` | M | State/Lifecycle, Validation/Eligibility, Inventory/Reservation, Money/Points, Audit/History | Customer, Venue Staff, System<br>Platform Admin | Five tokens are **deliberately removed** and must not be reintroduced: `BOOKING_EXPIRED` (use `VENUE_DID_NOT_RESPOND_IN_TIME`) · `RESOURCE_CONFLICT` (validation or atomic-commit failure, or `LATE_CONFIRM_FAILED_RESOURCE_CONFLICT`) · `CUSTOMER_SCHEDULE_CONFLICT` (`LATE_CONFIRM_FAILED_CUSTOMER_CONFLICT`) · `LATE_CONFIRM_RESOURCE_UNAVAILABLE` (as above) · `LATE_CONFIRM_ELIGIBILITY_LOST_RESOURCE_STATE_CHANGED` **as a terminal reason** (it is an event of the same name). Because none is terminal, none needs a fee treatment — which is what makes every reachable terminal reason map to exactly one treatment. | §33.3 |
| `FR-AUD-07` | M | Timer/Scheduler, Money/Points, Audit/History | Venue Staff, System<br>Platform Admin | `businessDate` is the single revenue-day key across online bookings, manual bookings and tournaments, and is historical. | §17.5 |
| `FR-AUD-08` | M | Timer/Scheduler, Money/Points | Venue Staff, System<br>Platform Admin | Venue revenue reporting aggregates today's revenue, this month's revenue, and online, manual and tournament revenue on `businessDate`, with an optional activity breakdown. | §50.3 |
| `FR-AUD-09` | M | Money/Points | Venue Staff, System<br>Platform Admin | Tournament revenue recognises the stored venue cost only on **COMPLETED**. | §36.10 |
| `FR-AUD-10` | M | Money/Points, Audit/History | Venue Staff, System<br>Platform Admin | Booking detail exposes the historical booking fee alongside the venue price and total, so a future non-zero fee is reportable without back-filling. | §51.6 |
| `FR-AUD-11` | S | State/Lifecycle | Customer, Venue Staff, System<br>Platform Admin | Platform reporting can produce the terminal-reason distribution split by customer-caused, venue-caused, operational and platform causes. Derived from PRD §4.2 (G3), not from v5.0. | **TBD / Proposed non-product requirement — not defined by Frozen Baseline** |
| `FR-AUD-12` | S | Trust/Enforcement | Venue Staff, System<br>Platform Admin | Platform reporting can produce the attendance mix and the check-in capture rate per venue. Derived from PRD §4.2 (G4), not from v5.0. | **TBD / Proposed non-product requirement — not defined by Frozen Baseline** |

**Domain acceptance obligations from PRD v1.1**

- A booking that goes PENDING → expired → Late Confirm → CONFIRMED → ACTIVE → COMPLETED shows every step in order, with nothing removed
- Changing a venue's hourly price leaves every historical booking's stored price and total unchanged
- Every audited action in v5.0 §53.3 produces a retrievable record in an end-to-end run

---

# 5. FUNCTIONAL ACCEPTANCE AND QA OBLIGATIONS

## 5.1 Mandatory cross-domain acceptance

1. Every `M` functional requirement must be implemented and testable.
2. No path may create overlapping active reservations for one resource.
3. Every terminal Booking and Tournament must carry exactly one closed reason code from its domain.
4. Every time boundary uses the frozen half-open/strict rule: an action allowed “before” a deadline requires `now < deadline`; at `now >= deadline`, expiry owns the state unless the specification explicitly defines another boundary.
5. Commitment-strengthening transitions revalidate immediately before commit and resolve races by the first successfully committed valid transition; the loser revalidates/fails cleanly.
6. Historical prices, fees, snapshots, audit and Trust facts are not silently rewritten. Corrections use the Product-defined correction/compensating model.
7. Owner Password-protected actions require fresh per-action validation.
8. Venue Account Disable, operational emergencies and OWNER_REVIEW_REQUIRED must never be mislabeled as ordinary venue non-response or customer fault.
9. Trust replay must be deterministic and reproduce current/as-of derived state from the canonical ordered event stream.
10. Product-visible behavior not supported by v5.0/PRD v1.1 is a change request, not an implementation choice.

## 5.2 High-risk functional test packs

- **Inventory race pack:** N simultaneous requests for the same slot, Late Confirm versus new booking, Tournament Hold versus booking, no-show release versus new sale.
- **Timer boundary pack:** venue response, intake cutoff, lead time, Late Confirm, Check-in/No-show/Late Present, tournament approval/checkpoint, OTP and cooldown boundaries at ±1 second where meaningful.
- **Emergency pack:** venue vs resource emergency scope, timer pause/resume/cumulative cap, optimistic feasibility, active interruption, conversion, owner-review entry and no-sellable-gap invariant.
- **Account Disable pack:** PENDING, AWAITING, CONFIRMED, ACTIVE and PENDING_VENUE_APPROVAL tournament behavior; attendance protection with repeated disable/re-enable intervals.
- **Trust correction pack:** no-show reversal, chained attendance corrections, retroactive ban qualification, active-ban extension, Verified Player revoke/automatic regain/admin-protected states.
- **Financial-history pack:** price/fee/referral/points snapshots, refund classification, manual fee NOT_APPLICABLE, correction without rewriting history.

---

# 6. TRACEABILITY AND COMPLETENESS

## 6.1 Requirement counts

| Domain | Count |
|---|---:|
| Accounts, Identity and Verification | 18 |
| Discovery and Browsing | 14 |
| Availability and Inventory | 15 |
| Online Booking Lifecycle | 32 |
| Late Confirmation | 18 |
| Attendance, Check-in and No-show | 28 |
| Manual Booking | 11 |
| Venue Model, Configuration and Dashboard | 47 |
| Maintenance, Emergencies and Operational Restrictions | 44 |
| Tournaments | 33 |
| Trust, Penalties and Bans | 27 |
| Points, Referrals, Wallet and Fees | 21 |
| Reviews | 11 |
| Platform Admin | 11 |
| Notifications | 12 |
| Audit and Historical Integrity / Reporting | 15 |
| **TOTAL** | **357** |

## 6.2 Completeness validation

- PRD Functional Requirements expected: **357**
- FRS Functional Requirements represented: **357**
- Unique primary requirement IDs: **357**
- Missing primary requirements: **0**
- Duplicated primary requirements: **0**
- New Product states/enums/reason codes introduced by this FRS: **0**
- Technical Architecture choices made by this FRS: **0**
- Remaining FRS behaviour mismatches after Derivation Audit: **0**
- Remaining functional-representation omissions after Derivation Audit: **0**
- Remaining canonical grouping / authorization / timer representation errors: **0**

## 6.3 Source precedence

1. Product Specification v5.0 — exact behavior.
2. PRD v1.1 — approved requirements/scope/priority.
3. This FRS — functional decomposition and traceability.
4. Future Technical Architecture — implementation choices within v5.0 latitude.

## 6.4 Change control

A new enum member, state transition, authorization rule, canonical availability/creation/commitment predicate change, Trust replay/effective-time/counter-reset change, refund-classification change, or change to a v5.0 fixed Product rule requires a Product Change Request against v5.0 first. FRS wording may be improved without a Product change only when behavior remains identical.

---

*End of E7GZ PS Functional Requirements Specification (FRS) v1.1 — APPROVED FOR BUILD.*
