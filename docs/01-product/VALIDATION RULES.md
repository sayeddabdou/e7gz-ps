# E7GZ PS
# VALIDATION RULES v1.0 — FUNCTIONAL BASELINE

**Status:** Derived functional baseline  
**Authority:** Product Specification v5.0 — FROZEN BASELINE → PRD v1.1 — APPROVED FOR BUILD → FRS v1.1 — APPROVED FOR BUILD  
**Market:** Egypt — initial launch in Cairo  
**Timezone:** Africa/Cairo  
**Currency:** EGP

> **No Product change.** This document is a derived working view of the approved baselines. It does not create new states, transitions, reason codes, timers, authorization rules, validation predicates, Trust semantics, financial rules, or scope. If anything here conflicts with Product Specification v5.0, v5.0 wins.

---

# 0. PURPOSE

This file isolates validation and eligibility rules from the approved FRS so they can be implemented and tested without turning validation into a new Product-design layer.

**Validation outcome rule:** where the frozen source defines an explicit state transition, event, error token or message, that exact outcome applies. Otherwise the attempted action is refused and authoritative state/history remain unchanged.

---

# 1. CROSS-CUTTING VALIDATION PRINCIPLES

- **VR-X-001 — Atomic revalidation:** Every commitment-strengthening action revalidates immediately before commit.
- **VR-X-002 — Race rule:** The first successfully committed valid transaction wins; later competing actions revalidate and fail if no longer valid.
- **VR-X-003 — Deadline rule:** `now < deadline` is required for an action allowed “before” a deadline; expiry owns `now >= deadline`.
- **VR-X-004 — Resource eligibility:** A Resource is eligible only when `baseState = ACTIVE`, `operationalRestriction = NONE`, and all relevant selling-block / emergency / maintenance / closure / reservation checks pass.
- **VR-X-005 — Inventory rule:** A displayed slot is not a commit guarantee. Every action that obtains/strengthens a Resource commitment must revalidate authoritative inventory.
- **VR-X-006 — Closed-enum rule:** Unknown Product-visible status/reason/event/detailCode values are invalid; adding one requires Product Change Control.
- **VR-X-007 — Authorization rule:** A protected action is invalid without the actor/Owner Password/Admin authority defined by v5.0 §54.
- **VR-X-008 — Historical rule:** Validation may not “fix” a current action by rewriting historical snapshots or past occupancy.

---

# 2. CORE INPUT VALIDATION

## 2.1 Registration / Identity
- Full Name, Username, Phone, Email and Password are required at registration.
- Username and Phone are unique; Username comparison is trimmed and case-insensitive.
- Customer password: at least 8 characters, with at least one letter and at least one digit. Applies wherever a Customer sets a password (registration, reset, change). Product Owner decision, 30 Sep 2026 (E7-13).
- Phone marketplace verification uses SMS OTP: 5-minute validity, max 5 attempts/code, 60-second resend delay, max 5 sends/hour/destination; new OTP invalidates previous OTP.
- Username change is blocked inside the 30-day cooldown.
- Phone/Email change becomes authoritative only after verification of the new value.
- Delete Account initiation requires an authenticated Customer and creates/reuses one unresolved deletion request; it never hard-deletes retained history.

## 2.2 Online Booking
- Customer must be Account Verified, not operationally banned, below the 4 counted-commitment limit, and free of conflicting online commitment.
- Start must satisfy the frozen lead-time and intake/session rules.
- Duration is 1–6 hours in 30-minute increments and must fit one valid Operating Session.
- PlayStation mode is required where the frozen flow requires Single/Multiplayer selection.
- Resource/Venue/Area/account/operational gates and canonical availability must all pass at commit.

## 2.3 Late Confirmation
- Source Booking must be `VENUE_RESPONSE_EXPIRED`, before original `startAt`, and still eligible for Existing-Commitment management.
- Initiation validates commitment count and Resource before creating the temporary Hold.
- Customer Accept revalidates commitment count, schedule conflict, reservation validity, ban/account and operational eligibility.

## 2.4 Manual Booking
- Venue Account must be ENABLED; Marketplace may be ACTIVE/INACTIVE/SUSPENDED but not ONBOARDING.
- Resource must be operationally eligible and conflict-free.
- Start is NOW or FUTURE, never historical.
- Duration is 1–6 hours in 30-minute increments.
- Required pricing configuration must exist; otherwise `MANUAL_BOOKING_PRICING_CONFIGURATION_MISSING` is returned and no Booking/reservation is created.

## 2.5 Tournament
- Capacity is 8–32; Resources 1–4; duration minimum 1 hour in 30-minute increments; creation is at least 2 hours before Start.
- Player-hosted creation requires operational VERIFIED creator and Venue approval; Venue-hosted does not.
- Existing reservations always win.
- Join/leave requires `now < startAt`; join also requires capacity and participant eligibility.

## 2.6 Reviews
- Reviewer must be Account Verified.
- Review contains rating 1–5 and a comment.
- One current logical Review per User/Venue.
- After Admin removal, 30-day cooldown must elapse before a new PENDING_MODERATION Review may be submitted.
- A signed-in Customer may report a public Review visible to them; the report reason is mandatory, and report creation does not mutate Review state.

---

# 3. SOURCE-DERIVED VALIDATION / ELIGIBILITY CATALOGUE

## 3.1 Accounts, Identity and Verification

### VR-ACC-02 — Source `FR-ACC-02`

**Actor / Surface:** Customer / Customer Mobile App  
**Governing v5.0:** §7

**Validation rule:** An account becomes **Account Verified** only when `phone_verified = true` via SMS OTP. Email OTP alone must never activate an account for marketplace actions.

### VR-ACC-03 — Source `FR-ACC-03`

**Actor / Surface:** Customer, Venue Staff / Customer Mobile App  
**Governing v5.0:** §7

**Validation rule:** An unverified user may browse and view venues, and may not create a booking, join or create a tournament, or write a review.

### VR-ACC-04 — Source `FR-ACC-04`

**Actor / Surface:** Customer / Customer Mobile App  
**Governing v5.0:** §7

**Validation rule:** OTP rules: valid 5 minutes · max 5 attempts per code · resend after 60 seconds · max 5 sends per hour per destination · a new OTP invalidates the previous one.

### VR-ACC-10 — Source `FR-ACC-10`

**Actor / Surface:** Customer, Platform Admin / Customer Mobile App, Platform Admin  
**Governing v5.0:** §7.7, §53.5

**Validation rule:** An authenticated Customer may initiate Account Deletion from inside the Customer App. At most one unresolved deletion request exists for the User; a repeated initiation reuses that request. Initiation cannot hard-delete retained history. Completion is the existing audited Support / Platform Admin anonymisation path and must link back to the request while applying the existing history-preservation, Review-removal and Tournament `ACCOUNT_DELETED` consequences.

### VR-ACC-17 — Source `FR-ACC-17`

**Actor / Surface:** Customer, Venue Staff / Customer Mobile App  
**Governing v5.0:** §9

**Validation rule:** A banned user may log in, browse, view venues, view history, view account standing and profile, cancel an existing future booking, decline a Late Confirmation, and write, edit or delete reviews. They may **not** create a booking, accept a Late Confirmation, join a tournament or create a tournament.

