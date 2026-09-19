# E7GZ PS
# PRODUCT REQUIREMENTS DOCUMENT v1.1

**Status:** APPROVED FOR BUILD
**Date:** 18 September 2026
**Supersedes:** PRD v1.0 of 18 September 2026
**Corrections applied:** E7GZ PS PRD-to-Frozen-Baseline Derivation Audit v1.0 — all 54 findings (Class A, B, C, D, E)
**Market:** Egypt — initial launch in Cairo
**Timezone:** Africa/Cairo · **Currency:** EGP
**Derived from:** E7GZ PS Product Specification v5.0 — FROZEN BASELINE
**Products in scope:** Customer Mobile App · Venue Dashboard · Platform Admin

> **Authority.** The behavioural authority is **E7GZ PS Product Specification v5.0 — FROZEN BASELINE**, which is unchanged by this revision. This document is a corrected derivation of it. No Product Change Request is raised, no Product decision is reopened, and no v5.x specification is created.

---

# 0. ABOUT THIS DOCUMENT

## 0.1 Purpose

This PRD states **what E7GZ PS must do at launch, why, and how we will know it works.** It is the document a delivery team, an architect, a QA lead or a stakeholder reads to understand the product as a set of requirements with rationale, priority and acceptance criteria.

## 0.2 Relationship to the Frozen Baseline

The **Product Specification v5.0 — FROZEN BASELINE** is the authority on *behaviour*: state machines, predicates, enums, timing rules and authorization. This PRD is the authority on *requirements*: scope, priority, rationale, personas, journeys, metrics and acceptance.

Where this PRD summarises a rule, it carries a **Spec** reference to the v5.0 section that governs it. **If this document and v5.0 ever disagree on behaviour, v5.0 wins** and this PRD is corrected.

| Question | Read |
|---|---|
| What exactly happens in this state? | v5.0 |
| What must we build, and is it in launch scope? | This PRD |
| Why does the product behave this way? | This PRD for rationale, v5.0 for the rule |
| How do we know a requirement is done? | This PRD — acceptance criteria |
| How do we change a rule? | v5.0 §57 — Product Change Control |

## 0.3 Requirement notation

Each requirement carries an ID, a priority and a spec reference.

**ID prefixes**

| Prefix | Domain |
|---|---|
| `FR-ACC` | Accounts, identity and verification |
| `FR-DIS` | Discovery and browsing |
| `FR-BOK` | Online booking lifecycle |
| `FR-LCF` | Late Confirmation |
| `FR-ATT` | Attendance, check-in and no-show |
| `FR-MAN` | Manual booking |
| `FR-VEN` | Venue configuration and Venue Dashboard |
| `FR-AVL` | Availability and inventory |
| `FR-OPS` | Maintenance, closures and emergencies |
| `FR-TRN` | Tournaments |
| `FR-TRS` | Trust, penalties and bans |
| `FR-PTS` | Points, referrals and wallet |
| `FR-REV` | Reviews |
| `FR-ADM` | Platform Admin |
| `FR-NTF` | Notifications |
| `FR-AUD` | Audit and historical integrity |
| `NFR` | Non-functional requirements |

**Priority (MoSCoW)**

| Code | Meaning |
|---|---|
| **M** | Must have — launch blocker. The product is not shippable without it. |
| **S** | Should have — high value; ship without it only on an explicit, recorded decision. |
| **C** | Could have — desirable if capacity allows; no launch dependency. |
| **W** | Won't have this release — explicitly deferred, recorded so it is not rediscovered as a gap. |

Unless stated otherwise, every requirement in §5 through §20 is **M**.

**Incorporation by reference.** A small number of requirements incorporate a frozen v5.0 catalogue in full rather than reproducing it — the notification catalogue (§52.2, §52.3), the historical snapshot list (§53.2), the audit-required action list (§53.3) and the admin remediation list (§47.1). Where a requirement says *"the full catalogue in v5.0 §X"*, every member of that catalogue is in scope and is launch-required at the requirement's stated priority. This is deliberate: the catalogue has one home, and it is v5.0.

**TBD marking.** A Spec column reading **TBD / Proposed non-product requirement — not defined by Frozen Baseline** means the requirement is authored by this PRD and is **not** derived from v5.0. It is legitimate — a PRD carries engineering targets, metrics, personas, phasing and risks that a product specification does not — but it is never to be treated as a frozen Product rule. Twelve requirements carry this marking.

## 0.4 Definition of Done for a requirement

A requirement is done when all of the following hold:

1. The behaviour matches the referenced v5.0 section exactly
2. Every acceptance criterion in this PRD passes
3. Audit records required by v5.0 §53.3 are written
4. Notifications required by v5.0 §52 are dispatched on the right channel
5. The behaviour is covered by automated tests, including the boundary and race cases named in the criteria
6. Where the requirement touches money, Trust or inventory, historical immutability (v5.0 §53.4) is verified

---

## 0.5 Contents

**Part A — Context**
§1 Executive summary · §2 Problem and opportunity · §3 Vision, goals and non-goals · §4 Users and success · §5 Scope · §6 Key user journeys · §7 System context

**Part B — Functional requirements**

| § | Area | IDs |
|---|---|---|
| 8 | Accounts, identity and verification | FR-ACC-01…18 |
| 9 | Discovery and browsing | FR-DIS-01…14 |
| 10 | Availability and inventory | FR-AVL-01…15 |
| 11 | Online booking lifecycle | FR-BOK-01…32 |
| 12 | Late Confirmation | FR-LCF-01…18 |
| 13 | Attendance, check-in and no-show | FR-ATT-01…28 |
| 14 | Manual booking | FR-MAN-01…11 |
| 15 | Venue model, configuration and Dashboard | FR-VEN-01…47 |
| 16 | Maintenance, emergencies and operational restrictions | FR-OPS-01…44 |
| 17 | Tournaments | FR-TRN-01…33 |
| 18 | Trust, penalties and bans | FR-TRS-01…27 |
| 19 | Points, referrals, wallet and fees | FR-PTS-01…21 |
| 20 | Reviews | FR-REV-01…10 |
| 21 | Platform Admin | FR-ADM-01…11 |
| 22 | Notifications | FR-NTF-01…12 |
| 23 | Audit and historical integrity | FR-AUD-01…06, FR-AUD-13…15 |

**Part C — Non-functional and delivery**
§24 Non-functional requirements (NFR-01…38) · §25 Data and reporting (FR-AUD-07…12) · §26 Release plan · §27 Dependencies and assumptions · §28 Risks · §29 Future roadmap · §30 Traceability · §31 Change control · §32 Glossary

**394 requirements in total** — 356 functional, 38 non-functional. Thirty requirements were added by the Derivation Audit corrections; none was removed.

---

# 1. EXECUTIVE SUMMARY

E7GZ PS is a booking marketplace for gaming and leisure venues in Cairo — PlayStation rooms, billiards tables and ping pong tables. It solves a market where availability is invisible: today a customer phones around or walks in, and a venue tracks reservations in a notebook or a WhatsApp thread.

The product ships as three surfaces against one shared inventory:

- **Customer Mobile App** — find a venue, see the real price, book, and know exactly what is happening with the booking
- **Venue Dashboard** — one inventory covering online, manual and tournament reservations, so the venue cannot double-book
- **Platform Admin** — marketplace control without running the venue's daily operations

The commercial model at launch is deliberately minimal: **the Booking Fee is EGP 0 and E7GZ collects no money**. The customer pays the venue directly, outside E7GZ. The fee mechanism, the wallet and the historical-value rules are built and correct from day one so that a non-zero fee can be switched on later without touching the ledger design.

What makes the product hard is not the booking form. It is that a reservation is a real-world commitment made by a venue that may lose power, break a console, close early, or have its account disabled by the platform — while a customer is holding a slot and a trust record is accumulating. The bulk of this PRD is therefore about **what happens when things go wrong**, and about never blaming a customer or a venue for something the other party — or the platform — caused.

Thirteen consistency audits were run before this document existed. The final audit returned zero hard contradictions, zero architecture blockers and zero open product decisions, and the behaviour was frozen as v5.0. This PRD turns that frozen behaviour into a buildable requirement set.

---

# 2. PROBLEM AND OPPORTUNITY

## 2.1 The customer's problem

A person who wants to play has no reliable way to answer three questions before leaving the house:

1. **Is anything free right now, or at the time I want?**
2. **What will it cost?**
3. **Is this place any good?**

The current answer is a phone call to each venue, or a walk-in and a wait. Prices are quoted verbally and vary by console and play mode. There is no record of the booking and no shared understanding of whether it is confirmed.

## 2.2 The venue's problem

A venue runs three overlapping streams of demand — walk-ins, phone reservations and tournaments — against the same physical rooms and tables. They are tracked in different places, or in nobody's head, which produces the operational failure the business fears most: **two customers sold the same room at the same time.**

Venues also have no cheap way to be discovered by customers who are not already regulars.

## 2.3 The platform's problem

A marketplace that hands venues a booking funnel inherits responsibility for trust. Customers who never show up cost the venue a slot. Venues that accept bookings and then fail to honour them cost the customer an evening. Neither can be managed without a defensible record of what actually happened.

## 2.4 The opportunity

A single shared inventory that all three demand streams write into, exposed to customers as a marketplace, with a trust record that is reconstructible from evidence rather than asserted from a counter. That combination is what E7GZ PS builds.

## 2.5 Why now, and why this shape

The launch deliberately avoids payments. Collecting money introduces settlement, refunds, chargebacks, tax and reconciliation, none of which are needed to prove the core hypothesis: **that customers will book ahead, and venues will honour the bookings, if availability and price are visible.** Launching fee-free removes the largest source of risk and the largest source of build time, while leaving the fee mechanism designed and ready.

---

# 3. VISION, GOALS AND NON-GOALS

## 3.1 Product vision

> **Find → Book → Play.**
>
> Manage all reservations in one inventory.
>
> Control the marketplace without running venue daily operations.

## 3.2 Product principles

These principles decide arguments. Where a requirement seems ambiguous, resolve it in favour of the principle.

**1. One inventory, no double booking.** Online, manual and tournament reservations share one canonical availability predicate. There is no second path to a slot. *Spec §16.*

**2. Never blame the wrong party.** Every terminal outcome carries a reason code that names the real cause. A platform-caused failure is never recorded as venue non-response; a venue-caused failure is never recorded as a customer cancellation. *Spec §33.6.*

**3. Trust is reconstructible, not asserted.** Counters, stages, bans and verified status are derived from an immutable ordered event stream and can be replayed as of any time. No derived value is authoritative. *Spec §45.*

**4. No silent cancellation.** A reservation is never cancelled as a side effect of a configuration action. Maintenance, resource disable, resource archive and tournament creation are all **blocked** by existing reservations rather than overriding them. *Spec §13.4, §36.7, §39.1.*

**5. The past is immutable.** Historical prices, fees, rewards, attendance results and operating-session snapshots are never rewritten by a later configuration change. Corrections are compensating entries, never edits. *Spec §53.*

**6. Protect the owner's sensitive controls.** Pricing and resource-status changes cannot happen casually; they require the Owner Password, per action. *Spec §54.*

**7. Emergencies are honest.** An emergency may cancel commitments immediately and irreversibly, but the system will never quietly return broken inventory to sale. *Spec §38.6.*

## 3.3 Launch goals

| # | Goal | Why it matters |
|---|---|---|
| G1 | A customer can find a venue, see real availability and price, and book — end to end, in the app | The core hypothesis |
| G2 | A venue never double-books a resource across online, manual and tournament demand | The venue's stated primary fear |
| G3 | Every booking reaches a deterministic terminal state with a truthful reason | Trust in the marketplace, and dispute resolution |
| G4 | Trust standing is derived from evidence and correctable by an admin without destroying history | Defensible penalties |
| G5 | The venue can run day-to-day operations — including emergencies — without calling the platform | Venue autonomy |
| G6 | The platform can control the marketplace without touching venue daily operations | Clean separation of concerns |
| G7 | The fee, wallet and points ledgers are correct on day one even though no money moves | A non-zero fee can be enabled later without redesign |

## 3.4 Non-goals for this release

These are **not** failures of scope; they are deliberate exclusions. Each is recorded so it is not rediscovered as a gap. *Spec §6.*

| Excluded | Note |
|---|---|
| PC Gaming | Removed from the product |
| Payment collection, settlement, prize handling | Booking Fee is EGP 0; tournament money is informational only |
| Venue POS, inventory, supplier management, payroll, employee attendance | The Dashboard is not an ERP |
| Full accounting and tax accounting | Revenue tracking is not accounting |
| Food, drink and snack ordering | — |
| Kick Off | Future concept; must not appear in any surface |
| Favorites, community feed, Elo, leaderboards, match history | — |
| Tournament brackets, score tracking, per-match scheduling | Capacity and resource count are intentionally independent |
| Individual cashier accounts, venue manager accounts, separate owner accounts | One shared venue account plus the Owner Password |
| Multi-branch venue account management | — |
| Arabic Customer Mobile UI | The app is English at launch, built localization-ready |
| Customer self-service account deletion | Handled through Support / Platform Admin |
| Active customer Wallet screen | The ledger exists; the screen is hidden |

---

# 4. USERS AND SUCCESS

> *Personas, success metrics, the launch readiness bar, journeys, the release plan, assumptions, risks and the roadmap are authored by this PRD and are **not defined by the Frozen Baseline**. They are governed by PRD §31.2 and may change without a Product Change Request. Where one of them restates a v5.0 rule, the v5.0 section is cited and v5.0 governs.*

## 4.1 Personas

**P1 — The Player (Customer).** Books for themselves and friends, usually same-day or a few hours ahead, often from the street. Cares about: is it free, what does it cost, is it confirmed. Low tolerance for ambiguity — a booking that looks cancelled but might revive is confusing unless the copy is explicit. Feels: *"I can quickly find somewhere available, know the price, see what users think, book it and understand exactly what is happening with my booking."*

**P2 — Venue Staff.** Works the counter. Accepts or rejects requests under time pressure, records walk-ins, checks customers in, marks no-shows, and declares emergencies when a console dies. Needs actions that take seconds and never require chasing the owner. Feels: *"Online, manual and tournament reservations share one inventory, so I always know what is available and cannot double-book."*

**P3 — The Venue Owner.** Not usually at the counter. Owns pricing and resource status. Delegates daily operations but must not have prices changed or rooms archived behind their back. Feels: *"Sensitive pricing and resource-status changes cannot happen casually without my Owner Password."*

**P4 — The Tournament Creator.** Either a Verified Player hosting at a venue, or the venue itself. Needs to hold resources, gather 8–32 players, and know well before the start whether the event is on.

**P5 — The Platform Admin.** Onboards venues, moderates reviews, corrects trust mistakes, investigates abuse signals, and controls global configuration. Never runs a venue's day.

## 4.2 Success metrics

Metrics are grouped by the goal they evidence. Targets are set by the Product Owner at launch; the requirement here is that each metric is **measurable from day one**.

**Marketplace liquidity (G1)**

| Metric | Definition |
|---|---|
| Booking request rate | Online Booking Requests created per active customer per week |
| Request-to-confirmed rate | CONFIRMED ÷ all Online Booking Requests created |
| Time to venue response | Median seconds from PENDING to Accept or Reject |
| Venue response expiry rate | VENUE_RESPONSE_EXPIRED ÷ all requests — a direct measure of venue responsiveness |
| Late Confirm recovery rate | Bookings revived to CONFIRMED ÷ all VENUE_RESPONSE_EXPIRED |
| Search-to-book conversion | Booking requests ÷ venue detail views |

**Inventory integrity (G2)**

| Metric | Definition |
|---|---|
| Double-booking incidents | Count of overlapping active reservations on one resource. **Target: zero. Any occurrence is a Sev-1.** |
| Manual booking share | Manual ÷ all bookings — tells us how much real demand the marketplace is not yet capturing |
| Resource utilisation | Reserved resource-hours ÷ open resource-hours per venue |

**Outcome truthfulness (G3)**

| Metric | Definition |
|---|---|
| Terminal reason distribution | Share of bookings by reason code, split customer-caused / venue-caused / operational / platform |
| Operationally caused cancellation rate | The four operational and platform buckets ÷ all terminal bookings |
| Unreasoned terminal bookings | **Target: zero.** Every terminal booking carries exactly one closed reason code |

**Trust health (G4)**

| Metric | Definition |
|---|---|
| Attendance mix | ATTENDED / ATTENDANCE_UNCONFIRMED / ATTENDED_INTERRUPTED / ABSENT_NO_SHOW share |
| Check-in capture rate | ATTENDED ÷ bookings that reached ACTIVE — low capture means staff are not checking in, and rewards are being lost |
| No-show rate, and ban incidence by stage | — |
| Admin correction rate | Attendance and no-show corrections ÷ attendance events — a rising rate signals an operational problem, not a customer problem |

**Venue autonomy and platform load (G5, G6)**

| Metric | Definition |
|---|---|
| Emergency declarations per venue per month, by type | — |
| Emergencies left in AWAITING_CONFIRMATION past expected end | Measures forgotten operational blocks |
| Resources entering OWNER_REVIEW_REQUIRED | Maintenance-threshold pressure |
| Admin interventions per 100 bookings | The platform's operational cost per unit of marketplace activity |

**Engagement**

| Metric | Definition |
|---|---|
| Verified Player conversion | Users reaching 10 completed attended bookings |
| Tournament fill rate | Tournaments passing checkpoint ÷ tournaments created |
| Review coverage | Venues with ≥1 active review ÷ all active venues |
| Referral conversion | REWARDED ÷ all referrals created |

## 4.3 Launch readiness bar

The product is launch-ready when:

- Every **M** requirement is Done per §0.4
- Zero known double-booking paths exist
- Every terminal booking reason and every tournament reason is reachable and mapped to a fee treatment (v5.0 §34.2)
- Trust replay reproduces current state for a seeded history, and reproduces as-of state at arbitrary past points
- The full emergency cascade is atomic under fault injection
- Every metric in §4.2 is instrumented

---

# 5. SCOPE

## 5.1 The three products

| Product | Users | Platform | Language at launch |
|---|---|---|---|
| **Customer Mobile App** | P1, P4 | Mobile | English, localization-ready for Arabic RTL |
| **Venue Dashboard** | P2, P3, P4 | Web | English (LTR) and العربية (full RTL) |
| **Platform Admin** | P5 | Web | English only |

**Customer Mobile App launch surfaces.** Registration · OTP Verification · Area / Location · Venue Discovery · Map View · Venue Ratings & Reviews · Live Resource Availability · Booking · My Bookings · Late Confirmation · Account Standing · Wallet · Points · Referrals · Verified Player · Tournaments · Notifications · Profile. Every surface listed is launch-required and is implemented by an **M** requirement in Part B. The Wallet surface exists as ledger and data model only: **no active wallet screen ships at launch** (FR-PTS-11). *Spec §2.1, §46.6.*

## 5.2 Activities and resources in scope

| Activity | Resource | Pricing dimensions |
|---|---|---|
| PlayStation | Room | Console type (PS4 / PS5) × play mode (Single / Multiplayer), with optional per-room price override |
| Billiards | Table | Venue-level hourly price |
| Ping Pong | Table | Venue-level hourly price |

PC Gaming is out of scope. Billiards and ping pong have no console type, no play mode and **no table-level price override** in MVP. *Spec §3, §4.*

## 5.3 Market and configuration scope

Single market (Egypt), single timezone (Africa/Cairo) with correct DST handling, single currency (EGP), money to 2 decimal places with half-up rounding. *Spec §4.4, §14.3.*

## 5.4 Explicitly deferred — see §3.4

---

# 6. KEY USER JOURNEYS

These are the journeys the product is judged on. Each names the requirements that implement it.

## J1 — Find and book *(P1)*

The customer opens the app, sees venues in their area sorted by distance, filters by activity, and sees each venue card with its rating and **nearest availability**. They open a venue, pick a room, a date, a time and a duration, see the exact price, and submit. The booking shows as **Pending**. Within ten minutes the venue accepts and it becomes **Confirmed**.

*Implements:* FR-DIS-01…08, FR-BOK-01…09 · *Spec §20, §21, §22, §23*

