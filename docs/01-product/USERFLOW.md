# E7GZ PS
# USER FLOWS v1.0 — FUNCTIONAL BASELINE

**Status:** Derived functional baseline  
**Authority:** Product Specification v5.0 — FROZEN BASELINE → PRD v1.1 — APPROVED FOR BUILD → FRS v1.1 — APPROVED FOR BUILD  
**Market:** Egypt — initial launch in Cairo  
**Timezone:** Africa/Cairo  
**Currency:** EGP

> **No Product change.** This document is a derived working view of the approved baselines. It does not create new states, transitions, reason codes, timers, authorization rules, validation predicates, Trust semantics, financial rules, or scope. If anything here conflicts with Product Specification v5.0, v5.0 wins.

---

# 0. PURPOSE

This file defines the approved **end-to-end User / Operational Flows** only. It is the flow view of the frozen Product and does not replace the FRS.

Each major flow identifies:
- actors / roles
- trigger
- inputs
- outputs
- happy path
- alternate / error paths
- source requirement coverage

---

## F-01 — Customer registration and phone verification

**Actors / Roles:** Customer; System  
**Trigger:** Customer starts registration  
**Inputs:** Full Name; Username; Phone; Email; Password; SMS OTP  
**Outputs:** Registered account; Account Verified after valid phone OTP  
**Requirement Coverage:** `FR-ACC-01` · `FR-ACC-02` · `FR-ACC-04` · `FR-ACC-05` · `FR-ACC-06`

### Happy Path

1. Customer submits Full Name, Username, Phone, Email and Password.
2. System validates mandatory fields and normalized Username/Phone uniqueness.
3. Account is created but marketplace actions remain gated until phone verification.
4. System issues SMS OTP under the frozen validity, attempt, resend and hourly-send limits.
5. A valid phone OTP sets the account to Account Verified; email verification remains independent.
6. Marketplace-gated actions use phone verification, not email verification, as the activation condition.

### Alternate / Error Paths

- Duplicate normalized Username or Phone is rejected.
- Expired/invalid OTP, attempt limit, resend delay and hourly-send limit are enforced.
- Email may remain unverified after phone verification; marketplace activation still depends on phone verification.

### Flow Boundary

Only behaviour supported by the cited approved requirements and Product Specification v5.0 is valid. Any additional branch or outcome requires Product Change Control.

---

## F-02 — Venue discovery and venue details

**Actors / Roles:** Customer; System  
**Trigger:** Customer begins discovery  
**Inputs:** Location permission and/or manual Area; selected Activity  
**Outputs:** Eligible venue list/map and Venue Details  
**Requirement Coverage:** `FR-DIS-13` · `FR-DIS-01` · `FR-DIS-02` · `FR-DIS-03` · `FR-DIS-04` · `FR-DIS-06` · `FR-DIS-08` · `FR-DIS-10` · `FR-DIS-11`

### Happy Path

1. Customer resolves Area/Location and selects Activity before venue discovery.
2. System lists discoverable venues under marketplace and Area rules; manual Area selection is a hard filter.
3. Venue cards show the frozen field set including starting price and Nearest Availability.
4. Closed venues remain browseable with future availability but cannot accept a new request until a valid intake window.
5. Map and Venue Details use the same eligibility/availability truth as list discovery.

### Alternate / Error Paths

- Manual Area selection remains a hard filter even if location is enabled.
- ONBOARDING, INACTIVE and SUSPENDED venues are excluded from normal discovery.
- A currently closed venue remains browseable, but new booking submission waits for a valid intake window.
- A valid deep link to an inactive/suspended venue may render with booking disabled.

### Flow Boundary

Only behaviour supported by the cited approved requirements and Product Specification v5.0 is valid. Any additional branch or outcome requires Product Change Control.

---

## F-03 — Find Available

**Actors / Roles:** Customer; System  
**Trigger:** Customer runs Find Available  
**Inputs:** Area; Activity; Date; Start Time; Duration  
**Outputs:** Resources available for the entire requested interval  
**Requirement Coverage:** `FR-DIS-12` · `FR-AVL-01` · `FR-AVL-02` · `FR-AVL-03`

### Happy Path

1. Customer supplies Area, Activity, Date, Start Time and Duration.
2. System evaluates the entire requested interval with the single canonical availability predicate.
3. Only resources available for the whole interval are returned.
4. No price filter is available in this release.

### Alternate / Error Paths

- No results are returned when every Resource fails the canonical predicate.
- DISABLED, ARCHIVED, OWNER_REVIEW_REQUIRED or selling-blocked Resources never appear as available.
- There is no price filter in this release.

### Flow Boundary

Only behaviour supported by the cited approved requirements and Product Specification v5.0 is valid. Any additional branch or outcome requires Product Change Control.

---

## F-04 — Create online booking

**Actors / Roles:** Account-Verified Customer; System  
**Trigger:** Customer confirms booking summary  
**Inputs:** Venue; Activity; Resource; PlayStation mode where applicable; Date; Start Time; Duration  
**Outputs:** One PENDING Online Booking; blocking reservation; venue-response deadline  
**Requirement Coverage:** `FR-BOK-01` · `FR-BOK-02` · `FR-BOK-03` · `FR-BOK-04` · `FR-BOK-08` · `FR-BOK-09` · `FR-BOK-11` · `FR-BOK-13` · `FR-AVL-09` · `FR-AVL-10`

### Happy Path

1. Customer selects resource, start time and nominal duration and confirms the request.
2. System checks the twenty cumulative creation conditions, including account, ban, commitment count, overlap, venue/account/Area, operating-session/intake, selling blocks, timing, resource state/restriction, reservation conflicts and compatibility.
3. Validation and commitment creation are atomic; displayed availability is not a commit guarantee.
4. On success, one Booking is created in PENDING, the resource interval is reserved and the 10-minute venue response timer begins.
5. No Auto Accept exists.

### Alternate / Error Paths

- Unverified, banned, over-limit or schedule-conflicting Customer is rejected.
- Invalid Venue/Area/Resource/Operating Session/intake/timing state is rejected.
- A concurrent winner may consume the slot; execution revalidates and the losing request fails cleanly.
- No Auto Accept exists.

### Flow Boundary

Only behaviour supported by the cited approved requirements and Product Specification v5.0 is valid. Any additional branch or outcome requires Product Change Control.

---

## F-05 — Venue accepts a pending booking

**Actors / Roles:** Venue Staff; System  
**Trigger:** Venue selects Accept before response deadline  
**Inputs:** PENDING Booking ID; Accept action  
**Outputs:** CONFIRMED Booking with reservation preserved  
**Requirement Coverage:** `FR-BOK-14` · `FR-BOK-15` · `FR-BOK-16` · `FR-AVL-09` · `FR-AVL-10`

### Happy Path

1. Venue staff selects Accept on an existing PENDING request.
2. System applies Existing-Commitment Operational eligibility; marketplace ACTIVE is not required.
3. System revalidates customer account/ban, venue and resource operational gates, reservation and interval atomically.
4. If valid, PENDING becomes CONFIRMED and the reservation remains held.
5. If a defined operational cause prevents confirmation, the booking follows that cause rather than venue non-response blame.

### Alternate / Error Paths

- At or after the deadline, expiry owns the state.
- Operational ineligibility prevents confirmation and uses the applicable operational cause rather than venue non-response blame.
- A concurrent authoritative state change forces revalidation/failure.