---

## 3.2 Discovery and Browsing

### VR-DIS-01 — Source `FR-DIS-01`

**Actor / Surface:** Customer, Venue Staff / Customer Mobile App  
**Governing v5.0:** §20.2

**Validation rule:** With location enabled and no manual area selection, show eligible venues across **active areas** sorted primarily by distance.

### VR-DIS-04 — Source `FR-DIS-04`

**Actor / Surface:** Customer, System / Customer Mobile App  
**Governing v5.0:** §16.5

**Validation rule:** **Nearest Availability** is the earliest valid start time for the currently selected activity at the **minimum booking duration of 1 hour**, computed from opening hours, eligible resources, the canonical availability predicate, the current time and the 15-minute lead-time rule. For PlayStation, before any room or console filter, use the earliest valid PlayStation resource.

### VR-DIS-05 — Source `FR-DIS-05`

**Actor / Surface:** Customer, Venue Staff / Customer Mobile App  
**Governing v5.0:** §16.5, §43.7

**Validation rule:** Resources that are DISABLED, ARCHIVED, under `OWNER_REVIEW_REQUIRED`, or under an operational selling block at venue or resource level must **never** contribute to Nearest Availability or appear as available.

### VR-DIS-06 — Source `FR-DIS-06`

**Actor / Surface:** Customer, Venue Staff / Customer Mobile App  
**Governing v5.0:** §20.7

**Validation rule:** A currently closed venue remains discoverable. The customer may view the venue, activities, future availability, prices and Nearest Availability, and sees state such as *Closed Now* and *Opens at 12:00 PM*. They may not submit a request until the next valid online intake window.

### VR-DIS-10 — Source `FR-DIS-10`

**Actor / Surface:** Customer, Venue Staff / Customer Mobile App  
**Governing v5.0:** §20.4, §2.1

**Validation rule:** **Map view** shows venue markers positioned by coordinates under the same eligibility rules as the list. A marker preview contains venue name, distance, starting price, Nearest Availability, average rating, review count and a **View Venue** action.

---

## 3.3 Availability and Inventory

### VR-AVL-06 — Source `FR-AVL-06`

**Actor / Surface:** System / System / cross-cutting  
**Governing v5.0:** §13.3, §16.2, §43

**Validation rule:** A resource requires **both** `baseState = ACTIVE` **and** `operationalRestriction = NONE` to be eligible for any flow.

### VR-AVL-07 — Source `FR-AVL-07`

**Actor / Surface:** Venue Staff, System / System / cross-cutting  
**Governing v5.0:** §38.4

**Validation rule:** Every creation and commitment predicate requires `Operational Selling Block = FALSE` for the affected venue **and** resource. Interval-overlap checks alone are never sufficient.

### VR-AVL-08 — Source `FR-AVL-08`

**Actor / Surface:** System / System / cross-cutting  
**Governing v5.0:** §16.4

**Validation rule:** **Double booking is never allowed.** Two active reservations must never overlap on one resource.

### VR-AVL-09 — Source `FR-AVL-09`

**Actor / Surface:** Venue Staff, System / System / cross-cutting  
**Governing v5.0:** §31.9, §31.10

**Validation rule:** Eligibility is revalidated immediately before **every** commitment-strengthening transition — creation, Venue Accept, Late Confirm initiation, Late Confirm Accept, manual booking creation, tournament creation, tournament approval, tournament checkpoint confirmation, booking CONFIRMED → ACTIVE, tournament CONFIRMED → ACTIVE, and resource or hold assignment, **and before any future transition whose effect is to obtain or strengthen a resource or venue commitment**. No lifecycle boundary is exempt from operational eligibility.

### VR-AVL-10 — Source `FR-AVL-10`

**Actor / Surface:** System / System / cross-cutting  
**Governing v5.0:** §31.11

**Validation rule:** Revalidation and the state transition commit in **one atomic transaction**. The first successfully committed valid transition wins; a state change between validation and commit causes revalidation or failure.

### VR-AVL-11 — Source `FR-AVL-11`

**Actor / Surface:** System / System / cross-cutting  
**Governing v5.0:** §31.12

**Validation rule:** Displayed availability is a snapshot, not a commit guarantee. An action is offered only when the latest known state satisfies its predicate; execution always revalidates atomically and may fail.

### VR-AVL-12 — Source `FR-AVL-12`

**Actor / Surface:** System / System / cross-cutting  
**Governing v5.0:** §17.2

**Validation rule:** **Historical planned intervals may overlap; active reservation occupancy may not.** A no-show releases the remainder of an interval, so a later valid booking may start inside the original planned window.

### VR-AVL-15 — Source `FR-AVL-15`

**Actor / Surface:** Customer, Venue Staff, System / System / cross-cutting  
**Governing v5.0:** §17.6

**Validation rule:** Canonical timer boundary: an action allowed "before" a deadline requires `now < deadline`; at `now >= deadline` the expiry owns the state. This applies to the venue response window, Late Confirmation, customer cancellation at start, tournament join and leave at start, and every equivalent boundary.

---

## 3.4 Online Booking Lifecycle

### VR-BOK-02 — Source `FR-BOK-02`

**Actor / Surface:** System / Customer Mobile App, Venue Dashboard  
**Governing v5.0:** §19

**Validation rule:** A new online booking requires `now <= startAt − 15 minutes`. Exactly 15 minutes is permitted; less is rejected.

### VR-BOK-04 — Source `FR-BOK-04`

**Actor / Surface:** Customer / Customer Mobile App, Venue Dashboard  
**Governing v5.0:** §19.2

**Validation rule:** A customer may not hold **overlapping** online commitments. The 4-booking limit is a total-count limit **in addition to** the no-overlap rule.

### VR-BOK-05 — Source `FR-BOK-05`

**Actor / Surface:** System / Customer Mobile App, Venue Dashboard  
**Governing v5.0:** §14.5

**Validation rule:** The booking interval must fit entirely inside **one** future operating session. It may cross midnight where the session does. It may not cross a closure, a closed gap, or the end of one session into another.

### VR-BOK-10 — Source `FR-BOK-10`

**Actor / Surface:** System / Customer Mobile App, Venue Dashboard  
**Governing v5.0:** §21

**Validation rule:** Being open at submission does not make every future slot valid; the requested future interval is validated independently.

### VR-BOK-11 — Source `FR-BOK-11`

**Actor / Surface:** Customer, Venue Staff / Customer Mobile App, Venue Dashboard  
**Governing v5.0:** §22

**Validation rule:** Creation requires **all** of: account verified by phone OTP · customer not banned · customer below the 4-commitment limit · no overlapping customer commitment · venue marketplace **ACTIVE** · venue account **ENABLED** · area **ACTIVE** · venue inside an operating session · at least 10 minutes before session end · no venue operational selling block · no resource operational selling block · requested start at least 15 minutes ahead · duration 1–6 hours in 30-minute increments · interval fits one future operating session · resource base state **ACTIVE** · resource operational restriction **NONE** · no overlapping blocking reservation · activity/resource compatibility · **all other required product validation** (§22 item 20). The twenty conditions above are cumulative and are the complete canonical list. Note: the explicit *"required pricing configuration exists"* condition belongs to the **manual** booking predicate (§30.2 item 15, FR-MAN-02) and is **not** one of §22's twenty. Where a venue's pricing configuration for the activity is absent, online creation fails under §22 item 20. Stating it as an explicit twenty-first condition of §22 would be a Product Change Request against v5.0 §22, not a PRD edit; the Product Owner has ruled that **no twenty-first condition is added**.

