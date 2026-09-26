# E7GZ PS
# BUSINESS RULES v1.0 — FUNCTIONAL BASELINE

**Status:** Derived functional baseline  
**Authority:** Product Specification v5.0 — FROZEN BASELINE → PRD v1.1 — APPROVED FOR BUILD → FRS v1.1 — APPROVED FOR BUILD  
**Market:** Egypt — initial launch in Cairo  
**Timezone:** Africa/Cairo  
**Currency:** EGP

> **No Product change.** This document is a derived working view of the approved baselines. It does not create new states, transitions, reason codes, timers, authorization rules, validation predicates, Trust semantics, financial rules, or scope. If anything here conflicts with Product Specification v5.0, v5.0 wins.

---

# 0. RULE-CATALOGUE METHOD

This file is a **Business / Product Rule view** of the approved 357 Functional Requirements. It does not invent a second rule set: each `BR-*` entry preserves the approved requirement text and points back to its `FR-*` source.

The catalogue is intentionally comprehensive so Product, UX, Engineering and QA can reference rules independently of flow prose.

---

# 1. CROSS-CUTTING BUSINESS PRINCIPLES

- **BR-X-001 — One inventory:** Online Bookings, Manual Bookings and Tournaments use one canonical Resource availability truth.
- **BR-X-002 — No double booking:** Active reservation occupancy for one Resource may never overlap.
- **BR-X-003 — Truthful causality:** Customer, Venue, platform and operational causes must not be mislabeled as one another.
- **BR-X-004 — Trust is reconstructible:** Trust state is derived from immutable ordered events, not authoritative mutable counters.
- **BR-X-005 — No silent cancellation:** Configuration actions do not silently override protected commitments unless the frozen Product explicitly defines an operational cascade.
- **BR-X-006 — Historical immutability:** Historical prices, fees, rewards, attendance and operating snapshots are preserved; corrections are append-only / compensating.
- **BR-X-007 — Protected owner actions:** Owner Password is fresh per protected action; there is no owner-unlock session.
- **BR-X-008 — Emergencies are scoped:** Venue emergencies affect the Venue; Resource emergencies affect only the Resource; emergency logic may not be used as a bypass for future protected changes.
- **BR-X-009 — Deadline ownership:** Actions allowed “before” a deadline require `now < deadline`; at `now >= deadline`, expiry owns the state.
- **BR-X-010 — Atomic commitment:** Commitment-strengthening transitions revalidate immediately before commit; first successfully committed valid transition wins.

---

# 2. Accounts, Identity and Verification

## BR-ACC-001 — Source `FR-ACC-01`

**Priority:** M  
**Actor / Surface:** Customer, Platform Admin / Customer Mobile App, Platform Admin  
**Rule Type:** Authorization, Notification, UI/Presentation  
**Governing v5.0:** §7.1

**Rule:** Registration captures **Full Name**, **Username**, **Phone**, **Email** and **Password**, all required. Username and Phone are unique; Full Name and Email are not constrained to be unique. Public reviews display **username only**; Platform Admin may see both full name and username.

## BR-ACC-002 — Source `FR-ACC-02`

**Priority:** M  
**Actor / Surface:** Customer / Customer Mobile App  
**Rule Type:** State/Lifecycle, Validation/Eligibility, Authorization, Notification  
**Governing v5.0:** §7

**Rule:** An account becomes **Account Verified** only when `phone_verified = true` via SMS OTP. Email OTP alone must never activate an account for marketplace actions.

## BR-ACC-003 — Source `FR-ACC-03`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff / Customer Mobile App  
**Rule Type:** Validation/Eligibility, UI/Presentation  
**Governing v5.0:** §7

**Rule:** An unverified user may browse and view venues, and may not create a booking, join or create a tournament, or write a review.

## BR-ACC-004 — Source `FR-ACC-04`

**Priority:** M  
**Actor / Surface:** Customer / Customer Mobile App  
**Rule Type:** Validation/Eligibility, Timer/Scheduler  
**Governing v5.0:** §7

**Rule:** OTP rules: valid 5 minutes · max 5 attempts per code · resend after 60 seconds · max 5 sends per hour per destination · a new OTP invalidates the previous one.

## BR-ACC-005 — Source `FR-ACC-05`

**Priority:** M  
**Actor / Surface:** Customer / Customer Mobile App  
**Rule Type:** Notification  
**Governing v5.0:** §7

**Rule:** Email is mandatory registration data. Email verification may occur separately and supports ownership verification, account recovery and email notifications; an unverified email never blocks booking once the phone is verified.

## BR-ACC-006 — Source `FR-ACC-06`

**Priority:** M  
**Actor / Surface:** Customer / Customer Mobile App  
**Rule Type:** Functional rule  
**Governing v5.0:** §7

**Rule:** Username uniqueness is evaluated on a normalised form — trimmed and case-insensitive.

## BR-ACC-007 — Source `FR-ACC-07`

**Priority:** M  
**Actor / Surface:** Customer / Customer Mobile App  
**Rule Type:** Timer/Scheduler, Notification  
**Governing v5.0:** §7

**Rule:** Username is editable with a **30-day cooldown**; Full Name is editable freely. Phone and email changes each require verification of the new value.

## BR-ACC-008 — Source `FR-ACC-08`

**Priority:** M  
**Actor / Surface:** Customer / Customer Mobile App  
**Rule Type:** Audit/History, UI/Presentation  
**Governing v5.0:** §7, §49.2

**Rule:** Historical entities reference the immutable User ID. Reviews display the **current** username.

## BR-ACC-009 — Source `FR-ACC-09`

**Priority:** M  
**Actor / Surface:** Customer / Customer Mobile App  
**Rule Type:** Notification  
**Governing v5.0:** §7

**Rule:** Password reset uses verified phone OTP or email verification and does not require the old password.

## BR-ACC-010 — Source `FR-ACC-10`

**Priority:** M  
**Actor / Surface:** Customer, Platform Admin / Customer Mobile App, Platform Admin  
**Rule Type:** Authorization, Audit/History, UI/Presentation  
**Governing v5.0:** §53.5

**Rule:** The Customer Mobile App provides an in-app **Delete Account** action that initiates one durable Account Deletion Request. Support / Platform Admin completes the existing audited anonymisation process. Initiation never hard-deletes retained history; completion disables login, removes/anonymises PII where appropriate, preserves required booking/financial/audit history, removes Reviews from public display and applies the existing Tournament `ACCOUNT_DELETED` consequence.

## BR-ACC-011 — Source `FR-ACC-11`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff, System / Customer Mobile App  
**Rule Type:** State/Lifecycle, Trust/Enforcement, Audit/History, UI/Presentation  
**Governing v5.0:** §8.1, §8.2, §8.3

**Rule:** **Verified Player Qualification** is derived from corrected trust history — **10 completed attended bookings, all of which may be at the same venue** — and is displayed as progress, for example *"6 / 10 Completed Bookings"*. It is separate from **Operational Verified Player Status** (NOT_VERIFIED / VERIFIED / SUSPENDED / REVOKED). Only VERIFIED may create a player-hosted tournament.

## BR-ACC-012 — Source `FR-ACC-12`

**Priority:** M  
**Actor / Surface:** Customer / Customer Mobile App  
**Rule Type:** Trust/Enforcement, Audit/History  
**Governing v5.0:** §8

**Rule:** Every operational Verified Player transition creates an immutable `verifiedPlayerTransitionId`, including a retroactively discovered first qualification.

## BR-ACC-013 — Source `FR-ACC-13`

**Priority:** M  
**Actor / Surface:** Customer / Customer Mobile App  
**Rule Type:** State/Lifecycle  
**Governing v5.0:** §8

**Rule:** A retroactively discovered qualification activates operational status **now**, never back-dated.

## BR-ACC-014 — Source `FR-ACC-14`

**Priority:** M  
**Actor / Surface:** Customer, Platform Admin, System / Customer Mobile App, Platform Admin  
**Rule Type:** State/Lifecycle, Authorization, Trust/Enforcement  
**Governing v5.0:** §8

**Rule:** Where operational status was REVOKED **solely** by `TRUST_CORRECTION` and a later replay restores qualification, the system **must** transition REVOKED → VERIFIED automatically with a new transition ID. No admin action is required in this case.

## BR-ACC-015 — Source `FR-ACC-15`

**Priority:** M  
**Actor / Surface:** Customer, Platform Admin, System / Customer Mobile App, Platform Admin  
**Rule Type:** Authorization, Trust/Enforcement  
**Governing v5.0:** §8

**Rule:** Admin SUSPENDED and admin REVOKED are protected: automatic trust regain never overrides them. Admin Restore yields VERIFIED if currently qualified, otherwise NOT_VERIFIED.

## BR-ACC-016 — Source `FR-ACC-16`

**Priority:** M  
**Actor / Surface:** Customer / Customer Mobile App  
**Rule Type:** Trust/Enforcement  
**Governing v5.0:** §8, §30.6

**Rule:** Manual bookings and tournament participation contribute **nothing** to Verified Player progress.

## BR-ACC-017 — Source `FR-ACC-17`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff / Customer Mobile App  
**Rule Type:** Validation/Eligibility, Authorization, Trust/Enforcement, Audit/History, UI/Presentation  
**Governing v5.0:** §9

**Rule:** A banned user may log in, browse, view venues, view history, view account standing and profile, cancel an existing future booking, decline a Late Confirmation, and write, edit or delete reviews. They may **not** create a booking, accept a Late Confirmation, join a tournament or create a tournament.

## BR-ACC-018 — Source `FR-ACC-18`

**Priority:** M  
**Actor / Surface:** Customer / Customer Mobile App  
**Rule Type:** State/Lifecycle, Audit/History  
**Governing v5.0:** §8.10

**Rule:** **Qualification as-of** and **operational status as-of** are separate historical queries and may legitimately differ after a correction. Historical qualification never implies historical operational tournament privileges.

---

# 3. Discovery and Browsing

## BR-DIS-001 — Source `FR-DIS-13`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff / Customer Mobile App  
**Rule Type:** State/Lifecycle, Inventory/Reservation, Configuration  
**Governing v5.0:** §20.1

**Rule:** The canonical discovery order is **Area / Location → Activity → Venue → Resource → Availability → Booking**. **Activity selection happens before venue discovery**, which is what makes Nearest Availability computable per selected activity.

## BR-DIS-002 — Source `FR-DIS-14`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff / Customer Mobile App  
**Rule Type:** State/Lifecycle, Inventory/Reservation, Configuration  
**Governing v5.0:** §20.8

**Rule:** The canonical booking flow is **Area → Activity → Venue → Resource → (Single / Multiplayer, PlayStation only) → Date → Start Time → Duration → Booking Summary → Confirm**.

## BR-DIS-003 — Source `FR-DIS-01`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff / Customer Mobile App  
**Rule Type:** Validation/Eligibility, Authorization, Configuration  
**Governing v5.0:** §20.2

**Rule:** With location enabled and no manual area selection, show eligible venues across **active areas** sorted primarily by distance.

## BR-DIS-004 — Source `FR-DIS-02`

**Priority:** M  
**Actor / Surface:** Customer / Customer Mobile App  
**Rule Type:** State/Lifecycle, Authorization, Configuration  
**Governing v5.0:** §20.2

**Rule:** A manually selected area becomes a **hard filter**, even while location permission remains enabled.

## BR-DIS-005 — Source `FR-DIS-03`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff / Customer Mobile App  
**Rule Type:** Inventory/Reservation, Money/Points, UI/Presentation, Configuration  
**Governing v5.0:** §20.3

**Rule:** The **venue card** shows: venue image · venue name · area · distance where available · **starting price** · **Nearest Availability** · average rating · review count.

## BR-DIS-006 — Source `FR-DIS-04`

**Priority:** M  
**Actor / Surface:** Customer, System / Customer Mobile App  
**Rule Type:** Validation/Eligibility, Timer/Scheduler, Inventory/Reservation, Configuration  
**Governing v5.0:** §16.5

**Rule:** **Nearest Availability** is the earliest valid start time for the currently selected activity at the **minimum booking duration of 1 hour**, computed from opening hours, eligible resources, the canonical availability predicate, the current time and the 15-minute lead-time rule. For PlayStation, before any room or console filter, use the earliest valid PlayStation resource.

## BR-DIS-007 — Source `FR-DIS-05`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff / Customer Mobile App  
**Rule Type:** Validation/Eligibility, Inventory/Reservation, UI/Presentation  
**Governing v5.0:** §16.5, §43.7

**Rule:** Resources that are DISABLED, ARCHIVED, under `OWNER_REVIEW_REQUIRED`, or under an operational selling block at venue or resource level must **never** contribute to Nearest Availability or appear as available.

## BR-DIS-008 — Source `FR-DIS-06`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff / Customer Mobile App  
**Rule Type:** State/Lifecycle, Validation/Eligibility, Timer/Scheduler, Inventory/Reservation, Money/Points  
**Governing v5.0:** §20.7

**Rule:** A currently closed venue remains discoverable. The customer may view the venue, activities, future availability, prices and Nearest Availability, and sees state such as *Closed Now* and *Opens at 12:00 PM*. They may not submit a request until the next valid online intake window.

## BR-DIS-009 — Source `FR-DIS-07`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff / Customer Mobile App  
**Rule Type:** Functional rule  
**Governing v5.0:** §20.6

**Rule:** Cross-activity discovery surfaces the venue's other activities — *Also available at this venue*.

## BR-DIS-010 — Source `FR-DIS-08`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff / Customer Mobile App  
**Rule Type:** State/Lifecycle, UI/Presentation  
**Governing v5.0:** §11

**Rule:** Venues with marketplace status ONBOARDING, INACTIVE or SUSPENDED are not shown in normal customer discovery. A deep-linked venue page may remain viewable with booking actions disabled.

## BR-DIS-011 — Source `FR-DIS-09`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff / Customer Mobile App  
**Rule Type:** State/Lifecycle, Trust/Enforcement, UI/Presentation  
**Governing v5.0:** §20.10, §34.5

**Rule:** The venue phone is hidden before a booking exists. Counterpart phone access **opens when the booking becomes PENDING**: the venue may see the customer phone and the customer may see the venue phone. **Manual booking is exempt**, because the venue entered any contact data itself.

## BR-DIS-012 — Source `FR-DIS-10`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff / Customer Mobile App  
**Rule Type:** Validation/Eligibility, Inventory/Reservation, Money/Points, UI/Presentation  
**Governing v5.0:** §20.4, §2.1

**Rule:** **Map view** shows venue markers positioned by coordinates under the same eligibility rules as the list. A marker preview contains venue name, distance, starting price, Nearest Availability, average rating, review count and a **View Venue** action.

## BR-DIS-013 — Source `FR-DIS-11`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff / Customer Mobile App  
**Rule Type:** Timer/Scheduler, Inventory/Reservation, UI/Presentation, Configuration  
**Governing v5.0:** §20.5

**Rule:** **Venue Details** shows images, venue name, area, address, distance, opening hours, rating, reviews, a **View Location** action, the selected activity, its resources, pricing and availability.

## BR-DIS-014 — Source `FR-DIS-12`

**Priority:** M  
**Actor / Surface:** Customer / Customer Mobile App  
**Rule Type:** Inventory/Reservation, Money/Points, Configuration  
**Governing v5.0:** §20.9

**Rule:** **Find Available** search takes area, activity, date, start time and duration, and returns only resources available for the **entire** requested interval under the canonical availability predicate. There is **no price filter** in this release.

---

# 4. Availability and Inventory

## BR-AVL-001 — Source `FR-AVL-01`

**Priority:** M  
**Actor / Surface:** System / System / cross-cutting  
**Rule Type:** Inventory/Reservation  
**Governing v5.0:** §16

**Rule:** A single canonical availability predicate governs every flow — online booking, manual booking, Late Confirm, tournament creation, tournament holds and Nearest Availability.

## BR-AVL-002 — Source `FR-AVL-02`

**Priority:** M  
**Actor / Surface:** Venue Staff, System / System / cross-cutting  
**Rule Type:** State/Lifecycle, Inventory/Reservation, Configuration  
**Governing v5.0:** §16.2

**Rule:** Canonical availability evaluates, **in this order**: **1.** venue operational selling block · **2.** resource operational selling block · **3.** resource base state · **4.** resource operational restriction · **5.** emergency type, status, affected scope and declared interval · **6.** maintenance · **7.** closure · **8.** blocking reservations. An operational selling block is evaluated **explicitly before** any resource reservation check and needs no fabricated future interval; an unresolved emergency is **never** treated as an infinitely extended reservation.

## BR-AVL-003 — Source `FR-AVL-03`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff, System / System / cross-cutting  
**Rule Type:** Inventory/Reservation, UI/Presentation, Configuration  
**Governing v5.0:** §16.3

**Rule:** A resource interval is **not free** when **any** of: the resource is DISABLED or ARCHIVED · `operationalRestriction = OWNER_REVIEW_REQUIRED` · a relevant operational selling block is active at venue or resource level · the interval overlaps a blocking reservation from a PENDING, AWAITING_CUSTOMER_CONFIRMATION, CONFIRMED or ACTIVE booking of either source, a Player Tournament Approval Hold, a Tournament Hold, Maintenance, a Venue Closure, an Emergency Closure or a Resource Emergency Outage.

## BR-AVL-004 — Source `FR-AVL-04`

**Priority:** M  
**Actor / Surface:** System / System / cross-cutting  
**Rule Type:** State/Lifecycle, Inventory/Reservation  
**Governing v5.0:** §16.3

**Rule:** **Booking status decides whether a booking blocks inventory; booking source does not.** For `source = MANUAL` only CONFIRMED and ACTIVE are normally reachable blocking states.

## BR-AVL-005 — Source `FR-AVL-05`

**Priority:** M  
**Actor / Surface:** Venue Staff, System / System / cross-cutting  
**Rule Type:** State/Lifecycle, Inventory/Reservation  
**Governing v5.0:** §16.3

**Rule:** `VENUE_RESPONSE_EXPIRED` does **not** block inventory, because its reservation has been released. Terminal bookings never block.

## BR-AVL-006 — Source `FR-AVL-06`

**Priority:** M  
**Actor / Surface:** System / System / cross-cutting  
**Rule Type:** State/Lifecycle, Validation/Eligibility, Inventory/Reservation  
**Governing v5.0:** §13.3, §16.2, §43

**Rule:** A resource requires **both** `baseState = ACTIVE` **and** `operationalRestriction = NONE` to be eligible for any flow.

## BR-AVL-007 — Source `FR-AVL-07`

**Priority:** M  
**Actor / Surface:** Venue Staff, System / System / cross-cutting  
**Rule Type:** Validation/Eligibility, Inventory/Reservation  
**Governing v5.0:** §38.4

**Rule:** Every creation and commitment predicate requires `Operational Selling Block = FALSE` for the affected venue **and** resource. Interval-overlap checks alone are never sufficient.

## BR-AVL-008 — Source `FR-AVL-08`

**Priority:** M  
**Actor / Surface:** System / System / cross-cutting  
**Rule Type:** Validation/Eligibility, Inventory/Reservation  
**Governing v5.0:** §16.4

**Rule:** **Double booking is never allowed.** Two active reservations must never overlap on one resource.

## BR-AVL-009 — Source `FR-AVL-09`

**Priority:** M  
**Actor / Surface:** Venue Staff, System / System / cross-cutting  
**Rule Type:** State/Lifecycle, Validation/Eligibility, Inventory/Reservation  
**Governing v5.0:** §31.9, §31.10

**Rule:** Eligibility is revalidated immediately before **every** commitment-strengthening transition — creation, Venue Accept, Late Confirm initiation, Late Confirm Accept, manual booking creation, tournament creation, tournament approval, tournament checkpoint confirmation, booking CONFIRMED → ACTIVE, tournament CONFIRMED → ACTIVE, and resource or hold assignment, **and before any future transition whose effect is to obtain or strengthen a resource or venue commitment**. No lifecycle boundary is exempt from operational eligibility.

## BR-AVL-010 — Source `FR-AVL-10`

**Priority:** M  
**Actor / Surface:** System / System / cross-cutting  
**Rule Type:** State/Lifecycle, Validation/Eligibility  
**Governing v5.0:** §31.11

**Rule:** Revalidation and the state transition commit in **one atomic transaction**. The first successfully committed valid transition wins; a state change between validation and commit causes revalidation or failure.

## BR-AVL-011 — Source `FR-AVL-11`

**Priority:** M  
**Actor / Surface:** System / System / cross-cutting  
**Rule Type:** State/Lifecycle, Validation/Eligibility, Inventory/Reservation, Audit/History, UI/Presentation  
**Governing v5.0:** §31.12

**Rule:** Displayed availability is a snapshot, not a commit guarantee. An action is offered only when the latest known state satisfies its predicate; execution always revalidates atomically and may fail.

## BR-AVL-012 — Source `FR-AVL-12`

**Priority:** M  
**Actor / Surface:** System / System / cross-cutting  
**Rule Type:** Validation/Eligibility, Timer/Scheduler, Inventory/Reservation, Trust/Enforcement, Audit/History  
**Governing v5.0:** §17.2

**Rule:** **Historical planned intervals may overlap; active reservation occupancy may not.** A no-show releases the remainder of an interval, so a later valid booking may start inside the original planned window.

## BR-AVL-013 — Source `FR-AVL-13`

**Priority:** M  
**Actor / Surface:** System / System / cross-cutting  
**Rule Type:** Inventory/Reservation, Audit/History  
**Governing v5.0:** §17.3

**Rule:** Reservation history stores `plannedStart`, `plannedEnd`, `actualReservationStart`, `actualReservationReleaseAt` and `releaseReason`, so historical availability can be replayed and an apparent overlap explained.