### Flow Boundary

Only behaviour supported by the cited approved requirements and Product Specification v5.0 is valid. Any additional branch or outcome requires Product Change Control.

---

## F-06 — Venue rejects a pending booking

**Actors / Roles:** Venue Staff; System  
**Trigger:** Venue selects Reject before expiry  
**Inputs:** PENDING Booking ID; mandatory venue-supplied reject reason  
**Outputs:** REJECTED Booking; released reservation  
**Requirement Coverage:** `FR-BOK-17`

### Happy Path

1. Venue staff enters a mandatory operational reject reason and confirms Reject.
2. PENDING becomes REJECTED with terminal reason VENUE_REJECTED.
3. The venue-supplied reason is stored separately from the terminal reason code.
4. The resource reservation is released immediately and required notifications/history are produced.

### Alternate / Error Paths

- Missing reject reason prevents the action.
- If the Booking is no longer PENDING, the action is refused after revalidation.

### Flow Boundary

Only behaviour supported by the cited approved requirements and Product Specification v5.0 is valid. Any additional branch or outcome requires Product Change Control.

---

## F-07 — Venue response expiry

**Actors / Roles:** System; Customer; Venue Staff  
**Trigger:** venueResponseExpiresAt reached without a committed Venue response  
**Inputs:** PENDING Booking whose response deadline arrives  
**Outputs:** VENUE_RESPONSE_EXPIRED; released reservation and commitment slot  
**Requirement Coverage:** `FR-BOK-18` · `FR-BOK-19` · `FR-BOK-20` · `FR-BOK-23`

### Happy Path

1. At the venue response deadline, if no valid response owns the state, PENDING becomes VENUE_RESPONSE_EXPIRED.
2. The resource reservation and customer commitment slot are released; the state remains non-terminal.
3. Customer projection shows the request under Cancelled with venue-nonresponse copy while the internal Booking remains eligible for Late Confirm until startAt.
4. If still expired at startAt, the terminal reason is chosen by the frozen priority: owner review, resource emergency, venue emergency, account disabled, otherwise venue did not respond.

### Alternate / Error Paths

- VENUE_RESPONSE_EXPIRED remains non-terminal and may be Late-Confirmed before startAt.
- At startAt, owner review, resource emergency, venue emergency and account-disable causes take priority over generic non-response.

### Flow Boundary

Only behaviour supported by the cited approved requirements and Product Specification v5.0 is valid. Any additional branch or outcome requires Product Change Control.

---

## F-08 — Reject after expiry

**Actors / Roles:** Venue Staff; System  
**Trigger:** Venue selects Reject After Expiry before startAt  
**Inputs:** VENUE_RESPONSE_EXPIRED Booking ID  
**Outputs:** REJECTED with REJECTED_AFTER_EXPIRY  
**Requirement Coverage:** `FR-BOK-21`

### Happy Path

1. Before startAt, Venue staff may act on a VENUE_RESPONSE_EXPIRED request.
2. Reject After Expiry changes the booking to REJECTED with REJECTED_AFTER_EXPIRY.
3. No reservation exists to release because expiry already released it.

### Alternate / Error Paths

- No Resource release occurs because expiry already released the reservation.
- Action is unavailable after startAt or after another authoritative transition.

### Flow Boundary

Only behaviour supported by the cited approved requirements and Product Specification v5.0 is valid. Any additional branch or outcome requires Product Change Control.

---

## F-09 — Late Confirmation revival

**Actors / Roles:** Venue Staff; Customer; System  
**Trigger:** Venue initiates Late Confirm  
**Inputs:** Eligible expired Booking; original booking snapshots; Customer response  
**Outputs:** AWAITING_CUSTOMER_CONFIRMATION then CONFIRMED or CANCELLED  
**Requirement Coverage:** `FR-LCF-01` · `FR-LCF-02` · `FR-LCF-03` · `FR-LCF-04` · `FR-LCF-06` · `FR-LCF-08` · `FR-LCF-09` · `FR-LCF-13` · `FR-LCF-14` · `FR-LCF-15` · `FR-LCF-16` · `FR-LCF-17`

### Happy Path

1. Venue initiates Late Confirm on an eligible VENUE_RESPONSE_EXPIRED Booking.
2. System treats the action as Existing-Commitment Operational management and checks venue, resource and customer eligibility.
3. Commitment-count check, resource validation, temporary Hold creation and transition to AWAITING_CUSTOMER_CONFIRMATION occur atomically.
4. The same Booking ID and original commercial/resource snapshots are retained; expiry is MIN(now + 5 minutes, startAt).
5. Customer Accept revalidates commitment count, customer schedule, ban/account, resource and operational eligibility then moves to CONFIRMED.
6. Customer Decline cancels under CUSTOMER_DECLINED_LATE_CONFIRM; timeout cancels under CUSTOMER_RECONFIRM_TIMEOUT; both are penalty-free and use full future fee refund classification.
7. Failed accept due to customer/resource conflict records the canonical non-terminal event rather than inventing a terminal reason.

### Alternate / Error Paths

- Initiation is blocked at 4 counted commitments and creates no hold.
- Resource/operational ineligibility blocks initiation or Accept.
- Customer schedule conflict on Accept records LATE_CONFIRM_FAILED_CUSTOMER_CONFLICT without inventing a terminal reason.
- Resource conflict records the canonical non-terminal failure path.
- Decline or timeout cancels penalty-free.
- Emergency pause rules may pause/resume/cap the countdown; Customer may still Decline while paused.

### Flow Boundary

Only behaviour supported by the cited approved requirements and Product Specification v5.0 is valid. Any additional branch or outcome requires Product Change Control.

---

## F-10 — Customer cancellation

**Actors / Roles:** Customer; System  
**Trigger:** Customer selects Cancel while now < startAt  
**Inputs:** Eligible future Online Booking  
**Outputs:** CANCELLED with CUSTOMER_CANCELLED; released inventory/commitment  
**Requirement Coverage:** `FR-BOK-31` · `FR-PTS-14` · `FR-PTS-16`

### Happy Path

1. Customer requests cancellation while now < startAt.
2. The booking is cancelled under CUSTOMER_CANCELLED, inventory/commitment are released and there is no no-show/trust penalty.
3. After startAt, ordinary customer cancellation is disabled.
4. If a non-zero Booking Fee exists in the future, refundability follows the frozen 15-minute-from-creation and before-start rule.

### Alternate / Error Paths

- At now >= startAt, ordinary Customer cancellation is disabled.
- A banned Customer may still cancel an existing future Booking.
- A PENDING Booking may still be cancelled while its venue-response timer is paused.

### Flow Boundary

Only behaviour supported by the cited approved requirements and Product Specification v5.0 is valid. Any additional branch or outcome requires Product Change Control.

---

## F-11 — Booking start-time transition

**Actors / Roles:** System  
**Trigger:** startAt reached  
**Inputs:** CONFIRMED Booking at startAt  
**Outputs:** ACTIVE or one deterministic terminal operational outcome  
**Requirement Coverage:** `FR-BOK-24` · `FR-BOK-25` · `FR-BOK-26` · `FR-BOK-27` · `FR-BOK-28`

### Happy Path

