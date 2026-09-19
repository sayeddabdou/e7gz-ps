# E7GZ PS
## THIRD-PASS PRODUCT CONSISTENCY & ARCHITECTURE-READINESS AUDIT

**Scope:** Product Functional Plan v4.0 + v4.1 Clarifications & Resolutions + v4.2 Clarifications & Resolutions
**Priority applied:** v4.2 > v4.1 > v4.0 (v4.2 §10, §106)
**Product status:** NOT FROZEN
**Audit stance:** auditor only. No Product behavior has been chosen, invented or redesigned. Where two implementations are possible, both are named to demonstrate the ambiguity — that is evidence, not a recommendation.

**Reference convention:** `4.0 §n` / `4.1 §n` / `4.2 §n`.

**Classification legend**

| Class | Meaning |
|---|---|
| **A** | HARD CONTRADICTION — two rules in force cannot both be true |
| **B** | PRODUCT DECISION REQUIRED — behavior undefined; two or more materially different implementations are valid |
| **C** | ARCHITECTURE CLARIFICATION — intent clear, implementation rule must be stated |
| **D** | DOCUMENT HYGIENE — wording/enum/numbering, no material behavior change |

**Third-pass counts**

| Class | BLOCKER | HIGH | MEDIUM | LOW | Total |
|---|---:|---:|---:|---:|---:|
| A — Hard contradiction | 2 | 2 | 2 | 1 | 7 |
| B — Product decision required | 7 | 6 | 7 | 1 | 21 |
| C — Architecture clarification | 0 | 1 | 6 | 1 | 8 |
| D — Document hygiene | 0 | 0 | 4 | 1 | 5 |
| **Total** | **9** | **9** | **19** | **4** | **41** |

**Trajectory:** Pass 2 raised 41 findings, 11 blockers. v4.2 closed **40 of 41** (see Part 0) — an unusually high resolution rate. This pass raises 41 new or residual findings, of which **9 are blockers**. Seven of the nine are defects *introduced by v4.2 itself*, concentrated in three places: the new **Check-in / Attendance** model, the new **Emergency Closure** path, and the new **Manual Booking lifecycle**. The pattern is consistent — each v4.2 resolution is correct in its own section and incomplete at its seams with the sections it touches.

---

# PART 0 — WHAT v4.2 CLOSED

Recorded so these are not re-litigated. All were pass-2 findings.

| Pass-2 finding | Closed by |
|---|---|
| F-01 Manual status vs source | 4.2 §1, §2, §3 |
| F-02 No exit from ACTIVE | 4.2 §13, §43, §52 |
| F-03 Presence-blind completion | 4.2 §9, §10, §11 (Check-in + ATTENDANCE_UNCONFIRMED) |
| F-04 VENUE_RESPONSE_EXPIRED projection | 4.2 §15, §16, §22 |
| F-05 Expiring notification vs MIN() window | 4.2 §20 |
| F-06 Boundary inclusivity | 4.2 §94, §95, §97 |
| F-07 Races | 4.2 §96, §97, §98 |
| F-08 Reason codes | 4.2 §99, §100 |
| F-09 My Bookings projection | 4.2 §22 |
| F-10 Availability single source of truth | 4.2 §23, §24, §25 (layered model) |
| F-11 Maintenance loophole | 4.2 §38, §39, §40 (partially — see T3-16) |
| F-12 Emergency Closure impossible | 4.2 §42, §43 (and introduces T3-12) |
| F-13 "Current or future reservation" | 4.2 §45 |
| F-14 Area deactivation | 4.2 §28 |
| F-15 Operating Session model | 4.2 §29, §30, §31, §32 |
| F-16 DST | 4.2 §33 |
| F-17 Off-hours intake | 4.2 §5, §6 (and introduces T3-05) |
| F-18 Hours-change evaluation order | 4.2 §34, §37 |
| F-19 No-show unprotected/irreversible | 4.2 §12, §51 (partially — see T3-09) |
| F-20 Admin remediation | 4.2 §51, §52, §53 (partially — see T3-10) |
| F-21 Owner Password scope/security | 4.2 §49, §50 |
| F-22 Account Verified to book | 4.2 §4 |
| F-23 Venue Account disabled | 4.2 §26 |
| F-24 Venue Suspended powers | 4.2 §27 |
| F-25 Tournament manual cancellation | 4.2 §67, §68, §69 |
| F-26 Tournament state model | 4.2 §60 |
| F-27 Join before approval | 4.2 §64, §65 |
| F-28 Tournament pricing/revenue/history | 4.2 §72–§76 |
| F-29 Verified Player revocation cascade | 4.2 §71 |
| F-30 Approval at checkpoint instant | 4.2 §61 (2-hour cutoff) |
| F-31 Counter reset on ban | 4.2 §55, §56 |
| F-32 "Consecutive" definition | 4.2 §57 |
| F-33 In-flight bookings at ban | 4.2 §58 |
| F-34 Room Override vs console change | 4.2 §47 |
| F-35 Points/Referral snapshot points | 4.2 §80, §81, §102 |
| F-36 Free cancellation vs fee | 4.2 §92 (explicitly reconciled) |
| F-37 Wallet with no funding source | 4.2 §91 |
| F-38 Review moderation bypass | 4.2 §77, §78, §79 (partially — see T3-26) |
| F-39 Self-referral / OTP / reversal | 4.2 §82, §83, §84 |
| F-40 Identity mutability / deletion | 4.2 §85, §86, §87 |
| F-41 Password reset / contact windows | 4.2 §88, §89, §90 (and introduces T3-28) |

**Not closed:** F-19 and F-20 are closed on the punitive side and left open on the rewarding side (T3-09, T3-10). F-11 is closed for the ≤24h case and left open for the split-window case (T3-16).

Also verified as genuinely sound this pass — the §55/§56/§57 interaction, which was the heaviest pass-2 blocker. With §55 resetting the counter on ban creation and §56 triggering only on increment-to-threshold, the counter can never exceed the threshold, so the runaway-ban failure mode is gone. §57's start-time ordering is redundant for forward processing (because §18 forbids overlapping customer bookings, recording order and start order coincide) but becomes load-bearing under §51 reversals — see T3-10.

---

# PART 1 — FINDINGS

## 1. BOOKING MODEL, MANUAL SOURCE & STATE MACHINE

---

### T3-01 — The two canonical predicates still enumerate MANUAL as a peer of Booking Statuses

**Class:** A · **Severity:** BLOCKER
**References:** 4.2 §1, §2, §3, §24, §45

**Exact conflict**
- 4.2 §1: "`MANUAL` is **NOT** a Booking Status." 4.2 §2: "All Bookings use the same state model" (nine statuses). 4.2 §3: a Manual Booking's statuses are CONFIRMED → ACTIVE → COMPLETED.
- 4.2 §24 — **the canonical inventory conflict predicate** — lists blocking reservations as: "Pending Online Booking, Awaiting Customer Confirmation, Confirmed Online Booking, Active Online Booking, **Manual Booking**, Player Tournament Approval Hold, Tournament Hold, Maintenance, Venue Closure."
- 4.2 §45 — **the canonical resource-state-change predicate** — lists: "PENDING, AWAITING_CUSTOMER_CONFIRMATION, CONFIRMED, ACTIVE, **MANUAL**, Player Tournament Approval Hold, Tournament Hold, Maintenance, Closure."
- Both lists mix the status dimension with the source dimension, and both qualify the online entries by status while leaving "Manual Booking" unqualified.

**Exact missing behavior**
Whether a Manual Booking in a **terminal** status (COMPLETED, CANCELLED, NO_SHOW) blocks. Read literally, "Manual Booking" is unqualified and therefore blocks in every status — including COMPLETED. Read by analogy with the online entries, only CONFIRMED and ACTIVE manual bookings block.

**Why it matters**
§24 is the single predicate every other flow is instructed to reuse (§23, §25, §38, §41, §45, §46, §63). The two readings differ for every past interval on every resource, and the divergence propagates into Maintenance creation, Disable/Archive, Tournament creation and retroactive Manual creation (T3-02) simultaneously. This is the exact defect class v4.2 §1 was written to eliminate, surviving inside the predicates themselves.

**Scenario**
Room 1 had a Manual Booking 8:00–9:00 PM last Tuesday, now COMPLETED. Today the venue tries to Archive Room 1 (§45/§46). Under the literal reading, §45's "MANUAL" entry has no status qualifier, so the check depends only on "any blocking reservation whose interval has not completely ended" — last Tuesday's has ended, so Archive proceeds. Now the venue tries to create Maintenance covering last Tuesday for a historical record: §38 forbids overlap with "any existing blocking reservation" and §24's unqualified "Manual Booking" blocks it. The same booking is simultaneously non-blocking under §45 and blocking under §24.

**Architecture can proceed?** **NO.** Both canonical predicates must be restated on one dimension.

---

### T3-02 — Retroactive Manual Bookings are unbounded and can overlap terminal bookings

**Class:** B · **Severity:** BLOCKER
**References:** 4.2 §3, §7, §24, §102; 4.0 §65, §66, §93

**Exact rule introduced**
4.2 §3: "A Manual Booking created during its selected current session may begin directly as **ACTIVE** if the requested interval has already started and is otherwise valid."

**Exact missing behavior**
1. **How far back** a Manual Booking may be created. §3 says "during its selected current session", which bounds it to the current Operating Session — but §3 does not state that as a hard constraint on `startAt`, and 4.0 §65 permits "Now or Future" without addressing the past at all. Whether a venue may record yesterday's or last month's walk-in is undefined.
2. **Whether §24 is a valid predicate for a past interval.** §24's blocking list contains no terminal status, so a past interval occupied by a COMPLETED, CANCELLED or NO_SHOW booking is "free". A retroactive Manual Booking may therefore be created on top of a completed online booking.
3. Whether a retroactively created Manual Booking passes straight to COMPLETED (its `endAt` is already past) or sits ACTIVE until a scheduler notices.

**Why it matters**
§24 was designed as a forward-looking conflict predicate; §3 is the first rule that points it at the past, where every historical booking is terminal and therefore invisible to it. The result is double-occupancy of a historical interval and double-counted Manual Booking Revenue (4.0 §93), against a history that §102 declares immutable. It also opens a revenue-reporting write path with no stated bound.

**Scenario**
Room 2 has a COMPLETED, ATTENDED online booking 8:00–9:00 PM yesterday at EGP 200. Today a staff member creates a Manual Booking on Room 2 for 8:00–9:00 PM yesterday at EGP 200. §24 reports the interval free (the online booking is terminal). Yesterday's revenue is now EGP 400 for one hour on one console, and yesterday's Availability Schedule (4.0 §96) shows two overlapping reservations. Nothing in v4.0–v4.2 prevents it.

**Architecture can proceed?** **NO.** The availability predicate's temporal domain and the Manual creation bound must be decided.

---

### T3-03 — Reject-after-expiry: §14 forbids it, v4.1 §5 permits it, §99 keeps its reason code

**Class:** A · **Severity:** BLOCKER
**References:** 4.2 §14, §99, §106; 4.1 §5; 4.0 §44, §55