## J2 — The venue is slow, and the customer's evening is saved *(P1, P2)*

The venue misses the ten-minute window. The customer sees the booking under **Cancelled** with *"Venue did not respond in time."* Internally it is revivable. Twenty minutes later, staff notice it in **Missed / Expired Requests** and choose **Late Confirm**. The customer receives a push: *"Game Zone is asking to reactivate your expired booking. Do you still want it?"* — with a live countdown. They accept, and it becomes Confirmed under the **same Booking ID**, with its full history preserved.

*Implements:* FR-BOK-10…13, FR-LCF-01…14 · *Spec §24, §25*

## J3 — Play and get credit *(P1, P2)*

The customer arrives. Staff tap **Check In** within the first fifteen minutes. At the end time the booking completes as **ATTENDED**: +200 points, +1 towards Verified Player, and the consecutive no-show counter resets to zero.

*Implements:* FR-ATT-01…05 · *Spec §26, §27, §28*

## J4 — Nobody shows up *(P2, P1)*

Fifteen minutes past the start the room is empty. Staff mark **No-show**. The remaining interval is released immediately and can be sold to a walk-in. The customer's counter increments. On the third consecutive no-show a 7-day ban activates, their pending and future confirmed bookings are cancelled, and an already-active session is left alone.

*Implements:* FR-ATT-06…09, FR-TRS-05…10 · *Spec §27.4, §27.7, §48*

## J5 — The customer is late but present *(P2)*

The customer walks in at 8:20 for an 8:00 booking. The Check-in window has closed. Staff use **Mark Present — Late**, which blocks the no-show, grants no reward, and completes the booking as ATTENDANCE_UNCONFIRMED. The customer is not punished, and is not rewarded for a session the product could not verify.

*Implements:* FR-ATT-03, FR-ATT-04 · *Spec §27.3, §27.5*

## J6 — A console breaks mid-session *(P2, P1)*

The PS5 in Room 3 dies during an active booking. Staff declare a **Resource Emergency Outage** with a reason and an expected return time — no Owner Password, because it is an emergency. The active session is terminated under `RESOURCE_EMERGENCY_OUTAGE`; because the customer had checked in, attendance is **ATTENDED_INTERRUPTED**, which resets their counter and grants no reward. Future bookings on that room in the declared window are cancelled with the same honest reason. Other rooms keep selling.

*Implements:* FR-OPS-06…12, FR-ATT-10 · *Spec §41, §31.7*

## J7 — The room keeps breaking *(P2, P3)*

The same room accumulates more than 24 maintenance hours inside a rolling 48-hour window. When the emergency ends, the room does **not** return to sale: it enters `OWNER_REVIEW_REQUIRED`. Its remaining commitments — pending, awaiting, confirmed, active, manual and pre-start tournament — are cancelled atomically under one honest reason. Only the Owner, with the Owner Password, can return it to service, extend maintenance, disable it or archive it.

*Implements:* FR-OPS-13…19 · *Spec §43*

## J8 — The venue records a walk-in *(P2)*

Someone walks in wanting the table now. Staff create a **Manual Booking** starting now; it begins directly as ACTIVE, takes the same inventory, needs no Owner Password, and does not have to fit published opening hours — a venue may record a private arrangement or extended operation.

*Implements:* FR-MAN-01…08 · *Spec §30*

## J9 — The owner changes opening hours *(P3)*

The owner shortens Friday. The system validates the proposed sessions, checks for tournament conflicts first and **blocks** the save if any exist, then computes every affected reservation and shows a complete impact preview. Because confirmed online bookings would be cancelled, the final **Save & Cancel Affected Bookings** demands the Owner Password. The whole change commits atomically or not at all. Affected customers are notified with `VENUE_OPENING_HOURS_CHANGED`.

*Implements:* FR-VEN-08…13 · *Spec §14.5, §14.6*

## J10 — A player hosts a tournament *(P4, P2)*

A Verified Player requests a tournament at a venue at least two hours before the start. Resources take temporary Approval Holds, and the tournament is invisible to everyone else. The venue has ten minutes to accept. On accept it becomes **Waiting for Players** and, if public, appears in Discover. At exactly one hour before the start, the checkpoint runs: eight or more active participants confirms it, fewer cancels it and releases the resources.

*Implements:* FR-TRN-01…16 · *Spec §36, §37*

## J11 — The platform disables a venue account mid-session *(P5, P1)*

An admin disables the venue's account while a booking is active. The physical venue keeps operating, so the session is **not** cancelled — but staff can no longer record attendance. The customer is told that account access was restricted and attendance recording may be affected, explicitly **not** that the booking was cancelled. At resolution the product applies **Platform Attendance Protection**: no penalty, counter reset, and no unverified reward.

*Implements:* FR-ATT-11…14 · *Spec §32*

## J12 — An admin fixes a wrongful no-show *(P5, P1)*

The customer disputes a no-show and produces evidence. The admin reverses it with a mandatory reason. Trust replay recalculates the counter, the stage and any ban from the corrected event stream. The released slot is **not** taken back from whoever bought it, and there is no special admin booking path — the customer rebooks through the normal flow.

*Implements:* FR-TRS-11…16 · *Spec §45.6, §47*

---

# 7. SYSTEM CONTEXT

## 7.1 The domains that must never be conflated

Fourteen independent status domains exist. Requirements and implementation must never infer one from another. *Spec §0.5, §55.20.*

| Domain | Values |
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

## 7.2 The three eligibility categories

Every action is exactly one of these. Getting the category wrong is the most common source of incorrect behaviour. *Spec §35.*

| Category | Used for | Requires Marketplace ACTIVE? |
|---|---|---|
| **A — New-Activity Marketplace** | New online booking, new player-hosted tournament request, new venue-hosted tournament | **Yes** |
| **B — Existing-Commitment Operational** | Venue Accept, Late Confirm, tournament checkpoint, booking start, tournament start | **No** — asks only whether the venue can operationally honour it |
| **C — Internal Venue Operation** | Manual booking, internal scheduling and resource management | **No** — ACTIVE, INACTIVE or SUSPENDED; never ONBOARDING |

## 7.3 Money at launch

| Flow | At launch |
|---|---|
| E7GZ Booking Fee | **EGP 0**, per booking |
| Venue gaming charge | Paid by the customer to the venue, **outside E7GZ** |
| Tournament entry fee and prize | **Informational only** — E7GZ collects, holds and distributes nothing |
| Tournament venue cost | Informational; settled between organiser and venue outside E7GZ |
| Tournament Creation Fee | **EGP 0** |
| Customer Wallet | Ledger exists; **no active screen** |
| Points | **Active** — earned and adjustable |
| Payment method tracking | **Not tracked** — no cash, card, Visa, Mastercard or terminal |

*Spec §5.*

---

# PART B — FUNCTIONAL REQUIREMENTS

---

# 8. ACCOUNTS, IDENTITY AND VERIFICATION

**Rationale.** Phone verification is the gate to every marketplace action, because a phone number is the only identifier a venue can actually use to reach a customer. Email is collected but never sufficient on its own.

| ID | Pri | Requirement | Spec |
|---|---|---|---|
| FR-ACC-01 | M | Registration captures **Full Name**, **Username**, **Phone**, **Email** and **Password**, all required. Username and Phone are unique; Full Name and Email are not constrained to be unique. Public reviews display **username only**; Platform Admin may see both full name and username. | §7.1 |
| FR-ACC-02 | M | An account becomes **Account Verified** only when `phone_verified = true` via SMS OTP. Email OTP alone must never activate an account for marketplace actions. | §7 |
| FR-ACC-03 | M | An unverified user may browse and view venues, and may not create a booking, join or create a tournament, or write a review. | §7 |
| FR-ACC-04 | M | OTP rules: valid 5 minutes · max 5 attempts per code · resend after 60 seconds · max 5 sends per hour per destination · a new OTP invalidates the previous one. | §7 |
| FR-ACC-05 | M | Email is mandatory registration data. Email verification may occur separately and supports ownership verification, account recovery and email notifications; an unverified email never blocks booking once the phone is verified. | §7 |
| FR-ACC-06 | M | Username uniqueness is evaluated on a normalised form — trimmed and case-insensitive. | §7 |
| FR-ACC-07 | M | Username is editable with a **30-day cooldown**; Full Name is editable freely. Phone and email changes each require verification of the new value. | §7 |
| FR-ACC-08 | M | Historical entities reference the immutable User ID. Reviews display the **current** username. | §7, §49.2 |
| FR-ACC-09 | M | Password reset uses verified phone OTP or email verification and does not require the old password. | §7 |
| FR-ACC-10 | M | There is **no customer self-service Delete Account** in MVP. Deletion and anonymisation run through Support / Platform Admin, preserving legally and operationally required booking, financial and audit history while removing reviews from public display. | §53.5 |
| FR-ACC-11 | M | **Verified Player Qualification** is derived from corrected trust history — **10 completed attended bookings, all of which may be at the same venue** — and is displayed as progress, for example *"6 / 10 Completed Bookings"*. It is separate from **Operational Verified Player Status** (NOT_VERIFIED / VERIFIED / SUSPENDED / REVOKED). Only VERIFIED may create a player-hosted tournament. | §8.1, §8.2, §8.3 |
| FR-ACC-12 | M | Every operational Verified Player transition creates an immutable `verifiedPlayerTransitionId`, including a retroactively discovered first qualification. | §8 |
| FR-ACC-13 | M | A retroactively discovered qualification activates operational status **now**, never back-dated. | §8 |
| FR-ACC-14 | M | Where operational status was REVOKED **solely** by `TRUST_CORRECTION` and a later replay restores qualification, the system **must** transition REVOKED → VERIFIED automatically with a new transition ID. No admin action is required in this case. | §8 |
| FR-ACC-15 | M | Admin SUSPENDED and admin REVOKED are protected: automatic trust regain never overrides them. Admin Restore yields VERIFIED if currently qualified, otherwise NOT_VERIFIED. | §8 |
| FR-ACC-16 | M | Manual bookings and tournament participation contribute **nothing** to Verified Player progress. | §8, §30.6 |
| FR-ACC-17 | M | A banned user may log in, browse, view venues, view history, view account standing and profile, cancel an existing future booking, decline a Late Confirmation, and write, edit or delete reviews. They may **not** create a booking, accept a Late Confirmation, join a tournament or create a tournament. | §9 |
| FR-ACC-18 | M | **Qualification as-of** and **operational status as-of** are separate historical queries and may legitimately differ after a correction. Historical qualification never implies historical operational tournament privileges. | §8.10 |

**Acceptance criteria**

- An unverified account is rejected at every one of the four gated actions, with a message that names phone verification as the requirement
- A 6th OTP send within an hour to the same destination is refused
- Changing a username twice within 30 days is refused; the error states the remaining cooldown
- A user who loses Verified Player status through a trust correction, then regains qualification through a later correction, is VERIFIED again with **no** admin action, and carries two distinct transition IDs
- A user suspended by an admin who then reaches qualification remains SUSPENDED
- Accepting a Late Confirmation while banned is refused; declining it succeeds

---

# 9. DISCOVERY AND BROWSING

**Rationale.** A closed venue is still a useful search result — the customer is usually planning the next few hours, not the next five minutes. Removing closed venues from discovery would make the marketplace look empty for most of the day.

| ID | Pri | Requirement | Spec |
|---|---|---|---|
| FR-DIS-13 | M | The canonical discovery order is **Area / Location → Activity → Venue → Resource → Availability → Booking**. **Activity selection happens before venue discovery**, which is what makes Nearest Availability computable per selected activity. | §20.1 |
| FR-DIS-14 | M | The canonical booking flow is **Area → Activity → Venue → Resource → (Single / Multiplayer, PlayStation only) → Date → Start Time → Duration → Booking Summary → Confirm**. | §20.8 |
| FR-DIS-01 | M | With location enabled and no manual area selection, show eligible venues across **active areas** sorted primarily by distance. | §20.2 |
| FR-DIS-02 | M | A manually selected area becomes a **hard filter**, even while location permission remains enabled. | §20.2 |
| FR-DIS-03 | M | The **venue card** shows: venue image · venue name · area · distance where available · **starting price** · **Nearest Availability** · average rating · review count. | §20.3 |
| FR-DIS-04 | M | **Nearest Availability** is the earliest valid start time for the currently selected activity at the **minimum booking duration of 1 hour**, computed from opening hours, eligible resources, the canonical availability predicate, the current time and the 15-minute lead-time rule. For PlayStation, before any room or console filter, use the earliest valid PlayStation resource. | §16.5 |
| FR-DIS-05 | M | Resources that are DISABLED, ARCHIVED, under `OWNER_REVIEW_REQUIRED`, or under an operational selling block at venue or resource level must **never** contribute to Nearest Availability or appear as available. | §16.5, §43.7 |
| FR-DIS-06 | M | A currently closed venue remains discoverable. The customer may view the venue, activities, future availability, prices and Nearest Availability, and sees state such as *Closed Now* and *Opens at 12:00 PM*. They may not submit a request until the next valid online intake window. | §20.7 |
| FR-DIS-07 | M | Cross-activity discovery surfaces the venue's other activities — *Also available at this venue*. | §20.6 |
| FR-DIS-08 | M | Venues with marketplace status ONBOARDING, INACTIVE or SUSPENDED are not shown in normal customer discovery. A deep-linked venue page may remain viewable with booking actions disabled. | §11 |
| FR-DIS-09 | M | The venue phone is hidden before a booking exists. Counterpart phone access **opens when the booking becomes PENDING**: the venue may see the customer phone and the customer may see the venue phone. **Manual booking is exempt**, because the venue entered any contact data itself. | §20.10, §34.5 |
| FR-DIS-10 | M | **Map view** shows venue markers positioned by coordinates under the same eligibility rules as the list. A marker preview contains venue name, distance, starting price, Nearest Availability, average rating, review count and a **View Venue** action. | §20.4, §2.1 |
| FR-DIS-11 | M | **Venue Details** shows images, venue name, area, address, distance, opening hours, rating, reviews, a **View Location** action, the selected activity, its resources, pricing and availability. | §20.5 |
| FR-DIS-12 | M | **Find Available** search takes area, activity, date, start time and duration, and returns only resources available for the **entire** requested interval under the canonical availability predicate. There is **no price filter** in this release. | §20.9 |

**Acceptance criteria**

- A venue whose only PlayStation room is under owner review shows no PlayStation nearest availability, and does not appear in a PlayStation-filtered list as bookable
- Selecting an area while location is on returns only that area's venues
- A closed venue is browsable and priced, and its book action is disabled with copy explaining when it opens
- A suspended venue disappears from the list but its deep link still renders, with booking disabled

---

# 10. AVAILABILITY AND INVENTORY

**Rationale.** This is the single most important requirement group in the product. Every path to a slot uses the same predicate; there is no second path. A double booking is a Sev-1 defect.

## 10.1 Three distinct questions

The engine answers three separate questions and must never conflate them: **A.** is the resource interval free · **B.** is this online booking allowed · **C.** is this tournament or operational action allowed. *Spec §16.1.*

| ID | Pri | Requirement | Spec |
|---|---|---|---|
| FR-AVL-01 | M | A single canonical availability predicate governs every flow — online booking, manual booking, Late Confirm, tournament creation, tournament holds and Nearest Availability. | §16 |
| FR-AVL-02 | M | Canonical availability evaluates, **in this order**: **1.** venue operational selling block · **2.** resource operational selling block · **3.** resource base state · **4.** resource operational restriction · **5.** emergency type, status, affected scope and declared interval · **6.** maintenance · **7.** closure · **8.** blocking reservations. An operational selling block is evaluated **explicitly before** any resource reservation check and needs no fabricated future interval; an unresolved emergency is **never** treated as an infinitely extended reservation. | §16.2 |
| FR-AVL-03 | M | A resource interval is **not free** when **any** of: the resource is DISABLED or ARCHIVED · `operationalRestriction = OWNER_REVIEW_REQUIRED` · a relevant operational selling block is active at venue or resource level · the interval overlaps a blocking reservation from a PENDING, AWAITING_CUSTOMER_CONFIRMATION, CONFIRMED or ACTIVE booking of either source, a Player Tournament Approval Hold, a Tournament Hold, Maintenance, a Venue Closure, an Emergency Closure or a Resource Emergency Outage. | §16.3 |
| FR-AVL-04 | M | **Booking status decides whether a booking blocks inventory; booking source does not.** For `source = MANUAL` only CONFIRMED and ACTIVE are normally reachable blocking states. | §16.3 |
| FR-AVL-05 | M | `VENUE_RESPONSE_EXPIRED` does **not** block inventory, because its reservation has been released. Terminal bookings never block. | §16.3 |
| FR-AVL-06 | M | A resource requires **both** `baseState = ACTIVE` **and** `operationalRestriction = NONE` to be eligible for any flow. | §13.3, §16.2, §43 |
| FR-AVL-07 | M | Every creation and commitment predicate requires `Operational Selling Block = FALSE` for the affected venue **and** resource. Interval-overlap checks alone are never sufficient. | §38.4 |
| FR-AVL-08 | M | **Double booking is never allowed.** Two active reservations must never overlap on one resource. | §16.4 |
| FR-AVL-09 | M | Eligibility is revalidated immediately before **every** commitment-strengthening transition — creation, Venue Accept, Late Confirm initiation, Late Confirm Accept, manual booking creation, tournament creation, tournament approval, tournament checkpoint confirmation, booking CONFIRMED → ACTIVE, tournament CONFIRMED → ACTIVE, and resource or hold assignment, **and before any future transition whose effect is to obtain or strengthen a resource or venue commitment**. No lifecycle boundary is exempt from operational eligibility. | §31.9, §31.10 |
| FR-AVL-10 | M | Revalidation and the state transition commit in **one atomic transaction**. The first successfully committed valid transition wins; a state change between validation and commit causes revalidation or failure. | §31.11 |
| FR-AVL-11 | M | Displayed availability is a snapshot, not a commit guarantee. An action is offered only when the latest known state satisfies its predicate; execution always revalidates atomically and may fail. | §31.12 |
| FR-AVL-12 | M | **Historical planned intervals may overlap; active reservation occupancy may not.** A no-show releases the remainder of an interval, so a later valid booking may start inside the original planned window. | §17.2 |
| FR-AVL-13 | M | Reservation history stores `plannedStart`, `plannedEnd`, `actualReservationStart`, `actualReservationReleaseAt` and `releaseReason`, so historical availability can be replayed and an apparent overlap explained. | §17.3 |
| FR-AVL-14 | M | Every revenue-producing record — online booking, manual booking, tournament — stores a `businessDate`, which is the canonical revenue-day key and is historical. | §17.5 |
| FR-AVL-15 | M | Canonical timer boundary: an action allowed "before" a deadline requires `now < deadline`; at `now >= deadline` the expiry owns the state. This applies to the venue response window, Late Confirmation, customer cancellation at start, tournament join and leave at start, and every equivalent boundary. | §17.6 |

**Acceptance criteria**

- A concurrency test issuing N simultaneous bookings for one slot produces exactly one CONFIRMED path and N−1 clean failures, with no partial reservation
- A resource with `baseState = ACTIVE` and `operationalRestriction = OWNER_REVIEW_REQUIRED` is rejected by all six flows in FR-AVL-01
- A venue under an emergency selling block rejects a manual booking whose interval does not overlap the declared emergency interval
- After a no-show at 8:16 on an 8:00–9:00 booking, a new booking starting 8:20 succeeds, and both records survive with overlapping planned intervals and non-overlapping reservation intervals
- A manual booking created outside published opening hours still carries a `businessDate`

---

# 11. ONLINE BOOKING LIFECYCLE

**Rationale.** The venue gets ten minutes to answer because a reservation that sits unanswered is worse than a rejection — it holds a room the venue could sell. The window is enforced while the venue is actually open, which is why the intake window closes ten minutes before the session ends.

## 11.1 Booking parameters