1. At startAt, System revalidates operational gates immediately before CONFIRMED → ACTIVE.
2. If all gates pass, the Booking becomes ACTIVE automatically.
3. If a gate fails, the Booking cannot remain CONFIRMED past startAt; it terminates under exactly one frozen reason according to priority.
4. Resource-state anomalies that should have been impossible use SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR, with no customer penalty and the required admin alert.

### Alternate / Error Paths

- OWNER_REVIEW_REQUIRED outranks resource emergency, venue emergency, closure and integrity fallback.
- Venue Account DISABLED alone is not a CONFIRMED-start failure.
- Unexpected resource-state anomaly uses SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR, full-refund classification and admin alert.

### Flow Boundary

Only behaviour supported by the cited approved requirements and Product Specification v5.0 is valid. Any additional branch or outcome requires Product Change Control.

---

## F-12 — Check-in

**Actors / Roles:** Venue Staff; System  
**Trigger:** Staff selects Check In  
**Inputs:** Eligible Booking ID; current time  
**Outputs:** CHECK_IN_RECORDED operational evidence  
**Requirement Coverage:** `FR-ATT-01` · `FR-ATT-02`

### Happy Path

1. Venue staff records Check-in only while startAt <= now < startAt + 15 minutes.
2. At exactly +15 minutes Check-in is refused.
3. The action needs no Owner Password and is audited as operational evidence.
4. Check-in affects final Trust only through the later canonical attendance outcome.

### Alternate / Error Paths

- Allowed only for startAt <= now < startAt + 15 minutes.
- At exactly +15 minutes, Check-in is refused.
- Manual Bookings do not use Customer Check-in.
- Venue Account Disable may prevent the staff action during an ACTIVE session; Platform Attendance Protection rules handle affected evidence.

### Flow Boundary

Only behaviour supported by the cited approved requirements and Product Specification v5.0 is valid. Any additional branch or outcome requires Product Change Control.

---

## F-13 — Mark Present — Late

**Actors / Roles:** Venue Staff; System  
**Trigger:** Customer is present after Check-in window  
**Inputs:** Booking ID; current time  
**Outputs:** LATE_PRESENT_RECORDED; no-show blocked; later ATTENDANCE_UNCONFIRMED  
**Requirement Coverage:** `FR-ATT-03`

### Happy Path

1. From startAt + 15 minutes until before endAt, Venue staff may mark the customer present if no valid Check-in and no committed No-show exist.
2. The evidence blocks No-show for the Booking.
3. It grants no points, no Verified Player progress and no counter reset by itself; the final outcome is resolved under the attendance rules.

### Alternate / Error Paths

- Available only while startAt + 15 min <= now < endAt.
- Unavailable if valid Check-in exists or a No-show has already committed.
- Grants no Points, Verified Player progress or no-show-counter reset.

### Flow Boundary

Only behaviour supported by the cited approved requirements and Product Specification v5.0 is valid. Any additional branch or outcome requires Product Change Control.

---

## F-14 — No-show

**Actors / Roles:** Venue Staff; System  
**Trigger:** Customer is absent after +15 minutes  
**Inputs:** Eligible Booking ID; current time  
**Outputs:** NO_SHOW terminal state; ABSENT_NO_SHOW attendance; immediate release of remaining interval  
**Requirement Coverage:** `FR-ATT-04` · `FR-ATT-05` · `FR-ATT-06`

### Happy Path

1. From startAt + 15 minutes until before endAt, Venue staff may commit No-show only if no Check-in and no Late Present evidence exists.
2. The Booking becomes terminal status NO_SHOW; the `NO_SHOW_RECORDED` Booking Event is written and the default Attendance Result is ABSENT_NO_SHOW. The remaining resource interval is released immediately.
3. Venue staff cannot undo a committed No-show; Platform Admin correction is required.
4. A later reversal never evicts a new reservation that validly used the released interval.

### Alternate / Error Paths

- Unavailable before +15 minutes, at/after endAt, after Check-in, or after Mark Present — Late.
- Venue staff cannot undo a committed No-show; Platform Admin correction is required.
- A later valid booking may use the released remainder without being evicted by a future correction.

### Flow Boundary

Only behaviour supported by the cited approved requirements and Product Specification v5.0 is valid. Any additional branch or outcome requires Product Change Control.

---

## F-15 — Booking completion and attendance resolution

**Actors / Roles:** System  
**Trigger:** endAt reached without earlier terminal interruption/no-show  
**Inputs:** ACTIVE Booking at endAt plus attendance evidence  
**Outputs:** COMPLETED plus canonical Attendance Result  
**Requirement Coverage:** `FR-BOK-24` · `FR-ATT-07` · `FR-ATT-08` · `FR-TRS-01` · `FR-TRS-02`

### Happy Path

1. At endAt, an ACTIVE Booking that has not been operationally terminated becomes COMPLETED automatically.
2. Attendance Result is resolved independently from Booking Status using the frozen evidence/default table.
3. The resulting canonical Trust input, points, progress and no-show-counter effect are applied once according to corrected history.

### Alternate / Error Paths

- Valid Check-in → ATTENDED.
- Late Present only → ATTENDANCE_UNCONFIRMED.
- No verified attendance and no No-show → ATTENDANCE_UNCONFIRMED.
- Manual Booking → NOT_APPLICABLE.
- Operational interruption uses its own attendance-resolution rules instead of normal completion.

### Flow Boundary

Only behaviour supported by the cited approved requirements and Product Specification v5.0 is valid. Any additional branch or outcome requires Product Change Control.

---

## F-16 — Create Manual Booking

**Actors / Roles:** Venue Staff; System  
**Trigger:** Venue creates a walk-in/phone/private reservation  
**Inputs:** Venue/Resource; Activity; Start Time; Duration; optional Customer Name/Phone  
**Outputs:** MANUAL Booking as ACTIVE now or CONFIRMED future; blocking reservation; businessDate  
**Requirement Coverage:** `FR-MAN-01` · `FR-MAN-02` · `FR-MAN-03` · `FR-MAN-04` · `FR-MAN-05` · `FR-MAN-06` · `FR-MAN-09` · `FR-MAN-10` · `FR-VEN-45`

### Happy Path

1. Venue staff creates a NOW or FUTURE internal booking; past backdating is not allowed.
2. Venue Account must be ENABLED and Marketplace may be ACTIVE, INACTIVE or SUSPENDED, never ONBOARDING.
3. System applies the same resource conflict and operational restrictions as other reservation flows; published Opening Hours do not gate Manual Booking.
4. Required pricing configuration must exist or creation fails before any Booking/reservation record.
5. Future Manual starts CONFIRMED; current walk-in starts ACTIVE.
6. Manual Booking has attendance NOT_APPLICABLE, no Trust/points/progress/no-show effect, and always stores businessDate under the frozen rule.
7. Ordinary Venue cancellation under `VENUE_CANCELLED_MANUAL_BOOKING` is a **before-Start** action on a CONFIRMED Manual Booking. An ACTIVE Manual Booking may end early only through a defined operational/system interruption; that interruption uses its actual operational reason and Attendance Result remains NOT_APPLICABLE.

### Alternate / Error Paths

- ONBOARDING Venue is invalid; ACTIVE/INACTIVE/SUSPENDED may create if Account is ENABLED and operational gates pass.
- Published Opening Hours and Area marketplace status do not block Manual Booking.
- Historical/back-dated start is refused.
- Missing pricing returns MANUAL_BOOKING_PRICING_CONFIGURATION_MISSING with no Booking or reservation.
- Conflicting inventory is refused.