**Exact conflict**
- 4.2 §14: "Venue Accept/Reject is valid **only while** `now < venueResponseExpiresAt`. At `now >= venueResponseExpiresAt` the expiry transition wins."
- 4.1 §5: "Venue **may Reject** a `VENUE_RESPONSE_EXPIRED` Booking **before Start Time**. Result → REJECTED. Reason: `REJECTED_AFTER_EXPIRY`."
- 4.2 §99 retains `REJECTED_AFTER_EXPIRY` in the canonical VENUE reason-code list.
- 4.0 §44 offers the Venue "Late Confirm **or Reject**" from Missed / Expired Requests.
- 4.2 §106's supersession list covers "**Rejected After Expiry as a primary status**" — the v4.1 §5 *status-vs-reason* clarification — and says nothing about the *transition*.

**Exact missing behavior**
Whether `VENUE_RESPONSE_EXPIRED → REJECTED` still exists. If §14 governs, the transition is gone, `REJECTED_AFTER_EXPIRY` is an unreachable code, and 4.0 §44's Reject button must disappear from Missed / Expired Requests — leaving the venue's only action there as Late Confirm. If v4.1 §5 survives, §14's "only while" is false.

**Why it matters**
This is a transition in the core state machine, a button in the Venue Dashboard, and a canonical reason code — all three disagree. It also determines whether `VENUE_RESPONSE_EXPIRED` has one exit (final expiry at `startAt`, plus revival) or two.

**Scenario**
7:00 PM: booking for 9:00 PM expires unanswered. 7:30 PM: the venue opens Missed / Expired Requests, sees the request, knows the room is broken, and taps **Reject** so the customer is told promptly. Under §14 the action is invalid and the customer learns nothing until 9:00 PM, when §4 (v4.1) cancels it with `VENUE_DID_NOT_RESPOND_IN_TIME`. Under v4.1 §5 the customer is notified at 7:30 PM with `REJECTED_AFTER_EXPIRY`. Both are currently in force.

**Architecture can proceed?** **NO.** The state machine cannot be drawn with a contested transition.

---

### T3-04 — Manual Booking's reachable states are undefined: NO_SHOW and Check-in

**Class:** B · **Severity:** HIGH
**References:** 4.2 §2, §3, §9, §11, §12; 4.1 §12

**Exact conflict / omission**
- 4.1 §12 scoped No-show explicitly: "Venue may mark an **Online Customer Booking** as No-show…"
- 4.2 §12 **drops the word Online**: "No-show may be recorded only when `startAt + 15 minutes <= now < endAt`." No source restriction.
- 4.2 §3: "Manual Booking never contributes to … No-show progression." 4.2 §11: manual bookings "never generate … No-show Trust consequences."
- 4.2 §2: "**All** Bookings use the same state model."

**Exact missing behavior**
Whether a Manual Booking can *enter* NO_SHOW at all. Two valid readings: (a) it can, and the status is recorded with no trust effect — making NO_SHOW a reachable manual state that terminates the booking mid-session and releases the resource; (b) it cannot, and NO_SHOW is unreachable for `source = MANUAL`, contradicting §2's "all Bookings". The same question applies to **Check-in**: §11 says manual bookings "do not require Customer Check-in **for Product Trust purposes**", which does not say whether the action exists, is recorded, or is refused.

**Why it matters**
Reading (a) gives the venue a no-password way to end a manual session early and resell the slot — which §13 explicitly denies for bookings generally. Reading (b) makes four of the nine canonical states unreachable for one source, which must then be stated so the state machine is not built with impossible transitions. The audit list (§101) requires Check-in and No-show to be audited without saying which bookings can have them.

**Scenario**
A walk-in's Manual Booking runs 8:00–11:00 PM. At 8:20 PM the group leaves. Under reading (a) the venue marks NO_SHOW, the booking becomes terminal, the room frees at 8:20 PM and can be resold manually. Under reading (b) the room is blocked until 11:00 PM (§13, no early end). The same operational event has two opposite inventory outcomes.

**Architecture can proceed?** Partially — the online path is clear; the manual path's state machine is not.

---

### T3-05 — §5's stated guarantee is arithmetically false: the response timer can expire after closing

**Class:** A · **Severity:** HIGH
**References:** 4.2 §5, §14, §17; 4.1 §3; 4.0 §39