## BR-AVL-014 — Source `FR-AVL-14`

**Priority:** M  
**Actor / Surface:** Venue Staff, System / System / cross-cutting  
**Rule Type:** Timer/Scheduler, Money/Points, Audit/History  
**Governing v5.0:** §17.5

**Rule:** Every revenue-producing record — online booking, manual booking, tournament — stores a `businessDate`, which is the canonical revenue-day key and is historical.

## BR-AVL-015 — Source `FR-AVL-15`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff, System / System / cross-cutting  
**Rule Type:** State/Lifecycle, Validation/Eligibility, Timer/Scheduler  
**Governing v5.0:** §17.6

**Rule:** Canonical timer boundary: an action allowed "before" a deadline requires `now < deadline`; at `now >= deadline` the expiry owns the state. This applies to the venue response window, Late Confirmation, customer cancellation at start, tournament join and leave at start, and every equivalent boundary.

---

# 5. Online Booking Lifecycle

## BR-BOK-001 — Source `FR-BOK-01`

**Priority:** M  
**Actor / Surface:** System / Customer Mobile App, Venue Dashboard  
**Rule Type:** Timer/Scheduler  
**Governing v5.0:** §19

**Rule:** Duration: minimum **1 hour**, increments of **30 minutes**, maximum **6 hours** for online bookings.

## BR-BOK-002 — Source `FR-BOK-02`

**Priority:** M  
**Actor / Surface:** System / Customer Mobile App, Venue Dashboard  
**Rule Type:** Validation/Eligibility, Timer/Scheduler  
**Governing v5.0:** §19

**Rule:** A new online booking requires `now <= startAt − 15 minutes`. Exactly 15 minutes is permitted; less is rejected.

## BR-BOK-003 — Source `FR-BOK-03`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff / Customer Mobile App, Venue Dashboard  
**Rule Type:** State/Lifecycle, Inventory/Reservation  
**Governing v5.0:** §19.1

**Rule:** A customer may hold at most **4** active/upcoming online commitments — counting PENDING, AWAITING_CUSTOMER_CONFIRMATION, CONFIRMED and ACTIVE across all venues. VENUE_RESPONSE_EXPIRED and terminal states do not count; manual bookings do not count. The limit is checked on **every** action that would increase the count.

## BR-BOK-004 — Source `FR-BOK-04`

**Priority:** M  
**Actor / Surface:** Customer / Customer Mobile App, Venue Dashboard  
**Rule Type:** Validation/Eligibility, Inventory/Reservation  
**Governing v5.0:** §19.2

**Rule:** A customer may not hold **overlapping** online commitments. The 4-booking limit is a total-count limit **in addition to** the no-overlap rule.

## BR-BOK-005 — Source `FR-BOK-05`

**Priority:** M  
**Actor / Surface:** System / Customer Mobile App, Venue Dashboard  
**Rule Type:** Validation/Eligibility, Configuration  
**Governing v5.0:** §14.5

**Rule:** The booking interval must fit entirely inside **one** future operating session. It may cross midnight where the session does. It may not cross a closure, a closed gap, or the end of one session into another.

## BR-BOK-006 — Source `FR-BOK-06`

**Priority:** M  
**Actor / Surface:** System / Customer Mobile App, Venue Dashboard  
**Rule Type:** Timer/Scheduler, Money/Points, Audit/History  
**Governing v5.0:** §4.3

**Rule:** Price = `hourlyPrice × nominalBookedMinutes ÷ 60`, rounded **half-up to 2 decimal places** per line item; totals are sums of rounded line items. The stored historical amount is immutable.

## BR-BOK-007 — Source `FR-BOK-07`

**Priority:** M  
**Actor / Surface:** Customer / Customer Mobile App, Venue Dashboard  
**Rule Type:** Timer/Scheduler, Inventory/Reservation, Money/Points  
**Governing v5.0:** §4.4, §14.3

**Rule:** Price and duration use the customer-selected **nominal** duration, never a DST-anomalous elapsed duration. Inventory and `endAt` use real elapsed time: `endAt = startAt + nominalBookedMinutes`.

## BR-BOK-008 — Source `FR-BOK-08`

**Priority:** M  
**Actor / Surface:** Venue Staff / Customer Mobile App, Venue Dashboard  
**Rule Type:** Functional rule  
**Governing v5.0:** §21

**Rule:** A request may be submitted only while the venue is **currently open** under its current operating session — even for a future date and time.

## BR-BOK-009 — Source `FR-BOK-09`

**Priority:** M  
**Actor / Surface:** Venue Staff / Customer Mobile App, Venue Dashboard  
**Rule Type:** Timer/Scheduler  
**Governing v5.0:** §21

**Rule:** New online booking intake **stops 10 minutes before the current operating session ends**, guaranteeing the venue a full response window while operating.

## BR-BOK-010 — Source `FR-BOK-10`

**Priority:** M  
**Actor / Surface:** System / Customer Mobile App, Venue Dashboard  
**Rule Type:** Validation/Eligibility  
**Governing v5.0:** §21

**Rule:** Being open at submission does not make every future slot valid; the requested future interval is validated independently.

## BR-BOK-011 — Source `FR-BOK-11`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff / Customer Mobile App, Venue Dashboard  
**Rule Type:** State/Lifecycle, Validation/Eligibility, Timer/Scheduler, Inventory/Reservation, Authorization  
**Governing v5.0:** §22

**Rule:** Creation requires **all** of: account verified by phone OTP · customer not banned · customer below the 4-commitment limit · no overlapping customer commitment · venue marketplace **ACTIVE** · venue account **ENABLED** · area **ACTIVE** · venue inside an operating session · at least 10 minutes before session end · no venue operational selling block · no resource operational selling block · requested start at least 15 minutes ahead · duration 1–6 hours in 30-minute increments · interval fits one future operating session · resource base state **ACTIVE** · resource operational restriction **NONE** · no overlapping blocking reservation · activity/resource compatibility · **all other required product validation** (§22 item 20). The twenty conditions above are cumulative and are the complete canonical list. Note: the explicit *"required pricing configuration exists"* condition belongs to the **manual** booking predicate (§30.2 item 15, FR-MAN-02) and is **not** one of §22's twenty. Where a venue's pricing configuration for the activity is absent, online creation fails under §22 item 20. Stating it as an explicit twenty-first condition of §22 would be a Product Change Request against v5.0 §22, not a PRD edit; the Product Owner has ruled that **no twenty-first condition is added**.

## BR-BOK-012 — Source `FR-BOK-12`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff / Customer Mobile App, Venue Dashboard  
**Rule Type:** State/Lifecycle  
**Governing v5.0:** §18.2

**Rule:** Canonical states: PENDING · VENUE_RESPONSE_EXPIRED · AWAITING_CUSTOMER_CONFIRMATION · CONFIRMED · ACTIVE · COMPLETED · REJECTED · CANCELLED · NO_SHOW. Terminal: COMPLETED, REJECTED, CANCELLED, NO_SHOW. **`VENUE_RESPONSE_EXPIRED` is non-terminal.**

## BR-BOK-013 — Source `FR-BOK-13`

**Priority:** M  
**Actor / Surface:** Venue Staff / Customer Mobile App, Venue Dashboard  
**Rule Type:** Timer/Scheduler, Inventory/Reservation  
**Governing v5.0:** §23.1

**Rule:** On creation the booking is **PENDING** and reserves the resource. The venue has **10 minutes** to Accept or Reject. There is **no auto-accept**.

## BR-BOK-014 — Source `FR-BOK-14`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff / Customer Mobile App, Venue Dashboard  
**Rule Type:** State/Lifecycle, Validation/Eligibility, Inventory/Reservation, Trust/Enforcement  
**Governing v5.0:** §23.2

**Rule:** Venue Accept revalidates atomically: venue existing-commitment eligibility, venue selling block, resource base state, resource operational restriction, resource selling block, reservation validity, interval validity, and **always** customer account and ban eligibility.

## BR-BOK-015 — Source `FR-BOK-15`

**Priority:** M  
**Actor / Surface:** Venue Staff / Customer Mobile App, Venue Dashboard  
**Rule Type:** Validation/Eligibility  
**Governing v5.0:** §35.2

**Rule:** Venue Accept uses **existing-commitment** eligibility (category B) and therefore does **not** require marketplace ACTIVE. A venue that became INACTIVE or SUSPENDED after the request may still accept it.

## BR-BOK-016 — Source `FR-BOK-16`

**Priority:** M  
**Actor / Surface:** Venue Staff / Customer Mobile App, Venue Dashboard  
**Rule Type:** Validation/Eligibility, Inventory/Reservation, UI/Presentation  
**Governing v5.0:** §23.3

**Rule:** If Accept fails because the venue or resource became unavailable through a venue emergency, a resource emergency or owner review, the booking may **not** become CONFIRMED; it is CANCELLED under the applicable operational reason, and **no venue non-response blame is applied**.

## BR-BOK-017 — Source `FR-BOK-17`

**Priority:** M  
**Actor / Surface:** Venue Staff / Customer Mobile App, Venue Dashboard  
**Rule Type:** State/Lifecycle, Inventory/Reservation, Configuration  
**Governing v5.0:** §23.4

**Rule:** Venue Reject: PENDING → **REJECTED** under terminal reason code `VENUE_REJECTED`. A **venue-supplied reject reason is mandatory** and is recorded separately from the reason code — examples: resource unavailable, maintenance, venue issue, other. The **resource reservation is released immediately**.

## BR-BOK-018 — Source `FR-BOK-18`

**Priority:** M  
**Actor / Surface:** Venue Staff / Customer Mobile App, Venue Dashboard  
**Rule Type:** State/Lifecycle, Timer/Scheduler, Inventory/Reservation  
**Governing v5.0:** §23.5

**Rule:** At `venueResponseExpiresAt` with no response, PENDING → **VENUE_RESPONSE_EXPIRED** and the resource reservation releases immediately.

## BR-BOK-019 — Source `FR-BOK-19`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff / Customer Mobile App, Venue Dashboard  
**Rule Type:** UI/Presentation  
**Governing v5.0:** §18.3

**Rule:** The customer-facing projection maps: **Upcoming** = PENDING, AWAITING_CUSTOMER_CONFIRMATION, CONFIRMED, ACTIVE · **Completed** = COMPLETED · **Cancelled** = VENUE_RESPONSE_EXPIRED, CANCELLED, REJECTED, NO_SHOW.

## BR-BOK-020 — Source `FR-BOK-20`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff, System / Customer Mobile App, Venue Dashboard  
**Rule Type:** Validation/Eligibility, Timer/Scheduler, UI/Presentation  
**Governing v5.0:** §18.3, §24.1

**Rule:** A VENUE_RESPONSE_EXPIRED booking is displayed under Cancelled with *"Venue did not respond in time"*, while remaining internally eligible for Late Confirmation until `startAt`. There is no pre-emptive customer opt-out, and no automatic reactivation.

## BR-BOK-021 — Source `FR-BOK-21`

**Priority:** M  
**Actor / Surface:** Venue Staff / Customer Mobile App, Venue Dashboard  
**Rule Type:** State/Lifecycle, Timer/Scheduler  
**Governing v5.0:** §24.2

**Rule:** The venue may **Reject After Expiry** before the start time: VENUE_RESPONSE_EXPIRED → REJECTED, reason `REJECTED_AFTER_EXPIRY`.

## BR-BOK-022 — Source `FR-BOK-22`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff, System / Customer Mobile App, Venue Dashboard  
**Rule Type:** State/Lifecycle, Validation/Eligibility, Inventory/Reservation, Notification  
**Governing v5.0:** §24.4

**Rule:** Resource Disable, Archive and Console Change may proceed while a booking is only VENUE_RESPONSE_EXPIRED, since no reservation is held. The system records `LATE_CONFIRM_ELIGIBILITY_LOST_RESOURCE_STATE_CHANGED` with the matching `detailCode`; no additional customer push is required.

## BR-BOK-023 — Source `FR-BOK-23`

**Priority:** M  
**Actor / Surface:** Venue Staff / Customer Mobile App, Venue Dashboard  
**Rule Type:** State/Lifecycle, Validation/Eligibility, Timer/Scheduler, Inventory/Reservation, UI/Presentation  
**Governing v5.0:** §24.3

**Rule:** A booking still VENUE_RESPONSE_EXPIRED at its original `startAt` becomes CANCELLED, with the reason chosen by this priority: **1.** `RESOURCE_OWNER_REVIEW_REQUIRED` · **2.** `RESOURCE_EMERGENCY_OUTAGE` · **3.** `VENUE_EMERGENCY_CLOSURE` · **4.** `VENUE_ACCOUNT_DISABLED` · **5.** `VENUE_DID_NOT_RESPOND_IN_TIME`. The last applies only when no overriding operational or platform cause exists.

## BR-BOK-024 — Source `FR-BOK-24`

**Priority:** M  
**Actor / Surface:** Venue Staff, System / Customer Mobile App, Venue Dashboard  
**Rule Type:** State/Lifecycle, Timer/Scheduler  
**Governing v5.0:** §26.1

**Rule:** CONFIRMED → ACTIVE at `startAt` and ACTIVE → COMPLETED at `endAt` are automatic and backend-controlled. No venue action is required.

## BR-BOK-025 — Source `FR-BOK-25`

**Priority:** M  
**Actor / Surface:** Venue Staff, System / Customer Mobile App, Venue Dashboard  
**Rule Type:** State/Lifecycle, Validation/Eligibility, Inventory/Reservation, Configuration  
**Governing v5.0:** §31.2

**Rule:** Immediately before CONFIRMED → ACTIVE the system revalidates venue selling block, resource base state, resource operational restriction, resource selling block, and applicable closure or emergency conditions — atomically with the transition.

## BR-BOK-026 — Source `FR-BOK-26`

**Priority:** M  
**Actor / Surface:** System / Customer Mobile App, Venue Dashboard  
**Rule Type:** State/Lifecycle, Timer/Scheduler, Trust/Enforcement, Money/Points, Notification  
**Governing v5.0:** §31.3

**Rule:** Every start-time failure produces a deterministic terminal transition, a closed reason code, an attendance result, a notification and a refund classification. **A booking can never remain CONFIRMED past `startAt` because a check failed.**

## BR-BOK-027 — Source `FR-BOK-27`

**Priority:** M  
**Actor / Surface:** Venue Staff, Platform Admin, System / Customer Mobile App, Venue Dashboard, Platform Admin  
**Rule Type:** State/Lifecycle, Inventory/Reservation, Authorization, UI/Presentation, Configuration  
**Governing v5.0:** §31.6

**Rule:** Start-time reason priority: **1.** `RESOURCE_OWNER_REVIEW_REQUIRED` · **2.** `RESOURCE_EMERGENCY_OUTAGE` · **3.** `VENUE_EMERGENCY_CLOSURE` · **4.** `VENUE_CLOSURE` · **5.** `VENUE_ACCOUNT_DISABLED` where it applies to an unresolved pre-confirmation lifecycle · **6.** `SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR` · **7.** other explicit admin or system correction. Exactly one reason is stored.

## BR-BOK-028 — Source `FR-BOK-28`

**Priority:** M  
**Actor / Surface:** Customer, Platform Admin, System / Customer Mobile App, Venue Dashboard, Platform Admin  
**Rule Type:** State/Lifecycle, Timer/Scheduler, Inventory/Reservation, Authorization, Trust/Enforcement  
**Governing v5.0:** §31.4

**Rule:** If a resource base-state anomaly survives to `startAt`, the booking is cancelled immediately under `SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR`, with no customer penalty, a full future fee refund, a customer notification and a Platform Admin alert recording booking ID, resource ID, the unexpected state, `startAt`, detection time, the conflicting source where known, and a correlation identifier.

## BR-BOK-029 — Source `FR-BOK-29`

**Priority:** M  
**Actor / Surface:** Venue Staff, Platform Admin / Customer Mobile App, Venue Dashboard, Platform Admin  
**Rule Type:** Timer/Scheduler, Inventory/Reservation, Authorization, UI/Presentation, Configuration  
**Governing v5.0:** §26.3

**Rule:** There is **no ordinary early session end**. An ACTIVE booking blocks its resource until `endAt`. The only early-termination paths are venue emergency closure, resource emergency outage, entry into `OWNER_REVIEW_REQUIRED`, and Platform Admin support correction.

## BR-BOK-030 — Source `FR-BOK-30`

**Priority:** M  
**Actor / Surface:** Venue Staff, Platform Admin / Customer Mobile App, Venue Dashboard, Platform Admin  
**Rule Type:** Inventory/Reservation, Authorization, UI/Presentation, Configuration  
**Governing v5.0:** §31.8

**Rule:** ACTIVE interruption reason priority: **1.** `RESOURCE_OWNER_REVIEW_REQUIRED` · **2.** `RESOURCE_EMERGENCY_OUTAGE` · **3.** `VENUE_EMERGENCY_CLOSURE` · **4.** explicit Platform Admin correction.

## BR-BOK-031 — Source `FR-BOK-31`

**Priority:** M  
**Actor / Surface:** Customer / Customer Mobile App, Venue Dashboard  
**Rule Type:** Timer/Scheduler, Trust/Enforcement  
**Governing v5.0:** §29.1

**Rule:** The customer may cancel while `now < startAt`, free of charge, with no no-show and no trust penalty, reason `CUSTOMER_CANCELLED`. After start time, customer cancellation is disabled.

## BR-BOK-032 — Source `FR-BOK-32`

**Priority:** M  
**Actor / Surface:** Customer / Customer Mobile App, Venue Dashboard  
**Rule Type:** Timer/Scheduler  
**Governing v5.0:** §29.2

**Rule:** The customer retains the right to cancel a PENDING booking while its response timer is paused by an emergency.

---

# 6. Late Confirmation

## BR-LCF-001 — Source `FR-LCF-01`

**Priority:** M  
**Actor / Surface:** Customer / Customer Mobile App, Venue Dashboard  
**Rule Type:** Functional rule  
**Governing v5.0:** §35.2

**Rule:** Late Confirm is **existing-commitment operational management** (category B), not new-activity acquisition.

## BR-LCF-002 — Source `FR-LCF-02`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff / Customer Mobile App, Venue Dashboard  
**Rule Type:** State/Lifecycle, Validation/Eligibility, Timer/Scheduler, Authorization, Configuration  
**Governing v5.0:** §25.2

**Rule:** Venue eligibility: marketplace status **ACTIVE, INACTIVE or SUSPENDED** · venue account **ENABLED** · area **ACTIVE** · venue currently inside a valid operating session · no venue operational selling block · the original interval still fits valid opening hours · original start still in the future.

## BR-LCF-003 — Source `FR-LCF-03`

**Priority:** M  
**Actor / Surface:** Customer / Customer Mobile App, Venue Dashboard  
**Rule Type:** State/Lifecycle, Validation/Eligibility, Inventory/Reservation  
**Governing v5.0:** §25.3

**Rule:** Resource eligibility: base state **ACTIVE** · operational restriction **NONE** · no resource operational selling block · the interval still passes the canonical conflict predicate.

## BR-LCF-004 — Source `FR-LCF-04`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff / Customer Mobile App, Venue Dashboard  
**Rule Type:** Validation/Eligibility, Inventory/Reservation, Authorization, Trust/Enforcement  
**Governing v5.0:** §25.4

**Rule:** Customer eligibility at initiation: not banned · below the 4-commitment limit. If the customer is already at 4, initiation is **blocked**, the booking stays VENUE_RESPONSE_EXPIRED, and **no temporary hold is created**.

## BR-LCF-005 — Source `FR-LCF-05`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff / Customer Mobile App, Venue Dashboard  
**Rule Type:** Validation/Eligibility, Audit/History  
**Governing v5.0:** §25.4

**Rule:** When initiation is blocked by the limit, the venue sees a neutral message — *"Customer is not currently eligible for Late Confirmation"* — and **never** learns about the customer's other bookings. The internal event is `LATE_CONFIRM_FAILED_BOOKING_LIMIT`.

## BR-LCF-006 — Source `FR-LCF-06`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff / Customer Mobile App, Venue Dashboard  
**Rule Type:** State/Lifecycle, Validation/Eligibility, Inventory/Reservation  
**Governing v5.0:** §25.5

**Rule:** Initiation is **one atomic transaction**: read commitment count · validate the limit · validate the resource · create the temporary hold · transition VENUE_RESPONSE_EXPIRED → AWAITING_CUSTOMER_CONFIRMATION. Concurrent initiations can never produce a temporary fifth commitment.

## BR-LCF-007 — Source `FR-LCF-07`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff / Customer Mobile App, Venue Dashboard  
**Rule Type:** Timer/Scheduler  
**Governing v5.0:** §25.6

**Rule:** Late Confirm is **not** restricted by the customer intake window; the venue may initiate it even with fewer than 10 minutes left in the session, because it is a venue-initiated action needing no new venue response window.

## BR-LCF-008 — Source `FR-LCF-08`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff / Customer Mobile App, Venue Dashboard  
**Rule Type:** Inventory/Reservation, Money/Points, Audit/History  
**Governing v5.0:** §25.7

**Rule:** Late Confirmation keeps the **same Booking ID**, venue, activity, resource, console, play mode, date, time, duration, venue price, booking fee and total. All prior history is preserved.

## BR-LCF-009 — Source `FR-LCF-09`

**Priority:** M  
**Actor / Surface:** Customer / Customer Mobile App, Venue Dashboard  
**Rule Type:** Validation/Eligibility, Timer/Scheduler  
**Governing v5.0:** §25.8

**Rule:** `lateConfirmExpiresAt = MIN(now + 5 minutes, booking.startAt)`. The window must never extend beyond the original start time.

## BR-LCF-010 — Source `FR-LCF-10`