### Flow Boundary

Only behaviour supported by the cited approved requirements and Product Specification v5.0 is valid. Any additional branch or outcome requires Product Change Control.

---

## F-17 — Opening Hours change

**Actors / Roles:** Venue Staff; Venue Owner when protected cancellation is created; System  
**Trigger:** Venue saves Opening Hours change  
**Inputs:** Proposed Opening Hours; affected date/session rules  
**Outputs:** Updated Opening Hours or blocked save; atomic affected-booking outcomes  
**Requirement Coverage:** `FR-VEN-17` · `FR-VEN-18` · `FR-VEN-19` · `FR-VEN-20` · `FR-VEN-23` · `FR-VEN-24` · `FR-VEN-25` · `FR-VEN-26` · `FR-VEN-27` · `FR-VEN-28`

### Happy Path

1. Venue edits Default or per-day Opening Hours and requests Save.
2. System validates the cyclic weekly session model and evaluates affected reservations in the frozen order.
3. Tournament conflict blocks the save.
4. If protected Customer Bookings would be cancelled, the final save requires Owner Password; otherwise it does not.
5. The change and its affected set commit atomically; affected bookings use VENUE_OPENING_HOURS_CHANGED and required notifications/refunds.
6. ACTIVE bookings are not auto-cancelled merely by an ordinary Hours edit.

### Alternate / Error Paths

- Tournament conflicts block the save first.
- If protected Online/Awaiting bookings would be cancelled, fresh Owner Password is required.
- ACTIVE Bookings are not auto-cancelled by an ordinary hours change.
- VENUE_RESPONSE_EXPIRED is excluded; later Late Confirm revalidates.

### Flow Boundary

Only behaviour supported by the cited approved requirements and Product Specification v5.0 is valid. Any additional branch or outcome requires Product Change Control.

---

## F-18 — Maintenance

**Actors / Roles:** Venue Staff; Venue Owner when threshold exceeded; System  
**Trigger:** Venue creates/edits/deletes Maintenance  
**Inputs:** Resource; Maintenance start/end; reason  
**Outputs:** Maintenance block or rejected change  
**Requirement Coverage:** `FR-OPS-01` · `FR-OPS-02` · `FR-OPS-03` · `FR-OPS-04` · `FR-OPS-05`

### Happy Path

1. Venue creates/edits/deletes resource Maintenance.
2. Maintenance may not overlap an existing blocking reservation and never silently cancels one.
3. Maintenance exceeding more than 24 hours inside a rolling 48-hour window for the resource requires Owner Password; emergency-created Maintenance counts toward the same total.
4. All maintenance changes are audited and affect canonical availability.

### Alternate / Error Paths

- Existing protected reservations block Maintenance rather than being silently cancelled.
- More than 24 Maintenance hours in any rolling 48-hour window per Resource requires Owner Password.
- Emergency-generated Maintenance counts toward the same threshold.
- A Venue Closure may overlap existing Maintenance, but creating Maintenance over an existing Closure is blocked for the overlap.

### Flow Boundary

Only behaviour supported by the cited approved requirements and Product Specification v5.0 is valid. Any additional branch or outcome requires Product Change Control.

---

## F-19 — Venue Emergency Closure

**Actors / Roles:** Venue Staff; System; Venue Owner/Admin notified  
**Trigger:** Immediate Venue emergency prevents service  
**Inputs:** Mandatory reason; NOW start; Expected End within current Operating Session  
**Outputs:** Venue Operational Selling Block; emergency record; scoped cancellation/interruption cascade  
**Requirement Coverage:** `FR-OPS-06` · `FR-OPS-07` · `FR-OPS-08` · `FR-OPS-09` · `FR-OPS-10` · `FR-OPS-44`

### Happy Path

1. Venue staff declares an Emergency Closure starting NOW with mandatory reason and expected end; no Owner Password is required.
2. System turns on the venue operational block and atomically applies the defined cascade to overlapping commitments.
3. PENDING/AWAITING/CONFIRMED and affected tournaments are cancelled; ACTIVE sessions are operationally interrupted under the emergency reason.
4. An AWAITING booking caught by the cascade uses the emergency reason immediately; CUSTOMER_RECONFIRM_TIMEOUT must not win.
5. Ending an emergency early never resurrects already-cancelled commitments.

### Alternate / Error Paths

- No Owner Password is required for declaration.
- Only reservations overlapping the declared interval enter the cascade.
- Future periods beyond the current Operating Session require normal Closure/Opening Hours mechanisms.
- Interrupted ACTIVE attendance uses Check-in/Late Present/no-evidence rules.

### Flow Boundary

Only behaviour supported by the cited approved requirements and Product Specification v5.0 is valid. Any additional branch or outcome requires Product Change Control.

---

## F-20 — Resource Emergency Outage

**Actors / Roles:** Venue Staff; System; Venue Owner/Admin notified  
**Trigger:** One Resource becomes genuinely unusable  
**Inputs:** Affected Resource; mandatory reason; NOW start; Expected End/return time  
**Outputs:** Resource Operational Selling Block; scoped Resource emergency cascade  
**Requirement Coverage:** `FR-OPS-11` · `FR-OPS-12` · `FR-OPS-13` · `FR-OPS-14` · `FR-OPS-20` · `FR-OPS-21`

### Happy Path

1. Venue staff declares an outage on one resource, with mandatory reason and expected return time; no Owner Password is required for the emergency declaration.
2. Only the affected resource is blocked; other resources continue normally.
3. Affected commitments follow the frozen operational cascade, including ACTIVE interruption and tournament resource handling.
4. Emergency-generated maintenance and later conversion/end behavior follow the resource-emergency lifecycle; repeated tournament cancellations may raise the non-punitive admin risk alert.

### Alternate / Error Paths

- Other Resources remain operational; no Venue-level block is created.
- Declaration requires no Owner Password.
- At emergency end the rolling threshold is evaluated before returning the Resource to sale.
- Conversion may only use valid target flows and may not bypass protected reservations.

### Flow Boundary

Only behaviour supported by the cited approved requirements and Product Specification v5.0 is valid. Any additional branch or outcome requires Product Change Control.

---

## F-21 — Emergency timer pause, resume and cap

**Actors / Roles:** System; Customer; Venue Staff  
**Trigger:** Relevant emergency interrupts a running timer  
**Inputs:** PENDING response timer or AWAITING Late Confirm timer plus active emergency/selling block  
**Outputs:** Paused/resumed timer history or cancellation at deterministic cap/feasibility outcome  
**Requirement Coverage:** `FR-OPS-34` · `FR-OPS-35` · `FR-OPS-36` · `FR-OPS-37` · `FR-OPS-38` · `FR-OPS-39` · `FR-OPS-40` · `FR-OPS-41`

### Happy Path

1. Relevant venue/resource emergencies pause the PENDING response timer or AWAITING Late Confirm countdown only under the frozen affected-scope rules.
2. Remaining duration is preserved; repeated pauses accumulate rather than reset.
3. Venue-response and Late-Confirm pauses each have their own 30-minute cumulative cap.
4. On cap, the Booking cancels under the specific interrupted-by-emergency reason and releases reservation/hold and commitment with no venue blame or customer penalty.
5. Response timer resumes only after all five response-resumption conditions hold; Late Confirm resumes only while the booking remains before startAt and all parties/resources remain eligible.
6. The optimistic feasibility test may cancel a paused response early only when no valid future intake window can fit the remaining response duration even assuming immediate block resolution.
7. Customer may Decline a paused Late Confirm immediately; pause history is immutable.