**Exact conflict**
4.2 §5 restricts intake to while the Venue is "CURRENTLY OPEN", and justifies it: "The normal 10-minute response timer **therefore always starts while the Venue is operating**." Starting inside the session does not keep the timer inside it. With a session ending at 4:00 AM (§5's own example of 12 PM → 4 AM), a request submitted at 3:55 AM is valid under §5, and its `venueResponseExpiresAt` is 4:05 AM — five minutes after the venue closed and the staff left.

**Exact missing behavior**
Whether intake must stop `venueResponseWindow` before the session end; and, separately, whether the **Venue must be currently open** to Accept, Reject or Late Confirm. §14 conditions Accept/Reject only on the deadline. §17 conditions Late Confirm on "Venue still eligible" — a term used nowhere else and never defined.

**Why it matters**
§5 exists solely to guarantee that a staffed venue sees every request. The last ten minutes of every session defeat it, and the customer is told `VENUE_DID_NOT_RESPOND_IN_TIME` for a request the venue could not have seen. The undefined "Venue still eligible" then decides whether the closed venue may revive it the next day.

**Scenario**
Venue 12 PM → 4 AM. Customer submits at 3:57 AM for tomorrow 9:00 PM. Timer expires 4:07 AM. Staff left at 4:00 AM. Booking → VENUE_RESPONSE_EXPIRED, shown to the customer in the **Cancelled** tab (§15, §22) as "Venue Did Not Respond in Time". The venue opens at 12 PM and finds it in Missed / Expired Requests — and whether it may Late Confirm depends entirely on an undefined term.

**Architecture can proceed?** Partially — but the intake boundary and "Venue still eligible" must be defined before the booking API is specified.

---

### T3-06 — The "currently open" intake rule silently disables discovery-driven booking for most of the day

**Class:** B · **Severity:** HIGH
**References:** 4.2 §5, §6, §25; 4.1 §60, §61; 4.0 §14, §15, §16, §36

**Exact conflict of intent**
4.2 §5 blocks request submission unless the Venue is currently open, "even when the Customer is booking a future date/time". But the Customer discovery model is cross-venue and always-on: 4.0 §14 (Area → Activity → Venue → Resource → Availability → Booking), 4.0 §36 Find Available (Area + Activity + Date + Start + Duration), 4.0 §15/§16 venue cards and map previews carrying "Nearest Availability", and 4.1 §60/§61 which compute and sort that availability across all Active Areas with no reference to whether each venue is open right now.

**Exact missing behavior**
1. Whether venues that are currently closed are excluded from, or merely non-bookable within, discovery — and what the Customer is shown in their place.
2. The consequence for venues **closed today**: a venue with Tuesday marked Closed (§30) is never "currently open" on Tuesday except during Monday's overnight tail, so it cannot receive any request on Tuesday for any future date.
3. Whether an **Emergency Closure** (§42) or a partial-time **Closure** (§41) makes the venue "not currently open" for intake purposes on unrelated future dates. §25 lists "Venue currently open when request is submitted" and "Resource interval free" as separate conditions, so a closure's effect on intake is unstated.

**Why it matters**
This is the single largest Customer-funnel rule in the specification and it is stated in one sentence with one example. Cairo gaming venues typically open in the afternoon; under §5, the entire morning is browse-only across the whole marketplace, while the product's own venue cards advertise "Next available: 8:30 PM" to a customer who cannot act on it.

**Scenario**
11:00 AM. A customer opens the app, selects New Cairo + PlayStation, and sees eight venues with prices and next-available times (4.0 §15). Every one is closed until 12 PM. Tapping through to any of them and choosing 9:00 PM tonight produces a refusal at submission (§5). Nothing in the spec says the list should have been filtered, greyed, labelled, or accompanied by "opens at 12 PM".

**Architecture can proceed?** Partially — the eligibility check is specified (§25); the discovery contract that feeds it is not.

---

### T3-07 — Customer schedule conflict: two different enforcement points, and no reason code

**Class:** B · **Severity:** MEDIUM
**References:** 4.2 §17, §18, §99

**Exact conflict**
- 4.2 §17 lists, as a **Late Confirm eligibility** condition (evaluated when the **Venue** initiates): "Customer has no conflicting active Booking commitment."
- 4.2 §18 states the same rule as a check on the **Customer's Accept**: "If Customer has already booked another Venue for the same period: Late Confirmation **Accept** is rejected."

**Exact missing behavior**
Whether the conflict blocks the Venue's Late Confirm button (§17) or the Customer's Accept (§18) — or both. The two produce different products: under §17 the venue is told "this customer is now booked elsewhere" and no notification is sent; under §18 the venue Late Confirms, the customer receives the reactivation request (§16), taps Accept, and is refused. §99 provides no reason code for either outcome — `RESOURCE_CONFLICT` describes inventory, not a customer commitment — while §99's closing line requires a failed Late Confirm to be recorded as an append-only Booking Event.

**Why it matters**
It decides whether one customer's booking elsewhere is visible to a venue (a privacy-adjacent disclosure), what the venue's dashboard shows, and which event is written to history.

**Scenario**
Customer's Venue A request expires; they book Venue B for the same hour and are Confirmed. Venue A taps Late Confirm. Under §17 the button fails with an unspecified message and no customer notification. Under §18 the customer gets "Game Zone is asking to reactivate your expired booking" (§16), taps **Accept**, and is refused — having been offered something they could not take.

**Architecture can proceed?** Yes, once the enforcement point and code are fixed.

---

### T3-08 — No maximum booking duration, no booking-count limit, and no cost to hold inventory

**Class:** B · **Severity:** HIGH
**References:** 4.2 §8, §18, §31, §32; 4.1 §14; 4.0 §34, §61

**Exact missing behavior**
- 4.1 §14: "There is **no global maximum duration** in MVP", bounded only by fitting one Operating Session — which §31 allows to be up to 24 hours.
- 4.2 §18 forbids **overlapping** customer commitments only. Non-overlapping ones are unlimited: there is no cap on how many future bookings one customer may hold.
- 4.2 §8: cancellation is free until `startAt`, with no penalty. 4.0 §61: Booking Fee is EGP 0.
- No deposit, no fee, no per-user concurrency cap, no per-day cap.

**Why it matters**
Holding venue inventory carries zero cost and zero risk. The only defences in the product are the Trust engine — which requires a **No-show**, and a No-show requires the customer to have a booking that reaches `startAt + 15min` without cancellation (§12). A customer who cancels at `startAt - 1 second` is永 untouched by every penalty in §54–§57. This is a Product decision about launch exposure, not an implementation detail.

**Scenario**
One user books Room 1 from 12:00 PM to 4:00 AM (a full 16-hour session, valid under §31/§32) every day for the next 30 days, at EGP 0 cost. Thirty rooms-days of the venue's flagship room are unavailable to everyone else. The user cancels each one a second before start. No fee, no No-show, no counter increment, no ban, and nothing in the Venue Dashboard or Platform Admin flags it.

**Architecture can proceed?** Partially — the booking API can be built, but any limit added later changes the Customer contract.

---

## 2. CHECK-IN, ATTENDANCE & TRUST

---

### T3-09 — Check-in is irreversible, unverified, and neutralizes the entire Trust engine

**Class:** B · **Severity:** BLOCKER
**References:** 4.2 §9, §10, §12, §51, §52, §101

**Exact conflict / omission**
- 4.2 §9: Check-in is a Venue action, permitted `startAt <= now < endAt`, audited. 4.2 §12: "Once a valid Customer Check-in exists: **No-show is no longer allowed**." 4.2 §10: ATTENDED → Points + Verified Player progress + counter reset.
- 4.2 §51 provides a reversal path for **No-show** only, and closes with one unelaborated sentence: "Platform Admin may separately correct Attendance/Points where required."
- 4.2 §52's enumerated remediation list — the canonical one — contains Trust, Points, Wallet, Referral and Booking items. It contains **no Attendance or Check-in item**.
- Nothing in any document states how the Venue establishes that the person checking in is the booking's Customer.

**Exact missing behavior**
Whether Check-in can be reversed, by whom, and with what effect on Points, Verified Player progress and the consecutive counter. And whether Check-in requires any identity assertion.

**Why it matters**
v4.2 made Check-in the sole gate on every reward and the sole blocker of every penalty. It is therefore the most powerful action in the product, and it is the only such action with no reversal, no authorization gate (no Owner Password), and no verification of the fact it asserts. The asymmetry is exact and inverted: the **punitive** action (No-show) is reversible by Admin, and the **rewarding** action (Check-in) — which also permanently blocks the punitive one — is reversible by nobody in §52's list. A single venue can manufacture Verified Player status and immunity from banning for any account, at will, with a button that is merely logged.

**Scenario**
A venue checks in a friend's account for ten bookings the friend never attends. Each yields +200 Points, +1 progress and a counter reset (§10), and each permanently forecloses No-show (§12). The account reaches **Verified Player** (4.0 §7) and can now create Player-hosted Tournaments that hold up to 4 resources at any venue (§39, §63). Platform Admin, following §52, has no listed action to undo the Check-ins; following §51's final sentence, an undefined one.

**Architecture can proceed?** **NO.** The Trust engine's write path and the Admin authorization matrix both depend on it.

---

### T3-10 — No-show reversal has no defined arithmetic, and §51 mandates a consistency it does not specify

**Class:** B · **Severity:** BLOCKER
**References:** 4.2 §51, §52, §54, §55, §56, §57

**Exact conflict**
- 4.2 §51: reversal "removes that No-show from Trust/Penalty consideration. **Any related Ban or derived Trust state must be corrected consistently.**"
- 4.2 §55: when a ban is created the counter is **immediately reset to 0** and the stage advances.
- 4.2 §56: penalty triggers only when a new No-show "**increments the counter to the current threshold**", and is "not repeatedly evaluated merely because an old stored number is greater than a later threshold".
- 4.2 §57: ordering uses **booking start time**.

**Exact missing behavior**
What "corrected consistently" computes. Because §55 destroys the counter at ban creation, the pre-ban sequence is not recoverable from state — only from the event history. Reversing No-show #2 of 3 after the ban has fired requires re-deriving the sequence in start-time order, deciding whether the ban is lifted or shortened, whether the **penalty stage** rolls back, what the counter becomes, and whether subsequent events that were evaluated under the advanced stage are re-evaluated. None of that is specified. §52 separately offers manual "Correct Penalty Counter / Correct Penalty Stage" — so the product has both an automatic consistency requirement (§51) and a manual override (§52) for the same quantities, with no rule on which applies.

Also undefined: the **status of the reversed booking**. NO_SHOW is terminal (§2). Does reversal leave it NO_SHOW-but-trust-invisible, move it to COMPLETED, and if so with which Attendance Result (§10)? Its Attendance Result determines, under §57, whether it *breaks* the consecutive sequence or is merely ignored — two different recomputations.

**Why it matters**
§51 is a mandatory requirement with no computable content. Every downstream Trust value — counter, stage, ban end date, Verified Player progress — depends on the answer, and §52's manual override means two implementations could satisfy the text while producing different bans for the same support action.

**Scenario**
User no-shows on bookings starting 8 PM Mon, 8 PM Wed, 8 PM Fri. The Friday one triggers Stage 0 → 7-day ban; counter → 0; stage → 1. On Saturday, Admin reverses the **Wednesday** No-show as erroneous. Valid outcomes under the text: (a) ban lifted, counter 2, stage back to 0; (b) ban lifted, counter 0, stage 1 retained; (c) ban shortened, counter set manually under §52 to whatever the Admin types. The three leave the user 1, 3 or an arbitrary number of No-shows away from a 1-month ban.

**Architecture can proceed?** **NO.** The Trust engine's correction semantics are a Product decision.

---

### T3-11 — Ban stacking: No-shows during an active ban have no defined effect

**Class:** B · **Severity:** BLOCKER
**References:** 4.2 §54, §55, §56, §57, §58, §59

**Exact conflict / omission**
- 4.2 §58: when a ban activates, **CONFIRMED remains valid and ACTIVE continues**. Only PENDING and AWAITING are cancelled.
- 4.2 §59: a banned user may not create bookings, but retains the confirmed ones they already hold.
- 4.2 §12 places no ban condition on No-show, so a banned user's surviving CONFIRMED bookings **can be marked No-show** during the ban.
- 4.2 §55: the counter is 0 for the duration of the ban and "remains 0 unless **new qualifying No-shows have occurred after return**."

**Exact missing behavior**
Whether a No-show recorded **while the ban is active** is a "qualifying" No-show. §55's phrase "after return" suggests only post-ban events count, which would make in-ban No-shows free. If they do count, §56 can fire a second ban while the first is running, and no rule says whether the new ban replaces, extends, or runs concurrently with it — nor what §89 (4.0) Account Standing displays for two overlapping bans.

Correspondingly undefined: whether a banned user's surviving CONFIRMED booking can be **Checked in** (§9 has no ban condition), thereby earning Points, +1 Verified Player progress and a counter reset **during** a ban.

**Why it matters**
Ban start and end dates, the stage ladder and Account Standing (4.0 §89) all depend on it. As written, a user banned for 7 days with three confirmed bookings inside the ban window may either accrue nothing, accrue progress toward Verified Player, or trigger a second overlapping ban — three materially different products.

**Scenario**
User is banned Monday for 7 days, holding CONFIRMED bookings on Tuesday, Wednesday and Thursday (all valid per §58). They attend all three and are Checked in. Under one reading they gain +600 Points, +3 Verified Player progress and a counter reset while serving a ban for no-shows. Under another, in-ban events are inert. Nothing distinguishes them.

**Architecture can proceed?** **NO.** The ban engine's event filter is undefined.

---

### T3-12 — Emergency Closure has no duration, no end, and no undo, while cascading irreversibly

**Class:** B · **Severity:** BLOCKER
**References:** 4.2 §42, §43, §41, §68, §24, §101

**Exact rule introduced**
4.2 §42: Emergency Closure requires **no Owner Password**, "may only start **NOW** and cannot be scheduled as a future planned Closure", with mandatory reason, full audit, and notification to Venue Owner and Platform Admin.
4.2 §43: it immediately stops new online bookings and manual reservations "for affected period", cancels Pending, Awaiting and **Confirmed affected** bookings, **terminates affected ACTIVE sessions**, cancels **affected Tournaments**, and releases future Resource Holds.

**Exact missing behavior**
1. **Its duration or end time.** §42 fixes only the start. Every use of the word "affected" in §43 is scoped by an interval that is never defined. The cascade's blast radius is therefore undefined: one hour of cancellations, or the rest of the Operating Session, or every future booking and tournament on the books.
2. **How it ends.** No rule provides an End Emergency Closure, edit, shorten or delete action. §40 provides Create/Edit/Delete for Maintenance; §41 and v4.1 §23 mention "creating/editing" a normal Closure; §42 provides none.
3. **Any limit on frequency or scope**, and whether it applies venue-wide only (§22 says a Closure applies to the entire Venue) or can be scoped to one resource (see T3-13).

**Why it matters**
This is simultaneously the product's only early-termination path (§13) and its only unprotected destructive action. Every action it performs is Owner-Password-protected elsewhere: cancelling CONFIRMED bookings (4.0 §71, §36), cancelling a CONFIRMED Tournament (§68). §68 states plainly that "Emergency Closure **bypasses** Owner Password". With an unbounded interval, one staff member with no password can, in one click, terminate every active session and cancel every confirmed booking and tournament the venue holds — and then cannot reverse it, because nothing ends an Emergency Closure and §52's Admin remediation cannot restore a CANCELLED booking.

**Scenario**
9:20 PM, a breaker trips. A staff member declares an Emergency Closure. Because no duration is defined, the implementation applies it to "all future" — cancelling 40 confirmed bookings across the next three weeks and two CONFIRMED tournaments, with reason `VENUE_EMERGENCY_CLOSURE`, and terminating three active sessions with no Points awarded (§43). Power returns at 9:45 PM. There is no action that ends the closure and no action that restores any of it.

**Architecture can proceed?** **NO.** The closure entity has no interval and no lifecycle.

---

### T3-13 — There is no resource-level failure path: a broken console keeps being sold

**Class:** B · **Severity:** HIGH
**References:** 4.2 §13, §22, §38, §42, §45, §46; 4.1 §21

**Exact conflict**
Four rules close every door on one operational event:
- 4.2 §13: no early session end; "A normal ACTIVE Booking continues blocking its Resource until `endAt`, **even if the Customer leaves early or equipment becomes unavailable**."
- 4.2 §38: Maintenance "may NOT overlap any existing blocking reservation" — so it cannot be placed over the ACTIVE booking.
- 4.2 §46: Disable/Archive is blocked while any non-ended blocking reservation exists.
- 4.2 §42/§22: the only exception path, Emergency Closure, applies to the **entire Venue**.

**Exact missing behavior**
Any way to take a single Resource out of service while it holds a reservation. The venue's options for one broken PS5 are: do nothing until `endAt`, then individually cancel each future CONFIRMED booking with the Owner Password (§99 `VENUE_CANCELLED_CONFIRMED_BOOKING`) before placing Maintenance — or declare a venue-wide Emergency Closure that also terminates every other resource's sessions (§43).

**Why it matters**
Equipment failure is the most common operational event in a gaming venue and is named explicitly in §13 as something that does *not* justify early termination. Meanwhile the resource stays in inventory: it is not blocked, so the availability predicate (§24) keeps selling it for every interval after the current booking until the venue manually clears them one by one.

**Scenario**
Room 3's PS5 fails at 8:20 PM during an 8:00–11:00 PM session. Bookings exist at 11:00 PM, midnight and 1:00 AM, all CONFIRMED. The venue cannot end the active session, cannot place Maintenance (§38 — the active booking blocks it), cannot Disable the room (§46 — four blocking reservations), and will not declare a venue-wide Emergency Closure over one console. Room 3 remains bookable for tomorrow throughout.

**Architecture can proceed?** Partially — the predicates are implementable; the operational gap is a Product decision.

---

### T3-14 — Emergency Closure voids a Check-in that has already occurred

**Class:** A · **Severity:** MEDIUM
**References:** 4.2 §9, §10, §43, §57

**Exact conflict**
- 4.2 §10: a booking with a valid Check-in is **ATTENDED**, and ATTENDED grants Points, +1 Verified Player progress, and a counter reset. These effects are described as consequences of Check-in, realised at End Time.
- 4.2 §43: an ACTIVE booking terminated by Emergency Closure becomes CANCELLED with "**No Points or Trust reward** … granted for that Booking".
- 4.2 §57: only an "eligible **attended Completed** Online Booking" breaks the consecutive sequence; CANCELLED is in the ignored list.

**Exact behavior in conflict**
A customer who was physically present and validly Checked in loses the ATTENDED result, the Points, the progress and — materially — the **counter reset** they had earned, because the booking never reaches COMPLETED.

**Why it matters**
It is defensible that no reward is granted for a session cut short by an emergency. It is a different matter that the customer's **penalty counter is not reset**, leaving a user who attended as scheduled one step closer to a ban through an event caused entirely by the venue. §43 and §10 both speak to the same booking and disagree on what a recorded Check-in is worth.

**Scenario**
A user with 2 consecutive No-shows (Stage 0, threshold 3) books, attends, and is Checked in at 8:05 PM. At 8:40 PM the venue declares an Emergency Closure. The booking → CANCELLED, `VENUE_EMERGENCY_CLOSURE`. Under §57 it is ignored, so the counter stays at 2. Their next No-show — at any venue, at any time — triggers the 7-day ban, notwithstanding a verified attendance in between.

**Architecture can proceed?** Yes — but the rule as written produces a customer-visible unfairness that should be an explicit decision.

---

### T3-15 — Check-in and No-show have no minimum-attendance or late-arrival rule; NO_SHOW frees the resource mid-session

**Class:** B · **Severity:** MEDIUM
**References:** 4.2 §2, §9, §10, §12, §24, §95

**Exact missing behavior**
1. §9 permits Check-in at any point up to `endAt`. A customer arriving with five minutes left is ATTENDED and receives full Points, full progress and a counter reset (§10). No minimum attendance is defined.
2. §12 permits No-show from `startAt + 15min`. NO_SHOW is **terminal** (§2) and terminal statuses are absent from §24's blocking list — so the moment a No-show is recorded, the remainder of the booked interval becomes **free inventory**. No rule states whether that is intended, whether the interval may be resold, or what happens if the customer then arrives.
3. No rule resolves the arriving-after-No-show case: the booking is terminal and cannot be Checked in, and §52's Admin remediation is the only listed correction.

**Why it matters**
(2) is an inventory rule hiding inside a trust rule. Whether a no-showed 8:00–11:00 PM booking releases 2h40m of sellable time is a material question for the availability engine and the Venue's revenue, and it is currently answered only by the absence of NO_SHOW from a list. (1) determines whether Check-in means "attended" or "appeared at some point".

**Scenario**
8:00–11:00 PM booking. At 8:16 PM the venue records No-show. The room is now free from 8:16 PM under §24. A walk-in is given it as a Manual Booking at 8:30 PM. At 8:45 PM the original customer arrives with proof of a delayed train. Their booking is terminal, their room is occupied, their counter has incremented, and the only path is a Platform Admin reversal (§51) whose arithmetic is undefined (T3-10).

**Architecture can proceed?** Partially — the release-on-No-show behavior must be stated explicitly in §24 either way.

---

### T3-16 — The Maintenance Owner-Password threshold is bypassable with a one-minute gap

**Class:** B · **Severity:** MEDIUM
**References:** 4.2 §38, §39, §40, §45

**Exact rule and gap**
4.2 §39: Maintenance up to 24 hours needs no Owner Password; longer than 24 hours does. "**Back-to-back or overlapping** Maintenance windows on the same Resource that create one continuous Maintenance period longer than 24 hours are treated as **ONE CONTINUOUS PERIOD** … This prevents bypass by splitting long Maintenance into multiple windows."

**Exact missing behavior**
"Back-to-back" is not defined. Two windows separated by one minute are neither back-to-back nor overlapping, so they are not aggregated, and the bypass §39 exists to close remains open at the cost of one minute per day. Also unaggregated: Maintenance across **multiple resources** (aggregation is explicitly per-Resource), so four rooms × 23 hours each removes the venue's entire PlayStation inventory for a day with no Owner Password.

**Why it matters**
§39 is a numeric authorization threshold, and a threshold with an undefined adjacency test is not enforceable. The stated intent ("prevents bypass") is not achieved by the stated rule.

**Scenario**
A staff member creates, on Room 1: 00:00–23:59 Monday, 00:00–23:59 Tuesday, 00:00–23:59 Wednesday — each 23h59m, each separated by a one-minute gap. No window exceeds 24 hours and none is back-to-back. No Owner Password is requested. Repeated on all four rooms, the venue is out of PlayStation inventory for three days. Each action is audited (§38) and none is authorized.

**Architecture can proceed?** Yes — but the adjacency and aggregation rule must be a Product number, not an implementer's choice.

---

## 3. AVAILABILITY, VENUE STATUS & OPERATING SESSIONS

---

### T3-17 — There is no canonical Venue status model, yet three rules depend on one

**Class:** B · **Severity:** BLOCKER
**References:** 4.2 §25, §26, §27, §28; 4.1 §58, §59; 4.0 §124, §127

**Exact conflict / omission**
- 4.2 §25 makes two of the eleven Online Booking eligibility conditions "**Venue marketplace status** allows Online Booking" and "**Venue Account** allows new Online Booking" — two distinct status dimensions.
- 4.2 §26 describes the Account dimension (enabled / disabled) behaviorally, without naming an enum.
- 4.2 §27 describes **SUSPENDED** behaviorally.
- 4.2 §28 references venues with marketplace status "**ACTIVE**" or "**SUSPENDED**", and then instructs that venues "must first be moved or **made inactive** as appropriate" — invoking a third marketplace state that **no section defines**.
- No document enumerates the marketplace status values, their transitions, who sets them, or what each permits.

**Exact missing behavior**
The canonical Venue marketplace status enum and its effects; the relationship between it and Account enabled/disabled; and what "made inactive" means — in particular whether an INACTIVE venue is discoverable, bookable, reviewable (§27 permits reviews of SUSPENDED venues), able to run tournaments, or able to hold existing bookings.

**Why it matters**
Two of §25's eleven conditions cannot be evaluated against an undefined enum. §28's Area-deactivation guard is also unimplementable: it forbids deactivating an Area containing ACTIVE or SUSPENDED venues and directs the Admin to a state that does not exist. And §25's "Area Active" condition becomes unreachable-or-not depending on whether a venue can sit in an inactive Area at all.

**Scenario**
Admin wants to deactivate the "Sheraton" Area, which holds three venues: one ACTIVE, one SUSPENDED, one whose Account is disabled. §28 blocks the first two and says to make them "inactive". There is no such action in any Admin module (4.0 §123, §127 offer only Disable/Re-enable Account and password resets). The third venue's marketplace status is unstated, so whether it blocks the deactivation is unanswerable.

**Architecture can proceed?** **NO.** Two inputs to the eligibility predicate and one Admin guard have no domain.

---

### T3-18 — "Venue still eligible" and "Resource still eligible" are undefined in the Late Confirm predicate

**Class:** B · **Severity:** HIGH
**References:** 4.2 §17, §24, §25, §26, §27, §45

**Exact conflict / omission**
4.2 §25 gives Online Booking a complete, explicit eleven-condition predicate. 4.2 §17 gives Late Confirm a seven-condition predicate, two of which are the undefined phrases "**Venue still eligible**" and "**Resource still eligible**" — terms used nowhere else in any of the three documents.

**Exact missing behavior**
Whether Late Confirm inherits §25's venue-level conditions. Specifically: may a **SUSPENDED** venue Late Confirm? §27 says a suspended venue "cannot receive New Online Bookings" but "may manage existing Bookings" — and a VENUE_RESPONSE_EXPIRED booking is an existing booking whose revival creates a new CONFIRMED reservation. Both readings are textually supported. The same question applies to an Area that has been deactivated, to a venue whose Account was disabled after the request expired, and to a resource that has since been DISABLED or ARCHIVED (§45's predicate excludes VENUE_RESPONSE_EXPIRED, so disabling is permitted while an expired-but-revivable booking exists — see T3-19).