### VR-BOK-14 — Source `FR-BOK-14`

**Actor / Surface:** Customer, Venue Staff / Customer Mobile App, Venue Dashboard  
**Governing v5.0:** §23.2

**Validation rule:** Venue Accept revalidates atomically: venue existing-commitment eligibility, venue selling block, resource base state, resource operational restriction, resource selling block, reservation validity, interval validity, and **always** customer account and ban eligibility.

### VR-BOK-15 — Source `FR-BOK-15`

**Actor / Surface:** Venue Staff / Customer Mobile App, Venue Dashboard  
**Governing v5.0:** §35.2

**Validation rule:** Venue Accept uses **existing-commitment** eligibility (category B) and therefore does **not** require marketplace ACTIVE. A venue that became INACTIVE or SUSPENDED after the request may still accept it.

### VR-BOK-16 — Source `FR-BOK-16`

**Actor / Surface:** Venue Staff / Customer Mobile App, Venue Dashboard  
**Governing v5.0:** §23.3

**Validation rule:** If Accept fails because the venue or resource became unavailable through a venue emergency, a resource emergency or owner review, the booking may **not** become CONFIRMED; it is CANCELLED under the applicable operational reason, and **no venue non-response blame is applied**.

### VR-BOK-20 — Source `FR-BOK-20`

**Actor / Surface:** Customer, Venue Staff, System / Customer Mobile App, Venue Dashboard  
**Governing v5.0:** §18.3, §24.1

**Validation rule:** A VENUE_RESPONSE_EXPIRED booking is displayed under Cancelled with *"Venue did not respond in time"*, while remaining internally eligible for Late Confirmation until `startAt`. There is no pre-emptive customer opt-out, and no automatic reactivation.

### VR-BOK-22 — Source `FR-BOK-22`

**Actor / Surface:** Customer, Venue Staff, System / Customer Mobile App, Venue Dashboard  
**Governing v5.0:** §24.4

**Validation rule:** Resource Disable, Archive and Console Change may proceed while a booking is only VENUE_RESPONSE_EXPIRED, since no reservation is held. The system records `LATE_CONFIRM_ELIGIBILITY_LOST_RESOURCE_STATE_CHANGED` with the matching `detailCode`; no additional customer push is required.

### VR-BOK-23 — Source `FR-BOK-23`

**Actor / Surface:** Venue Staff / Customer Mobile App, Venue Dashboard  
**Governing v5.0:** §24.3

**Validation rule:** A booking still VENUE_RESPONSE_EXPIRED at its original `startAt` becomes CANCELLED, with the reason chosen by this priority: **1.** `RESOURCE_OWNER_REVIEW_REQUIRED` · **2.** `RESOURCE_EMERGENCY_OUTAGE` · **3.** `VENUE_EMERGENCY_CLOSURE` · **4.** `VENUE_ACCOUNT_DISABLED` · **5.** `VENUE_DID_NOT_RESPOND_IN_TIME`. The last applies only when no overriding operational or platform cause exists.

### VR-BOK-25 — Source `FR-BOK-25`

**Actor / Surface:** Venue Staff, System / Customer Mobile App, Venue Dashboard  
**Governing v5.0:** §31.2

**Validation rule:** Immediately before CONFIRMED → ACTIVE the system revalidates venue selling block, resource base state, resource operational restriction, resource selling block, and applicable closure or emergency conditions — atomically with the transition.

---

## 3.5 Late Confirmation

### VR-LCF-02 — Source `FR-LCF-02`

**Actor / Surface:** Customer, Venue Staff / Customer Mobile App, Venue Dashboard  
**Governing v5.0:** §25.2

**Validation rule:** Venue eligibility: marketplace status **ACTIVE, INACTIVE or SUSPENDED** · venue account **ENABLED** · area **ACTIVE** · venue currently inside a valid operating session · no venue operational selling block · the original interval still fits valid opening hours · original start still in the future.

### VR-LCF-03 — Source `FR-LCF-03`

**Actor / Surface:** Customer / Customer Mobile App, Venue Dashboard  
**Governing v5.0:** §25.3

**Validation rule:** Resource eligibility: base state **ACTIVE** · operational restriction **NONE** · no resource operational selling block · the interval still passes the canonical conflict predicate.

### VR-LCF-04 — Source `FR-LCF-04`

**Actor / Surface:** Customer, Venue Staff / Customer Mobile App, Venue Dashboard  
**Governing v5.0:** §25.4

**Validation rule:** Customer eligibility at initiation: not banned · below the 4-commitment limit. If the customer is already at 4, initiation is **blocked**, the booking stays VENUE_RESPONSE_EXPIRED, and **no temporary hold is created**.

### VR-LCF-05 — Source `FR-LCF-05`

**Actor / Surface:** Customer, Venue Staff / Customer Mobile App, Venue Dashboard  
**Governing v5.0:** §25.4

**Validation rule:** When initiation is blocked by the limit, the venue sees a neutral message — *"Customer is not currently eligible for Late Confirmation"* — and **never** learns about the customer's other bookings. The internal event is `LATE_CONFIRM_FAILED_BOOKING_LIMIT`.

### VR-LCF-06 — Source `FR-LCF-06`

**Actor / Surface:** Customer, Venue Staff / Customer Mobile App, Venue Dashboard  
**Governing v5.0:** §25.5

**Validation rule:** Initiation is **one atomic transaction**: read commitment count · validate the limit · validate the resource · create the temporary hold · transition VENUE_RESPONSE_EXPIRED → AWAITING_CUSTOMER_CONFIRMATION. Concurrent initiations can never produce a temporary fifth commitment.

### VR-LCF-09 — Source `FR-LCF-09`

**Actor / Surface:** Customer / Customer Mobile App, Venue Dashboard  
**Governing v5.0:** §25.8

**Validation rule:** `lateConfirmExpiresAt = MIN(now + 5 minutes, booking.startAt)`. The window must never extend beyond the original start time.

### VR-LCF-14 — Source `FR-LCF-14`

**Actor / Surface:** Customer, Venue Staff / Customer Mobile App, Venue Dashboard  
**Governing v5.0:** §25.11

**Validation rule:** Accept revalidates: commitment count still valid · no customer schedule conflict · resource reservation still valid · ban and account eligibility · venue and resource operational eligibility including selling block.

### VR-LCF-15 — Source `FR-LCF-15`

**Actor / Surface:** Customer, Venue Staff / Customer Mobile App, Venue Dashboard  
**Governing v5.0:** §25.12

**Validation rule:** For privacy, the customer schedule conflict is checked **only when the customer attempts to accept**. The venue is never told the customer has another booking and may freely send the request.

### VR-LCF-17 — Source `FR-LCF-17`

