# E7GZ PS
# ERROR & EDGE CASES v1.0 — FUNCTIONAL BASELINE

**Status:** Derived functional baseline  
**Authority:** Product Specification v5.0 — FROZEN BASELINE → PRD v1.1 — APPROVED FOR BUILD → FRS v1.1 — APPROVED FOR BUILD  
**Market:** Egypt — initial launch in Cairo  
**Timezone:** Africa/Cairo  
**Currency:** EGP

> **No Product change.** This document is a derived working view of the approved baselines. It does not create new states, transitions, reason codes, timers, authorization rules, validation predicates, Trust semantics, financial rules, or scope. If anything here conflicts with Product Specification v5.0, v5.0 wins.

---

# 0. PURPOSE

This file collects frozen boundary, race, failure, correction and exceptional-operation cases that must not be lost in the happy path. It is a QA/implementation companion, not a source of new behaviour.

---

# 1. HIGH-RISK CROSS-DOMAIN EDGE PACKS

## EC-X-001 — Inventory races
Test simultaneous requests for the same Resource/interval, Late Confirm versus a new booking, Tournament Hold versus booking, and no-show release versus new sale. Exactly one valid commitment may win; losers fail cleanly without partial reservation.

## EC-X-002 — Timer boundaries
Test venue response, intake cutoff, lead time, Late Confirm, Check-in/No-show/Late Present, Tournament approval/checkpoint, OTP and cooldown boundaries at the exact deadline and ±1 second where meaningful. Expiry owns `now >= deadline`.

## EC-X-003 — Emergency scope and timer pauses
Test Venue vs Resource emergency scope, pause/resume, separate cumulative counters, shared emergency-type contribution to the response counter, 30-minute caps, optimistic feasibility, conversion and no-sellable-gap behaviour.

## EC-X-004 — Venue Account Disable
Test PENDING, AWAITING, CONFIRMED, ACTIVE, PENDING_VENUE_APPROVAL and repeated disable/re-enable intervals. Access restriction must not be mislabeled as physical outage.

## EC-X-005 — Trust corrections
Test no-show reversal, chained attendance corrections, retroactive Ban qualification, active-Ban extension, Verified Player revoke/automatic regain, and Admin-protected status. Replay must remain deterministic and idempotent.

## EC-X-006 — Financial and historical snapshots
Test later price/fee/referral/points changes against existing records, refund classification, Manual Booking fee `NOT_APPLICABLE`, and corrections without rewriting historical values.

---

# 2. DOMAIN ERROR / EDGE CASE CATALOGUE

## 2.1 Accounts, Identity and Verification

### EC-ACCO-001

- **Scenario / Expected Result:** An unverified account is rejected at every one of the four gated actions, with a message that names phone verification as the requirement

### EC-ACCO-002

- **Scenario / Expected Result:** A 6th OTP send within an hour to the same destination is refused

### EC-ACCO-003

- **Scenario / Expected Result:** Changing a username twice within 30 days is refused; the error states the remaining cooldown

### EC-ACCO-004

- **Scenario / Expected Result:** A user who loses Verified Player status through a trust correction, then regains qualification through a later correction, is VERIFIED again with **no** admin action, and carries two distinct transition IDs

### EC-ACCO-005

- **Scenario / Expected Result:** A user suspended by an admin who then reaches qualification remains SUSPENDED

### EC-ACCO-006

- **Scenario / Expected Result:** Accepting a Late Confirmation while banned is refused; declining it succeeds

---

## 2.2 Discovery and Browsing

### EC-DISC-001

- **Scenario / Expected Result:** A venue whose only PlayStation room is under owner review shows no PlayStation nearest availability, and does not appear in a PlayStation-filtered list as bookable

### EC-DISC-002

- **Scenario / Expected Result:** Selecting an area while location is on returns only that area's venues

### EC-DISC-003

- **Scenario / Expected Result:** A closed venue is browsable and priced, and its book action is disabled with copy explaining when it opens

### EC-DISC-004

- **Scenario / Expected Result:** A suspended venue disappears from the list but its deep link still renders, with booking disabled

---

## 2.3 Availability and Inventory

### EC-AVAI-001

- **Scenario / Expected Result:** A concurrency test issuing N simultaneous bookings for one slot produces exactly one CONFIRMED path and N−1 clean failures, with no partial reservation