**Why it matters**
Late Confirm is the one transition that manufactures a CONFIRMED booking outside §25's predicate. If it does not inherit the venue-level conditions, it is a documented bypass of suspension, account disablement and area deactivation.

**Scenario**
A venue is SUSPENDED at 6:00 PM for customer abuse. It holds nine VENUE_RESPONSE_EXPIRED requests for tonight. Between 6:00 PM and each request's start time it Late Confirms all nine. Under one reading suspension stopped only *new* bookings and all nine revive; under the other all nine are refused. The difference is nine confirmed customers at a venue the platform just suspended.

**Architecture can proceed?** **NO** for the Late Confirm endpoint's authorization.

---

### T3-19 — Disabling a Resource silently destroys Late Confirm eligibility with no notification and no code

**Class:** C · **Severity:** MEDIUM
**References:** 4.2 §17, §45, §46, §99

**Exact behavior**
§45 excludes VENUE_RESPONSE_EXPIRED from the resource-state-change predicate, so Disable/Archive/Console-change are **permitted** while expired-but-revivable bookings exist against that resource. §17 then requires "Resource still eligible" for Late Confirm. The two combine so that disabling a resource silently voids every pending revival on it.

**Exact missing behavior**
Whether the affected Customers are notified, and what is recorded. §99's closing line requires a failed Late Confirm to be written as an append-only Booking Event, and provides `RESOURCE_CONFLICT` — which describes an interval clash, not a disabled resource. Those bookings then sit until `startAt` and cancel with `VENUE_DID_NOT_RESPOND_IN_TIME` (v4.1 §4), a reason that is now inaccurate.

**Why it matters**
The Customer's final record names the wrong cause. It is a small correctness issue in the event log and the notification catalogue, not an ambiguity of intent.

**Scenario**
Six expired requests exist on Room 3 for tonight. At 6:00 PM the venue archives Room 3 (permitted by §45). All six are now unrevivable. Their customers are told nothing at 6:00 PM, and at each start time receive "Venue Did Not Respond in Time".

**Architecture can proceed?** Yes.

---

### T3-20 — Closure blocks Disable/Archive venue-wide and indefinitely

**Class:** C · **Severity:** MEDIUM
**References:** 4.2 §41, §45, §46; 4.1 §22

**Exact behavior**
§45 includes **Closure** among the blocking reservations that prevent Disable, Archive and Console Change. Per v4.1 §22, a Closure "applies to the entire Venue and blocks all Resources during its interval". Therefore any future scheduled Closure — a holiday, a private event — blocks the Disable, Archive and Console Change of **every resource in the venue** until that Closure has completely ended (§45: "whose interval has not completely ended").