**Priority:** M  
**Actor / Surface:** Customer / Customer Mobile App, Venue Dashboard  
**Rule Type:** State/Lifecycle, Timer/Scheduler, Inventory/Reservation  
**Governing v5.0:** §25.8

**Rule:** Late Confirmation state is stored **per booking** — its own expiry, warning flag, customer response and resource hold. One user may hold several simultaneous Late Confirmations that never interfere.

## BR-LCF-011 — Source `FR-LCF-11`

**Priority:** M  
**Actor / Surface:** Customer / Customer Mobile App, Venue Dashboard  
**Rule Type:** UI/Presentation  
**Governing v5.0:** §25.9

**Rule:** Customer copy must communicate that the original request expired — *"Game Zone is asking to reactivate your expired booking. Do you still want it?"* — with **Accept Booking** and **Decline** actions.

## BR-LCF-012 — Source `FR-LCF-12`

**Priority:** M  
**Actor / Surface:** Customer / Customer Mobile App, Venue Dashboard  
**Rule Type:** Timer/Scheduler, Notification  
**Governing v5.0:** §25.9

**Rule:** If the window exceeds 60 seconds, send the Late Confirmation notification plus one *Late Confirmation Expiring* notification about a minute before expiry, once per booking, linked to the exact Booking ID. If the window is 60 seconds or less, send no second notification and show the real short countdown in the first.

## BR-LCF-013 — Source `FR-LCF-13`

**Priority:** M  
**Actor / Surface:** Customer / Customer Mobile App, Venue Dashboard  
**Rule Type:** State/Lifecycle, Trust/Enforcement  
**Governing v5.0:** §25.10

**Rule:** Responses: **Accept** → CONFIRMED · **Decline** → CANCELLED `CUSTOMER_DECLINED_LATE_CONFIRM` · **No response** → CANCELLED `CUSTOMER_RECONFIRM_TIMEOUT`. All keep the same Booking ID and carry **no penalty**.

## BR-LCF-014 — Source `FR-LCF-14`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff / Customer Mobile App, Venue Dashboard  
**Rule Type:** Validation/Eligibility, Inventory/Reservation, Trust/Enforcement  
**Governing v5.0:** §25.11

**Rule:** Accept revalidates: commitment count still valid · no customer schedule conflict · resource reservation still valid · ban and account eligibility · venue and resource operational eligibility including selling block.

## BR-LCF-015 — Source `FR-LCF-15`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff / Customer Mobile App, Venue Dashboard  
**Rule Type:** Validation/Eligibility  
**Governing v5.0:** §25.12

**Rule:** For privacy, the customer schedule conflict is checked **only when the customer attempts to accept**. The venue is never told the customer has another booking and may freely send the request.

## BR-LCF-016 — Source `FR-LCF-16`

**Priority:** M  
**Actor / Surface:** Customer / Customer Mobile App, Venue Dashboard  
**Rule Type:** State/Lifecycle  
**Governing v5.0:** §25.12

**Rule:** A conflicting Accept **fails** and records `LATE_CONFIRM_FAILED_CUSTOMER_CONFLICT`. The booking does not terminate under a generic schedule-conflict reason; any later terminal outcome uses its real reason.

## BR-LCF-017 — Source `FR-LCF-17`

**Priority:** M  
**Actor / Surface:** Customer / Customer Mobile App, Venue Dashboard  
**Rule Type:** State/Lifecycle, Validation/Eligibility, Inventory/Reservation  
**Governing v5.0:** §25.13

**Rule:** Resource unavailability during Late Confirm is expressed as validation failure, atomic transaction failure, or `LATE_CONFIRM_FAILED_RESOURCE_CONFLICT`. There is **no** terminal "late confirm resource unavailable" reason.

## BR-LCF-018 — Source `FR-LCF-18`

**Priority:** M  
**Actor / Surface:** Customer / Customer Mobile App, Venue Dashboard  
**Rule Type:** Trust/Enforcement  
**Governing v5.0:** §34.7

**Rule:** Counterpart phone visibility **must reopen** for the AWAITING_CUSTOMER_CONFIRMATION period, and is fully restored on Accept.

---

# 7. Attendance, Check-in and No-show

## BR-ATT-001 — Source `FR-ATT-01`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff, Venue Owner authorization / Customer Mobile App, Venue Dashboard  
**Rule Type:** Authorization, Audit/History  
**Governing v5.0:** §27.1, §27.2

**Rule:** **Check-in** is a venue staff action requiring no QR code, PIN, customer device confirmation or biometrics. It does not require the Owner Password, and it is audited with venue account identity, timestamp and Booking ID.

## BR-ATT-002 — Source `FR-ATT-02`

**Priority:** M  
**Actor / Surface:** System / Customer Mobile App, Venue Dashboard  
**Rule Type:** Validation/Eligibility, Timer/Scheduler  
**Governing v5.0:** §27.2

**Rule:** Check-in window: `startAt <= now < startAt + 15 minutes`. At exactly `+15 minutes` check-in is refused.

## BR-ATT-003 — Source `FR-ATT-03`

**Priority:** M  
**Actor / Surface:** Venue Owner authorization / Customer Mobile App, Venue Dashboard  
**Rule Type:** Validation/Eligibility, Timer/Scheduler, Authorization, Trust/Enforcement, Money/Points  
**Governing v5.0:** §27.3

**Rule:** **Mark Present — Late** is available while `startAt + 15 min <= now < endAt`, only when no valid check-in exists and no no-show has been committed. It blocks no-show, grants no points, grants no Verified Player progress, and does **not** reset the counter. It requires no Owner Password and is audited.

## BR-ATT-004 — Source `FR-ATT-04`

**Priority:** M  
**Actor / Surface:** Customer, Venue Owner authorization / Customer Mobile App, Venue Dashboard  
**Rule Type:** Validation/Eligibility, Timer/Scheduler, Authorization, Trust/Enforcement, Audit/History  
**Governing v5.0:** §27.4, §27.5

**Rule:** **No-show** is available while `startAt + 15 min <= now < endAt`, only when no valid check-in and no Mark Present — Late exist. It requires no Owner Password and is audited. A customer marked Late Present can never afterwards be marked no-show for the same booking.

## BR-ATT-005 — Source `FR-ATT-05`

**Priority:** M  
**Actor / Surface:** System / Customer Mobile App, Venue Dashboard  
**Rule Type:** Inventory/Reservation, Trust/Enforcement  
**Governing v5.0:** §27.7

**Rule:** A no-show **releases the remaining interval immediately**; the resource may then be sold online, manually or otherwise under normal rules.

## BR-ATT-006 — Source `FR-ATT-06`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff, Platform Admin / Customer Mobile App, Venue Dashboard, Platform Admin  
**Rule Type:** State/Lifecycle, Validation/Eligibility, Inventory/Reservation, Authorization, Trust/Enforcement  
**Governing v5.0:** §27.7

**Rule:** A committed no-show is terminal and **venue staff cannot undo it**. Reversal requires Platform Admin. Reversing a no-show never evicts a later customer who validly obtained the released slot.

## BR-ATT-007 — Source `FR-ATT-07`

**Priority:** M  
**Actor / Surface:** System / Customer Mobile App, Venue Dashboard  
**Rule Type:** State/Lifecycle, Trust/Enforcement  
**Governing v5.0:** §28.1

**Rule:** **Attendance Result is an independent stored field**, never permanently derived from booking status: ATTENDED · ATTENDANCE_UNCONFIRMED · ATTENDED_INTERRUPTED · ABSENT_NO_SHOW · NOT_APPLICABLE. The token is `ABSENT_NO_SHOW`, deliberately distinct from booking status `NO_SHOW`.

## BR-ATT-008 — Source `FR-ATT-08`

**Priority:** M  
**Actor / Surface:** System / Customer Mobile App, Venue Dashboard  
**Rule Type:** State/Lifecycle, Validation/Eligibility, Trust/Enforcement  
**Governing v5.0:** §28.2

**Rule:** Default generation: COMPLETED after valid check-in → ATTENDED · COMPLETED without verified attendance and without no-show → ATTENDANCE_UNCONFIRMED · status NO_SHOW → ABSENT_NO_SHOW · valid check-in then emergency interruption → ATTENDED_INTERRUPTED · manual booking → NOT_APPLICABLE · cancelled or rejected before attendance → NOT_APPLICABLE · interruption with Late Present only → ATTENDANCE_UNCONFIRMED · interruption with no evidence → NOT_APPLICABLE. These are defaults, not permanent formulas.

## BR-ATT-009 — Source `FR-ATT-09`

**Priority:** M  
**Actor / Surface:** System / Customer Mobile App, Venue Dashboard  
**Rule Type:** State/Lifecycle, Validation/Eligibility, Trust/Enforcement, Audit/History  
**Governing v5.0:** §28.3

**Rule:** After audited correction the following are intentionally valid: COMPLETED with ABSENT_NO_SHOW · CANCELLED with ATTENDED_INTERRUPTED · CANCELLED with ATTENDED. An `ATTENDANCE_CORRECTED` may replace the result **without** changing an already-terminal status.

## BR-ATT-010 — Source `FR-ATT-10`

**Priority:** M  
**Actor / Surface:** Customer / Customer Mobile App, Venue Dashboard  
**Rule Type:** State/Lifecycle, Trust/Enforcement, Money/Points  
**Governing v5.0:** §28.5, §31.7

**Rule:** Evidence at interruption decides the outcome: qualifying check-in → ATTENDED_INTERRUPTED with counter reset · Late Present only → ATTENDANCE_UNCONFIRMED, counter unchanged · nothing → NOT_APPLICABLE, counter unchanged. In every case there is no customer penalty and, under a future non-zero fee, a full refund.

## BR-ATT-011 — Source `FR-ATT-11`

**Priority:** M  
**Actor / Surface:** Venue Staff, Platform Admin / Customer Mobile App, Venue Dashboard, Platform Admin  
**Rule Type:** Validation/Eligibility, Authorization, Trust/Enforcement, Audit/History  
**Governing v5.0:** §27.8

**Rule:** Platform Admin may reverse a check-in, reverse a Mark Present — Late, reverse a no-show, and issue an attendance correction. Each requires a mandatory reason and is audited. **Venue staff can reverse none of them.**

## BR-ATT-012 — Source `FR-ATT-12`

**Priority:** M  
**Actor / Surface:** Platform Admin / Customer Mobile App, Venue Dashboard, Platform Admin  
**Rule Type:** Validation/Eligibility, Timer/Scheduler, Authorization, Trust/Enforcement, Money/Points  
**Governing v5.0:** §27.8

**Rule:** Reversal semantics: check-in reversed while ACTIVE keeps the booking ACTIVE and re-opens no-show eligibility within the window · check-in reversed after an ATTENDED completion creates `ATTENDANCE_CORRECTED` and reverses points by compensating transaction · Late Present reversed before `endAt` re-opens no-show eligibility · no-show reversed before original `endAt` leaves the booking CANCELLED under `ADMIN_NO_SHOW_REVERSAL` · no-show reversed after original `endAt` yields COMPLETED / ATTENDANCE_UNCONFIRMED unless attendance is separately established.

## BR-ATT-013 — Source `FR-ATT-13`

**Priority:** M  
**Actor / Surface:** Platform Admin / Customer Mobile App, Venue Dashboard, Platform Admin  
**Rule Type:** State/Lifecycle, Authorization, Trust/Enforcement  
**Governing v5.0:** §27.9

**Rule:** After a wrong no-show the admin chooses explicitly: **attendance confirmed with reallocation** → ATTENDED_INTERRUPTED · **attendance confirmed, session actually completed** → ATTENDED · **penalty removal only** → ATTENDANCE_UNCONFIRMED.

## BR-ATT-014 — Source `FR-ATT-14`

**Priority:** M  
**Actor / Surface:** Venue Staff / Customer Mobile App, Venue Dashboard  
**Rule Type:** Functional rule  
**Governing v5.0:** §32.1

**Rule:** Disabling the venue account does **not** terminate an ACTIVE session. The physical venue may still be operating.

## BR-ATT-015 — Source `FR-ATT-15`

**Priority:** M  
**Actor / Surface:** System / Customer Mobile App, Venue Dashboard  
**Rule Type:** Trust/Enforcement  
**Governing v5.0:** §32.5

**Rule:** Attendance evidence recorded **before** the disable stays authoritative — check-in, Late Present and no-show alike. Account disable never erases or reverses earlier evidence.

## BR-ATT-016 — Source `FR-ATT-16`

**Priority:** M  
**Actor / Surface:** System / Customer Mobile App, Venue Dashboard  
**Rule Type:** Validation/Eligibility, Inventory/Reservation, Trust/Enforcement, Audit/History  
**Governing v5.0:** §32.6

**Rule:** Protection applies only when **all** hold: the booking was ACTIVE · the account became DISABLED during the session · the disable materially prevented reliable attendance recording · no authoritative check-in existed beforehand · no authoritative no-show existed beforehand · the final effective outcome is **not** ATTENDED, ATTENDED_INTERRUPTED or ABSENT_NO_SHOW. Eligible outcomes are ATTENDANCE_UNCONFIRMED and NOT_APPLICABLE from operational early termination.

## BR-ATT-017 — Source `FR-ATT-17`

**Priority:** M  
**Actor / Surface:** Platform Admin / Customer Mobile App, Venue Dashboard, Platform Admin  
**Rule Type:** Timer/Scheduler, Inventory/Reservation, Authorization, Trust/Enforcement, Audit/History  
**Governing v5.0:** §32.6

**Rule:** "Materially prevented reliable recording" is evaluated from the booking's `accountAccessRestrictionIntervals[]` against the check-in window and the no-show window: a disable interval overlapping the window in which the missing evidence would have been recorded is what makes recording unreliable. Genuinely ambiguous cases resolve through admin correction, never an inferred outcome.

## BR-ATT-018 — Source `FR-ATT-18`

**Priority:** M  
**Actor / Surface:** Venue Staff / Customer Mobile App, Venue Dashboard  
**Rule Type:** Timer/Scheduler, Inventory/Reservation, Trust/Enforcement, Audit/History, UI/Presentation  
**Governing v5.0:** §32.7

**Rule:** Protection is **not** limited to sessions reaching normal `endAt`; it still applies when the session terminates early through a venue emergency, a resource emergency or owner review. The attendance result of an early termination remains governed by the normal interruption model — the protection event never changes it.

## BR-ATT-019 — Source `FR-ATT-19`

**Priority:** M  
**Actor / Surface:** System / Customer Mobile App, Venue Dashboard  
**Rule Type:** State/Lifecycle, Timer/Scheduler, Trust/Enforcement, Money/Points, Audit/History  
**Governing v5.0:** §32.8

**Rule:** The trust event `BOOKING_ACCOUNT_DISABLE_ATTENDANCE_PROTECTION` resets the consecutive no-show counter to 0 and does nothing else: no points, no progress, no qualification, no change to attendance result or booking status. `effectiveAt = booking.startAt`; `occurredAt` = attendance-resolution time.

## BR-ATT-020 — Source `FR-ATT-20`

**Priority:** M  
**Actor / Surface:** System / Customer Mobile App, Venue Dashboard  
**Rule Type:** Trust/Enforcement, Audit/History  
**Governing v5.0:** §32.9

**Rule:** The event is created **when the effective attendance outcome resolves**, not at disable time. At disable time the system records only a protection candidate and audit context.

## BR-ATT-021 — Source `FR-ATT-21`

**Priority:** M  
**Actor / Surface:** Platform Admin / Customer Mobile App, Venue Dashboard, Platform Admin  
**Rule Type:** Authorization, Audit/History  
**Governing v5.0:** §32.9

**Rule:** A booking may span several access intervals, stored as an ordered `accountAccessRestrictionIntervals[]` with `disabledAt`, nullable `reenabledAt`, admin actor or source, reason and correlation ID. All intervals are evaluated at resolution, and **at most one** effective protection event is emitted per booking.

## BR-ATT-022 — Source `FR-ATT-22`

**Priority:** M  
**Actor / Surface:** System / Customer Mobile App, Venue Dashboard  
**Rule Type:** Trust/Enforcement, Audit/History  
**Governing v5.0:** §32.12

**Rule:** If the booking ultimately produces ATTENDED or ATTENDED_INTERRUPTED, the protection event is **not** additionally applied — exactly one counter-reset mechanism per booking.

## BR-ATT-023 — Source `FR-ATT-23`

**Priority:** M  
**Actor / Surface:** System / Customer Mobile App, Venue Dashboard  
**Rule Type:** State/Lifecycle, Validation/Eligibility, Timer/Scheduler, Trust/Enforcement  
**Governing v5.0:** §32.10

**Rule:** Later evidence governs: a valid check-in after re-enable within the window → normal ATTENDED · a Late Present after re-enable → ATTENDANCE_UNCONFIRMED with the protection reset still applicable · a valid no-show after re-enable within the window → authoritative. **An expired check-in window is never retroactively reopened.**

## BR-ATT-024 — Source `FR-ATT-24`

**Priority:** M  
**Actor / Surface:** Customer, Platform Admin / Customer Mobile App, Venue Dashboard, Platform Admin  
**Rule Type:** Validation/Eligibility, Authorization, Trust/Enforcement  
**Governing v5.0:** §32.11

**Rule:** An admin correction to ABSENT_NO_SHOW **neutralises** the protection reset. No customer ever receives both a protected reset and a valid no-show increment for the same effective outcome.

## BR-ATT-025 — Source `FR-ATT-25`

**Priority:** M  
**Actor / Surface:** Customer / Customer Mobile App, Venue Dashboard  
**Rule Type:** Inventory/Reservation, Trust/Enforcement, Notification  
**Governing v5.0:** §32.13

**Rule:** When a disable overlaps an ACTIVE booking, the customer receives `ACTIVE_SESSION_ATTENDANCE_RECORDING_AFFECTED` (In-App + Push). The notification **must not** say the booking was cancelled unless another rule actually cancels it.

## BR-ATT-026 — Source `FR-ATT-26`

**Priority:** M  
**Actor / Surface:** Venue Staff, Platform Admin / Customer Mobile App, Venue Dashboard, Platform Admin  
**Rule Type:** Authorization, Trust/Enforcement  
**Governing v5.0:** §32.13

**Rule:** Platform Admin receives an operational alert with venue, Booking ID, session interval, disable time, current attendance evidence, and whether protection may be required.

## BR-ATT-027 — Source `FR-ATT-27`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff, Platform Admin / Customer Mobile App, Venue Dashboard, Platform Admin  
**Rule Type:** Timer/Scheduler, Authorization, Trust/Enforcement  
**Governing v5.0:** §27.10

**Rule:** Platform Admin receives **non-blocking** operational signals: unusually high check-in rate · high check-in reversal rate · unusual concentration of Verified Player progress from one venue · repeated check-ins immediately before the 15-minute deadline · high Late Present rate · repeated Late Present on the same users · Late Present reversals · suspicious repeated customer/venue patterns.

## BR-ATT-028 — Source `FR-ATT-28`

**Priority:** M  
**Actor / Surface:** Venue Staff, Platform Admin, System / Customer Mobile App, Venue Dashboard, Platform Admin  
**Rule Type:** State/Lifecycle, Validation/Eligibility, Authorization, Trust/Enforcement, UI/Presentation  
**Governing v5.0:** §27.10

**Rule:** Monitoring may flag, alert and surface for review. It must **never** automatically revoke Verified Player status, suspend a venue, reverse check-ins or ban users. Every consequence requires explicit admin action.

---

# 8. Manual Booking

## BR-MAN-001 — Source `FR-MAN-01`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff / Venue Dashboard  
**Rule Type:** Inventory/Reservation  
**Governing v5.0:** §30.1

**Rule:** Manual booking is an **internal venue operation** (category C) with `source = MANUAL`. It does not pass through PENDING, reserves the resource immediately, and may have no registered customer. Optional customer name and phone may be captured.

## BR-MAN-002 — Source `FR-MAN-02`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard  
**Rule Type:** State/Lifecycle, Validation/Eligibility, Timer/Scheduler, Inventory/Reservation, Authorization  
**Governing v5.0:** §30.2

**Rule:** Creation requires: venue account **ENABLED** · marketplace **ACTIVE, INACTIVE or SUSPENDED** — **ONBOARDING is explicitly not allowed** · resource base state ACTIVE · operational restriction NONE · no venue or resource selling block · resource supports the activity · no blocking reservation · no overlap with closure, emergency closure, resource emergency outage or maintenance · start NOW or FUTURE · duration 1–6 hours in 30-minute increments · required pricing configuration present.

## BR-MAN-003 — Source `FR-MAN-03`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard  
**Rule Type:** State/Lifecycle, Timer/Scheduler, Configuration  
**Governing v5.0:** §30.3

**Rule:** Manual booking **does not require the interval to fit published opening hours**, and area marketplace status does not block it.

## BR-MAN-004 — Source `FR-MAN-04`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff / Venue Dashboard  
**Rule Type:** State/Lifecycle, Timer/Scheduler  
**Governing v5.0:** §30.4

**Rule:** Reachable states: future → CONFIRMED → ACTIVE → COMPLETED · current walk-in with `startAt` = server time → ACTIVE → COMPLETED · before start → CONFIRMED → CANCELLED. Manual bookings never use PENDING, VENUE_RESPONSE_EXPIRED, AWAITING_CUSTOMER_CONFIRMATION, REJECTED, NO_SHOW or check-in.

## BR-MAN-005 — Source `FR-MAN-05`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard  
**Rule Type:** State/Lifecycle, Audit/History  
**Governing v5.0:** §30.5

**Rule:** **No back-dating.** A manual booking may never be created over a historical interval, even where the original occupant is now terminal.