### Alternate / Error Paths

- Venue- and Resource-emergency pauses share one cumulative Venue-response counter; changing type never resets it.
- Venue-response and Late-Confirm pause counters are separate and each cap at 30 cumulative minutes.
- Response timer resumes only when all frozen session/account/marketplace/intake conditions pass.
- Optimistic feasibility may cancel early if remaining response duration cannot fit any future intake window even assuming immediate emergency resolution.
- Customer may Decline Late Confirm while paused.

### Flow Boundary

Only behaviour supported by the cited approved requirements and Product Specification v5.0 is valid. Any additional branch or outcome requires Product Change Control.

---

## F-22 — OWNER_REVIEW_REQUIRED lifecycle

**Actors / Roles:** System; Venue Owner; Platform Admin; affected Customers/Participants  
**Trigger:** Threshold evaluation requires owner review  
**Inputs:** Resource emergency ending with rolling Maintenance threshold exceeded  
**Outputs:** OWNER_REVIEW_REQUIRED restriction; atomic cascade; owner-resolution outcome  
**Requirement Coverage:** `FR-OPS-22` · `FR-OPS-23` · `FR-OPS-24` · `FR-OPS-25` · `FR-OPS-26` · `FR-OPS-27` · `FR-OPS-28` · `FR-OPS-29` · `FR-OPS-30` · `FR-OPS-31` · `FR-OPS-32` · `FR-OPS-33`

### Happy Path

1. When the rolling maintenance threshold logic requires owner review at emergency resolution, the resource enters OWNER_REVIEW_REQUIRED rather than silently returning to sale.
2. System applies the owner-review cascade atomically to affected commitments; it is a system operational consequence and needs no Owner Password to cancel them.
3. The restriction is not a pause state and blocks new sellable/commit flows and Late Confirm on the resource.
4. Resolution itself requires Owner Password and offers Return to Service, Extend Maintenance, Disable or Archive under the frozen prerequisites.

### Alternate / Error Paths

- Emergency may end while the separate restriction remains active.
- PENDING/AWAITING/future CONFIRMED/ACTIVE/manual/pre-start tournament commitments cascade under RESOURCE_OWNER_REVIEW_REQUIRED.
- VENUE_RESPONSE_EXPIRED is not in the reservation-release cascade but Late Confirm is blocked until restriction clears.
- Resolution requires Owner Password: Return to Service, Extend Maintenance, Disable, or Archive.

### Flow Boundary

Only behaviour supported by the cited approved requirements and Product Specification v5.0 is valid. Any additional branch or outcome requires Product Change Control.

---

## F-23 — Venue Account Disable

**Actors / Roles:** Platform Admin; System; Venue Staff/Customer  
**Trigger:** Platform Admin disables Venue Account  
**Inputs:** Venue Account disable/enable action  
**Outputs:** Access state change and state-specific booking/tournament consequences  
**Requirement Coverage:** `FR-ATT-11` · `FR-ATT-12` · `FR-ATT-13` · `FR-TRN-29` · `FR-TRN-30` · `FR-ADM-03`

### Happy Path

1. Platform Admin disables a Venue Account without changing Marketplace Status or deleting venue data.
2. Dashboard operational actions stop. PENDING response is interrupted into VENUE_RESPONSE_EXPIRED with a non-terminal account-disable event and no venue blame; AWAITING cancels under VENUE_ACCOUNT_DISABLED.
3. Existing CONFIRMED and ACTIVE commitments are not cancelled solely by Account Disable; their operational lifecycle continues where deliverable.
4. A Tournament still PENDING_VENUE_APPROVAL cancels immediately under tournament reason VENUE_ACCOUNT_DISABLED and releases approval holds and creator pending slot.
5. Re-enable restores Dashboard capability prospectively; it does not resurrect cancelled commitments or notify customers by itself.

### Alternate / Error Paths

- PENDING becomes VENUE_RESPONSE_EXPIRED with VENUE_RESPONSE_INTERRUPTED_BY_ACCOUNT_DISABLE event and no venue blame.
- AWAITING_CUSTOMER_CONFIRMATION cancels with VENUE_ACCOUNT_DISABLED.
- PENDING_VENUE_APPROVAL tournament cancels immediately, releases holds/creator slot and has no retry cooldown.
- CONFIRMED/ACTIVE bookings and WAITING/CONFIRMED/ACTIVE tournaments are not cancelled solely because of account disable.
- Re-enable alone does not resurrect cancelled records or require a Customer notification.

### Flow Boundary

Only behaviour supported by the cited approved requirements and Product Specification v5.0 is valid. Any additional branch or outcome requires Product Change Control.

---

## F-24 — Platform Attendance Protection

**Actors / Roles:** System; Platform Admin; Customer  
**Trigger:** Venue Account Disable overlaps an ACTIVE Booking  
**Inputs:** ACTIVE Booking; account-access restriction intervals; existing attendance evidence  
**Outputs:** Protected attendance resolution and canonical Trust effect when predicate qualifies  
**Requirement Coverage:** `FR-ATT-14` · `FR-ATT-15` · `FR-ATT-16` · `FR-ATT-17` · `FR-ATT-18` · `FR-ATT-19` · `FR-ATT-20` · `FR-ATT-21` · `FR-ATT-22` · `FR-ATT-23` · `FR-ATT-24` · `FR-ATT-25` · `FR-ATT-26`

### Happy Path

1. When Account Disable overlaps an ACTIVE Booking, the system records access-restriction intervals and evaluates protection only when effective attendance is resolved.
2. Protection may apply to normal completion or eligible early operational termination where the platform restriction materially prevented reliable attendance recording and no authoritative disqualifying outcome exists.
3. The protection Trust event has effectiveAt = booking.startAt, occurredAt = attendance-resolution time and at most one effective event per Booking.
4. Protection resets the consecutive no-show counter but never creates attendance, points or Verified Player progress and does not alter Booking Status/Attendance Result.
5. Later Admin attendance correction deterministically supersedes/neutralizes the protection effect where appropriate.

### Alternate / Error Paths

- Pre-disable authoritative Check-in/Late-Present evidence remains authoritative.
- Protection applies only when restricted intervals materially prevented reliable attendance recording under the frozen predicate.
- No invented attendance evidence or reward is created.
- At most one protection reset per Booking; if final ATTENDED/ATTENDED_INTERRUPTED exists, the separate protection event is not additionally applied.
- Later Admin correction can neutralize/replace the protection effect.

### Flow Boundary

Only behaviour supported by the cited approved requirements and Product Specification v5.0 is valid. Any additional branch or outcome requires Product Change Control.

---

## F-25 — Player-hosted Tournament

**Actors / Roles:** Verified Player Creator; Venue Staff; System  
**Trigger:** Verified Player creates Player-hosted Tournament at least 2h before start  
**Inputs:** Venue; Activity; 1–4 Resources; Date/Start/Duration; visibility; capacity; PS mode where applicable; tournament details  
**Outputs:** PENDING_VENUE_APPROVAL with temporary Approval Holds, then approved/rejected/expired lifecycle  
**Requirement Coverage:** `FR-TRN-01` · `FR-TRN-02` · `FR-TRN-03` · `FR-TRN-04` · `FR-TRN-05` · `FR-TRN-06` · `FR-TRN-07` · `FR-TRN-08` · `FR-TRN-09` · `FR-TRN-10` · `FR-TRN-33`