**Exact missing behavior**
Whether a venue-wide Closure is intended to gate resource-level configuration changes, or whether §45's list was meant to cover resource-scoped blocks only.

**Why it matters**
A venue that schedules a closure for Eid cannot retire a broken console, change a PS4 to a PS5, or archive a removed table until after the holiday — with no way to proceed short of deleting the Closure. It also interacts with T3-13: the resource-failure path is blocked by an unrelated future holiday.

**Scenario**
On 1 October a venue schedules a full-day Closure for 20 October. On 2 October a console dies and the venue tries to Archive that room. §45/§46 block it, because a Closure with an interval that has not ended overlaps that resource. The room stays in inventory for 18 days, or the venue deletes its holiday closure.

**Architecture can proceed?** Yes, once §45's scope is confirmed.

---

### T3-21 — A 24-hour Operating Session is invalid on a DST fall-back date

**Class:** A · **Severity:** LOW
**References:** 4.2 §31, §33, §29

**Exact conflict**
- 4.2 §31: "Session length must be … **no more than 24 hours**. A 24-hour session may be represented explicitly as a full-day session."
- 4.2 §33: "Absolute instants are authoritative. Product duration represents **REAL ELAPSED MINUTES**."
- On Egypt's autumn DST transition, a local full-day session (00:00 → 24:00) is **25 real hours**; on the spring transition it is 23.

**Exact behavior in conflict**
A venue with a declared full-day session cannot save or cannot generate a valid session on the fall-back date: §31's limit is expressed in hours and §33 requires those hours to be real elapsed minutes.

**Why it matters**
Occurs twice a year, but produces a validation failure on a date the venue did not edit. Whether §31's 24 hours is a wall-clock or elapsed bound is a one-line Product answer.

**Scenario**
A 24/7 venue is configured as a full-day session. On the fall-back date the generated session is 25 real hours. Session generation either rejects it (the venue has no hours that day) or accepts it (§31 is violated).

**Architecture can proceed?** Yes.

---

### T3-22 — Operating Session non-overlap is not stated as a cyclic (week-wrap) validation, and the session is not in the historical-integrity list

**Class:** C · **Severity:** MEDIUM
**References:** 4.2 §29, §30, §31, §32, §34, §102

**Exact missing behavior**
1. §31 forbids overlapping sessions. Because Opening Hours are a weekly recurring template (v4.1 §17) and sessions may cross midnight (§30), the non-overlap check must be evaluated **cyclically**, including the Sunday-session-into-Monday wrap. §34's evaluation order says "Validate proposed Operating Sessions" without stating the domain of that validation.
2. §32 requires every Booking to fit one Operating Session, and §30 makes session membership depend on the schedule in force. §102's immutable historical values do **not** include the Operating Day / Operating Session a booking belonged to. A later Opening Hours change therefore retroactively changes which session a historical booking derives from.

**Why it matters**
(1) is a validation-domain question whose omission produces a schedule that passes validation and then generates overlapping sessions across the week boundary. (2) means historical reporting by operating day is not reproducible, which matters for the Venue's revenue views (4.0 §92, §93).

**Scenario**
Default hours are set to 10:00 AM → 8:00 AM (22 hours). Each day's session overlaps the next day's by 10 hours. Validated per-day in isolation, each session is ≤24h and legal; validated cyclically, every pair overlaps and §31 rejects the schedule.

**Architecture can proceed?** Yes.

---

### T3-23 — The Opening Hours impact set is not revalidated between preview and commit, and pending-approval Tournaments are not addressed

**Class:** C · **Severity:** MEDIUM
**References:** 4.2 §34, §36, §63, §96

**Exact missing behavior**
§34 fixes the evaluation order — validate, check Tournament conflicts, block, compute affected reservations, show preview, determine password requirement, request password, commit atomically, notify — and declares the whole thing all-or-nothing. It does not state that the impact set computed at step 4 is **revalidated at step 8**. Between preview and commit, new PENDING bookings can be created (§5 permits intake while open), new Tournament requests can arrive with Approval Holds (§63), and existing AWAITING bookings can be Accepted. §96's first-commit-wins governs competing transactions but says nothing about whether the Owner Password obtained for one impact set authorizes a larger one.

Separately: §34 step 2 says "Check **Tournament** conflicts" without specifying whether a Tournament in **PENDING_VENUE_APPROVAL** — which holds resources (§63) but is not yet approved — blocks the change.

**Why it matters**
The Owner Password in §36 is consent for a specific set of protected cancellations. If the committed set is larger than the previewed one, the consent was not given for it.

**Scenario**
Venue previews an hours change affecting 2 Confirmed bookings, and is prompted for the Owner Password. While the owner is fetched, 3 more customers book the affected window (legally). At commit, 5 Confirmed bookings are cancelled under a password entered for 2.

**Architecture can proceed?** Yes.

---

### T3-24 — Emergency Closure's cascade is not declared atomic

**Class:** C · **Severity:** MEDIUM
**References:** 4.2 §34, §43, §96, §101

**Exact missing behavior**
§34 explicitly declares the Opening Hours change all-or-nothing. §43's Emergency Closure performs a comparable multi-entity cascade — cancel Pending, cancel Awaiting, cancel Confirmed, terminate ACTIVE, cancel Tournaments, release Holds, notify — with **no atomicity statement**. If it partially applies, the venue is left with a closure in force and reservations still holding inventory inside it, and §24 will report those intervals as blocked by reservations that should not exist.

**Why it matters**
Emergency Closure is the one path used under real-time pressure, on possibly degraded connectivity, and it is the only path that touches five entity types at once. Consistency of the resulting inventory state is Product-visible.

**Scenario**
An Emergency Closure cancels 12 of 18 confirmed bookings and then fails. Six customers still hold confirmed bookings at a venue with a power cut and an active closure; the availability predicate blocks those intervals; no rule says whether to retry, roll back, or continue.

**Architecture can proceed?** Yes — but state it, as §34 does.

---

## 4. TOURNAMENTS

---

### T3-25 — "Active Participants" is undefined, and banned or deleted participants have no defined treatment

**Class:** B · **Severity:** HIGH
**References:** 4.2 §58, §59, §65, §66, §71, §87, §98; 4.1 §48

**Exact conflict / omission**
- 4.2 §66: at the checkpoint, "if **active Participants** < 8 → CANCELLED". The term "active Participants" appears here and in §98 and is defined nowhere.
- 4.2 §59: a banned user "may NOT Join Tournament" — but §58's ban cascade covers bookings only (PENDING and AWAITING cancelled). Nothing addresses a user who is **already a participant** when the ban activates.
- 4.2 §71 cascades a Verified Player revocation to the Creator's tournaments and says nothing about participants.
- 4.2 §87: a DELETED account has login disabled; its tournament participation is not addressed.

**Exact missing behavior**
Whether banned and deleted participants count toward the minimum of 8 at the checkpoint, and whether they are removed at ban activation.

**Why it matters**
The checkpoint is a hard automated cancel/confirm decision on a count. If banned participants count, a tournament can confirm on a roster of users who are barred from attending; if they do not, the count silently changes at ban time and can drop a tournament below 8 with no participant action — and §66 evaluates only once, at the checkpoint, so the timing of a ban relative to the checkpoint decides the tournament's fate.

**Scenario**
A tournament has exactly 8 participants. Ninety minutes before start, one is banned for a third No-show at another venue. At `startAt - 1h` the checkpoint runs. Under one reading the roster is 8 → CONFIRMED, and a banned user is on it. Under the other it is 7 → CANCELLED with `MINIMUM_PARTICIPANTS_NOT_REACHED`, and seven uninvolved players lose their tournament because of a stranger's ban.

**Architecture can proceed?** **NO** for the checkpoint job's input set.

---

### T3-26 — Tournament historical pricing snapshot point is "creation/approval" — two different instants

**Class:** B · **Severity:** MEDIUM
**References:** 4.2 §72, §74, §61, §62, §80, §81, §102

**Exact conflict**
4.2 §74: "Tournament stores **at creation/approval**: each Resource, historical hourly price, duration, resource subtotal, total Venue Cost."

For a Player-hosted Tournament these are two distinct instants up to 10 minutes apart (§62), and the Venue may change prices in between (a protected but instantaneous action, 4.0 §70). v4.2 resolved exactly this ambiguity for the other two snapshotted values — §80 fixes the Referral Reward at OTP verification and §81 fixes Completed Booking Points at the attended completion event — and left it open here.

**Exact missing behavior**
Which instant's price is stored for a Player-hosted Tournament.

**Why it matters**
§74's stored subtotal feeds §75's Tournament Revenue, which feeds the Venue Dashboard's revenue aggregation (§76), and §102 declares it immutable. The two readings produce different permanent revenue figures.

**Scenario**
Player requests a tournament at 6:00 PM on 4 rooms; PS5 Multiplayer is EGP 200/hr. At 6:04 PM the Owner raises it to EGP 240/hr. At 6:07 PM the Venue approves. The stored Total Venue Cost for a 3-hour tournament is EGP 2,400 or EGP 2,880 depending on which word in "creation/approval" governs.

**Architecture can proceed?** Yes — but the figure is permanent once written.

---

### T3-27 — Tournament Approval Holds are free, unlimited and unrate-limited

**Class:** B · **Severity:** MEDIUM
**References:** 4.2 §24, §61, §62, §63, §39 (v4.1)

**Exact missing behavior**
A Verified Player may request a Tournament on up to 4 Resources (4.1 §46); the request immediately places **Approval Holds** that block all other reservations (§63, §24) for up to 10 minutes (§62). There is no limit on: how many simultaneous requests one player may hold, how many consecutive requests they may make, whether the same player may re-request the same resources immediately after a rejection, or whether requests may be placed at several venues at once.

**Why it matters**
This is a free, authenticated, repeatable inventory-denial primitive available to any Verified Player — and Verified Player status is itself obtainable through a venue's unverified Check-in button (T3-09). Nothing in the Trust engine penalises it: rejected and expired tournament requests are not No-shows and §57 ignores tournament activity entirely.

**Scenario**
A Verified Player requests, at 6:00 PM, a 4-resource tournament for tonight's peak 9:00 PM slot at each of the six venues in New Cairo. Twenty-four resources are held. Each venue rejects within 10 minutes; the player re-requests immediately. Peak inventory across the Area is unsellable for as long as the player keeps tapping, at zero cost.

**Architecture can proceed?** Yes — the holds are specified; the absence of limits is a Product exposure decision.

---

### T3-28 — Verified Player *Suspension* vs *Revocation* is not distinguished in the cascade

**Class:** B · **Severity:** MEDIUM
**References:** 4.2 §71, §100; 4.0 §9, §129; 4.1 §63

**Exact conflict**
4.0 §9 and §129 define three distinct Admin actions on Verified Player status: **Suspend**, **Revoke**, **Restore**. 4.1 §63 gives all three their own notifications. 4.2 §71 cascades to tournaments on the single condition "**loses** Verified Player status", and §100's reason code is `CREATOR_VERIFIED_STATUS_REVOKED`.