## BR-MAN-006 — Source `FR-MAN-06`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard  
**Rule Type:** Trust/Enforcement, Money/Points  
**Governing v5.0:** §30.6

**Rule:** Manual bookings contribute nothing to Verified Player progress, completed-booking points or no-show progression. Attendance is NOT_APPLICABLE, including when an ACTIVE manual booking is terminated operationally.

## BR-MAN-007 — Source `FR-MAN-07`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff / Venue Dashboard  
**Rule Type:** Functional rule  
**Governing v5.0:** §30.7

**Rule:** Manual bookings do not count against the customer's 4-commitment limit.

## BR-MAN-008 — Source `FR-MAN-08`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff, Venue Owner authorization / Venue Dashboard  
**Rule Type:** Authorization, Money/Points  
**Governing v5.0:** §30.8

**Rule:** Venue staff may cancel a manual booking **without** the Owner Password, reason `VENUE_CANCELLED_MANUAL_BOOKING`. Its booking-fee treatment is **NOT_APPLICABLE**, because it carries no customer E7GZ fee.

## BR-MAN-009 — Source `FR-MAN-09`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard  
**Rule Type:** Timer/Scheduler, Audit/History  
**Governing v5.0:** §30.9

**Rule:** A manual booking inside a published session stores the normal operating-session snapshot and `businessDate` = operating day date. Outside published hours, session fields are NULL, timezone is Africa/Cairo, and `businessDate` = the Africa/Cairo local calendar date of `startAt`. **Every manual booking always has a `businessDate`.**

## BR-MAN-010 — Source `FR-MAN-10`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard  
**Rule Type:** Validation/Eligibility, Inventory/Reservation, Audit/History, Configuration  
**Governing v5.0:** §30.2

**Rule:** Missing pricing configuration blocks creation with the API/validation error `MANUAL_BOOKING_PRICING_CONFIGURATION_MISSING`. No reservation and no booking record are created, so the token is neither a reason code nor a booking event.

## BR-MAN-011 — Source `FR-MAN-11`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard  
**Rule Type:** UI/Presentation  
**Governing v5.0:** §30.10, §57.2

**Rule:** There is **no additional product maximum future horizon** for manual booking beyond the date range the Dashboard supports. The product rule remains **NOW or FUTURE, never past**. A technical date-picker bound may be applied consistently as a UI matter without changing the product rule.

---

# 9. Venue Model, Configuration and Dashboard

## BR-VEN-001 — Source `FR-VEN-01`

**Priority:** M  
**Actor / Surface:** Venue Staff, Venue Owner authorization / Venue Dashboard, Platform Admin  
**Rule Type:** Functional rule  
**Governing v5.0:** §10

**Rule:** One shared venue account per venue. No individual cashier, manager or separate owner accounts.

## BR-VEN-002 — Source `FR-VEN-02`

**Priority:** M  
**Actor / Surface:** Venue Staff, Venue Owner authorization / Venue Dashboard, Platform Admin  
**Rule Type:** Validation/Eligibility, Timer/Scheduler, Authorization  
**Governing v5.0:** §54.3

**Rule:** The Owner Password is required **per protected action**. There is no 15-minute unlock, no 30-minute unlock and no owner session mode; each action revalidates.

## BR-VEN-003 — Source `FR-VEN-03`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff, Venue Owner authorization / Venue Dashboard, Platform Admin  
**Rule Type:** Timer/Scheduler, Inventory/Reservation, Authorization, Money/Points, UI/Presentation  
**Governing v5.0:** §54.1

**Rule:** Owner Password is **required** for: venue pricing changes · room price override set and reset · cancelling a CONFIRMED online booking · an opening-hours save that cancels protected customer bookings · a normal-closure save that cancels protected customer bookings · disable, re-enable, archive and restore resource · console change · protected resource configuration where defined · maintenance exceeding the rolling threshold · venue cancellation of a CONFIRMED tournament · tournament operational termination · resolving `OWNER_REVIEW_REQUIRED`.

## BR-VEN-004 — Source `FR-VEN-04`

**Priority:** M  
**Actor / Surface:** Venue Staff, Venue Owner authorization, System / Venue Dashboard, Platform Admin  
**Rule Type:** Timer/Scheduler, Inventory/Reservation, Authorization, Trust/Enforcement, Audit/History  
**Governing v5.0:** §54.2

**Rule:** Owner Password is **not required** for: accept · reject · reject after expiry · late confirm · manual booking creation and pre-start cancellation · check-in · Mark Present — Late · no-show · maintenance within the threshold · opening-hours or closure edits with no protected cancellation · emergency closure · resource emergency outage · venue cancellation of a WAITING_FOR_PLAYERS tournament · any system operational consequence cascade. All remain audited.

## BR-VEN-005 — Source `FR-VEN-05`

**Priority:** M  
**Actor / Surface:** Venue Staff, Venue Owner authorization, Platform Admin / Venue Dashboard, Platform Admin  
**Rule Type:** Validation/Eligibility, Authorization, Audit/History  
**Governing v5.0:** §54.3

**Rule:** Successful and failed Owner Password attempts are audited; validation is rate-limited. A Platform Admin reset notifies the venue.

## BR-VEN-006 — Source `FR-VEN-46`

**Priority:** M  
**Actor / Surface:** Venue Staff, Venue Owner authorization, Platform Admin / Venue Dashboard, Platform Admin  
**Rule Type:** State/Lifecycle, Validation/Eligibility, Authorization, Audit/History  
**Governing v5.0:** §10.2

**Rule:** The venue may **change its own Owner Password**. The new password becomes active **immediately**, the old one stops working at once, and every subsequent protected action validates against the new one. The change is audited. This is distinct from a Platform Admin reset, which is covered by FR-ADM-03.

## BR-VEN-007 — Source `FR-VEN-06`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** State/Lifecycle, Authorization  
**Governing v5.0:** §11

**Rule:** Marketplace status (ONBOARDING / ACTIVE / INACTIVE / SUSPENDED) and account status (ENABLED / DISABLED) are **independent**. Disabling the account does not rewrite marketplace status, and suspending the marketplace does not disable the account.

## BR-VEN-008 — Source `FR-VEN-07`

**Priority:** M  
**Actor / Surface:** Venue Staff, Platform Admin / Venue Dashboard, Platform Admin  
**Rule Type:** Inventory/Reservation, Authorization, UI/Presentation, Configuration  
**Governing v5.0:** §11

**Rule:** ONBOARDING: not discoverable, no online booking, no player tournament requests, configuration may continue, not operational marketplace inventory. **INACTIVE**: hidden from discovery, no new online booking or tournament requests, Dashboard available, manual booking available, existing bookings and tournaments remain — non-punitive. **SUSPENDED**: as INACTIVE plus no new marketplace tournament creation, reviews remain visible and new reviews remain allowed — administrative and punitive.

## BR-VEN-009 — Source `FR-VEN-08`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** Trust/Enforcement, UI/Presentation  
**Governing v5.0:** §11, §32.1

**Rule:** Account **DISABLED** blocks Dashboard login and therefore every ordinary Dashboard action, including accept, reject, late confirm, check-in, Mark Present — Late and no-show. It is an **access restriction, not a physical venue outage**; existing commitments remain governed by their own lifecycle.

## BR-VEN-010 — Source `FR-VEN-09`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff, System / Venue Dashboard, Platform Admin  
**Rule Type:** State/Lifecycle, Timer/Scheduler, Audit/History  
**Governing v5.0:** §32.2

**Rule:** Effects of a disable by booking state: PENDING → VENUE_RESPONSE_EXPIRED immediately with event `VENUE_RESPONSE_INTERRUPTED_BY_ACCOUNT_DISABLE` · AWAITING_CUSTOMER_CONFIRMATION → CANCELLED `VENUE_ACCOUNT_DISABLED` · VENUE_RESPONSE_EXPIRED still disabled at `startAt` → CANCELLED `VENUE_ACCOUNT_DISABLED` · CONFIRMED unaffected and may still start · ACTIVE unaffected and continues.

## BR-VEN-011 — Source `FR-VEN-10`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** State/Lifecycle  
**Governing v5.0:** §32.3

**Rule:** `VENUE_ACCOUNT_DISABLED` applies only to an unresolved **pre-confirmation** response lifecycle. It is never a general CONFIRMED → ACTIVE cancellation reason.

## BR-VEN-012 — Source `FR-VEN-11`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** Timer/Scheduler  
**Governing v5.0:** §32.3

**Rule:** Marketplace SUSPENDED or INACTIVE creates **no venue blame**: it never causes `VENUE_DID_NOT_RESPOND_IN_TIME` and needs no new expiry reason.

## BR-VEN-013 — Source `FR-VEN-12`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff, Platform Admin / Venue Dashboard, Platform Admin  
**Rule Type:** State/Lifecycle, Authorization, Audit/History, Configuration  
**Governing v5.0:** §12

**Rule:** **Areas** are controlled by Platform Admin and carry status **Active** or **Inactive**. Only Active areas are available for new venue assignment. Inactive areas remain in historical data and historical references are never rewritten. Area status governs **customer discovery only** and never blocks manual booking.

## BR-VEN-014 — Source `FR-VEN-47`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** State/Lifecycle, Validation/Eligibility, Audit/History, Configuration  
**Governing v5.0:** §12, §51.11

**Rule:** An area may be deactivated **only** when no venue assigned to it has marketplace status ACTIVE or SUSPENDED. Such venues must first move area or become INACTIVE. Historical references are unchanged.

## BR-VEN-015 — Source `FR-VEN-13`

**Priority:** M  
**Actor / Surface:** Venue Staff, Venue Owner authorization / Venue Dashboard, Platform Admin  
**Rule Type:** State/Lifecycle, Inventory/Reservation, UI/Presentation  
**Governing v5.0:** §13

**Rule:** Resource Base State (ACTIVE / DISABLED / ARCHIVED) is separate from Resource Operational Restriction (NONE / OWNER_REVIEW_REQUIRED). A resource may be ACTIVE and still unsellable.

## BR-VEN-016 — Source `FR-VEN-14`

**Priority:** M  
**Actor / Surface:** Venue Staff, System / Venue Dashboard, Platform Admin  
**Rule Type:** Validation/Eligibility, Inventory/Reservation, Configuration  
**Governing v5.0:** §13.4

**Rule:** Disable, Archive and Console Change are **blocked** by any current or future blocking reservation — CONFIRMED, ACTIVE, PENDING, AWAITING, Player Tournament Approval Hold, Tournament Hold or resource Maintenance. Existing reservations are **never** automatically cancelled; the venue must resolve them first. A normal venue closure alone does not block these actions, and `VENUE_RESPONSE_EXPIRED` does not block them.

## BR-VEN-017 — Source `FR-VEN-15`

**Priority:** M  
**Actor / Surface:** Venue Staff, Venue Owner authorization / Venue Dashboard, Platform Admin  
**Rule Type:** Validation/Eligibility, Authorization, Money/Points, Audit/History, Configuration  
**Governing v5.0:** §13.5

**Rule:** A console change (PS4 ↔ PS5) requires the Owner Password and **clears all existing room price overrides**; the room returns to venue default pricing for the new console. Historical bookings retain their stored console, mode and price.

## BR-VEN-018 — Source `FR-VEN-16`

**Priority:** M  
**Actor / Surface:** Venue Staff, Venue Owner authorization / Venue Dashboard, Platform Admin  
**Rule Type:** Inventory/Reservation, Audit/History, Configuration  
**Governing v5.0:** §53.3

**Rule:** Resource disable, re-enable, archive and restore are audited. Maintenance is not owner-protected within the threshold, and is audited.

## BR-VEN-019 — Source `FR-VEN-41`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** Inventory/Reservation  
**Governing v5.0:** §3.2

**Rule:** Resource naming is fixed: PlayStation resources are **Room 1, Room 2, …** · Billiards resources are **Pool Table 1, Pool Table 2, …** · Ping Pong resources are **Table 1, Table 2, …**. Supported PlayStation console types are **PS5 and PS4**, and only these. The console type is shown **wherever resource information appears**, across all three products.

## BR-VEN-020 — Source `FR-VEN-42`

**Priority:** M  
**Actor / Surface:** Venue Staff, System / Venue Dashboard, Platform Admin  
**Rule Type:** Validation/Eligibility, Configuration  
**Governing v5.0:** §3.3

**Rule:** PlayStation requires a play mode of **Single** or **Multiplayer**. This is a **pricing mode, not an exact player count**, and the system must never validate, capture or enforce a party size against it. Play mode does **not** apply to Billiards or Ping Pong.

## BR-VEN-021 — Source `FR-VEN-43`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** Money/Points, Configuration  
**Governing v5.0:** §4.1

**Rule:** **PlayStation pricing** is configured as **venue-level defaults per console type × play mode** — PS5 Single, PS5 Multiplayer, PS4 Single, PS4 Multiplayer. A PlayStation room may carry a **Room Price Override** that replaces the venue default for that room; **with no override the room inherits the venue console default**.

## BR-VEN-022 — Source `FR-VEN-44`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** Timer/Scheduler, Inventory/Reservation, Money/Points  
**Governing v5.0:** §4.2

**Rule:** **Billiards and Ping Pong** are priced at a **venue-level hourly rate only** — no console type, no play mode, and **no table-level override** in this release.

## BR-VEN-023 — Source `FR-VEN-45`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** Validation/Eligibility, Money/Points, Configuration  
**Governing v5.0:** §4.1, §4.2, §30.2

**Rule:** Manual booking, online booking and tournament all price from this same configuration, and creation is **blocked** where the configuration required for the activity does not exist.

## BR-VEN-024 — Source `FR-VEN-17`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** Timer/Scheduler, Configuration  
**Governing v5.0:** §14

**Rule:** Opening hours use a default schedule plus weekday overrides; each weekday may use the default, use custom hours or be closed. Cross-midnight schedules are supported.

## BR-VEN-025 — Source `FR-VEN-18`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** Validation/Eligibility, Timer/Scheduler, Inventory/Reservation  
**Governing v5.0:** §14

**Rule:** Each **operating day** creates at most **one operating session**, identified by operating day, local start, local end and the Africa/Cairo timezone. Session length is greater than 0 and no more than 24 hours. Sessions may touch at boundaries but **may not overlap**.

## BR-VEN-026 — Source `FR-VEN-19`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** State/Lifecycle, Validation/Eligibility, Timer/Scheduler, Inventory/Reservation  
**Governing v5.0:** §14

**Rule:** Non-overlap validation is **cyclical across the full week**, including the Sunday → Monday wrap. A cross-midnight session may not overlap the next session.

## BR-VEN-027 — Source `FR-VEN-20`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** Timer/Scheduler, Audit/History  
**Governing v5.0:** §14

**Rule:** A closed weekday prevents a **new** session starting that day, but an overnight session from the previous day legitimately continues into it.

## BR-VEN-028 — Source `FR-VEN-21`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** Validation/Eligibility, Timer/Scheduler, Inventory/Reservation  
**Governing v5.0:** §14.4, §14.3

**Rule:** DST: non-existent local start times are not offered; ambiguous repeated local times are not offered as duplicate slots; a declared full-day session stays valid when real elapsed time is 23 or 25 hours; absolute instants remain authoritative for inventory.

## BR-VEN-029 — Source `FR-VEN-22`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** Timer/Scheduler, Audit/History  
**Governing v5.0:** §14

**Rule:** Booking history stores the operating day, local session start, local session end and timezone as a **historical snapshot**; later opening-hours changes never rewrite it.

## BR-VEN-030 — Source `FR-VEN-23`

**Priority:** M  
**Actor / Surface:** Venue Staff, Venue Owner authorization / Venue Dashboard, Platform Admin  
**Rule Type:** Validation/Eligibility, Timer/Scheduler, Inventory/Reservation, Authorization, Notification  
**Governing v5.0:** §14.7

**Rule:** An opening-hours change is **all-or-nothing** and evaluates in this order: **1.** validate proposed sessions · **2.** check tournament conflicts · **3.** block immediately on any tournament conflict · **4.** compute affected ordinary reservations · **5.** show a complete impact preview · **6.** determine the Owner Password requirement · **7.** request the Owner Password where protected cancellations exist · **8.** commit the change and all cancellations atomically · **9.** notify. If any step fails, **nothing changes**.

## BR-VEN-031 — Source `FR-VEN-24`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** Inventory/Reservation  
**Governing v5.0:** §14.8

**Rule:** Affected reservations are future PENDING, AWAITING_CUSTOMER_CONFIRMATION, CONFIRMED and MANUAL. ACTIVE sessions are not cancelled. VENUE_RESPONSE_EXPIRED holds no reservation and is not cancelled.

## BR-VEN-032 — Source `FR-VEN-25`

**Priority:** M  
**Actor / Surface:** Venue Staff, Venue Owner authorization / Venue Dashboard, Platform Admin  
**Rule Type:** Validation/Eligibility, Timer/Scheduler, Authorization, Configuration  
**Governing v5.0:** §14.9, §14.8

**Rule:** Editing opening hours needs no Owner Password, but a save that cancels a CONFIRMED online booking or an AWAITING booking requires it. Pending online and manual cancellations alone do not. Cancellation reason: `VENUE_OPENING_HOURS_CHANGED`.

## BR-VEN-033 — Source `FR-VEN-26`

**Priority:** M  
**Actor / Surface:** Venue Staff, Venue Owner authorization / Venue Dashboard, Platform Admin  
**Rule Type:** Validation/Eligibility, Inventory/Reservation, Authorization, UI/Presentation  
**Governing v5.0:** §14.7

**Rule:** Before commit the complete affected set is **revalidated**. If new affected reservations appeared since the preview, the commit is **aborted** and a refreshed preview is shown; any Owner Password must be entered against the refreshed set. **No booking outside the approved preview may be silently cancelled.**

## BR-VEN-034 — Source `FR-VEN-27`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** Inventory/Reservation  
**Governing v5.0:** §14.10

**Rule:** A tournament conflict **blocks** the save, including a PENDING_VENUE_APPROVAL tournament holding approval holds. The venue must resolve or cancel the tournament first.

## BR-VEN-035 — Source `FR-VEN-28`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** Timer/Scheduler, Inventory/Reservation  
**Governing v5.0:** §14.9

**Rule:** An AWAITING booking cancelled by an hours change stops its Late Confirmation timer immediately, releases the hold, and notifies the customer explicitly.

## BR-VEN-036 — Source `FR-VEN-29`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** Timer/Scheduler, Inventory/Reservation, Configuration  
**Governing v5.0:** §15

**Rule:** A closure may be full-day or partial-time, applies to the whole venue, and blocks all resources for its interval.

## BR-VEN-037 — Source `FR-VEN-30`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff, Venue Owner authorization / Venue Dashboard, Platform Admin  
**Rule Type:** Validation/Eligibility, Inventory/Reservation, Authorization, Configuration  
**Governing v5.0:** §15

**Rule:** Creating or editing a closure needs no Owner Password unless it cancels protected customer reservations, in which case the final **Save & Cancel Affected Bookings** requires it. Cancellation reason `VENUE_CLOSURE`; customers are notified.

## BR-VEN-038 — Source `FR-VEN-31`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** Validation/Eligibility, Configuration  
**Governing v5.0:** §15

**Rule:** A closure may **not** be created retroactively over an ACTIVE session, and must begin after existing active sessions end unless those have been resolved through an allowed flow.

## BR-VEN-039 — Source `FR-VEN-32`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** Configuration  
**Governing v5.0:** §15

**Rule:** A proposed closure conflicting with an existing tournament **blocks the save**.

## BR-VEN-040 — Source `FR-VEN-33`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** Validation/Eligibility, Inventory/Reservation, Configuration  
**Governing v5.0:** §39.4

**Rule:** A closure may overlap existing maintenance; creating maintenance over an existing closure is blocked for the overlapping period.

## BR-VEN-041 — Source `FR-VEN-34`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** Timer/Scheduler, Inventory/Reservation, Money/Points, Audit/History, UI/Presentation  
**Governing v5.0:** §2.2, §50.1

**Rule:** Dashboard modules: Overview · Pending Requests · Missed / Expired Requests · **Accept / Reject** · **Late Confirmation** · Bookings · Manual Booking · Rooms / Resource Management · Pricing · Availability · Maintenance & Closures · **Opening Hours** · Reviews (view only) · Tournaments · Booking History · **Revenue Summary** · Settings, in English and العربية. §50.1 names the core module set; §2.2 names the full launch surface set and both are frozen.

## BR-VEN-042 — Source `FR-VEN-35`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** Timer/Scheduler, Money/Points, UI/Presentation  
**Governing v5.0:** §50.2

**Rule:** Overview shows today's revenue, this month's revenue, today's bookings, pending requests, today's schedule, quick manual booking and quick create tournament.

## BR-VEN-043 — Source `FR-VEN-36`

**Priority:** M  
**Actor / Surface:** Venue Staff, System / Venue Dashboard, Platform Admin  
**Rule Type:** Money/Points, UI/Presentation  
**Governing v5.0:** §50.3

**Rule:** Revenue covers online, manual and tournament revenue with an optional activity breakdown, aggregated on **`businessDate`**. The Dashboard is explicitly not a POS, accounting system, ERP, tax system or reconciliation system, and tracks no payment method.

## BR-VEN-044 — Source `FR-VEN-37`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** Inventory/Reservation, UI/Presentation, Configuration  
**Governing v5.0:** §50.4

**Rule:** The availability schedule displays Free · Pending · Awaiting Customer Confirmation · Confirmed · Active · Manual · Tournament Hold · Maintenance · Closure.

## BR-VEN-045 — Source `FR-VEN-38`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** State/Lifecycle, Timer/Scheduler, Inventory/Reservation, Audit/History  
**Governing v5.0:** §50.5