**Actor / Surface:** Customer / Customer Mobile App, Venue Dashboard  
**Governing v5.0:** §25.13

**Validation rule:** Resource unavailability during Late Confirm is expressed as validation failure, atomic transaction failure, or `LATE_CONFIRM_FAILED_RESOURCE_CONFLICT`. There is **no** terminal "late confirm resource unavailable" reason.

---

## 3.6 Attendance, Check-in and No-show

### VR-ATT-02 — Source `FR-ATT-02`

**Actor / Surface:** System / Customer Mobile App, Venue Dashboard  
**Governing v5.0:** §27.2

**Validation rule:** Check-in window: `startAt <= now < startAt + 15 minutes`. At exactly `+15 minutes` check-in is refused.

### VR-ATT-03 — Source `FR-ATT-03`

**Actor / Surface:** Venue Owner authorization / Customer Mobile App, Venue Dashboard  
**Governing v5.0:** §27.3

**Validation rule:** **Mark Present — Late** is available while `startAt + 15 min <= now < endAt`, only when no valid check-in exists and no no-show has been committed. It blocks no-show, grants no points, grants no Verified Player progress, and does **not** reset the counter. It requires no Owner Password and is audited.

### VR-ATT-04 — Source `FR-ATT-04`

**Actor / Surface:** Customer, Venue Owner authorization / Customer Mobile App, Venue Dashboard  
**Governing v5.0:** §27.4, §27.5

**Validation rule:** **No-show** is available while `startAt + 15 min <= now < endAt`, only when no valid check-in and no Mark Present — Late exist. It requires no Owner Password and is audited. A customer marked Late Present can never afterwards be marked no-show for the same booking.

### VR-ATT-06 — Source `FR-ATT-06`

**Actor / Surface:** Customer, Venue Staff, Platform Admin / Customer Mobile App, Venue Dashboard, Platform Admin  
**Governing v5.0:** §27.7

**Validation rule:** A committed no-show is terminal and **venue staff cannot undo it**. Reversal requires Platform Admin. Reversing a no-show never evicts a later customer who validly obtained the released slot.

### VR-ATT-08 — Source `FR-ATT-08`

**Actor / Surface:** System / Customer Mobile App, Venue Dashboard  
**Governing v5.0:** §28.2

**Validation rule:** Default generation: COMPLETED after valid check-in → ATTENDED · COMPLETED without verified attendance and without no-show → ATTENDANCE_UNCONFIRMED · status NO_SHOW → ABSENT_NO_SHOW · valid check-in then emergency interruption → ATTENDED_INTERRUPTED · manual booking → NOT_APPLICABLE · cancelled or rejected before attendance → NOT_APPLICABLE · interruption with Late Present only → ATTENDANCE_UNCONFIRMED · interruption with no evidence → NOT_APPLICABLE. These are defaults, not permanent formulas.

### VR-ATT-09 — Source `FR-ATT-09`

**Actor / Surface:** System / Customer Mobile App, Venue Dashboard  
**Governing v5.0:** §28.3

**Validation rule:** After audited correction the following are intentionally valid: COMPLETED with ABSENT_NO_SHOW · CANCELLED with ATTENDED_INTERRUPTED · CANCELLED with ATTENDED. An `ATTENDANCE_CORRECTED` may replace the result **without** changing an already-terminal status.

### VR-ATT-11 — Source `FR-ATT-11`

**Actor / Surface:** Venue Staff, Platform Admin / Customer Mobile App, Venue Dashboard, Platform Admin  
**Governing v5.0:** §27.8

**Validation rule:** Platform Admin may reverse a check-in, reverse a Mark Present — Late, reverse a no-show, and issue an attendance correction. Each requires a mandatory reason and is audited. **Venue staff can reverse none of them.**

### VR-ATT-12 — Source `FR-ATT-12`

**Actor / Surface:** Platform Admin / Customer Mobile App, Venue Dashboard, Platform Admin  
**Governing v5.0:** §27.8

**Validation rule:** Reversal semantics: check-in reversed while ACTIVE keeps the booking ACTIVE and re-opens no-show eligibility within the window · check-in reversed after an ATTENDED completion creates `ATTENDANCE_CORRECTED` and reverses points by compensating transaction · Late Present reversed before `endAt` re-opens no-show eligibility · no-show reversed before original `endAt` leaves the booking CANCELLED under `ADMIN_NO_SHOW_REVERSAL` · no-show reversed after original `endAt` yields COMPLETED / ATTENDANCE_UNCONFIRMED unless attendance is separately established.

### VR-ATT-16 — Source `FR-ATT-16`

**Actor / Surface:** System / Customer Mobile App, Venue Dashboard  
**Governing v5.0:** §32.6

**Validation rule:** Protection applies only when **all** hold: the booking was ACTIVE · the account became DISABLED during the session · the disable materially prevented reliable attendance recording · no authoritative check-in existed beforehand · no authoritative no-show existed beforehand · the final effective outcome is **not** ATTENDED, ATTENDED_INTERRUPTED or ABSENT_NO_SHOW. Eligible outcomes are ATTENDANCE_UNCONFIRMED and NOT_APPLICABLE from operational early termination.

### VR-ATT-23 — Source `FR-ATT-23`

**Actor / Surface:** System / Customer Mobile App, Venue Dashboard  
**Governing v5.0:** §32.10

**Validation rule:** Later evidence governs: a valid check-in after re-enable within the window → normal ATTENDED · a Late Present after re-enable → ATTENDANCE_UNCONFIRMED with the protection reset still applicable · a valid no-show after re-enable within the window → authoritative. **An expired check-in window is never retroactively reopened.**

### VR-ATT-24 — Source `FR-ATT-24`

**Actor / Surface:** Customer, Platform Admin / Customer Mobile App, Venue Dashboard, Platform Admin  
**Governing v5.0:** §32.11

**Validation rule:** An admin correction to ABSENT_NO_SHOW **neutralises** the protection reset. No customer ever receives both a protected reset and a valid no-show increment for the same effective outcome.

### VR-ATT-28 — Source `FR-ATT-28`

**Actor / Surface:** Venue Staff, Platform Admin, System / Customer Mobile App, Venue Dashboard, Platform Admin  
**Governing v5.0:** §27.10

**Validation rule:** Monitoring may flag, alert and surface for review. It must **never** automatically revoke Verified Player status, suspend a venue, reverse check-ins or ban users. Every consequence requires explicit admin action.

---

## 3.7 Manual Booking

### VR-MAN-02 — Source `FR-MAN-02`

**Actor / Surface:** Venue Staff / Venue Dashboard  
**Governing v5.0:** §30.2

**Validation rule:** Creation requires: venue account **ENABLED** · marketplace **ACTIVE, INACTIVE or SUSPENDED** — **ONBOARDING is explicitly not allowed** · resource base state ACTIVE · operational restriction NONE · no venue or resource selling block · resource supports the activity · no blocking reservation · no overlap with closure, emergency closure, resource emergency outage or maintenance · start NOW or FUTURE · duration 1–6 hours in 30-minute increments · required pricing configuration present.

### VR-MAN-10 — Source `FR-MAN-10`

**Actor / Surface:** Venue Staff / Venue Dashboard  
**Governing v5.0:** §30.2