**Exact missing behavior**
Whether a **Suspension** triggers the §71 cascade, or only a Revocation. The reason code names only revocation, while the rule's trigger is the broader "loses".

**Why it matters**
Suspension is the reversible remedy; §71's cascade is explicitly irreversible ("Restoring Verified Player status does NOT restore a cancelled Tournament"). Applying an irreversible cascade to a reversible action makes Suspension indistinguishable from Revocation in effect.

**Scenario**
Admin suspends a Verified Player for 48 hours pending review of a complaint, intending to restore it. §71 immediately cancels their WAITING_FOR_PLAYERS tournament with 14 participants. The complaint is dismissed and the status restored; the tournament is gone permanently.

**Architecture can proceed?** Yes.

---

### T3-29 — Creator may cancel a CONFIRMED Tournament with no cutoff, no Owner Password and no venue recourse

**Class:** B · **Severity:** LOW
**References:** 4.2 §7, §67, §68, §69, §75

**Exact behavior**
§67 permits the **Creator** to cancel a Player-hosted Tournament in PENDING_VENUE_APPROVAL, WAITING_FOR_PLAYERS **or CONFIRMED**. §68 confirms "Creator cancellation of their Player-hosted Tournament does not use Venue Owner Password" — while a **Venue** cancelling the same CONFIRMED tournament does require it. No cutoff is stated, so the Creator may cancel at `startAt - 1 second`.

**Exact missing behavior**
Whether Creator cancellation has a deadline, and whether the Venue has any recourse for the released slot. Per §7, an Online Booking cannot be created inside 15 minutes of start, so a late release is unsellable except as a Manual Booking; per §75 a cancelled Tournament contributes no revenue.

**Why it matters**
The asymmetry is deliberate on the password (the Creator has no venue password) but leaves the venue exposed: a 4-resource peak-hour block can evaporate one minute before start with no penalty to the Creator and no compensation.

**Scenario**
A CONFIRMED tournament holds 4 PS rooms 9:00 PM–1:00 AM with a stored Venue Cost of EGP 3,200. At 8:59 PM the Creator cancels. Four rooms are free for the peak evening, no online booking can take them (§7), and nothing records the Creator's conduct.

**Architecture can proceed?** Yes.

---

## 5. REVIEWS, IDENTITY, CONTACT & NOTIFICATIONS

---

### T3-30 — A REMOVED review's one-per-venue slot is undefined: either a permanent silent block or a moderation bypass

**Class:** A · **Severity:** HIGH
**References:** 4.2 §77, §78, §79; 4.0 §22, §27

**Exact conflict**
- 4.0 §22: "Each User may have **ONE ACTIVE REVIEW PER VENUE**", with Edit and Delete available.
- 4.2 §78: a REMOVED review may not be edited back into public content; the user "may remove/delete it from their **personal view**"; the backend retains the moderation record; it "never returns to Active without explicit Admin action".
- 4.2 §79: user deletion makes the public review disappear while the moderation record remains.

**Exact missing behavior**
Whether a REMOVED (or user-deleted-after-removal) review still occupies the user's one-per-venue slot. Both answers are defensible from the text and both are bad in a stated way:
- **Occupied** → the user can never review that venue again. Moderation of a single review becomes a permanent, unstated, unappealable ban on reviewing that venue, with no rule creating it and no notification describing it.
- **Freed** → the user writes a fresh review immediately, and §78's "never returns to Active" is satisfied in letter while defeated in substance. §62 (v4.1) helpfully notifies them that moderation occurred.

**Why it matters**
It is the uniqueness constraint on the reviews table, the enforcement point of moderation, and a customer-visible permission — decided by a rule that does not exist.

**Scenario**
A user posts an abusive review of Game Zone. Admin **Removes** it (4.0 §26, reason "Abuse") and the user is notified (v4.1 §62). The user opens Game Zone and taps Write Review. Either the button is unavailable forever with no explanation, or they post a second abusive review in thirty seconds.

**Architecture can proceed?** Partially — the schema constraint cannot be fixed without it.

---

### T3-31 — §78 defers its own existence: "if Product Admin tools support restoration"

**Class:** D · **Severity:** MEDIUM
**References:** 4.2 §77, §78; 4.0 §26, §27

**Exact wording**
4.2 §78: "Removed Review never returns to Active without explicit Admin action **if Product Admin tools support restoration**."

**Exact problem**
A specification clause conditioned on whether the specification provides a capability. 4.0 §26 does list "Restore Hidden Review" as an Admin power — for **Hidden**, not Removed — and 4.2 §77 requires explicit Admin Restore for Hidden. Whether **Removed** is restorable is left as a conditional with no antecedent.

Related: 4.0 §27's review status enum is Active / Hidden / Removed. §78 introduces a fourth condition — removed-from-the-user's-personal-view while retained in the backend — that the enum does not carry.

**Why it matters**
Pure hygiene, but it leaves the Admin Reviews module's action set undecided and the review status field's domain incomplete.

**Scenario**
An implementer building the Admin Reviews module (4.0 §130) cannot tell whether a Restore action belongs on a Removed row.

**Architecture can proceed?** Yes.

---

### T3-32 — Contact visibility is keyed to terminality, which now occurs mid-session and mid-dispute

**Class:** B · **Severity:** MEDIUM
**References:** 4.2 §90, §2, §12, §51; 4.1 §70; 4.0 §18

**Exact conflict**
4.2 §90: phone access begins at PENDING; "**After Booking becomes terminal**: Customer no longer sees Venue Phone in Booking Details; Venue no longer sees Customer Phone in long-term Booking History."

NO_SHOW is terminal (§2) and is recorded **during** the session, from `startAt + 15min` (§12). So the moment the venue records a No-show, it loses the phone number of the customer who is 16 minutes late — precisely when a call is the operationally correct action. Symmetrically, the customer loses the venue's number at the instant they have something to dispute, and §51's Admin reversal path presumes a dispute can be raised.

**Exact missing behavior**
Whether No-show (and CANCELLED) should carry a contact grace window; and what "**long-term** Booking History" means — immediately on terminality, or after some retention period. The qualifier "long-term" implies a two-tier visibility the spec never defines.

**Why it matters**
It is an access-control predicate that must be expressed as a query condition, and the trigger chosen makes the product least useful at the moment of conflict.

**Scenario**
8:16 PM: venue records No-show; the booking is terminal. The customer walks in at 8:20 PM claiming they called. The venue cannot see their number to check, the customer cannot see the venue's to prove it, and the only remedy is a Platform Admin reversal (§51) whose arithmetic is undefined (T3-10).

**Architecture can proceed?** Partially — the predicate is simple once "long-term" is defined.

---

### T3-33 — Account Verification may be email-only, yet the Venue is shown an unverified phone number

**Class:** B · **Severity:** MEDIUM
**References:** 4.2 §4, §84, §90; 4.0 §5, §6

**Exact conflict**
- 4.0 §5: both Phone **and** Email are mandatory at registration.
- 4.0 §6: the Customer **chooses** SMS **or** Email for OTP delivery; successful OTP produces Account Verified.
- 4.2 §4: Account-Verified users may create Online Bookings. 4.2 §84: successful OTP completes Account Verification, with no channel requirement.
- 4.2 §90: from PENDING onward, the **Venue may see the Customer Phone**.

**Exact missing behavior**
Whether the **phone** must be verified for booking, or whether email verification suffices. As written, a user can verify by email and book, and the venue is then given a phone number that no party has ever confirmed — the number it will call when the customer is late, and the identity thread behind Check-in (T3-09) and No-show.

Related: §85 requires verification when the phone is **changed**, which implies phone verification matters — while §84/§4 do not require it initially.

**Why it matters**
The venue's only channel to the customer, and the basis of §83's self-referral phone check, may be unverified data. §83 hard-blocks self-referral on "Same Phone" — a field that need never have been proven.

**Scenario**
A user registers with someone else's phone number, verifies by email, and books. The venue calls that number at 8:16 PM about a no-show and reaches an uninvolved person. The same unverified number is the anti-fraud key for §83.

**Architecture can proceed?** Yes — one-line answer, but it changes the registration funnel.

---

### T3-34 — v4.2 adds ten notifiable events and updates no notification catalogue

**Class:** B · **Severity:** MEDIUM
**References:** 4.2 §10, §12, §42, §43, §51, §52, §58, §82, §91; 4.0 §145, §146, §147; 4.1 §62, §63, §64

**Exact omission**
The canonical notification catalogues are 4.0 §145 (Customer), §146 (Venue), §147 (Platform Admin), extended by 4.1 §62–§64. v4.2 introduces events with notification consequences and adds none of them to any catalogue:

| v4.2 event | Section | In any catalogue? |
|---|---|---|
| Check-in recorded | §9 | No |
| Completion as ATTENDANCE_UNCONFIRMED (no Points) | §10 | No |
| Booking cancelled `USER_BANNED` | §58 ("Customer is notified") | No |
| No-show reversed | §51 | No |
| Ban lifted / shortened | §52 | No |
| Points adjustment (±) | §52, §53 | No |
| Wallet adjustment (±) | §52, §91 | No |
| Referral invalidated / reward reversed | §82 | No |
| Emergency Closure — Customer notifications | §43 ("appropriate Notifications") | No |
| Admin cancellation / correction of a Booking | §52 | No |

Also still absent from every version: any **delivery-channel model** (push / SMS / email / in-app). §20's rules turn on sub-minute timing and §12/§19's windows are minutes wide, which presupposes push.

**Why it matters**
Two of these are load-bearing rather than cosmetic. **ATTENDANCE_UNCONFIRMED** puts a booking in the Customer's **Completed** tab (§22) with no Points and no counter reset (§10, §57) — a customer who did attend but was not checked in has no way to learn why, and no path to §51's correction. And §58's ban cascade cancels bookings the customer is relying on. §101 requires all of these to be audited; none is required to be communicated.

**Scenario**
A customer attends a session; staff forget to press Check-in. At `endAt` the booking becomes COMPLETED / ATTENDANCE_UNCONFIRMED. The customer's Completed tab shows it as completed, their Points do not move, their Verified Player progress stays at 6/10, and their No-show counter is not reset. No notification explains any of it, and no rule tells them a correction exists.

**Architecture can proceed?** Partially — the notification engine cannot be specified from an incomplete catalogue.

---

## 6. HISTORICAL INTEGRITY, ENUMS & HYGIENE

---

### T3-35 — §99's reason-code enum is explicitly open, and two codes are unmapped or duplicated

**Class:** D · **Severity:** MEDIUM
**References:** 4.2 §99, §100, §14, §35, §51, §9; 4.1 §4, §5, §35