### EC-AVAI-002

- **Scenario / Expected Result:** A resource with `baseState = ACTIVE` and `operationalRestriction = OWNER_REVIEW_REQUIRED` is rejected by all six flows in FR-AVL-01

### EC-AVAI-003

- **Scenario / Expected Result:** A venue under an emergency selling block rejects a manual booking whose interval does not overlap the declared emergency interval

### EC-AVAI-004

- **Scenario / Expected Result:** After a no-show at 8:16 on an 8:00–9:00 booking, a new booking starting 8:20 succeeds, and both records survive with overlapping planned intervals and non-overlapping reservation intervals

### EC-AVAI-005

- **Scenario / Expected Result:** A manual booking created outside published opening hours still carries a `businessDate`

---

## 2.4 Online Booking Lifecycle

### EC-ONLI-001

- **Scenario / Expected Result:** A request submitted 9 minutes before the session ends is refused with the intake-window reason; one submitted 11 minutes before succeeds

### EC-ONLI-002

- **Scenario / Expected Result:** A request for `startAt` exactly 15 minutes ahead succeeds; 14 minutes 59 seconds is refused

### EC-ONLI-003

- **Scenario / Expected Result:** A 5th commitment is refused at creation, and also at Late Confirm initiation and at Late Confirm Accept

### EC-ONLI-004

- **Scenario / Expected Result:** A venue that accepts at `venueResponseExpiresAt − 1s` succeeds; at the boundary the expiry wins

### EC-ONLI-005

- **Scenario / Expected Result:** A booking expiring at a venue whose account is disabled resolves at `startAt` under `VENUE_ACCOUNT_DISABLED`, never `VENUE_DID_NOT_RESPOND_IN_TIME`

### EC-ONLI-006

- **Scenario / Expected Result:** A SUSPENDED venue can still accept a PENDING booking created before suspension

### EC-ONLI-007

- **Scenario / Expected Result:** Forcing a resource into DISABLED behind the predicate and letting a CONFIRMED booking reach `startAt` produces `SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR`, a terminal booking, and an admin alert

### EC-ONLI-008

- **Scenario / Expected Result:** Every terminal booking in a seeded end-to-end run carries exactly one reason code from the closed enum

---

## 2.5 Late Confirmation

### EC-LATE-001

- **Scenario / Expected Result:** Initiating at 7:59 PM for an 8:00 PM booking gives the customer exactly 60 seconds and **no** second notification

### EC-LATE-002

- **Scenario / Expected Result:** Initiating at 7:50 PM gives 5 minutes and one expiring notification at roughly 7:54 PM

### EC-LATE-003

- **Scenario / Expected Result:** A customer at 4 commitments cannot have a Late Confirm initiated for them; the venue message reveals nothing, and no hold is created

### EC-LATE-004

- **Scenario / Expected Result:** Two venues initiating Late Confirm simultaneously for a customer at 3 commitments produce exactly one AWAITING booking

### EC-LATE-005

- **Scenario / Expected Result:** A customer accepting a Late Confirmation that overlaps another commitment gets a clear failure, the booking stays AWAITING until its own expiry, and the event is recorded

### EC-LATE-006

- **Scenario / Expected Result:** A declined Late Confirmation costs the customer nothing and, under a future non-zero fee, refunds in full

---

## 2.6 Attendance, Check-in and No-show

### EC-ATTE-001

- **Scenario / Expected Result:** Check-in at `startAt + 14:59` succeeds; at `+15:00` it is refused and Mark Present — Late becomes available

### EC-ATTE-002

- **Scenario / Expected Result:** A booking with Mark Present — Late cannot be marked no-show, and completes as ATTENDANCE_UNCONFIRMED with no points

### EC-ATTE-003

- **Scenario / Expected Result:** A no-show at 8:16 frees 8:16–9:00 for immediate resale; reversing it at 8:40 does not evict the new booking

### EC-ATTE-004

- **Scenario / Expected Result:** Two disable/re-enable cycles inside one active session produce **one** protection event and **one** counter reset

### EC-ATTE-005

- **Scenario / Expected Result:** A session where the account was disabled only outside both attendance windows does not qualify for protection

### EC-ATTE-006

- **Scenario / Expected Result:** An admin correcting a protected booking to ABSENT_NO_SHOW leaves the customer with a no-show increment and no reset