**Validation rule:** Missing pricing configuration blocks creation with the API/validation error `MANUAL_BOOKING_PRICING_CONFIGURATION_MISSING`. No reservation and no booking record are created, so the token is neither a reason code nor a booking event.

---

## 3.8 Venue Model, Configuration and Dashboard

### VR-VEN-02 — Source `FR-VEN-02`

**Actor / Surface:** Venue Staff, Venue Owner authorization / Venue Dashboard, Platform Admin  
**Governing v5.0:** §54.3

**Validation rule:** The Owner Password is required **per protected action**. There is no 15-minute unlock, no 30-minute unlock and no owner session mode; each action revalidates.

### VR-VEN-05 — Source `FR-VEN-05`

**Actor / Surface:** Venue Staff, Venue Owner authorization, Platform Admin / Venue Dashboard, Platform Admin  
**Governing v5.0:** §54.3

**Validation rule:** Successful and failed Owner Password attempts are audited; validation is rate-limited. A Platform Admin reset notifies the venue.

### VR-VEN-46 — Source `FR-VEN-46`

**Actor / Surface:** Venue Staff, Venue Owner authorization, Platform Admin / Venue Dashboard, Platform Admin  
**Governing v5.0:** §10.2

**Validation rule:** The venue may **change its own Owner Password**. The new password becomes active **immediately**, the old one stops working at once, and every subsequent protected action validates against the new one. The change is audited. This is distinct from a Platform Admin reset, which is covered by FR-ADM-03.

### VR-VEN-47 — Source `FR-VEN-47`

**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Governing v5.0:** §12, §51.11

**Validation rule:** An area may be deactivated **only** when no venue assigned to it has marketplace status ACTIVE or SUSPENDED. Such venues must first move area or become INACTIVE. Historical references are unchanged.

### VR-VEN-14 — Source `FR-VEN-14`

**Actor / Surface:** Venue Staff, System / Venue Dashboard, Platform Admin  
**Governing v5.0:** §13.4

**Validation rule:** Disable, Archive and Console Change are **blocked** by any current or future blocking reservation — CONFIRMED, ACTIVE, PENDING, AWAITING, Player Tournament Approval Hold, Tournament Hold or resource Maintenance. Existing reservations are **never** automatically cancelled; the venue must resolve them first. A normal venue closure alone does not block these actions, and `VENUE_RESPONSE_EXPIRED` does not block them.

### VR-VEN-15 — Source `FR-VEN-15`

**Actor / Surface:** Venue Staff, Venue Owner authorization / Venue Dashboard, Platform Admin  
**Governing v5.0:** §13.5

**Validation rule:** A console change (PS4 ↔ PS5) requires the Owner Password and **clears all existing room price overrides**; the room returns to venue default pricing for the new console. Historical bookings retain their stored console, mode and price.

### VR-VEN-42 — Source `FR-VEN-42`

**Actor / Surface:** Venue Staff, System / Venue Dashboard, Platform Admin  
**Governing v5.0:** §3.3

**Validation rule:** PlayStation requires a play mode of **Single** or **Multiplayer**. This is a **pricing mode, not an exact player count**, and the system must never validate, capture or enforce a party size against it. Play mode does **not** apply to Billiards or Ping Pong.

### VR-VEN-45 — Source `FR-VEN-45`

**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Governing v5.0:** §4.1, §4.2, §30.2

**Validation rule:** Manual booking, online booking and tournament all price from this same configuration, and creation is **blocked** where the configuration required for the activity does not exist.

### VR-VEN-18 — Source `FR-VEN-18`

**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Governing v5.0:** §14

**Validation rule:** Each **operating day** creates at most **one operating session**, identified by operating day, local start, local end and the Africa/Cairo timezone. Session length is greater than 0 and no more than 24 hours. Sessions may touch at boundaries but **may not overlap**.

### VR-VEN-19 — Source `FR-VEN-19`

**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Governing v5.0:** §14

**Validation rule:** Non-overlap validation is **cyclical across the full week**, including the Sunday → Monday wrap. A cross-midnight session may not overlap the next session.

### VR-VEN-21 — Source `FR-VEN-21`

**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Governing v5.0:** §14.4, §14.3

**Validation rule:** DST: non-existent local start times are not offered; ambiguous repeated local times are not offered as duplicate slots; a declared full-day session stays valid when real elapsed time is 23 or 25 hours; absolute instants remain authoritative for inventory.

### VR-VEN-23 — Source `FR-VEN-23`

**Actor / Surface:** Venue Staff, Venue Owner authorization / Venue Dashboard, Platform Admin  
**Governing v5.0:** §14.7

**Validation rule:** An opening-hours change is **all-or-nothing** and evaluates in this order: **1.** validate proposed sessions · **2.** check tournament conflicts · **3.** block immediately on any tournament conflict · **4.** compute affected ordinary reservations · **5.** show a complete impact preview · **6.** determine the Owner Password requirement · **7.** request the Owner Password where protected cancellations exist · **8.** commit the change and all cancellations atomically · **9.** notify. If any step fails, **nothing changes**.

### VR-VEN-25 — Source `FR-VEN-25`

**Actor / Surface:** Venue Staff, Venue Owner authorization / Venue Dashboard, Platform Admin  
**Governing v5.0:** §14.9, §14.8

**Validation rule:** Editing opening hours needs no Owner Password, but a save that cancels a CONFIRMED online booking or an AWAITING booking requires it. Pending online and manual cancellations alone do not. Cancellation reason: `VENUE_OPENING_HOURS_CHANGED`.

### VR-VEN-26 — Source `FR-VEN-26`

**Actor / Surface:** Venue Staff, Venue Owner authorization / Venue Dashboard, Platform Admin  
**Governing v5.0:** §14.7

**Validation rule:** Before commit the complete affected set is **revalidated**. If new affected reservations appeared since the preview, the commit is **aborted** and a refreshed preview is shown; any Owner Password must be entered against the refreshed set. **No booking outside the approved preview may be silently cancelled.**

### VR-VEN-30 — Source `FR-VEN-30`

**Actor / Surface:** Customer, Venue Staff, Venue Owner authorization / Venue Dashboard, Platform Admin  
**Governing v5.0:** §15

**Validation rule:** Creating or editing a closure needs no Owner Password unless it cancels protected customer reservations, in which case the final **Save & Cancel Affected Bookings** requires it. Cancellation reason `VENUE_CLOSURE`; customers are notified.

### VR-VEN-31 — Source `FR-VEN-31`

**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Governing v5.0:** §15

**Validation rule:** A closure may **not** be created retroactively over an ACTIVE session, and must begin after existing active sessions end unless those have been resolved through an allowed flow.

### VR-VEN-33 — Source `FR-VEN-33`

**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Governing v5.0:** §39.4

**Validation rule:** A closure may overlap existing maintenance; creating maintenance over an existing closure is blocked for the overlapping period.

---

## 3.9 Maintenance, Emergencies and Operational Restrictions

### VR-OPS-02 — Source `FR-OPS-02`

**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Governing v5.0:** §39.1

**Validation rule:** Maintenance may **never** overlap an existing blocking reservation; creation is blocked with a clear message and the reservations must be resolved through their normal flow. **Maintenance may never silently cancel a booking.**