**Rule:** Booking history filters: date range (today, yesterday, last 7 days, this month, custom) · activity · source · resource · status · search.

## BR-VEN-046 — Source `FR-VEN-39`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** UI/Presentation  
**Governing v5.0:** §49.7

**Rule:** The venue is **view only** on reviews, seeing average rating, total reviews, rating distribution, and per review the username, rating, comment and date.

## BR-VEN-047 — Source `FR-VEN-40`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** State/Lifecycle, UI/Presentation  
**Governing v5.0:** §50.6

**Rule:** Full English (LTR) and العربية (RTL) support across every listed surface element. Free-text stored data is unchanged. Language resolves: venue account preference → browser storage → English default.

---

# 10. Maintenance, Emergencies and Operational Restrictions

## BR-OPS-001 — Source `FR-OPS-01`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** Inventory/Reservation, Audit/History, Configuration  
**Governing v5.0:** §39.1

**Rule:** Maintenance is normal venue operation at resource level. Create, edit and delete are audited.

## BR-OPS-002 — Source `FR-OPS-02`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** Validation/Eligibility, Inventory/Reservation, Configuration  
**Governing v5.0:** §39.1

**Rule:** Maintenance may **never** overlap an existing blocking reservation; creation is blocked with a clear message and the reservations must be resolved through their normal flow. **Maintenance may never silently cancel a booking.**

## BR-OPS-003 — Source `FR-OPS-03`

**Priority:** M  
**Actor / Surface:** Venue Staff, Venue Owner authorization / Venue Dashboard, Platform Admin  
**Rule Type:** Timer/Scheduler, Inventory/Reservation, Authorization, Configuration  
**Governing v5.0:** §39.2

**Rule:** Owner Password is required when total maintenance for a resource exceeds **24 hours inside any rolling 48-hour window**. Back-to-back or overlapping windows forming one continuous period over 24 hours count as one period. The threshold is **per resource**.

## BR-OPS-004 — Source `FR-OPS-04`

**Priority:** M  
**Actor / Surface:** Venue Staff, Venue Owner authorization / Venue Dashboard, Platform Admin  
**Rule Type:** Validation/Eligibility, Inventory/Reservation, Authorization  
**Governing v5.0:** §39.2

**Rule:** An edit that extends a continuous period past the threshold requires the Owner Password.

## BR-OPS-005 — Source `FR-OPS-05`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** Inventory/Reservation, Configuration  
**Governing v5.0:** §39.3

**Rule:** Maintenance created from a resource emergency outage **counts** toward the same rolling total. There is no bypass between emergency-generated and manual maintenance.

## BR-OPS-006 — Source `FR-OPS-06`

**Priority:** M  
**Actor / Surface:** Venue Staff, Venue Owner authorization, Platform Admin / Venue Dashboard, Platform Admin  
**Rule Type:** Validation/Eligibility, Authorization, Audit/History, Configuration  
**Governing v5.0:** §40.1

**Rule:** Emergency Closure requires **no Owner Password**, may start only **NOW**, cannot be scheduled, requires a mandatory reason and full audit, and notifies the venue owner and Platform Admin.

## BR-OPS-007 — Source `FR-OPS-07`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** Timer/Scheduler, Configuration  
**Governing v5.0:** §40.2

**Rule:** Expected End Time is **mandatory**, later than start and no later than the end of the current operating session. Longer outages must use a normal closure, an opening-hours change or another future mechanism.

## BR-OPS-008 — Source `FR-OPS-08`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** Inventory/Reservation, Configuration  
**Governing v5.0:** §40.3, §40.4

**Rule:** The cascade covers only reservations overlapping `[emergencyStart, expectedEnd)`. It stops new online and manual bookings for the period, cancels affected PENDING, AWAITING and CONFIRMED bookings, terminates affected ACTIVE sessions, cancels affected tournaments and releases future holds, under `VENUE_EMERGENCY_CLOSURE`.

## BR-OPS-009 — Source `FR-OPS-09`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** Inventory/Reservation, Notification, Audit/History, Configuration  
**Governing v5.0:** §40.5

**Rule:** The cascade is **all-or-nothing**: the closure, cancellations, active terminations, tournament cancellations, releases and history persist atomically **before** notifications dispatch. A partial cascade is not an acceptable outcome.

## BR-OPS-010 — Source `FR-OPS-10`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** Functional rule  
**Governing v5.0:** §40.6

**Rule:** Emergency cancellation is immediate and irreversible; ending the emergency early never restores what was cancelled.

## BR-OPS-011 — Source `FR-OPS-44`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** Timer/Scheduler  
**Governing v5.0:** §40.4

**Rule:** An AWAITING_CUSTOMER_CONFIRMATION booking caught by an emergency cascade is cancelled **immediately under the emergency reason**. Its Late Confirmation timer stops and the customer **never** receives `CUSTOMER_RECONFIRM_TIMEOUT`: the emergency reason wins.

## BR-OPS-012 — Source `FR-OPS-11`

**Priority:** M  
**Actor / Surface:** Venue Staff, Venue Owner authorization, Platform Admin / Venue Dashboard, Platform Admin  
**Rule Type:** Validation/Eligibility, Inventory/Reservation, Authorization, Notification, Audit/History  
**Governing v5.0:** §41.2, §41.4, §39.2

**Rule:** A resource emergency outage requires **no Owner Password** and carries a mandatory reason, timestamp, affected resource, venue account identity, full audit, owner notification and Platform Admin visibility. Staff must supply an **expected return-to-service time**. For an **unprotected** emergency outage this may extend only to the end of the current operating session; a longer resource outage must be carried as **maintenance under normal maintenance authorization**, including the Owner Password where the rolling threshold is exceeded.

## BR-OPS-013 — Source `FR-OPS-12`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** Inventory/Reservation, Configuration  
**Governing v5.0:** §41.3

**Rule:** It may terminate the ACTIVE booking on that resource, cancel future overlapping reservations, affect tournament use, block new availability and transition the resource into maintenance — under `RESOURCE_EMERGENCY_OUTAGE`. Other venue resources keep operating.

## BR-OPS-014 — Source `FR-OPS-13`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** Functional rule  
**Governing v5.0:** §41.2

**Rule:** There is **no hard venue-wide emergency rate limit**, because such a limit could block a genuine emergency.

## BR-OPS-015 — Source `FR-OPS-14`

**Priority:** M  
**Actor / Surface:** Venue Staff, Venue Owner authorization, Platform Admin / Venue Dashboard, Platform Admin  
**Rule Type:** Validation/Eligibility, Timer/Scheduler, Inventory/Reservation, Authorization  
**Governing v5.0:** §41.6

**Rule:** Platform Admin receives a risk alert when one venue causes **3 or more tournament cancellations through resource emergency outage within a rolling 30 days**. The alert is investigative and non-blocking; it never suspends the venue, requires no Owner Password for future emergencies, and reverses nothing.

## BR-OPS-016 — Source `FR-OPS-15`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** State/Lifecycle, Inventory/Reservation  
**Governing v5.0:** §38.2, §38.3

**Rule:** A relevant emergency **must** create the corresponding operational selling block — venue emergency → venue block; resource emergency → that resource's block only. It is mandatory, not optional.

## BR-OPS-017 — Source `FR-OPS-16`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** State/Lifecycle, Inventory/Reservation, Audit/History  
**Governing v5.0:** §38.4

**Rule:** A selling block prevents new online bookings, new manual bookings, new tournament holds and approval holds, Late Confirm requiring the affected venue or resource, venue accept, and CONFIRMED → ACTIVE — for the affected venue or resource.

## BR-OPS-018 — Source `FR-OPS-17`

**Priority:** M  
**Actor / Surface:** Venue Staff, System / Venue Dashboard, Platform Admin  
**Rule Type:** Inventory/Reservation  
**Governing v5.0:** §38.5

**Rule:** Reservations **outside** the declared emergency interval are not automatically cancelled when the emergency enters AWAITING_CONFIRMATION. A block may persist across a session end and into the next session without extending the earlier cascade.

## BR-OPS-019 — Source `FR-OPS-18`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** State/Lifecycle  
**Governing v5.0:** §42.1, §42.2

**Rule:** Emergency status is ACTIVE → (expected end passes) → AWAITING_CONFIRMATION → ENDED or CONVERTED. Blocks are ON for ACTIVE and AWAITING_CONFIRMATION. **Expected end never auto-reopens anything**; staff resolution is mandatory.

## BR-OPS-020 — Source `FR-OPS-19`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** State/Lifecycle, Validation/Eligibility, Inventory/Reservation  
**Governing v5.0:** §42.3

**Rule:** On ENDED the emergency selling block **must** end. Staff may only end an emergency when the venue or resource is operational again, or when a valid replacement operational state or block commits atomically.

## BR-OPS-021 — Source `FR-OPS-20`

**Priority:** M  
**Actor / Surface:** Venue Staff, Venue Owner authorization / Venue Dashboard, Platform Admin  
**Rule Type:** Inventory/Reservation, UI/Presentation, Configuration  
**Governing v5.0:** §42.3

**Rule:** If a resource is still unusable but below the owner-review threshold, the emergency **must not** move to ENDED with no replacement block; it stays AWAITING_CONFIRMATION until repair, or a successful maintenance conversion, disable or archive.

## BR-OPS-022 — Source `FR-OPS-21`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** Validation/Eligibility, Inventory/Reservation, Audit/History  
**Governing v5.0:** §38.6

**Rule:** **No sellable gap**: there must never be a period where the emergency block has ended, the resource is still unusable, and no replacement restriction exists. The enforceable guarantee is that E7GZ will not release the block unless staff assert the resource is operational or a replacement commits. END EMERGENCY is that audited, attributable staff assertion.

## BR-OPS-023 — Source `FR-OPS-22`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** Validation/Eligibility, Inventory/Reservation, UI/Presentation  
**Governing v5.0:** §42.4

**Rule:** Extending an emergency computes the newly affected interval and reservations, previews the impact, takes confirmation, and applies the new cancellations atomically. Previously cancelled reservations stay cancelled. Emergency mode may not be extended beyond the current operating session as an unprotected cancellation mechanism.

## BR-OPS-024 — Source `FR-OPS-23`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** State/Lifecycle, Validation/Eligibility, Timer/Scheduler, Inventory/Reservation, Configuration  
**Governing v5.0:** §42.5

**Rule:** Conversion targets: venue emergency → normal closure or opening-hours change; resource emergency → maintenance, disabled or archived. A **resource emergency may not convert directly into an opening-hours change**. Each conversion obeys the target action's own authorization and impact rules and never bypasses the resource state-change predicate.

## BR-OPS-025 — Source `FR-OPS-24`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** Inventory/Reservation  
**Governing v5.0:** §42.5

**Rule:** On CONVERTED, the emergency block ends **at the same atomic commit** that activates the replacement. No availability gap is allowed.

## BR-OPS-026 — Source `FR-OPS-25`

**Priority:** M  
**Actor / Surface:** Venue Staff, Venue Owner authorization / Venue Dashboard, Platform Admin  
**Rule Type:** UI/Presentation  
**Governing v5.0:** §42.7

**Rule:** The Dashboard and owner must be notified when an expected end is reached with the emergency still active, when a return-to-service time is reached with the outage still active, and when manual confirmation is required — so operational blocks are not forgotten.

## BR-OPS-027 — Source `FR-OPS-42`

**Priority:** M  
**Actor / Surface:** Venue Staff, Venue Owner authorization / Venue Dashboard, Platform Admin  
**Rule Type:** State/Lifecycle, Inventory/Reservation, Authorization, Configuration  
**Governing v5.0:** §13.6, §42.8

**Rule:** An active emergency closure or resource emergency outage blocks **availability** but does **not** by itself forbid Disable, Archive or Console Change, provided the Owner Password requirement passes, all remaining future reservations are resolved, and applicable maintenance restrictions pass. Where a resource emergency has created maintenance, that **maintenance** becomes the continuing resource-level block and normal state-change rules evaluate the maintenance interval.

## BR-OPS-028 — Source `FR-OPS-43`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** State/Lifecycle, Validation/Eligibility, Inventory/Reservation, UI/Presentation, Configuration  
**Governing v5.0:** §42.6, §31.12

**Rule:** Where a resource cannot return to service but still holds **future protected reservations**, the operational selling block **remains** and the venue must resolve those commitments through the normal flows before Disable, Archive or extended Maintenance becomes available. The Dashboard must not present an unavailable conversion as executable: it displays the blocking commitments and states that they must be resolved first.

## BR-OPS-029 — Source `FR-OPS-26`

**Priority:** M  
**Actor / Surface:** Venue Staff, Venue Owner authorization / Venue Dashboard, Platform Admin  
**Rule Type:** Timer/Scheduler, Inventory/Reservation, Authorization, UI/Presentation, Configuration  
**Governing v5.0:** §43.1

**Rule:** When a resource has exceeded 24 maintenance hours in a rolling 48-hour window, it enters `OWNER_REVIEW_REQUIRED` at the emergency's expected or actual end. A genuine new emergency may always still be declared immediately without the Owner Password.

## BR-OPS-030 — Source `FR-OPS-27`

**Priority:** M  
**Actor / Surface:** Venue Staff, Venue Owner authorization / Venue Dashboard, Platform Admin  
**Rule Type:** State/Lifecycle, Inventory/Reservation, UI/Presentation  
**Governing v5.0:** §43.2

**Rule:** The threshold is evaluated **before** ending an emergency. Ending an emergency can never return an over-threshold resource to sellable state: **owner review takes precedence over availability**.

## BR-OPS-031 — Source `FR-OPS-28`

**Priority:** M  
**Actor / Surface:** Venue Staff, Venue Owner authorization, System / Venue Dashboard, Platform Admin  
**Rule Type:** State/Lifecycle, Inventory/Reservation, UI/Presentation  
**Governing v5.0:** §43.3

**Rule:** Entry triggers a **system operational cascade** — not a manual venue cancellation — resolving atomically: PENDING, AWAITING, future CONFIRMED, ACTIVE and future manual bookings → CANCELLED `RESOURCE_OWNER_REVIEW_REQUIRED`; pre-start tournaments → CANCELLED with the same reason; an ACTIVE tournament loses that resource and is cancelled only if none remains.

## BR-OPS-032 — Source `FR-OPS-29`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff, Venue Owner authorization, Platform Admin, System / Venue Dashboard, Platform Admin  
**Rule Type:** Validation/Eligibility, Authorization, Audit/History  
**Governing v5.0:** §43.3, §43.4

**Rule:** The cascade requires **no Owner Password anywhere**, including the manual-booking and tournament cancellations, requires system audit, and notifies the venue owner, Platform Admin, affected customers and participants. **A partial cascade is not a valid outcome.**

## BR-OPS-033 — Source `FR-OPS-30`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff, Venue Owner authorization / Venue Dashboard, Platform Admin  
**Rule Type:** State/Lifecycle, Timer/Scheduler, UI/Presentation  
**Governing v5.0:** §43.5

**Rule:** Owner review is **not a pause state**: affected bookings are cancelled, not held, so customers are never left in indefinite commitments. A PENDING booking cancelled this way stops its timer immediately and never becomes VENUE_RESPONSE_EXPIRED or uses `VENUE_DID_NOT_RESPOND_IN_TIME`.

## BR-OPS-034 — Source `FR-OPS-31`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff, System / Venue Dashboard, Platform Admin  
**Rule Type:** Trust/Enforcement, Money/Points  
**Governing v5.0:** §43.5

**Rule:** An affected customer receives no penalty, an explicit operational reason, the ability to rebook normally, and a full future fee refund. No automatic replacement booking is created. Attendance is NOT_APPLICABLE with no trust effect.

## BR-OPS-035 — Source `FR-OPS-32`

**Priority:** M  
**Actor / Surface:** Venue Staff, Venue Owner authorization, System / Venue Dashboard, Platform Admin  
**Rule Type:** State/Lifecycle, Validation/Eligibility, Timer/Scheduler, Inventory/Reservation, UI/Presentation  
**Governing v5.0:** §43.6

**Rule:** While the restriction holds, the venue cannot initiate Late Confirm on that resource; the system records `LATE_CONFIRM_ELIGIBILITY_LOST_RESOURCE_STATE_CHANGED` with `detailCode = RESOURCE_OWNER_REVIEW_REQUIRED`. Clearing the restriction before `startAt` restores eligibility, but never triggers an automatic Late Confirm.

## BR-OPS-036 — Source `FR-OPS-33`

**Priority:** M  
**Actor / Surface:** Venue Staff, Venue Owner authorization / Venue Dashboard, Platform Admin  
**Rule Type:** Validation/Eligibility, Inventory/Reservation, Authorization, Configuration  
**Governing v5.0:** §43.8

**Rule:** Resolution requires the Owner Password and offers **Return to Service**, **Extend Maintenance**, **Disable Resource** or **Archive Resource**. Return to Service requires no blocking maintenance, no emergency selling block and an otherwise operational resource; it then sets `operationalRestriction = NONE`.

## BR-OPS-037 — Source `FR-OPS-34`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** Timer/Scheduler, Inventory/Reservation, Configuration  
**Governing v5.0:** §44.1

**Rule:** The **venue response timer** on a PENDING booking pauses while a venue emergency closure, **or** a resource emergency outage affecting that booking's resource, is in force. A resource emergency on any other resource pauses nothing. While a relevant block is active, Venue Accept is disabled.

## BR-OPS-038 — Source `FR-OPS-35`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** Inventory/Reservation  
**Governing v5.0:** §44.1

**Rule:** The **customer Late Confirmation countdown** pauses while an active emergency or selling block affects the venue **or** the booked resource, and the booking has not already been cancelled by the emergency cascade.

## BR-OPS-039 — Source `FR-OPS-36`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** Timer/Scheduler  
**Governing v5.0:** §44.1

**Rule:** Remaining duration is **preserved** across a pause: a 10-minute timer with 3 minutes elapsed resumes with 7 minutes remaining.

## BR-OPS-040 — Source `FR-OPS-37`

**Priority:** M  
**Actor / Surface:** Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** Timer/Scheduler, Inventory/Reservation, Trust/Enforcement  
**Governing v5.0:** §44.2

**Rule:** Two **separate** cumulative caps of **30 minutes** each: venue-response emergency pause per booking, and Late Confirmation emergency pause per AWAITING phase. Venue and resource emergency pauses contribute to the **same** response-pause counter, so switching emergency type can never reset the clock. Repeated pauses accumulate and never reset. Stored per booking: `responsePauseStartedAt`, `cumulativeEmergencyPauseDuration`, `lateConfirmPauseStartedAt`, `cumulativeLateConfirmEmergencyPauseDuration`.

## BR-OPS-041 — Source `FR-OPS-38`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** State/Lifecycle, Inventory/Reservation, Trust/Enforcement  
**Governing v5.0:** §44.3

**Rule:** On reaching a cap: venue response under a venue-level emergency → PENDING CANCELLED `VENUE_RESPONSE_INTERRUPTED_BY_EMERGENCY` · venue response under a resource-level emergency → PENDING CANCELLED `RESOURCE_RESPONSE_INTERRUPTED_BY_EMERGENCY` · Late Confirmation → AWAITING CANCELLED `LATE_CONFIRM_INTERRUPTED_BY_EMERGENCY`. In every case the reservation or temporary hold releases, the customer commitment slot releases, the customer is notified, no customer penalty applies and **no venue non-response blame is recorded**.

## BR-OPS-042 — Source `FR-OPS-39`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** Validation/Eligibility, Timer/Scheduler, Inventory/Reservation, Authorization  
**Governing v5.0:** §44.4

**Rule:** A paused venue response timer resumes only when **all** hold: the emergency has ended · venue account ENABLED · venue marketplace permits the action · the venue is inside a valid operating session · the current time is inside the online intake window. Ending an emergency while the venue is closed does not restart the timer; it waits for the **next** valid intake window. A paused Late Confirmation countdown resumes only when the affecting emergency or block is resolved, the booking is still before `startAt`, and venue, resource and customer all remain eligible.

## BR-OPS-043 — Source `FR-OPS-40`

**Priority:** M  
**Actor / Surface:** Venue Staff, System / Venue Dashboard, Platform Admin  
**Rule Type:** State/Lifecycle, Validation/Eligibility, Timer/Scheduler  
**Governing v5.0:** §44.5

**Rule:** Early cancellation is deterministic through the **optimistic feasibility test**: assuming the current selling block could resolve immediately, could the remaining venue-response duration fit inside a valid future online intake window before `startAt`? The system evaluates the remaining response duration, future valid operating sessions, the final-10-minute intake cutoff, `startAt` and the active selling block. **No such window even under immediate resolution** → cancel now under the applicable interrupted-by-emergency reason. **A window could exist but the emergency is unresolved** → the booking stays paused only until the 30-minute cap, which is the deterministic outer bound. Block duration is never predicted by the scheduler.

## BR-OPS-044 — Source `FR-OPS-41`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff / Venue Dashboard, Platform Admin  
**Rule Type:** Inventory/Reservation, Audit/History  
**Governing v5.0:** §44.6, §44.7, §29.3

**Rule:** The customer is notified when a Late Confirmation countdown is paused, and sees *"Confirmation temporarily paused due to venue/resource issue."* On resume the remaining time is shown. **While paused the customer may still Decline**, which takes effect immediately under `CUSTOMER_DECLINED_LATE_CONFIRM`. Both pause records are immutable operational history storing `pauseStartedAt`, `pauseEndedAt`, `pauseCause` and `accumulatedDuration`.

---

# 11. Tournaments

## BR-TRN-001 — Source `FR-TRN-01`

**Priority:** M  
**Actor / Surface:** System / Customer Mobile App, Venue Dashboard  
**Rule Type:** Timer/Scheduler, Audit/History  
**Governing v5.0:** §36.1