### EC-ATTE-007

- **Scenario / Expected Result:** The customer notification for an overlapping disable never contains the word "cancelled"

---

## 2.7 Manual Booking

### EC-MANU-001

- **Scenario / Expected Result:** A manual booking at an ONBOARDING venue is refused; at a SUSPENDED venue it succeeds

### EC-MANU-002

- **Scenario / Expected Result:** A manual booking overlapping a PENDING online booking is refused

### EC-MANU-003

- **Scenario / Expected Result:** A manual booking at 3 AM for a venue closing at 2 AM succeeds and carries a `businessDate`

### EC-MANU-004

- **Scenario / Expected Result:** A walk-in created at the current server time begins ACTIVE, not CONFIRMED

### EC-MANU-005

- **Scenario / Expected Result:** A completed manual booking grants no points and no Verified Player progress

---

## 2.8 Venue Model, Configuration and Dashboard

### EC-VENU-001

- **Scenario / Expected Result:** Two protected actions in a row each demand the Owner Password separately

### EC-VENU-002

- **Scenario / Expected Result:** Disabling a resource with a future confirmed booking is refused and names the blocking reservation

### EC-VENU-003

- **Scenario / Expected Result:** A console change clears the room override and leaves historical bookings untouched

### EC-VENU-004

- **Scenario / Expected Result:** An hours change conflicting with a PENDING_VENUE_APPROVAL tournament is blocked before any preview of booking impact

### EC-VENU-005

- **Scenario / Expected Result:** A new confirmed booking created between preview and commit aborts the commit and forces a refreshed preview

### EC-VENU-006

- **Scenario / Expected Result:** A closure overlapping an active session is refused

### EC-VENU-007

- **Scenario / Expected Result:** The Dashboard renders correctly in Arabic RTL across all modules, with stored free text unchanged

---

## 2.9 Maintenance, Emergencies and Operational Restrictions

### EC-MAIN-001

- **Scenario / Expected Result:** Splitting a 30-hour maintenance into two windows with a one-minute gap still demands the Owner Password

### EC-MAIN-002

- **Scenario / Expected Result:** A venue emergency cascade interrupted by an injected fault leaves **no** partial state — either all cancellations and the closure exist, or none do

### EC-MAIN-003

- **Scenario / Expected Result:** A resource emergency does not block bookings on the venue's other resources

### EC-MAIN-004

- **Scenario / Expected Result:** END EMERGENCY on an over-threshold resource leaves it unsellable under owner review

### EC-MAIN-005

- **Scenario / Expected Result:** The owner-review cascade cancels an ACTIVE booking automatically with no admin action

### EC-MAIN-006

- **Scenario / Expected Result:** No test scenario produces a window where the block is off and no replacement restriction exists

### EC-MAIN-007

- **Scenario / Expected Result:** A booking paused by a venue emergency for 12 minutes, resumed, then paused by a resource emergency for 19 minutes, cancels at the 30-minute cumulative mark under the resource reason — the counter does not reset when the emergency type changes

### EC-MAIN-008

- **Scenario / Expected Result:** A PENDING booking whose remaining response time cannot fit any future intake window before `startAt` is cancelled immediately, not held to the cap

### EC-MAIN-009

- **Scenario / Expected Result:** A customer declines a Late Confirmation while its countdown is paused and the booking becomes CANCELLED `CUSTOMER_DECLINED_LATE_CONFIRM` at once

### EC-MAIN-010

- **Scenario / Expected Result:** Every one of the three interrupted-by-emergency reason codes is reachable in a seeded end-to-end run

---

## 2.10 Tournaments

### EC-TOUR-001

- **Scenario / Expected Result:** Creation at `startAt − 1h59m` is refused; at exactly `startAt − 2h` it succeeds

### EC-TOUR-002

- **Scenario / Expected Result:** A player request against a resource already holding a confirmed booking fails, and the booking is untouched

### EC-TOUR-003

- **Scenario / Expected Result:** A creator with one pending request cannot open a second anywhere on the platform

### EC-TOUR-004

- **Scenario / Expected Result:** A tournament at 7 participants at the checkpoint cancels and releases resources within the same transaction

### EC-TOUR-005

- **Scenario / Expected Result:** A tournament at a venue suspended after creation still passes its checkpoint