### VR-OPS-04 — Source `FR-OPS-04`

**Actor / Surface:** Venue Staff, Venue Owner authorization / Venue Dashboard, Platform Admin  
**Governing v5.0:** §39.2

**Validation rule:** An edit that extends a continuous period past the threshold requires the Owner Password.

### VR-OPS-06 — Source `FR-OPS-06`

**Actor / Surface:** Venue Staff, Venue Owner authorization, Platform Admin / Venue Dashboard, Platform Admin  
**Governing v5.0:** §40.1

**Validation rule:** Emergency Closure requires **no Owner Password**, may start only **NOW**, cannot be scheduled, requires a mandatory reason and full audit, and notifies the venue owner and Platform Admin.

### VR-OPS-11 — Source `FR-OPS-11`

**Actor / Surface:** Venue Staff, Venue Owner authorization, Platform Admin / Venue Dashboard, Platform Admin  
**Governing v5.0:** §41.2, §41.4, §39.2

**Validation rule:** A resource emergency outage requires **no Owner Password** and carries a mandatory reason, timestamp, affected resource, venue account identity, full audit, owner notification and Platform Admin visibility. Staff must supply an **expected return-to-service time**. For an **unprotected** emergency outage this may extend only to the end of the current operating session; a longer resource outage must be carried as **maintenance under normal maintenance authorization**, including the Owner Password where the rolling threshold is exceeded.

### VR-OPS-14 — Source `FR-OPS-14`

**Actor / Surface:** Venue Staff, Venue Owner authorization, Platform Admin / Venue Dashboard, Platform Admin  
**Governing v5.0:** §41.6

**Validation rule:** Platform Admin receives a risk alert when one venue causes **3 or more tournament cancellations through resource emergency outage within a rolling 30 days**. The alert is investigative and non-blocking; it never suspends the venue, requires no Owner Password for future emergencies, and reverses nothing.

### VR-OPS-19 — Source `FR-OPS-19`

**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Governing v5.0:** §42.3

**Validation rule:** On ENDED the emergency selling block **must** end. Staff may only end an emergency when the venue or resource is operational again, or when a valid replacement operational state or block commits atomically.

### VR-OPS-21 — Source `FR-OPS-21`

**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Governing v5.0:** §38.6

**Validation rule:** **No sellable gap**: there must never be a period where the emergency block has ended, the resource is still unusable, and no replacement restriction exists. The enforceable guarantee is that E7GZ will not release the block unless staff assert the resource is operational or a replacement commits. END EMERGENCY is that audited, attributable staff assertion.

### VR-OPS-22 — Source `FR-OPS-22`

**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Governing v5.0:** §42.4

**Validation rule:** Extending an emergency computes the newly affected interval and reservations, previews the impact, takes confirmation, and applies the new cancellations atomically. Previously cancelled reservations stay cancelled. Emergency mode may not be extended beyond the current operating session as an unprotected cancellation mechanism.

### VR-OPS-23 — Source `FR-OPS-23`

**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Governing v5.0:** §42.5

**Validation rule:** Conversion targets: venue emergency → normal closure or opening-hours change; resource emergency → maintenance, disabled or archived. A **resource emergency may not convert directly into an opening-hours change**. Each conversion obeys the target action's own authorization and impact rules and never bypasses the resource state-change predicate.

### VR-OPS-43 — Source `FR-OPS-43`

**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Governing v5.0:** §42.6, §31.12

**Validation rule:** Where a resource cannot return to service but still holds **future protected reservations**, the operational selling block **remains** and the venue must resolve those commitments through the normal flows before Disable, Archive or extended Maintenance becomes available. The Dashboard must not present an unavailable conversion as executable: it displays the blocking commitments and states that they must be resolved first.

### VR-OPS-29 — Source `FR-OPS-29`

**Actor / Surface:** Customer, Venue Staff, Venue Owner authorization, Platform Admin, System / Venue Dashboard, Platform Admin  
**Governing v5.0:** §43.3, §43.4

**Validation rule:** The cascade requires **no Owner Password anywhere**, including the manual-booking and tournament cancellations, requires system audit, and notifies the venue owner, Platform Admin, affected customers and participants. **A partial cascade is not a valid outcome.**

### VR-OPS-32 — Source `FR-OPS-32`

**Actor / Surface:** Venue Staff, Venue Owner authorization, System / Venue Dashboard, Platform Admin  
**Governing v5.0:** §43.6

**Validation rule:** While the restriction holds, the venue cannot initiate Late Confirm on that resource; the system records `LATE_CONFIRM_ELIGIBILITY_LOST_RESOURCE_STATE_CHANGED` with `detailCode = RESOURCE_OWNER_REVIEW_REQUIRED`. Clearing the restriction before `startAt` restores eligibility, but never triggers an automatic Late Confirm.

### VR-OPS-33 — Source `FR-OPS-33`

**Actor / Surface:** Venue Staff, Venue Owner authorization / Venue Dashboard, Platform Admin  
**Governing v5.0:** §43.8

**Validation rule:** Resolution requires the Owner Password and offers **Return to Service**, **Extend Maintenance**, **Disable Resource** or **Archive Resource**. Return to Service requires no blocking maintenance, no emergency selling block and an otherwise operational resource; it then sets `operationalRestriction = NONE`.

### VR-OPS-39 — Source `FR-OPS-39`

**Actor / Surface:** Customer, Venue Staff / Venue Dashboard, Platform Admin  
**Governing v5.0:** §44.4

**Validation rule:** A paused venue response timer resumes only when **all** hold: the emergency has ended · venue account ENABLED · venue marketplace permits the action · the venue is inside a valid operating session · the current time is inside the online intake window. Ending an emergency while the venue is closed does not restart the timer; it waits for the **next** valid intake window. A paused Late Confirmation countdown resumes only when the affecting emergency or block is resolved, the booking is still before `startAt`, and venue, resource and customer all remain eligible.

### VR-OPS-40 — Source `FR-OPS-40`

**Actor / Surface:** Venue Staff, System / Venue Dashboard, Platform Admin  
**Governing v5.0:** §44.5

**Validation rule:** Early cancellation is deterministic through the **optimistic feasibility test**: assuming the current selling block could resolve immediately, could the remaining venue-response duration fit inside a valid future online intake window before `startAt`? The system evaluates the remaining response duration, future valid operating sessions, the final-10-minute intake cutoff, `startAt` and the active selling block. **No such window even under immediate resolution** → cancel now under the applicable interrupted-by-emergency reason. **A window could exist but the emergency is unresolved** → the booking stays paused only until the 30-minute cap, which is the deterministic outer bound. Block duration is never predicted by the scheduler.

---

## 3.10 Tournaments

### VR-TRN-04 — Source `FR-TRN-04`

**Actor / Surface:** System / Customer Mobile App, Venue Dashboard  
**Governing v5.0:** §36.3

**Validation rule:** A PlayStation tournament must select Single or Multiplayer, which drives resource pricing. The interval must fit **one** valid operating session, so a tournament may exceed the 6-hour online booking maximum.

### VR-TRN-08 — Source `FR-TRN-08`