| ID | Pri | Requirement | Spec |
|---|---|---|---|
| FR-BOK-01 | M | Duration: minimum **1 hour**, increments of **30 minutes**, maximum **6 hours** for online bookings. | §19 |
| FR-BOK-02 | M | A new online booking requires `now <= startAt − 15 minutes`. Exactly 15 minutes is permitted; less is rejected. | §19 |
| FR-BOK-03 | M | A customer may hold at most **4** active/upcoming online commitments — counting PENDING, AWAITING_CUSTOMER_CONFIRMATION, CONFIRMED and ACTIVE across all venues. VENUE_RESPONSE_EXPIRED and terminal states do not count; manual bookings do not count. The limit is checked on **every** action that would increase the count. | §19.1 |
| FR-BOK-04 | M | A customer may not hold **overlapping** online commitments. The 4-booking limit is a total-count limit **in addition to** the no-overlap rule. | §19.2 |
| FR-BOK-05 | M | The booking interval must fit entirely inside **one** future operating session. It may cross midnight where the session does. It may not cross a closure, a closed gap, or the end of one session into another. | §14.5 |
| FR-BOK-06 | M | Price = `hourlyPrice × nominalBookedMinutes ÷ 60`, rounded **half-up to 2 decimal places** per line item; totals are sums of rounded line items. The stored historical amount is immutable. | §4.3 |
| FR-BOK-07 | M | Price and duration use the customer-selected **nominal** duration, never a DST-anomalous elapsed duration. Inventory and `endAt` use real elapsed time: `endAt = startAt + nominalBookedMinutes`. | §4.4, §14.3 |

## 11.2 Intake window

| ID | Pri | Requirement | Spec |
|---|---|---|---|
| FR-BOK-08 | M | A request may be submitted only while the venue is **currently open** under its current operating session — even for a future date and time. | §21 |
| FR-BOK-09 | M | New online booking intake **stops 10 minutes before the current operating session ends**, guaranteeing the venue a full response window while operating. | §21 |
| FR-BOK-10 | M | Being open at submission does not make every future slot valid; the requested future interval is validated independently. | §21 |

## 11.3 Creation predicate

| ID | Pri | Requirement | Spec |
|---|---|---|---|
| FR-BOK-11 | M | Creation requires **all** of: account verified by phone OTP · customer not banned · customer below the 4-commitment limit · no overlapping customer commitment · venue marketplace **ACTIVE** · venue account **ENABLED** · area **ACTIVE** · venue inside an operating session · at least 10 minutes before session end · no venue operational selling block · no resource operational selling block · requested start at least 15 minutes ahead · duration 1–6 hours in 30-minute increments · interval fits one future operating session · resource base state **ACTIVE** · resource operational restriction **NONE** · no overlapping blocking reservation · activity/resource compatibility · **all other required product validation** (§22 item 20). The twenty conditions above are cumulative and are the complete canonical list. Note: the explicit *"required pricing configuration exists"* condition belongs to the **manual** booking predicate (§30.2 item 15, FR-MAN-02) and is **not** one of §22's twenty. Where a venue's pricing configuration for the activity is absent, online creation fails under §22 item 20. Stating it as an explicit twenty-first condition of §22 would be a Product Change Request against v5.0 §22, not a PRD edit; the Product Owner has ruled that **no twenty-first condition is added**. | §22 |

## 11.4 States and venue response

| ID | Pri | Requirement | Spec |
|---|---|---|---|
| FR-BOK-12 | M | Canonical states: PENDING · VENUE_RESPONSE_EXPIRED · AWAITING_CUSTOMER_CONFIRMATION · CONFIRMED · ACTIVE · COMPLETED · REJECTED · CANCELLED · NO_SHOW. Terminal: COMPLETED, REJECTED, CANCELLED, NO_SHOW. **`VENUE_RESPONSE_EXPIRED` is non-terminal.** | §18.2 |
| FR-BOK-13 | M | On creation the booking is **PENDING** and reserves the resource. The venue has **10 minutes** to Accept or Reject. There is **no auto-accept**. | §23.1 |
| FR-BOK-14 | M | Venue Accept revalidates atomically: venue existing-commitment eligibility, venue selling block, resource base state, resource operational restriction, resource selling block, reservation validity, interval validity, and **always** customer account and ban eligibility. | §23.2 |
| FR-BOK-15 | M | Venue Accept uses **existing-commitment** eligibility (category B) and therefore does **not** require marketplace ACTIVE. A venue that became INACTIVE or SUSPENDED after the request may still accept it. | §35.2 |
| FR-BOK-16 | M | If Accept fails because the venue or resource became unavailable through a venue emergency, a resource emergency or owner review, the booking may **not** become CONFIRMED; it is CANCELLED under the applicable operational reason, and **no venue non-response blame is applied**. | §23.3 |
| FR-BOK-17 | M | Venue Reject: PENDING → **REJECTED** under terminal reason code `VENUE_REJECTED`. A **venue-supplied reject reason is mandatory** and is recorded separately from the reason code — examples: resource unavailable, maintenance, venue issue, other. The **resource reservation is released immediately**. | §23.4 |
| FR-BOK-18 | M | At `venueResponseExpiresAt` with no response, PENDING → **VENUE_RESPONSE_EXPIRED** and the resource reservation releases immediately. | §23.5 |
| FR-BOK-19 | M | The customer-facing projection maps: **Upcoming** = PENDING, AWAITING_CUSTOMER_CONFIRMATION, CONFIRMED, ACTIVE · **Completed** = COMPLETED · **Cancelled** = VENUE_RESPONSE_EXPIRED, CANCELLED, REJECTED, NO_SHOW. | §18.3 |
| FR-BOK-20 | M | A VENUE_RESPONSE_EXPIRED booking is displayed under Cancelled with *"Venue did not respond in time"*, while remaining internally eligible for Late Confirmation until `startAt`. There is no pre-emptive customer opt-out, and no automatic reactivation. | §18.3, §24.1 |
| FR-BOK-21 | M | The venue may **Reject After Expiry** before the start time: VENUE_RESPONSE_EXPIRED → REJECTED, reason `REJECTED_AFTER_EXPIRY`. | §24.2 |
| FR-BOK-22 | M | Resource Disable, Archive and Console Change may proceed while a booking is only VENUE_RESPONSE_EXPIRED, since no reservation is held. The system records `LATE_CONFIRM_ELIGIBILITY_LOST_RESOURCE_STATE_CHANGED` with the matching `detailCode`; no additional customer push is required. | §24.4 |

## 11.5 Resolution at the original start time

| ID | Pri | Requirement | Spec |
|---|---|---|---|
| FR-BOK-23 | M | A booking still VENUE_RESPONSE_EXPIRED at its original `startAt` becomes CANCELLED, with the reason chosen by this priority: **1.** `RESOURCE_OWNER_REVIEW_REQUIRED` · **2.** `RESOURCE_EMERGENCY_OUTAGE` · **3.** `VENUE_EMERGENCY_CLOSURE` · **4.** `VENUE_ACCOUNT_DISABLED` · **5.** `VENUE_DID_NOT_RESPOND_IN_TIME`. The last applies only when no overriding operational or platform cause exists. | §24.3 |

## 11.6 Start, completion and cancellation

| ID | Pri | Requirement | Spec |
|---|---|---|---|
| FR-BOK-24 | M | CONFIRMED → ACTIVE at `startAt` and ACTIVE → COMPLETED at `endAt` are automatic and backend-controlled. No venue action is required. | §26.1 |
| FR-BOK-25 | M | Immediately before CONFIRMED → ACTIVE the system revalidates venue selling block, resource base state, resource operational restriction, resource selling block, and applicable closure or emergency conditions — atomically with the transition. | §31.2 |
| FR-BOK-26 | M | Every start-time failure produces a deterministic terminal transition, a closed reason code, an attendance result, a notification and a refund classification. **A booking can never remain CONFIRMED past `startAt` because a check failed.** | §31.3 |
| FR-BOK-27 | M | Start-time reason priority: **1.** `RESOURCE_OWNER_REVIEW_REQUIRED` · **2.** `RESOURCE_EMERGENCY_OUTAGE` · **3.** `VENUE_EMERGENCY_CLOSURE` · **4.** `VENUE_CLOSURE` · **5.** `VENUE_ACCOUNT_DISABLED` where it applies to an unresolved pre-confirmation lifecycle · **6.** `SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR` · **7.** other explicit admin or system correction. Exactly one reason is stored. | §31.6 |
| FR-BOK-28 | M | If a resource base-state anomaly survives to `startAt`, the booking is cancelled immediately under `SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR`, with no customer penalty, a full future fee refund, a customer notification and a Platform Admin alert recording booking ID, resource ID, the unexpected state, `startAt`, detection time, the conflicting source where known, and a correlation identifier. | §31.4 |
| FR-BOK-29 | M | There is **no ordinary early session end**. An ACTIVE booking blocks its resource until `endAt`. The only early-termination paths are venue emergency closure, resource emergency outage, entry into `OWNER_REVIEW_REQUIRED`, and Platform Admin support correction. | §26.3 |
| FR-BOK-30 | M | ACTIVE interruption reason priority: **1.** `RESOURCE_OWNER_REVIEW_REQUIRED` · **2.** `RESOURCE_EMERGENCY_OUTAGE` · **3.** `VENUE_EMERGENCY_CLOSURE` · **4.** explicit Platform Admin correction. | §31.8 |
| FR-BOK-31 | M | The customer may cancel while `now < startAt`, free of charge, with no no-show and no trust penalty, reason `CUSTOMER_CANCELLED`. After start time, customer cancellation is disabled. | §29.1 |
| FR-BOK-32 | M | The customer retains the right to cancel a PENDING booking while its response timer is paused by an emergency. | §29.2 |

**Acceptance criteria**

- A request submitted 9 minutes before the session ends is refused with the intake-window reason; one submitted 11 minutes before succeeds
- A request for `startAt` exactly 15 minutes ahead succeeds; 14 minutes 59 seconds is refused
- A 5th commitment is refused at creation, and also at Late Confirm initiation and at Late Confirm Accept
- A venue that accepts at `venueResponseExpiresAt − 1s` succeeds; at the boundary the expiry wins
- A booking expiring at a venue whose account is disabled resolves at `startAt` under `VENUE_ACCOUNT_DISABLED`, never `VENUE_DID_NOT_RESPOND_IN_TIME`
- A SUSPENDED venue can still accept a PENDING booking created before suspension
- Forcing a resource into DISABLED behind the predicate and letting a CONFIRMED booking reach `startAt` produces `SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR`, a terminal booking, and an admin alert
- Every terminal booking in a seeded end-to-end run carries exactly one reason code from the closed enum

---

# 12. LATE CONFIRMATION

**Rationale.** Late Confirm exists because a venue that missed the window still has the room, and the customer still wants it. It is a **revival of the same booking**, not a new one — which is why it keeps the Booking ID, why declining it costs the customer nothing, and why it is classified as existing-commitment management rather than new marketplace acquisition.

| ID | Pri | Requirement | Spec |
|---|---|---|---|
| FR-LCF-01 | M | Late Confirm is **existing-commitment operational management** (category B), not new-activity acquisition. | §35.2 |
| FR-LCF-02 | M | Venue eligibility: marketplace status **ACTIVE, INACTIVE or SUSPENDED** · venue account **ENABLED** · area **ACTIVE** · venue currently inside a valid operating session · no venue operational selling block · the original interval still fits valid opening hours · original start still in the future. | §25.2 |
| FR-LCF-03 | M | Resource eligibility: base state **ACTIVE** · operational restriction **NONE** · no resource operational selling block · the interval still passes the canonical conflict predicate. | §25.3 |
| FR-LCF-04 | M | Customer eligibility at initiation: not banned · below the 4-commitment limit. If the customer is already at 4, initiation is **blocked**, the booking stays VENUE_RESPONSE_EXPIRED, and **no temporary hold is created**. | §25.4 |
| FR-LCF-05 | M | When initiation is blocked by the limit, the venue sees a neutral message — *"Customer is not currently eligible for Late Confirmation"* — and **never** learns about the customer's other bookings. The internal event is `LATE_CONFIRM_FAILED_BOOKING_LIMIT`. | §25.4 |
| FR-LCF-06 | M | Initiation is **one atomic transaction**: read commitment count · validate the limit · validate the resource · create the temporary hold · transition VENUE_RESPONSE_EXPIRED → AWAITING_CUSTOMER_CONFIRMATION. Concurrent initiations can never produce a temporary fifth commitment. | §25.5 |
| FR-LCF-07 | M | Late Confirm is **not** restricted by the customer intake window; the venue may initiate it even with fewer than 10 minutes left in the session, because it is a venue-initiated action needing no new venue response window. | §25.6 |
| FR-LCF-08 | M | Late Confirmation keeps the **same Booking ID**, venue, activity, resource, console, play mode, date, time, duration, venue price, booking fee and total. All prior history is preserved. | §25.7 |
| FR-LCF-09 | M | `lateConfirmExpiresAt = MIN(now + 5 minutes, booking.startAt)`. The window must never extend beyond the original start time. | §25.8 |
| FR-LCF-10 | M | Late Confirmation state is stored **per booking** — its own expiry, warning flag, customer response and resource hold. One user may hold several simultaneous Late Confirmations that never interfere. | §25.8 |
| FR-LCF-11 | M | Customer copy must communicate that the original request expired — *"Game Zone is asking to reactivate your expired booking. Do you still want it?"* — with **Accept Booking** and **Decline** actions. | §25.9 |
| FR-LCF-12 | M | If the window exceeds 60 seconds, send the Late Confirmation notification plus one *Late Confirmation Expiring* notification about a minute before expiry, once per booking, linked to the exact Booking ID. If the window is 60 seconds or less, send no second notification and show the real short countdown in the first. | §25.9 |
| FR-LCF-13 | M | Responses: **Accept** → CONFIRMED · **Decline** → CANCELLED `CUSTOMER_DECLINED_LATE_CONFIRM` · **No response** → CANCELLED `CUSTOMER_RECONFIRM_TIMEOUT`. All keep the same Booking ID and carry **no penalty**. | §25.10 |
| FR-LCF-14 | M | Accept revalidates: commitment count still valid · no customer schedule conflict · resource reservation still valid · ban and account eligibility · venue and resource operational eligibility including selling block. | §25.11 |
| FR-LCF-15 | M | For privacy, the customer schedule conflict is checked **only when the customer attempts to accept**. The venue is never told the customer has another booking and may freely send the request. | §25.12 |
| FR-LCF-16 | M | A conflicting Accept **fails** and records `LATE_CONFIRM_FAILED_CUSTOMER_CONFLICT`. The booking does not terminate under a generic schedule-conflict reason; any later terminal outcome uses its real reason. | §25.12 |
| FR-LCF-17 | M | Resource unavailability during Late Confirm is expressed as validation failure, atomic transaction failure, or `LATE_CONFIRM_FAILED_RESOURCE_CONFLICT`. There is **no** terminal "late confirm resource unavailable" reason. | §25.13 |
| FR-LCF-18 | M | Counterpart phone visibility **must reopen** for the AWAITING_CUSTOMER_CONFIRMATION period, and is fully restored on Accept. | §34.7 |

**Acceptance criteria**

- Initiating at 7:59 PM for an 8:00 PM booking gives the customer exactly 60 seconds and **no** second notification
- Initiating at 7:50 PM gives 5 minutes and one expiring notification at roughly 7:54 PM
- A customer at 4 commitments cannot have a Late Confirm initiated for them; the venue message reveals nothing, and no hold is created
- Two venues initiating Late Confirm simultaneously for a customer at 3 commitments produce exactly one AWAITING booking
- A customer accepting a Late Confirmation that overlaps another commitment gets a clear failure, the booking stays AWAITING until its own expiry, and the event is recorded
- A declined Late Confirmation costs the customer nothing and, under a future non-zero fee, refunds in full

---

# 13. ATTENDANCE, CHECK-IN AND NO-SHOW

**Rationale.** MVP deliberately trusts venue staff rather than demanding a QR code or a customer PIN, because friction at the counter would kill adoption. The protection is not identity proof — it is audit, admin correction, trust replay and abuse monitoring. The 15-minute boundary is the hinge: before it a customer can earn credit, after it the venue must choose honestly between "absent" and "present but late", and "present but late" earns nothing.

| ID | Pri | Requirement | Spec |
|---|---|---|---|
| FR-ATT-01 | M | **Check-in** is a venue staff action requiring no QR code, PIN, customer device confirmation or biometrics. It does not require the Owner Password, and it is audited with venue account identity, timestamp and Booking ID. | §27.1, §27.2 |
| FR-ATT-02 | M | Check-in window: `startAt <= now < startAt + 15 minutes`. At exactly `+15 minutes` check-in is refused. | §27.2 |
| FR-ATT-03 | M | **Mark Present — Late** is available while `startAt + 15 min <= now < endAt`, only when no valid check-in exists and no no-show has been committed. It blocks no-show, grants no points, grants no Verified Player progress, and does **not** reset the counter. It requires no Owner Password and is audited. | §27.3 |
| FR-ATT-04 | M | **No-show** is available while `startAt + 15 min <= now < endAt`, only when no valid check-in and no Mark Present — Late exist. It requires no Owner Password and is audited. A customer marked Late Present can never afterwards be marked no-show for the same booking. | §27.4, §27.5 |
| FR-ATT-05 | M | A no-show **releases the remaining interval immediately**; the resource may then be sold online, manually or otherwise under normal rules. | §27.7 |
| FR-ATT-06 | M | A committed no-show is terminal and **venue staff cannot undo it**. Reversal requires Platform Admin. Reversing a no-show never evicts a later customer who validly obtained the released slot. | §27.7 |
| FR-ATT-07 | M | **Attendance Result is an independent stored field**, never permanently derived from booking status: ATTENDED · ATTENDANCE_UNCONFIRMED · ATTENDED_INTERRUPTED · ABSENT_NO_SHOW · NOT_APPLICABLE. The token is `ABSENT_NO_SHOW`, deliberately distinct from booking status `NO_SHOW`. | §28.1 |
| FR-ATT-08 | M | Default generation: COMPLETED after valid check-in → ATTENDED · COMPLETED without verified attendance and without no-show → ATTENDANCE_UNCONFIRMED · status NO_SHOW → ABSENT_NO_SHOW · valid check-in then emergency interruption → ATTENDED_INTERRUPTED · manual booking → NOT_APPLICABLE · cancelled or rejected before attendance → NOT_APPLICABLE · interruption with Late Present only → ATTENDANCE_UNCONFIRMED · interruption with no evidence → NOT_APPLICABLE. These are defaults, not permanent formulas. | §28.2 |
| FR-ATT-09 | M | After audited correction the following are intentionally valid: COMPLETED with ABSENT_NO_SHOW · CANCELLED with ATTENDED_INTERRUPTED · CANCELLED with ATTENDED. An `ATTENDANCE_CORRECTED` may replace the result **without** changing an already-terminal status. | §28.3 |
| FR-ATT-10 | M | Evidence at interruption decides the outcome: qualifying check-in → ATTENDED_INTERRUPTED with counter reset · Late Present only → ATTENDANCE_UNCONFIRMED, counter unchanged · nothing → NOT_APPLICABLE, counter unchanged. In every case there is no customer penalty and, under a future non-zero fee, a full refund. | §28.5, §31.7 |
| FR-ATT-11 | M | Platform Admin may reverse a check-in, reverse a Mark Present — Late, reverse a no-show, and issue an attendance correction. Each requires a mandatory reason and is audited. **Venue staff can reverse none of them.** | §27.8 |
| FR-ATT-12 | M | Reversal semantics: check-in reversed while ACTIVE keeps the booking ACTIVE and re-opens no-show eligibility within the window · check-in reversed after an ATTENDED completion creates `ATTENDANCE_CORRECTED` and reverses points by compensating transaction · Late Present reversed before `endAt` re-opens no-show eligibility · no-show reversed before original `endAt` leaves the booking CANCELLED under `ADMIN_NO_SHOW_REVERSAL` · no-show reversed after original `endAt` yields COMPLETED / ATTENDANCE_UNCONFIRMED unless attendance is separately established. | §27.8 |
| FR-ATT-13 | M | After a wrong no-show the admin chooses explicitly: **attendance confirmed with reallocation** → ATTENDED_INTERRUPTED · **attendance confirmed, session actually completed** → ATTENDED · **penalty removal only** → ATTENDANCE_UNCONFIRMED. | §27.9 |