### EC-TOUR-006

- **Scenario / Expected Result:** Disabling the venue account during PENDING_VENUE_APPROVAL frees the creator's slot immediately with **no** cooldown

### EC-TOUR-007

- **Scenario / Expected Result:** An active tournament losing 3 of 4 resources continues and notifies; losing the 4th cancels it

### EC-TOUR-008

- **Scenario / Expected Result:** A tournament losing a resource keeps its original stored cost

---

## 2.11 Trust, Penalties and Bans

### EC-TRUS-001

- **Scenario / Expected Result:** A seeded history replayed from events reproduces the stored counter, stage, ban and Verified Player status exactly

### EC-TRUS-002

- **Scenario / Expected Result:** Reversing a Monday no-show on Friday changes the Monday position in the sequence, not the Friday position

### EC-TRUS-003

- **Scenario / Expected Result:** Three sequential attendance corrections on one booking yield exactly one effective outcome and one counter effect

### EC-TRUS-004

- **Scenario / Expected Result:** A correction that newly qualifies a user for a ban starts it today and cancels only bookings existing now

### EC-TRUS-005

- **Scenario / Expected Result:** A second qualification during an active ban produces one instance, an advanced stage, a reset counter and a `BAN_EXTENDED` record

### EC-TRUS-006

- **Scenario / Expected Result:** Replaying trust twice never cancels the same booking twice

---

## 2.12 Points, Referrals, Wallet and Fees

### EC-POIN-001

- **Scenario / Expected Result:** Reversing a +1,000 referral for a user holding 300 points leaves the balance at 0 and records a 700 unrecovered amount that is never later deducted

### EC-POIN-002

- **Scenario / Expected Result:** Changing the referral reward between signup and OTP verification grants the value in force **at verification**

### EC-POIN-003

- **Scenario / Expected Result:** Every terminal reason in the closed enum maps to exactly one of the three fee treatments, verified by an exhaustive test over the enum

### EC-POIN-004

- **Scenario / Expected Result:** No customer UI surface exposes a wallet screen

---

## 2.13 Reviews

### EC-REVI-001

- **Scenario / Expected Result:** An unverified user cannot post a review

### EC-REVI-002

- **Scenario / Expected Result:** Editing a hidden review leaves it hidden

### EC-REVI-003

- **Scenario / Expected Result:** A removed review cannot be restored by any admin action

### EC-REVI-004

- **Scenario / Expected Result:** A second review for the same venue 29 days after a removal is refused; on day 31 it is accepted as pending

### EC-REVI-005

- **Scenario / Expected Result:** A pending review does not move the venue's average rating

---

## 2.14 Platform Admin

### EC-PLAT-001

- **Scenario / Expected Result:** Disabling a venue account preserves all venue data and every existing booking

### EC-PLAT-002

- **Scenario / Expected Result:** A global fee change leaves historical bookings' stored fee untouched

### EC-PLAT-003

- **Scenario / Expected Result:** An area with one SUSPENDED venue cannot be deactivated

---

## 2.15 Notifications

### EC-NOTI-001

- **Scenario / Expected Result:** A push for any booking event deep-links to that exact Booking ID, including a revived Late Confirm booking

### EC-NOTI-002

- **Scenario / Expected Result:** The account-disable notification contains no cancellation language

### EC-NOTI-003

- **Scenario / Expected Result:** Re-enabling an account sends nothing by itself

### EC-NOTI-004

- **Scenario / Expected Result:** No notification is delivered for a transaction that rolled back

---

## 2.16 Audit and Historical Integrity / Reporting

### EC-AUDI-001

- **Scenario / Expected Result:** A booking that goes PENDING → expired → Late Confirm → CONFIRMED → ACTIVE → COMPLETED shows every step in order, with nothing removed

### EC-AUDI-002

- **Scenario / Expected Result:** Changing a venue's hourly price leaves every historical booking's stored price and total unchanged

### EC-AUDI-003

- **Scenario / Expected Result:** Every audited action in v5.0 §53.3 produces a retrievable record in an end-to-end run

---

# COMPLETENESS

- Cross-domain high-risk packs: **6**
- Source-derived PRD domain acceptance / edge scenarios: **91**
- These cases should be turned into executable tests during QA; they must not be reinterpreted into new Product behaviour.