**Rule:** Tournaments are Day-1 across PlayStation, Billiards and Ping Pong, and are a **separate entity from a booking**. Booking history and tournament history are separate; a tournament is never modelled as a booking.

## BR-TRN-002 — Source `FR-TRN-02`

**Priority:** M  
**Actor / Surface:** Venue Staff / Customer Mobile App, Venue Dashboard  
**Rule Type:** Functional rule  
**Governing v5.0:** §36.2

**Rule:** Player-hosted tournaments require an operational **VERIFIED** creator and **venue approval**. Venue-hosted tournaments require neither.

## BR-TRN-003 — Source `FR-TRN-03`

**Priority:** M  
**Actor / Surface:** System / Customer Mobile App, Venue Dashboard  
**Rule Type:** Timer/Scheduler, Inventory/Reservation, Money/Points  
**Governing v5.0:** §36.3

**Rule:** Parameters: 8–32 participants · 1–4 resources · minimum 1 hour in 30-minute increments · no global maximum below the operating-session limit · creation at `requestTime <= startAt − 2 hours` · checkpoint at exactly `startAt − 1 hour` · creation fee EGP 0. Capacity and resource count are independent; E7GZ manages no brackets, per-match scheduling or per-match resource assignment.

## BR-TRN-004 — Source `FR-TRN-04`

**Priority:** M  
**Actor / Surface:** System / Customer Mobile App, Venue Dashboard  
**Rule Type:** Validation/Eligibility, Timer/Scheduler, Inventory/Reservation, Configuration  
**Governing v5.0:** §36.3

**Rule:** A PlayStation tournament must select Single or Multiplayer, which drives resource pricing. The interval must fit **one** valid operating session, so a tournament may exceed the 6-hour online booking maximum.

## BR-TRN-005 — Source `FR-TRN-05`

**Priority:** M  
**Actor / Surface:** System / Customer Mobile App, Venue Dashboard  
**Rule Type:** Functional rule  
**Governing v5.0:** §36.4

**Rule:** The player creator chooses **Organizer Only** or **Organizer + Participant**; if participating, they count in the total.

## BR-TRN-006 — Source `FR-TRN-06`

**Priority:** M  
**Actor / Surface:** System / Customer Mobile App, Venue Dashboard  
**Rule Type:** UI/Presentation  
**Governing v5.0:** §36.5

**Rule:** Public tournaments appear in Discover and any account-verified user may join. Private tournaments are hidden and require an invite code or link; the Dashboard supports copy code and copy/share link.

## BR-TRN-007 — Source `FR-TRN-07`

**Priority:** M  
**Actor / Surface:** Venue Staff / Customer Mobile App, Venue Dashboard  
**Rule Type:** UI/Presentation  
**Governing v5.0:** §36.5

**Rule:** While PENDING_VENUE_APPROVAL a tournament is not in public Discover, its invite code and link are inactive, and no one may join. Only the creator and the venue may view it. Discovery rules activate on venue accept.

## BR-TRN-008 — Source `FR-TRN-08`

**Priority:** M  
**Actor / Surface:** System / Customer Mobile App, Venue Dashboard  
**Rule Type:** Validation/Eligibility, Inventory/Reservation, Configuration  
**Governing v5.0:** §36.7

**Rule:** **Existing reservations always win.** Tournament creation cannot override any existing booking, tournament, maintenance or closure, and **a tournament may never automatically cancel an existing booking**; creation fails for conflicting resources.

## BR-TRN-009 — Source `FR-TRN-33`

**Priority:** M  
**Actor / Surface:** Venue Staff / Customer Mobile App, Venue Dashboard  
**Rule Type:** State/Lifecycle, Validation/Eligibility, Inventory/Reservation  
**Governing v5.0:** §36.6

**Rule:** At creation, **every** assigned resource requires base state **ACTIVE**, operational restriction **NONE**, no operational selling block at venue or resource level, no conflicting reservation or hold, and activity compatibility.

## BR-TRN-010 — Source `FR-TRN-09`

**Priority:** M  
**Actor / Surface:** Venue Staff / Customer Mobile App, Venue Dashboard  
**Rule Type:** Timer/Scheduler, Inventory/Reservation  
**Governing v5.0:** §36.8

**Rule:** Created tournaments hold their resources. During PENDING_VENUE_APPROVAL these are temporary **Approval Holds** that block other reservations and release on reject, expiry or cancellation; on accept they become standard Tournament Holds.

## BR-TRN-011 — Source `FR-TRN-10`

**Priority:** M  
**Actor / Surface:** Venue Staff / Customer Mobile App, Venue Dashboard  
**Rule Type:** State/Lifecycle, Timer/Scheduler, Inventory/Reservation  
**Governing v5.0:** §37.2

**Rule:** Player-hosted approval window: `MIN(requestCreatedAt + 10 minutes, checkpointAt)`. Accept → WAITING_FOR_PLAYERS · Reject → REJECTED `VENUE_REJECTED_TOURNAMENT` · No response → CANCELLED `VENUE_DID_NOT_APPROVE_TOURNAMENT_IN_TIME`. Holds release on both failure paths.

## BR-TRN-012 — Source `FR-TRN-11`

**Priority:** M  
**Actor / Surface:** Venue Staff / Customer Mobile App, Venue Dashboard  
**Rule Type:** Timer/Scheduler, Inventory/Reservation  
**Governing v5.0:** §37.3

**Rule:** A creator may hold only **one** player-hosted tournament in PENDING_VENUE_APPROVAL platform-wide, and must wait **5 minutes** after a rejection or approval expiry before re-requesting the same venue and time interval.

## BR-TRN-013 — Source `FR-TRN-12`

**Priority:** M  
**Actor / Surface:** System / Customer Mobile App, Venue Dashboard  
**Rule Type:** Validation/Eligibility, Timer/Scheduler, Authorization, Trust/Enforcement  
**Governing v5.0:** §37.4

**Rule:** Users may join while WAITING_FOR_PLAYERS or CONFIRMED and `now < startAt`, with capacity remaining, not banned, and with valid invite access if private. **Capacity remaining counts JOINED participants plus any still-valid pre-start `INELIGIBLE/BAN` reserved slots**, so a temporarily banned Participant's restoration slot cannot be consumed by another join. Participants may leave, and organizers may remove, until `startAt`. Falling below 8 before the checkpoint does **not** cancel early.

## BR-TRN-014 — Source `FR-TRN-13`

**Priority:** M  
**Actor / Surface:** System / Customer Mobile App, Venue Dashboard  
**Rule Type:** State/Lifecycle, Validation/Eligibility, Authorization, Trust/Enforcement, Audit/History  
**Governing v5.0:** §37.4

**Rule:** An **Active Participant** is JOINED, not BANNED, not DELETED, and has neither left nor been removed. Participant states remain JOINED / LEFT / REMOVED / INELIGIBLE. `INELIGIBLE` carries cause `BAN` or `ACCOUNT_DELETED`, with deletion taking precedence. A pre-start BAN-only ineligible Participant reserves one capacity slot while restoration remains possible but does **not** count as Active; `ACCOUNT_DELETED` reserves none. Historical participation and ineligibility-cause history are retained.

## BR-TRN-015 — Source `FR-TRN-14`

**Priority:** M  
**Actor / Surface:** System / Customer Mobile App, Venue Dashboard  
**Rule Type:** State/Lifecycle, Timer/Scheduler, Inventory/Reservation  
**Governing v5.0:** §37.5

**Rule:** At `startAt − 1 hour`: ≥ 8 active participants → CONFIRMED · < 8 → CANCELLED `MINIMUM_PARTICIPANTS_NOT_REACHED` with resources released. After CONFIRMED a later drop below 8 does **not** auto-cancel. There is **no "Run Anyway" override**.

## BR-TRN-016 — Source `FR-TRN-15`

**Priority:** M  
**Actor / Surface:** Venue Staff / Customer Mobile App, Venue Dashboard  
**Rule Type:** State/Lifecycle, Validation/Eligibility, Inventory/Reservation  
**Governing v5.0:** §37.5

**Rule:** The checkpoint revalidates every assigned resource — base state ACTIVE, restriction NONE, no selling block, hold still valid, venue eligible, tournament not otherwise cancelled.

## BR-TRN-017 — Source `FR-TRN-16`

**Priority:** M  
**Actor / Surface:** Venue Staff / Customer Mobile App, Venue Dashboard  
**Rule Type:** Validation/Eligibility, Configuration  
**Governing v5.0:** §37.5

**Rule:** At the checkpoint "venue eligible" means **operationally able to honour the tournament**, not eligible for new marketplace activity. Marketplace SUSPENDED or INACTIVE therefore never fails a checkpoint by itself; only a venue selling block, a venue emergency affecting the tournament, or an applicable closure does. A tournament **never** remains indefinitely in WAITING_FOR_PLAYERS after a failed mandatory checkpoint.

## BR-TRN-018 — Source `FR-TRN-17`

**Priority:** M  
**Actor / Surface:** Venue Owner authorization, System / Customer Mobile App, Venue Dashboard  
**Rule Type:** State/Lifecycle, Validation/Eligibility, Inventory/Reservation, UI/Presentation  
**Governing v5.0:** §37.6

**Rule:** CONFIRMED → ACTIVE at start, ACTIVE → COMPLETED at end. Before CONFIRMED → ACTIVE every resource is revalidated; any resource under owner review cancels the tournament **before gameplay begins**.

## BR-TRN-019 — Source `FR-TRN-18`

**Priority:** M  
**Actor / Surface:** Venue Staff / Customer Mobile App, Venue Dashboard  
**Rule Type:** Timer/Scheduler, Inventory/Reservation, Notification  
**Governing v5.0:** §37.7

**Rule:** A venue account disable during PENDING_VENUE_APPROVAL resolves the tournament **immediately** to CANCELLED `VENUE_ACCOUNT_DISABLED`: the timer stops with no pause or resumption, holds release, the creator's platform-wide pending slot releases, the venue is **not** recorded as failing to approve, the **5-minute cooldown does not apply**, and the creator notification must not use venue non-response blame. A later re-enable never restores it.

## BR-TRN-020 — Source `FR-TRN-19`

**Priority:** M  
**Actor / Surface:** Venue Staff / Customer Mobile App, Venue Dashboard  
**Rule Type:** Inventory/Reservation  
**Governing v5.0:** §37.7

**Rule:** A venue account disable alone does **not** cancel WAITING_FOR_PLAYERS, CONFIRMED or ACTIVE tournaments while the venue and resources can still deliver.

## BR-TRN-021 — Source `FR-TRN-20`

**Priority:** M  
**Actor / Surface:** Venue Staff / Customer Mobile App, Venue Dashboard  
**Rule Type:** State/Lifecycle, Trust/Enforcement  
**Governing v5.0:** §37.8

**Rule:** Verified Player cascade: **REVOKED** or qualification lost through trust correction cancels PENDING_VENUE_APPROVAL and WAITING_FOR_PLAYERS under `CREATOR_VERIFIED_STATUS_REVOKED`, while CONFIRMED and ACTIVE remain. **SUSPENDED** cancels nothing existing but blocks new creation. Restoration never resurrects a cancelled tournament. The cascade executes **once per `verifiedPlayerTransitionId`**.

## BR-TRN-022 — Source `FR-TRN-21`

**Priority:** M  
**Actor / Surface:** Venue Staff, Platform Admin / Customer Mobile App, Venue Dashboard, Platform Admin  
**Rule Type:** Authorization  
**Governing v5.0:** §37.9

**Rule:** Cancellation authority: venue may cancel before ACTIVE (player-hosted, after its own approval) · creator may cancel PENDING_VENUE_APPROVAL and WAITING_FOR_PLAYERS before the checkpoint · Platform Admin may cancel any non-completed tournament. **After CONFIRMED the creator may no longer cancel through E7GZ** and must contact the venue or support.

## BR-TRN-023 — Source `FR-TRN-22`

**Priority:** M  
**Actor / Surface:** Venue Staff, Platform Admin / Customer Mobile App, Venue Dashboard, Platform Admin  
**Rule Type:** Inventory/Reservation, Authorization, Configuration  
**Governing v5.0:** §37.9

**Rule:** An ACTIVE tournament may end early only through venue emergency closure, an applicable resource emergency outage, **Tournament Operational Termination**, or Platform Admin correction. There is no unrestricted "venue operational termination" power.

## BR-TRN-024 — Source `FR-TRN-23`

**Priority:** M  
**Actor / Surface:** Venue Staff, Venue Owner authorization / Customer Mobile App, Venue Dashboard  
**Rule Type:** State/Lifecycle, Validation/Eligibility, Authorization, Notification, Audit/History  
**Governing v5.0:** §37.9

**Rule:** Tournament Operational Termination covers a tournament-specific incident not requiring venue closure, requires the **Owner Password**, a mandatory reason, audit and participant and organizer notification, and yields ACTIVE → CANCELLED `TOURNAMENT_OPERATIONAL_TERMINATION`.

## BR-TRN-025 — Source `FR-TRN-24`

**Priority:** M  
**Actor / Surface:** Venue Staff, Venue Owner authorization, System / Customer Mobile App, Venue Dashboard  
**Rule Type:** Authorization, UI/Presentation  
**Governing v5.0:** §37.10

**Rule:** Owner Password matrix: cancelling WAITING_FOR_PLAYERS **no** · cancelling CONFIRMED **yes** · operational termination **yes** · any emergency or owner-review consequence **no**, because those are system consequences rather than the venue's cancel action.

## BR-TRN-026 — Source `FR-TRN-25`

**Priority:** M  
**Actor / Surface:** System / Customer Mobile App, Venue Dashboard  
**Rule Type:** Inventory/Reservation, Audit/History  
**Governing v5.0:** §37.9

**Rule:** Every cancellation releases holds, updates history, notifies creator and participants, and stores the cancelling actor and reason. No resource booking is silently created or removed outside that transaction.

## BR-TRN-027 — Source `FR-TRN-26`

**Priority:** M  
**Actor / Surface:** System / Customer Mobile App, Venue Dashboard  
**Rule Type:** State/Lifecycle, Validation/Eligibility, Timer/Scheduler, Authorization, Trust/Enforcement  
**Governing v5.0:** §37.11

**Rule:** Participant Ban/deletion handling is cause-specific. Ban → `INELIGIBLE/BAN`: zero Active count but one capacity slot stays reserved while the account is active, the Tournament is non-terminal and `now < startAt`. Account deletion → `INELIGIBLE/ACCOUNT_DELETED`: zero Active count, no reserved slot and no automatic restoration. Deletion overrides an earlier BAN cause — including same-operation Ban/deletion — and later Ban expiry/lift cannot restore the Participant. A BAN-only Participant automatically returns to JOINED only if the Ban ends/lifts before `startAt`; if it was already ineffective at the exact checkpoint, restoration is applied before that checkpoint count even if processing runs late. At/after `startAt` no restoration or BAN-only capacity reservation remains, and no later change resurrects a Tournament cancelled at the checkpoint.

## BR-TRN-028 — Source `FR-TRN-27`

**Priority:** M  
**Actor / Surface:** Venue Staff, Venue Owner authorization / Customer Mobile App, Venue Dashboard  
**Rule Type:** State/Lifecycle, Inventory/Reservation, UI/Presentation  
**Governing v5.0:** §37.12

**Rule:** Resource loss during an ACTIVE tournament: at least one operational resource remaining → the tournament continues, the failed resources are removed, and creator and participants receive **Tournament Resource Availability Changed** · none remaining → CANCELLED under the applicable resource reason. Before the start the rule is stricter: **any** assigned resource lost to a resource emergency or owner review cancels the entire tournament, including one still in PENDING_VENUE_APPROVAL.

## BR-TRN-029 — Source `FR-TRN-28`

**Priority:** M  
**Actor / Surface:** Venue Staff / Customer Mobile App, Venue Dashboard  
**Rule Type:** Timer/Scheduler, Inventory/Reservation, Money/Points, Audit/History  
**Governing v5.0:** §36.9, §37.12

**Rule:** Tournament venue cost = `historical resource hourly price × booked nominal duration`, summed over resources, with the same half-up 2-decimal rounding. Snapshot is taken at **request creation** for player-hosted and at **creation** for venue-hosted. Later price changes never alter a stored cost, and **a resource loss never changes it**.

## BR-TRN-030 — Source `FR-TRN-29`

**Priority:** M  
**Actor / Surface:** Venue Staff / Customer Mobile App, Venue Dashboard  
**Rule Type:** Validation/Eligibility, Inventory/Reservation, Trust/Enforcement, Money/Points  
**Governing v5.0:** §36.9, §36.11

**Rule:** Tournament venue cost, entry fee and prize are **informational only**. E7GZ collects nothing, holds nothing and distributes nothing, and never imposes a penalty, debt, wallet deduction or financial obligation on a creator who cannot continue.

## BR-TRN-031 — Source `FR-TRN-30`

**Priority:** M  
**Actor / Surface:** Venue Staff / Customer Mobile App, Venue Dashboard  
**Rule Type:** Money/Points, UI/Presentation  
**Governing v5.0:** §36.10

**Rule:** A COMPLETED tournament contributes its stored cost to Dashboard tournament revenue on its `businessDate`. Cancelled and rejected tournaments contribute nothing.

## BR-TRN-032 — Source `FR-TRN-31`

**Priority:** M  
**Actor / Surface:** Venue Staff, Venue Owner authorization / Customer Mobile App, Venue Dashboard  
**Rule Type:** State/Lifecycle, Inventory/Reservation, UI/Presentation, Configuration  
**Governing v5.0:** §37.14

**Rule:** Exactly one terminal tournament reason is stored, by priority: **1.** `RESOURCE_OWNER_REVIEW_REQUIRED` · **2.** `RESOURCE_EMERGENCY_OUTAGE` · **3.** `EMERGENCY_CLOSURE` · **4.** `VENUE_ACCOUNT_DISABLED` · **5.** `MINIMUM_PARTICIPANTS_NOT_REACHED` · **6.** `CREATOR_VERIFIED_STATUS_REVOKED` · **7.** explicit actor cancellation.

## BR-TRN-033 — Source `FR-TRN-32`

**Priority:** M  
**Actor / Surface:** Venue Staff, Venue Owner authorization, Platform Admin / Customer Mobile App, Venue Dashboard, Platform Admin  
**Rule Type:** State/Lifecycle, Validation/Eligibility, Inventory/Reservation, Authorization, UI/Presentation  
**Governing v5.0:** §37.13, §55.7, §57.3

**Rule:** The closed **Tournament Reason Code** enum is `CREATOR_CANCELLED` · `VENUE_CANCELLED` · `ADMIN_CANCELLED` · `VENUE_REJECTED_TOURNAMENT` · `VENUE_DID_NOT_APPROVE_TOURNAMENT_IN_TIME` · `MINIMUM_PARTICIPANTS_NOT_REACHED` · `CREATOR_VERIFIED_STATUS_REVOKED` · `TOURNAMENT_OPERATIONAL_TERMINATION` · `EMERGENCY_CLOSURE` · `RESOURCE_EMERGENCY_OUTAGE` · `RESOURCE_OWNER_REVIEW_REQUIRED` · `VENUE_ACCOUNT_DISABLED`. Every terminal tournament carries exactly one, and a new member requires Product Change Control.

---

# 12. Trust, Penalties and Bans

## BR-TRS-001 — Source `FR-TRS-01`

**Priority:** M  
**Actor / Surface:** System / Platform Admin  
**Rule Type:** State/Lifecycle, Trust/Enforcement, Audit/History  
**Governing v5.0:** §45.1

**Rule:** Trust is calculated from **immutable trust input events**. The no-show counter, penalty stage, ban state, Verified Player progress and Verified Player status are **derived** and never authoritative. No counter may be inferred from a stored derived value.

## BR-TRS-002 — Source `FR-TRS-02`

**Priority:** M  
**Actor / Surface:** Platform Admin, System / Platform Admin  
**Rule Type:** Authorization, Trust/Enforcement  
**Governing v5.0:** §45.2

**Rule:** The canonical catalogue is: **Booking outcomes** `BOOKING_ATTENDED`, `BOOKING_ATTENDANCE_UNCONFIRMED`, `BOOKING_ATTENDED_INTERRUPTED`, `BOOKING_NO_SHOW` · **Corrections** `NO_SHOW_REVERSED`, `ATTENDANCE_CORRECTED` · **Administration** `ADMIN_TRUST_ADJUSTMENT`, `VERIFIED_PLAYER_SUSPENDED`, `VERIFIED_PLAYER_REVOKED`, `VERIFIED_PLAYER_RESTORED` · **System protection** `BOOKING_ACCOUNT_DISABLE_ATTENDANCE_PROTECTION`.

## BR-TRS-003 — Source `FR-TRS-03`

**Priority:** M  
**Actor / Surface:** System / Platform Admin  
**Rule Type:** Trust/Enforcement, Money/Points, Audit/History  
**Governing v5.0:** §45.2

**Rule:** Operational evidence — `CHECK_IN_RECORDED`, a pre-completion `CHECK_IN_REVERSED`, `LATE_PRESENT_RECORDED`, `LATE_PRESENT_REVERSED` — stays in booking and audit history and enters trust **only** through a resulting canonical outcome or correction. Pressing check-in is not itself a reward.

## BR-TRS-004 — Source `FR-TRS-04`

**Priority:** M  
**Actor / Surface:** System / Platform Admin  
**Rule Type:** Audit/History  
**Governing v5.0:** §45.3

**Rule:** Every event carries `eventId`, `eventType`, `occurredAt`, `effectiveAt`, `eventSequence`, `subjectUserId`, optional `bookingId`, optional `targetEventId` and `eventPayload`.

## BR-TRS-005 — Source `FR-TRS-05`