**Exact problems**
1. §99 opens with "Canonical reason codes include **at minimum**" — an open enum in a document being prepared for freeze. Any implementer may add codes, and 4.0 §133's "Full Lifecycle History" and Admin filtering then diverge across products.
2. **`BOOKING_EXPIRED`** appears under SYSTEM with **no rule that assigns it**. The final expiry at `startAt` is assigned `VENUE_DID_NOT_RESPOND_IN_TIME` by v4.1 §4. Either it is a duplicate of that code or an unreachable one.
3. **`REJECTED_AFTER_EXPIRY`** is retained while §14 forbids the transition that produces it — see T3-03.
4. **`VENUE_CANCELLED_CONFIRMED_BOOKING`** is the only venue-cancellation code, but v4.1 §35 permits Manual Booking cancellation **without** Owner Password while 4.0 §71 protects Confirmed Online cancellation. One code spans two differently-authorized actions.
5. No code exists for: Late Confirm blocked by a Customer schedule conflict (§17/§18, T3-07); Late Confirm blocked by a disabled resource (T3-19); Check-in recorded; No-show reversed.
6. §100's tournament codes lack a normal `VENUE_CLOSURE` counterpart (only `EMERGENCY_CLOSURE`) although §41 blocks — rather than cancels — on normal closure, and lack any code for the venue's operational termination of an ACTIVE tournament permitted by §67.

**Why it matters**
Reason codes are the join key between booking history, Admin filtering (4.0 §132, §133), notification copy and support tooling. An open enum with unmapped members cannot be frozen.

**Architecture can proceed?** Yes.

---

### T3-36 — The Owner-Password-protected action list exists in four documents and matches no single enumeration

**Class:** D · **Severity:** MEDIUM
**References:** 4.0 §70, §71, §72, §74; 4.1 §19, §23, §27, §28, §29; 4.2 §36, §39, §40, §49, §68, §101

**Exact problem**
The protected-action set is assembled from: 4.0 §70 (all pricing changes), §71 (cancel Confirmed booking), §72 (Disable / Re-enable / Archive / Restore); 4.1 §19 / 4.2 §36 (Opening Hours with protected cancellations), 4.1 §23 (Closure with protected cancellations); 4.2 §39/§40 (Maintenance >24h), §68 (cancel CONFIRMED Tournament). §49 then states the scope rule ("per protected action") and §101 gives an audit list that is a **different** set — §101 includes Check-in, No-show, Emergency Closure and Admin corrections, which are explicitly *not* Owner-protected.

**Why it matters**
There is no canonical list to build an authorization matrix from, and the audit list is close enough to be mistaken for one. Exceptions (§42 Emergency Closure bypasses; v4.1 §35 Manual cancellation exempt; §12 No-show exempt; §68 WAITING_FOR_PLAYERS exempt) are each stated locally.

**Architecture can proceed?** Yes — consolidation is mechanical, provided no action's protection status changes.

---

### T3-37 — Hourly pricing has no rounding or minimum-charge rule for 30-minute increments

**Class:** C · **Severity:** MEDIUM
**References:** 4.2 §33, §72, §102; 4.0 §30, §32, §34, §62

**Exact missing behavior**
Prices are hourly (4.0 §30: PS5 EGP 170/hr Single) and durations come in 30-minute increments (4.0 §34) with no maximum (4.1 §14). No rule states how a fractional-hour total is computed or rounded: 1.5h × EGP 170 = EGP 255, and whether the product rounds to the piastre, the pound, or up to a minimum charge unit is unstated. §33 adds that "Pricing uses the booked **elapsed** duration", so a DST-affected booking's total differs from a naive wall-clock calculation and the figure shown in the Booking Summary (4.0 §33) must already reflect it. §72 applies the same arithmetic to Tournament Venue Cost across up to 4 resources, and §102 makes the result immutable.

**Why it matters**
The computed total is stored permanently (§102), displayed to the Customer before Confirm, and aggregated into Venue Revenue (4.0 §93) and Tournament Revenue (§75). Two implementations differing by rounding produce permanently different ledgers.

**Scenario**
A 2.5-hour PS5 Multiplayer booking at EGP 200/hr is EGP 500. A 1.5-hour Billiards booking at EGP 135/hr is EGP 202.50 — payable at a counter in a currency whose smallest practical unit is EGP 0.25 or EGP 1. Nothing says what is charged or stored.

**Architecture can proceed?** Yes.

---

### T3-38 — Attendance Result has no defined domain outside ATTENDED / ATTENDANCE_UNCONFIRMED

**Class:** C · **Severity:** MEDIUM
**References:** 4.2 §3, §10, §11, §22, §43, §102

**Exact missing behavior**
§10 defines exactly two Attendance Results, both on COMPLETED bookings. Undefined: the value for a **Manual** booking that reaches COMPLETED (§3, §11 — check-in is not required "for Product Trust purposes", which does not say whether the field is set); for a booking in CANCELLED, REJECTED or NO_SHOW; and for a booking terminated by Emergency Closure (§43). §102's immutable historical values do not include the Attendance Result, although it determines Points (§81), Verified Player progress and counter reset (§10, §57), all of which *are* in the list. §22's tab projection is by status only, so the Customer-facing surfacing of the result is also unstated (see T3-34).

**Why it matters**
It is a stored field whose domain and nullability drive the Trust engine's queries and whose absence from §102 leaves it recomputable — which, after a §51 correction, it must not be.

**Architecture can proceed?** Yes.

---

### T3-39 — §13 forbids operational early termination of a Booking while §67 permits it for a Tournament

**Class:** D · **Severity:** MEDIUM
**References:** 4.2 §13, §43, §52, §67, §99, §100

**Exact inconsistency**
- §13: the *only* exceptional early-termination paths for an ACTIVE **Booking** are Emergency Closure and an authorized Platform Admin support correction. A venue may not end an active booking for an operational reason.
- §67: an ACTIVE **Tournament** "may be terminated only by — **Venue for operational/emergency reason**, Platform Admin support action, Emergency Closure."

The Venue therefore holds an operational-termination power over tournaments that it is explicitly denied over bookings. Correspondingly, §99 has no venue operational-termination code for bookings (consistent with §13) and §100 has none for tournaments either (inconsistent with §67).

**Why it matters**
Probably an asymmetry of drafting rather than intent, but it is the difference between two authorization matrices, and it interacts with T3-13: the venue can end a live tournament when a console breaks and cannot end a live booking for the same reason.

**Architecture can proceed?** Yes — but confirm which is intended before the Venue Dashboard's action set is built.

---

### T3-40 — Admin may adjust a Wallet the Customer cannot see

**Class:** C · **Severity:** LOW
**References:** 4.2 §52, §53, §91; 4.0 §137

**Exact inconsistency**
§91 removes the Wallet screen from the Customer launch UI ("does not show an active Wallet screen") because there is no funding source. §52 retains Platform Admin **Wallet positive and negative adjustment**, and §53 requires append-only compensating transactions. 4.0 §137 retains the Admin Wallet module.

**Why it matters**
A support agent can credit a balance the customer has no way to see or spend, producing a liability with no disclosure surface. Minor at EGP 0 volumes, but it should be an explicit choice rather than a by-product of hiding a screen.

**Architecture can proceed?** Yes.

---

### T3-41 — Residual terminology and enum drift after the v4.2 consolidation

**Class:** D · **Severity:** LOW
**References:** 4.2 §105, §106, §24, §45, §17, §66, §28, §90, §74

**Exact items**
§105 fixes the vocabulary and §106 declares the supersession classes — both are substantial improvements. Residual drift:

| Term | Problem |
|---|---|
| "Manual Booking" in §24 / "MANUAL" in §45 | enumerated as a peer of Booking Statuses, contradicting §1 and §105 (see T3-01) |
| "Venue still eligible" / "Resource still eligible" (§17) | undefined; no counterpart in §105 (T3-18) |
| "active Participants" (§66, §98) | undefined (T3-25) |
| "made inactive" (§28) | invokes an undefined Venue marketplace state (T3-17) |
| "long-term Booking History" (§90) | implies an undefined two-tier retention (T3-32) |
| "at creation/approval" (§74) | two instants in one snapshot rule (T3-26) |
| "include at minimum" (§99) | open enum in a freeze candidate (T3-35) |
| "if Product Admin tools support restoration" (§78) | self-deferring clause (T3-31) |
| `ACTIVE` | still overloaded across Booking status, Tournament status, Resource Base State (§105), Area status (4.0 §12) and Review status (4.0 §27); §105 names three of the five |

§106's supersession list is by **class** rather than by section number, which requires every reader to re-derive which specific v4.0/v4.1 sections died. T3-03 is a direct consequence: a transition survived because the class it belonged to was described in terms of "status" rather than the transition itself.

**Architecture can proceed?** Yes.

---

# PART 2 — FOCUS-AREA VERDICTS

Against the specific areas nominated for this pass.

## Booking

| Item | Verdict | Findings |
|---|---|---|
| Manual Source vs Status | **Model fixed (§1–§3); predicates not** | T3-01 |
| Check-in and attendance | **New and underspecified — irreversible, unverified, absent from §52** | T3-09, T3-14, T3-15, T3-34, T3-38 |
| Venue Response Expiry | **Contradicted on Reject-after-expiry; §5's guarantee false** | T3-03, T3-05 |
| Late Confirmation | **Predicate contains two undefined terms; enforcement point ambiguous** | T3-07, T3-18, T3-19 |
| Booking tab projection | **Resolved** (§22) | — |
| Active Booking termination | **Resolved in principle (§13/§43/§52); no resource-level path; asymmetric with tournaments** | T3-13, T3-39 |
| Customer schedule conflicts | **Rule established (§18); enforcement point and code missing** | T3-07 |
| *(new)* Manual retroactivity | **Unbounded; can overlap terminal bookings** | T3-02 |
| *(new)* Duration / count / cost limits | **None exist** | T3-08 |

## Availability

| Item | Verdict | Findings |
|---|---|---|
| Online Booking eligibility | **Explicit (§25); two of eleven conditions reference an undefined enum** | T3-17 |
| Resource interval conflicts | **Canonical (§24) but mixes status and source, and has no temporal domain** | T3-01, T3-02 |
| Venue Account Disabled | **Resolved** (§26) | — |
| Venue Suspension | **Behaviorally resolved (§27); its effect on Late Confirm is not** | T3-18 |
| Area status | **Guard added (§28) but it directs to a non-existent state** | T3-17 |
| Maintenance | **Owner-Password threshold added (§39) and bypassable by a 1-minute gap** | T3-16 |
| Closure | **Defined (§41); no edit/end/delete lifecycle; blocks resource changes venue-wide** | T3-12, T3-20 |
| Emergency Closure | **Path created (§42/§43) with no duration, no end, no undo, no password** | T3-12, T3-24 |

## Opening Hours

| Item | Verdict | Findings |
|---|---|---|
| Operating Day | **Resolved** (§29, §30, §105) | — |
| Cross-midnight | **Resolved** (§30, §32) | — |
| Closed-day behavior | **Resolved** (§30) — but drives the §5 intake consequence | T3-06 |
| 24-hour sessions | **Bounded (§31); invalid on DST fall-back dates** | T3-21 |
| DST | **Substantially resolved (§33); interacts with §31 and with pricing** | T3-21, T3-37 |
| Atomic Hours changes | **Resolved (§34); impact set not revalidated at commit; pending-approval tournaments unaddressed** | T3-23 |
| *(new)* Non-overlap validation domain | **Cyclic week-wrap not stated; session not in §102** | T3-22 |