## 13.1 Platform Attendance Protection

**Rationale.** If the platform disables a venue's account mid-session, staff cannot record attendance. Punishing the customer for that would be indefensible; rewarding them for an unverified session would be equally wrong. The product therefore grants **no penalty and no reward**.

| ID | Pri | Requirement | Spec |
|---|---|---|---|
| FR-ATT-14 | M | Disabling the venue account does **not** terminate an ACTIVE session. The physical venue may still be operating. | §32.1 |
| FR-ATT-15 | M | Attendance evidence recorded **before** the disable stays authoritative — check-in, Late Present and no-show alike. Account disable never erases or reverses earlier evidence. | §32.5 |
| FR-ATT-16 | M | Protection applies only when **all** hold: the booking was ACTIVE · the account became DISABLED during the session · the disable materially prevented reliable attendance recording · no authoritative check-in existed beforehand · no authoritative no-show existed beforehand · the final effective outcome is **not** ATTENDED, ATTENDED_INTERRUPTED or ABSENT_NO_SHOW. Eligible outcomes are ATTENDANCE_UNCONFIRMED and NOT_APPLICABLE from operational early termination. | §32.6 |
| FR-ATT-17 | M | "Materially prevented reliable recording" is evaluated from the booking's `accountAccessRestrictionIntervals[]` against the check-in window and the no-show window: a disable interval overlapping the window in which the missing evidence would have been recorded is what makes recording unreliable. Genuinely ambiguous cases resolve through admin correction, never an inferred outcome. | §32.6 |
| FR-ATT-18 | M | Protection is **not** limited to sessions reaching normal `endAt`; it still applies when the session terminates early through a venue emergency, a resource emergency or owner review. The attendance result of an early termination remains governed by the normal interruption model — the protection event never changes it. | §32.7 |
| FR-ATT-19 | M | The trust event `BOOKING_ACCOUNT_DISABLE_ATTENDANCE_PROTECTION` resets the consecutive no-show counter to 0 and does nothing else: no points, no progress, no qualification, no change to attendance result or booking status. `effectiveAt = booking.startAt`; `occurredAt` = attendance-resolution time. | §32.8 |
| FR-ATT-20 | M | The event is created **when the effective attendance outcome resolves**, not at disable time. At disable time the system records only a protection candidate and audit context. | §32.9 |
| FR-ATT-21 | M | A booking may span several access intervals, stored as an ordered `accountAccessRestrictionIntervals[]` with `disabledAt`, nullable `reenabledAt`, admin actor or source, reason and correlation ID. All intervals are evaluated at resolution, and **at most one** effective protection event is emitted per booking. | §32.9 |
| FR-ATT-22 | M | If the booking ultimately produces ATTENDED or ATTENDED_INTERRUPTED, the protection event is **not** additionally applied — exactly one counter-reset mechanism per booking. | §32.12 |
| FR-ATT-23 | M | Later evidence governs: a valid check-in after re-enable within the window → normal ATTENDED · a Late Present after re-enable → ATTENDANCE_UNCONFIRMED with the protection reset still applicable · a valid no-show after re-enable within the window → authoritative. **An expired check-in window is never retroactively reopened.** | §32.10 |
| FR-ATT-24 | M | An admin correction to ABSENT_NO_SHOW **neutralises** the protection reset. No customer ever receives both a protected reset and a valid no-show increment for the same effective outcome. | §32.11 |
| FR-ATT-25 | M | When a disable overlaps an ACTIVE booking, the customer receives `ACTIVE_SESSION_ATTENDANCE_RECORDING_AFFECTED` (In-App + Push). The notification **must not** say the booking was cancelled unless another rule actually cancels it. | §32.13 |
| FR-ATT-26 | M | Platform Admin receives an operational alert with venue, Booking ID, session interval, disable time, current attendance evidence, and whether protection may be required. | §32.13 |

## 13.2 Abuse monitoring

| ID | Pri | Requirement | Spec |
|---|---|---|---|
| FR-ATT-27 | M | Platform Admin receives **non-blocking** operational signals: unusually high check-in rate · high check-in reversal rate · unusual concentration of Verified Player progress from one venue · repeated check-ins immediately before the 15-minute deadline · high Late Present rate · repeated Late Present on the same users · Late Present reversals · suspicious repeated customer/venue patterns. | §27.10 |
| FR-ATT-28 | M | Monitoring may flag, alert and surface for review. It must **never** automatically revoke Verified Player status, suspend a venue, reverse check-ins or ban users. Every consequence requires explicit admin action. | §27.10 |

**Acceptance criteria**

- Check-in at `startAt + 14:59` succeeds; at `+15:00` it is refused and Mark Present — Late becomes available
- A booking with Mark Present — Late cannot be marked no-show, and completes as ATTENDANCE_UNCONFIRMED with no points
- A no-show at 8:16 frees 8:16–9:00 for immediate resale; reversing it at 8:40 does not evict the new booking
- Two disable/re-enable cycles inside one active session produce **one** protection event and **one** counter reset
- A session where the account was disabled only outside both attendance windows does not qualify for protection
- An admin correcting a protected booking to ABSENT_NO_SHOW leaves the customer with a no-show increment and no reset
- The customer notification for an overlapping disable never contains the word "cancelled"

---

# 14. MANUAL BOOKING

**Rationale.** Manual booking is how the venue records the demand the marketplace has not captured — walk-ins, phone bookings, private arrangements. It must take the same inventory, so it cannot double-book; but it must **not** be constrained by customer-facing marketplace rules, because it is an internal operation.

| ID | Pri | Requirement | Spec |
|---|---|---|---|
| FR-MAN-01 | M | Manual booking is an **internal venue operation** (category C) with `source = MANUAL`. It does not pass through PENDING, reserves the resource immediately, and may have no registered customer. Optional customer name and phone may be captured. | §30.1 |
| FR-MAN-02 | M | Creation requires: venue account **ENABLED** · marketplace **ACTIVE, INACTIVE or SUSPENDED** — **ONBOARDING is explicitly not allowed** · resource base state ACTIVE · operational restriction NONE · no venue or resource selling block · resource supports the activity · no blocking reservation · no overlap with closure, emergency closure, resource emergency outage or maintenance · start NOW or FUTURE · duration 1–6 hours in 30-minute increments · required pricing configuration present. | §30.2 |
| FR-MAN-03 | M | Manual booking **does not require the interval to fit published opening hours**, and area marketplace status does not block it. | §30.3 |
| FR-MAN-04 | M | Reachable states: future → CONFIRMED → ACTIVE → COMPLETED · current walk-in with `startAt` = server time → ACTIVE → COMPLETED · before start → CONFIRMED → CANCELLED. Manual bookings never use PENDING, VENUE_RESPONSE_EXPIRED, AWAITING_CUSTOMER_CONFIRMATION, REJECTED, NO_SHOW or check-in. | §30.4 |
| FR-MAN-05 | M | **No back-dating.** A manual booking may never be created over a historical interval, even where the original occupant is now terminal. | §30.5 |
| FR-MAN-06 | M | Manual bookings contribute nothing to Verified Player progress, completed-booking points or no-show progression. Attendance is NOT_APPLICABLE, including when an ACTIVE manual booking is terminated operationally. | §30.6 |
| FR-MAN-07 | M | Manual bookings do not count against the customer's 4-commitment limit. | §30.7 |
| FR-MAN-08 | M | Venue staff may cancel a manual booking **without** the Owner Password, reason `VENUE_CANCELLED_MANUAL_BOOKING`. Its booking-fee treatment is **NOT_APPLICABLE**, because it carries no customer E7GZ fee. | §30.8 |
| FR-MAN-09 | M | A manual booking inside a published session stores the normal operating-session snapshot and `businessDate` = operating day date. Outside published hours, session fields are NULL, timezone is Africa/Cairo, and `businessDate` = the Africa/Cairo local calendar date of `startAt`. **Every manual booking always has a `businessDate`.** | §30.9 |
| FR-MAN-10 | M | Missing pricing configuration blocks creation with the API/validation error `MANUAL_BOOKING_PRICING_CONFIGURATION_MISSING`. No reservation and no booking record are created, so the token is neither a reason code nor a booking event. | §30.2 |
| FR-MAN-11 | M | There is **no additional product maximum future horizon** for manual booking beyond the date range the Dashboard supports. The product rule remains **NOW or FUTURE, never past**. A technical date-picker bound may be applied consistently as a UI matter without changing the product rule. | §30.10, §57.2 |

**Acceptance criteria**

- A manual booking at an ONBOARDING venue is refused; at a SUSPENDED venue it succeeds
- A manual booking overlapping a PENDING online booking is refused
- A manual booking at 3 AM for a venue closing at 2 AM succeeds and carries a `businessDate`
- A walk-in created at the current server time begins ACTIVE, not CONFIRMED
- A completed manual booking grants no points and no Verified Player progress

---

# 15. VENUE MODEL, CONFIGURATION AND DASHBOARD

**Rationale.** One shared venue account is a deliberate simplification: venues in this market do not have per-cashier logins and would not adopt them. The Owner Password is the substitute — it protects exactly the actions an owner would be angry to discover had happened without them, and nothing else, so it never becomes friction staff route around.

## 15.1 Account and Owner Password

| ID | Pri | Requirement | Spec |
|---|---|---|---|
| FR-VEN-01 | M | One shared venue account per venue. No individual cashier, manager or separate owner accounts. | §10 |
| FR-VEN-02 | M | The Owner Password is required **per protected action**. There is no 15-minute unlock, no 30-minute unlock and no owner session mode; each action revalidates. | §54.3 |
| FR-VEN-03 | M | Owner Password is **required** for: venue pricing changes · room price override set and reset · cancelling a CONFIRMED online booking · an opening-hours save that cancels protected customer bookings · a normal-closure save that cancels protected customer bookings · disable, re-enable, archive and restore resource · console change · protected resource configuration where defined · maintenance exceeding the rolling threshold · venue cancellation of a CONFIRMED tournament · tournament operational termination · resolving `OWNER_REVIEW_REQUIRED`. | §54.1 |
| FR-VEN-04 | M | Owner Password is **not required** for: accept · reject · reject after expiry · late confirm · manual booking creation and pre-start cancellation · check-in · Mark Present — Late · no-show · maintenance within the threshold · opening-hours or closure edits with no protected cancellation · emergency closure · resource emergency outage · venue cancellation of a WAITING_FOR_PLAYERS tournament · any system operational consequence cascade. All remain audited. | §54.2 |
| FR-VEN-05 | M | Successful and failed Owner Password attempts are audited; validation is rate-limited. A Platform Admin reset notifies the venue. | §54.3 |
| FR-VEN-46 | M | The venue may **change its own Owner Password**. The new password becomes active **immediately**, the old one stops working at once, and every subsequent protected action validates against the new one. The change is audited. This is distinct from a Platform Admin reset, which is covered by FR-ADM-03. | §10.2 |

## 15.2 Marketplace status and account status

| ID | Pri | Requirement | Spec |
|---|---|---|---|
| FR-VEN-06 | M | Marketplace status (ONBOARDING / ACTIVE / INACTIVE / SUSPENDED) and account status (ENABLED / DISABLED) are **independent**. Disabling the account does not rewrite marketplace status, and suspending the marketplace does not disable the account. | §11 |
| FR-VEN-07 | M | ONBOARDING: not discoverable, no online booking, no player tournament requests, configuration may continue, not operational marketplace inventory. **INACTIVE**: hidden from discovery, no new online booking or tournament requests, Dashboard available, manual booking available, existing bookings and tournaments remain — non-punitive. **SUSPENDED**: as INACTIVE plus no new marketplace tournament creation, reviews remain visible and new reviews remain allowed — administrative and punitive. | §11 |
| FR-VEN-08 | M | Account **DISABLED** blocks Dashboard login and therefore every ordinary Dashboard action, including accept, reject, late confirm, check-in, Mark Present — Late and no-show. It is an **access restriction, not a physical venue outage**; existing commitments remain governed by their own lifecycle. | §11, §32.1 |
| FR-VEN-09 | M | Effects of a disable by booking state: PENDING → VENUE_RESPONSE_EXPIRED immediately with event `VENUE_RESPONSE_INTERRUPTED_BY_ACCOUNT_DISABLE` · AWAITING_CUSTOMER_CONFIRMATION → CANCELLED `VENUE_ACCOUNT_DISABLED` · VENUE_RESPONSE_EXPIRED still disabled at `startAt` → CANCELLED `VENUE_ACCOUNT_DISABLED` · CONFIRMED unaffected and may still start · ACTIVE unaffected and continues. | §32.2 |
| FR-VEN-10 | M | `VENUE_ACCOUNT_DISABLED` applies only to an unresolved **pre-confirmation** response lifecycle. It is never a general CONFIRMED → ACTIVE cancellation reason. | §32.3 |
| FR-VEN-11 | M | Marketplace SUSPENDED or INACTIVE creates **no venue blame**: it never causes `VENUE_DID_NOT_RESPOND_IN_TIME` and needs no new expiry reason. | §32.3 |

## 15.3 Areas and resources

| ID | Pri | Requirement | Spec |
|---|---|---|---|
| FR-VEN-12 | M | **Areas** are controlled by Platform Admin and carry status **Active** or **Inactive**. Only Active areas are available for new venue assignment. Inactive areas remain in historical data and historical references are never rewritten. Area status governs **customer discovery only** and never blocks manual booking. | §12 |
| FR-VEN-47 | M | An area may be deactivated **only** when no venue assigned to it has marketplace status ACTIVE or SUSPENDED. Such venues must first move area or become INACTIVE. Historical references are unchanged. | §12, §51.11 |
| FR-VEN-13 | M | Resource Base State (ACTIVE / DISABLED / ARCHIVED) is separate from Resource Operational Restriction (NONE / OWNER_REVIEW_REQUIRED). A resource may be ACTIVE and still unsellable. | §13 |
| FR-VEN-14 | M | Disable, Archive and Console Change are **blocked** by any current or future blocking reservation — CONFIRMED, ACTIVE, PENDING, AWAITING, Player Tournament Approval Hold, Tournament Hold or resource Maintenance. Existing reservations are **never** automatically cancelled; the venue must resolve them first. A normal venue closure alone does not block these actions, and `VENUE_RESPONSE_EXPIRED` does not block them. | §13.4 |
| FR-VEN-15 | M | A console change (PS4 ↔ PS5) requires the Owner Password and **clears all existing room price overrides**; the room returns to venue default pricing for the new console. Historical bookings retain their stored console, mode and price. | §13.5 |
| FR-VEN-16 | M | Resource disable, re-enable, archive and restore are audited. Maintenance is not owner-protected within the threshold, and is audited. | §53.3 |
| FR-VEN-41 | M | Resource naming is fixed: PlayStation resources are **Room 1, Room 2, …** · Billiards resources are **Pool Table 1, Pool Table 2, …** · Ping Pong resources are **Table 1, Table 2, …**. Supported PlayStation console types are **PS5 and PS4**, and only these. The console type is shown **wherever resource information appears**, across all three products. | §3.2 |
| FR-VEN-42 | M | PlayStation requires a play mode of **Single** or **Multiplayer**. This is a **pricing mode, not an exact player count**, and the system must never validate, capture or enforce a party size against it. Play mode does **not** apply to Billiards or Ping Pong. | §3.3 |
| FR-VEN-43 | M | **PlayStation pricing** is configured as **venue-level defaults per console type × play mode** — PS5 Single, PS5 Multiplayer, PS4 Single, PS4 Multiplayer. A PlayStation room may carry a **Room Price Override** that replaces the venue default for that room; **with no override the room inherits the venue console default**. | §4.1 |
| FR-VEN-44 | M | **Billiards and Ping Pong** are priced at a **venue-level hourly rate only** — no console type, no play mode, and **no table-level override** in this release. | §4.2 |
| FR-VEN-45 | M | Manual booking, online booking and tournament all price from this same configuration, and creation is **blocked** where the configuration required for the activity does not exist. | §4.1, §4.2, §30.2 |

## 15.4 Opening hours and operating sessions

| ID | Pri | Requirement | Spec |
|---|---|---|---|
| FR-VEN-17 | M | Opening hours use a default schedule plus weekday overrides; each weekday may use the default, use custom hours or be closed. Cross-midnight schedules are supported. | §14 |
| FR-VEN-18 | M | Each **operating day** creates at most **one operating session**, identified by operating day, local start, local end and the Africa/Cairo timezone. Session length is greater than 0 and no more than 24 hours. Sessions may touch at boundaries but **may not overlap**. | §14 |
| FR-VEN-19 | M | Non-overlap validation is **cyclical across the full week**, including the Sunday → Monday wrap. A cross-midnight session may not overlap the next session. | §14 |
| FR-VEN-20 | M | A closed weekday prevents a **new** session starting that day, but an overnight session from the previous day legitimately continues into it. | §14 |
| FR-VEN-21 | M | DST: non-existent local start times are not offered; ambiguous repeated local times are not offered as duplicate slots; a declared full-day session stays valid when real elapsed time is 23 or 25 hours; absolute instants remain authoritative for inventory. | §14.4, §14.3 |
| FR-VEN-22 | M | Booking history stores the operating day, local session start, local session end and timezone as a **historical snapshot**; later opening-hours changes never rewrite it. | §14 |
| FR-VEN-23 | M | An opening-hours change is **all-or-nothing** and evaluates in this order: **1.** validate proposed sessions · **2.** check tournament conflicts · **3.** block immediately on any tournament conflict · **4.** compute affected ordinary reservations · **5.** show a complete impact preview · **6.** determine the Owner Password requirement · **7.** request the Owner Password where protected cancellations exist · **8.** commit the change and all cancellations atomically · **9.** notify. If any step fails, **nothing changes**. | §14.7 |
| FR-VEN-24 | M | Affected reservations are future PENDING, AWAITING_CUSTOMER_CONFIRMATION, CONFIRMED and MANUAL. ACTIVE sessions are not cancelled. VENUE_RESPONSE_EXPIRED holds no reservation and is not cancelled. | §14.8 |
| FR-VEN-25 | M | Editing opening hours needs no Owner Password, but a save that cancels a CONFIRMED online booking or an AWAITING booking requires it. Pending online and manual cancellations alone do not. Cancellation reason: `VENUE_OPENING_HOURS_CHANGED`. | §14.9, §14.8 |
| FR-VEN-26 | M | Before commit the complete affected set is **revalidated**. If new affected reservations appeared since the preview, the commit is **aborted** and a refreshed preview is shown; any Owner Password must be entered against the refreshed set. **No booking outside the approved preview may be silently cancelled.** | §14.7 |
| FR-VEN-27 | M | A tournament conflict **blocks** the save, including a PENDING_VENUE_APPROVAL tournament holding approval holds. The venue must resolve or cancel the tournament first. | §14.10 |
| FR-VEN-28 | M | An AWAITING booking cancelled by an hours change stops its Late Confirmation timer immediately, releases the hold, and notifies the customer explicitly. | §14.9 |

## 15.5 Closures

| ID | Pri | Requirement | Spec |
|---|---|---|---|
| FR-VEN-29 | M | A closure may be full-day or partial-time, applies to the whole venue, and blocks all resources for its interval. | §15 |
| FR-VEN-30 | M | Creating or editing a closure needs no Owner Password unless it cancels protected customer reservations, in which case the final **Save & Cancel Affected Bookings** requires it. Cancellation reason `VENUE_CLOSURE`; customers are notified. | §15 |
| FR-VEN-31 | M | A closure may **not** be created retroactively over an ACTIVE session, and must begin after existing active sessions end unless those have been resolved through an allowed flow. | §15 |
| FR-VEN-32 | M | A proposed closure conflicting with an existing tournament **blocks the save**. | §15 |
| FR-VEN-33 | M | A closure may overlap existing maintenance; creating maintenance over an existing closure is blocked for the overlapping period. | §39.4 |

## 15.6 Venue Dashboard