**Actor / Surface:** System / Customer Mobile App, Venue Dashboard  
**Governing v5.0:** §36.7

**Validation rule:** **Existing reservations always win.** Tournament creation cannot override any existing booking, tournament, maintenance or closure, and **a tournament may never automatically cancel an existing booking**; creation fails for conflicting resources.

### VR-TRN-33 — Source `FR-TRN-33`

**Actor / Surface:** Venue Staff / Customer Mobile App, Venue Dashboard  
**Governing v5.0:** §36.6

**Validation rule:** At creation, **every** assigned resource requires base state **ACTIVE**, operational restriction **NONE**, no operational selling block at venue or resource level, no conflicting reservation or hold, and activity compatibility.

### VR-TRN-12 — Source `FR-TRN-12`

**Actor / Surface:** System / Customer Mobile App, Venue Dashboard  
**Governing v5.0:** §37.4

**Validation rule:** Users may join while WAITING_FOR_PLAYERS or CONFIRMED and `now < startAt`, with capacity remaining, not banned, and with valid invite access if private. Capacity remaining is evaluated against **JOINED + still-valid pre-start `INELIGIBLE/BAN` reserved slots**, not Active Participant count alone. Participants may leave, and organizers may remove, until `startAt`. Falling below 8 before the checkpoint does **not** cancel early.

### VR-TRN-13 — Source `FR-TRN-13`

**Actor / Surface:** System / Customer Mobile App, Venue Dashboard  
**Governing v5.0:** §37.4

**Validation rule:** An **Active Participant** is JOINED, not BANNED, not DELETED, and has neither left nor been removed. Participant states remain JOINED / LEFT / REMOVED / INELIGIBLE. `INELIGIBLE` requires the explicit cause `BAN` or `ACCOUNT_DELETED`; `ACCOUNT_DELETED` has precedence. A BAN-only ineligible Participant may reserve one pre-start capacity slot while contributing zero to Active Participant count; an account-deleted Participant contributes to neither. Historical participation and cause history are retained.

### VR-TRN-15 — Source `FR-TRN-15`

**Actor / Surface:** Venue Staff / Customer Mobile App, Venue Dashboard  
**Governing v5.0:** §37.5

**Validation rule:** The checkpoint revalidates every assigned resource — base state ACTIVE, restriction NONE, no selling block, hold still valid, venue eligible, tournament not otherwise cancelled.

### VR-TRN-16 — Source `FR-TRN-16`

**Actor / Surface:** Venue Staff / Customer Mobile App, Venue Dashboard  
**Governing v5.0:** §37.5

**Validation rule:** At the checkpoint "venue eligible" means **operationally able to honour the tournament**, not eligible for new marketplace activity. Marketplace SUSPENDED or INACTIVE therefore never fails a checkpoint by itself; only a venue selling block, a venue emergency affecting the tournament, or an applicable closure does. A tournament **never** remains indefinitely in WAITING_FOR_PLAYERS after a failed mandatory checkpoint.

### VR-TRN-17 — Source `FR-TRN-17`

**Actor / Surface:** Venue Owner authorization, System / Customer Mobile App, Venue Dashboard  
**Governing v5.0:** §37.6

**Validation rule:** CONFIRMED → ACTIVE at start, ACTIVE → COMPLETED at end. Before CONFIRMED → ACTIVE every resource is revalidated; any resource under owner review cancels the tournament **before gameplay begins**.

### VR-TRN-23 — Source `FR-TRN-23`

**Actor / Surface:** Venue Staff, Venue Owner authorization / Customer Mobile App, Venue Dashboard  
**Governing v5.0:** §37.9

**Validation rule:** Tournament Operational Termination covers a tournament-specific incident not requiring venue closure, requires the **Owner Password**, a mandatory reason, audit and participant and organizer notification, and yields ACTIVE → CANCELLED `TOURNAMENT_OPERATIONAL_TERMINATION`.

### VR-TRN-26 — Source `FR-TRN-26`

**Actor / Surface:** System / Customer Mobile App, Venue Dashboard  
**Governing v5.0:** §37.11

**Validation rule:** Ban and deletion are distinct `INELIGIBLE` causes. `BAN` removes the Participant from Active count but reserves one capacity slot only while the account is active, Tournament is non-terminal/pre-start and `now < startAt`; `ACCOUNT_DELETED` reserves none. Account deletion overrides BAN in the same logical operation and prevents all future automatic restoration. A BAN-only Participant must restore to JOINED if the Ban is no longer effective before `startAt`; if the Ban was already ineffective at the exact checkpoint/start boundary, that restoration is applied before the boundary decision even if execution is delayed. At/after `startAt`, Ban expiry/lift does not restore, and later changes never resurrect a Tournament already cancelled at the checkpoint.

### VR-TRN-29 — Source `FR-TRN-29`

**Actor / Surface:** Venue Staff / Customer Mobile App, Venue Dashboard  
**Governing v5.0:** §36.9, §36.11

**Validation rule:** Tournament venue cost, entry fee and prize are **informational only**. E7GZ collects nothing, holds nothing and distributes nothing, and never imposes a penalty, debt, wallet deduction or financial obligation on a creator who cannot continue.

### VR-TRN-32 — Source `FR-TRN-32`

**Actor / Surface:** Venue Staff, Venue Owner authorization, Platform Admin / Customer Mobile App, Venue Dashboard, Platform Admin  
**Governing v5.0:** §37.13, §55.7, §57.3

**Validation rule:** The closed **Tournament Reason Code** enum is `CREATOR_CANCELLED` · `VENUE_CANCELLED` · `ADMIN_CANCELLED` · `VENUE_REJECTED_TOURNAMENT` · `VENUE_DID_NOT_APPROVE_TOURNAMENT_IN_TIME` · `MINIMUM_PARTICIPANTS_NOT_REACHED` · `CREATOR_VERIFIED_STATUS_REVOKED` · `TOURNAMENT_OPERATIONAL_TERMINATION` · `EMERGENCY_CLOSURE` · `RESOURCE_EMERGENCY_OUTAGE` · `RESOURCE_OWNER_REVIEW_REQUIRED` · `VENUE_ACCOUNT_DISABLED`. Every terminal tournament carries exactly one, and a new member requires Product Change Control.

---

## 3.11 Trust, Penalties and Bans

### VR-TRS-14 — Source `FR-TRS-14`

**Actor / Surface:** System / Platform Admin  
**Governing v5.0:** §48.2

**Validation rule:** A penalty triggers when a new eligible no-show **increments the counter to the current threshold** — never merely because a stored number exceeds a later threshold.

### VR-TRS-21 — Source `FR-TRS-21`

**Actor / Surface:** System / Platform Admin  
**Governing v5.0:** §48.7

**Validation rule:** Each ban instance's cancellation cascade executes **once only**; repeated replay must never repeat it.

### VR-TRS-24 — Source `FR-TRS-24`

**Actor / Surface:** System / Platform Admin  
**Governing v5.0:** §47.3

**Validation rule:** Replay corrects **trust state** but **never rewinds external booking side-effects into the past**, so it cannot destroy valid later reservations or restore slots already sold.

### VR-TRS-25 — Source `FR-TRS-25`