### Happy Path

1. Only an operationally VERIFIED player may create a player-hosted tournament, at least two hours before start and subject to the one pending approval request platform-wide rule.
2. Creator selects activity, duration, 1–4 eligible resources and public/private participation settings under the frozen rules.
3. System creates PENDING_VENUE_APPROVAL plus temporary approval holds; the tournament is not public/joinable yet.
4. Venue approval/rejection uses the frozen approval window and revalidation.
5. On approval it becomes WAITING_FOR_PLAYERS and uses Tournament Holds; on rejection/expiry it terminates under the proper reason and releases holds.
6. Once participation is open, the shared participant lifecycle applies: BAN-only ineligibility may reserve one pre-start capacity slot, while account deletion overrides BAN and releases any such slot permanently.

### Alternate / Error Paths

- Creator must be operational VERIFIED and not banned.
- Only one PENDING_VENUE_APPROVAL per creator platform-wide.
- Venue approval deadline is MIN(requestCreatedAt + 10m, checkpointAt).
- Reject/approval expiry releases holds and normally imposes 5-minute same-venue/time retry cooldown.
- Venue Account Disable during pending approval cancels with no cooldown and no venue blame.

### Flow Boundary

Only behaviour supported by the cited approved requirements and Product Specification v5.0 is valid. Any additional branch or outcome requires Product Change Control.

---

## F-26 — Venue-hosted Tournament

**Actors / Roles:** Venue Staff; System  
**Trigger:** Venue creates its own Tournament  
**Inputs:** Venue-hosted Tournament parameters and eligible Resources  
**Outputs:** WAITING_FOR_PLAYERS Tournament with Tournament Holds  
**Requirement Coverage:** `FR-TRN-01` · `FR-TRN-11` · `FR-TRN-12` · `FR-TRN-13` · `FR-TRN-26`

### Happy Path

1. Eligible Venue creates a tournament directly as WAITING_FOR_PLAYERS; Verified Player creator qualification does not apply.
2. Resources are validated/held using the canonical tournament resource rules.
3. Public/private and participant rules then follow the shared tournament lifecycle.
4. Join-capacity checks count JOINED participants plus any still-valid pre-start `INELIGIBLE/BAN` reserved slots; BAN-only ineligible participants do not count toward the checkpoint minimum.

### Alternate / Error Paths

- No Verified Player requirement and no venue-approval step.
- Existing reservations always win; Tournament creation never cancels an existing Booking.
- Parameters and operating-session/resource rules are the same frozen Tournament constraints where applicable.
- A temporary Ban uses `INELIGIBLE/BAN` and preserves one pre-start capacity slot; account deletion uses `INELIGIBLE/ACCOUNT_DELETED`, frees any such slot immediately and can never auto-restore.

### Flow Boundary

Only behaviour supported by the cited approved requirements and Product Specification v5.0 is valid. Any additional branch or outcome requires Product Change Control.

---

## F-27 — Tournament checkpoint

**Actors / Roles:** System; Creator/Participants/Venue notified  
**Trigger:** Mandatory checkpoint instant  
**Inputs:** WAITING_FOR_PLAYERS Tournament at startAt - 1h; active participant count; resource/venue state  
**Outputs:** CONFIRMED or CANCELLED with released holds  
**Requirement Coverage:** `FR-TRN-13` · `FR-TRN-14` · `FR-TRN-15` · `FR-TRN-16` · `FR-TRN-26`

### Happy Path

1. At exactly T-1 hour, System resolves any BAN-only restoration that was already effective at the checkpoint boundary, then counts Active Participants and revalidates the tournament/resources under Existing-Commitment Operational eligibility.
2. BAN-only `INELIGIBLE` participants whose Ban remains effective at the checkpoint are excluded from the minimum even though their pre-start capacity slot is still reserved; `ACCOUNT_DELETED` participants reserve no slot.
3. At least 8 active participants moves WAITING_FOR_PLAYERS to CONFIRMED.
4. Fewer than 8 cancels under MINIMUM_PARTICIPANTS_NOT_REACHED and releases resource holds.
5. After a successful checkpoint, later participant count falling below 8 does not auto-cancel the tournament.

### Alternate / Error Paths

- >=8 active participants confirms; <8 cancels MINIMUM_PARTICIPANTS_NOT_REACHED.
- Marketplace INACTIVE/SUSPENDED alone does not fail existing-tournament checkpoint.
- Venue selling block/emergency/closure or resource emergency/owner review cancels under the applicable operational reason.
- After CONFIRMED, later participant count below 8 does not auto-cancel.
- `BAN → ACCOUNT_DELETED` releases the temporary reserved slot immediately and later Ban expiry/lift cannot restore the Participant.
- A BAN-only Participant may auto-restore only before `startAt`; at/after Start the reservation right ends and later Ban expiry/lift does not rejoin them.

### Flow Boundary

Only behaviour supported by the cited approved requirements and Product Specification v5.0 is valid. Any additional branch or outcome requires Product Change Control.

---

## F-28 — Tournament start and completion

**Actors / Roles:** System; Tournament Creator/Participants  
**Trigger:** Tournament start/end timers  
**Inputs:** CONFIRMED Tournament at startAt; ACTIVE Tournament at endAt  
**Outputs:** ACTIVE then COMPLETED, or pre-start operational cancellation  
**Requirement Coverage:** `FR-TRN-17` · `FR-TRN-18` · `FR-TRN-19`

### Happy Path

1. At startAt, a CONFIRMED tournament that remains operationally eligible becomes ACTIVE automatically.
2. At endAt, an ACTIVE tournament completes unless already operationally cancelled.
3. Tournament venue cost/revenue uses stored informational values and is recognized only on COMPLETED under the frozen rule.

### Alternate / Error Paths

- Every assigned Resource is revalidated before start.
- Any required Resource under OWNER_REVIEW_REQUIRED before gameplay cancels the entire Tournament.
- Completed Tournament contributes stored venue cost to revenue on businessDate; cancelled/rejected tournaments do not.

### Flow Boundary

Only behaviour supported by the cited approved requirements and Product Specification v5.0 is valid. Any additional branch or outcome requires Product Change Control.

---

## F-29 — Tournament operational interruption/resource loss

**Actors / Roles:** System; Venue Staff/Owner for operational termination; Creator/Participants  
**Trigger:** Assigned Resource is lost or serious Tournament-specific incident occurs  
**Inputs:** ACTIVE or pre-start Tournament plus Resource/operational incident  
**Outputs:** Resource removal, continued Tournament, or CANCELLED under exactly one reason  
**Requirement Coverage:** `FR-TRN-20` · `FR-TRN-21` · `FR-TRN-22` · `FR-TRN-23` · `FR-TRN-31` · `FR-TRN-32`

### Happy Path