| ID | Pri | Requirement | Spec |
|---|---|---|---|
| FR-VEN-34 | M | Dashboard modules: Overview · Pending Requests · Missed / Expired Requests · **Accept / Reject** · **Late Confirmation** · Bookings · Manual Booking · Rooms / Resource Management · Pricing · Availability · Maintenance & Closures · **Opening Hours** · Reviews (view only) · Tournaments · Booking History · **Revenue Summary** · Settings, in English and العربية. §50.1 names the core module set; §2.2 names the full launch surface set and both are frozen. | §2.2, §50.1 |
| FR-VEN-35 | M | Overview shows today's revenue, this month's revenue, today's bookings, pending requests, today's schedule, quick manual booking and quick create tournament. | §50.2 |
| FR-VEN-36 | M | Revenue covers online, manual and tournament revenue with an optional activity breakdown, aggregated on **`businessDate`**. The Dashboard is explicitly not a POS, accounting system, ERP, tax system or reconciliation system, and tracks no payment method. | §50.3 |
| FR-VEN-37 | M | The availability schedule displays Free · Pending · Awaiting Customer Confirmation · Confirmed · Active · Manual · Tournament Hold · Maintenance · Closure. | §50.4 |
| FR-VEN-38 | M | Booking history filters: date range (today, yesterday, last 7 days, this month, custom) · activity · source · resource · status · search. | §50.5 |
| FR-VEN-39 | M | The venue is **view only** on reviews, seeing average rating, total reviews, rating distribution, and per review the username, rating, comment and date. | §49.7 |
| FR-VEN-40 | M | Full English (LTR) and العربية (RTL) support across every listed surface element. Free-text stored data is unchanged. Language resolves: venue account preference → browser storage → English default. | §50.6 |

**Acceptance criteria**

- Two protected actions in a row each demand the Owner Password separately
- Disabling a resource with a future confirmed booking is refused and names the blocking reservation
- A console change clears the room override and leaves historical bookings untouched
- An hours change conflicting with a PENDING_VENUE_APPROVAL tournament is blocked before any preview of booking impact
- A new confirmed booking created between preview and commit aborts the commit and forces a refreshed preview
- A closure overlapping an active session is refused
- The Dashboard renders correctly in Arabic RTL across all modules, with stored free text unchanged

---

# 16. MAINTENANCE, EMERGENCIES AND OPERATIONAL RESTRICTIONS

**Rationale.** Emergencies are the one place the product allows immediate, irreversible cancellation — because a venue that has lost power cannot honour anything. The trade is that the system will never quietly return broken inventory to sale, and the ladder from emergency to owner review exists so repeated outages cannot be used to dodge the owner.

## 16.1 Maintenance

| ID | Pri | Requirement | Spec |
|---|---|---|---|
| FR-OPS-01 | M | Maintenance is normal venue operation at resource level. Create, edit and delete are audited. | §39.1 |
| FR-OPS-02 | M | Maintenance may **never** overlap an existing blocking reservation; creation is blocked with a clear message and the reservations must be resolved through their normal flow. **Maintenance may never silently cancel a booking.** | §39.1 |
| FR-OPS-03 | M | Owner Password is required when total maintenance for a resource exceeds **24 hours inside any rolling 48-hour window**. Back-to-back or overlapping windows forming one continuous period over 24 hours count as one period. The threshold is **per resource**. | §39.2 |
| FR-OPS-04 | M | An edit that extends a continuous period past the threshold requires the Owner Password. | §39.2 |
| FR-OPS-05 | M | Maintenance created from a resource emergency outage **counts** toward the same rolling total. There is no bypass between emergency-generated and manual maintenance. | §39.3 |

## 16.2 Venue Emergency Closure

| ID | Pri | Requirement | Spec |
|---|---|---|---|
| FR-OPS-06 | M | Emergency Closure requires **no Owner Password**, may start only **NOW**, cannot be scheduled, requires a mandatory reason and full audit, and notifies the venue owner and Platform Admin. | §40.1 |
| FR-OPS-07 | M | Expected End Time is **mandatory**, later than start and no later than the end of the current operating session. Longer outages must use a normal closure, an opening-hours change or another future mechanism. | §40.2 |
| FR-OPS-08 | M | The cascade covers only reservations overlapping `[emergencyStart, expectedEnd)`. It stops new online and manual bookings for the period, cancels affected PENDING, AWAITING and CONFIRMED bookings, terminates affected ACTIVE sessions, cancels affected tournaments and releases future holds, under `VENUE_EMERGENCY_CLOSURE`. | §40.3, §40.4 |
| FR-OPS-09 | M | The cascade is **all-or-nothing**: the closure, cancellations, active terminations, tournament cancellations, releases and history persist atomically **before** notifications dispatch. A partial cascade is not an acceptable outcome. | §40.5 |
| FR-OPS-10 | M | Emergency cancellation is immediate and irreversible; ending the emergency early never restores what was cancelled. | §40.6 |
| FR-OPS-44 | M | An AWAITING_CUSTOMER_CONFIRMATION booking caught by an emergency cascade is cancelled **immediately under the emergency reason**. Its Late Confirmation timer stops and the customer **never** receives `CUSTOMER_RECONFIRM_TIMEOUT`: the emergency reason wins. | §40.4 |

## 16.3 Resource Emergency Outage

| ID | Pri | Requirement | Spec |
|---|---|---|---|
| FR-OPS-11 | M | A resource emergency outage requires **no Owner Password** and carries a mandatory reason, timestamp, affected resource, venue account identity, full audit, owner notification and Platform Admin visibility. Staff must supply an **expected return-to-service time**. For an **unprotected** emergency outage this may extend only to the end of the current operating session; a longer resource outage must be carried as **maintenance under normal maintenance authorization**, including the Owner Password where the rolling threshold is exceeded. | §41.2, §41.4, §39.2 |
| FR-OPS-12 | M | It may terminate the ACTIVE booking on that resource, cancel future overlapping reservations, affect tournament use, block new availability and transition the resource into maintenance — under `RESOURCE_EMERGENCY_OUTAGE`. Other venue resources keep operating. | §41.3 |
| FR-OPS-13 | M | There is **no hard venue-wide emergency rate limit**, because such a limit could block a genuine emergency. | §41.2 |
| FR-OPS-14 | M | Platform Admin receives a risk alert when one venue causes **3 or more tournament cancellations through resource emergency outage within a rolling 30 days**. The alert is investigative and non-blocking; it never suspends the venue, requires no Owner Password for future emergencies, and reverses nothing. | §41.6 |

## 16.4 Operational Selling Block and the emergency lifecycle

| ID | Pri | Requirement | Spec |
|---|---|---|---|
| FR-OPS-15 | M | A relevant emergency **must** create the corresponding operational selling block — venue emergency → venue block; resource emergency → that resource's block only. It is mandatory, not optional. | §38.2, §38.3 |
| FR-OPS-16 | M | A selling block prevents new online bookings, new manual bookings, new tournament holds and approval holds, Late Confirm requiring the affected venue or resource, venue accept, and CONFIRMED → ACTIVE — for the affected venue or resource. | §38.4 |
| FR-OPS-17 | M | Reservations **outside** the declared emergency interval are not automatically cancelled when the emergency enters AWAITING_CONFIRMATION. A block may persist across a session end and into the next session without extending the earlier cascade. | §38.5 |
| FR-OPS-18 | M | Emergency status is ACTIVE → (expected end passes) → AWAITING_CONFIRMATION → ENDED or CONVERTED. Blocks are ON for ACTIVE and AWAITING_CONFIRMATION. **Expected end never auto-reopens anything**; staff resolution is mandatory. | §42.1, §42.2 |
| FR-OPS-19 | M | On ENDED the emergency selling block **must** end. Staff may only end an emergency when the venue or resource is operational again, or when a valid replacement operational state or block commits atomically. | §42.3 |
| FR-OPS-20 | M | If a resource is still unusable but below the owner-review threshold, the emergency **must not** move to ENDED with no replacement block; it stays AWAITING_CONFIRMATION until repair, or a successful maintenance conversion, disable or archive. | §42.3 |
| FR-OPS-21 | M | **No sellable gap**: there must never be a period where the emergency block has ended, the resource is still unusable, and no replacement restriction exists. The enforceable guarantee is that E7GZ will not release the block unless staff assert the resource is operational or a replacement commits. END EMERGENCY is that audited, attributable staff assertion. | §38.6 |
| FR-OPS-22 | M | Extending an emergency computes the newly affected interval and reservations, previews the impact, takes confirmation, and applies the new cancellations atomically. Previously cancelled reservations stay cancelled. Emergency mode may not be extended beyond the current operating session as an unprotected cancellation mechanism. | §42.4 |
| FR-OPS-23 | M | Conversion targets: venue emergency → normal closure or opening-hours change; resource emergency → maintenance, disabled or archived. A **resource emergency may not convert directly into an opening-hours change**. Each conversion obeys the target action's own authorization and impact rules and never bypasses the resource state-change predicate. | §42.5 |
| FR-OPS-24 | M | On CONVERTED, the emergency block ends **at the same atomic commit** that activates the replacement. No availability gap is allowed. | §42.5 |
| FR-OPS-25 | M | The Dashboard and owner must be notified when an expected end is reached with the emergency still active, when a return-to-service time is reached with the outage still active, and when manual confirmation is required — so operational blocks are not forgotten. | §42.7 |
| FR-OPS-42 | M | An active emergency closure or resource emergency outage blocks **availability** but does **not** by itself forbid Disable, Archive or Console Change, provided the Owner Password requirement passes, all remaining future reservations are resolved, and applicable maintenance restrictions pass. Where a resource emergency has created maintenance, that **maintenance** becomes the continuing resource-level block and normal state-change rules evaluate the maintenance interval. | §13.6, §42.8 |
| FR-OPS-43 | M | Where a resource cannot return to service but still holds **future protected reservations**, the operational selling block **remains** and the venue must resolve those commitments through the normal flows before Disable, Archive or extended Maintenance becomes available. The Dashboard must not present an unavailable conversion as executable: it displays the blocking commitments and states that they must be resolved first. | §42.6, §31.12 |

## 16.5 OWNER_REVIEW_REQUIRED

| ID | Pri | Requirement | Spec |
|---|---|---|---|
| FR-OPS-26 | M | When a resource has exceeded 24 maintenance hours in a rolling 48-hour window, it enters `OWNER_REVIEW_REQUIRED` at the emergency's expected or actual end. A genuine new emergency may always still be declared immediately without the Owner Password. | §43.1 |
| FR-OPS-27 | M | The threshold is evaluated **before** ending an emergency. Ending an emergency can never return an over-threshold resource to sellable state: **owner review takes precedence over availability**. | §43.2 |
| FR-OPS-28 | M | Entry triggers a **system operational cascade** — not a manual venue cancellation — resolving atomically: PENDING, AWAITING, future CONFIRMED, ACTIVE and future manual bookings → CANCELLED `RESOURCE_OWNER_REVIEW_REQUIRED`; pre-start tournaments → CANCELLED with the same reason; an ACTIVE tournament loses that resource and is cancelled only if none remains. | §43.3 |
| FR-OPS-29 | M | The cascade requires **no Owner Password anywhere**, including the manual-booking and tournament cancellations, requires system audit, and notifies the venue owner, Platform Admin, affected customers and participants. **A partial cascade is not a valid outcome.** | §43.3, §43.4 |
| FR-OPS-30 | M | Owner review is **not a pause state**: affected bookings are cancelled, not held, so customers are never left in indefinite commitments. A PENDING booking cancelled this way stops its timer immediately and never becomes VENUE_RESPONSE_EXPIRED or uses `VENUE_DID_NOT_RESPOND_IN_TIME`. | §43.5 |
| FR-OPS-31 | M | An affected customer receives no penalty, an explicit operational reason, the ability to rebook normally, and a full future fee refund. No automatic replacement booking is created. Attendance is NOT_APPLICABLE with no trust effect. | §43.5 |
| FR-OPS-32 | M | While the restriction holds, the venue cannot initiate Late Confirm on that resource; the system records `LATE_CONFIRM_ELIGIBILITY_LOST_RESOURCE_STATE_CHANGED` with `detailCode = RESOURCE_OWNER_REVIEW_REQUIRED`. Clearing the restriction before `startAt` restores eligibility, but never triggers an automatic Late Confirm. | §43.6 |
| FR-OPS-33 | M | Resolution requires the Owner Password and offers **Return to Service**, **Extend Maintenance**, **Disable Resource** or **Archive Resource**. Return to Service requires no blocking maintenance, no emergency selling block and an otherwise operational resource; it then sets `operationalRestriction = NONE`. | §43.8 |

**Acceptance criteria**

- Splitting a 30-hour maintenance into two windows with a one-minute gap still demands the Owner Password
- A venue emergency cascade interrupted by an injected fault leaves **no** partial state — either all cancellations and the closure exist, or none do
- A resource emergency does not block bookings on the venue's other resources
- END EMERGENCY on an over-threshold resource leaves it unsellable under owner review
- The owner-review cascade cancels an ACTIVE booking automatically with no admin action
- No test scenario produces a window where the block is off and no replacement restriction exists

---

## 16.6 Timer pauses during emergencies

**Rationale.** A venue that has lost power cannot answer a booking request, and a customer should not lose a booking to a clock the venue was physically unable to beat. The pause is bounded at thirty minutes because an indefinite pause is just an indefinite commitment.

| ID | Pri | Requirement | Spec |
|---|---|---|---|
| FR-OPS-34 | M | The **venue response timer** on a PENDING booking pauses while a venue emergency closure, **or** a resource emergency outage affecting that booking's resource, is in force. A resource emergency on any other resource pauses nothing. While a relevant block is active, Venue Accept is disabled. | §44.1 |
| FR-OPS-35 | M | The **customer Late Confirmation countdown** pauses while an active emergency or selling block affects the venue **or** the booked resource, and the booking has not already been cancelled by the emergency cascade. | §44.1 |
| FR-OPS-36 | M | Remaining duration is **preserved** across a pause: a 10-minute timer with 3 minutes elapsed resumes with 7 minutes remaining. | §44.1 |
| FR-OPS-37 | M | Two **separate** cumulative caps of **30 minutes** each: venue-response emergency pause per booking, and Late Confirmation emergency pause per AWAITING phase. Venue and resource emergency pauses contribute to the **same** response-pause counter, so switching emergency type can never reset the clock. Repeated pauses accumulate and never reset. Stored per booking: `responsePauseStartedAt`, `cumulativeEmergencyPauseDuration`, `lateConfirmPauseStartedAt`, `cumulativeLateConfirmEmergencyPauseDuration`. | §44.2 |
| FR-OPS-38 | M | On reaching a cap: venue response under a venue-level emergency → PENDING CANCELLED `VENUE_RESPONSE_INTERRUPTED_BY_EMERGENCY` · venue response under a resource-level emergency → PENDING CANCELLED `RESOURCE_RESPONSE_INTERRUPTED_BY_EMERGENCY` · Late Confirmation → AWAITING CANCELLED `LATE_CONFIRM_INTERRUPTED_BY_EMERGENCY`. In every case the reservation or temporary hold releases, the customer commitment slot releases, the customer is notified, no customer penalty applies and **no venue non-response blame is recorded**. | §44.3 |
| FR-OPS-39 | M | A paused venue response timer resumes only when **all** hold: the emergency has ended · venue account ENABLED · venue marketplace permits the action · the venue is inside a valid operating session · the current time is inside the online intake window. Ending an emergency while the venue is closed does not restart the timer; it waits for the **next** valid intake window. A paused Late Confirmation countdown resumes only when the affecting emergency or block is resolved, the booking is still before `startAt`, and venue, resource and customer all remain eligible. | §44.4 |
| FR-OPS-40 | M | Early cancellation is deterministic through the **optimistic feasibility test**: assuming the current selling block could resolve immediately, could the remaining venue-response duration fit inside a valid future online intake window before `startAt`? The system evaluates the remaining response duration, future valid operating sessions, the final-10-minute intake cutoff, `startAt` and the active selling block. **No such window even under immediate resolution** → cancel now under the applicable interrupted-by-emergency reason. **A window could exist but the emergency is unresolved** → the booking stays paused only until the 30-minute cap, which is the deterministic outer bound. Block duration is never predicted by the scheduler. | §44.5 |
| FR-OPS-41 | M | The customer is notified when a Late Confirmation countdown is paused, and sees *"Confirmation temporarily paused due to venue/resource issue."* On resume the remaining time is shown. **While paused the customer may still Decline**, which takes effect immediately under `CUSTOMER_DECLINED_LATE_CONFIRM`. Both pause records are immutable operational history storing `pauseStartedAt`, `pauseEndedAt`, `pauseCause` and `accumulatedDuration`. | §44.6, §44.7, §29.3 |

**Acceptance criteria**

- A booking paused by a venue emergency for 12 minutes, resumed, then paused by a resource emergency for 19 minutes, cancels at the 30-minute cumulative mark under the resource reason — the counter does not reset when the emergency type changes
- A PENDING booking whose remaining response time cannot fit any future intake window before `startAt` is cancelled immediately, not held to the cap
- A customer declines a Late Confirmation while its countdown is paused and the booking becomes CANCELLED `CUSTOMER_DECLINED_LATE_CONFIRM` at once
- Every one of the three interrupted-by-emergency reason codes is reachable in a seeded end-to-end run

---

# 17. TOURNAMENTS

**Rationale.** A tournament holds up to four resources for hours, which is the largest single commitment a venue makes through E7GZ. That is why a player cannot impose one without venue consent, why creation closes two hours out, and why a mandatory checkpoint an hour before the start either confirms the event or gives the venue its rooms back.