**Actor / Surface:** System / Platform Admin  
**Governing v5.0:** §47.3

**Validation rule:** A ban proved invalid is CORRECTED/ENDED with restrictions stopped, the user notified and state recalculated — but **previously cancelled bookings do not return**.

### VR-TRS-26 — Source `FR-TRS-26`

**Actor / Surface:** Customer, Platform Admin, System / Platform Admin  
**Governing v5.0:** §47.4

**Validation rule:** There is **no Support Replacement Booking** and no special admin booking-creation path. All customer booking creation uses the same online predicate. Support may assist but cannot bypass validation.

---

## 3.12 Points, Referrals, Wallet and Fees

### VR-PTS-02 — Source `FR-PTS-02`

**Actor / Surface:** Customer, System / Customer Mobile App, Platform Admin  
**Governing v5.0:** §46.1

**Validation rule:** Points and trust are separate systems. Points are a reward currency; trust governs standing and eligibility.

### VR-PTS-06 — Source `FR-PTS-06`

**Actor / Surface:** Customer / Customer Mobile App, Platform Admin  
**Governing v5.0:** §46.4

**Validation rule:** A referral becomes eligible only on **successful account OTP verification**, not on record creation. Statuses: PENDING / REWARDED / INVALID. Only one reward per referred user. Self-referral is not allowed.

### VR-PTS-09 — Source `FR-PTS-09`

**Actor / Surface:** Customer / Customer Mobile App, Platform Admin  
**Governing v5.0:** §46.5

**Validation rule:** Invalidating a REWARDED referral sets it INVALID and creates a compensating transaction for the **exact historical reward**. A referral may become INVALID even when the full amount cannot be deducted; the ledger records historical reward, requested reversal, applied reversal and unrecovered amount.

---

## 3.13 Reviews

### VR-REV-05 — Source `FR-REV-05`

**Actor / Surface:** Customer, Platform Admin, System / Customer Mobile App, Venue Dashboard, Platform Admin  
**Governing v5.0:** §49.4

**Validation rule:** A normal eligible review is created **ACTIVE**, except in the moderation re-entry flow. There is **no auto-publish** of a pending review, however much time passes; admin reminders never change status automatically.

### VR-REV-07 — Source `FR-REV-07`

**Actor / Surface:** Customer, Venue Staff, Platform Admin / Customer Mobile App, Venue Dashboard, Platform Admin  
**Governing v5.0:** §49.6

**Validation rule:** After an admin removal the user may not review that venue for **30 days**; the next review starts PENDING_MODERATION. A further removal starts a new 30-day cooldown.

### VR-REV-08 — Source `FR-REV-08`

**Actor / Surface:** Customer, Venue Staff, Platform Admin / Customer Mobile App, Venue Dashboard, Platform Admin  
**Governing v5.0:** §49.7

**Validation rule:** The venue is **view only**. Platform Admin may hide, restore, remove, approve a pending review and remove a pending review; every action requires a reason and records action, reason, date, previous status and new status.

### VR-REV-11 — Source `FR-REV-11`

**Actor / Surface:** Customer, Platform Admin / Customer Mobile App, Platform Admin  
**Governing v5.0:** §49.10

**Validation rule:** The reporter must be a signed-in Customer and the Review must be visible to that Customer when the report is submitted. The reason must be non-empty; optional supporting details may be supplied. Report creation does not change Review status or rating contribution. Admin resolution requires an Admin actor and a non-empty resolution note; any linked moderation event must be a valid event for the same Review.

---

## 3.14 Notifications

### VR-NTF-07 — Source `FR-NTF-07`

**Actor / Surface:** System / Platform Admin  
**Governing v5.0:** §32.13

**Validation rule:** A notification must **never** state that a booking was cancelled unless a rule actually cancelled it.

### VR-NTF-08 — Source `FR-NTF-08`

**Actor / Surface:** Customer, Venue Staff, System / Platform Admin  
**Governing v5.0:** §32.13

**Validation rule:** There is **no** mandatory customer notification merely because a venue account is re-enabled; the customer is notified only when a customer-relevant action occurs.

---

## 3.15 Audit and Historical Integrity / Reporting

### VR-AUD-06 — Source `FR-AUD-06`

**Actor / Surface:** Venue Staff, Platform Admin, System / Platform Admin  
**Governing v5.0:** §53.3

**Validation rule:** Every audit record carries the actor identity (venue account, admin or system), a timestamp and, where the action requires one, the mandatory reason.

### VR-AUD-13 — Source `FR-AUD-13`

**Actor / Surface:** System / Platform Admin  
**Governing v5.0:** §33.1, §57.3

**Validation rule:** **Booking Reason Codes** and **Booking Events** are two **separate closed enums**. A reason code explains a **terminal** booking outcome; an event records an action or a **non-terminal** failure in append-only history. A token belongs to exactly one of the two. Adding a product-visible member to either requires Product Change Control.

### VR-AUD-14 — Source `FR-AUD-14`

**Actor / Surface:** Customer, Venue Staff, System / Platform Admin  
**Governing v5.0:** §33.4

**Validation rule:** The canonical **Booking Event** list is `CHECK_IN_RECORDED` · `CHECK_IN_REVERSED` · `LATE_PRESENT_RECORDED` · `LATE_PRESENT_REVERSED` · `NO_SHOW_RECORDED` · `NO_SHOW_REVERSED` · `ATTENDANCE_CORRECTED` · `LATE_CONFIRM_FAILED_RESOURCE_CONFLICT` · `LATE_CONFIRM_FAILED_CUSTOMER_CONFLICT` · `LATE_CONFIRM_FAILED_BOOKING_LIMIT` · `LATE_CONFIRM_ELIGIBILITY_LOST_RESOURCE_STATE_CHANGED` · `VENUE_RESPONSE_INTERRUPTED_BY_ACCOUNT_DISABLE`. **No API validation-only token belongs in this enum**, which is why `MANUAL_BOOKING_PRICING_CONFIGURATION_MISSING` is an error code only.

### VR-AUD-15 — Source `FR-AUD-15`

**Actor / Surface:** Customer, Venue Staff, System / Platform Admin  
**Governing v5.0:** §33.3

**Validation rule:** Five tokens are **deliberately removed** and must not be reintroduced: `BOOKING_EXPIRED` (use `VENUE_DID_NOT_RESPOND_IN_TIME`) · `RESOURCE_CONFLICT` (validation or atomic-commit failure, or `LATE_CONFIRM_FAILED_RESOURCE_CONFLICT`) · `CUSTOMER_SCHEDULE_CONFLICT` (`LATE_CONFIRM_FAILED_CUSTOMER_CONFLICT`) · `LATE_CONFIRM_RESOURCE_UNAVAILABLE` (as above) · `LATE_CONFIRM_ELIGIBILITY_LOST_RESOURCE_STATE_CHANGED` **as a terminal reason** (it is an event of the same name). Because none is terminal, none needs a fee treatment — which is what makes every reachable terminal reason map to exactly one treatment.

---

# COMPLETENESS

- FRS requirements explicitly classified `Validation/Eligibility`: **113**
- Additional core input rules above are direct frozen constraints needed for form/action validation.