**Priority:** M  
**Actor / Surface:** Platform Admin, System / Platform Admin  
**Rule Type:** Timer/Scheduler, Authorization, Trust/Enforcement, Audit/History  
**Governing v5.0:** §45.4

**Rule:** Effective-time rules are closed: any booking-derived event and the protection event use `booking.startAt` · any correction of a booking event uses the **same** `effectiveAt` as the corrected event · admin trust adjustment and the three Verified Player admin events use `occurredAt`.

## BR-TRS-006 — Source `FR-TRS-06`

**Priority:** M  
**Actor / Surface:** System / Platform Admin  
**Rule Type:** Audit/History  
**Governing v5.0:** §45.5

**Rule:** Replay orders **exclusively** by `effectiveAt` ASC then `eventSequence` ASC. There is no competing ordering.

## BR-TRS-007 — Source `FR-TRS-07`

**Priority:** M  
**Actor / Surface:** System / Platform Admin  
**Rule Type:** Trust/Enforcement  
**Governing v5.0:** §45.6

**Rule:** Corrections never delete. Replay **neutralises** the targeted effect and, where appropriate, **substitutes** the corrected effect. `ATTENDANCE_CORRECTED` carries a mandatory `correctedOutcome` of ATTENDED, ATTENDED_INTERRUPTED, ATTENDANCE_UNCONFIRMED or ABSENT_NO_SHOW.

## BR-TRS-008 — Source `FR-TRS-08`

**Priority:** M  
**Actor / Surface:** Platform Admin, System / Platform Admin  
**Rule Type:** Authorization, Money/Points, Audit/History, Configuration  
**Governing v5.0:** §45.6

**Rule:** A correction to ATTENDED uses the points value applicable to the booking's **original** completion date and configuration, posts the transaction at correction time, and records the historical value, correction time, reason and admin identity.

## BR-TRS-009 — Source `FR-TRS-09`

**Priority:** M  
**Actor / Surface:** System / Platform Admin  
**Rule Type:** Trust/Enforcement, Money/Points, Audit/History  
**Governing v5.0:** §45.7

**Rule:** The **latest non-superseded** attendance correction by `eventSequence` is authoritative. Each correction references the booking, the superseded correction if any, the original outcome event and the corrected outcome. Only **one** effective outcome per booking contributes to replay, so sequential corrections can never double a reward, a reset or a penalty.

## BR-TRS-010 — Source `FR-TRS-10`

**Priority:** M  
**Actor / Surface:** System / Platform Admin  
**Rule Type:** State/Lifecycle, Audit/History  
**Governing v5.0:** §45.8

**Rule:** Replay can compute state **as of any time**, reproducing both historical and current state from the same inputs.

## BR-TRS-011 — Source `FR-TRS-11`

**Priority:** M  
**Actor / Surface:** System / Platform Admin  
**Rule Type:** Inventory/Reservation, Trust/Enforcement, Money/Points, Audit/History  
**Governing v5.0:** §45.9

**Rule:** Counter effects: ATTENDED resets and grants points and +1 progress · ATTENDED_INTERRUPTED resets, no reward · protection resets, no reward · ATTENDANCE_UNCONFIRMED does nothing · NO_SHOW increments. The **three canonical counter-reset trust events** are exactly ATTENDED, ATTENDED_INTERRUPTED and the protection event. Separately, and not as a trust event, reaching a penalty threshold also resets the counter at ban creation or extension.

## BR-TRS-012 — Source `FR-TRS-12`

**Priority:** M  
**Actor / Surface:** Venue Staff, System / Platform Admin  
**Rule Type:** Trust/Enforcement  
**Governing v5.0:** §45.9, §45.10

**Rule:** Cancelled, rejected, venue-response-expired, attendance-unconfirmed, manual bookings and tournament participation neither increment nor reset the counter. Consecutive tracking is **global across E7GZ PS**, ordered by booking start time.

## BR-TRS-013 — Source `FR-TRS-13`

**Priority:** M  
**Actor / Surface:** System / Platform Admin  
**Rule Type:** State/Lifecycle, Timer/Scheduler, Inventory/Reservation  
**Governing v5.0:** §48.2

**Rule:** The ladder: stage 0 — 3 consecutive → 7 days · stage 1 — 2 consecutive → 1 month · stage 2 — 2 consecutive → 2 months · recurring maximum — every 3 consecutive → 2 months. The threshold intentionally returns to 3 at the recurring stage, and 2 months is the maximum.

## BR-TRS-014 — Source `FR-TRS-14`

**Priority:** M  
**Actor / Surface:** System / Platform Admin  
**Rule Type:** Validation/Eligibility, Inventory/Reservation, Trust/Enforcement  
**Governing v5.0:** §48.2

**Rule:** A penalty triggers when a new eligible no-show **increments the counter to the current threshold** — never merely because a stored number exceeds a later threshold.

## BR-TRS-015 — Source `FR-TRS-15`

**Priority:** M  
**Actor / Surface:** System / Platform Admin  
**Rule Type:** Inventory/Reservation, Trust/Enforcement, UI/Presentation  
**Governing v5.0:** §48.3

**Rule:** Account Standing stores and displays penalty stage, current consecutive count, current threshold, ban start, ban until and remaining time, showing exactly **one** current ban.

## BR-TRS-016 — Source `FR-TRS-16`

**Priority:** M  
**Actor / Surface:** System / Platform Admin  
**Rule Type:** Timer/Scheduler, Trust/Enforcement, Audit/History, UI/Presentation  
**Governing v5.0:** §48.4

**Rule:** On ban creation the counter resets to 0 and the stage advances; on expiry the counter stays 0 unless new qualifying no-shows occurred after return. A completed booking resets only the current counter and never erases the historical stage.

## BR-TRS-017 — Source `FR-TRS-17`

**Priority:** M  
**Actor / Surface:** System / Platform Admin  
**Rule Type:** State/Lifecycle, Trust/Enforcement, Audit/History  
**Governing v5.0:** §48.5

**Rule:** **Ban Qualification** (historical, derived, with `qualificationAt`) and **Ban Instance** (operational, with `banInstanceId`, `activatedAt`, `endsAt`, `penaltyStage`, `duration`, `status`, `activationReason`) are distinct and must not be conflated.

## BR-TRS-018 — Source `FR-TRS-18`

**Priority:** M  
**Actor / Surface:** System / Platform Admin  
**Rule Type:** Trust/Enforcement, Audit/History  
**Governing v5.0:** §48.5

**Rule:** A retroactively discovered ban activates at **correction time**, never back-dated, and produces **one** ban instance carrying a historical `qualificationAt` and a current `activatedAt`. Ban end is always `activatedAt + duration`.

## BR-TRS-019 — Source `FR-TRS-19`

**Priority:** M  
**Actor / Surface:** System / Platform Admin  
**Rule Type:** Authorization, Trust/Enforcement  
**Governing v5.0:** §48.5

**Rule:** "Was this user banned then?" is answered from the ban instance's `activatedAt`/`endsAt`, not from the qualification point. Trust Qualification As-Of and Operational Ban As-Of are separate queries and may legitimately differ after a correction.

## BR-TRS-020 — Source `FR-TRS-20`

**Priority:** M  
**Actor / Surface:** Customer, System / Platform Admin  
**Rule Type:** State/Lifecycle, Inventory/Reservation, Authorization, Trust/Enforcement  
**Governing v5.0:** §48.6

**Rule:** Ban cascade: PENDING → CANCELLED `USER_BANNED` · AWAITING → CANCELLED `USER_BANNED` with hold release · future CONFIRMED → CANCELLED `USER_BANNED` · **ACTIVE continues**. Future holds release and the customer is notified.

## BR-TRS-021 — Source `FR-TRS-21`

**Priority:** M  
**Actor / Surface:** System / Platform Admin  
**Rule Type:** Validation/Eligibility, Trust/Enforcement  
**Governing v5.0:** §48.7

**Rule:** Each ban instance's cancellation cascade executes **once only**; repeated replay must never repeat it.

## BR-TRS-022 — Source `FR-TRS-22`

**Priority:** M  
**Actor / Surface:** System / Platform Admin  
**Rule Type:** Authorization, Trust/Enforcement, Audit/History  
**Governing v5.0:** §48.8

**Rule:** At most **one ACTIVE ban instance** per user. A further qualification while banned advances the stage as a full trigger, resets the counter to 0, and **extends** the existing instance: `newEndsAt = MAX(currentEndsAt, now + durationForNewStage)`. Extension never repeats an already-executed cascade. `BAN_EXTENDED` is audit-required with instance ID, triggering qualification and event, previous and new stage, old and new end, and timestamp.

## BR-TRS-023 — Source `FR-TRS-23`

**Priority:** M  
**Actor / Surface:** System / Platform Admin  
**Rule Type:** State/Lifecycle, Trust/Enforcement, Audit/History  
**Governing v5.0:** §48.9

**Rule:** Ban instance status is ACTIVE / EXPIRED / LIFTED / CORRECTED, and the historical instance always remains in audit.

## BR-TRS-024 — Source `FR-TRS-24`

**Priority:** M  
**Actor / Surface:** System / Platform Admin  
**Rule Type:** State/Lifecycle, Validation/Eligibility, Inventory/Reservation, Trust/Enforcement  
**Governing v5.0:** §47.3

**Rule:** Replay corrects **trust state** but **never rewinds external booking side-effects into the past**, so it cannot destroy valid later reservations or restore slots already sold.

## BR-TRS-025 — Source `FR-TRS-25`

**Priority:** M  
**Actor / Surface:** System / Platform Admin  
**Rule Type:** State/Lifecycle, Validation/Eligibility, Trust/Enforcement  
**Governing v5.0:** §47.3

**Rule:** A ban proved invalid is CORRECTED/ENDED with restrictions stopped, the user notified and state recalculated — but **previously cancelled bookings do not return**.

## BR-TRS-026 — Source `FR-TRS-26`

**Priority:** M  
**Actor / Surface:** Customer, Platform Admin, System / Platform Admin  
**Rule Type:** Validation/Eligibility, Authorization  
**Governing v5.0:** §47.4

**Rule:** There is **no Support Replacement Booking** and no special admin booking-creation path. All customer booking creation uses the same online predicate. Support may assist but cannot bypass validation.

## BR-TRS-027 — Source `FR-TRS-27`

**Priority:** M  
**Actor / Surface:** Platform Admin, System / Platform Admin  
**Rule Type:** Authorization, Trust/Enforcement, Money/Points, Audit/History  
**Governing v5.0:** §47.1

**Rule:** Admin remediation covers trust, points, referrals and bookings as listed in v5.0 §47.1, each requiring a reason, admin identity, timestamp and audit record. Wallet adjustment is **not** a launch action.

---

# 13. Points, Referrals, Wallet and Fees

## BR-PTS-001 — Source `FR-PTS-01`

**Priority:** M  
**Actor / Surface:** Customer / Customer Mobile App, Platform Admin  
**Rule Type:** Trust/Enforcement, Money/Points, Audit/History, UI/Presentation, Configuration  
**Governing v5.0:** §5.6, §46.1

**Rule:** Points at launch: completed booking with qualifying attendance **+200** and **+1** Verified Player progress · referral signup **+1,000** and **0** progress · no-show, cancelled, rejected and **review** all **0** points and **0** progress. The configured value is read at the **successful attended completion event** and the granted amount is stored in the transaction; later configuration never rewrites history.

## BR-PTS-002 — Source `FR-PTS-02`

**Priority:** M  
**Actor / Surface:** Customer, System / Customer Mobile App, Platform Admin  
**Rule Type:** Validation/Eligibility, Trust/Enforcement, Money/Points  
**Governing v5.0:** §46.1

**Rule:** Points and trust are separate systems. Points are a reward currency; trust governs standing and eligibility.

## BR-PTS-003 — Source `FR-PTS-03`

**Priority:** M  
**Actor / Surface:** Customer / Customer Mobile App, Platform Admin  
**Rule Type:** Money/Points  
**Governing v5.0:** §46.2

**Rule:** **Points balance may never be negative**; the minimum effective balance is 0. A compensating deduction applies `MIN(currentBalance, requestedDeduction)`.

## BR-PTS-004 — Source `FR-PTS-04`

**Priority:** M  
**Actor / Surface:** Customer, System / Customer Mobile App, Platform Admin  
**Rule Type:** Money/Points, Audit/History  
**Governing v5.0:** §46.2

**Rule:** Any shortfall is recorded in audit as `UNRECOVERED_REVERSAL_AMOUNT`. It does not create a negative balance, does not become future debt, and future earned points are **not** automatically used to repay it.

## BR-PTS-005 — Source `FR-PTS-05`

**Priority:** M  
**Actor / Surface:** Customer / Customer Mobile App, Platform Admin  
**Rule Type:** Money/Points, Audit/History  
**Governing v5.0:** §46.3

**Rule:** Points and wallet history is **append-only**. Corrections create compensating transactions; no ledger row is ever deleted.

## BR-PTS-006 — Source `FR-PTS-06`

**Priority:** M  
**Actor / Surface:** Customer / Customer Mobile App, Platform Admin  
**Rule Type:** State/Lifecycle, Validation/Eligibility, Money/Points  
**Governing v5.0:** §46.4

**Rule:** A referral becomes eligible only on **successful account OTP verification**, not on record creation. Statuses: PENDING / REWARDED / INVALID. Only one reward per referred user. Self-referral is not allowed.

## BR-PTS-007 — Source `FR-PTS-07`

**Priority:** M  
**Actor / Surface:** Customer / Customer Mobile App, Platform Admin  
**Rule Type:** Trust/Enforcement, Money/Points, Configuration  
**Governing v5.0:** §46.4

**Rule:** The reward configuration is read at OTP verification and the applied value is stored permanently. Referrals grant **no** Verified Player progress.

## BR-PTS-008 — Source `FR-PTS-08`

**Priority:** M  
**Actor / Surface:** Customer, System / Customer Mobile App, Platform Admin  
**Rule Type:** Notification  
**Governing v5.0:** §46.4

**Rule:** Hard self-referral checks cover same phone and same email. Same device and same IP may be stored as **fraud signals** but are not automatic hard blocks in MVP.

## BR-PTS-009 — Source `FR-PTS-09`

**Priority:** M  
**Actor / Surface:** Customer / Customer Mobile App, Platform Admin  
**Rule Type:** Validation/Eligibility, Money/Points, Audit/History  
**Governing v5.0:** §46.5

**Rule:** Invalidating a REWARDED referral sets it INVALID and creates a compensating transaction for the **exact historical reward**. A referral may become INVALID even when the full amount cannot be deducted; the ledger records historical reward, requested reversal, applied reversal and unrecovered amount.

## BR-PTS-010 — Source `FR-PTS-10`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff / Customer Mobile App, Platform Admin  
**Rule Type:** Money/Points  
**Governing v5.0:** §46.6

**Rule:** Every user has a wallet with an initial balance of EGP 0, supporting E7GZ booking-fee refunds and a future points conversion. **The wallet never pays the venue gaming charge.**

## BR-PTS-011 — Source `FR-PTS-11`

**Priority:** M  
**Actor / Surface:** Customer, Platform Admin / Customer Mobile App, Platform Admin  
**Rule Type:** Authorization, Money/Points, UI/Presentation  
**Governing v5.0:** §46.6

**Rule:** The wallet ledger exists in the backend; the customer UI shows **no active wallet screen** at launch. Platform Admin wallet adjustment is **not** a launch action. Points adjustment **is**, because points are active.

## BR-PTS-012 — Source `FR-PTS-12`

**Priority:** W  
**Actor / Surface:** Customer / Customer Mobile App, Platform Admin  
**Rule Type:** Money/Points, Configuration  
**Governing v5.0:** §46.6

**Rule:** Points-to-wallet conversion is deferred; the ratio will be configurable.

## BR-PTS-013 — Source `FR-PTS-13`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff, Platform Admin / Customer Mobile App, Platform Admin  
**Rule Type:** Timer/Scheduler, Authorization, Money/Points, Audit/History, UI/Presentation  
**Governing v5.0:** §5.1, §51.10

**Rule:** The global booking fee is **EGP 0**, charged **per booking** — not per hour, per venue or per activity — is configurable by Platform Admin, and is **displayed to the customer as FREE**. Each booking stores the fee applied at creation; historical values are immutable and a later configuration change never alters them. Late Confirm reuses the **original stored** booking fee.

## BR-PTS-014 — Source `FR-PTS-14`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff / Customer Mobile App, Platform Admin  
**Rule Type:** Timer/Scheduler, Inventory/Reservation, Authorization, Money/Points  
**Governing v5.0:** §34.1

**Rule:** Refund classification is binding the moment a non-zero fee is enabled. **Customer-caused** cancellation follows the 15-minute rule — refundable only within 15 minutes of creation **and** before start. **Venue, platform or resource operationally caused** cancellation receives a **full refund**.

## BR-PTS-015 — Source `FR-PTS-15`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff, Platform Admin, System / Customer Mobile App, Platform Admin  
**Rule Type:** State/Lifecycle, Timer/Scheduler, Inventory/Reservation, Authorization, Trust/Enforcement  
**Governing v5.0:** §34.2

**Rule:** Full refund applies to: `VENUE_REJECTED` · `VENUE_DID_NOT_RESPOND_IN_TIME` · `REJECTED_AFTER_EXPIRY` · `CUSTOMER_DECLINED_LATE_CONFIRM` · `CUSTOMER_RECONFIRM_TIMEOUT` · `VENUE_CANCELLED_CONFIRMED_BOOKING` · `VENUE_CLOSURE` · `VENUE_OPENING_HOURS_CHANGED` · `VENUE_EMERGENCY_CLOSURE` · `RESOURCE_EMERGENCY_OUTAGE` · `RESOURCE_OWNER_REVIEW_REQUIRED` · `VENUE_RESPONSE_INTERRUPTED_BY_EMERGENCY` · `RESOURCE_RESPONSE_INTERRUPTED_BY_EMERGENCY` · `LATE_CONFIRM_INTERRUPTED_BY_EMERGENCY` · `VENUE_ACCOUNT_DISABLED` · `SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR` · admin cancellation or correction where the customer is not at fault · `ADMIN_NO_SHOW_REVERSAL` where the wrongful no-show caused service loss.

## BR-PTS-016 — Source `FR-PTS-16`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff / Customer Mobile App, Platform Admin  
**Rule Type:** State/Lifecycle, Authorization, Trust/Enforcement, Money/Points  
**Governing v5.0:** §34.2

**Rule:** Customer cancellation policy applies to `CUSTOMER_CANCELLED` and `USER_BANNED`. `VENUE_CANCELLED_MANUAL_BOOKING` is **NOT_APPLICABLE** because no customer fee exists. **Every reachable terminal reason has exactly one fee treatment.**

## BR-PTS-017 — Source `FR-PTS-17`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff / Customer Mobile App, Platform Admin  
**Rule Type:** Timer/Scheduler, Money/Points  
**Governing v5.0:** §34.3

**Rule:** Declining or timing out a Late Confirmation is **not** an ordinary customer cancellation for fee purposes, because Late Confirm exists only through the venue's own expiry.

## BR-PTS-018 — Source `FR-PTS-18`

**Priority:** M  
**Actor / Surface:** Customer, Platform Admin / Customer Mobile App, Platform Admin  
**Rule Type:** Authorization, Trust/Enforcement, Money/Points  
**Governing v5.0:** §34.3

**Rule:** Correcting an attendance classification is **not** a refund trigger. Refund follows **failure to deliver the booked service**, not reclassification. A customer-fault case must use its real reason rather than a generic `ADMIN_CORRECTION`.

## BR-PTS-019 — Source `FR-PTS-19`

**Priority:** M  
**Actor / Surface:** Customer / Customer Mobile App, Platform Admin  
**Rule Type:** Timer/Scheduler, Money/Points  
**Governing v5.0:** §34.4

**Rule:** An operational interruption **after service start** receives a full refund regardless of the 15-minute rule.

## BR-PTS-020 — Source `FR-PTS-20`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff / Customer Mobile App, Platform Admin  
**Rule Type:** State/Lifecycle, Timer/Scheduler, Trust/Enforcement  
**Governing v5.0:** §34.6

**Rule:** Counterpart phone visibility **ends**: for a booking terminal **before** start time, at `terminalAt + 2 hours` · for a booking terminal **at or after** start time, including NO_SHOW, at the original `endAt + 2 hours` · for VENUE_RESPONSE_EXPIRED, mandatorily at `venueResponseExpiredAt + 2 hours` unless the booking is revived before then. After the retention point the normal booking UI hides counterpart phone data.

## BR-PTS-021 — Source `FR-PTS-21`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff, Platform Admin / Customer Mobile App, Platform Admin  
**Rule Type:** State/Lifecycle, Timer/Scheduler, Authorization, Audit/History  
**Governing v5.0:** §34.6, §34.7

**Rule:** Retention past a started session exists to support operational disputes and late-arrival contact. Platform Admin and authorised support may retain controlled, audited access where operationally required. Expiry of phone visibility after VENUE_RESPONSE_EXPIRED **never** makes that booking terminal; it remains revivable until `startAt`.

---

# 14. Reviews

## BR-REV-001 — Source `FR-REV-01`

**Priority:** M  
**Actor / Surface:** Customer / Customer Mobile App, Venue Dashboard, Platform Admin  
**Rule Type:** UI/Presentation  
**Governing v5.0:** §49.1

**Rule:** A reviewer must be an **account-verified** user. A completed booking is **not** required.

## BR-REV-002 — Source `FR-REV-02`

**Priority:** M  
**Actor / Surface:** Customer, Platform Admin / Customer Mobile App, Venue Dashboard, Platform Admin  
**Rule Type:** Authorization, UI/Presentation  
**Governing v5.0:** §49.2