1. Operational resource loss while ACTIVE removes only the affected resource first.
2. If one or more operational assigned resources remain, the Tournament stays ACTIVE and participants are notified of resource availability change.
3. If zero operational resources remain, the Tournament is cancelled under the applicable frozen operational reason.
4. Normal venue Operational Termination is distinct, Owner-Password protected and uses TOURNAMENT_OPERATIONAL_TERMINATION.
5. Exactly one terminal Tournament reason is selected under the canonical priority.

### Alternate / Error Paths

- Before start, loss of any assigned Resource through Resource Emergency/Owner Review cancels the entire Tournament.
- While ACTIVE, if at least one operational assigned Resource remains, remove failed Resource and continue with notification.
- If none remain, cancel under applicable resource reason.
- Tournament Operational Termination is a distinct Owner-Password-protected venue action with mandatory reason.

### Flow Boundary

Only behaviour supported by the cited approved requirements and Product Specification v5.0 is valid. Any additional branch or outcome requires Product Change Control.

---

## F-30 — Trust replay

**Actors / Roles:** System; Platform Admin as correction source  
**Trigger:** Trust state is queried or new/corrected event is processed  
**Inputs:** Immutable Trust Input Events  
**Outputs:** Derived current/as-of counter, stage, ban qualification, Verified Player qualification/status effects  
**Requirement Coverage:** `FR-TRS-01` · `FR-TRS-02` · `FR-TRS-03` · `FR-TRS-04` · `FR-TRS-05` · `FR-TRS-06` · `FR-TRS-07`

### Happy Path

1. System stores immutable Trust input events and orders replay by effectiveAt ASC then eventSequence ASC.
2. Derived counter, penalty stage, ban qualification and Verified Player qualification/status effects are recalculated from corrected history rather than treated as authoritative counters.
3. As-of replay must reproduce the state at a requested historical point.
4. Only the canonical outcome/correction/protection event types affect Trust; raw operational evidence does not directly reward or penalize.

### Alternate / Error Paths

- Replay order is effectiveAt ASC then eventSequence ASC only.
- Raw Check-in/Late-Present evidence does not directly reward/penalize; only canonical outcomes/corrections/protection do.
- Corrections neutralize/substitute; they never delete source events.

### Flow Boundary

Only behaviour supported by the cited approved requirements and Product Specification v5.0 is valid. Any additional branch or outcome requires Product Change Control.

---

## F-31 — Attendance correction

**Actors / Roles:** Platform Admin; System; Customer notified when materially affected  
**Trigger:** Admin corrects wrong attendance/no-show evidence  
**Inputs:** Booking/Trust event; mandatory reason; correctedOutcome  
**Outputs:** Append-only correction; replayed Trust/points effects  
**Requirement Coverage:** `FR-TRS-08` · `FR-TRS-09` · `FR-TRS-10` · `FR-TRS-11` · `FR-TRS-12`

### Happy Path

1. Platform Admin records an attendance correction with reason and corrected canonical outcome.
2. Correction is compensating/append-only and becomes the authoritative latest non-superseded outcome for Trust replay.
3. Prior events remain visible in history and rewards/penalties are recalculated without compounding.
4. Corrections may reverse or create downstream Ban/Verified Player effects according to replay, but never resurrect released inventory.

### Alternate / Error Paths

- Latest non-superseded correction by eventSequence is authoritative.
- Sequential corrections produce one effective outcome only.
- Points corrections use compensating entries and historical value rules.
- Trust correction may alter current ban/Verified Player state but never rewinds external inventory history or resurrects old bookings.

### Flow Boundary

Only behaviour supported by the cited approved requirements and Product Specification v5.0 is valid. Any additional branch or outcome requires Product Change Control.

---

## F-32 — Ban qualification, activation, extension and expiry

**Actors / Roles:** System; Customer; Platform Admin for lift/correction  
**Trigger:** No-show counter reaches current stage threshold or correction changes qualification  
**Inputs:** Canonical Trust replay and qualifying no-show events  
**Outputs:** Counter/stage updates; Ban Instance activation/extension/expiry; cancellation cascade  
**Requirement Coverage:** `FR-TRS-13` · `FR-TRS-14` · `FR-TRS-15` · `FR-TRS-16` · `FR-TRS-17` · `FR-TRS-18` · `FR-TRS-19` · `FR-TRS-20` · `FR-TRS-21` · `FR-TRS-22` · `FR-TRS-23`

### Happy Path

1. Eligible no-shows increment the consecutive counter and reset events reset it according to the canonical Trust catalogue.
2. When a stage threshold is reached, a Ban Qualification exists at the Trust position and an operational Ban Instance activates now; retroactive qualification never backdates operational punishment.
3. Activation applies the booking/tournament cascade defined for a banned customer and resets the no-show counter.
4. Only one ACTIVE Ban Instance may exist; another qualification while active advances stage and extends the same instance to MAX(currentEndsAt, now + newStageDuration), with BAN_EXTENDED audit.
5. At endsAt the Ban expires unless lifted/corrected earlier; historical qualification and operational-ban as-of queries remain distinct.

### Alternate / Error Paths

- Ladder is 3→7d, then 2→1mo, then 2→2mo, then recurring 3→2mo.
- Retroactively discovered qualification activates a Ban now, never in the past.
- At most one ACTIVE Ban Instance; new qualification while banned extends the same instance and advances stage.
- Ban cascade executes once per instance; ACTIVE Booking continues.
- Invalid Ban correction ends restrictions but does not resurrect previously cancelled bookings.

### Flow Boundary

Only behaviour supported by the cited approved requirements and Product Specification v5.0 is valid. Any additional branch or outcome requires Product Change Control.

---

## F-33 — Verified Player qualification, revoke and restore

**Actors / Roles:** System; Customer; Platform Admin  
**Trigger:** 10th qualifying attended completion, Trust correction, or Admin moderation  
**Inputs:** Corrected Trust history; admin Verified Player actions  
**Outputs:** Qualification and Operational Verified Player Status transitions with transition IDs  
**Requirement Coverage:** `FR-ACC-11` · `FR-ACC-12` · `FR-ACC-13` · `FR-ACC-14` · `FR-ACC-15` · `FR-ACC-18` · `FR-TRS-24` · `FR-TRS-25` · `FR-TRS-26`

### Happy Path

1. Qualification is derived from corrected Trust history after 10 qualifying completed attended bookings.
2. First qualification creates an operational transition to VERIFIED at processing time, never backdated.
3. Loss of qualification through Trust correction moves operational status from VERIFIED to REVOKED and triggers the defined pending/waiting tournament cascade.
4. If REVOKED solely because of TRUST_CORRECTION and qualification is later regained, System automatically restores VERIFIED with a new transition ID.
5. Admin SUSPENDED/REVOKED cannot be automatically overridden; Admin Restore requires current qualification to return VERIFIED.

### Alternate / Error Paths

- Manual bookings and tournament participation do not contribute.
- Retroactive first qualification activates operational status now, not historically.
- Trust-correction-only revoke may auto-restore to VERIFIED when qualification returns.
- Admin SUSPENDED/REVOKED are protected from automatic regain.

### Flow Boundary

Only behaviour supported by the cited approved requirements and Product Specification v5.0 is valid. Any additional branch or outcome requires Product Change Control.

---

## F-34 — Points earning and reversal

**Actors / Roles:** System; Platform Admin  
**Trigger:** Qualifying ATTENDED completion or points correction  
**Inputs:** Qualifying attendance outcome or correction/adjustment  
**Outputs:** Append-only Points transaction and non-negative balance  
**Requirement Coverage:** `FR-PTS-01` · `FR-PTS-02` · `FR-PTS-03` · `FR-PTS-04` · `FR-PTS-05`