| ID | Pri | Requirement | Spec |
|---|---|---|---|
| FR-TRN-01 | M | Tournaments are Day-1 across PlayStation, Billiards and Ping Pong, and are a **separate entity from a booking**. Booking history and tournament history are separate; a tournament is never modelled as a booking. | §36.1 |
| FR-TRN-02 | M | Player-hosted tournaments require an operational **VERIFIED** creator and **venue approval**. Venue-hosted tournaments require neither. | §36.2 |
| FR-TRN-03 | M | Parameters: 8–32 participants · 1–4 resources · minimum 1 hour in 30-minute increments · no global maximum below the operating-session limit · creation at `requestTime <= startAt − 2 hours` · checkpoint at exactly `startAt − 1 hour` · creation fee EGP 0. Capacity and resource count are independent; E7GZ manages no brackets, per-match scheduling or per-match resource assignment. | §36.3 |
| FR-TRN-04 | M | A PlayStation tournament must select Single or Multiplayer, which drives resource pricing. The interval must fit **one** valid operating session, so a tournament may exceed the 6-hour online booking maximum. | §36.3 |
| FR-TRN-05 | M | The player creator chooses **Organizer Only** or **Organizer + Participant**; if participating, they count in the total. | §36.4 |
| FR-TRN-06 | M | Public tournaments appear in Discover and any account-verified user may join. Private tournaments are hidden and require an invite code or link; the Dashboard supports copy code and copy/share link. | §36.5 |
| FR-TRN-07 | M | While PENDING_VENUE_APPROVAL a tournament is not in public Discover, its invite code and link are inactive, and no one may join. Only the creator and the venue may view it. Discovery rules activate on venue accept. | §36.5 |
| FR-TRN-08 | M | **Existing reservations always win.** Tournament creation cannot override any existing booking, tournament, maintenance or closure, and **a tournament may never automatically cancel an existing booking**; creation fails for conflicting resources. | §36.7 |
| FR-TRN-33 | M | At creation, **every** assigned resource requires base state **ACTIVE**, operational restriction **NONE**, no operational selling block at venue or resource level, no conflicting reservation or hold, and activity compatibility. | §36.6 |
| FR-TRN-09 | M | Created tournaments hold their resources. During PENDING_VENUE_APPROVAL these are temporary **Approval Holds** that block other reservations and release on reject, expiry or cancellation; on accept they become standard Tournament Holds. | §36.8 |
| FR-TRN-10 | M | Player-hosted approval window: `MIN(requestCreatedAt + 10 minutes, checkpointAt)`. Accept → WAITING_FOR_PLAYERS · Reject → REJECTED `VENUE_REJECTED_TOURNAMENT` · No response → CANCELLED `VENUE_DID_NOT_APPROVE_TOURNAMENT_IN_TIME`. Holds release on both failure paths. | §37.2 |
| FR-TRN-11 | M | A creator may hold only **one** player-hosted tournament in PENDING_VENUE_APPROVAL platform-wide, and must wait **5 minutes** after a rejection or approval expiry before re-requesting the same venue and time interval. | §37.3 |
| FR-TRN-12 | M | Users may join while WAITING_FOR_PLAYERS or CONFIRMED and `now < startAt`, with capacity remaining, not banned, and with valid invite access if private. Participants may leave, and organizers may remove, until `startAt`. Falling below 8 before the checkpoint does **not** cancel early. | §37.4 |
| FR-TRN-13 | M | An **Active Participant** is JOINED, not BANNED, not DELETED, and has neither left nor been removed. Participant states are JOINED / LEFT / REMOVED / INELIGIBLE, and historical participation is always retained. | §37.4 |
| FR-TRN-14 | M | At `startAt − 1 hour`: ≥ 8 active participants → CONFIRMED · < 8 → CANCELLED `MINIMUM_PARTICIPANTS_NOT_REACHED` with resources released. After CONFIRMED a later drop below 8 does **not** auto-cancel. There is **no "Run Anyway" override**. | §37.5 |
| FR-TRN-15 | M | The checkpoint revalidates every assigned resource — base state ACTIVE, restriction NONE, no selling block, hold still valid, venue eligible, tournament not otherwise cancelled. | §37.5 |
| FR-TRN-16 | M | At the checkpoint "venue eligible" means **operationally able to honour the tournament**, not eligible for new marketplace activity. Marketplace SUSPENDED or INACTIVE therefore never fails a checkpoint by itself; only a venue selling block, a venue emergency affecting the tournament, or an applicable closure does. A tournament **never** remains indefinitely in WAITING_FOR_PLAYERS after a failed mandatory checkpoint. | §37.5 |
| FR-TRN-17 | M | CONFIRMED → ACTIVE at start, ACTIVE → COMPLETED at end. Before CONFIRMED → ACTIVE every resource is revalidated; any resource under owner review cancels the tournament **before gameplay begins**. | §37.6 |
| FR-TRN-18 | M | A venue account disable during PENDING_VENUE_APPROVAL resolves the tournament **immediately** to CANCELLED `VENUE_ACCOUNT_DISABLED`: the timer stops with no pause or resumption, holds release, the creator's platform-wide pending slot releases, the venue is **not** recorded as failing to approve, the **5-minute cooldown does not apply**, and the creator notification must not use venue non-response blame. A later re-enable never restores it. | §37.7 |
| FR-TRN-19 | M | A venue account disable alone does **not** cancel WAITING_FOR_PLAYERS, CONFIRMED or ACTIVE tournaments while the venue and resources can still deliver. | §37.7 |
| FR-TRN-20 | M | Verified Player cascade: **REVOKED** or qualification lost through trust correction cancels PENDING_VENUE_APPROVAL and WAITING_FOR_PLAYERS under `CREATOR_VERIFIED_STATUS_REVOKED`, while CONFIRMED and ACTIVE remain. **SUSPENDED** cancels nothing existing but blocks new creation. Restoration never resurrects a cancelled tournament. The cascade executes **once per `verifiedPlayerTransitionId`**. | §37.8 |
| FR-TRN-21 | M | Cancellation authority: venue may cancel before ACTIVE (player-hosted, after its own approval) · creator may cancel PENDING_VENUE_APPROVAL and WAITING_FOR_PLAYERS before the checkpoint · Platform Admin may cancel any non-completed tournament. **After CONFIRMED the creator may no longer cancel through E7GZ** and must contact the venue or support. | §37.9 |
| FR-TRN-22 | M | An ACTIVE tournament may end early only through venue emergency closure, an applicable resource emergency outage, **Tournament Operational Termination**, or Platform Admin correction. There is no unrestricted "venue operational termination" power. | §37.9 |
| FR-TRN-23 | M | Tournament Operational Termination covers a tournament-specific incident not requiring venue closure, requires the **Owner Password**, a mandatory reason, audit and participant and organizer notification, and yields ACTIVE → CANCELLED `TOURNAMENT_OPERATIONAL_TERMINATION`. | §37.9 |
| FR-TRN-24 | M | Owner Password matrix: cancelling WAITING_FOR_PLAYERS **no** · cancelling CONFIRMED **yes** · operational termination **yes** · any emergency or owner-review consequence **no**, because those are system consequences rather than the venue's cancel action. | §37.10 |
| FR-TRN-25 | M | Every cancellation releases holds, updates history, notifies creator and participants, and stores the cancelling actor and reason. No resource booking is silently created or removed outside that transaction. | §37.9 |
| FR-TRN-26 | M | Participant ban and deletion handling: banned or deleted before the checkpoint removes them from the active count and may cause checkpoint cancellation; after the checkpoint they become ineligible but the tournament is not cancelled for falling below 8. A ban expiring while the tournament is non-terminal and `now < startAt` automatically returns a participant who was ineligible **only** because of that ban. A ban expiring after the start does not restore them, and never resurrects a tournament already cancelled at the checkpoint. | §37.11 |
| FR-TRN-27 | M | Resource loss during an ACTIVE tournament: at least one operational resource remaining → the tournament continues, the failed resources are removed, and creator and participants receive **Tournament Resource Availability Changed** · none remaining → CANCELLED under the applicable resource reason. Before the start the rule is stricter: **any** assigned resource lost to a resource emergency or owner review cancels the entire tournament, including one still in PENDING_VENUE_APPROVAL. | §37.12 |
| FR-TRN-28 | M | Tournament venue cost = `historical resource hourly price × booked nominal duration`, summed over resources, with the same half-up 2-decimal rounding. Snapshot is taken at **request creation** for player-hosted and at **creation** for venue-hosted. Later price changes never alter a stored cost, and **a resource loss never changes it**. | §36.9, §37.12 |
| FR-TRN-29 | M | Tournament venue cost, entry fee and prize are **informational only**. E7GZ collects nothing, holds nothing and distributes nothing, and never imposes a penalty, debt, wallet deduction or financial obligation on a creator who cannot continue. | §36.9, §36.11 |
| FR-TRN-30 | M | A COMPLETED tournament contributes its stored cost to Dashboard tournament revenue on its `businessDate`. Cancelled and rejected tournaments contribute nothing. | §36.10 |
| FR-TRN-31 | M | Exactly one terminal tournament reason is stored, by priority: **1.** `RESOURCE_OWNER_REVIEW_REQUIRED` · **2.** `RESOURCE_EMERGENCY_OUTAGE` · **3.** `EMERGENCY_CLOSURE` · **4.** `VENUE_ACCOUNT_DISABLED` · **5.** `MINIMUM_PARTICIPANTS_NOT_REACHED` · **6.** `CREATOR_VERIFIED_STATUS_REVOKED` · **7.** explicit actor cancellation. | §37.14 |
| FR-TRN-32 | M | The closed **Tournament Reason Code** enum is `CREATOR_CANCELLED` · `VENUE_CANCELLED` · `ADMIN_CANCELLED` · `VENUE_REJECTED_TOURNAMENT` · `VENUE_DID_NOT_APPROVE_TOURNAMENT_IN_TIME` · `MINIMUM_PARTICIPANTS_NOT_REACHED` · `CREATOR_VERIFIED_STATUS_REVOKED` · `TOURNAMENT_OPERATIONAL_TERMINATION` · `EMERGENCY_CLOSURE` · `RESOURCE_EMERGENCY_OUTAGE` · `RESOURCE_OWNER_REVIEW_REQUIRED` · `VENUE_ACCOUNT_DISABLED`. Every terminal tournament carries exactly one, and a new member requires Product Change Control. | §37.13, §55.7, §57.3 |

**Acceptance criteria**

- Creation at `startAt − 1h59m` is refused; at exactly `startAt − 2h` it succeeds
- A player request against a resource already holding a confirmed booking fails, and the booking is untouched
- A creator with one pending request cannot open a second anywhere on the platform
- A tournament at 7 participants at the checkpoint cancels and releases resources within the same transaction
- A tournament at a venue suspended after creation still passes its checkpoint
- Disabling the venue account during PENDING_VENUE_APPROVAL frees the creator's slot immediately with **no** cooldown
- An active tournament losing 3 of 4 resources continues and notifies; losing the 4th cancels it
- A tournament losing a resource keeps its original stored cost

---

# 18. TRUST, PENALTIES AND BANS

**Rationale.** Penalties must be defensible. That is only possible if the state that produces them can be rebuilt from evidence — which is why counters are derived, why corrections are events rather than edits, and why a retroactively discovered ban starts today rather than being back-dated onto a past the customer already lived through.

## 18.1 The engine

| ID | Pri | Requirement | Spec |
|---|---|---|---|
| FR-TRS-01 | M | Trust is calculated from **immutable trust input events**. The no-show counter, penalty stage, ban state, Verified Player progress and Verified Player status are **derived** and never authoritative. No counter may be inferred from a stored derived value. | §45.1 |
| FR-TRS-02 | M | The canonical catalogue is: **Booking outcomes** `BOOKING_ATTENDED`, `BOOKING_ATTENDANCE_UNCONFIRMED`, `BOOKING_ATTENDED_INTERRUPTED`, `BOOKING_NO_SHOW` · **Corrections** `NO_SHOW_REVERSED`, `ATTENDANCE_CORRECTED` · **Administration** `ADMIN_TRUST_ADJUSTMENT`, `VERIFIED_PLAYER_SUSPENDED`, `VERIFIED_PLAYER_REVOKED`, `VERIFIED_PLAYER_RESTORED` · **System protection** `BOOKING_ACCOUNT_DISABLE_ATTENDANCE_PROTECTION`. | §45.2 |
| FR-TRS-03 | M | Operational evidence — `CHECK_IN_RECORDED`, a pre-completion `CHECK_IN_REVERSED`, `LATE_PRESENT_RECORDED`, `LATE_PRESENT_REVERSED` — stays in booking and audit history and enters trust **only** through a resulting canonical outcome or correction. Pressing check-in is not itself a reward. | §45.2 |
| FR-TRS-04 | M | Every event carries `eventId`, `eventType`, `occurredAt`, `effectiveAt`, `eventSequence`, `subjectUserId`, optional `bookingId`, optional `targetEventId` and `eventPayload`. | §45.3 |
| FR-TRS-05 | M | Effective-time rules are closed: any booking-derived event and the protection event use `booking.startAt` · any correction of a booking event uses the **same** `effectiveAt` as the corrected event · admin trust adjustment and the three Verified Player admin events use `occurredAt`. | §45.4 |
| FR-TRS-06 | M | Replay orders **exclusively** by `effectiveAt` ASC then `eventSequence` ASC. There is no competing ordering. | §45.5 |
| FR-TRS-07 | M | Corrections never delete. Replay **neutralises** the targeted effect and, where appropriate, **substitutes** the corrected effect. `ATTENDANCE_CORRECTED` carries a mandatory `correctedOutcome` of ATTENDED, ATTENDED_INTERRUPTED, ATTENDANCE_UNCONFIRMED or ABSENT_NO_SHOW. | §45.6 |
| FR-TRS-08 | M | A correction to ATTENDED uses the points value applicable to the booking's **original** completion date and configuration, posts the transaction at correction time, and records the historical value, correction time, reason and admin identity. | §45.6 |
| FR-TRS-09 | M | The **latest non-superseded** attendance correction by `eventSequence` is authoritative. Each correction references the booking, the superseded correction if any, the original outcome event and the corrected outcome. Only **one** effective outcome per booking contributes to replay, so sequential corrections can never double a reward, a reset or a penalty. | §45.7 |
| FR-TRS-10 | M | Replay can compute state **as of any time**, reproducing both historical and current state from the same inputs. | §45.8 |
| FR-TRS-11 | M | Counter effects: ATTENDED resets and grants points and +1 progress · ATTENDED_INTERRUPTED resets, no reward · protection resets, no reward · ATTENDANCE_UNCONFIRMED does nothing · NO_SHOW increments. The **three canonical counter-reset trust events** are exactly ATTENDED, ATTENDED_INTERRUPTED and the protection event. Separately, and not as a trust event, reaching a penalty threshold also resets the counter at ban creation or extension. | §45.9 |
| FR-TRS-12 | M | Cancelled, rejected, venue-response-expired, attendance-unconfirmed, manual bookings and tournament participation neither increment nor reset the counter. Consecutive tracking is **global across E7GZ PS**, ordered by booking start time. | §45.9, §45.10 |

## 18.2 Penalties and bans

| ID | Pri | Requirement | Spec |
|---|---|---|---|
| FR-TRS-13 | M | The ladder: stage 0 — 3 consecutive → 7 days · stage 1 — 2 consecutive → 1 month · stage 2 — 2 consecutive → 2 months · recurring maximum — every 3 consecutive → 2 months. The threshold intentionally returns to 3 at the recurring stage, and 2 months is the maximum. | §48.2 |
| FR-TRS-14 | M | A penalty triggers when a new eligible no-show **increments the counter to the current threshold** — never merely because a stored number exceeds a later threshold. | §48.2 |
| FR-TRS-15 | M | Account Standing stores and displays penalty stage, current consecutive count, current threshold, ban start, ban until and remaining time, showing exactly **one** current ban. | §48.3 |
| FR-TRS-16 | M | On ban creation the counter resets to 0 and the stage advances; on expiry the counter stays 0 unless new qualifying no-shows occurred after return. A completed booking resets only the current counter and never erases the historical stage. | §48.4 |
| FR-TRS-17 | M | **Ban Qualification** (historical, derived, with `qualificationAt`) and **Ban Instance** (operational, with `banInstanceId`, `activatedAt`, `endsAt`, `penaltyStage`, `duration`, `status`, `activationReason`) are distinct and must not be conflated. | §48.5 |
| FR-TRS-18 | M | A retroactively discovered ban activates at **correction time**, never back-dated, and produces **one** ban instance carrying a historical `qualificationAt` and a current `activatedAt`. Ban end is always `activatedAt + duration`. | §48.5 |
| FR-TRS-19 | M | "Was this user banned then?" is answered from the ban instance's `activatedAt`/`endsAt`, not from the qualification point. Trust Qualification As-Of and Operational Ban As-Of are separate queries and may legitimately differ after a correction. | §48.5 |
| FR-TRS-20 | M | Ban cascade: PENDING → CANCELLED `USER_BANNED` · AWAITING → CANCELLED `USER_BANNED` with hold release · future CONFIRMED → CANCELLED `USER_BANNED` · **ACTIVE continues**. Future holds release and the customer is notified. | §48.6 |
| FR-TRS-21 | M | Each ban instance's cancellation cascade executes **once only**; repeated replay must never repeat it. | §48.7 |
| FR-TRS-22 | M | At most **one ACTIVE ban instance** per user. A further qualification while banned advances the stage as a full trigger, resets the counter to 0, and **extends** the existing instance: `newEndsAt = MAX(currentEndsAt, now + durationForNewStage)`. Extension never repeats an already-executed cascade. `BAN_EXTENDED` is audit-required with instance ID, triggering qualification and event, previous and new stage, old and new end, and timestamp. | §48.8 |
| FR-TRS-23 | M | Ban instance status is ACTIVE / EXPIRED / LIFTED / CORRECTED, and the historical instance always remains in audit. | §48.9 |

## 18.3 Corrections and their limits

| ID | Pri | Requirement | Spec |
|---|---|---|---|
| FR-TRS-24 | M | Replay corrects **trust state** but **never rewinds external booking side-effects into the past**, so it cannot destroy valid later reservations or restore slots already sold. | §47.3 |
| FR-TRS-25 | M | A ban proved invalid is CORRECTED/ENDED with restrictions stopped, the user notified and state recalculated — but **previously cancelled bookings do not return**. | §47.3 |
| FR-TRS-26 | M | There is **no Support Replacement Booking** and no special admin booking-creation path. All customer booking creation uses the same online predicate. Support may assist but cannot bypass validation. | §47.4 |
| FR-TRS-27 | M | Admin remediation covers trust, points, referrals and bookings as listed in v5.0 §47.1, each requiring a reason, admin identity, timestamp and audit record. Wallet adjustment is **not** a launch action. | §47.1 |

**Acceptance criteria**

- A seeded history replayed from events reproduces the stored counter, stage, ban and Verified Player status exactly
- Reversing a Monday no-show on Friday changes the Monday position in the sequence, not the Friday position
- Three sequential attendance corrections on one booking yield exactly one effective outcome and one counter effect
- A correction that newly qualifies a user for a ban starts it today and cancels only bookings existing now
- A second qualification during an active ban produces one instance, an advanced stage, a reset counter and a `BAN_EXTENDED` record
- Replaying trust twice never cancels the same booking twice

---

# 19. POINTS, REFERRALS, WALLET AND FEES

**Rationale.** No money moves at launch, but every ledger rule that would be expensive to retrofit is built now: historical value capture, compensating transactions, a balance floor, and a complete refund classification. Switching on a non-zero fee later must be a configuration change, not a redesign.

| ID | Pri | Requirement | Spec |
|---|---|---|---|
| FR-PTS-01 | M | Points at launch: completed booking with qualifying attendance **+200** and **+1** Verified Player progress · referral signup **+1,000** and **0** progress · no-show, cancelled, rejected and **review** all **0** points and **0** progress. The configured value is read at the **successful attended completion event** and the granted amount is stored in the transaction; later configuration never rewrites history. | §5.6, §46.1 |
| FR-PTS-02 | M | Points and trust are separate systems. Points are a reward currency; trust governs standing and eligibility. | §46.1 |
| FR-PTS-03 | M | **Points balance may never be negative**; the minimum effective balance is 0. A compensating deduction applies `MIN(currentBalance, requestedDeduction)`. | §46.2 |
| FR-PTS-04 | M | Any shortfall is recorded in audit as `UNRECOVERED_REVERSAL_AMOUNT`. It does not create a negative balance, does not become future debt, and future earned points are **not** automatically used to repay it. | §46.2 |
| FR-PTS-05 | M | Points and wallet history is **append-only**. Corrections create compensating transactions; no ledger row is ever deleted. | §46.3 |
| FR-PTS-06 | M | A referral becomes eligible only on **successful account OTP verification**, not on record creation. Statuses: PENDING / REWARDED / INVALID. Only one reward per referred user. Self-referral is not allowed. | §46.4 |
| FR-PTS-07 | M | The reward configuration is read at OTP verification and the applied value is stored permanently. Referrals grant **no** Verified Player progress. | §46.4 |
| FR-PTS-08 | M | Hard self-referral checks cover same phone and same email. Same device and same IP may be stored as **fraud signals** but are not automatic hard blocks in MVP. | §46.4 |
| FR-PTS-09 | M | Invalidating a REWARDED referral sets it INVALID and creates a compensating transaction for the **exact historical reward**. A referral may become INVALID even when the full amount cannot be deducted; the ledger records historical reward, requested reversal, applied reversal and unrecovered amount. | §46.5 |
| FR-PTS-10 | M | Every user has a wallet with an initial balance of EGP 0, supporting E7GZ booking-fee refunds and a future points conversion. **The wallet never pays the venue gaming charge.** | §46.6 |
| FR-PTS-11 | M | The wallet ledger exists in the backend; the customer UI shows **no active wallet screen** at launch. Platform Admin wallet adjustment is **not** a launch action. Points adjustment **is**, because points are active. | §46.6 |
| FR-PTS-12 | W | Points-to-wallet conversion is deferred; the ratio will be configurable. | §46.6 |
| FR-PTS-13 | M | The global booking fee is **EGP 0**, charged **per booking** — not per hour, per venue or per activity — is configurable by Platform Admin, and is **displayed to the customer as FREE**. Each booking stores the fee applied at creation; historical values are immutable and a later configuration change never alters them. Late Confirm reuses the **original stored** booking fee. | §5.1, §51.10 |
| FR-PTS-14 | M | Refund classification is binding the moment a non-zero fee is enabled. **Customer-caused** cancellation follows the 15-minute rule — refundable only within 15 minutes of creation **and** before start. **Venue, platform or resource operationally caused** cancellation receives a **full refund**. | §34.1 |
| FR-PTS-15 | M | Full refund applies to: `VENUE_REJECTED` · `VENUE_DID_NOT_RESPOND_IN_TIME` · `REJECTED_AFTER_EXPIRY` · `CUSTOMER_DECLINED_LATE_CONFIRM` · `CUSTOMER_RECONFIRM_TIMEOUT` · `VENUE_CANCELLED_CONFIRMED_BOOKING` · `VENUE_CLOSURE` · `VENUE_OPENING_HOURS_CHANGED` · `VENUE_EMERGENCY_CLOSURE` · `RESOURCE_EMERGENCY_OUTAGE` · `RESOURCE_OWNER_REVIEW_REQUIRED` · `VENUE_RESPONSE_INTERRUPTED_BY_EMERGENCY` · `RESOURCE_RESPONSE_INTERRUPTED_BY_EMERGENCY` · `LATE_CONFIRM_INTERRUPTED_BY_EMERGENCY` · `VENUE_ACCOUNT_DISABLED` · `SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR` · admin cancellation or correction where the customer is not at fault · `ADMIN_NO_SHOW_REVERSAL` where the wrongful no-show caused service loss. | §34.2 |
| FR-PTS-16 | M | Customer cancellation policy applies to `CUSTOMER_CANCELLED` and `USER_BANNED`. `VENUE_CANCELLED_MANUAL_BOOKING` is **NOT_APPLICABLE** because no customer fee exists. **Every reachable terminal reason has exactly one fee treatment.** | §34.2 |
| FR-PTS-17 | M | Declining or timing out a Late Confirmation is **not** an ordinary customer cancellation for fee purposes, because Late Confirm exists only through the venue's own expiry. | §34.3 |
| FR-PTS-18 | M | Correcting an attendance classification is **not** a refund trigger. Refund follows **failure to deliver the booked service**, not reclassification. A customer-fault case must use its real reason rather than a generic `ADMIN_CORRECTION`. | §34.3 |
| FR-PTS-19 | M | An operational interruption **after service start** receives a full refund regardless of the 15-minute rule. | §34.4 |
| FR-PTS-20 | M | Counterpart phone visibility **ends**: for a booking terminal **before** start time, at `terminalAt + 2 hours` · for a booking terminal **at or after** start time, including NO_SHOW, at the original `endAt + 2 hours` · for VENUE_RESPONSE_EXPIRED, mandatorily at `venueResponseExpiredAt + 2 hours` unless the booking is revived before then. After the retention point the normal booking UI hides counterpart phone data. | §34.6 |
| FR-PTS-21 | M | Retention past a started session exists to support operational disputes and late-arrival contact. Platform Admin and authorised support may retain controlled, audited access where operationally required. Expiry of phone visibility after VENUE_RESPONSE_EXPIRED **never** makes that booking terminal; it remains revivable until `startAt`. | §34.6, §34.7 |