**Rule:** A review has a 1–5 star rating and a comment. Public reviews display the **current username only**; Platform Admin may see both full name and username.

## BR-REV-003 — Source `FR-REV-03`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff / Customer Mobile App, Venue Dashboard, Platform Admin  
**Rule Type:** Audit/History, UI/Presentation  
**Governing v5.0:** §49.3

**Rule:** One active review per user per venue, editable and deletable. Only one current record per user/venue at a time; historical removed records persist.

## BR-REV-004 — Source `FR-REV-04`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff, Platform Admin / Customer Mobile App, Venue Dashboard, Platform Admin  
**Rule Type:** State/Lifecycle, Authorization, Audit/History, UI/Presentation  
**Governing v5.0:** §49.4

**Rule:** States: PENDING_MODERATION (visible to author as *Pending Review* and to admin, not public, excluded from venue rating) · ACTIVE · HIDDEN (not public, editable, stays hidden after edit, restorable) · REMOVED (**terminal**, never restorable, moderation history permanent).

## BR-REV-005 — Source `FR-REV-05`

**Priority:** M  
**Actor / Surface:** Customer, Platform Admin, System / Customer Mobile App, Venue Dashboard, Platform Admin  
**Rule Type:** State/Lifecycle, Validation/Eligibility, Authorization, UI/Presentation  
**Governing v5.0:** §49.4

**Rule:** A normal eligible review is created **ACTIVE**, except in the moderation re-entry flow. There is **no auto-publish** of a pending review, however much time passes; admin reminders never change status automatically.

## BR-REV-006 — Source `FR-REV-06`

**Priority:** M  
**Actor / Surface:** Customer / Customer Mobile App, Venue Dashboard, Platform Admin  
**Rule Type:** Audit/History, UI/Presentation  
**Governing v5.0:** §49.5

**Rule:** Author deletion removes the public review but never destroys moderation audit and history.

## BR-REV-007 — Source `FR-REV-07`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff, Platform Admin / Customer Mobile App, Venue Dashboard, Platform Admin  
**Rule Type:** Validation/Eligibility, Timer/Scheduler, Authorization, UI/Presentation  
**Governing v5.0:** §49.6

**Rule:** After an admin removal the user may not review that venue for **30 days**; the next review starts PENDING_MODERATION. A further removal starts a new 30-day cooldown.

## BR-REV-008 — Source `FR-REV-08`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff, Platform Admin / Customer Mobile App, Venue Dashboard, Platform Admin  
**Rule Type:** State/Lifecycle, Validation/Eligibility, Authorization, UI/Presentation  
**Governing v5.0:** §49.7

**Rule:** The venue is **view only**. Platform Admin may hide, restore, remove, approve a pending review and remove a pending review; every action requires a reason and records action, reason, date, previous status and new status.

## BR-REV-009 — Source `FR-REV-09`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff / Customer Mobile App, Venue Dashboard, Platform Admin  
**Rule Type:** UI/Presentation  
**Governing v5.0:** §49.8

**Rule:** A SUSPENDED or INACTIVE venue keeps its reviews visible and may still receive new reviews.

## BR-REV-010 — Source `FR-REV-10`

**Priority:** M  
**Actor / Surface:** Customer / Customer Mobile App, Venue Dashboard, Platform Admin  
**Rule Type:** Functional rule  
**Governing v5.0:** §49.9

**Rule:** The author is notified on submission for moderation, approval, hiding and removal, with the moderation reason where appropriate.

---

## BR-REV-011 — Source `FR-REV-11`

**Rule:** A signed-in Customer may report a public Review visible to them. A report requires a non-empty reason and may include optional details. Reporting creates a durable report for Platform Admin and does not automatically change Review state, rating contribution or Venue authority. Platform Admin resolves the report separately and may link an existing valid moderation action.

# 15. Platform Admin

## BR-ADM-001 — Source `FR-ADM-01`

**Priority:** M  
**Actor / Surface:** Venue Staff, Platform Admin / Platform Admin  
**Rule Type:** Authorization, Trust/Enforcement, Money/Points, Notification, UI/Presentation  
**Governing v5.0:** §2.3, §51.1

**Rule:** Platform Admin modules: Dashboard · Areas · Venues · **Venue Onboarding** · Venue Accounts · Activities · Users · Verified Players · Reviews Moderation · Global Bookings · No-show Records · Tournaments · Wallet · Points · Referrals · Platform Fees · **Global Rules** · **Notifications** · **Global Search** · Settings. English only. §51.1 names the core module set; §2.3 names the full launch surface set and both are frozen.

## BR-ADM-002 — Source `FR-ADM-02`

**Priority:** M  
**Actor / Surface:** Venue Staff, Venue Owner authorization, Platform Admin / Platform Admin  
**Rule Type:** Inventory/Reservation, Authorization, Configuration  
**Governing v5.0:** §51.2

**Rule:** Venue onboarding is **Platform-Admin managed**, not self-service, covering venue information, activities, account credentials including the initial Owner Password, resources and pricing. Venue images persist through onboarding, venue details, edit and navigation.

## BR-ADM-003 — Source `FR-ADM-03`

**Priority:** M  
**Actor / Surface:** Venue Staff, Venue Owner authorization, Platform Admin / Platform Admin  
**Rule Type:** Authorization  
**Governing v5.0:** §51.3

**Rule:** Admin may disable and re-enable a venue account and reset the venue login and Owner Passwords **without deleting venue data**; an Owner Password reset notifies the venue. There is no venue self-service forgot-password flow in MVP.

## BR-ADM-004 — Source `FR-ADM-04`

**Priority:** M  
**Actor / Surface:** Platform Admin / Platform Admin  
**Rule Type:** State/Lifecycle, Trust/Enforcement, Notification, UI/Presentation  
**Governing v5.0:** §51.4

**Rule:** User management displays name, username, phone, email, OTP verification, Verified Player status, completed bookings, no-show standing, account status and joined date.

## BR-ADM-005 — Source `FR-ADM-05`

**Priority:** M  
**Actor / Surface:** Platform Admin, System / Platform Admin  
**Rule Type:** Authorization, Trust/Enforcement, UI/Presentation  
**Governing v5.0:** §51.5

**Rule:** Verified Player management supports View, Suspend, Revoke and Restore, each requiring a reason. Admin SUSPENDED and REVOKED are protected from automatic trust regain.

## BR-ADM-006 — Source `FR-ADM-06`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff, Platform Admin / Platform Admin  
**Rule Type:** State/Lifecycle, Timer/Scheduler, Inventory/Reservation, Money/Points, Audit/History  
**Governing v5.0:** §51.6

**Rule:** Global bookings support filters (date range, area, venue, activity, status, source) and search (Booking ID, user name, phone, venue), exposing booking detail with Booking ID, user, venue, activity, resource, **console**, **play mode**, date, time, duration, venue price, **historical booking fee**, total, source, status and **full lifecycle history** — including Pending, Venue Response Expired, Auto Cancelled, Late Confirm Requested, Awaiting Customer Confirmation, Customer Accepted, Customer Declined, Customer Confirmation Expired, Rejected After Expiry, Venue Opening Hours Changed, and every operational and platform reason in v5.0 §33.2.

## BR-ADM-007 — Source `FR-ADM-07`

**Priority:** M  
**Actor / Surface:** Venue Staff, Platform Admin / Platform Admin  
**Rule Type:** Inventory/Reservation, Trust/Enforcement  
**Governing v5.0:** §51.7

**Rule:** No-show records show user, venue, booking, resource, booking date, consecutive count, penalty stage, penalty result and ban information.

## BR-ADM-008 — Source `FR-ADM-08`

**Priority:** M  
**Actor / Surface:** Venue Staff, Platform Admin / Platform Admin  
**Rule Type:** State/Lifecycle, Timer/Scheduler, Inventory/Reservation  
**Governing v5.0:** §51.8

**Rule:** Tournament inspection covers tournament, host, host type, activity, play mode, venue, resources, participants, capacity, entry, prize, visibility, date and time, deadline and status.

## BR-ADM-009 — Source `FR-ADM-09`

**Priority:** M  
**Actor / Surface:** Venue Staff, Platform Admin / Platform Admin  
**Rule Type:** State/Lifecycle, Money/Points, Audit/History, UI/Presentation  
**Governing v5.0:** §51.9

**Rule:** Wallet module shows user, phone, balance and transaction history, and **never** venue gaming revenue. Points module shows user, current points, booking rewards, referral rewards and transaction history. Referral records show referrer, code, referred user, signup date, reward applied and status.

## BR-ADM-010 — Source `FR-ADM-10`

**Priority:** M  
**Actor / Surface:** Platform Admin / Platform Admin  
**Rule Type:** Authorization, Money/Points  
**Governing v5.0:** §51.10

**Rule:** Admin controls the **Global Booking Fee** (EGP 0) and the **Tournament Creation Fee** (EGP 0).

## BR-ADM-011 — Source `FR-ADM-11`

**Priority:** M  
**Actor / Surface:** Platform Admin / Platform Admin  
**Rule Type:** Audit/History, Configuration  
**Governing v5.0:** §53.1

**Rule:** Every configuration change affects **future events only**.

---

# 16. Notifications

## BR-NTF-001 — Source `FR-NTF-01`

**Priority:** M  
**Actor / Surface:** System / Platform Admin  
**Rule Type:** Notification  
**Governing v5.0:** §52.1

**Rule:** Launch channels: In-App · Push · Email · **SMS for OTP only**.

## BR-NTF-002 — Source `FR-NTF-02`

**Priority:** M  
**Actor / Surface:** System / Platform Admin  
**Rule Type:** Inventory/Reservation, Trust/Enforcement, Money/Points, Notification, Audit/History  
**Governing v5.0:** §52.2

**Rule:** Time-sensitive booking, tournament and trust events use **In-App + Push**, covering the full catalogue in v5.0 §52.2 — including every operational and platform cause, `ACTIVE_SESSION_ATTENDANCE_RECORDING_AFFECTED`, tournament resource-availability changes, and operational refund confirmations.

## BR-NTF-003 — Source `FR-NTF-03`

**Priority:** M  
**Actor / Surface:** System / Platform Admin  
**Rule Type:** Notification, Audit/History  
**Governing v5.0:** §52.3

**Rule:** Account, security and moderation events use **In-App + Email**, per v5.0 §52.3.

## BR-NTF-004 — Source `FR-NTF-04`

**Priority:** M  
**Actor / Surface:** System / Platform Admin  
**Rule Type:** Notification  
**Governing v5.0:** §52.2

**Rule:** Every booking notification internally references the **specific Booking ID**, so navigation always lands on the correct booking.

## BR-NTF-005 — Source `FR-NTF-05`

**Priority:** M  
**Actor / Surface:** System / Platform Admin  
**Rule Type:** Trust/Enforcement, Money/Points, Notification  
**Governing v5.0:** §52.2

**Rule:** An attendance-unconfirmed notification must explain that **no attendance reward was applied**.

## BR-NTF-006 — Source `FR-NTF-06`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff, System / Platform Admin  
**Rule Type:** Timer/Scheduler, UI/Presentation  
**Governing v5.0:** §33.6

**Rule:** Customer copy must distinguish causes: ordinary expiry *"Venue did not respond in time"* · platform account disable *"Venue is temporarily unable to confirm this booking"* · operational issue the corresponding reason. Internal codes remain authoritative.

## BR-NTF-007 — Source `FR-NTF-07`

**Priority:** M  
**Actor / Surface:** System / Platform Admin  
**Rule Type:** State/Lifecycle, Validation/Eligibility, Notification  
**Governing v5.0:** §32.13

**Rule:** A notification must **never** state that a booking was cancelled unless a rule actually cancelled it.

## BR-NTF-008 — Source `FR-NTF-08`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff, System / Platform Admin  
**Rule Type:** Validation/Eligibility, Authorization, Notification  
**Governing v5.0:** §32.13

**Rule:** There is **no** mandatory customer notification merely because a venue account is re-enabled; the customer is notified only when a customer-relevant action occurs.

## BR-NTF-009 — Source `FR-NTF-09`

**Priority:** M  
**Actor / Surface:** Venue Staff, System / Platform Admin  
**Rule Type:** Notification, UI/Presentation, Configuration  
**Governing v5.0:** §52.5

**Rule:** Venue notifications cover new requests, expiring requests, auto-cancellations, all Late Confirm outcomes, tournament requests and outcomes, maintenance starting soon, emergency operational reminders, and owner notification of emergencies and owner-review entry.

## BR-NTF-010 — Source `FR-NTF-10`

**Priority:** M  
**Actor / Surface:** Platform Admin, System / Platform Admin  
**Rule Type:** Inventory/Reservation, Authorization, Trust/Enforcement, Money/Points, Notification  
**Governing v5.0:** §52.6

**Rule:** Platform Admin notifications cover onboarding completion, account disable, fee updates, Verified Player revocation, tournament auto-cancellation, ban activation, emergency declarations, owner-review entry, the tournament-cancellation abuse alert, check-in and Late-Present abuse signals, integrity-error alerts, and account disable overlapping an active booking.

## BR-NTF-011 — Source `FR-NTF-11`

**Priority:** M  
**Actor / Surface:** Customer, Platform Admin, System / Platform Admin  
**Rule Type:** State/Lifecycle, Authorization, Trust/Enforcement, Money/Points  
**Governing v5.0:** §52.3

**Rule:** The customer is notified whenever an admin materially changes a ban, a no-show, an attendance result, points, a booking status or a referral reward in a way that affects them.

## BR-NTF-012 — Source `FR-NTF-12`

**Priority:** M  
**Actor / Surface:** System / Platform Admin  
**Rule Type:** Notification  
**Governing v5.0:** §40.5

**Rule:** Notifications dispatch **after** the transaction commits, never before.

---

# 17. Audit and Historical Integrity / Reporting

## BR-AUD-001 — Source `FR-AUD-01`

**Priority:** M  
**Actor / Surface:** System / Platform Admin  
**Rule Type:** Inventory/Reservation, Money/Points, Audit/History, Configuration  
**Governing v5.0:** §5.9, §53.1

**Rule:** Configuration changes affect **future events only**. Historical transactions retain their original values: booking fee · referral reward · booking price · tournament values applied at creation · Late Confirm original totals · **points actually granted** · **console type** · **play mode** · **resource identity** · **resource name snapshot**.

## BR-AUD-002 — Source `FR-AUD-02`

**Priority:** M  
**Actor / Surface:** System / Platform Admin  
**Rule Type:** Inventory/Reservation, Audit/History, UI/Presentation  
**Governing v5.0:** §53.2

**Rule:** Historical snapshots are stored for bookings, tournaments, referrals, reservations, operational interruptions, emergency pause history, account access restriction intervals and owner-review cascades, per v5.0 §53.2.

## BR-AUD-003 — Source `FR-AUD-03`

**Priority:** M  
**Actor / Surface:** Venue Staff, System / Platform Admin  
**Rule Type:** Trust/Enforcement, Audit/History  
**Governing v5.0:** §53.3

**Rule:** Every action in the canonical audit list of v5.0 §53.3 writes an audit record — venue operational actions, operational interruptions, platform and integrity events, trust and ledger actions, and moderation.

## BR-AUD-004 — Source `FR-AUD-04`

**Priority:** M  
**Actor / Surface:** System / Platform Admin  
**Rule Type:** Trust/Enforcement, Money/Points, Audit/History  
**Governing v5.0:** §53.4

**Rule:** Append-only guarantees: booking history · points and wallet history · trust input events · attendance result (audited correction only) · `businessDate` and operating-session snapshots.

## BR-AUD-005 — Source `FR-AUD-05`

**Priority:** M  
**Actor / Surface:** System / Platform Admin  
**Rule Type:** Audit/History, UI/Presentation  
**Governing v5.0:** §33.7

**Rule:** Booking history is append-only: previous lifecycle events never disappear. *Auto Cancelled* stays visible even after a Late Confirm revival.

## BR-AUD-006 — Source `FR-AUD-06`

**Priority:** M  
**Actor / Surface:** Venue Staff, Platform Admin, System / Platform Admin  
**Rule Type:** Validation/Eligibility, Authorization, Audit/History  
**Governing v5.0:** §53.3

**Rule:** Every audit record carries the actor identity (venue account, admin or system), a timestamp and, where the action requires one, the mandatory reason.

## BR-AUD-007 — Source `FR-AUD-13`

**Priority:** M  
**Actor / Surface:** System / Platform Admin  
**Rule Type:** State/Lifecycle, Validation/Eligibility, Audit/History, UI/Presentation  
**Governing v5.0:** §33.1, §57.3

**Rule:** **Booking Reason Codes** and **Booking Events** are two **separate closed enums**. A reason code explains a **terminal** booking outcome; an event records an action or a **non-terminal** failure in append-only history. A token belongs to exactly one of the two. Adding a product-visible member to either requires Product Change Control.

## BR-AUD-008 — Source `FR-AUD-14`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff, System / Platform Admin  
**Rule Type:** State/Lifecycle, Validation/Eligibility, Inventory/Reservation, Trust/Enforcement, Audit/History  
**Governing v5.0:** §33.4

**Rule:** The canonical **Booking Event** list is `CHECK_IN_RECORDED` · `CHECK_IN_REVERSED` · `LATE_PRESENT_RECORDED` · `LATE_PRESENT_REVERSED` · `NO_SHOW_RECORDED` · `NO_SHOW_REVERSED` · `ATTENDANCE_CORRECTED` · `LATE_CONFIRM_FAILED_RESOURCE_CONFLICT` · `LATE_CONFIRM_FAILED_CUSTOMER_CONFLICT` · `LATE_CONFIRM_FAILED_BOOKING_LIMIT` · `LATE_CONFIRM_ELIGIBILITY_LOST_RESOURCE_STATE_CHANGED` · `VENUE_RESPONSE_INTERRUPTED_BY_ACCOUNT_DISABLE`. **No API validation-only token belongs in this enum**, which is why `MANUAL_BOOKING_PRICING_CONFIGURATION_MISSING` is an error code only.

## BR-AUD-009 — Source `FR-AUD-15`

**Priority:** M  
**Actor / Surface:** Customer, Venue Staff, System / Platform Admin  
**Rule Type:** State/Lifecycle, Validation/Eligibility, Inventory/Reservation, Money/Points, Audit/History  
**Governing v5.0:** §33.3

**Rule:** Five tokens are **deliberately removed** and must not be reintroduced: `BOOKING_EXPIRED` (use `VENUE_DID_NOT_RESPOND_IN_TIME`) · `RESOURCE_CONFLICT` (validation or atomic-commit failure, or `LATE_CONFIRM_FAILED_RESOURCE_CONFLICT`) · `CUSTOMER_SCHEDULE_CONFLICT` (`LATE_CONFIRM_FAILED_CUSTOMER_CONFLICT`) · `LATE_CONFIRM_RESOURCE_UNAVAILABLE` (as above) · `LATE_CONFIRM_ELIGIBILITY_LOST_RESOURCE_STATE_CHANGED` **as a terminal reason** (it is an event of the same name). Because none is terminal, none needs a fee treatment — which is what makes every reachable terminal reason map to exactly one treatment.

## BR-AUD-010 — Source `FR-AUD-07`

**Priority:** M  
**Actor / Surface:** Venue Staff, System / Platform Admin  
**Rule Type:** Timer/Scheduler, Money/Points, Audit/History  
**Governing v5.0:** §17.5

**Rule:** `businessDate` is the single revenue-day key across online bookings, manual bookings and tournaments, and is historical.

## BR-AUD-011 — Source `FR-AUD-08`

**Priority:** M  
**Actor / Surface:** Venue Staff, System / Platform Admin  
**Rule Type:** Timer/Scheduler, Money/Points  
**Governing v5.0:** §50.3

**Rule:** Venue revenue reporting aggregates today's revenue, this month's revenue, and online, manual and tournament revenue on `businessDate`, with an optional activity breakdown.

## BR-AUD-012 — Source `FR-AUD-09`

**Priority:** M  
**Actor / Surface:** Venue Staff, System / Platform Admin  
**Rule Type:** Money/Points  
**Governing v5.0:** §36.10

**Rule:** Tournament revenue recognises the stored venue cost only on **COMPLETED**.

## BR-AUD-013 — Source `FR-AUD-10`

**Priority:** M  
**Actor / Surface:** Venue Staff, System / Platform Admin  
**Rule Type:** Money/Points, Audit/History  
**Governing v5.0:** §51.6

**Rule:** Booking detail exposes the historical booking fee alongside the venue price and total, so a future non-zero fee is reportable without back-filling.

## BR-AUD-014 — Source `FR-AUD-11`

**Priority:** S  
**Actor / Surface:** Customer, Venue Staff, System / Platform Admin  
**Rule Type:** State/Lifecycle  
**Governing v5.0:** **TBD / Proposed non-product requirement — not defined by Frozen Baseline**

**Rule:** Platform reporting can produce the terminal-reason distribution split by customer-caused, venue-caused, operational and platform causes. Derived from PRD §4.2 (G3), not from v5.0.

## BR-AUD-015 — Source `FR-AUD-12`

**Priority:** S  
**Actor / Surface:** Venue Staff, System / Platform Admin  
**Rule Type:** Trust/Enforcement  
**Governing v5.0:** **TBD / Proposed non-product requirement — not defined by Frozen Baseline**

**Rule:** Platform reporting can produce the attendance mix and the check-in capture rate per venue. Derived from PRD §4.2 (G4), not from v5.0.

---

# COMPLETENESS

- Source Functional Requirements represented as business/product rules: **357 / 357**
- Cross-cutting principles above are summaries only; the source `FR-*` rule remains authoritative if wording differs.