### Happy Path

1. Qualifying completed attended bookings grant the configured historical points amount once; non-qualifying outcomes grant zero.
2. Points use an append-only ledger and balance never falls below zero.
3. Reversal/adjustment records requested deduction, applied deduction and unrecovered amount; unrecovered amount is audit information, not future debt.

### Alternate / Error Paths

- ATTENDED grants configured historical points once; interrupted/unconfirmed/manual/no-show outcomes do not.
- Reversal uses compensating transaction; balance floor is 0.
- Unrecovered deduction amount is audit information, not future debt.

### Flow Boundary

Only behaviour supported by the cited approved requirements and Product Specification v5.0 is valid. Any additional branch or outcome requires Product Change Control.

---

## F-35 — Referral reward and invalidation

**Actors / Roles:** Referrer Customer; Referred Customer; System; Platform Admin  
**Trigger:** Referred user completes qualifying verification  
**Inputs:** Referral code; referred signup; phone OTP verification; normalized phone/email identity  
**Outputs:** REWARDED or INVALID referral plus Points transactions  
**Requirement Coverage:** `FR-PTS-06` · `FR-PTS-07` · `FR-PTS-08` · `FR-PTS-09`

### Happy Path

1. Referral qualifies only under the frozen OTP/signup rule and may reward once per referred user.
2. Reward configuration is read at OTP verification and the historical applied value is stored.
3. Self-referral is hard-blocked by normalized phone/email; device/IP are fraud signals only in MVP.
4. Invalidation changes referral state to INVALID and writes an exact compensating points transaction subject to the zero-floor rule.

### Alternate / Error Paths

- One reward per referred user.
- Self-referral by normalized phone or email is hard-blocked.
- Device/IP are fraud signals only, not automatic invalidation rules.
- Invalidation posts compensating points subject to zero floor.

### Flow Boundary

Only behaviour supported by the cited approved requirements and Product Specification v5.0 is valid. Any additional branch or outcome requires Product Change Control.

---

## F-36 — Review creation and moderation

**Actors / Roles:** Account-Verified Customer; Platform Admin; Venue Staff view-only  
**Trigger:** Customer creates/edits/deletes Review or Admin moderates  
**Inputs:** Venue; 1–5 rating; comment; current Review state  
**Outputs:** ACTIVE/HIDDEN/PENDING_MODERATION/REMOVED Review and moderation history  
**Requirement Coverage:** `FR-REV-01` · `FR-REV-02` · `FR-REV-03` · `FR-REV-04` · `FR-REV-05` · `FR-REV-06` · `FR-REV-07` · `FR-REV-08` · `FR-REV-09` · `FR-REV-10` · `FR-REV-11`

### Happy Path

1. Any Account-Verified user may review a venue without a completed booking, one current logical review per venue/user.
2. Normal eligible review starts ACTIVE except the 30-day post-admin-removal re-entry flow, which starts PENDING_MODERATION and requires Admin approval.
3. Admin may hide, restore hidden, remove or approve pending reviews under the frozen state rules; REMOVED is terminal and cannot be restored.
4. Public display uses current Username and only public states contribute to public content/rating under the frozen rule.
5. Venue may view reviews but has no moderation authority.

### Alternate / Error Paths

- Completed Booking is not required.
- Only one current logical Review per user/venue.
- After Admin removal, 30-day cooldown applies; next Review starts PENDING_MODERATION and never auto-publishes.
- Editing HIDDEN keeps it HIDDEN.
- REMOVED is terminal and cannot be restored.
- Venue has view-only review access.

### Flow Boundary

Only behaviour supported by the cited approved requirements and Product Specification v5.0 is valid. Any additional branch or outcome requires Product Change Control.

---

## F-37 — Platform Admin correction and marketplace control

**Actors / Roles:** Platform Admin; Support/Operations; System  
**Trigger:** Admin performs an approved platform action  
**Inputs:** Admin action plus mandatory reason where required  
**Outputs:** Marketplace/configuration/moderation/correction change with audit and required notifications  
**Requirement Coverage:** `FR-ADM-01` · `FR-ADM-02` · `FR-ADM-03` · `FR-ADM-04` · `FR-ADM-05` · `FR-ADM-06` · `FR-ADM-07` · `FR-ADM-08` · `FR-ADM-09` · `FR-ADM-10` · `FR-ADM-11` · `FR-AUD-01` · `FR-AUD-02` · `FR-AUD-03`

### Happy Path

1. Platform Admin performs onboarding, status/account control, moderation, global booking/tournament inspection, trust remediation, points/referral controls and global configuration only through the frozen Admin surfaces/actions.
2. Admin actions that materially alter customer state are audited and notified according to the canonical catalogues.
3. Historical snapshots and append-only histories remain immutable; corrections are explicit compensating actions, not silent rewrites.
4. Global configuration changes affect future events only unless the Product specification explicitly states another behavior.

### Alternate / Error Paths

- Only frozen Admin capabilities are available; no hidden support booking-creation path exists.
- Historical snapshots and append-only histories are preserved; corrections are explicit.
- Global configuration changes affect future events only unless frozen Product rules say otherwise.

### Flow Boundary

Only behaviour supported by the cited approved requirements and Product Specification v5.0 is valid. Any additional branch or outcome requires Product Change Control.

---

# 38. FLOW PACK COMPLETENESS

- FRS end-to-end flows represented: **37 / 37**
- These flows cover Customer Mobile App, Venue Dashboard, Platform Admin, system timers, Trust and operational cascades.
- Detailed validation, business-rule and error catalogues are maintained in their separate companion files.

---

## F-38 — Customer initiates Account Deletion

**Requirement Coverage:** `FR-ACC-10`

### Happy Path

1. Authenticated Customer opens Account/Profile settings and selects **Delete Account**.
2. Customer confirms the request.
3. System creates one durable Account Deletion Request linked to the User ID and shows that the request has been received.
4. The Customer initiation does not delete retained history.
5. Support / Platform Admin later completes the existing deletion/anonymisation process and links the completion to the request.
6. Completion disables login, anonymises/removes PII where appropriate, preserves required history, removes Reviews from public display and applies Tournament `ACCOUNT_DELETED` consequences.

### Alternate / Error Paths

- If an unresolved request already exists, the same request remains authoritative; no duplicate deletion operation is created.
- An already anonymised/deleted account cannot create a new request.
- No Customer-side hard-delete bypasses the completion process.

---

## F-39 — Customer reports a Review

**Requirement Coverage:** `FR-REV-11`

### Happy Path

1. Signed-in Customer views a visible Review and selects **Report**.
2. Customer provides a mandatory reason and optional details.
3. System creates a durable Review Report linked to the Review and reporter.
4. The Review remains in its current state and continues to follow the normal Review state/rating rules.
5. Platform Admin sees the report in the Review moderation surface and resolves it with a resolution note.
6. If the Admin also performs Hide/Remove/Restore/Approve, that remains the existing audited moderation flow and the report may reference the moderation event.

### Alternate / Error Paths

- A Review that is no longer visible at submission time is not accepted as a new Customer report.
- Reporting never grants the Venue moderation authority.
- Reporting alone never hides or removes the Review.