**Acceptance criteria**

- Reversing a +1,000 referral for a user holding 300 points leaves the balance at 0 and records a 700 unrecovered amount that is never later deducted
- Changing the referral reward between signup and OTP verification grants the value in force **at verification**
- Every terminal reason in the closed enum maps to exactly one of the three fee treatments, verified by an exhaustive test over the enum
- No customer UI surface exposes a wallet screen

---

# 20. REVIEWS

**Rationale.** Reviews are the only quality signal in the marketplace, so the bar to write one is low — verified account, no booking required. The moderation model exists because a removed review must not simply be rewritten and republished.

| ID | Pri | Requirement | Spec |
|---|---|---|---|
| FR-REV-01 | M | A reviewer must be an **account-verified** user. A completed booking is **not** required. | §49.1 |
| FR-REV-02 | M | A review has a 1–5 star rating and a comment. Public reviews display the **current username only**; Platform Admin may see both full name and username. | §49.2 |
| FR-REV-03 | M | One active review per user per venue, editable and deletable. Only one current record per user/venue at a time; historical removed records persist. | §49.3 |
| FR-REV-04 | M | States: PENDING_MODERATION (visible to author as *Pending Review* and to admin, not public, excluded from venue rating) · ACTIVE · HIDDEN (not public, editable, stays hidden after edit, restorable) · REMOVED (**terminal**, never restorable, moderation history permanent). | §49.4 |
| FR-REV-05 | M | A normal eligible review is created **ACTIVE**, except in the moderation re-entry flow. There is **no auto-publish** of a pending review, however much time passes; admin reminders never change status automatically. | §49.4 |
| FR-REV-06 | M | Author deletion removes the public review but never destroys moderation audit and history. | §49.5 |
| FR-REV-07 | M | After an admin removal the user may not review that venue for **30 days**; the next review starts PENDING_MODERATION. A further removal starts a new 30-day cooldown. | §49.6 |
| FR-REV-08 | M | The venue is **view only**. Platform Admin may hide, restore, remove, approve a pending review and remove a pending review; every action requires a reason and records action, reason, date, previous status and new status. | §49.7 |
| FR-REV-09 | M | A SUSPENDED or INACTIVE venue keeps its reviews visible and may still receive new reviews. | §49.8 |
| FR-REV-10 | M | The author is notified on submission for moderation, approval, hiding and removal, with the moderation reason where appropriate. | §49.9 |

**Acceptance criteria**

- An unverified user cannot post a review
- Editing a hidden review leaves it hidden
- A removed review cannot be restored by any admin action
- A second review for the same venue 29 days after a removal is refused; on day 31 it is accepted as pending
- A pending review does not move the venue's average rating

---

# 21. PLATFORM ADMIN

| ID | Pri | Requirement | Spec |
|---|---|---|---|
| FR-ADM-01 | M | Platform Admin modules: Dashboard · Areas · Venues · **Venue Onboarding** · Venue Accounts · Activities · Users · Verified Players · Reviews Moderation · Global Bookings · No-show Records · Tournaments · Wallet · Points · Referrals · Platform Fees · **Global Rules** · **Notifications** · **Global Search** · Settings. English only. §51.1 names the core module set; §2.3 names the full launch surface set and both are frozen. | §2.3, §51.1 |
| FR-ADM-02 | M | Venue onboarding is **Platform-Admin managed**, not self-service, covering venue information, activities, account credentials including the initial Owner Password, resources and pricing. Venue images persist through onboarding, venue details, edit and navigation. | §51.2 |
| FR-ADM-03 | M | Admin may disable and re-enable a venue account and reset the venue login and Owner Passwords **without deleting venue data**; an Owner Password reset notifies the venue. There is no venue self-service forgot-password flow in MVP. | §51.3 |
| FR-ADM-04 | M | User management displays name, username, phone, email, OTP verification, Verified Player status, completed bookings, no-show standing, account status and joined date. | §51.4 |
| FR-ADM-05 | M | Verified Player management supports View, Suspend, Revoke and Restore, each requiring a reason. Admin SUSPENDED and REVOKED are protected from automatic trust regain. | §51.5 |
| FR-ADM-06 | M | Global bookings support filters (date range, area, venue, activity, status, source) and search (Booking ID, user name, phone, venue), exposing booking detail with Booking ID, user, venue, activity, resource, **console**, **play mode**, date, time, duration, venue price, **historical booking fee**, total, source, status and **full lifecycle history** — including Pending, Venue Response Expired, Auto Cancelled, Late Confirm Requested, Awaiting Customer Confirmation, Customer Accepted, Customer Declined, Customer Confirmation Expired, Rejected After Expiry, Venue Opening Hours Changed, and every operational and platform reason in v5.0 §33.2. | §51.6 |
| FR-ADM-07 | M | No-show records show user, venue, booking, resource, booking date, consecutive count, penalty stage, penalty result and ban information. | §51.7 |
| FR-ADM-08 | M | Tournament inspection covers tournament, host, host type, activity, play mode, venue, resources, participants, capacity, entry, prize, visibility, date and time, deadline and status. | §51.8 |
| FR-ADM-09 | M | Wallet module shows user, phone, balance and transaction history, and **never** venue gaming revenue. Points module shows user, current points, booking rewards, referral rewards and transaction history. Referral records show referrer, code, referred user, signup date, reward applied and status. | §51.9 |
| FR-ADM-10 | M | Admin controls the **Global Booking Fee** (EGP 0) and the **Tournament Creation Fee** (EGP 0). | §51.10 |
| FR-ADM-11 | M | Every configuration change affects **future events only**. | §53.1 |

**Acceptance criteria**

- Disabling a venue account preserves all venue data and every existing booking
- A global fee change leaves historical bookings' stored fee untouched
- An area with one SUSPENDED venue cannot be deactivated

---

# 22. NOTIFICATIONS

**Rationale.** Timing-critical events need push; account and moderation events need a durable written record. The hard rule is the one in J11: a notification must never imply something the system did not do.

| ID | Pri | Requirement | Spec |
|---|---|---|---|
| FR-NTF-01 | M | Launch channels: In-App · Push · Email · **SMS for OTP only**. | §52.1 |
| FR-NTF-02 | M | Time-sensitive booking, tournament and trust events use **In-App + Push**, covering the full catalogue in v5.0 §52.2 — including every operational and platform cause, `ACTIVE_SESSION_ATTENDANCE_RECORDING_AFFECTED`, tournament resource-availability changes, and operational refund confirmations. | §52.2 |
| FR-NTF-03 | M | Account, security and moderation events use **In-App + Email**, per v5.0 §52.3. | §52.3 |
| FR-NTF-04 | M | Every booking notification internally references the **specific Booking ID**, so navigation always lands on the correct booking. | §52.2 |
| FR-NTF-05 | M | An attendance-unconfirmed notification must explain that **no attendance reward was applied**. | §52.2 |
| FR-NTF-06 | M | Customer copy must distinguish causes: ordinary expiry *"Venue did not respond in time"* · platform account disable *"Venue is temporarily unable to confirm this booking"* · operational issue the corresponding reason. Internal codes remain authoritative. | §33.6 |
| FR-NTF-07 | M | A notification must **never** state that a booking was cancelled unless a rule actually cancelled it. | §32.13 |
| FR-NTF-08 | M | There is **no** mandatory customer notification merely because a venue account is re-enabled; the customer is notified only when a customer-relevant action occurs. | §32.13 |
| FR-NTF-09 | M | Venue notifications cover new requests, expiring requests, auto-cancellations, all Late Confirm outcomes, tournament requests and outcomes, maintenance starting soon, emergency operational reminders, and owner notification of emergencies and owner-review entry. | §52.5 |
| FR-NTF-10 | M | Platform Admin notifications cover onboarding completion, account disable, fee updates, Verified Player revocation, tournament auto-cancellation, ban activation, emergency declarations, owner-review entry, the tournament-cancellation abuse alert, check-in and Late-Present abuse signals, integrity-error alerts, and account disable overlapping an active booking. | §52.6 |
| FR-NTF-11 | M | The customer is notified whenever an admin materially changes a ban, a no-show, an attendance result, points, a booking status or a referral reward in a way that affects them. | §52.3 |
| FR-NTF-12 | M | Notifications dispatch **after** the transaction commits, never before. | §40.5 |

**Acceptance criteria**

- A push for any booking event deep-links to that exact Booking ID, including a revived Late Confirm booking
- The account-disable notification contains no cancellation language
- Re-enabling an account sends nothing by itself
- No notification is delivered for a transaction that rolled back

---

# 23. AUDIT AND HISTORICAL INTEGRITY

| ID | Pri | Requirement | Spec |
|---|---|---|---|
| FR-AUD-01 | M | Configuration changes affect **future events only**. Historical transactions retain their original values: booking fee · referral reward · booking price · tournament values applied at creation · Late Confirm original totals · **points actually granted** · **console type** · **play mode** · **resource identity** · **resource name snapshot**. | §5.9, §53.1 |
| FR-AUD-02 | M | Historical snapshots are stored for bookings, tournaments, referrals, reservations, operational interruptions, emergency pause history, account access restriction intervals and owner-review cascades, per v5.0 §53.2. | §53.2 |
| FR-AUD-03 | M | Every action in the canonical audit list of v5.0 §53.3 writes an audit record — venue operational actions, operational interruptions, platform and integrity events, trust and ledger actions, and moderation. | §53.3 |
| FR-AUD-04 | M | Append-only guarantees: booking history · points and wallet history · trust input events · attendance result (audited correction only) · `businessDate` and operating-session snapshots. | §53.4 |
| FR-AUD-05 | M | Booking history is append-only: previous lifecycle events never disappear. *Auto Cancelled* stays visible even after a Late Confirm revival. | §33.7 |
| FR-AUD-06 | M | Every audit record carries the actor identity (venue account, admin or system), a timestamp and, where the action requires one, the mandatory reason. | §53.3 |
| FR-AUD-13 | M | **Booking Reason Codes** and **Booking Events** are two **separate closed enums**. A reason code explains a **terminal** booking outcome; an event records an action or a **non-terminal** failure in append-only history. A token belongs to exactly one of the two. Adding a product-visible member to either requires Product Change Control. | §33.1, §57.3 |
| FR-AUD-14 | M | The canonical **Booking Event** list is `CHECK_IN_RECORDED` · `CHECK_IN_REVERSED` · `LATE_PRESENT_RECORDED` · `LATE_PRESENT_REVERSED` · `NO_SHOW_RECORDED` · `NO_SHOW_REVERSED` · `ATTENDANCE_CORRECTED` · `LATE_CONFIRM_FAILED_RESOURCE_CONFLICT` · `LATE_CONFIRM_FAILED_CUSTOMER_CONFLICT` · `LATE_CONFIRM_FAILED_BOOKING_LIMIT` · `LATE_CONFIRM_ELIGIBILITY_LOST_RESOURCE_STATE_CHANGED` · `VENUE_RESPONSE_INTERRUPTED_BY_ACCOUNT_DISABLE`. **No API validation-only token belongs in this enum**, which is why `MANUAL_BOOKING_PRICING_CONFIGURATION_MISSING` is an error code only. | §33.4 |
| FR-AUD-15 | M | Five tokens are **deliberately removed** and must not be reintroduced: `BOOKING_EXPIRED` (use `VENUE_DID_NOT_RESPOND_IN_TIME`) · `RESOURCE_CONFLICT` (validation or atomic-commit failure, or `LATE_CONFIRM_FAILED_RESOURCE_CONFLICT`) · `CUSTOMER_SCHEDULE_CONFLICT` (`LATE_CONFIRM_FAILED_CUSTOMER_CONFLICT`) · `LATE_CONFIRM_RESOURCE_UNAVAILABLE` (as above) · `LATE_CONFIRM_ELIGIBILITY_LOST_RESOURCE_STATE_CHANGED` **as a terminal reason** (it is an event of the same name). Because none is terminal, none needs a fee treatment — which is what makes every reachable terminal reason map to exactly one treatment. | §33.3 |

**Acceptance criteria**

- A booking that goes PENDING → expired → Late Confirm → CONFIRMED → ACTIVE → COMPLETED shows every step in order, with nothing removed
- Changing a venue's hourly price leaves every historical booking's stored price and total unchanged
- Every audited action in v5.0 §53.3 produces a retrievable record in an end-to-end run

---

# PART C — NON-FUNCTIONAL REQUIREMENTS

---

# 24. NON-FUNCTIONAL REQUIREMENTS

## 24.1 Correctness and consistency

| ID | Pri | Requirement | Spec |
|---|---|---|---|
| NFR-01 | M | Every commitment-strengthening transition revalidates and mutates state in **one atomic transaction**. Partial commits are never an acceptable outcome. | §31.11 |
| NFR-02 | M | Concurrency resolves by **first successfully committed valid transition wins**, with mandatory revalidation for the loser. The architecture may choose any locking or isolation strategy that delivers this. | §31.11 |
| NFR-03 | M | Multi-entity cascades — emergency closure, resource emergency, owner review, opening-hours change, ban activation — are **all-or-nothing**, persisting every cancellation, release, history record and outbox event before notifications dispatch. | §40.5, §43.4 |
| NFR-04 | M | Scheduled transitions (venue response expiry, Late Confirm expiry, booking start, booking completion, tournament checkpoint, tournament start and end, ban expiry) must fire reliably and idempotently; a retried scheduler run must not double-apply an effect. | §17.6 |
| NFR-05 | M | Trust replay is **deterministic**: the same event stream always produces the same derived state, and as-of replay at any past instant is reproducible. | §45.8 |
| NFR-06 | M | Idempotency keys exist for every cascade that must execute once — `banInstanceId` for ban cascades, `verifiedPlayerTransitionId` for tournament cascades, and one effective protection event per booking. | §48.7, §37.8, §32.9 |

## 24.2 Time correctness

| ID | Pri | Requirement | Spec |
|---|---|---|---|
| NFR-07 | M | All scheduling and availability logic uses **absolute instants**; local wall-clock is a presentation and template concern. | §14.4 |
| NFR-08 | M | The Africa/Cairo IANA zone with full DST rules governs operating sessions, intake windows and every timer. | §14.4 |
| NFR-09 | M | Customer price and displayed duration use the **selected nominal duration**; `endAt = startAt + nominalBookedMinutes` in real elapsed time. A DST transition must never change the price. | §4.4 |
| NFR-10 | M | Timer boundaries are strict: `now < deadline` permits, `now >= deadline` expires. All timers are server-authoritative; client countdowns are display only. | §17.6 |

## 24.3 Availability, performance and scale

| ID | Pri | Requirement | Spec |
|---|---|---|---|
| NFR-11 | M | Availability queries backing discovery and Nearest Availability must be fast enough to render a venue list without perceptible delay on a mobile connection. Target: p95 under 1 second for a list of venues in an area. | **TBD / Proposed non-product requirement — not defined by Frozen Baseline** |
| NFR-12 | M | Booking creation, venue accept, Late Confirm initiation and Late Confirm accept must complete well inside their user-visible timers. Target: p95 under 2 seconds end to end. | **TBD / Proposed non-product requirement — not defined by Frozen Baseline** |
| NFR-13 | M | Push notifications for timer-critical events — Late Confirmation received, Late Confirmation expiring — must dispatch promptly after the triggering commit, because the customer's window may be as short as 60 seconds. The §25.9 rules are frozen: one expiring notification about a minute before expiry when the window exceeds 60 seconds, none when it does not, and the real short countdown shown in the first notification. **Proposed target: dispatch within seconds of commit — **TBD / Proposed non-product requirement — not defined by Frozen Baseline**.** | §25.9 + TBD |
| NFR-14 | M | Late firing of a scheduled transition must **never** produce an incorrect outcome: the timer boundary rule decides, not the execution time. **Proposed target: scheduled transitions fire within a small bounded delay of their due instant — **TBD / Proposed non-product requirement — not defined by Frozen Baseline**.** | §17.6 + TBD |
| NFR-15 | S | The system must handle the launch city's venue and booking volume with headroom for a 10× increase without architectural change. | **TBD / Proposed non-product requirement — not defined by Frozen Baseline** |

## 24.4 Security and privacy

| ID | Pri | Requirement | Spec |
|---|---|---|---|
| NFR-16 | M | The Owner Password is validated per action, rate-limited, and never cached into a session or unlock window. Successful and failed attempts are audited. | §54.3 |
| NFR-17 | M | OTP is rate-limited per destination and per code, and a new OTP invalidates the previous one. | §7 |
| NFR-18 | M | Counterpart phone visibility is enforced server-side by the retention rules of v5.0 §34.6 — it is never merely hidden in the UI. | §34.6 |
| NFR-19 | M | A venue must never learn why a customer is ineligible for a Late Confirmation. Eligibility failures return neutral operational messages. | §25.4, §25.12 |
| NFR-20 | M | Platform Admin and authorised support access to retained contact data is permission-controlled and audited. | §34.6 |
| NFR-21 | M | Credentials are never logged, and audit records never contain secrets. | **TBD / Proposed non-product requirement — not defined by Frozen Baseline** |

## 24.5 Data integrity

| ID | Pri | Requirement | Spec |
|---|---|---|---|
| NFR-22 | M | Append-only stores — booking history, trust events, points and wallet ledgers, audit — must be append-only in behaviour. **Proposed additionally: enforce this at the data layer rather than by application convention alone — **TBD / Proposed non-product requirement — not defined by Frozen Baseline**; v5.0 §57.2 leaves storage choices to the architecture phase.** | §53.4 + TBD |
| NFR-23 | M | Closed enums are validated at write time; an unknown value is rejected rather than stored. | §33 |
| NFR-24 | M | `detailCode` is validated against the **event-specific** closed set and is immutable once recorded. | §33.5 |
| NFR-25 | M | Money is stored and computed at 2 decimal places with half-up rounding; totals are sums of rounded line items. | §4.3 |
| NFR-26 | M | Every terminal booking and every terminal tournament carries exactly **one** reason code. A record with zero or two is a data-integrity defect. | §31.6, §37.14 |