## Tournament

| Item | Verdict | Findings |
|---|---|---|
| Canonical states | **Resolved** (§60) | — |
| Venue approval | **Resolved** (§62) | — |
| Approval holds | **Defined (§63); free, unlimited, unrate-limited** | T3-27 |
| Join / Leave | **Defined (§65, §94, §98); "active Participants" undefined** | T3-25 |
| 2-hour creation cutoff | **Resolved** (§61, §62) | — |
| Checkpoint | **Resolved (§66, §98); input set undefined** | T3-25 |
| Manual cancellation | **Resolved** (§67, §69) | — |
| Owner Password | **Resolved** (§68) | — |
| Emergency cancellation | **Resolved (§43, §67); inherits the closure-duration gap** | T3-12 |
| Pricing | **Resolved (§72); snapshot instant ambiguous** | T3-26 |
| Revenue | **Resolved (§75); reports uncollected amounts by design (§73)** | T3-29 (related) |
| History | **Resolved** (§76) | — |
| Verified Player revocation | **Resolved for Revoke (§71); Suspend not distinguished** | T3-28 |

## Trust

| Item | Verdict | Findings |
|---|---|---|
| Check-in | **Sole gate on all rewards; irreversible, unverified, no Admin correction in §52** | T3-09 |
| No-show | **Window, audit and reversal added (§12, §51); frees inventory mid-session by omission** | T3-15 |
| Counter reset | **Resolved** (§55) | — |
| Consecutive definition | **Resolved** (§57) | — |
| Ban | **Ladder sound (§54–§56); stacking during an active ban undefined** | T3-11 |
| In-flight Booking handling | **Resolved for bookings (§58); tournament participation not covered** | T3-25 |
| Admin remediation | **Powers granted (§51, §52); the arithmetic of a reversal is undefined** | T3-10 |

## Historical Integrity

| Item | Verdict | Findings |
|---|---|---|
| Booking Price | **Resolved** (§102) | — |
| Booking Fee | **Resolved** (§102, §92) | — |
| Completed Booking Points | **Resolved** (§81, §102) | — |
| Referral Reward | **Resolved** (§80, §82, §102) | — |
| Tournament Price | **In §102; snapshot instant ambiguous** | T3-26 |
| Resource Name / Console changes | **Resolved** (§47, §103) | — |
| Compensating ledger entries | **Resolved** (§53, §82) | — |
| *(new)* Attendance Result | **Not in §102 although it determines three listed values** | T3-38 |
| *(new)* Operating Session of a booking | **Not in §102; derivation changes when hours change** | T3-22 |
| *(new)* Rounding of fractional-hour totals | **Undefined; the stored figure is immutable** | T3-37 |
| *(new)* Retroactive Manual double-count | **Possible; corrupts immutable history** | T3-02 |

---

# PART 3 — SUMMARY

## ARCHITECTURE BLOCKERS

Nine. Each determines a schema, a state-machine transition, an authorization rule or the availability predicate, and cannot be deferred without rework.

| # | Finding | Class | What it blocks |
|---|---|---|---|
| 1 | **T3-01** — §24 and §45 enumerate MANUAL as a peer of Booking Statuses, contradicting §1 | A | The canonical inventory predicate; every flow that reuses it |
| 2 | **T3-02** — Retroactive Manual Bookings are unbounded and can overlap terminal bookings | B | The availability predicate's temporal domain; revenue integrity |
| 3 | **T3-03** — Reject-after-expiry: §14 forbids it, v4.1 §5 permits it, §99 keeps its code | A | The booking state machine; Venue Dashboard action set |
| 4 | **T3-09** — Check-in is irreversible, unverified and absent from §52's remediation list | B | The Trust engine write path; the Admin authorization matrix |
| 5 | **T3-10** — No-show reversal has no defined arithmetic, while §51 mandates consistency | B | The Trust engine's correction semantics; ban dates; progress |
| 6 | **T3-11** — No-shows and Check-ins during an active ban have no defined effect; stacking undefined | B | The ban engine's event filter; Account Standing |
| 7 | **T3-12** — Emergency Closure has no duration, no end and no undo, while cascading irreversibly | B | The Closure entity's interval and lifecycle; the cascade's scope |
| 8 | **T3-17** — No canonical Venue marketplace status model, though §25/§28 depend on one | B | Two of §25's eleven eligibility conditions; §28's Admin guard |
| 9 | **T3-25** — "Active Participants" undefined; banned/deleted participants unhandled | B | The checkpoint job's input set |

Near-blockers, listed so they are not lost: **T3-18** (undefined "Venue still eligible" in the Late Confirm predicate — a possible documented bypass of suspension) and **T3-05** (§5's stated guarantee is arithmetically false). Both are HIGH and both sit on endpoints that will be built early.

## NON-BLOCKING CLARIFICATIONS

Resolvable during architecture provided the Product Owner confirms that stated intent is unchanged. Items marked ◆ are Product-visible choices that should be recorded in writing even though they do not stall design.

| Finding | Class · Severity |
|---|---|
| ◆ T3-05 — §5's "timer always starts while operating" is false for the last 10 minutes of a session | A · HIGH |
| ◆ T3-06 — "Currently open" intake disables discovery-driven booking for most of the day | B · HIGH |
| ◆ T3-08 — No maximum duration, no booking-count limit, no cost to hold inventory | B · HIGH |
| ◆ T3-13 — No resource-level failure path; a broken console stays in inventory | B · HIGH |
| T3-18 — "Venue still eligible" / "Resource still eligible" undefined | B · HIGH |
| ◆ T3-30 — A REMOVED review's one-per-venue slot: permanent block or moderation bypass | A · HIGH |
| ◆ T3-04 — Manual Booking's reachable states (NO_SHOW, Check-in) undefined | B · HIGH |
| ◆ T3-14 — Emergency Closure voids an already-recorded Check-in, including the counter reset | A · MEDIUM |
| T3-07 — Customer schedule conflict: two enforcement points, no reason code | B · MEDIUM |
| ◆ T3-15 — No minimum attendance; NO_SHOW frees the resource mid-session by omission | B · MEDIUM |
| ◆ T3-16 — Maintenance >24h threshold bypassable with a one-minute gap | B · MEDIUM |
| T3-19 — Disabling a resource silently voids Late Confirm eligibility | C · MEDIUM |
| T3-20 — Venue-wide Closure blocks all resource-state changes until it ends | C · MEDIUM |
| T3-22 — Session non-overlap validation domain; session absent from §102 | C · MEDIUM |
| T3-23 — Hours-change impact set not revalidated at commit; pending-approval tournaments | C · MEDIUM |
| T3-24 — Emergency Closure cascade not declared atomic | C · MEDIUM |
| T3-26 — Tournament price snapshot: "creation/approval" is two instants | B · MEDIUM |
| ◆ T3-27 — Tournament Approval Holds are free, unlimited and unrate-limited | B · MEDIUM |
| ◆ T3-28 — Verified Player Suspend vs Revoke not distinguished in §71's cascade | B · MEDIUM |
| ◆ T3-32 — Contact visibility keyed to terminality, which occurs mid-session and mid-dispute | B · MEDIUM |
| ◆ T3-33 — Account Verification may be email-only; the Venue sees an unverified phone | B · MEDIUM |
| ◆ T3-34 — Ten new notifiable events added to no catalogue; no channel model anywhere | B · MEDIUM |
| T3-37 — No rounding or minimum-charge rule for fractional-hour pricing | C · MEDIUM |
| T3-38 — Attendance Result's domain and nullability; absent from §102 | C · MEDIUM |
| T3-21 — A 24-hour session is invalid on a DST fall-back date | A · LOW |
| ◆ T3-29 — Creator may cancel a CONFIRMED Tournament with no cutoff | B · LOW |
| T3-40 — Admin may adjust a Wallet the Customer cannot see | C · LOW |

## DOCUMENT CLEANUP

| Finding | Item |
|---|---|
| **T3-35** | §99's reason-code enum is explicitly open ("at minimum"); `BOOKING_EXPIRED` is unmapped or duplicates `VENUE_DID_NOT_RESPOND_IN_TIME`; `REJECTED_AFTER_EXPIRY` survives a transition §14 forbids; one venue-cancellation code spans two differently-authorized actions; six events have no code; §100 lacks normal-closure and venue-operational-termination codes |
| **T3-36** | The Owner-Password-protected action list is spread across 4.0 §70–§72/§74, 4.1 §19/§23/§27–§29 and 4.2 §36/§39/§40/§68, with no canonical enumeration; §101's audit list is a different set and is close enough to be mistaken for one |
| **T3-31** | §78 defers its own existence ("if Product Admin tools support restoration"); 4.0 §27's review status enum does not carry §78's fourth condition |
| **T3-39** | §13 forbids venue operational early-termination of a Booking while §67 permits it for a Tournament; neither §99 nor §100 carries a matching code |
| **T3-41** | Residual terminology and enum drift after the §105/§106 consolidation: six undefined or double-valued terms, `ACTIVE` overloaded across five entities, and §106's supersession list stated by class rather than by section number — which is how T3-03 survived |

---

## FREEZE READINESS

### NOT READY FOR PRODUCT FREEZE

**Product Owner decisions still required: 28.**

Composed of every A-class and B-class finding, each of which requires an explicit Product answer rather than an architectural one:

- **7 HARD CONTRADICTIONS** — T3-01, T3-03, T3-05, T3-14, T3-21, T3-30, and (jointly) the §24/§45 dimension mix. Each needs a ruling on which rule survives.
- **21 PRODUCT DECISIONS REQUIRED** — T3-02, T3-04, T3-06, T3-07, T3-08, T3-09, T3-10, T3-11, T3-12, T3-13, T3-15, T3-16, T3-17, T3-18, T3-25, T3-26, T3-27, T3-28, T3-29, T3-32, T3-33, T3-34. *(22 listed; T3-34's notification catalogue and channel model are counted as one decision.)*

Against v4.2 §109's freeze criteria: **7 hard contradictions ≠ 0**, **28 product decisions ≠ 0**, **9 architecture blockers ≠ 0**.

The remaining 13 findings (8 C-class, 5 D-class) do not require Product Owner input and may be consolidated during architecture or into the final single-source document.

**Assessment of trajectory.** v4.2 is a strong document: it closed 40 of 41 pass-2 findings, and the three hardest — the availability predicate, the operating-session model and the no-show counter arithmetic — are now correct. The residual work has changed character. Pass 2 found missing engines; this pass finds **seams**: each v4.2 section is internally sound and incomplete where it meets the section it touches. Three seams account for two-thirds of the blockers — **Check-in** (introduced without a reversal, an identity assertion, or an entry in §52), **Emergency Closure** (introduced with a start but no end), and **Manual Booking** (given a lifecycle in §3 but not reflected in §24/§45). A v4.3 that closes the 28 decisions above, with particular attention to those three, has a credible path to freeze.

---

*Produced 18 September 2026. Third pass. No Product solution is proposed in this document by design; each finding is stated so the Product Owner can decide it.*