## 24.6 Observability

| ID | Pri | Requirement | Spec |
|---|---|---|---|
| NFR-27 | M | Every metric in **this document's** §4.2 is instrumented from day one. | **TBD / Proposed non-product requirement — not defined by Frozen Baseline** |
| NFR-28 | M | Two active reservations must never overlap on one resource (§16.4). **Proposed additionally: any occurrence raises an immediate Sev-1 alert — **TBD / Proposed non-product requirement — not defined by Frozen Baseline**.** | §16.4 + TBD |
| NFR-29 | M | `SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR` raises a Platform Admin operational alert carrying the full diagnostic payload. | §31.4 |
| NFR-30 | M | Emergencies left in AWAITING_CONFIRMATION past their expected end, and resources under owner review, are surfaced operationally to the venue, the owner and Platform Admin. | §42.7 |
| NFR-31 | M | Abuse signals are computed and surfaced without ever auto-punishing. | §27.10, §41.6 |
| NFR-32 | M | Cascade executions are traceable end to end by a correlation identifier. | §32.9 |

## 24.7 Localization and accessibility

| ID | Pri | Requirement | Spec |
|---|---|---|---|
| NFR-33 | M | The Venue Dashboard supports full Arabic RTL across every module listed in FR-VEN-40; stored free text is never transformed. | §50.6 |
| NFR-34 | M | The Customer app is English at launch and **localization-ready**: no hard-coded user-facing strings, and layouts that tolerate RTL mirroring. | §52.7 |
| NFR-35 | M | All currency is EGP, formatted consistently across the three surfaces. | §4.4 |
| NFR-36 | S | Interactive elements meet common mobile accessibility expectations for touch target size, contrast and screen-reader labelling. | **TBD / Proposed non-product requirement — not defined by Frozen Baseline** |

## 24.8 Configurability

| ID | Pri | Requirement | Spec |
|---|---|---|---|
| NFR-37 | M | Every value in v5.0 §56 is configurable unless the specification states otherwise, and every change applies to **future events only**. | §56 |
| NFR-38 | M | Enabling a non-zero booking fee must require **no schema or logic redesign** — only configuration plus the payment integration deferred from this release. | §5 |

---

# 25. DATA AND REPORTING REQUIREMENTS

| ID | Pri | Requirement | Spec |
|---|---|---|---|
| FR-AUD-07 | M | `businessDate` is the single revenue-day key across online bookings, manual bookings and tournaments, and is historical. | §17.5 |
| FR-AUD-08 | M | Venue revenue reporting aggregates today's revenue, this month's revenue, and online, manual and tournament revenue on `businessDate`, with an optional activity breakdown. | §50.3 |
| FR-AUD-09 | M | Tournament revenue recognises the stored venue cost only on **COMPLETED**. | §36.10 |
| FR-AUD-10 | M | Booking detail exposes the historical booking fee alongside the venue price and total, so a future non-zero fee is reportable without back-filling. | §51.6 |
| FR-AUD-11 | S | Platform reporting can produce the terminal-reason distribution split by customer-caused, venue-caused, operational and platform causes. Derived from PRD §4.2 (G3), not from v5.0. | **TBD / Proposed non-product requirement — not defined by Frozen Baseline** |
| FR-AUD-12 | S | Platform reporting can produce the attendance mix and the check-in capture rate per venue. Derived from PRD §4.2 (G4), not from v5.0. | **TBD / Proposed non-product requirement — not defined by Frozen Baseline** |

---

# 26. RELEASE PLAN

> *The release plan, its phases and its exit criteria are authored by this PRD and are **not defined by the Frozen Baseline**. They are governed by PRD §31.2 and may change without a Product Change Request. Where a phase restates a v5.0 rule, v5.0 governs.*

The product ships as one launch. The phasing below is a **build and validation sequence**, not a staged public release — every **M** requirement is in the launch.

## Phase 0 — Foundations

Timezone and operating-session engine · resource model with both status domains · pricing and money rounding · audit and append-only infrastructure · the closed enums with write-time validation.

*Exit:* operating sessions validate cyclically across a week including DST transitions; money rounds correctly; enum writes reject unknown values.

## Phase 1 — Inventory and availability

The canonical availability predicate · reservation model with release history · `businessDate` · Nearest Availability.

*Exit:* the concurrency test in §10 passes; no path to a slot bypasses the predicate.

## Phase 2 — Core booking

Registration and OTP · discovery · **map view** · online booking creation through the 20-condition predicate · venue accept, reject and expiry · manual booking · customer cancellation · the customer-facing projection.

*Exit:* J1 and J8 complete end to end; every terminal booking carries one reason code.

## Phase 3 — Recovery and attendance

VENUE_RESPONSE_EXPIRED handling and start-time resolution priority · Late Confirm in full · check-in, Mark Present — Late and no-show · attendance results · start-time revalidation.

*Exit:* J2, J3, J4 and J5 complete; boundary tests at ±1 second pass on all four timers.

## Phase 4 — Trust

The trust event stream · replay with as-of queries · attendance corrections and chains · the penalty ladder · ban qualification versus ban instance · the ban cascade · Verified Player qualification and operational status · points and referrals.

*Exit:* J12 completes; replay reproduces a seeded history exactly; no double reward or double penalty is reachable.

## Phase 5 — Operations and resilience

Maintenance with the rolling threshold · closures · venue and resource emergencies · the emergency status lifecycle including conversion · operational selling blocks · owner review and its cascade · timer pauses and caps · venue account disable and Platform Attendance Protection.

*Exit:* J6, J7 and J11 complete; fault injection produces no partial cascade and no sellable gap; **all three interrupted-by-emergency reason codes are reachable, and the optimistic feasibility test produces the same decision on repeated evaluation of identical state**.

## Phase 6 — Tournaments

The tournament entity · player and venue hosted creation · approval · holds · join and leave · the checkpoint · lifecycle and cancellation authority · resource loss handling · cost snapshots and revenue.

*Exit:* J10 completes; every tournament reason is reachable and prioritised correctly.

## Phase 7 — Surround and launch readiness

Reviews and moderation · Platform Admin modules · notification catalogue across all channels · Arabic RTL Dashboard · reporting and metric instrumentation · abuse signals.

*Exit:* the launch readiness bar in §4.3 is met.

**Dependency notes.** Phase 1 blocks everything. Phase 3 depends on Phase 2. Phase 5's owner-review cascade depends on Phase 3's booking lifecycle and Phase 6's tournament entity for its full cascade, so tournaments and operations overlap in practice; build the tournament entity early enough that the owner-review cascade can be tested against it.

---

# 27. DEPENDENCIES AND ASSUMPTIONS

## 27.1 External dependencies

| Dependency | Used for | Risk if unavailable |
|---|---|---|
| SMS provider | Mandatory phone OTP | **Blocking** — no account can be verified, so no booking can be created |
| Push notification service | Every timer-critical customer event | **High** — Late Confirmation with a 60-second window is unusable without push |
| Email provider | Account, security and moderation events | Medium — degraded, not blocking |
| Map and geocoding | Map view, distance sorting | Medium — list view and area filter still work |
| Payment provider | Deferred | None at launch |

## 27.2 Assumptions

1. Venues staff their Dashboard during operating hours well enough to answer inside ten minutes; the expiry and Late Confirm paths exist because this will often fail.
2. Venue staff record attendance honestly. The product mitigates with audit, correction and monitoring rather than identity proof.
3. Customers pay venues directly, and no dispute about that money reaches E7GZ at launch.
4. One timezone and one currency for the launch market.
5. Each venue is a single site; multi-branch management is out of scope.
6. Venue onboarding volume is low enough for Platform Admin to manage manually.
7. Arabic is needed by venue staff at launch; customers accept an English app initially. *Derived from Spec §2.4, §52.7.*

## 27.3 Open items for the architecture phase

These are **implementation** questions the frozen behaviour deliberately leaves open, not product decisions.

- Locking and isolation strategy delivering NFR-01 and NFR-02
- Scheduler technology and its idempotency guarantees for NFR-04
- Trust replay strategy — full replay versus snapshot-plus-delta — provided determinism holds
- Storage and indexing for the availability predicate
- Outbox and notification delivery mechanics satisfying NFR-12 and FR-NTF-12
- Concrete rate-limit values within the rules of NFR-16 and NFR-17
- Media storage for venue images
- Whether append-only stores are enforced at the data layer or by application convention (NFR-22). v5.0 §57.2 leaves storage choices open; this PRD proposes data-layer enforcement as an architecture decision, not as a frozen Product rule

---

# 28. RISKS

| # | Risk | Impact | Mitigation |
|---|---|---|---|
| R1 | **A double booking reaches a real customer.** | Existential for venue trust | One canonical predicate, atomic revalidate-and-commit, a Sev-1 alert on any overlap, and a concurrency test in the launch bar |
| R2 | **Venues do not answer inside ten minutes**, so most requests expire. | Marketplace looks broken | Late Confirm recovers the booking with the same ID; expiry rate and recovery rate are launch metrics; venue push notifications for new and expiring requests |
| R3 | **Staff do not check customers in**, so customers earn nothing and Verified Player never converts. | Trust and rewards stall silently | Check-in capture rate is a launch metric surfaced per venue; the Dashboard puts check-in on the day view |
| R4 | **Staff misuse emergency declarations** to dodge bookings or the Owner Password. | Customer harm, owner circumvented | Mandatory reason, full audit, owner notification, the rolling maintenance threshold escalating to owner review, and the tournament-cancellation abuse alert |
| R5 | **A partial cascade** leaves inconsistent state after an emergency or owner review. | Silent inventory corruption | All-or-nothing cascades, fault injection in the launch bar, correlation IDs for tracing |
| R6 | **Trust penalties are disputed and indefensible.** | Reputational, possible customer loss | Event-sourced trust, as-of replay, admin correction that never destroys history, and a monitored correction rate |
| R7 | **A platform action is read as venue failure** — or the reverse. | Unfair blame, venue churn | Distinct closed reason codes for platform, venue, resource and customer causes, plus the mandatory copy distinctions of FR-NTF-06 |
| R8 | **SMS delivery fails or is unreliable**, blocking verification. | Blocking | Provider selection with fallback capability; resend and attempt limits tuned to real delivery behaviour |
| R9 | **The 60-second Late Confirmation window** is unusable if push is slow. | Recovery path fails in practice | NFR-13 dispatch target; the short-window rule already suppresses a useless second notification and shows the real countdown |
| R10 | **DST produces a wrong price or a wrong session.** | Money and inventory errors | Nominal duration for price, absolute instants for inventory, explicit DST cases in the test suite |
| R11 | **Scope pressure to add payments before launch.** | Large schedule and risk increase | The fee model is already built and inert; enabling it is a later configuration change, recorded as deferred in §3.4 |
| R12 | **An implementer resolves an ambiguity against an old v4.x document.** | Behaviour drift from the frozen baseline | v5.0 supersedes v4.0–v4.12 in full; this PRD references v5.0 sections only; Product Change Control governs every change |

---

# 29. FUTURE ROADMAP

Recorded so that deferred work is visible rather than rediscovered. Nothing here is committed.

| Candidate | Note |
|---|---|
| Non-zero booking fee with payment collection and settlement | The model, historical values and refund classification already exist |
| Wallet activation and points-to-wallet conversion | Ledger exists; ratio configurable |
| Arabic Customer Mobile UI | The app is built localization-ready |
| Tournament brackets, scoring, per-match scheduling | Explicitly excluded from MVP |
| Tournament payment collection and prize settlement | Currently informational only |
| Kick Off | Must not appear in any launch surface |
| Favorites, community feed, Elo, leaderboards, match history | — |
| Customer-side check-in proof (QR or PIN) | Would reduce reliance on venue honesty |
| Venue self-service onboarding | Currently Platform-Admin managed |
| Multi-branch venue accounts, role-based venue users | Currently one shared account plus Owner Password |
| Additional cities and multi-timezone support | Single market at launch |
| Food, drink and snack ordering; venue POS | Explicitly excluded |

---

# 30. TRACEABILITY

| PRD area | Requirement IDs | v5.0 sections |
|---|---|---|
| Accounts and identity | FR-ACC-01…18 | §7, §8, §9, §53.5 |
| Discovery | FR-DIS-01…14 | §2.1, §11, §16.5, §20 |
| Availability and inventory | FR-AVL-01…15 | §16, §17, §31.10–§31.12, §38.4, §43 |
| Online booking | FR-BOK-01…32 | §14.4, §18, §19, §21–§24, §26, §29, §31, §35.2, §4.4 |
| Late Confirmation | FR-LCF-01…18 | §25, §34.7, §35.2 |
| Attendance and protection | FR-ATT-01…28 | §26, §27, §28, §31.7, §32 |
| Manual booking | FR-MAN-01…11 | §30 |
| Venue model and Dashboard | FR-VEN-01…47 | §2.2, §3.2, §3.3, §4.1, §4.2, §10, §11, §12, §13, §14, §15, §32, §39.4, §49.7, §50, §51.11, §53.3, §54 |
| Operations and emergencies | FR-OPS-01…44 | §38, §39, §40, §41, §42, §43, **§44** |
| Tournaments | FR-TRN-01…33 | §36, §37 |
| Trust and bans | FR-TRS-01…27 | §45, §47, §48 |
| Points, referrals, fees | FR-PTS-01…21 | §5, §34, §46, §51.10 |
| Reviews | FR-REV-01…10 | §49 |
| Platform Admin | FR-ADM-01…11 | §2.3, §51, §53.1 |
| Notifications | FR-NTF-01…12 | §32.13, §33.6, §40.5, §52 |
| Audit and reporting | FR-AUD-01…15 | §5.9, §17.5, §33, §36.10, §50.3, §51.6, §53 |
| Non-functional | NFR-01…38 | §4.4, §7, §14.3, §16.4, §17.6, §25, §27.10, §31, §32.9, §33, §34.6, §37.8, §40.5, §41.6, §42.7, §43.4, §45.8, §48.7, §50.6, §52.7, §53.4, §54.3, §56 |

**Coverage statement.** Every top-level section of v5.0 §1–§57 is represented by at least one requirement that states the rule, not merely by a row in this matrix. Parts I–X map to §8–§25 of this document as above; v5.0 §0 maps to §7.1 and §7.2 here; v5.0 §1 maps to §3.1 and §3.2; v5.0 §2 maps to §5.1, FR-VEN-34 and FR-ADM-01; v5.0 §3 and §4 map to §5.2, FR-VEN-41 to FR-VEN-45 and FR-BOK-06; v5.0 §6 maps to §3.4; v5.0 §12 maps to FR-VEN-12 and FR-VEN-47; v5.0 §44 maps to §16.6, FR-OPS-34 to FR-OPS-41; v5.0 §55 and §56 are represented through NFR-23, NFR-37 and the enum listing in §7.1; v5.0 §57 governs §31 below. Coverage was verified by the PRD-to-Frozen-Baseline Derivation Audit v1.0.

---

# 31. CHANGE CONTROL FOR THIS PRD

## 31.1 Precedence

Behaviour is governed by **v5.0**. If this PRD contradicts it, v5.0 wins and the PRD is corrected without ceremony.

## 31.2 What changes here without touching v5.0

Priorities, phasing, metric targets, personas, journeys, risk assessments, the roadmap, and acceptance-criteria wording that does not change the behaviour being asserted.

## 31.3 What requires a Product Change Request against v5.0 first

Any new enum member · any state-machine transition change · any authorization change · any change to the canonical availability predicate or a creation or commitment predicate · any change to trust replay ordering, effective-time rules or counter-reset semantics · any change to the refund classification · any change to a value v5.0 states as a fixed product rule rather than a configurable. *Spec §57.3.*

A change of this kind updates v5.0 first, then this PRD.

## 31.4 Correction record — v1.0 to v1.1

This revision applies all 54 findings of the **E7GZ PS PRD-to-Frozen-Baseline Derivation Audit v1.0**: 1 Class A behaviour mismatch, 30 Class B omissions or compressions, 4 Class C priority mismatches, 13 Class D unsupported additions and 6 Class E traceability clusters covering 15 reference corrections. Thirty requirements were added and none removed, taking the total from 364 to 394. **Product Specification v5.0 is unchanged.** No Product Change Request was raised and no Product decision was reopened. Three boundary items the audit referred to the Product Owner were ruled on and applied as rulings, not as specification changes: FR-BOK-11 remains at twenty conditions with no twenty-first added; `activities` is removed from the venue card; FR-VEN-34 and FR-ADM-01 carry the combined §2.2/§50.1 and §2.3/§51.1 surface sets.

**Numbering note.** Audit finding B-02 asked that the new Areas model be inserted as FR-VEN-12 with the block renumbered. A cascading renumber would have collided with the new FR-VEN-41 to FR-VEN-46 and would have invalidated the Spec-reference corrections that finding E-01 states against the original FR-VEN-14 and FR-VEN-15. The new Areas model therefore takes **FR-VEN-12** as the audit directs, and the area-deactivation rule formerly carrying that ID is reissued unchanged in substance as **FR-VEN-47**. No requirement is lost, no other ID moves, and the total remains 394. The venue range is consequently **FR-VEN-01…47**, where the audit's §30 row read FR-VEN-01…46.

## 31.5 Known documentary gap

The Eleventh-Pass Audit document was never supplied to the audit chain, so findings F11-02 through F11-04 remain unclassified by reference. This affects audit-history completeness only. It is not a freeze criterion, and it affects no requirement in this document. *Spec §57.5.*

---

# 32. GLOSSARY

| Term | Meaning |
|---|---|
| **Account Verified** | Phone verified by SMS OTP — the gate to every marketplace action |
| **Active Participant** | A tournament participant who is JOINED, not banned, not deleted, and has neither left nor been removed |
| **Attendance Result** | An independent stored field recording what actually happened, separate from booking status |
| **Ban Instance** | The operational ban that actually restricts behaviour, distinct from the historical qualification |
| **Ban Qualification** | The historical trust fact that a threshold was crossed |
| **businessDate** | The canonical revenue-day key on every revenue-producing record |
| **Existing-Commitment Operational Eligibility** | Category B — can the venue operationally honour a commitment that already exists |
| **Internal Venue Operation** | Category C — manual booking and internal scheduling, outside marketplace acquisition rules |
| **Late Confirm** | Venue-initiated revival of an expired request, keeping the same Booking ID |
| **Nearest Availability** | The earliest valid start time for the selected activity at the 1-hour minimum duration |
| **New-Activity Marketplace Eligibility** | Category A — creating a new marketplace commitment; requires marketplace ACTIVE |
| **Nominal duration** | The customer-selected duration that drives price and display, never a DST-anomalous elapsed figure |
| **Operating Day** | The weekday an operating session belongs to; a 2 AM booking may belong to the previous day |
| **Operating Session** | The actual start/end interval generated from opening hours; at most one per operating day |
| **Operational Selling Block** | A higher-level availability restriction created by an emergency; not a reservation |
| **`OWNER_REVIEW_REQUIRED`** | A resource operational restriction that makes a resource unsellable until the owner resolves it |
| **Owner Password** | Per-action authorization for pricing and resource-status changes; no session or unlock window |
| **Platform Attendance Protection** | Protection applied when a platform account disable prevented reliable attendance recording — no penalty, no reward |
| **Reservation** | A generic inventory-blocking interval |
| **System Operational Consequence** | An automatic cascade caused by an emergency or owner review; never requires the Owner Password |
| **Trust Input Event** | An immutable event from which all trust state is derived |
| **Verified Player** | A customer qualified by 10 completed attended bookings; only operational status VERIFIED may host a tournament |

---

*End of E7GZ PS Product Requirements Document v1.1. Behaviour is governed by E7GZ PS Product Specification v5.0 — FROZEN BASELINE, which this revision leaves unchanged.*
