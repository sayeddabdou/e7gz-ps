# E7GZ PS
# PRODUCT SPECIFICATION v5.0 — FROZEN BASELINE

**Status:** FROZEN — PCR-001, PCR-002 and PCR-003 INCORPORATED
**Market:** Egypt — initial launch in Cairo
**Timezone:** Africa/Cairo
**Currency:** EGP
**Frozen on:** 18 September 2026
**Product Change Requests incorporated:** 25 September 2026 — PCR-001 (Tournament Participant cause/capacity), PCR-002 (Customer-initiated Account Deletion), PCR-003 (Customer Review Reporting). PCR-001 changes §§37.4/37.11 without introducing a new Participant Status; PCR-002 changes §§7.7/53.5; PCR-003 adds §49.10.
**Supersedes:** Product Functional Plan v4.0 and Clarifications v4.1 through v4.12, in full.

**Approved products:**

- Customer Mobile App
- Venue Dashboard
- Platform Admin

---

# 0. HOW TO READ THIS DOCUMENT

## 0.1 What this document is

This is the single source of truth for E7GZ PS Product behaviour. It consolidates thirteen documents — the v4.0 Product Functional Plan and the twelve clarification versions v4.1 through v4.12 — into one resolved specification with every supersession already applied.

Where an earlier version said one thing and a later version said another, only the resolved rule appears here. There is no need to read the v4.x chain to implement the Product. Those documents are retained as the audit trail of how each rule was decided, not as rules to be reconciled against this one.

## 0.2 Freeze basis

The Thirteenth-Pass Product Freeze Confirmation Audit, run across v4.0 → v4.12 on 18 September 2026, returned:

| Criterion | Count |
|---|---|
| Hard Contradictions | 0 |
| Architecture Blockers | 0 |
| Product Owner Decisions Required | 0 |

All three criteria were met simultaneously, which is the freeze condition the Product Owner set in v4.1 and every version since.

## 0.3 What Freeze means

Product and UX definition is closed. This document is the baseline for:

- Technical Architecture and Data Model
- Database and API design
- Backend, Mobile, Venue Dashboard and Platform Admin development
- Infrastructure, notifications, security and payment readiness

Any behaviour that conflicts with this document must be introduced as an explicit, versioned Product Change Request against v5.0. Implementation must not resolve an apparent conflict by choosing an older document.

## 0.4 Architecture latitude

Technical Architecture may freely choose locking strategy, transaction isolation, queue and scheduler technology, indexing, caching, partitioning, and security rate-limit values, provided Product-visible behaviour defined here does not change.

## 0.5 Terminology discipline

These status domains are independent. Never infer one from another.

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
| Operational Verified Player Status | NOT_VERIFIED · VERIFIED · SUSPENDED · REVOKED |
| Review Status | PENDING_MODERATION · ACTIVE · HIDDEN · REMOVED |
| Referral Status | PENDING · REWARDED · INVALID |

**Tournament Participant ineligibility cause.** When `Tournament Participant Status = INELIGIBLE`, the cause is explicit. Current causes are `BAN` and `ACCOUNT_DELETED`. The cause is subordinate to Participant Status and is **not** another status domain. `ACCOUNT_DELETED` has precedence over `BAN`.

Supporting terms:

- **Operating Day** — the weekday to which an Operating Session belongs.
- **Operating Session** — the actual start/end interval generated from Opening Hours.
- **Reservation** — a generic inventory-blocking interval.
- **Booking** — the Customer or Manual business entity.
- **Tournament** — a separate business entity, never modelled as a Booking.
- **Tournament Capacity Occupancy** — for pre-start join-capacity checks, counts `JOINED` participants plus `INELIGIBLE/BAN` participants whose accounts remain active and whose automatic pre-start restoration is still possible. It is deliberately different from Active Participant count.


## 0.6 Document map

| Part | Sections | Covers |
|---|---|---|
| **I — Business Model** | 1–6 | Vision · the three products · activities and resources · pricing · money model · exclusions |
| **II — Identity, Accounts and Trust Status** | 7–9 | Registration and verification · Verified Player · banned-user permissions |
| **III — Venue Model** | 10–15 | Venue Account and Owner Password · marketplace and account status · areas · resources · opening hours · closures |
| **IV — The Availability Engine** | 16–17 | Canonical availability · historical occupancy and businessDate |
| **V — The Booking Lifecycle** | 18–34 | Booking model · parameters · discovery · intake · creation predicate · venue response · expiry · late confirm · start and completion · attendance · cancellation · manual booking · start-time revalidation · account disable and attendance protection · reason codes · fees and phone visibility |
| **VI — Eligibility Framework and Tournaments** | 35–37 | The three eligibility categories · tournament model · tournament lifecycle |
| **VII — Maintenance, Emergencies and Operational Restrictions** | 38–44 | Operational Selling Block · maintenance · venue and resource emergencies · emergency lifecycle · owner review · timer pauses |
| **VIII — Trust, Rewards and Enforcement** | 45–48 | Trust engine · points, referrals and wallet · admin remediation · no-show penalties and bans |
| **IX — Reviews, Dashboards and Administration** | 49–52 | Reviews · venue dashboard · platform admin · notifications |
| **X — Audit, Authorization and Canonical Reference** | 53–57 | Audit and historical integrity · authorization matrix · enum appendix · configuration baseline · change control |

---

# PART I — BUSINESS MODEL

---

# 1. PRODUCT VISION

E7GZ PS is an Egyptian digital platform connecting Customers with gaming venues and giving all parties one shared system for discovering, booking and managing gaming Resources.

**Customer experience:** FIND → BOOK → PLAY

**Venue experience:** MANAGE ALL RESERVATIONS IN ONE INVENTORY

**Platform experience:** CONTROL THE MARKETPLACE WITHOUT RUNNING VENUE DAILY OPERATIONS

## 1.1 Product principles

**Customer.** "I can quickly find somewhere available, know the price, see what users think, book it, and understand exactly what is happening with my booking."

**Venue.** "Online, manual and tournament reservations share one inventory, so I always know what is available and cannot double-book."

**Venue Owner.** "Sensitive pricing and resource-status changes cannot happen casually without my Owner Password."

**Platform.** "I control the marketplace, moderation, trust and global rules without becoming the venue cashier."

---

# 2. THE THREE PRODUCTS

## 2.1 Customer Mobile App

Registration · OTP Verification · Area / Location · Venue Discovery · Map View · Venue Ratings & Reviews · Live Resource Availability · Booking · My Bookings · Late Confirmation · Account Standing · Wallet · Points · Referrals · Verified Player · Tournaments · Notifications · Profile

## 2.2 Venue Dashboard

Overview · Pending Requests · Missed / Expired Requests · Accept / Reject · Late Confirmation · Manual Booking · Bookings · Rooms / Resource Management · Pricing · Availability · Maintenance & Closures · Opening Hours · Reviews (view only) · Tournaments · Booking History · Revenue Summary · Settings · English / العربية

## 2.3 Platform Admin

Dashboard · Areas · Venues · Venue Onboarding · Venue Accounts · Activities · Users · Verified Players · Reviews Moderation · Global Bookings · No-show Records · Tournaments · Wallet · Points · Referrals · Platform Fees · Global Rules · Notifications · Global Search · Settings

## 2.4 Language

| Product | Languages |
|---|---|
| Customer Mobile App | English UI at launch; implementation must be localization-ready for future Arabic / RTL. Arabic Customer UI is not part of this baseline. |
| Venue Dashboard | English (LTR) and العربية (full RTL), covering sidebar, header, KPIs, tables, filters, forms, buttons, modals, drawers, statuses, pricing, revenue, reviews, tournaments, opening hours, booking history, notifications and settings. Free-text stored data remains as entered. |
| Platform Admin | English only. |

Venue Dashboard language preference resolves in this order: Venue Account preference, then browser/device storage, then English.

---

# 3. SUPPORTED ACTIVITIES AND RESOURCES

## 3.1 Activities

Supported activities are **PlayStation**, **Billiards / Pool**, and **Ping Pong**, and only these. A Venue may support one, two or all three.

**PC Gaming is removed from the Product.** It must not appear in any product surface, onboarding, activity list, resource, filter, search, tournament, report or architecture artefact unless explicitly reintroduced by a future Product version.

## 3.2 Resource naming

| Activity | Resource naming | Extra attribute |
|---|---|---|
| PlayStation | Room 1, Room 2, Room 3, … | Console type, shown wherever Resource information appears |
| Billiards | Pool Table 1, Pool Table 2, … | — |
| Ping Pong | Table 1, Table 2, … | — |

Supported PlayStation console types are **PS5** and **PS4**.

## 3.3 Play mode

PlayStation requires **Single** or **Multiplayer**. This is a *pricing mode*, not an exact player count. It does not apply to Billiards or Ping Pong.

---

# 4. PRICING MODEL

## 4.1 PlayStation

Priced on **console type + play mode**, set as Venue-level defaults. Illustrative approved example:

| Console | Single | Multiplayer |
|---|---:|---:|
| PS5 | EGP 170/hr | EGP 200/hr |
| PS4 | EGP 150/hr | EGP 170/hr |

A PlayStation Room may carry a **Room Price Override** replacing the Venue default for that room. Without an override the room inherits the console default.

## 4.2 Billiards and Ping Pong

Venue-level hourly price only. No play mode, no console type, and **no table-level override** in this release.

## 4.3 Price calculation

`amount = hourlyPrice × nominalBookedMinutes / 60`

Rounded **half-up to 2 decimal places** per line item. Totals are sums of rounded line items. All EGP values use 2 decimal places.

Example: EGP 135/hour × 1.5 hours = EGP 202.50.

The stored historical amount is immutable.

## 4.4 Nominal duration governs price

Online Booking, Manual Booking and Tournament all price on the **selected nominal booked duration**, never on DST-anomalous real elapsed duration. A Customer who selects 6 hours pays for 6 hours, and receives 6 real elapsed hours of Resource occupancy (`endAt = startAt + nominalBookedMinutes`).

---

# 5. MONEY MODEL

## 5.1 Booking Fee

E7GZ PS charges **one global Booking Fee per Booking** — not per hour, per venue or per activity.

**Launch value: EGP 0**, displayed to the Customer as FREE.

Each Booking stores the fee applied at creation. Changing the global fee later must never alter historical Bookings. Late Confirm reuses the original stored Booking Fee.

Future non-zero example: Venue Price EGP 170 + Booking Fee EGP 20 = Total EGP 190, of which EGP 20 is paid now and EGP 170 at the venue.

## 5.2 Venue gaming charge

The venue's gaming amount is **settled directly between Customer and Venue**. It never passes through E7GZ, and never through the Wallet.

## 5.3 Tournament money

Entry Fee and Prize are **informational only**. E7GZ does not collect entry fees, hold prizes or distribute prizes.

Venue Tournament Cost is calculated and stored for the record but is **informational in this release**; the organizer settles with the Venue outside E7GZ.

**Tournament Creation Fee** launch value: **EGP 0**, configurable.

## 5.4 Payment method tracking

The MVP does not track cash, card, Visa, Mastercard or payment terminals. The Venue Dashboard is not a POS, accounting system, ERP, tax system or payment reconciliation system.

## 5.5 Wallet

Every User has an E7GZ PS Wallet with an initial balance of EGP 0. It supports Booking Fee refunds and future Points conversion. It never pays the venue gaming charge.

Because the Booking Fee is EGP 0 and Points conversion is inactive, the **Customer launch UI does not show an active Wallet screen**, and **Platform Admin Wallet adjustment is not a launch action**. The ledger and data model exist so Wallet can be activated later without redesigning core ledger architecture.

## 5.6 Points

| Event | Points | Verified Player Progress |
|---|---:|---:|
| Completed Booking (attended) | +200 | +1 |
| Referral signup | +1,000 | 0 |
| No-show | 0 | 0 |
| Cancelled | 0 | 0 |
| Rejected | 0 | 0 |
| Review | 0 | 0 |

Points and Trust are separate systems. Future Points-to-Wallet conversion (illustratively 2,000 Points → EGP 20) is configurable and not active at launch.

**Points balance may never be negative.** Minimum effective balance is 0. See §46.

## 5.7 Referral

A User receives a Referral Code and Link. The reward becomes eligible only after the referred person **completes Account OTP verification**, not merely on record creation.

Reward value is read at the moment of successful OTP verification and stored permanently on the transaction. Only one reward per referred User. Self-referral is not allowed.

## 5.8 Venue revenue

The Venue Dashboard tracks Online Booking Revenue, Manual Booking Revenue and Tournament Revenue, with optional activity breakdown. Only **COMPLETED** Tournaments contribute Tournament Revenue; cancelled and rejected ones do not.

All revenue aggregates on **businessDate** (§17.5).

## 5.9 Historical value integrity

Configuration changes affect **future events only**. Historical transactions keep their original values, including Booking Fee, Referral Reward, Booking Price, Tournament values applied at creation, Late Confirm original totals, Points actually granted, console type, play mode, resource identity and resource name snapshot.

---

# 6. NOT IN THIS RELEASE

The following must not appear in the Customer App, Venue Dashboard, Platform Admin or the current architecture:

PC Gaming · Kick Off · Favorites · Community Feed · Elo · Player Leaderboards · Match History · Tournament Brackets · Tournament Score Tracking · Tournament Payment Collection · Prize Settlement · Food / Drink / Snack Ordering · Venue POS · Inventory · Supplier Management · Payroll · Employee Attendance · Individual Cashier Accounts · Venue Manager Accounts · Separate Venue Owner Accounts · Full Accounting · Tax Accounting · Multi-branch Venue Account Management

---

# PART II — IDENTITY, ACCOUNTS AND TRUST STATUS

---

# 7. CUSTOMER REGISTRATION AND VERIFICATION

## 7.1 Registration fields

| Field | Required | Unique |
|---|---|---|
| Full Name | Yes | No |
| Username | Yes | Yes |
| Phone Number | Yes | Yes |
| Email Address | Yes | — |
| Password | Yes | — |

Public Reviews display **Username only**. Platform Admin may see both Full Name and Username.

Username uniqueness is evaluated on a normalized form: surrounding whitespace trimmed, comparison case-insensitive. Storage and index implementation is an architecture choice.

## 7.2 Account Verified

**Phone OTP verification is mandatory for account activation.** An account becomes Account Verified only when `phone_verified = true`.

Email remains mandatory registration data. Email verification may occur separately and supports email ownership verification, account recovery and email notifications. An unverified email does **not** prevent Booking once phone verification is complete.

**Email OTP alone does not activate a Customer account for marketplace actions.**

Account Verified and Verified Player are different concepts.

## 7.3 OTP security

- OTP valid for 5 minutes
- Maximum 5 attempts per OTP
- Resend available after 60 seconds
- Maximum 5 OTP sends per hour per destination
- A new OTP invalidates the previous OTP

The OTP channel for mandatory phone verification is **SMS**.

## 7.4 Unverified users

A User who has registered but not completed OTP verification may browse and view venues, but may **not** create a Booking, join a Tournament, create a Tournament or write a Review.

## 7.5 Profile changes

| Field | Rule |
|---|---|
| Full Name | Editable |
| Username | Editable; must remain unique; **30-day change cooldown** |
| Phone | Change requires verification of the new phone |
| Email | Change requires verification of the new email |

Historical entities reference the immutable User ID. Reviews display the **current** Username.

## 7.6 Password reset

Customers reset a forgotten password using verified Phone OTP or email verification. The old password is not required.

Venue Login Password and Owner Password resets are handled through Platform Admin. No Venue self-service forgot-password flow exists in this release.

## 7.7 Account deletion

The Customer Mobile App provides an in-app **Delete Account** action. An authenticated Customer may use it to **initiate** deletion of their own account. Initiation creates one durable deletion request and does not directly hard-delete retained Product history. If an unresolved request already exists for that User, the same request remains authoritative rather than creating a second deletion operation.

The existing Support / Platform Admin deletion/anonymisation process completes the request. Completion supports: account state DELETED, login disabled, removal or anonymisation of PII where appropriate, preservation of legally and operationally required Booking / financial / audit history, removal of that User's Reviews from public display, and the existing Tournament `ACCOUNT_DELETED` participant consequence.

There is no Customer-side hard-delete path that bypasses this audited completion process, and this baseline defines no self-service cancellation/undo deadline or automatic completion deadline for the deletion request.

---

# 8. VERIFIED PLAYER

## 8.1 Purpose

Verified Player is a trust status earned through successful platform usage. It is required **only** for Player-hosted Tournament creation. It is not required to browse, create a normal Booking, or join a Tournament, provided the User is not banned.

## 8.2 Qualification threshold

**10 Completed Bookings.** All ten may be at the same Venue. Progress is displayed as, for example, "6 / 10 Completed Bookings".

Manual Bookings and Tournament participation **do not** count toward Verified Player Progress.

## 8.3 Two distinct concepts

Verified Player follows the same separation principle as Ban.

**A. Verified Player Qualification** — derived from corrected Trust history. Values NOT_QUALIFIED or QUALIFIED, with a historical `qualificationAt` based on the Trust event sequence.

**B. Operational Verified Player Status** — used by Product authorization. Values **NOT_VERIFIED · VERIFIED · SUSPENDED · REVOKED**. Only **VERIFIED** may create a Player-hosted Tournament.

## 8.4 First qualification

When the User reaches the threshold through normal live processing: Qualification → QUALIFIED, Operational Status → VERIFIED, and an immutable `verifiedPlayerTransitionId` is created.

## 8.5 Retroactively discovered qualification

If a Trust correction discovers today that the User historically qualified earlier, historical qualification may show `qualificationAt` at the historical Trust position, but operational activation occurs **now**, at correction processing time. The Product does not back-date operational Tournament privileges. A new `verifiedPlayerTransitionId` must be created for this activation too — every operational transition has an ID.

## 8.6 Loss of qualification through Trust correction

If replay determines a currently VERIFIED User no longer qualifies: Operational Status VERIFIED → REVOKED, source `TRUST_CORRECTION`, with one unique `verifiedPlayerTransitionId`.

## 8.7 Automatic regain

If the User was REVOKED **solely** because of `TRUST_CORRECTION` and later Trust replay restores qualification, the system **must** transition REVOKED → VERIFIED automatically, creating a new `verifiedPlayerTransitionId`. No Admin action is required in this case.

## 8.8 Admin states remain protected

Admin SUSPENDED and Admin REVOKED are explicit operational actions and are never overridden by later Trust qualification. They require explicit Admin Restore, which returns the User to:

- **VERIFIED** if currently qualified
- **NOT_VERIFIED** if not currently qualified

A reason is required for every Admin Verified Player moderation action.

## 8.9 Tournament cascade on revocation

A transition from operational VERIFIED to REVOKED executes:

| Tournament state | Outcome |
|---|---|
| PENDING_VENUE_APPROVAL | → CANCELLED |
| WAITING_FOR_PLAYERS | → CANCELLED |
| CONFIRMED | remains |
| ACTIVE | remains |

Reason: `CREATOR_VERIFIED_STATUS_REVOKED`

This cascade executes **once per `verifiedPlayerTransitionId`**. Repeated Trust replay must not repeat it. Restoring status never resurrects cancelled Tournaments.

**Verified Player Suspension** prevents creation of new Player-hosted Tournaments while suspended but does **not** cancel existing PENDING_VENUE_APPROVAL, WAITING_FOR_PLAYERS, CONFIRMED or ACTIVE Tournaments.

If Trust replay removes Verified Player eligibility because prior attendance data was corrected, treat the loss like **revocation** for cascade purposes.

## 8.10 As-of queries

Two historical questions are separate and may legitimately differ:

- **Qualification as-of** — was the User qualified according to corrected Trust history?
- **Operational status as-of** — what Verified Player status was actually in force?

Historical qualification does not imply historical operational tournament privileges.

---

# 9. BANNED USER PERMISSIONS

A banned User **may**:

- Log in, browse, view venues
- View Booking History, Account Standing and Profile
- Cancel an existing future Booking
- Decline a Late Confirmation
- Write, edit and delete Reviews

A banned User **may not**:

- Create a new Booking
- **Accept** a Late Confirmation
- Join a Tournament
- Create a Tournament

Accepting a Late Confirmation is treated as creating or restoring an active reservation and is therefore blocked during a Ban.

For what happens to in-flight Bookings when a Ban activates, see §48.6.

---

# PART III — VENUE MODEL

---

# 10. VENUE ACCOUNT AND OWNER PASSWORD

## 10.1 Account model

Each Venue has **one shared Venue Account**. This release has no cashier subaccounts, manager accounts, separate owner account or staff roles matrix.

## 10.2 Owner Password

The Venue has a separate **Owner Password**. It is not an account login; it protects sensitive actions.

Owner Password is required **per protected action**. There is no 15-minute unlock, no 30-minute unlock and no Owner Session mode. Each protected action requires fresh validation.

Successful and failed attempts are audited, and validation is rate-limited (exact thresholds are a security-architecture choice). Platform Admin may reset the Owner Password without displaying the old value, and the Venue is notified when this happens.

When the Venue changes its Owner Password, the new password becomes active immediately, the old one stops working, and all future protected actions use the new one.

## 10.3 Canonical Owner-Password-protected actions

- Venue pricing changes
- Room Price Override; Reset Room Price Override
- PlayStation Console Change (PS4 ↔ PS5)
- Cancel CONFIRMED Online Booking
- Opening Hours save that cancels protected Customer Bookings
- Normal Closure save that cancels protected Customer Bookings
- Disable Resource; Re-enable Resource
- Archive Resource; Restore Resource
- Change protected Resource configuration where defined
- Maintenance exceeding the rolling threshold (§39.2)
- Venue cancellation of a CONFIRMED Tournament
- Tournament Operational Termination
- Resolving `OWNER_REVIEW_REQUIRED`

## 10.4 Explicitly NOT Owner-Password-protected

- Accept Booking; Reject Booking; Reject After Expiry
- Late Confirm
- Manual Booking; Cancel Manual Booking before Start
- Check-in; Mark Present — Late; No-show
- Maintenance within the allowed threshold
- Opening Hours edit with no protected cancellation
- Emergency Closure
- Resource Emergency Outage
- Venue cancellation of a WAITING_FOR_PLAYERS Tournament
- Any automatic System Operational Cascade, including the `OWNER_REVIEW_REQUIRED` cascade and Emergency-driven Tournament cancellation

All of these remain audited.

---

# 11. VENUE MARKETPLACE STATUS AND ACCOUNT STATUS

These are **two independent dimensions**. Neither is inferred from the other.

## 11.1 Marketplace Status

### ONBOARDING
Not Customer-discoverable · no Online Booking · no Player Tournament requests · Admin/Venue configuration may continue · not operational marketplace inventory.

### ACTIVE
May appear in Discovery · may receive Online Bookings when all other conditions pass · may receive Player Tournament requests · normal marketplace operation.

### INACTIVE
Hidden from normal Discovery · no new Online Booking · no new Player Tournament requests · Venue Dashboard remains available · Manual Booking remains available · existing Bookings and Tournaments remain · historical data remains. **Non-punitive** marketplace deactivation.

### SUSPENDED
Hidden from normal Discovery · no new Online Booking · no new Player Tournament requests · no new marketplace Tournament creation · Venue Dashboard accessible if the Account is enabled · Manual Booking available · existing Bookings and Tournaments remain · Reviews remain visible and new Reviews remain allowed. **Administrative / punitive** marketplace state.

Deep-linked Venue Details may remain viewable for INACTIVE and SUSPENDED venues, but Booking actions are disabled.

## 11.2 Venue Account Status

### ENABLED
Normal Venue Dashboard operational access.

### DISABLED
Venue Dashboard login is disabled, which removes the Venue's operational ability to act. While DISABLED:

- Venue cannot Accept
- Venue cannot Reject
- Venue cannot initiate Late Confirm
- Venue cannot perform ordinary Dashboard operational actions, including Check-in, Mark Present — Late and Mark No-show

Disabling the Account does **not** automatically delete the Venue, hide it, change Marketplace Status, cancel existing Bookings or change existing Availability.

**Account Disable is an access restriction, not a physical venue outage.** Existing commitments remain governed by their own operational lifecycle rules. The full treatment across the Booking and Tournament lifecycles is §32 and §37.

## 11.3 Marketplace state creates no Venue blame

SUSPENDED or INACTIVE Marketplace Status alone does not prevent management of an already-existing Booking, does not require a new expiry reason, and never causes `VENUE_DID_NOT_RESPOND_IN_TIME` merely because the Platform stopped new acquisition.

**Account DISABLED does create an overriding platform cause.** It actually prevents Dashboard response, so it must never be represented as ordinary Venue non-response. The closed `VENUE_ACCOUNT_DISABLED` reason exists for this.

---

# 12. AREAS

Areas are controlled by Platform Admin. Examples: New Cairo, Nasr City, Heliopolis, Sheraton, Maadi, 6th of October.

Status is **Active** or **Inactive**. Inactive Areas remain in historical data; only Active Areas are available for new assignments.

**An Area may not be deactivated** while any Venue assigned to it has Marketplace Status ACTIVE or SUSPENDED. Those Venues must first move Area or become INACTIVE. Historical references remain unchanged.

Area status does **not** block Manual Booking, which is an internal Venue operation.

---

# 13. RESOURCES

## 13.1 Resource Base State

**ACTIVE · DISABLED · ARCHIVED**

Historical Resources are never hard-deleted.

## 13.2 Resource Operational Restriction

**NONE · OWNER_REVIEW_REQUIRED**

`OWNER_REVIEW_REQUIRED` is **not** a Base State. A Resource may have `baseState = ACTIVE` while `operationalRestriction = OWNER_REVIEW_REQUIRED`, and in that combination it is not sellable or bookable.

While restricted, the Resource cannot receive an Online Booking, Manual Booking, Tournament Hold or Tournament Approval Hold, cannot be used for Late Confirm, cannot appear as available, cannot contribute to Nearest Availability, and cannot return to normal marketplace availability. Resolving it requires Owner Password.

## 13.3 Canonical Resource eligibility

Any Product flow requiring an operational Resource requires **both**:

```
resource.baseState = ACTIVE
resource.operationalRestriction = NONE
```

This applies to Online Booking, Manual Booking, Late Confirm, Tournament creation, Tournament Approval Hold, Tournament Resource assignment and Nearest Availability.

## 13.4 Resource state changes

**Disable · Archive · Console Change** each require Owner Password and are **blocked** by any current or future blocking reservation, meaning any whose interval has not completely ended:

- CONFIRMED Booking
- ACTIVE Booking
- PENDING Online Booking
- AWAITING_CUSTOMER_CONFIRMATION Booking
- Player Tournament Approval Hold
- Tournament Hold
- Resource Maintenance

`VENUE_RESPONSE_EXPIRED` does **not** block, because it holds no Resource reservation. Terminal Booking history does not block. A normal Venue Closure alone does **not** block these configuration actions, because a Venue-wide Closure is not a Resource-level future reservation.

Existing Bookings are **never** automatically cancelled to permit a configuration change; the Venue must resolve them first through the normal Product flow.

Archive never deletes historical Bookings. Re-enable and Restore both require Owner Password.

## 13.5 Console change

A PlayStation Resource console may change PS4 ↔ PS5 only when the Resource has no current or future reservations. The change requires **Owner Password**.

On change, all existing **Room Price Overrides are cleared** and the room returns to the Venue default pricing for the new console. The Owner may then create a new override.

Historical Bookings retain their stored console type, play mode and historical price.

## 13.6 Emergency states and configuration

Emergency Closure and Resource Emergency Outage **block availability** during their active interval but do not by themselves forbid Disable, Archive or Console Change, provided Owner Password requirements pass, all remaining future reservations have been resolved, and applicable Maintenance restrictions pass.

## 13.7 Resource audit

Disabled, Re-enabled, Archived and Restored are audited Owner-protected Venue actions. Maintenance is audited but not Owner-protected within threshold.

---

# 14. OPENING HOURS AND OPERATING SESSIONS

## 14.1 Model

Opening Hours use **Default Schedule + weekday overrides**. Each weekday may use the default, use custom hours, or be closed. Cross-midnight schedules are supported.

Example — Default 12 PM → 4 AM; Monday 2 PM → 3 AM; Tuesday Closed; Wednesday use Default.

Editing Opening Hours does **not** itself require Owner Password.

## 14.2 Operating Day and Operating Session

Opening Hours are interpreted by **Operating Day**, not by calendar day.

If Monday's hours are 12:00 PM → 4:00 AM, a Booking on Tuesday at 2:00 AM belongs to **Monday's Operating Session**.

Each Operating Day creates at most **one Operating Session**, identified by Operating Day, local start date/time, local end date/time and the Africa/Cairo timezone.

If Tuesday is Closed there is no Operating Session that *starts* on Tuesday, but an overnight Monday session may still legally continue into early Tuesday morning. Tuesday being Closed only prevents a new Tuesday session from starting.

## 14.3 Session validity

- Length greater than 0 and no more than 24 hours
- A 24-hour session may be represented explicitly as a full-day session
- Sessions may touch at boundaries but **may not overlap**
- Non-overlap validation is **cyclical across the full week**, including the Sunday → Monday wrap

The 24-hour maximum is a **local wall-clock template rule**. A declared full-day session remains valid even when a DST transition makes the real elapsed interval 23 or 25 hours; absolute instants remain authoritative for actual Booking duration.

## 14.4 Timezone and DST

Venue timezone is **Africa/Cairo**, with valid IANA rules including DST changes. Absolute instants are authoritative for inventory, start/end instants and scheduler execution.

Non-existent local start times caused by a DST transition are not offered as selectable Start Times. Ambiguous repeated local start times are not offered as separate duplicate Customer-selectable slots.

Customer and Venue price and duration remain the **nominal** duration agreed at creation (§4.4). A Booking's absolute interval `[startAt, endAt)` is validated against the Operating Session's absolute interval; if the real duration cannot fit, the Booking is not offered or accepted.

## 14.5 Booking within a session

Every **Online Booking** and every **Tournament** must fit completely within one Operating Session. A Booking may cross midnight. It may **not** cross a Closure, a closed gap, or the end of one Operating Session into another disconnected session.

**Manual Booking is exempt** from this requirement (§30.3).

## 14.6 Operating Session historical snapshot

Booking history stores Operating Day, local session start, local session end and timezone as historical snapshots. Later Opening Hours changes never rewrite the Operating Session associated with historical Bookings.

## 14.7 Opening Hours change — evaluation order

The update is **all-or-nothing**:

1. Validate proposed Operating Sessions
2. Check Tournament conflicts
3. If a Tournament conflict exists → **BLOCK immediately**
4. Compute affected ordinary reservations
5. Show complete impact preview
6. Determine Owner Password requirement
7. Request Owner Password if protected cancellations exist
8. Commit the Opening Hours change and all affected cancellations atomically
9. Send notifications

If any step fails, **nothing is changed**.

Before commit, the complete affected set must be **revalidated**. If new affected reservations have appeared since the preview, the commit is aborted and the Venue receives a refreshed impact preview; Owner Password must then be entered against the refreshed set. No Booking outside the approved preview may be silently cancelled.

## 14.8 Affected Bookings

Future Bookings in **PENDING**, **AWAITING_CUSTOMER_CONFIRMATION**, **CONFIRMED** and **MANUAL** are evaluated across all future dates, with the correct weekday computed from the actual future date.

**ACTIVE** sessions already in progress are not cancelled. **VENUE_RESPONSE_EXPIRED** holds no Resource reservation and is not cancelled; if Late Confirm is attempted later, the new Opening Hours are validated again.

Cancellation reason: `VENUE_OPENING_HOURS_CHANGED`. Resources are released, history remains, and online Customers are notified.

## 14.9 Owner Password on Opening Hours

Owner Password is required if the affected set includes a **CONFIRMED Online Booking** or an **AWAITING_CUSTOMER_CONFIRMATION Booking**. Cancelling only PENDING Online and Manual Bookings does not require it.

If an AWAITING_CUSTOMER_CONFIRMATION Booking is cancelled this way, the Late Confirmation timer stops immediately, the Resource Hold releases, the Booking becomes CANCELLED with reason `VENUE_OPENING_HOURS_CHANGED`, and the Customer receives an explicit cancellation notification. The old Late Confirmation action becomes invalid.

## 14.10 Tournaments and Opening Hours

An existing Tournament is **never** auto-cancelled by an Opening Hours change. If new hours conflict with an existing Tournament, the **Opening Hours save is blocked** and the Venue must explicitly resolve or cancel the Tournament first.

A Tournament in PENDING_VENUE_APPROVAL with active Approval Holds counts as a Tournament conflict for this purpose.

A Tournament's requested interval must fit completely within current Venue Opening Hours; Tournaments cannot be created outside operating hours.

---

# 15. CLOSURES

## 15.1 Normal Closure

A Venue Closure may be **full-day** or **partial-time**, applies to the entire Venue, and blocks all Resources during its interval. Example reasons: emergency closure, private event, venue maintenance, utility outage.

Creating or editing a Closure does not require Owner Password **unless** it cancels protected Customer reservations. If it affects CONFIRMED Online Bookings or AWAITING_CUSTOMER_CONFIRMATION Bookings, the final "Save & Cancel Affected Bookings" requires **Owner Password**. Affected Bookings are cancelled with reason `VENUE_CLOSURE` and Customers are notified.

A Closure **may not** be created retroactively over an ACTIVE session. It must begin after existing active sessions end unless those have been resolved through an allowed Product flow.

A Closure may **not** automatically cancel an existing Tournament. If a proposed Closure conflicts with one, **the Closure save is blocked** until the Venue explicitly resolves or cancels that Tournament.

## 15.2 Closure and Maintenance overlap

A Venue Closure **may** overlap an existing Maintenance window, because the Closure affects the whole Venue while Maintenance remains a valid Resource-level historical record. Creating new Maintenance over an existing Closure is unnecessary and is blocked for the overlapping period.

---

# PART IV — THE AVAILABILITY ENGINE

---

# 16. CANONICAL AVAILABILITY

## 16.1 Three distinct questions

The Product keeps these separate and never conflates them:

- **A.** Is the Resource interval free?
- **B.** Is this Online Booking allowed?
- **C.** Is this Tournament or operational action allowed?

## 16.2 Canonical availability inputs

Availability evaluates, in this order:

1. Venue Operational Selling Block
2. Resource Operational Selling Block
3. Resource Base State
4. Resource Operational Restriction
5. Emergency type, status, affected scope and declared interval
6. Maintenance
7. Closure
8. Blocking Reservations

An Operational Selling Block is a **higher-level availability restriction**. It does not require a fabricated future reservation interval, and Availability evaluates it **explicitly before** Resource reservation checks. An unresolved Emergency is never treated as an infinitely extended reservation interval.

## 16.3 Resource interval free — canonical predicate

A Resource interval is **not free** if the Resource is DISABLED or ARCHIVED, or if `operationalRestriction = OWNER_REVIEW_REQUIRED`, or if a relevant Operational Selling Block is active, or if the interval overlaps an active blocking reservation from:

- Pending Online Booking
- Awaiting Customer Confirmation Booking
- Confirmed Online Booking
- Active Online Booking
- Manual Booking (CONFIRMED or ACTIVE)
- Player Tournament Approval Hold
- Tournament Hold
- Maintenance
- Venue Closure
- Emergency Closure
- Resource Emergency Outage

**`VENUE_RESPONSE_EXPIRED` does not block inventory**, because its Resource reservation has been released.

**Booking Source does not determine whether a Booking blocks. Booking Status does.** For `source = MANUAL`, only CONFIRMED and ACTIVE are normally reachable blocking states.

Terminal Bookings never continue blocking Resource inventory.

## 16.4 Double booking

**Double booking is never allowed.** All products use this one shared availability logic.

## 16.5 Nearest Availability

"Nearest Availability" on a Venue Card means the earliest valid Start Time for the **currently selected Activity**, assuming the **minimum booking duration of 1 hour**.

The calculation considers Opening Hours, eligible Resources, the canonical availability rule, the current time and the 15-minute latest-booking rule. For PlayStation, before specific room or console filtering, it uses the earliest valid PlayStation Resource.

Resources blocked at the Venue level or the Resource level, and Resources in `OWNER_REVIEW_REQUIRED`, are **ignored** by Nearest Availability.

---

# 17. HISTORICAL OCCUPANCY

## 17.1 Immutability

Historical records are immutable. Past Booking records are not edited to pretend they never existed, past intervals are not back-dated into new Manual Bookings, and original planned intervals remain visible in history.

## 17.2 Planned interval vs active reservation

Immutability does **not** mean planned intervals can never overlap after a legitimate early Resource release.

When a NO_SHOW releases the remaining Resource interval, the original Booking remains historically 8:00–9:00 while its active reservation ended at, say, 8:16. A later valid Booking may then start at 8:20.

Therefore:

- **Booking historical intervals may overlap.**
- **Active reservation occupancy must never overlap.**

## 17.3 Reservation history

Reservation history stores `plannedStart`, `plannedEnd`, `actualReservationStart`, `actualReservationReleaseAt` and `releaseReason`, which allows historical availability replay to explain overlapping planned intervals without representing a double booking.

## 17.4 Race resolution

For two simultaneous valid actions competing for the same state or inventory: **the first successfully committed valid transaction wins.** Once one transaction changes the authoritative state or reservation, any later transaction must revalidate and fail if it is no longer valid.

This applies to Venue Accept versus another state mutation, Late Confirm versus a new Booking, two Customers booking the same Resource, two Tournament requests, and Tournament Join versus cancellation.

## 17.5 businessDate

Every revenue-producing record — Online Booking, Manual Booking and Tournament — stores a **businessDate**, which is the canonical revenue-day key for all Venue Dashboard aggregation.

| Record | businessDate |
|---|---|
| Online Booking | Operating Day date of its Operating Session |
| Tournament | Operating Day date of its Operating Session |
| Manual Booking inside an Operating Session | Operating Day date |
| Manual Booking outside published Operating Sessions | Africa/Cairo local calendar date of `startAt` |

businessDate is stored as a historical snapshot. Later Opening Hours changes never rewrite it.

## 17.6 Timer boundary rule

Actions allowed "before" a deadline require `now < deadline`. At `now >= deadline` the expiry transition owns the state — **expiry wins**.

This applies to the Venue 10-minute response, Late Confirmation, Customer cancellation at Start, Tournament Join/Leave at Start, and every equivalent timer boundary.

Example: response deadline 8:50:00. An Accept committed before 8:50:00 is valid; one arriving at or after 8:50:00 is not.

---

# PART V — THE BOOKING LIFECYCLE

---

# 18. BOOKING MODEL

## 18.1 Status and Source

**Booking Status** and **Booking Source** are separate fields. `MANUAL` is exclusively a Source, never a Status.

## 18.2 Canonical Booking states

```
PENDING
VENUE_RESPONSE_EXPIRED
AWAITING_CUSTOMER_CONFIRMATION
CONFIRMED
ACTIVE
COMPLETED
REJECTED
CANCELLED
NO_SHOW
```

**Terminal:** COMPLETED · REJECTED · CANCELLED · NO_SHOW

**`VENUE_RESPONSE_EXPIRED` is explicitly non-terminal.** It has released its Resource but remains revivable through Late Confirm until the original Start Time. Any wording elsewhere describing it as terminal is superseded.

## 18.3 Customer-facing projection

| My Bookings tab | Statuses |
|---|---|
| Upcoming | PENDING · AWAITING_CUSTOMER_CONFIRMATION · CONFIRMED · ACTIVE |
| Completed | COMPLETED |
| Cancelled | VENUE_RESPONSE_EXPIRED · CANCELLED · REJECTED · NO_SHOW |

`VENUE_RESPONSE_EXPIRED` displays under Cancelled with wording such as "Venue did not respond in time", while remaining internally eligible for Late Confirmation. There is no pre-emptive Customer opt-out. If the Venue later requests Late Confirmation, the Customer receives a new explicit reactivation request and must actively accept.

An AWAITING_CUSTOMER_CONFIRMATION Booking appears as an actionable Booking showing **Respond Now** and the remaining time.

---

# 19. BOOKING PARAMETERS

| Parameter | Value |
|---|---|
| Minimum duration | 1 hour |
| Increment | 30 minutes |
| Maximum Online Booking duration | 6 hours |
| Maximum Manual Booking duration | 6 hours |
| Latest new Booking | at least 15 minutes before Start Time |
| Venue response time | 10 minutes |
| Late Confirm Customer window | 5 minutes, capped at original Start Time |
| Late Confirm valid until | original Start Time |
| Maximum upcoming Online commitments per Customer | 4 |

## 19.1 Customer commitment limit

One Customer may hold a maximum of **4 active or upcoming Online Booking commitments** across all Venues, counting PENDING, AWAITING_CUSTOMER_CONFIRMATION, CONFIRMED and ACTIVE.

Not counted: VENUE_RESPONSE_EXPIRED, CANCELLED, REJECTED, COMPLETED, NO_SHOW. Manual Bookings never count against this limit.

The limit must be checked at **every action that would increase the count**, including new Booking creation, Late Confirm initiation and Late Confirm Accept.

## 19.2 Customer overlap rule

A Customer may not hold **overlapping** active Online Booking commitments. The blocking states are PENDING, AWAITING_CUSTOMER_CONFIRMATION, CONFIRMED and ACTIVE. Terminal and expired states do not block.

This is checked on new Online Booking and on Customer acceptance of a Late Confirmation. The 4-commitment limit is a total-count limit **in addition to** this no-overlap rule.

---

# 20. DISCOVERY AND CUSTOMER FLOW

## 20.1 Discovery flow

Area / Location → Activity → Venue → Resource → Availability → Booking

Activity selection happens **before** Venue discovery.

## 20.2 Location and Area

If Location is enabled and the User has not manually selected an Area, show eligible Venues across all Active Areas sorted primarily by distance. If the User manually selects an Area, that Area becomes a **hard filter** even while Location permission remains enabled.

If Location is disabled, the Customer manually selects an Area.

## 20.3 Venue Card

Venue image · name · Area · distance when available · starting price · Nearest Availability · average rating · review count.

## 20.4 Map View

Venue markers with a preview containing name, distance, starting price, Nearest Availability, average rating, review count and View Venue.

## 20.5 Venue Details

Images · name · Area · address · distance · Opening Hours · rating · reviews · View Location · selected Activity · Resources · pricing · availability.

## 20.6 Cross-activity discovery

When a Venue supports multiple activities, show **"Also available at this venue"** listing the others. Selecting another activity keeps the same Venue and changes the Resource context.

## 20.7 Closed venues remain discoverable

A currently closed Venue is **not** removed from Discovery merely because it is closed. The Customer may view the Venue, its activities, future availability, prices and Nearest Availability, and the UI should show concepts such as "Closed Now" and "Opens at 12:00 PM".

The Customer simply cannot submit an Online Booking Request until the Venue's next valid Online Intake Window. The same applies on a day with no Operating Session at all.

## 20.8 Normal Booking flow

Area → Activity → Venue → Resource → (Single / Multiplayer if PlayStation) → Date → Start Time → Duration → Booking Summary → Confirm

## 20.9 Find Available

Search fields: Area, Activity, Date, Start Time, Duration. Results contain only Resources available for the **entire** requested interval. There is no price filter in this release.

## 20.10 Venue phone privacy

Before Booking creation the Venue phone is hidden. Once a Booking enters **PENDING**, the Customer may see the Venue phone and the Venue may see the Customer phone. Full retention rules are §34.

---

# 21. ONLINE INTAKE WINDOW

A Customer may submit an Online Booking Request only while the Venue is **currently operating** according to its current Operating Session. This applies even when booking a future date.

In addition, **new Online Booking intake stops 10 minutes before the current Operating Session ends.**

Example: a session ending at 4:00 AM accepts its last Online Booking Request before 3:50 AM. This guarantees the Venue's full 10-minute response window occurs while the Venue is operating.

Being open at the moment of creation does not make every future slot valid: the requested future interval must independently fit its future Operating Session, pass the availability rules and pass the 15-minute lead-time rule.

**Late Confirm is not restricted by the Online Intake Window** (§25.6).

---

# 22. ONLINE BOOKING CREATION — CANONICAL PREDICATE

A new Online Booking requires **all** of the following:

**Customer**
1. Account Verified by Phone OTP
2. Customer not banned
3. Customer below the maximum of 4 active/upcoming Online Bookings
4. No overlapping Customer Online commitment

**Venue**
5. Venue Marketplace Status = ACTIVE
6. Venue Account = ENABLED
7. Area = ACTIVE
8. Venue currently inside an Operating Session
9. Current time at least 10 minutes before the current Operating Session ends
10. Venue Operational Selling Block = FALSE

**Timing**
11. Requested Start at least 15 minutes in the future
12. Requested duration between 1 and 6 hours
13. Duration in 30-minute increments
14. Requested interval fits entirely inside one future Operating Session

**Resource**
15. Resource Base State = ACTIVE
16. Resource Operational Restriction = NONE
17. Resource Operational Selling Block = FALSE
18. No overlapping blocking Resource reservation
19. Activity and Resource configuration compatible

20. All other required Product validation passes.

All conditions must pass. This list is **cumulative**, not a replacement for any earlier condition.

---

# 23. PENDING AND VENUE RESPONSE

## 23.1 Pending

On Customer confirmation the Booking becomes **PENDING** and the Resource is blocked immediately. The Venue has **10 minutes** to Accept or Reject.

**There is no auto-accept.** If the Venue does not respond, the Booking does not become CONFIRMED.

## 23.2 Accept

PENDING → **CONFIRMED**. The Resource remains reserved.

Before committing, Venue Accept **must** revalidate, atomically:

- Venue existing-commitment eligibility (§35.2)
- Venue Operational Selling Block
- Resource Base State
- Resource Operational Restriction
- Resource Operational Selling Block
- Resource reservation validity
- Booking interval validity
- **Customer Account eligibility and Ban eligibility** — always, with no "where applicable" qualifier

Venue Accept does **not** require Marketplace Status ACTIVE, because the Booking already exists (§35).

## 23.3 Accept when the Resource became ineligible

If Accept fails because the Venue or Resource became unavailable through Venue Emergency, Resource Emergency or `OWNER_REVIEW_REQUIRED`, the Booking may **not** become CONFIRMED.

Where the ineligibility makes the Booking impossible to honour, the Booking transitions to CANCELLED using the applicable operational reason — `VENUE_EMERGENCY_CLOSURE`, `RESOURCE_EMERGENCY_OUTAGE` or `RESOURCE_OWNER_REVIEW_REQUIRED`. The Customer is notified. **No ordinary "venue did not respond" blame is applied.**

While a relevant Operational Selling Block is active, Venue Accept is disabled and the Emergency response-timer pause rules apply.

## 23.4 Reject

PENDING → **REJECTED**. A reason is required (examples: resource unavailable, maintenance, venue issue, other). The Resource is released.

## 23.5 Expiry

At `now >= venueResponseExpiresAt`, the expiry transition wins:

PENDING → **VENUE_RESPONSE_EXPIRED**

The Resource reservation is released immediately. History records: Booking Created → Pending → Venue Response Time Expired.

---

# 24. VENUE_RESPONSE_EXPIRED

## 24.1 Nature

Non-terminal. No Resource reservation is held. It does not block availability and does not count toward the Customer's 4-commitment limit. It remains eligible for Late Confirmation until the original Start Time.

## 24.2 Reject After Expiry

Before the original Start Time, the Venue may explicitly choose **Reject After Expiry**:

VENUE_RESPONSE_EXPIRED → **REJECTED**, reason `REJECTED_AFTER_EXPIRY`

This is a valid transition. "Rejected After Expiry" is a reason and history event, not a separate primary Booking status.

## 24.3 Resolution at original Start Time

If the Booking is still VENUE_RESPONSE_EXPIRED when the original `startAt` is reached, it becomes **CANCELLED** and can no longer be Late Confirmed. The reason is chosen by this priority:

1. `RESOURCE_OWNER_REVIEW_REQUIRED` — Resource still under Owner review
2. `RESOURCE_EMERGENCY_OUTAGE` — Resource still blocked by unresolved Resource Emergency
3. `VENUE_EMERGENCY_CLOSURE` — Venue still blocked by unresolved Venue Emergency
4. `VENUE_ACCOUNT_DISABLED` — Venue Account still DISABLED
5. `VENUE_DID_NOT_RESPOND_IN_TIME` — only when no overriding operational or platform cause exists

Attendance Result in every case: **NOT_APPLICABLE**.

## 24.4 Resource configuration while expired

Disable, Archive and Console Change may still occur while a Booking is only VENUE_RESPONSE_EXPIRED, because no Resource reservation is active. The system records the Booking Event `LATE_CONFIRM_ELIGIBILITY_LOST_RESOURCE_STATE_CHANGED` with the appropriate `detailCode` for affected expired requests. No additional Customer push is required, since the Booking already displays as expired.

`VENUE_RESPONSE_EXPIRED` is **not** included in the `OWNER_REVIEW_REQUIRED` reservation-release cascade, because it holds no reservation.

---

# 25. LATE CONFIRM

## 25.1 Classification

Late Confirm initiation is **existing-commitment operational management**, not new-activity marketplace acquisition. It uses the same Booking ID, creates no new Booking, and exists only to manage a previously created Customer request. It is therefore **not** on the New-Activity Marketplace Eligibility list.

## 25.2 Venue eligibility

Late Confirm requires:

- Venue Marketplace Status **ACTIVE, INACTIVE or SUSPENDED** — the Marketplace ACTIVE requirement is removed for this existing-commitment action
- Venue Account = **ENABLED**
- Area = ACTIVE
- Venue currently inside a valid Operating Session
- Venue operationally able to honour the Booking
- No Venue Operational Selling Block
- The original requested interval still fits valid Opening Hours
- Original Start Time still in the future

ONBOARDING Venues may not initiate Late Confirm.

## 25.3 Resource eligibility

- Resource Base State = ACTIVE
- Resource Operational Restriction = NONE
- No Resource Operational Selling Block
- Requested interval passes the canonical Resource conflict predicate

A DISABLED, ARCHIVED or `OWNER_REVIEW_REQUIRED` Resource cannot be Late Confirmed. While a Resource remains under Owner review, the system records `LATE_CONFIRM_ELIGIBILITY_LOST_RESOURCE_STATE_CHANGED` with `detailCode = RESOURCE_OWNER_REVIEW_REQUIRED`.

If the Owner clears the restriction before the original `startAt`, the expired request may again become eligible if all Late Confirm rules pass. **No automatic Late Confirm ever occurs.**

## 25.4 Customer eligibility at initiation

Before the Venue may transition VENUE_RESPONSE_EXPIRED → AWAITING_CUSTOMER_CONFIRMATION, the system checks the Customer's current counted commitments.

If the Customer already holds **4** commitments, Late Confirm initiation is **blocked**, the Booking remains VENUE_RESPONSE_EXPIRED, and no temporary Resource Hold is created.

The Venue receives a neutral message — "Customer is not currently eligible for Late Confirmation" — and is given **no details** of the Customer's other Bookings. Internal event: `LATE_CONFIRM_FAILED_BOOKING_LIMIT`.

## 25.5 Initiation is atomic

This sequence is **one atomic transaction**:

1. Read the Customer commitment count
2. Validate the limit
3. Validate the Resource
4. Create the temporary Late Confirm Hold
5. Transition VENUE_RESPONSE_EXPIRED → AWAITING_CUSTOMER_CONFIRMATION

The first successfully committed valid transition wins against the commitment limit. A concurrent transition must revalidate before commit and can never produce a temporary fifth commitment.

## 25.6 Intake window exemption

Late Confirm is **not** restricted by the Customer New Booking Intake Window. The Venue may initiate it while inside an Operating Session even if fewer than 10 minutes remain before that session ends.

A new Booking Request needs a full Venue response period; Late Confirm is itself a **Venue-initiated action**, so no new 10-minute Venue response window is needed. The Customer's own Late Confirmation timer remains governed by its own deadline rules.

## 25.7 Same Booking ID

Late Confirmation **does not create another Booking**. It keeps the same Booking ID, original Venue, Activity, Resource, console, play mode, date, time, duration, Venue price, Booking Fee and total.

## 25.8 Customer window

`lateConfirmExpiresAt = MIN(now + 5 minutes, booking.startAt)`

The confirmation window **must never extend beyond the original Start Time**. If the Venue Late Confirms at 7:59 PM for an 8:00 PM Booking, the Customer receives 1 minute, not 5.

Late Confirmation state is stored **per Booking**. Each Booking independently owns its expiry, warning flag, Customer response and Resource Hold. One User may have several simultaneous Late Confirmation requests, and they must never overwrite or interfere with each other.

## 25.9 Customer messaging

The wording must clearly communicate that the original request expired — for example, *"Game Zone is asking to reactivate your expired booking. Do you still want it?"* — with actions **Accept Booking** and **Decline**. There is no automatic reactivation.

If the confirmation window is **more than 60 seconds**, send the normal Late Confirmation notification plus one "Late Confirmation Expiring" notification approximately one minute before expiry, once per Booking, linked to the exact Booking ID.

If the total window is **60 seconds or less**, do not send a second expiring notification; the original notification must display the actual short countdown.

## 25.10 Customer response

| Response | Outcome | Reason |
|---|---|---|
| Accept before expiry | → CONFIRMED | — |
| Decline | → CANCELLED | `CUSTOMER_DECLINED_LATE_CONFIRM` |
| No response by `lateConfirmExpiresAt` | → CANCELLED | `CUSTOMER_RECONFIRM_TIMEOUT` |

All use the same Booking ID and all previous history is preserved. Declining or timing out carries **no penalty**.

## 25.11 Accept revalidation

Because the Booking already counts toward the maximum once it is AWAITING_CUSTOMER_CONFIRMATION, Accept does not normally increase the count. Accept still revalidates, to protect against races and Admin changes:

- The commitment count remains valid
- No Customer schedule conflict
- Resource reservation still valid
- Ban and Account eligibility
- Venue and Resource operational eligibility, including Operational Selling Block

## 25.12 Customer schedule conflict

For privacy, the Customer schedule conflict is checked **when the Customer attempts to accept**, never earlier. The Venue is not told that the Customer has another Booking and may freely send the Late Confirmation request.

If Accept conflicts with another active commitment, the Accept **fails**, the Booking Event `LATE_CONFIRM_FAILED_CUSTOMER_CONFLICT` is recorded, and the Customer sees an appropriate message. The Booking does not terminate under a generic schedule-conflict reason; any later terminal outcome uses its actual terminal reason.

## 25.13 Resource conflict at Late Confirm

Resource unavailability or conflict during Late Confirm is represented through validation failure, atomic transaction failure, or the Booking Event `LATE_CONFIRM_FAILED_RESOURCE_CONFLICT`. There is **no** terminal "late confirm resource unavailable" reason code.


---

# 26. CONFIRMED → ACTIVE → COMPLETED

## 26.1 Automatic transitions

Both transitions are backend/scheduler controlled. No Venue action is required for either.

| At | Transition | Condition |
|---|---|---|
| `startAt` | CONFIRMED → ACTIVE | start-time revalidation passes (§31) |
| `endAt` | ACTIVE → COMPLETED | Booking was not cancelled and was not marked No-show |

## 26.2 Start is not unconditional

A Booking may become ACTIVE only when the Venue and the Resource are **operationally able to honour the session** at `startAt`. The full revalidation list and the deterministic failure reasons are §31.

## 26.3 No normal early session end

There is **no ordinary "End Session Early" function**. A normal ACTIVE Booking continues to block its Resource until `endAt` even if the Customer leaves early or the equipment stops being used.

The only paths that may end an ACTIVE Booking early are:

1. Venue Emergency Closure (§40)
2. Resource Emergency Outage (§41)
3. Resource entering OWNER_REVIEW_REQUIRED (§43)
4. Platform Admin support correction (§47)

The Venue does **not** receive a generic Owner-Password "End Active Booking" action. This asymmetry against Tournament Operational Termination (§37.9) is intentional: a Tournament is an organised multi-participant event and needs an event-level termination capability; a single Booking does not.

## 26.4 Completion carries an Attendance Result

Reaching COMPLETED does not by itself mean the Customer attended. Every Booking carries a separate Attendance Result (§28) which determines Points, Verified Player progress and the No-show counter.

---

# 27. CHECK-IN, MARK PRESENT — LATE, AND NO-SHOW

## 27.1 Check-in is a Venue staff action

Check-in is the Venue's confirmation that the Customer actually attended. MVP deliberately does **not** require:

- QR code
- Customer PIN
- Customer device confirmation
- Biometric verification

Venue staff select **Check In** on the correct Booking. This intentionally trusts Venue operational input. Protection comes from audit, Platform Admin correction, Trust replay and abuse monitoring (§27.8) rather than Customer-side identity proof.

## 27.2 Check-in window

`startAt <= now < startAt + 15 minutes`

At exactly `startAt + 15 minutes` Check-in is no longer allowed. This boundary is the same instant at which No-show eligibility begins, so the two windows never overlap.

Check-in does not require Owner Password. It is audited and stores the Venue Account identity, the timestamp and the Booking ID.

## 27.3 Mark Present — Late

To stop a physically present Customer from being treated as a No-show merely because staff missed the Check-in window, the Venue has **Mark Present — Late**, available while:

`startAt + 15 minutes <= now < endAt`

and only when no valid Check-in exists and no No-show has already been committed.

Effects:

- Blocks the No-show action for that Booking
- Grants **no** Completed Booking Points
- Grants **no** Verified Player progress
- Does **not** reset the Consecutive No-show Counter

At End Time the Booking becomes COMPLETED with Attendance Result ATTENDANCE_UNCONFIRMED. The Customer was operationally present but missed the Product Trust Check-in window.

Mark Present — Late does not require Owner Password. It is audited with Booking ID, Venue Account identity and timestamp.

## 27.4 No-show window

`startAt + 15 minutes <= now < endAt`

No-show may be recorded only when no valid Check-in exists and no Mark Present — Late has been recorded. The backend may technically accept status CONFIRMED or ACTIVE where the time condition is satisfied, to avoid scheduler timing races.

No-show does **not** require Owner Password. Every No-show action is audited.

After End Time, if No-show was not recorded, the Booking becomes COMPLETED.

## 27.5 The choice at 15 minutes

At or after `startAt + 15 minutes` and before `endAt`, the Venue must choose according to the real situation:

| Situation | Action |
|---|---|
| Customer absent | NO_SHOW |
| Customer present | MARK PRESENT — LATE |

A Customer marked Late Present cannot later be marked No-show for the same Booking. Both actions are audited.

## 27.6 Late arrival after 15 minutes

After the first 15 minutes the Venue may still let the Customer play at its own operational discretion. The Booking cannot receive a Product Check-in and therefore cannot qualify as ATTENDED for Points or Verified Player progress.

## 27.7 No-show releases the Resource

When a Booking becomes NO_SHOW the **remaining Booking interval is released immediately**. The Resource may then be booked online, booked manually or used by another valid reservation under normal Availability rules.

The Booking is terminal. Venue staff cannot undo it. If the Customer disputes it, Platform Admin correction is required (§47). Reversing a No-show does **not** evict a later Customer who validly obtained the released slot.

## 27.8 Reversals

| Action | Who | Effect |
|---|---|---|
| Reverse Check-in | Platform Admin only | Event `CHECK_IN_REVERSED` |
| Reverse Mark Present — Late | Platform Admin only | Event `LATE_PRESENT_REVERSED` |
| Reverse No-show | Platform Admin only | Event `NO_SHOW_REVERSED` |

Venue staff can reverse none of them. Each reversal requires a mandatory reason and is audited.

**Check-in reversed while ACTIVE:** the Booking stays ACTIVE, the Check-in leaves the Trust calculation, and No-show becomes eligible again if the window still allows it. No Points or progress had yet been permanently granted, so nothing is clawed back.

**Check-in reversed after an ATTENDED completion:** the system creates an `ATTENDANCE_CORRECTED` event targeting the original Attendance Trust event and the Admin selects the corrected outcome. Previously granted Points are reversed through a compensating Points transaction (§46.3).

**Mark Present — Late reversed before `endAt`:** the Booking becomes eligible for No-show again if the window still allows it and the Customer is actually absent.

**Mark Present — Late reversed after `endAt`:** if the Admin determines the Customer was actually absent, they may issue `ATTENDANCE_CORRECTED → ABSENT_NO_SHOW`, and Trust replay applies the No-show at the Booking's Trust position.

**No-show reversal, before original `endAt`:** the Booking is terminal as CANCELLED with reason `ADMIN_NO_SHOW_REVERSAL` unless the Admin applies a further Attendance Correction. It does not automatically restore the released Resource and does not automatically grant rewards.

**No-show reversal, after original `endAt`:** the Booking becomes COMPLETED / ATTENDANCE_UNCONFIRMED unless the Admin separately establishes a valid Attendance outcome.

## 27.9 Attendance correction choices after a wrong No-show

The Admin chooses between two distinct outcomes based on evidence:

| Evidence | Choice | Attendance Result | Trust effect |
|---|---|---|---|
| Customer attended, session could not continue because the Resource was reallocated | ATTENDANCE CONFIRMED | ATTENDED_INTERRUPTED | Counter resets; no Points; no progress |
| Customer attended and actually completed the session with no replacement conflict | ATTENDANCE CONFIRMED | ATTENDED | Counter resets; Points; +1 progress |
| Penalty was invalid but attendance is not established | PENALTY REMOVAL ONLY | ATTENDANCE_UNCONFIRMED | No penalty, no reward, no reset |

## 27.10 Abuse monitoring

Because MVP Check-in has no Customer-side proof, Platform Admin receives **non-blocking operational signals**, including:

- Unusually high Check-in rate
- Unusually high Check-in reversal rate
- Unusual concentration of Verified Player progress from one Venue
- Repeated Check-ins immediately before the 15-minute deadline
- Unusually high Mark Present — Late rate, or repeated Late Present on the same Users
- Late Present reversals
- Suspicious repeated Customer/Venue patterns

Monitoring may flag, alert and surface a Venue or User for review. It never automatically revokes Verified Player status, suspends a Venue, reverses Check-ins or bans Users. Every consequence requires explicit Admin action.

---

# 28. ATTENDANCE RESULT

## 28.1 An independent stored field

Attendance Result is **not** permanently derived from Booking Status. It is an independent historical field, subject only to explicit audited Admin correction.

Canonical values:

| Value | Meaning |
|---|---|
| `ATTENDED` | Valid Check-in and normal completion |
| `ATTENDANCE_UNCONFIRMED` | Presence was not established through a qualifying Check-in, but the Booking does not qualify as ABSENT_NO_SHOW |
| `ATTENDED_INTERRUPTED` | Attendance was established, but the booked session could not complete for a reason outside the Customer's control |
| `ABSENT_NO_SHOW` | The Customer was absent |
| `NOT_APPLICABLE` | Attendance does not apply |

The token is `ABSENT_NO_SHOW`, deliberately different from Booking Status `NO_SHOW`, so that the two enum domains never collide.

## 28.2 Default generation rules

The lifecycle produces a **default** Attendance Result. These are generation rules, not permanent derivation formulas.

| Situation | Default Attendance Result |
|---|---|
| COMPLETED after a valid Check-in | ATTENDED |
| COMPLETED with no verified attendance and no No-show | ATTENDANCE_UNCONFIRMED |
| Booking Status NO_SHOW | ABSENT_NO_SHOW |
| Valid Check-in then Venue/Resource emergency interruption | ATTENDED_INTERRUPTED |
| Manual Booking | NOT_APPLICABLE |
| Cancelled or Rejected before any attendance | NOT_APPLICABLE |
| Operational interruption with Late Present only | ATTENDANCE_UNCONFIRMED |
| Operational interruption with no attendance evidence | NOT_APPLICABLE |

## 28.3 Booking Status does not determine Attendance Result

After an audited correction the following combinations are intentionally permitted:

- Booking Status COMPLETED with Attendance Result ABSENT_NO_SHOW
- Booking Status CANCELLED with Attendance Result ATTENDED_INTERRUPTED
- Booking Status CANCELLED with Attendance Result ATTENDED

An audited `ATTENDANCE_CORRECTED` may replace the default Attendance Result **without** changing an already-terminal Booking Status. Trust replay consumes the corrected outcome.

## 28.4 ATTENDANCE_UNCONFIRMED is outcome-first

ATTENDANCE_UNCONFIRMED is an outcome classification, not a statement about reaching `endAt`. It arises from:

- **A.** Normal completion with no valid Check-in and no No-show
- **B.** A Late Present record with no valid Check-in, where a Venue/Resource operational failure ended the session early
- **C.** An Admin Attendance Correction establishing the neutral outcome

All three produce the Trust Input Event `BOOKING_ATTENDANCE_UNCONFIRMED`: no Points, no Verified Player progress, no No-show increment, **and no counter reset**.

## 28.5 ATTENDED_INTERRUPTED stays distinct

If a qualifying Check-in existed before the interruption, the result is ATTENDED_INTERRUPTED, not ATTENDANCE_UNCONFIRMED. The difference matters:

| Evidence before interruption | Attendance Result | Counter |
|---|---|---|
| Qualifying Check-in | ATTENDED_INTERRUPTED | Resets to 0 |
| Late Present only | ATTENDANCE_UNCONFIRMED | Unchanged |
| Nothing | NOT_APPLICABLE | Unchanged |

ATTENDED_INTERRUPTED may arise from an emergency interruption after a valid Check-in, or from a corrected wrongful No-show where the Resource had already been reallocated. Both cases produce the same Trust effect: **counter reset only**.

## 28.6 Attendance Result is historical data

It is stored as immutable Product history. Trust replay uses corrected Attendance events, never silent mutation of the stored value.

---

# 29. CUSTOMER CANCELLATION

## 29.1 Free until Start Time

The Customer may cancel an eligible Online Booking while `now < startAt`. Cancellation is free: it carries no cancellation penalty, does not count as a No-show and creates no Trust penalty. Reason `CUSTOMER_CANCELLED`.

After Start Time, Customer cancellation is disabled. This is an intentional Launch rule.

## 29.2 Cancellation during a paused response timer

The Customer keeps the normal right to cancel a PENDING Booking while `now < startAt`, even while the Venue response timer is paused by an emergency (§44).

## 29.3 Cancellation while the Late Confirm countdown is paused

While a Late Confirmation countdown is paused by an emergency, the Customer may still **Decline**. Decline takes effect immediately: the Booking becomes CANCELLED with reason `CUSTOMER_DECLINED_LATE_CONFIRM`. A Customer is never forced to hold a frozen commitment.

## 29.4 Banned Customers

A banned User may still cancel an existing future Booking and may still decline a Late Confirmation. See §9.

---

# 30. MANUAL BOOKING

## 30.1 Nature

Manual Booking is an **internal Venue operation** (eligibility category C, §35.3). It is created directly by the Venue with `source = MANUAL`, does not pass through PENDING, immediately reserves the Resource, and may have no registered Customer. Optional Customer Name and Customer Phone may be captured; Online phone-visibility rules do not apply because the Venue entered the data itself.

## 30.2 Canonical creation predicate

Manual Booking creation requires **all** of:

1. Venue Account = ENABLED
2. Venue Marketplace Status is ACTIVE, INACTIVE or SUSPENDED — **ONBOARDING is explicitly not allowed**
3. Resource Base State = ACTIVE
4. Resource Operational Restriction = NONE
5. Venue Operational Selling Block = FALSE
6. Resource Operational Selling Block = FALSE
7. Resource supports the selected Activity
8. No blocking Resource reservation in the requested interval
9. No overlap with a Venue Closure
10. No overlap with an Emergency Closure
11. No overlap with a Resource Emergency Outage
12. No overlap with Maintenance
13. Start Time is NOW or FUTURE — never retroactive
14. Duration 1–6 hours in 30-minute increments
15. The pricing configuration required for the Activity exists

If the required pricing configuration does not exist, creation is blocked with the API/validation error `MANUAL_BOOKING_PRICING_CONFIGURATION_MISSING`. No reservation and no Booking record are created, so this token is neither a Booking Reason Code nor a Booking Event.

## 30.3 Opening Hours are not required

Manual Booking **does not require the interval to fit published Opening Hours.** This is intentional, so a Venue may record a walk-in, a private arrangement, extended operation or a phone booking outside its Customer-facing hours.

Area marketplace status also does not block Manual Booking. Area status governs Customer discovery only.

## 30.4 Reachable states

| Case | Lifecycle |
|---|---|
| Future Manual Booking | CONFIRMED → ACTIVE → COMPLETED |
| Current walk-in (`startAt` = current server time) | ACTIVE → COMPLETED |
| Before Start Time | CONFIRMED → CANCELLED |

Manual Bookings never use PENDING, VENUE_RESPONSE_EXPIRED, AWAITING_CUSTOMER_CONFIRMATION, REJECTED or NO_SHOW, and never use Customer Check-in.

## 30.5 No back-dating

A Manual Booking may never be created over a historical interval, even if the Booking that originally occupied that interval is now terminal. Historical occupancy is immutable (§17).

## 30.6 Trust and rewards

Manual Bookings never contribute to Verified Player progress, Completed Booking Points or No-show progression. Their Attendance Result is NOT_APPLICABLE, including when an ACTIVE Manual Booking is terminated by an operational event, because Manual Booking does not participate in Customer Trust attendance at all.

## 30.7 Commitment limit

Manual Bookings do not count against the Customer's four-commitment limit (§19.1).

## 30.8 Cancellation

Venue staff may cancel a Manual Booking without Owner Password. Reason `VENUE_CANCELLED_MANUAL_BOOKING`. Owner Password protection for Booking cancellation applies to **CONFIRMED Online Customer Bookings** only.

Because a Manual Booking carries no Customer E7GZ Booking Fee, its Booking Fee treatment is **NOT_APPLICABLE** (§34.2). This is intentional, not an unmapped refund case.

## 30.9 Operating Session snapshot and businessDate

| Case | operatingDay / session fields | businessDate |
|---|---|---|
| Fits completely inside a valid published Operating Session | Normal snapshot stored | Operating Day date |
| Outside published hours, or does not fit one session | `operatingDay`, `operatingSessionStart`, `operatingSessionEnd` = NULL; `timezone` = Africa/Cairo | Africa/Cairo local calendar date of `startAt` |

Every Manual Booking always stores a `businessDate`, so an out-of-hours Manual Booking is never left without a revenue day.

## 30.10 Future horizon

There is no additional Product maximum future horizon for Manual Booking beyond the date range the Venue Dashboard supports. The rule remains: NOW or FUTURE, never past. A technical date-picker horizon may be applied consistently without changing the Product rule.

---

# 31. START-TIME REVALIDATION AND FAILURE PRIORITY

## 31.1 Canonical start-time principle

A Booking may transition CONFIRMED → ACTIVE only when the booked Venue and Resource are **operationally able to honour the session** at `startAt`. Every start-time gate is mandatory. The revalidation and the transition execute in one atomic transaction; if authoritative state changes before commit, the transition revalidates and fails safely.

## 31.2 The revalidation list

Immediately before CONFIRMED → ACTIVE the system revalidates:

1. Venue Operational Selling Block = FALSE
2. Resource Base State = ACTIVE
3. Resource Operational Restriction = NONE
4. Resource Operational Selling Block = FALSE
5. No applicable Venue Closure or Emergency condition prevents service

## 31.3 Deterministic outcomes

Every failure has one terminal transition, one closed reason code, an Attendance Result, a notification path and a refund classification. **A Booking can never remain CONFIRMED past `startAt` because a start-time check failed.**

| Failure | Transition | Reason | Attendance |
|---|---|---|---|
| Resource under Owner review | CONFIRMED → CANCELLED | `RESOURCE_OWNER_REVIEW_REQUIRED` | NOT_APPLICABLE |
| Resource Emergency unresolved (ACTIVE or AWAITING_CONFIRMATION) | CONFIRMED → CANCELLED | `RESOURCE_EMERGENCY_OUTAGE` | NOT_APPLICABLE |
| Venue Emergency unresolved | CONFIRMED → CANCELLED | `VENUE_EMERGENCY_CLOSURE` | NOT_APPLICABLE |
| Venue Closure still prevents service | CONFIRMED → CANCELLED | `VENUE_CLOSURE` | NOT_APPLICABLE |
| Resource Base State ≠ ACTIVE (integrity anomaly) | CONFIRMED → CANCELLED | `SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR` | NOT_APPLICABLE |

In every case the Customer receives no penalty, receives a notification, and receives the applicable full Booking Fee refund (§34.1).

That the originally declared Emergency interval has ended does not make `RESOURCE_EMERGENCY_OUTAGE` inaccurate while the Resource Emergency itself remains unresolved.

The Venue Closure row is a defensive fallback; normal Closure creation should already have resolved the affected Booking.

## 31.4 Resource Base State integrity error

Normal Resource state-change rules (§13.5) prevent a Resource with protected commitments from becoming DISABLED or ARCHIVED. If a data inconsistency nevertheless survives to `startAt`, the Booking is cancelled immediately under `SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR`. No manual action is required to terminate it. The system additionally:

- Raises a Platform Admin operational alert
- Records Booking ID, Resource ID, the unexpected Base State, `startAt`, detection time, the conflicting source/state change where known, and a correlation identifier

The anomaly is investigated separately from the deterministic Customer-facing cancellation.

## 31.5 Venue Account Disable is not a start-time failure

`VENUE_ACCOUNT_DISABLED` is **not** a general CONFIRMED → ACTIVE cancellation reason. Account Disable alone does not cancel an already-CONFIRMED Booking at `startAt`. See §32.

## 31.6 Start-time reason priority

When more than one operational blocker exists at `startAt`, exactly one terminal reason is stored, chosen in this order:

1. `RESOURCE_OWNER_REVIEW_REQUIRED`
2. `RESOURCE_EMERGENCY_OUTAGE`
3. `VENUE_EMERGENCY_CLOSURE`
4. `VENUE_CLOSURE`
5. `VENUE_ACCOUNT_DISABLED` — only where it applies to an unresolved pre-confirmation response lifecycle (§32.3), never to an already-CONFIRMED session
6. `SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR`
7. Other explicit Admin/System correction

## 31.7 ACTIVE Booking interruption

A Booking that is already ACTIVE may not continue normal occupancy once its Resource becomes operationally unusable. This is a **normal defined Product path**, not an inconsistency awaiting manual cleanup.

| Cause | Transition | Reason |
|---|---|---|
| Resource Emergency Outage begins | ACTIVE → CANCELLED | `RESOURCE_EMERGENCY_OUTAGE` |
| Venue Emergency Closure affects the Booking | ACTIVE → CANCELLED | `VENUE_EMERGENCY_CLOSURE` |
| Resource enters OWNER_REVIEW_REQUIRED | ACTIVE → CANCELLED | `RESOURCE_OWNER_REVIEW_REQUIRED` |

The Resource commitment releases at the effective interruption time. The OWNER_REVIEW_REQUIRED case is automatic and requires no Platform Admin action.

Attendance follows §28.5: a qualifying Check-in gives ATTENDED_INTERRUPTED (counter resets); Late Present only gives ATTENDANCE_UNCONFIRMED; no evidence gives NOT_APPLICABLE. In every case there is no Customer penalty and, where a future non-zero Booking Fee exists, a full refund — regardless of the Customer's normal 15-minute rule.

## 31.8 ACTIVE interruption reason priority

If several causes become effective simultaneously during an ACTIVE Booking, one canonical terminal reason is stored, in this order:

1. `RESOURCE_OWNER_REVIEW_REQUIRED`
2. `RESOURCE_EMERGENCY_OUTAGE`
3. `VENUE_EMERGENCY_CLOSURE`
4. Explicit Platform Admin correction

## 31.9 Commitment lifecycle principle

The same operational eligibility framework applies at every boundary:

| Boundary | Applies to |
|---|---|
| Before commitment | Creation |
| While strengthening commitment | Accept, Confirm, Holds |
| At service start | CONFIRMED → ACTIVE |
| During service | ACTIVE interruption when the Venue/Resource can no longer honour it |

**No lifecycle boundary is exempt from operational eligibility.**

## 31.10 Revalidation points — canonical list

Canonical gate revalidation occurs immediately before each of:

- Online Booking creation
- Venue Accept
- Late Confirm initiation
- Late Confirm Accept
- Manual Booking creation
- Tournament creation
- Venue Tournament approval
- Tournament checkpoint confirmation
- Booking CONFIRMED → ACTIVE
- Tournament CONFIRMED → ACTIVE
- Resource / Hold assignment

and before any future transition whose effect is to obtain or strengthen a Resource or Venue commitment.

## 31.11 Atomic commit principle

Eligibility revalidation and the state transition or resource mutation occur in **one atomic transaction**. The first successfully committed valid transition wins. A state change between validation and commit causes revalidation or failure.

## 31.12 Displayed availability is not a commit guarantee

The Venue Dashboard must not present an action as currently executable when its known predicate already fails. But between UI render and commit, state may change.

The canonical rule is: *"An action is shown as available only when the latest known state satisfies its predicate; final execution always revalidates atomically and may fail if authoritative state changed."*

---

# 32. VENUE ACCOUNT DISABLE ACROSS THE LIFECYCLE, AND PLATFORM ATTENDANCE PROTECTION

## 32.1 What Account Disable is

Disabling the Venue Account removes the Venue's operational Dashboard access. It is **an access restriction, not a physical Venue outage.** The Venue may still be operating normally.

While Account = DISABLED the Venue cannot Accept, Reject, initiate Late Confirm, or perform ordinary Dashboard operational actions — including Check-in, Mark Present — Late and Mark No-show.

It does not automatically delete the Venue, hide it, change Marketplace Status, or cancel existing Bookings.

## 32.2 Effect on each Booking state

| Booking state when Account is disabled | Outcome |
|---|---|
| PENDING | → VENUE_RESPONSE_EXPIRED immediately, with Booking Event `VENUE_RESPONSE_INTERRUPTED_BY_ACCOUNT_DISABLE` |
| AWAITING_CUSTOMER_CONFIRMATION | → CANCELLED, reason `VENUE_ACCOUNT_DISABLED`, Attendance NOT_APPLICABLE |
| VENUE_RESPONSE_EXPIRED, still disabled at original `startAt` | → CANCELLED, reason `VENUE_ACCOUNT_DISABLED`, Attendance NOT_APPLICABLE |
| CONFIRMED | Unaffected; may still become ACTIVE (§32.4) |
| ACTIVE | Unaffected; continues. Platform Attendance Protection may apply (§32.6) |

The PENDING case releases the Resource reservation and the Customer commitment slot at once, so the Product does not wait for a Venue response the Platform itself has made impossible. `VENUE_RESPONSE_INTERRUPTED_BY_ACCOUNT_DISABLE` is a **non-terminal Booking Event**, not a reason code and not a Venue non-response fault.

If the Account is re-enabled before the original `startAt`, that same VENUE_RESPONSE_EXPIRED Booking may again be considered for Late Confirm if all normal gates pass. No automatic Late Confirm occurs.

## 32.3 Scope of the `VENUE_ACCOUNT_DISABLED` reason

The reason applies where Platform Account Disable prevented completion of the **pre-confirmation response lifecycle**:

- AWAITING_CUSTOMER_CONFIRMATION cancellation
- VENUE_RESPONSE_EXPIRED still disabled at original `startAt`
- Tournament PENDING_VENUE_APPROVAL (§37.7)

It does **not** apply merely because an already-CONFIRMED session reaches `startAt` while Dashboard login remains disabled.

Marketplace SUSPENDED or INACTIVE, by contrast, creates no Venue blame at all: it does not prevent management of an existing Booking, needs no new expiry reason, and never causes `VENUE_DID_NOT_RESPOND_IN_TIME`. Account DISABLED genuinely prevents a Dashboard response, which is why it is an overriding platform cause with its own closed reason.

## 32.4 A CONFIRMED Booking may still start

A CONFIRMED Booking transitions to ACTIVE while the Account is DISABLED provided the Venue itself remains operational, there is no Venue or Resource Selling Block, the Resource is eligible, no Closure or Emergency prevents service, and all other start-time gates pass.

## 32.5 Pre-disable attendance evidence remains authoritative

Any attendance action successfully recorded **before** Account Disable remains valid — `CHECK_IN_RECORDED`, `LATE_PRESENT_RECORDED` and `NO_SHOW_RECORDED` alike. Account Disable never erases or overrides earlier attendance evidence, and never automatically reverses a No-show. Disputes continue through the normal Admin correction process.

If a valid Check-in already exists, the Booking follows normal attendance rules: ATTENDED at normal End Time with Points, progress and counter reset, or ATTENDED_INTERRUPTED if a separate operational interruption occurs.

## 32.6 Platform Attendance Protection — eligibility

Platform Attendance Protection applies only when **all** of the following are true:

1. The Booking was ACTIVE
2. The Venue Account became DISABLED during the session
3. The Account Disable materially prevented reliable Product attendance recording
4. No authoritative qualifying Check-in existed before the protection condition
5. No authoritative No-show existed before the protection condition
6. The final effective Attendance outcome is **not** ATTENDED, ATTENDED_INTERRUPTED or ABSENT_NO_SHOW

Eligible final outcomes are therefore **ATTENDANCE_UNCONFIRMED**, and **NOT_APPLICABLE arising from operational early termination**.

Condition 3 is evaluated against the Booking's recorded `accountAccessRestrictionIntervals[]` (§32.9) measured against the Check-in window (`startAt` → `startAt + 15 min`) and the No-show window (`startAt + 15 min` → `endAt`): a disable interval that overlapped the window in which the missing evidence would have been recorded is what makes the recording unreliable. Where evidence is genuinely ambiguous, the resolution path is Platform Admin attendance correction (§32.11), not an inferred outcome.

ATTENDED_INTERRUPTED is explicitly excluded because its own Trust effect already resets the counter. No second reset is ever applied.

## 32.7 Normal or early session end

Protection is **not** limited to a session that reaches normal `endAt`. If the ACTIVE Booking terminates early because of Venue Emergency Closure, Resource Emergency Outage or OWNER_REVIEW_REQUIRED, and the protection conditions are satisfied, `BOOKING_ACCOUNT_DISABLE_ATTENDANCE_PROTECTION` still applies.

The Attendance Result of an early termination is still determined by the normal interruption model (§28.5). With no authoritative evidence it stays **NOT_APPLICABLE**; the protection event does not change it to ATTENDED or ATTENDANCE_UNCONFIRMED. Attendance Result and Trust protection are separate concepts.

## 32.8 The protection Trust event

Canonical Trust Input Event: **`BOOKING_ACCOUNT_DISABLE_ATTENDANCE_PROTECTION`**

| Property | Value |
|---|---|
| Resets Consecutive No-show Counter | Yes, to 0 |
| Grants Points | No |
| Grants Verified Player progress | No |
| Grants Verified Player qualification | No |
| Changes Attendance Result | No |
| Changes Booking Status | No |
| `effectiveAt` | `booking.startAt` |
| `occurredAt` | Booking attendance-resolution time |

It participates in normal `effectiveAt ASC` then `eventSequence ASC` replay.

The reasoning behind the split: Account Disable prevented reliable attendance verification, so E7GZ grants **no Trust penalty** but also grants **no unverified positive reward** — no +200 Points and no +1 Verified Player progress unless qualifying attendance is independently established.

## 32.9 Creation time, and repeated disable cycles

The Trust event is created **when the Booking's effective Attendance outcome is resolved**, not when the Account is disabled. At disable time the system records only *protection candidate / audit context* — that protection **may** be required. This prevents protection events that would later need neutralising.

A Booking may span several access intervals. Audit stores them as an ordered collection:

```
accountAccessRestrictionIntervals[] = [
  { disabledAt, reenabledAt (nullable), adminActor/source, reason, correlationId },
  ...
]
```

At Booking resolution, protection evaluates **all** Account Disable intervals affecting that ACTIVE session. If at least one qualifying interval prevented reliable attendance recording and no authoritative disqualifying Attendance outcome exists, **exactly one** protection Trust event is emitted. Repeated disable/re-enable cycles never generate multiple effective Trust resets.

**Maximum one effective `BOOKING_ACCOUNT_DISABLE_ATTENDANCE_PROTECTION` event per Booking.**

## 32.10 Interaction with later evidence

| Later event | Result |
|---|---|
| Account re-enabled, valid Check-in recorded within the window | Normal ATTENDED rules; protection is unnecessary and must not create an additional Trust effect |
| Account re-enabled, Mark Present — Late recorded | Attendance stays ATTENDANCE_UNCONFIRMED; the protection counter reset remains applicable; no Points, no progress |
| Account re-enabled, valid No-show recorded while the window is still open | NO_SHOW becomes authoritative; protection does not override valid later evidence of absence |

Re-enabling the Account restores attendance actions according to their normal time windows. **The system never retroactively reopens an expired Check-in window.**

## 32.11 Admin correction supersedes protection

| Corrected outcome | Trust result |
|---|---|
| ATTENDED | Normal attended effect |
| ATTENDED_INTERRUPTED | Normal interrupted effect |
| ABSENT_NO_SHOW | Protection reset is neutralised; the No-show applies |
| ATTENDANCE_UNCONFIRMED, or eligible NOT_APPLICABLE | Protection remains applicable where conditions are met |

No Customer ever receives both a protected reset and a valid No-show increment for the same final effective attendance outcome. Replay stays deterministic.

## 32.12 Exactly one counter-reset mechanism per Booking

If the Booking ultimately produces ATTENDED or ATTENDED_INTERRUPTED, the separate protection event is **not** additionally applied — the qualifying Attendance event provides the reset.

## 32.13 Notifications and audit

If Account Disable overlaps an ACTIVE Booking, the Customer receives an In-App + Push notification `ACTIVE_SESSION_ATTENDANCE_RECORDING_AFFECTED`, indicating that Venue account access was temporarily restricted and attendance recording may be affected. **The notification must not state that the Booking has been cancelled** unless another rule actually cancels it.

Platform Admin receives an operational alert containing the Venue, Booking ID, session interval, Account Disable time, current attendance evidence, and whether Platform Attendance Protection may be required.

There is **no** mandatory Customer notification merely because the Account is re-enabled. The Customer is notified only when an actual Customer-relevant action occurs — a Late Confirm initiated, a Booking confirmed, a Booking cancelled.

Audit records: Booking ID, Venue ID, Account Disable timestamp, Account Re-enable timestamp if any, attendance evidence before disable, attendance evidence after re-enable, protection applied yes/no, Trust event ID, and any later Admin correction.

---

# 33. BOOKING REASON CODES AND BOOKING EVENTS

## 33.1 Two separate closed enums

**Booking Reason Codes** explain a **terminal** Booking outcome. **Booking Events** record actions and non-terminal failures in append-only history. A token belongs to exactly one of the two. Adding a Product-visible member to either requires Product Change Control (§57).

## 33.2 Canonical Booking Reason Codes — terminal only

**Customer-caused**

- `CUSTOMER_CANCELLED`
- `CUSTOMER_DECLINED_LATE_CONFIRM`
- `CUSTOMER_RECONFIRM_TIMEOUT`

**Venue-caused**

- `VENUE_REJECTED`
- `REJECTED_AFTER_EXPIRY`
- `VENUE_CANCELLED_CONFIRMED_BOOKING`
- `VENUE_CANCELLED_MANUAL_BOOKING`
- `VENUE_DID_NOT_RESPOND_IN_TIME`
- `VENUE_OPENING_HOURS_CHANGED`
- `VENUE_CLOSURE`
- `VENUE_EMERGENCY_CLOSURE`
- `RESOURCE_EMERGENCY_OUTAGE`

**Operational interruption**

- `VENUE_RESPONSE_INTERRUPTED_BY_EMERGENCY`
- `RESOURCE_RESPONSE_INTERRUPTED_BY_EMERGENCY`
- `LATE_CONFIRM_INTERRUPTED_BY_EMERGENCY`
- `RESOURCE_OWNER_REVIEW_REQUIRED`

**Platform / system**

- `VENUE_ACCOUNT_DISABLED`
- `SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR`

**Trust / account**

- `USER_BANNED`

**Admin**

- `ADMIN_CANCELLED`
- `ADMIN_CORRECTION`
- `ADMIN_NO_SHOW_REVERSAL`

## 33.3 Tokens deliberately removed

| Removed token | Why | Where the concept lives now |
|---|---|---|
| `BOOKING_EXPIRED` | Duplicate / unmapped | `VENUE_DID_NOT_RESPOND_IN_TIME` |
| `RESOURCE_CONFLICT` | No producing terminal transition; conflicts fail validation or atomic commit | Validation failure, or `LATE_CONFIRM_FAILED_RESOURCE_CONFLICT` |
| `CUSTOMER_SCHEDULE_CONFLICT` | No terminal producer | Booking Event `LATE_CONFIRM_FAILED_CUSTOMER_CONFLICT` |
| `LATE_CONFIRM_RESOURCE_UNAVAILABLE` | No terminal producer | Validation failure, atomic failure, or `LATE_CONFIRM_FAILED_RESOURCE_CONFLICT` |
| `LATE_CONFIRM_ELIGIBILITY_LOST_RESOURCE_STATE_CHANGED` | Is an Event, not a terminal reason | Booking Event of the same name |

Because these are no longer terminal Booking Reasons, they need no Booking Fee refund classification, and **every reachable terminal Booking Reason has exactly one deterministic fee treatment** (§34).

## 33.4 Canonical Booking Events — non-terminal

- `CHECK_IN_RECORDED`
- `CHECK_IN_REVERSED`
- `LATE_PRESENT_RECORDED`
- `LATE_PRESENT_REVERSED`
- `NO_SHOW_RECORDED`
- `NO_SHOW_REVERSED`
- `ATTENDANCE_CORRECTED`
- `LATE_CONFIRM_FAILED_RESOURCE_CONFLICT`
- `LATE_CONFIRM_FAILED_CUSTOMER_CONFLICT`
- `LATE_CONFIRM_FAILED_BOOKING_LIMIT`
- `LATE_CONFIRM_ELIGIBILITY_LOST_RESOURCE_STATE_CHANGED`
- `VENUE_RESPONSE_INTERRUPTED_BY_ACCOUNT_DISABLE`

All are append-only audit/history events. They are not cancellation reasons unless the Booking actually transitions terminally under its own reason.

No API validation-only token belongs in this enum. `MANUAL_BOOKING_PRICING_CONFIGURATION_MISSING` is an API/validation error code only, because no Booking record is created when it fires.

The canonical Event name for the commitment-limit failure is `LATE_CONFIRM_FAILED_BOOKING_LIMIT`. The older token `CUSTOMER_BOOKING_LIMIT_REACHED` is removed.

## 33.5 `detailCode`

A Booking Event may carry an optional structured `detailCode` where machine-readable context is needed without creating another Event Type. It:

- Is optional only where the Event schema explicitly permits it
- Is validated against an **Event-specific closed value set**
- Is historical and immutable once recorded
- Does not create a separate Booking Status
- Does not create another Booking Event enum member
- Is **not** a global free-text subtype field

Currently defined domain:

| Event | Allowed `detailCode` values |
|---|---|
| `LATE_CONFIRM_ELIGIBILITY_LOST_RESOURCE_STATE_CHANGED` | `RESOURCE_DISABLED`, `RESOURCE_ARCHIVED`, `RESOURCE_CONSOLE_CHANGED`, `RESOURCE_OWNER_REVIEW_REQUIRED` |

No other `detailCode` is valid for that Event without Product Change Control.

## 33.6 Customer-facing copy must distinguish causes

Internal reason codes remain authoritative, but the Customer message must not blame the Venue for something the Venue did not do:

| Cause | Customer copy concept |
|---|---|
| Ordinary expiry | "Venue did not respond in time." |
| Platform Account Disable | "Venue is temporarily unable to confirm this booking." |
| Venue/Resource operational issue | The corresponding operational reason |

## 33.7 Booking history is append-only

Previous lifecycle events must never disappear. *Auto Cancelled* stays visible even if the Booking is later restored through Late Confirmation.

---

# 34. BOOKING FEE REFUNDS AND PHONE VISIBILITY

## 34.1 Canonical refund principle

The Launch Booking Fee is **EGP 0**, so no settlement occurs today. The classification below is a binding Product rule that applies the moment a non-zero Booking Fee is enabled.

**Customer-caused cancellation** follows the Customer 15-minute refund rule: the fee is refundable only when the Customer cancels within 15 minutes of Booking creation **and** before Start Time. After 15 minutes the fee is non-refundable, although the Customer may still cancel freely before Start Time with no additional penalty.

**Venue, Platform or Resource operationally caused cancellation** receives a **full Booking Fee refund**. This is mandatory.

The Customer must never pay an E7GZ Booking Fee for a service the Venue never accepted or delivered.

## 34.2 Complete classification

**Full Booking Fee refund**

- `VENUE_REJECTED`
- `VENUE_DID_NOT_RESPOND_IN_TIME`
- `REJECTED_AFTER_EXPIRY`
- `CUSTOMER_DECLINED_LATE_CONFIRM`
- `CUSTOMER_RECONFIRM_TIMEOUT`
- `VENUE_CANCELLED_CONFIRMED_BOOKING`
- `VENUE_CLOSURE`
- `VENUE_OPENING_HOURS_CHANGED`
- `VENUE_EMERGENCY_CLOSURE`
- `RESOURCE_EMERGENCY_OUTAGE`
- `RESOURCE_OWNER_REVIEW_REQUIRED`
- `VENUE_RESPONSE_INTERRUPTED_BY_EMERGENCY`
- `RESOURCE_RESPONSE_INTERRUPTED_BY_EMERGENCY`
- `LATE_CONFIRM_INTERRUPTED_BY_EMERGENCY`
- `VENUE_ACCOUNT_DISABLED`
- `SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR`
- `ADMIN_CANCELLED` and `ADMIN_CORRECTION` where the Customer is not at fault
- `ADMIN_NO_SHOW_REVERSAL` where the wrongful No-show caused service loss or reallocation

**Customer cancellation policy applies**

- `CUSTOMER_CANCELLED`
- `USER_BANNED`
- Other Customer-fault or policy reasons explicitly defined elsewhere

**Not applicable — no Customer Booking Fee exists**

- `VENUE_CANCELLED_MANUAL_BOOKING`

## 34.3 Two refund clarifications

**Declining or timing out a Late Confirmation is not an ordinary Customer cancellation.** Late Confirm exists only because the Venue's own response window expired, so both `CUSTOMER_DECLINED_LATE_CONFIRM` and `CUSTOMER_RECONFIRM_TIMEOUT` receive a full refund and carry no penalty.

**Correcting an Attendance classification is not itself a refund trigger.** If an Admin reverses a No-show after the session and the evidence shows the service was actually delivered, the fee is not refunded merely because the record changed. Refund is tied to **failure to deliver the booked service**, not to reclassification.

Where a Customer-fault case exists, it must be coded with its actual Customer/Trust reason rather than a generic `ADMIN_CORRECTION`.

## 34.4 Operational interruption after service start

Where a future non-zero Booking Fee exists, a Venue or Resource operational interruption **after service has started** receives a full refund, regardless of the Customer's normal 15-minute rule.

## 34.5 Phone visibility — when it opens

Before Booking creation the Venue phone is hidden. Phone access begins when the Booking becomes **PENDING**: the Venue may see the Customer phone and the Customer may see the Venue phone.

Manual Booking is exempt, because the Venue entered any contact data itself.

## 34.6 Phone visibility — when it ends

| Case | Visibility ends at |
|---|---|
| Terminal **before** Start Time (Customer cancelled, Venue rejected, Ban cancellation, and similar) | `terminalAt + 2 hours` |
| Terminal **at or after** Start Time, including NO_SHOW | original `endAt + 2 hours` |
| VENUE_RESPONSE_EXPIRED | `venueResponseExpiredAt + 2 hours`, mandatorily, unless the Booking is revived before then |

Retention past a started session supports operational disputes and late-arrival contact. After the retention point, normal Booking UI hides counterpart phone data. Platform Admin and authorised Support may retain controlled access where operationally required.

## 34.7 Phone visibility and Late Confirm

When the Venue successfully initiates Late Confirm, counterpart phone visibility **must reopen** for the AWAITING_CUSTOMER_CONFIRMATION period. This is mandatory, not optional.

| Outcome | Visibility |
|---|---|
| Customer accepts → CONFIRMED | Normal confirmed-Booking rules are fully restored |
| Customer declines, or the window expires | Normal terminal pre-start retention: `terminalAt + 2 hours` |

Expiry of phone visibility after VENUE_RESPONSE_EXPIRED never makes that Booking terminal; the Booking remains revivable until `startAt`.

---

# PART VI — ELIGIBILITY FRAMEWORK AND TOURNAMENTS

---

# 35. THE THREE ELIGIBILITY CATEGORIES

Every Product action falls into exactly one of three categories. Conflating them is the single most common source of wrong behaviour, so the categories are canonical.

## 35.1 A — New-Activity Marketplace Eligibility

Used when creating a **new** marketplace commitment:

- New Online Booking Request
- New Player-hosted Tournament request
- New Venue-hosted Tournament creation

Normally requires Venue Marketplace Status = **ACTIVE**, plus all other relevant conditions.

## 35.2 B — Existing-Commitment Operational Eligibility

Used when deciding whether an **already-created** commitment may be honoured:

- Venue Accept of an existing PENDING Booking
- Late Confirm initiation and Late Confirm Accept
- Tournament checkpoint
- Booking Start
- Tournament Start
- Management of already-existing commitments

It asks one question: **can the Venue safely and operationally honour this existing commitment?**

It does **not** require Marketplace Status ACTIVE. A Venue that becomes INACTIVE or SUSPENDED after the commitment was created may still Accept, Late Confirm and honour it, provided the Venue Account remains ENABLED, no Operational Selling Block prevents service, the Resource remains eligible, the Customer remains eligible and all other existing-commitment gates pass.

| Venue Marketplace Status | New marketplace requests | Managing / honouring existing commitments |
|---|---|---|
| ACTIVE | Yes | Yes |
| INACTIVE | No | Yes |
| SUSPENDED | No | Yes |
| ONBOARDING | No | No marketplace inventory exists |

Venue Account DISABLED is different in kind: it removes Dashboard access, so the Venue cannot perform the action at all, whatever the Marketplace Status (§32).

## 35.3 C — Internal Venue Operation

Used for actions that are not Customer marketplace acquisition at all:

- Manual Booking
- Internal Venue scheduler and resource management

Internal Venue Operations do not use Customer marketplace acquisition rules unless explicitly stated. Manual Booking therefore keeps its own predicate (§30.2) — Venue Account ENABLED, Marketplace ACTIVE / INACTIVE / SUSPENDED (never ONBOARDING), Resource operationally eligible, no conflicts, no retroactivity, valid pricing — without being forced into category A.

## 35.4 Additive, not replacing

Every consolidated "final gate" list in this specification is **additive** to all other approved conditions for that flow. A later list never silently removes an earlier business condition; only an explicit supersession does that.

---

# 36. TOURNAMENT MODEL

## 36.1 Day-1 scope

Tournaments are a Day-1 feature across all three Activities: PlayStation, Billiards, Ping Pong.

A Tournament is **a separate entity from a Booking**. The Venue Dashboard holds Booking History and Tournament History separately. Tournaments must not be modelled as Bookings.

## 36.2 Host types

| Type | Creator requirement | Venue approval |
|---|---|---|
| Player-hosted | Operational Verified Player Status = VERIFIED | Required (§37.1) |
| Venue-hosted | Created from the Venue Dashboard | Not required |

A Verified Player does not have the authority to block Venue Resources without the Venue's consent, which is why Player-hosted Tournaments need approval.

## 36.3 Parameters

| Parameter | Value |
|---|---|
| Minimum participants | 8 |
| Maximum participants | 32 |
| Minimum Resources | 1 |
| Maximum Resources | 4 |
| Minimum duration | 1 hour |
| Duration increment | 30 minutes |
| Maximum duration | No global maximum below the Operating Session limit |
| Creation cutoff | `requestTime <= startAt - 2 hours` |
| Checkpoint | Exactly `startAt - 1 hour` |
| Tournament Creation Fee at Launch | EGP 0 (configurable) |

Participant capacity and Resource count are intentionally independent. E7GZ PS does not manage brackets, per-match scheduling or per-match Resource assignment in MVP.

A PlayStation Tournament must select **Single** or **Multiplayer**; the selected play mode controls Resource pricing.

The Tournament interval must fit completely within **one valid Venue Operating Session**. Because of that, a Tournament may legitimately run longer than the 6-hour Online Booking maximum if the Operating Session and the selected Resources support it.

The 2-hour creation cutoff applies to both host types, and guarantees that a Player-hosted Venue approval window completes well before the 1-hour checkpoint.

## 36.4 Player Creator participation

The Player Creator chooses **Organizer Only** or **Organizer + Participant**. If participating, the Creator counts in the participant total.

## 36.5 Public and Private

| Public | Private |
|---|---|
| Appears in Discover | Hidden from public discovery |
| Account-Verified Users may Join | Has an Invite Code and an Invite Link |
| No Creator approval to join | Requires valid invite access |

The Venue Dashboard supports Copy Code and Copy / Share Invite Link.

While a Tournament is PENDING_VENUE_APPROVAL it is **not** shown in public Discover, its Invite Code and Link are **not** active, and other Users may **not** Join. The Creator and the Venue may view it. Public/private discovery rules become active only after Venue Accept.

## 36.6 Resource eligibility

Every assigned Resource requires:

- Resource Base State = ACTIVE
- Resource Operational Restriction = NONE
- No Operational Selling Block, at Venue or Resource level
- No conflicting reservation or Hold
- Activity compatibility

## 36.7 Existing reservations always win

Tournament creation or request can never override a Pending Booking, an Awaiting Customer Confirmation Booking, a Confirmed or Active Booking, a Manual Booking, an existing Tournament, Maintenance or a Closure. Creation **fails** for conflicting Resources. **A Tournament may never automatically cancel an existing Booking.**

## 36.8 Resource Holds

When a Tournament is created, the selected Resources become **HELD** for the Tournament period and are unavailable to normal and manual Booking. During PENDING_VENUE_APPROVAL the Holds are temporary **Approval Holds**; they block other reservations just the same, and they release if the request is rejected, expires or is cancelled. On Venue Accept they become standard Tournament Holds.

## 36.9 Pricing and cost

Tournament Resource usage has a calculated **Venue Tournament Cost**: for each selected Resource, `historical resource hourly price × booked nominal duration`, summed across the selected Resources. PlayStation uses console type, play mode and any applicable Room Override; Billiards and Ping Pong use Venue-level hourly pricing. The same half-up, 2-decimal rounding as §4.4 applies.

| Host type | Price snapshot taken at |
|---|---|
| Player-hosted | Tournament **request creation** — the Venue sees and accepts the price attached to that request |
| Venue-hosted | Creation |

Later Venue price changes never alter a stored Tournament Cost.

Venue Tournament Cost is **informational** in MVP. E7GZ does not collect it; the Organizer settles with the Venue outside E7GZ. Entry Fee and Prize are likewise informational: E7GZ does not collect entry fees, hold prizes or distribute prizes.

## 36.10 Revenue

When a Tournament reaches **COMPLETED**, its stored Venue Tournament Cost contributes to the Venue Dashboard's Tournament Revenue, keyed on `businessDate` (§17.5). Cancelled and Rejected Tournaments contribute nothing.

## 36.11 No financial debt on the Creator

E7GZ never automatically imposes a penalty, debt, wallet deduction or platform financial obligation on a Creator who cannot continue after Tournament confirmation. Settlement stays outside E7GZ in MVP.

---

# 37. TOURNAMENT LIFECYCLE

## 37.1 Canonical states

| State | Terminal |
|---|---|
| `PENDING_VENUE_APPROVAL` | No — Player-hosted only |
| `WAITING_FOR_PLAYERS` | No |
| `CONFIRMED` | No |
| `ACTIVE` | No |
| `COMPLETED` | Yes |
| `REJECTED` | Yes |
| `CANCELLED` | Yes |

## 37.2 Player-hosted approval

The Verified Player selects Venue, Activity, Resources, Date, Start Time, Duration, Public/Private and Tournament details. The system validates availability. If available, the Tournament enters PENDING_VENUE_APPROVAL and the selected Resources receive temporary Approval Holds.

The Venue response window is:

`approvalExpiresAt = MIN(requestCreatedAt + 10 minutes, checkpointAt)`

| Venue response | Outcome |
|---|---|
| Accept | → WAITING_FOR_PLAYERS; Approval Holds become standard Tournament Holds |
| Reject | → REJECTED, reason `VENUE_REJECTED_TOURNAMENT`; Holds release |
| No response | → CANCELLED, reason `VENUE_DID_NOT_APPROVE_TOURNAMENT_IN_TIME`; Holds release |

A Venue-hosted Tournament skips this step: after validation and Hold creation it goes straight to WAITING_FOR_PLAYERS.

## 37.3 Creator request limits

A Verified Player Creator may hold only **one** Player-hosted Tournament in PENDING_VENUE_APPROVAL at any time across the whole platform.

After a request is REJECTED or its approval EXPIRED, the Creator must wait **5 minutes** before requesting the same Venue and the same Tournament time interval again.

## 37.4 Join and Leave

Users may Join only while the Tournament is WAITING_FOR_PLAYERS or CONFIRMED and `now < startAt`, with capacity remaining, the User not banned, and valid invite access for a Private Tournament. A Participant may Leave, and the Organizer may remove a Participant, until `startAt`.

Participant states remain: `JOINED`, `LEFT`, `REMOVED`, `INELIGIBLE`. When a Participant is `INELIGIBLE`, the system records the explicit cause `BAN` or `ACCOUNT_DELETED`; this cause does not create another Participant Status.

An **Active Participant** is one whose state is JOINED, whose account is neither BANNED nor DELETED, and who has not left or been removed. Active Participant count is the count used for the 8-participant checkpoint minimum.

**Capacity occupancy is intentionally different from Active Participant count.** Before `startAt`, a Participant who is `INELIGIBLE` **only because of a temporary Ban** keeps one Tournament capacity slot reserved while their account remains active and the Tournament remains non-terminal/pre-start. They do **not** count as an Active Participant and therefore do not help satisfy the checkpoint minimum. The reserved slot prevents another User from consuming capacity that must remain available if the Ban expires or is lifted before Start.

`INELIGIBLE/ACCOUNT_DELETED` never reserves a restoration slot. A BAN-only reserved slot ends immediately if the account is deleted, if the Tournament becomes terminal, or at `startAt`, whichever occurs first. Join-capacity validation therefore counts `JOINED` participants plus these still-valid BAN-only reserved slots.

If removal takes the Active Participant count below 8 before the checkpoint, the Tournament is **not** cancelled immediately — it stays open until the checkpoint.

## 37.5 Checkpoint

At exactly `startAt - 1 hour`:

| Active Participants | Outcome |
|---|---|
| ≥ 8 | → CONFIRMED |
| < 8 | → CANCELLED, reason `MINIMUM_PARTICIPANTS_NOT_REACHED`; Resources released |

Once CONFIRMED, a later drop below 8 does **not** auto-cancel the Tournament. This is intentional. There is no "Run Anyway" override for a Venue-hosted Tournament that fails the minimum.

Before WAITING_FOR_PLAYERS → CONFIRMED the system revalidates **every** assigned Resource: Base State ACTIVE, Operational Restriction NONE, no Operational Selling Block, Tournament Hold still valid, Venue eligible, Tournament not otherwise cancelled.

At the checkpoint, "Venue eligible" means **operationally able to honour the existing Tournament** (category B, §35.2) — not currently eligible to receive new marketplace activity. Marketplace SUSPENDED or INACTIVE therefore never causes checkpoint failure by itself. Checkpoint fails on a Venue condition only when an operational condition prevents fulfilment: an applicable Venue Operational Selling Block, a Venue Emergency affecting the Tournament, or an applicable Closure covering the interval. In that case the Tournament is CANCELLED under the applicable operational reason and everyone is notified. **A Tournament never remains indefinitely in WAITING_FOR_PLAYERS after a failed mandatory checkpoint.**

Resource-level checkpoint failure uses `RESOURCE_EMERGENCY_OUTAGE` or `RESOURCE_OWNER_REVIEW_REQUIRED` as applicable; the entire Tournament cancels, all Holds release, and the Creator and Participants are notified.

## 37.6 Start and end

At Start Time, CONFIRMED → ACTIVE. At End Time, ACTIVE → COMPLETED, unless explicitly cancelled or terminated.

Immediately before CONFIRMED → ACTIVE the system revalidates every Tournament Resource. If any required assigned Resource is OWNER_REVIEW_REQUIRED, the Tournament is CANCELLED with reason `RESOURCE_OWNER_REVIEW_REQUIRED` **before gameplay begins**.

## 37.7 Venue Account Disable during PENDING_VENUE_APPROVAL

If the Venue Account becomes DISABLED while a Player-hosted Tournament is PENDING_VENUE_APPROVAL, the Tournament is resolved **immediately**. It does not wait for the normal approval timer.

`PENDING_VENUE_APPROVAL → CANCELLED`, reason **`VENUE_ACCOUNT_DISABLED`** — a platform/access operational reason carrying **no Venue blame**.

Effects:

- The Venue-approval timer stops immediately; there is no paused timer and no later resumption for that Tournament record
- Temporary Tournament Resource Holds release
- The Creator's single platform-wide PENDING_VENUE_APPROVAL slot releases immediately
- The Venue is **not** recorded as failing to approve in time
- The normal 5-minute retry cooldown does **not** apply, because the Platform prevented the Venue's response
- The Creator is notified: *"Tournament approval could not continue because the Venue account became temporarily unavailable."* Venue non-response blame copy must not be used
- Venue Owner and Platform Admin operational history is updated

The Creator may immediately create another eligible Tournament request. If the same Venue remains Account DISABLED it is not eligible to receive a new request that requires Venue Dashboard action. If the Account is later re-enabled, the cancelled Tournament is **never** restored.

Venue Account Disable alone does **not** cancel WAITING_FOR_PLAYERS, CONFIRMED or ACTIVE Tournaments while the Venue and Resources remain operationally able to deliver. Account Disable is an access restriction, not a physical outage.

## 37.8 Verified Player cascade

| Creator status change | PENDING_VENUE_APPROVAL | WAITING_FOR_PLAYERS | CONFIRMED | ACTIVE |
|---|---|---|---|---|
| Verified Player **SUSPENDED** | Not cancelled | Not cancelled | Not cancelled | Not cancelled |
| Verified Player **REVOKED** | → CANCELLED | → CANCELLED | Remains | Remains |
| Qualification lost via Trust correction | → CANCELLED | → CANCELLED | Remains | Remains |

Reason: `CREATOR_VERIFIED_STATUS_REVOKED`. Suspension blocks the creation of **new** Player-hosted Tournaments while it lasts but cancels nothing existing. Restoring Verified Player status never resurrects a cancelled Tournament.

The cascade executes **once per `verifiedPlayerTransitionId`** (§8.3); repeated Trust replay must never repeat it.

## 37.9 Cancellation authority

| Actor | May cancel |
|---|---|
| Venue (Venue-hosted) | Any state before ACTIVE |
| Venue (Player-hosted) | After its own approval |
| Creator (Player-hosted) | PENDING_VENUE_APPROVAL and WAITING_FOR_PLAYERS, before the checkpoint |
| Platform Admin | Any non-completed Tournament |

**After a Tournament becomes CONFIRMED at the checkpoint, the Creator may no longer cancel it through E7GZ.** If the Creator cannot continue, they must contact the Venue or Support. Cancellation authority then belongs to the permitted Venue, Platform Admin and emergency flows.

An ACTIVE Tournament may be ended early only through Venue Emergency Closure, an applicable Resource Emergency Outage, Tournament Operational Termination, or a Platform Admin support correction. There is no vague unrestricted "Venue operational termination" power.

**Tournament Operational Termination** covers a serious Tournament-specific incident that does not require closing the whole Venue — a fight or safety incident isolated to the Tournament, organizer misconduct, a Tournament-only operational breakdown. It requires **Owner Password**, a mandatory reason, audit, and Participant and Organizer notification. ACTIVE → CANCELLED, reason `TOURNAMENT_OPERATIONAL_TERMINATION`.

Every cancellation releases Resource Holds, updates Tournament history, notifies the Creator and Participants, and stores the cancelling actor and the reason. No Resource Booking is silently created or removed outside that transaction.

## 37.10 Owner Password for Tournament cancellation

| Action | Owner Password |
|---|---|
| Venue cancels WAITING_FOR_PLAYERS | Not required |
| Venue cancels CONFIRMED | **Required** |
| Tournament Operational Termination | **Required** |
| Cancellation caused by Venue Emergency Closure | Not required |
| Cancellation caused by Resource Emergency Outage | Not required |
| Cancellation caused by OWNER_REVIEW_REQUIRED cascade | Not required |
| Creator cancels their own Player-hosted Tournament | Venue Owner Password not used |

The emergency rows are **system consequences of an emergency operation**, not the Venue's ordinary Cancel Tournament action, which is why the protected-cancellation requirement does not reach them.

## 37.11 Bans and deletions among Participants

`INELIGIBLE` always carries an explicit cause for this lifecycle: `BAN` or `ACCOUNT_DELETED`. `ACCOUNT_DELETED` has precedence because deletion permanently removes the Participant's ability to return, while a temporary Ban may expire or be lifted before Start.

| Situation | Effect |
|---|---|
| Participant banned before the checkpoint | → `INELIGIBLE/BAN`; removed from Active Participant count. While `now < startAt`, the account remains active and the Tournament remains non-terminal/pre-start, one capacity slot stays reserved for possible automatic restoration. The Participant still does **not** count toward the checkpoint minimum and may therefore take the Tournament below 8. |
| Participant banned after the checkpoint but before `startAt` | → `INELIGIBLE/BAN`; no longer eligible to participate and no longer Active. The already-CONFIRMED Tournament is not cancelled merely for falling below 8. The capacity slot remains reserved only until `startAt`, so a pre-start Ban expiry/lift can restore the same Participant without overfilling capacity. |
| Participant's account becomes DELETED | → `INELIGIBLE/ACCOUNT_DELETED`; removed from Active Participant count immediately and any BAN-only reserved capacity slot is released immediately. Account deletion never carries an automatic restoration right. |
| Participant is already `INELIGIBLE/BAN` and the account is then DELETED | The cause is reclassified to `ACCOUNT_DELETED` in the same logical operation. Deletion overrides BAN-only restoration eligibility and immediately releases the reserved slot. |
| Account is already DELETED and a Ban is activated later, or deletion and Ban occur in the same logical operation | Cause remains `ACCOUNT_DELETED`. A Ban must never downgrade the cause back to `BAN` or create future restoration eligibility. |
| Temporary Ban expires or is lifted while the Tournament is non-terminal, the account is active and `now < startAt` | An `INELIGIBLE/BAN` Participant automatically returns to `JOINED`. Because their slot was reserved, restoration does not require a newly free capacity slot. |
| Ban was no longer effective at the exact checkpoint | Restoration is effective for the checkpoint itself even if the worker runs later; the restored Participant is included in the exact checkpoint Active Participant count. |
| Ban expires after the checkpoint but before `startAt` | The BAN-only Participant automatically returns to `JOINED`; the already-CONFIRMED Tournament stays confirmed. |
| Ban expires or is lifted at or after `startAt` | No automatic restoration into an ACTIVE Tournament. The BAN-only reserved capacity right ends at `startAt`. |
| Tournament already cancelled because the checkpoint had fewer than 8 Active Participants | A later Ban expiry/lift never resurrects the Tournament. |

Historical Participant state and ineligibility-cause history are always retained. **Active Participant count and Tournament Capacity Occupancy are separate calculations**: BAN-only `INELIGIBLE` may reserve capacity before Start while contributing zero to the checkpoint minimum; `ACCOUNT_DELETED` contributes to neither.

## 37.12 Resource loss during an ACTIVE Tournament

| Situation | Outcome |
|---|---|
| One or more assigned Resources fail, at least one remains operational | Tournament remains ACTIVE; failed Resources are removed from its available Resources; Creator and Participants receive **Tournament Resource Availability Changed** (In-App + Push) |
| No assigned Resource remains operational | ACTIVE → CANCELLED, reason `RESOURCE_EMERGENCY_OUTAGE` or `RESOURCE_OWNER_REVIEW_REQUIRED` as applicable |

Before the Tournament starts the rule is stricter: if **any** assigned Resource becomes unavailable through Resource Emergency Outage or OWNER_REVIEW_REQUIRED, the **entire Tournament cancels**, all Holds release, and the Creator and Participants are notified. The same applies to a Tournament still in PENDING_VENUE_APPROVAL.

The Tournament's historical informational Venue Cost **never changes** when a Resource is lost, whether the cause is Resource Emergency Outage or OWNER_REVIEW_REQUIRED. The original snapshot is immutable; the Resource-loss event is recorded; any real-world settlement adjustment stays outside E7GZ in MVP.

## 37.13 Canonical Tournament Reason Codes

- `CREATOR_CANCELLED`
- `VENUE_CANCELLED`
- `ADMIN_CANCELLED`
- `VENUE_REJECTED_TOURNAMENT`
- `VENUE_DID_NOT_APPROVE_TOURNAMENT_IN_TIME`
- `MINIMUM_PARTICIPANTS_NOT_REACHED`
- `CREATOR_VERIFIED_STATUS_REVOKED`
- `TOURNAMENT_OPERATIONAL_TERMINATION`
- `EMERGENCY_CLOSURE`
- `RESOURCE_EMERGENCY_OUTAGE`
- `RESOURCE_OWNER_REVIEW_REQUIRED`
- `VENUE_ACCOUNT_DISABLED`

## 37.14 Tournament reason priority

Exactly one terminal Tournament reason is stored. Where a Venue or Resource operational emergency independently cancels the Tournament first, that specific operational reason is used; `VENUE_ACCOUNT_DISABLED` applies otherwise. The full order, mirroring the Booking-side priority of §31.6 and §31.8, is:

1. `RESOURCE_OWNER_REVIEW_REQUIRED`
2. `RESOURCE_EMERGENCY_OUTAGE`
3. `EMERGENCY_CLOSURE` (Venue-level)
4. `VENUE_ACCOUNT_DISABLED`
5. `MINIMUM_PARTICIPANTS_NOT_REACHED`
6. `CREATOR_VERIFIED_STATUS_REVOKED`
7. Explicit actor cancellation — `TOURNAMENT_OPERATIONAL_TERMINATION`, `VENUE_CANCELLED`, `CREATOR_CANCELLED`, `ADMIN_CANCELLED`

## 37.15 Tournament audit

Tournament audit records the Venue Account Disable timestamp, the affected Tournament ID, its previous status, the cancellation reason, Resource Hold releases, the Creator pending-slot release, notification delivery, and any later Account re-enable that is operationally relevant.

---

# PART VII — MAINTENANCE, EMERGENCIES AND OPERATIONAL RESTRICTIONS

---

# 38. OPERATIONAL SELLING BLOCK

## 38.1 What it is

An Operational Selling Block is a **higher-level availability restriction**. It is not a reservation and does not require a fabricated future interval. Canonical Availability evaluates it explicitly, before any Resource reservation check (§16.2).

## 38.2 It is mandatory, not optional

A relevant Emergency **must** create the corresponding Operational Selling Block. Any older wording saying an Emergency "may" create one is superseded.

## 38.3 Scope

| Emergency | Block created |
|---|---|
| Venue Emergency Closure | **Venue** Operational Selling Block |
| Resource Emergency Outage | **Resource** Operational Selling Block, affected Resource only |

One Venue-level block is sufficient to block all affected Venue marketplace operations; the flag does not need copying onto every Resource. A Resource Emergency never creates a Venue-level block, so the Venue's other Resources keep operating.

Availability checks the Venue block first, then the selected Resource block where a Resource is involved. Nearest Availability ignores Resources blocked at either level.

## 38.4 What it prevents

While a relevant Operational Selling Block is TRUE:

- No new Online Bookings
- No new Manual Bookings on the affected Venue/Resource
- No new Tournament Resource Holds or Approval Holds
- No Late Confirm requiring the affected Venue/Resource
- Venue Accept of a PENDING Booking is disabled
- No Booking may transition CONFIRMED → ACTIVE on the affected Venue/Resource

**Every creation and commitment predicate in this specification requires Operational Selling Block = FALSE** for the affected Venue and Resource. This is an explicit Product eligibility condition, not an inference from interval overlap.

## 38.5 Existing reservations are not swept up

Reservations **outside** the originally declared emergency interval are not automatically cancelled merely because an Emergency entered AWAITING_CONFIRMATION. They remain recorded. The Venue must resolve, extend or convert the Emergency if the outage will actually affect them.

An Operational Selling Block may persist across an Operating Session end, a closed period and into the next Operating Session, until staff resolve it. That persistence does **not** extend the earlier cancellation cascade.

## 38.6 The no-sellable-gap contract

E7GZ cannot physically verify whether a console or a table has been repaired. So "no sellable gap" means precisely this:

> **E7GZ will not release the operational block unless staff assert the Resource is operational, or a replacement block or state commits.**

That is the enforceable Product and system guarantee. There must never be a period in which the Emergency block has ended, the Resource is still unusable, and no replacement operational restriction or block exists.

Selecting **END EMERGENCY** is an explicit Venue assertion that the affected Venue or Resource is operational again. The action is audited, attributable to the Venue Account and timestamped. If staff falsely mark a broken Resource operational, the Product cannot guarantee physical correctness; it guarantees auditability, state consistency and Admin investigation capability, not independent physical verification. Misuse is surfaced through operational abuse monitoring.

---

# 39. MAINTENANCE

## 39.1 Nature

Maintenance is **normal Venue operation** at Resource level. Creating, editing and deleting Maintenance are audited.

**Maintenance may never overlap an existing blocking reservation.** If the selected interval conflicts with a Pending, Awaiting Customer Confirmation, Confirmed, Active, Manual, Tournament Hold or Closure record, creation is **blocked**, with a message such as *"This resource already has reservations during the selected period."* The existing reservations must first be resolved through their normal Product flow. **Maintenance may never silently cancel a Booking.**

## 39.2 Owner Password threshold — rolling 48 hours

For each Resource, Maintenance authorisation evaluates the **total Maintenance minutes inside any rolling 48-hour window**.

| Total Maintenance in any rolling 48h window, per Resource | Owner Password |
|---|---|
| Up to 24 hours | Not required |
| More than 24 hours | **Required** |

Back-to-back or overlapping Maintenance windows on the same Resource that form one continuous period longer than 24 hours are treated as one continuous period. The rolling-window rule closes the one-minute-gap bypass, so a long Maintenance cannot be split into short windows to avoid Owner review.

The threshold is **per Resource**. Maintenance within the allowed threshold on several different Resources remains permitted without Owner Password, and remains audited. This is an intentional MVP rule.

If an edit extends a continuous Maintenance period beyond the threshold, Owner Password is required for that edit.

## 39.3 Emergency-generated Maintenance counts

All Maintenance created from a Resource Emergency Outage counts toward the same rolling 48-hour total. There is no bypass between emergency-generated and manually created Maintenance. The emergency declaration itself remains allowed without Owner Password (§41.2), but once the threshold is exceeded, additional non-emergency Maintenance requires Owner Password and the Resource enters Owner review at the end of the emergency (§43.1).

## 39.4 Overlap with Closure

A Venue Closure **may** overlap an existing Maintenance window, because a Closure affects the whole Venue while Maintenance remains a valid Resource-level historical record. Creating Maintenance over an existing Closure is unnecessary and is blocked for the overlapping period.

---

# 40. VENUE EMERGENCY CLOSURE

## 40.1 Nature and authorisation

Emergency Closure is a separate operational path for immediate emergencies: power outage, safety issue, building evacuation, serious infrastructure failure.

- **Does not require Owner Password**
- May only start **NOW**; it can never be scheduled as a future planned Closure
- Reason is mandatory
- Full audit is mandatory
- Venue Owner and Platform Admin are notified

## 40.2 Required interval

| Field | Rule |
|---|---|
| Start | NOW |
| Expected End Time | **Mandatory** — later than Start, and no later than the end of the current Operating Session |

If the Venue expects an outage beyond the current Operating Session, Emergency Closure handles the **current Operating Session only**. Future periods must be represented through a Normal Closure, an Opening Hours change or another valid future mechanism under its own authorisation rules.

## 40.3 Affected interval

"Affected" means the reservation interval overlaps `[emergencyStart, expectedEnd)`. Only overlapping reservations enter the cascade; reservations entirely after Expected End are not cancelled by that Emergency.

## 40.4 Cascade

Emergency Closure immediately:

- Stops new Online Bookings and new Manual reservations for the affected period
- Cancels affected PENDING Bookings
- Cancels affected AWAITING_CUSTOMER_CONFIRMATION Bookings
- Cancels affected CONFIRMED Bookings
- Terminates affected ACTIVE sessions
- Cancels affected Tournaments
- Releases future Resource Holds

Booking reason: `VENUE_EMERGENCY_CLOSURE`. This is the explicit exception to the rule that an ACTIVE Booking has no operator early-end function.

Attendance for an interrupted ACTIVE Booking follows §28.5: a valid Check-in gives ATTENDED_INTERRUPTED with a counter reset and no Points or progress; Late Present only gives ATTENDANCE_UNCONFIRMED; no evidence gives NOT_APPLICABLE. A valid Check-in must never leave the Customer closer to a No-show ban because of a Venue emergency.

An AWAITING_CUSTOMER_CONFIRMATION Booking whose interval is caught by the cascade is cancelled immediately under the Emergency reason — the Late Confirmation timer stops and the Customer never receives `CUSTOMER_RECONFIRM_TIMEOUT`, because the emergency reason wins.

## 40.5 Atomicity

The cascade is **all-or-nothing**. The system atomically persists the Emergency Closure, the Booking cancellations, the ACTIVE Booking terminations, the Tournament cancellations, the reservation releases and the relevant history **before** notifications are dispatched. A partial emergency cascade is not an acceptable Product outcome.

## 40.6 Irreversibility

Emergency cancellation is immediate and irreversible. Ending the Emergency early does not restore Bookings or Tournaments that were already cancelled. This is an intentional MVP decision.

## 40.7 Effect on intake

An active Emergency Closure prevents Online Intake even when normal Opening Hours show the Venue open. Ending it restores intake only if Marketplace Status permits, the Venue Account is enabled, the Area is active, a normal Operating Session is still open, and the current time is still inside the valid Online Intake Window (§21).

---

# 41. RESOURCE EMERGENCY OUTAGE

## 41.1 Nature

Resource Emergency Outage covers a failure limited to one Resource — a broken PS5, a broken TV, a damaged Billiards table, a broken Ping Pong table. It is distinct from a Venue-wide Emergency Closure, and other Venue Resources keep operating.

## 41.2 Authorisation

Venue staff may trigger it **without Owner Password**. Requirements: mandatory outage reason, timestamp, affected Resource, Venue Account identity, full audit, Venue Owner notification, Platform Admin visibility. False or misused declarations may be investigated administratively.

There is **no hard Venue-wide emergency rate limit**, because such a limit could prevent a genuine emergency declaration.

## 41.3 Effect

A Resource Emergency Outage may terminate the ACTIVE Booking on that Resource, cancel future overlapping reservations on it, cancel or affect Tournament use of it, block new availability for it, and transition it into Maintenance.

Booking reason: `RESOURCE_EMERGENCY_OUTAGE`, distinct from the Venue-wide `VENUE_EMERGENCY_CLOSURE`. Attendance treatment is identical to §40.4.

## 41.4 Interval

Staff must provide an **expected return-to-service time**. For an unprotected emergency outage it may extend only to the end of the current Operating Session. Longer Resource Maintenance requires normal Maintenance authorisation.

## 41.5 Tournament consequences

Cancellations caused by a Resource Emergency Outage — of a PENDING request, a WAITING_FOR_PLAYERS Tournament, a CONFIRMED Tournament, or an ACTIVE Tournament where all assigned Resources fail — require **no Owner Password**, because they are system consequences of an emergency operation rather than the Venue's normal Cancel Tournament action. Outside valid emergency flows, Venue cancellation of a CONFIRMED Tournament still requires Owner Password (§37.10).

## 41.6 Tournament cancellation abuse alert

Platform Admin receives an abuse/risk alert if one Venue causes **3 or more Tournament cancellations through Resource Emergency Outage within a rolling 30 days**, across any Resources in that Venue.

The alert is investigative, non-blocking and never automatically punitive. It does not suspend the Venue, does not require Owner Password for genuine future emergencies, and does not reverse past cancellations. The Admin may investigate and take explicit action.

---

# 42. EMERGENCY STATUS LIFECYCLE

## 42.1 Canonical status enum

| Status | Meaning | Emergency Selling Block |
|---|---|---|
| `ACTIVE` | From declaration until Expected End | ON |
| `AWAITING_CONFIRMATION` | Expected End passed with no staff action | ON |
| `ENDED` | Staff asserted the Venue/Resource is operational again | OFF |
| `CONVERTED` | Replaced by another operational state | OFF, only atomically with the replacement |

There is no combined status such as *ACTIVE / AWAITING OPERATIONAL CONFIRMATION*. `AWAITING_CONFIRMATION` is **not** operationally safe merely because it differs from ACTIVE.

## 42.2 Expected End does not auto-reopen

At `expectedEndAt`, if staff have not ended, extended or converted the Emergency, it becomes AWAITING_CONFIRMATION. **It does not automatically end**, and the Venue or Resource does not silently return to sale. Staff resolution is mandatory.

In AWAITING_CONFIRMATION the declared cancellation interval has ended and the Product does not fabricate a new indefinite reservation interval. The Venue or Resource simply carries the Operational Selling Block until resolved.

## 42.3 End Emergency

Staff select **END EMERGENCY** when the Venue or Resource is operational. Status → ENDED. The Emergency Selling Block **must** end at that point; it is not optional.

Ending an Emergency:

- Records `actualEndAt`
- Releases the Emergency availability block
- Does **not** restore already-cancelled Bookings or Tournaments

New availability becomes possible only under all normal rules.

**A Resource may not be declared ENDED while still broken.** END EMERGENCY means the underlying Venue or Resource is operational again, except where a separately established Operational Restriction continues. So staff may end a Resource Emergency only if either the Resource is operational again, or a valid replacement operational state/block commits atomically.

If the Resource is still unusable but does not qualify for Owner review, the Emergency must **not** transition to ENDED with no replacement block. It remains AWAITING_CONFIRMATION until the Resource is repaired and the Emergency can end, or a Maintenance conversion, Disable or Archive succeeds.

## 42.4 Extend Emergency

Before or after Expected End, staff may extend within the permitted emergency horizon. The system:

1. Calculates the newly affected interval
2. Identifies the newly affected reservations
3. Previews the impact
4. Takes confirmation
5. Atomically applies the newly required cancellations

Previously cancelled reservations remain cancelled. Emergency mode may **not** be used as an unprotected cancellation mechanism beyond the current Operating Session; future periods need a Normal Closure, Maintenance or an Opening Hours change under their own authorisation. If no action is taken, the Venue or Resource may stay blocked from new booking until operational status is resolved, but future protected reservations are never silently cancelled.

## 42.5 Convert Emergency

| Emergency | May convert into |
|---|---|
| Venue Emergency Closure | Normal Venue Closure, Opening Hours change |
| Resource Emergency Outage | Maintenance, Disabled Resource, Archived Resource |

A Resource Emergency Outage **may not** convert directly into an Opening Hours change, because Opening Hours are Venue-level operating rules and do not represent a broken Resource.

Each conversion is subject to the target action's normal authorisation and impact rules. Conversion into Maintenance is possible only if the proposed Maintenance interval passes the applicable conflict and authorisation rules; if protected future reservations conflict, the conversion cannot commit until they are resolved. Disable and Archive remain subject to the Resource state-change predicate (§13.5), so they are unavailable while current or future blocking reservations remain. **Emergency conversion never silently bypasses those protections.**

When the Emergency transitions to CONVERTED, its Emergency Selling Block must end **at the same atomic commit** that activates the replacement Maintenance, Disabled state, Archived state, Closure or Opening Hours change. No availability gap is allowed.

## 42.6 A destroyed Resource with future reservations

If a Resource cannot return to service but still has future protected reservations, the Operational Selling Block remains. The Venue must resolve the affected future commitments through the appropriate existing flows. Once the blocking reservations are resolved, the Owner may Disable, Archive, or place or extend Maintenance under normal rules.

The Dashboard must not present an unavailable conversion as immediately executable; it displays the blocking commitments and indicates that they must be resolved first — subject always to §31.12.

## 42.7 Operational reminders

The Venue Dashboard and Owner must be notified when:

- Expected End is reached but the Emergency is still active
- A Resource return-to-service time is reached but the Outage is still active
- Manual confirmation is required

This prevents forgotten indefinite operational blocks.

## 42.8 Resource state changes during an Emergency

Emergency Closure and Resource Emergency Outage block availability during their active interval. They do **not** by themselves forbid Disable, Archive or Console Change, provided Owner Password requirements pass, all remaining future reservations have been resolved, and applicable Maintenance restrictions pass.

Where a Resource Emergency Outage creates Maintenance, that Maintenance becomes the continuing Resource-level operational block, and normal Resource state-change rules then evaluate the Maintenance interval.

---

# 43. OWNER_REVIEW_REQUIRED

## 43.1 When it arises

A genuine new Resource Emergency Outage may always be declared immediately without Owner Password, for safety. But if the Resource has exceeded **24 Maintenance hours inside any rolling 48-hour window**, the Resource enters `operationalRestriction = OWNER_REVIEW_REQUIRED` when the emergency reaches its expected or actual end.

This stops staff from using repeated Emergency Outages to return a Resource to sellable inventory again and again without Owner review, while leaving emergency declaration itself always available.

## 43.2 Precedence over ending an Emergency

When a Resource Emergency ends, the rolling Maintenance threshold is evaluated **first**:

| Threshold | Result |
|---|---|
| Owner review not required | Emergency → ENDED; Selling Block releases; normal Resource eligibility resumes |
| Owner review required | Emergency → ENDED; Resource Operational Restriction → OWNER_REVIEW_REQUIRED; the Emergency Selling Block ends, but the Resource stays unavailable because of the restriction |

Selecting END EMERGENCY can never return an over-threshold Resource to sellable state. **OWNER_REVIEW_REQUIRED takes precedence over Resource availability.**

Emergency Status and Resource Operational Restriction are separate dimensions: Emergency ENDED with Restriction OWNER_REVIEW_REQUIRED is a valid combination meaning the emergency itself ended but the Resource remains unavailable.

## 43.3 The operational cascade

Entering OWNER_REVIEW_REQUIRED means the Resource can no longer honour new or existing Resource commitments until Owner review completes. Entry therefore triggers a **System Operational Cascade** — not a manual Venue cancellation action.

| Affected commitment | Outcome | Reason |
|---|---|---|
| PENDING Online Booking | → CANCELLED | `RESOURCE_OWNER_REVIEW_REQUIRED` |
| AWAITING_CUSTOMER_CONFIRMATION | → CANCELLED | `RESOURCE_OWNER_REVIEW_REQUIRED` |
| CONFIRMED with `startAt > now` | → CANCELLED | `RESOURCE_OWNER_REVIEW_REQUIRED` |
| ACTIVE Booking | → CANCELLED | `RESOURCE_OWNER_REVIEW_REQUIRED` |
| Future Manual Booking (CONFIRMED) | → CANCELLED | `RESOURCE_OWNER_REVIEW_REQUIRED` |
| Tournament PENDING_VENUE_APPROVAL / WAITING_FOR_PLAYERS / CONFIRMED | → CANCELLED | `RESOURCE_OWNER_REVIEW_REQUIRED` |
| ACTIVE Tournament | Resource removed; cancelled only if no operational Resource remains (§37.12) | `RESOURCE_OWNER_REVIEW_REQUIRED` |
| VENUE_RESPONSE_EXPIRED | Not included — it holds no Resource reservation (§43.6) | — |

The ACTIVE Booking transition is **automatic**; no Platform Admin action is required to make it happen. Cancelling a Manual Booking through this cascade **does not require Owner Password**, because it is a System Operational Consequence.

The cascade requires no Owner Password anywhere, requires system audit, and notifies the Venue Owner, Platform Admin, affected Customers and Tournament Participants.

## 43.4 The cascade is atomic

Entering OWNER_REVIEW_REQUIRED and applying its cascade occur atomically together with the Resource restriction write, the affected reservation cancellations, the Hold releases, the Booking and Tournament history and the outbox events. Notifications follow after commit. **A partial cascade is not a valid Product outcome.**

## 43.5 It is not a pause state

Affected PENDING, AWAITING and future CONFIRMED Bookings are **cancelled**, not paused waiting for Owner review. This prevents indefinite Customer commitments. A PENDING Booking cancelled this way stops its Venue response timer immediately, releases the Resource commitment and the Customer commitment slot, and never becomes VENUE_RESPONSE_EXPIRED or uses `VENUE_DID_NOT_RESPOND_IN_TIME`.

A Customer whose CONFIRMED Booking is cancelled this way receives no penalty, receives the explicit operational reason, may rebook normally, and keeps the original Booking as terminal history. No automatic replacement Booking is created. Attendance Result is NOT_APPLICABLE and there is no Trust effect. Where a future non-zero Booking Fee exists, the refund is **full**, regardless of the Customer's normal 15-minute window (§34.2).

## 43.6 Effect on expired requests

VENUE_RESPONSE_EXPIRED remains non-terminal, released from inventory and potentially Late-Confirmable. It is not part of the reservation-release cascade because it holds no reservation. But while the Resource remains OWNER_REVIEW_REQUIRED the Venue **cannot** initiate Late Confirm on it. The system records Booking Event `LATE_CONFIRM_ELIGIBILITY_LOST_RESOURCE_STATE_CHANGED` with `detailCode = RESOURCE_OWNER_REVIEW_REQUIRED`.

If the Owner clears the restriction before the original `startAt`, the expired request may become eligible for Late Confirm again if all rules pass. No automatic Late Confirm occurs. If the restriction is still in force at `startAt`, the Booking cancels under `RESOURCE_OWNER_REVIEW_REQUIRED`, never `VENUE_DID_NOT_RESPOND_IN_TIME` (§24.3).

## 43.7 Availability and race safety

Resources with `operationalRestriction = OWNER_REVIEW_REQUIRED` must not appear as available, must not contribute to Nearest Availability, and must not be selected for Online, Manual, Tournament or Late Confirm use until the restriction is cleared.

If restriction entry races with a Venue Accept, a Late Confirm, a Tournament checkpoint, a Booking Start or a Tournament Start, the **first successfully committed valid authoritative transition wins**, followed by mandatory revalidation. A restricted Resource can never acquire or retain a stronger commitment after the restriction commits, except under the ACTIVE Tournament remaining-Resource rule (§37.12). A Booking racing with restriction entry is CANCELLED under `RESOURCE_OWNER_REVIEW_REQUIRED` and may never become CONFIRMED on the restricted Resource.

## 43.8 Resolution

Owner Password is required to resolve OWNER_REVIEW_REQUIRED. The Owner may choose:

- **RETURN TO SERVICE**
- **EXTEND MAINTENANCE**
- **DISABLE RESOURCE**
- **ARCHIVE RESOURCE**

RETURN TO SERVICE requires a valid Owner Password, no Maintenance currently blocking, no Emergency Selling Block, and the Resource otherwise operational. Then `operationalRestriction → NONE` and the Base State remains or becomes ACTIVE as appropriate.

## 43.9 Audit

Audit records the restriction `enteredAt`, the trigger, the affected Bookings, the affected Manual Bookings, the affected Tournaments, the ACTIVE session outcomes, the restriction resolution and the Owner actor where applicable — along with the threshold data and the final Resource action.

---

# 44. TIMER PAUSES DURING EMERGENCIES

## 44.1 What pauses

| Timer | Pauses when |
|---|---|
| Venue response timer on a PENDING Booking | A Venue Emergency Closure, **or** a Resource Emergency Outage affecting that Booking's Resource, is in force |
| Customer Late Confirmation countdown | An active Emergency or Selling Block affects the Venue **or** the booked Resource, and the Booking has not already been cancelled by the cascade |

A Resource Emergency Outage on another Resource that does not affect the Booking pauses nothing.

While a relevant block is active, Venue Accept is disabled. The remaining duration is preserved: a 10-minute timer with 3 minutes elapsed resumes with 7 minutes.

## 44.2 The 30-minute caps

| Counter | Cap |
|---|---|
| Cumulative Venue-response emergency pause, per Booking | **30 minutes** |
| Cumulative Late Confirmation emergency pause, per AWAITING phase | **30 minutes** |

The two counters are separate. Venue Emergency and Resource Emergency pauses contribute to the **same** response-pause counter, so switching between emergency types can never reset the clock. Repeated pauses accumulate and never reset the total.

Stored per Booking: `responsePauseStartedAt`, `cumulativeEmergencyPauseDuration`, `lateConfirmPauseStartedAt`, `cumulativeLateConfirmEmergencyPauseDuration`.

## 44.3 Cap reached

| Timer | Outcome | Reason |
|---|---|---|
| Venue response, Venue-level emergency | PENDING → CANCELLED | `VENUE_RESPONSE_INTERRUPTED_BY_EMERGENCY` |
| Venue response, Resource-level emergency | PENDING → CANCELLED | `RESOURCE_RESPONSE_INTERRUPTED_BY_EMERGENCY` |
| Late Confirmation | AWAITING → CANCELLED | `LATE_CONFIRM_INTERRUPTED_BY_EMERGENCY` |

In every case the Resource reservation or temporary Hold releases, the Customer commitment slot releases, the Customer receives an emergency-related notification, no Customer penalty applies, and **no Venue non-response blame is recorded**.

## 44.4 Resumption conditions

A paused Venue response timer resumes only when **all** of:

1. The Emergency has ended
2. Venue Account = ENABLED
3. Venue Marketplace permits the action
4. The Venue is inside a valid Operating Session
5. The current time is inside the Online Intake Window

Ending the Emergency while the Venue is closed does not restart the timer; it waits for the **next valid Online Intake Window**.

A paused Late Confirmation countdown resumes only when the affecting Emergency or Selling Block is resolved, the Booking is still before `startAt`, and the Venue, Resource and Customer all remain eligible. Otherwise the cancellation uses the appropriate Product reason.

## 44.5 Deterministic early cancellation

Selling Block duration depends on staff action and is **not** predicted by the scheduler. Early-cancellation logic must not assume an unresolved Emergency lasts forever, and must not use non-computable wording.

The canonical test is an **optimistic feasibility test**: assuming the current Selling Block could resolve immediately, could the remaining Venue-response duration fit inside a valid future Online Intake Window before `startAt`?

The system evaluates the remaining response timer duration, the future valid Operating Sessions, the final-10-minute intake cutoff, `startAt`, and the active Operational Selling Block.

- **No such window exists even under immediate resolution** → cancel now with `VENUE_RESPONSE_INTERRUPTED_BY_EMERGENCY`, or the Resource equivalent
- **A window could theoretically exist but the Emergency is unresolved** → the Booking stays paused only until the 30-minute cap, which is the deterministic outer bound

## 44.6 Customer visibility

The Customer receives a notification when a Late Confirmation countdown is paused by an emergency. The UI shows *"Confirmation temporarily paused due to venue/resource issue."* On resume, the remaining response time is shown.

While paused, the Customer may still **Decline** (§29.3).

## 44.7 Pause history

Both pause records are immutable operational history supporting dispute resolution and deterministic timer reconstruction:

- `pauseStartedAt`
- `pauseEndedAt`
- `pauseCause`
- `accumulatedDuration`

---

# PART VIII — TRUST, REWARDS AND ENFORCEMENT

---

# 45. THE TRUST ENGINE

## 45.1 Canonical principle

Trust is calculated from **immutable Trust Input Events**. The following are **derived state**, never the authoritative historical source:

- Consecutive No-show Counter
- Penalty Stage
- Ban state
- Verified Player Progress
- Verified Player Status

Current Trust state must be reconstructible from the ordered event stream alone. No counter value may be inferred from a previously stored derived value.

## 45.2 Canonical Trust Input Event catalogue

**Booking Outcomes**

- `BOOKING_ATTENDED`
- `BOOKING_ATTENDANCE_UNCONFIRMED`
- `BOOKING_ATTENDED_INTERRUPTED`
- `BOOKING_NO_SHOW`

**Corrections**

- `NO_SHOW_REVERSED`
- `ATTENDANCE_CORRECTED`

**Administration**

- `ADMIN_TRUST_ADJUSTMENT`
- `VERIFIED_PLAYER_SUSPENDED`
- `VERIFIED_PLAYER_REVOKED`
- `VERIFIED_PLAYER_RESTORED`

**System / Platform Protection**

- `BOOKING_ACCOUNT_DISABLE_ATTENDANCE_PROTECTION`

Operational evidence — `CHECK_IN_RECORDED`, a `CHECK_IN_REVERSED` before completion, `LATE_PRESENT_RECORDED`, `LATE_PRESENT_REVERSED` — stays in Booking and audit history and enters Trust **only** through a resulting canonical outcome or correction. Pressing the Check-in button is not itself a Trust reward.

Ban creation and Ban expiry are **derived outputs** of Trust replay, not independent Trust inputs, although the derived audit stream may record `BAN_EXPIRED` and `BAN_EXTENDED` for history and notification.

## 45.3 Event structure

Every Trust Input Event carries:

- `eventId`
- `eventType`
- `occurredAt` — the real system time the event was recorded
- `effectiveAt` — the point in the Trust sequence where its effect belongs
- `eventSequence` — a unique immutable tie-breaker
- `subjectUserId`
- `bookingId` (optional)
- `targetEventId` (optional)
- `eventPayload`

## 45.4 Effective time rules — closed

| Event type | `effectiveAt` |
|---|---|
| Any Booking-derived Trust event | `booking.startAt` |
| Any correction of a Booking Trust event | The **same** `effectiveAt` as the event being corrected |
| `BOOKING_ACCOUNT_DISABLE_ATTENDANCE_PROTECTION` | `booking.startAt` |
| `ADMIN_TRUST_ADJUSTMENT` | `occurredAt` |
| `VERIFIED_PLAYER_SUSPENDED` / `REVOKED` / `RESTORED` | `occurredAt` |

This closes the rule for every canonical Trust Input Event type.

Because a correction shares the corrected event's `effectiveAt` and carries a later `eventSequence`, it lands immediately after the original event at the same Trust position. A No-show recorded on Monday and reversed on Friday has `occurredAt = Friday` and `effectiveAt = Monday startAt`, so replay corrects the Monday effect in its original sequence position.

## 45.5 Canonical replay order

Trust Input Events are replayed **exclusively** by:

1. `effectiveAt` ASC
2. `eventSequence` ASC

This is the single canonical Trust order. There is no competing "booking order" versus "admin chronological order".

## 45.6 Corrections never delete

A correction never deletes the original event. Both remain in audit; during replay the correction **neutralises** the targeted original effect and, where appropriate, **substitutes** the corrected canonical effect. Correction is therefore not limited to neutralisation.

`ATTENDANCE_CORRECTED` carries a mandatory `correctedOutcome`, one of ATTENDED, ATTENDED_INTERRUPTED, ATTENDANCE_UNCONFIRMED or ABSENT_NO_SHOW:

| `correctedOutcome` | Replay substitutes | Counter | Points | Progress |
|---|---|---|---|---|
| ATTENDED | equivalent of `BOOKING_ATTENDED` | Reset | Yes | +1 |
| ATTENDED_INTERRUPTED | `BOOKING_ATTENDED_INTERRUPTED` | Reset | No | No |
| ATTENDANCE_UNCONFIRMED | neutralisation only | Unchanged | No | No |
| ABSENT_NO_SHOW | `BOOKING_NO_SHOW` at the Booking's `effectiveAt` | Normal No-show rules | No | No |

When a correction establishes ATTENDED, the Points use the Completed Booking Points value applicable to the Booking's **original** completion date and configuration, while the actual transaction is posted at correction time. History records the historical value used, the correction time, the reason and the Admin identity.

## 45.7 Correction chains

A Booking may receive several Attendance Corrections over time. The **latest non-superseded Attendance Correction by `eventSequence`** is authoritative for that Booking's Trust outcome. There is no additional "valid / invalid" correction state.

Each new correction references `bookingId`, the `supersededCorrectionEventId` if any, the `originalTrustOutcomeEventId` and the `correctedOutcome`. Earlier corrections remain immutable in audit but become **superseded for current Trust replay**.

**Only one effective Attendance outcome per Booking contributes to replay.** Sequential corrections can never produce double Points, double Verified Player progress, a duplicate counter reset or a duplicate No-show penalty.

## 45.8 As-of replay

Trust replay can calculate state **as of a specific time**, so historical and current state are both reproducible from the same ordered inputs.

## 45.9 Trust effects by event — summary

| Trust event | Counter | Points | Verified Player progress |
|---|---|---|---|
| `BOOKING_ATTENDED` | Resets to 0 | Configured Completed Booking Points | +1 |
| `BOOKING_ATTENDED_INTERRUPTED` | Resets to 0 | None | None |
| `BOOKING_ACCOUNT_DISABLE_ATTENDANCE_PROTECTION` | Resets to 0 | None | None |
| `BOOKING_ATTENDANCE_UNCONFIRMED` | Unchanged | None | None |
| `BOOKING_NO_SHOW` | +1 | None | None |

**The three canonical counter-reset Trust events are exactly `BOOKING_ATTENDED`, `BOOKING_ATTENDED_INTERRUPTED` and `BOOKING_ACCOUNT_DISABLE_ATTENDANCE_PROTECTION`.** This supersedes every older closed list containing only the first two.

A separate, non-event mechanism also sets the counter to 0: reaching a penalty threshold resets it at Ban creation or Ban extension (§48.4). That is a consequence of the ladder, not a Trust Input Event, and the two mechanisms coexist.

The following neither increment nor reset the counter: Cancelled, Rejected, Venue Response Expired, Attendance-Unconfirmed completion, Manual Booking and Tournament participation.

## 45.10 Sequencing is global

Consecutive No-show tracking is **global across E7GZ PS**, not per Venue, and is ordered by Booking Start Time through the `effectiveAt` rule.

---

# 46. POINTS, REFERRALS AND WALLET

## 46.1 Points

| Event | Points at Launch |
|---|---|
| Completed Booking with qualifying attendance | **+200** |
| Referral signup | **+1,000** |
| No-show | 0 |
| Cancelled | 0 |
| Rejected | 0 |

The Completed Booking Points configuration is read at the **successful attended completion event**, and the amount actually granted is stored in the Points transaction. Later configuration changes never rewrite history.

Points and Trust are separate systems. Points are a reward currency; Trust governs standing and eligibility.

## 46.2 Points balance floor

A Customer's Points balance **may never be negative**. The minimum displayed and effective balance is **0 Points**.

When a compensating negative adjustment is required:

`appliedDeduction = MIN(currentBalance, requestedDeduction)`

If the historical amount requiring reversal exceeds the current balance, the uncompensated difference is recorded in audit as **`UNRECOVERED_REVERSAL_AMOUNT`**. It does not create a negative balance, does not become future debt, and future earned Points are **not** automatically used to repay it. This is an intentional Product rule.

## 46.3 Ledger correction principle

Points and Wallet history is **append-only**. An Admin correction never deletes an old transaction; it creates a **compensating transaction**. An original +1,000 referral reward and a −1,000 fraud reversal both remain visible in history.

## 46.4 Referrals

A User receives a referral code and link. The reward is **not** granted merely because a User record was created: the referral becomes eligible after **successful account OTP verification**.

| Referral status | Meaning |
|---|---|
| `PENDING` | The account exists but the eligibility event has not completed |
| `REWARDED` | OTP verification completed and the reward was granted |
| `INVALID` | Invalidated — invalid code, self-referral, duplicate reward, or administrative fraud action |

Only one referral reward may be granted per referred User. Self-referral is not allowed. Hard self-referral checks cover **same phone** and **same email**; same device and same IP may be stored and used as **fraud signals** but are not automatic hard blocks in MVP.

The referral reward configuration is read at **successful OTP verification**, and the actual value applied is stored permanently. A signup on Monday at 1,000, an Admin change to 500 on Tuesday, and OTP verification on Wednesday yields **500**.

Referrals grant **no Verified Player progress**.

## 46.5 Referral invalidation

If an Admin invalidates an already-REWARDED referral, its status becomes INVALID and the system creates a compensating Points transaction for the **exact historical reward applied**. No original ledger row is deleted.

A referral may become INVALID even when the full historical reward cannot be deducted because the current balance is lower. The ledger records the historical reward, the requested reversal, the applied reversal and the unrecovered amount.

## 46.6 Wallet

Every User has an E7GZ PS Wallet with an initial balance of EGP 0. It exists to support E7GZ Booking Fee refunds and a future Points conversion. **The Wallet never pays the Venue gaming charge.**

At Launch the Wallet technical capability remains in the backend and data model, but the Customer UI **does not show an active Wallet screen**, because the Booking Fee is EGP 0, Points conversion is not active and there is no funding source. The Wallet can be activated later without redesigning the ledger.

Because the Wallet is hidden and has no active Customer funding or use flow, **Platform Admin Wallet adjustment is not a Launch action**. Wallet adjustments become available only when Wallet functionality is activated. Points adjustments, by contrast, remain available at Launch because Points are active.

Points-to-Wallet conversion is a future feature; the ratio is configurable (illustratively, 2,000 Points → EGP 20).

---

# 47. PLATFORM ADMIN REMEDIATION

## 47.1 Audited support actions

**Trust**

- Reverse No-show
- Reverse Check-in
- Reverse Mark Present — Late
- Attendance Correction
- Lift Ban
- Shorten Ban
- Correct Penalty Counter
- Correct Penalty Stage where support remediation requires it

**Points**

- Positive adjustment
- Negative adjustment (subject to the balance floor, §46.2)

**Wallet**

- Positive and negative adjustment — **not available at Launch** (§46.6)

**Referral**

- Invalidate a fraudulent reward
- Reverse a reward through a compensating Points transaction

**Booking**

- Administrative cancellation or correction of a non-terminal Booking
- Exceptional termination of an ACTIVE Booking for support or emergency purposes

Every action requires a **reason**, the **Admin identity**, a **timestamp** and an **audit record**.

## 47.2 Explicit Admin Trust overrides are events

An explicit Admin Trust adjustment is recorded as a Trust Input Event and participates in replay at its chronological point. Manual correction never destroys previous Trust history.

## 47.3 Replay does not rewind the world

Trust replay may discover that a historical Ban should not have existed, should have started elsewhere, or should have existed but did not. The **Trust state** is corrected. **External Booking side-effects are never automatically rewound into the past.** This prevents replay from destroying valid later reservations or restoring slots already sold to other Customers.

| Discovery | Trust | Bookings |
|---|---|---|
| A current Ban should not exist | Ban Instance → CORRECTED / ENDED; restrictions stop; User notified; state recalculated | Previously cancelled Bookings do **not** return |
| The User newly qualifies for a Ban | Ban activates **now**, at correction time; Penalty Stage from corrected history | Normal cascade against **current** future Bookings; never retroactive |

## 47.4 No Support Replacement Booking

There is **no** special third Booking-creation path for Platform Admin. All Customer Booking creation goes through the same Online Booking predicate (§22). After a Trust correction, the Customer may create new Bookings through the normal flow if slots remain. Support may assist but cannot bypass Booking validation.

---

# 48. NO-SHOW PENALTIES AND BANS

## 48.1 Effects of a No-show

A No-show yields 0 Points, 0 Verified Player progress, and increments the Consecutive No-show Counter.

## 48.2 The penalty ladder

| Stage | Trigger | Ban |
|---|---|---|
| Stage 0 | 3 consecutive No-shows | **7 days** |
| Stage 1 | 2 consecutive No-shows | **1 month** |
| Stage 2 | 2 consecutive No-shows | **2 months** |
| Recurring maximum | Every 3 consecutive No-shows | **2 months** |

The threshold intentionally returns to **3** at the recurring maximum stage, and the maximum duration remains 2 months.

A penalty triggers when a new eligible No-show **increments the counter to the current threshold**. It is not re-evaluated merely because an old stored number exceeds a later threshold.

## 48.3 Account Standing

The system stores and displays: Penalty Stage, Current Consecutive Count, Current Threshold, Ban Start, Ban Until and Remaining Time — shown as "1 of 3", "1 of 2", "2 of 3" and so on depending on the stage.

Account Standing displays **one** current Ban with its start, end, penalty stage and category. It never displays overlapping Ban records as simultaneously active.

## 48.4 Counter reset at threshold

When a Ban is **created** because a threshold was reached, the Consecutive No-show Counter is immediately reset to **0** and the Penalty Stage advances. When the Ban later expires, the counter remains 0 unless new qualifying No-shows have occurred after the return.

A Completed Booking resets only the **current consecutive counter**. It never erases the User's historical penalty stage.

## 48.5 Ban Qualification versus Ban Instance

These two concepts must never be conflated.

**Ban Qualification** is historical Trust information, derived from replay, with a `qualificationAt` equal to the effective point in the sequence where the threshold was reached. It does **not** by itself mean the User was operationally blocked at that historical time.

**Ban Instance** is the thing that actually restricts Product behaviour. Fields conceptually include: `banInstanceId`, `qualificationAt`, `activatedAt`, `endsAt`, `penaltyStage`, `duration`, `status`, `activationReason`.

| Detection | `qualificationAt` | `activatedAt` |
|---|---|---|
| Normal live processing | The qualifying event's Trust position | Current processing time; cascade executes immediately |
| Retroactively, via correction or replay | The historical threshold point | **Correction time** — the Product never back-dates operational punishment |

A retroactively discovered Ban produces **one** Ban Instance carrying a historical `qualificationAt` and a current `activatedAt`. It never creates one historical Ban plus a second operational Ban.

Ban End Time is always `activatedAt + configured duration`, never `qualificationAt + duration`.

Whether the User was actually banned at a given historical time is answered by the Ban Instance's `activatedAt` / `endsAt`, not by the qualification point. Two historical questions are therefore distinct, and after a retroactive correction they may legitimately differ:

- **Trust Qualification As-Of** — what the corrected sequence says had accumulated by that time
- **Operational Ban As-Of** — whether a Ban Instance was actually in force at that time

Penalty Stage is recalculated from corrected Trust history and governs future thresholds. Operational Ban history that genuinely happened is preserved in audit even when a later correction changes the derived historical qualification.

## 48.6 Ban cascade on in-flight Bookings

When a Ban activates:

| Booking state | Outcome |
|---|---|
| PENDING | → CANCELLED, reason `USER_BANNED` |
| AWAITING_CUSTOMER_CONFIRMATION | → CANCELLED, reason `USER_BANNED`; the Late Confirmation Hold releases |
| Future CONFIRMED | → CANCELLED, reason `USER_BANNED` |
| ACTIVE | **Continues** — not retroactively converted merely because a Ban was created |

All associated future Resource Holds release and the Customer is notified. Because all future Confirmed Bookings are cancelled at Ban creation, a banned User cannot accumulate Check-ins, No-shows, Booking Points or Verified Player progress during the Ban from previously confirmed reservations.

Because Online Booking overlaps are forbidden, normal Trust sequencing should not create another overlapping active future session; any exceptional case is handled by Platform Admin support correction.

## 48.7 Cascade idempotency

Each operational Ban carries one immutable `banInstanceId`, and its Booking cancellation cascade executes **once only**. Repeated Trust replay must never repeat the same operational cascade.

## 48.8 One Ban Instance at a time

A User may have at most **one ACTIVE operational Ban Instance**. If live processing or a correction discovers another qualifying Ban while one is already active, the system does **not** create a concurrent Ban. Instead:

- Penalty Stage advances exactly as if a new Ban Instance had been created — the qualification is a full penalty trigger for ladder purposes
- The Consecutive No-show Counter resets to 0, matching every other successful threshold trigger
- The existing Ban Instance is extended: `newEndsAt = MAX(currentBan.endsAt, now + durationForNewStage)`

Extending an already-active Ban does **not** repeat the cancellation of Bookings already cancelled by that Ban Instance. A banned User cannot create new Bookings, so none should exist; exceptional data inconsistencies are handled administratively.

`BAN_EXTENDED` is an audit-required record storing: `banInstanceId`, `triggeringQualificationAt`, the triggering Trust event or correction, `previousPenaltyStage`, `newPenaltyStage`, `oldEndsAt`, `newEndsAt` and the timestamp. This preserves every qualification represented by one operational Ban Instance.

## 48.9 Ban Instance status

| Status | Reached by |
|---|---|
| `ACTIVE` | Ban is in force |
| `EXPIRED` | Natural duration end |
| `LIFTED` | Admin early removal |
| `CORRECTED` | Trust correction proved the Ban invalid |

The historical Ban Instance always remains in audit. Ban End is derived from Ban Start plus duration; no separate Ban Expiry input event is needed for reconstruction, though the derived audit stream may record `BAN_EXPIRED`.

## 48.10 When a Ban ends

The User returns with a Consecutive No-show Counter of 0, the existing Penalty Stage intact, and the ability to create Bookings again. Normal Trust processing resumes.

## 48.11 Banned User permissions

See §9 for the canonical permission lists. Accepting a Late Confirmation is treated as creating or restoring an active reservation and is therefore blocked during a Ban; declining one is always permitted.

---

# PART IX — REVIEWS, DASHBOARDS AND ADMINISTRATION

---

# 49. REVIEWS

## 49.1 Who may review

A reviewer must be an **Account-Verified registered User** (`account_verified = true`, which requires phone OTP). A Completed Booking is **not** required.

## 49.2 Format and identity

A Review has a 1–5 star rating and a comment. Public Reviews display the **username only**, never the full name. A username change is reflected immediately: Reviews display the **current** username. Platform Admin may see both full name and username.

## 49.3 One active Review per User per Venue

A User holds one active Review per Venue, and may Edit or Delete it. Only one non-terminal/current Review record per User per Venue is allowed at a time; historical Removed records remain for moderation history.

## 49.4 Review states

| State | Public | Editable by author | Restorable |
|---|---|---|---|
| `PENDING_MODERATION` | No | — | Approved or Removed by Admin |
| `ACTIVE` | Yes | Yes | — |
| `HIDDEN` | No | Yes — and stays HIDDEN after the edit | Admin may Restore to ACTIVE |
| `REMOVED` | No | No | **Terminal for that record** |

A normal eligible Review is created as **ACTIVE**, unless the author is in the moderation re-entry flow of §49.6.

A PENDING_MODERATION Review is visible to its author (shown as *Pending Review*) and to Platform Admin, is not public, and does not affect the Venue rating. **There is no auto-publish**: it remains pending until a Platform Admin decision, no matter how much time passes. The system may send Admin operational reminders for old pending Reviews; reminder timing is an operations configuration and never changes a Review's status.

A REMOVED Review is terminal and cannot return to ACTIVE. Its moderation history remains permanently.

## 49.5 Deletion by the author

A User deletion removes the public Review but never destroys the moderation audit and history required by Platform Admin.

## 49.6 Cooldown after removal

After an Admin removes a User's Review of a Venue, that User may not create another Review for that Venue for **30 days**. After 30 days they may submit a new Review, which starts as **PENDING_MODERATION** and is not public until approved. If the new Review is removed again, a new 30-day cooldown begins.

## 49.7 Venue and Admin powers

The Venue is **view only** on Reviews. The Venue Reviews page shows average rating, total reviews, rating distribution, and per Review the username, rating, comment and review date.

Platform Admin may Hide an ACTIVE Review, Restore a HIDDEN Review, Remove a Review, Approve a PENDING_MODERATION Review and Remove a PENDING_MODERATION Review. A REMOVED Review is not restored.

Every moderation action requires a reason and records the action, reason, date, previous status and new status.

## 49.8 Reviews survive Venue state

A SUSPENDED or INACTIVE Venue keeps its existing Reviews visible, and Account-Verified Users may still review it.

## 49.9 Notifications

The author is notified when a Review is submitted for moderation, approved, hidden or removed, with the moderation reason where appropriate (§52.3).

## 49.10 Reporting a Review

A signed-in Customer may report a public Review visible to them from the Customer Mobile App. A report requires a reason and may include optional supporting details.

Submitting a report creates a durable Review Report linked to the Review and the reporting User. **Reporting alone never changes the Review state**: it does not automatically hide, remove or de-rank the Review, and it gives the Venue no moderation authority.

Platform Admin sees Review Reports in the Review moderation surface and may resolve a report with a mandatory resolution note. If the Admin takes an existing Review moderation action, the report may reference that moderation event. Reporting creates **no new Review Status and no new moderation action**.

---

# 50. VENUE DASHBOARD

## 50.1 Modules

Overview · Pending Requests · Missed / Expired Requests · Bookings · Manual Booking · Rooms · Pricing · Availability · Maintenance & Closures · Reviews · Tournaments · Booking History · Settings

## 50.2 Overview

Today's Revenue · This Month Revenue · Today's Bookings · Pending Requests · Today's Schedule · Quick Manual Booking · Quick Create Tournament

## 50.3 Revenue

Revenue tracking covers **Online Booking Revenue**, **Manual Booking Revenue** and **Tournament Revenue**, with an optional Activity breakdown across PlayStation, Billiards and Ping Pong. All revenue aggregation uses `businessDate` as the canonical revenue-day key (§17.5).

The Venue Dashboard is deliberately **not** a POS, an accounting system, an ERP, a tax system or a payment reconciliation system. MVP does not track cash, card, Visa, Mastercard or payment terminals at all.

## 50.4 Availability schedule

The schedule displays: Free · Pending · Awaiting Customer Confirmation · Confirmed · Active · Manual · Tournament Hold · Maintenance · Closure.

## 50.5 Booking History filters

**Date Range:** Today · Yesterday · Last 7 Days · This Month · Custom Range
**Activity:** All · PlayStation · Billiards · Ping Pong
**Also:** Source · Resource · Status · Search

## 50.6 Language

The Venue Dashboard supports **English (LTR)** and **العربية (full RTL)**. Arabic mode covers the sidebar, header, KPIs, tables, filters, forms, buttons, modals, drawers, statuses, pricing, revenue, reviews, tournaments, opening hours, booking history, notifications and settings. Free-text stored data remains exactly as entered.

Language preference resolves in this order: **Venue Account preference → browser/device storage → English default.**

---

# 51. PLATFORM ADMIN

## 51.1 Modules

Dashboard · Areas · Venues · Venue Accounts · Activities · Users · Verified Players · Reviews · Bookings · No-show Records · Tournaments · Wallet · Points · Referrals · Fees · Settings

Platform Admin is **English only** in the current release.

## 51.2 Venue onboarding

Venue onboarding is **Platform-Admin managed**, not Venue self-service at Launch. It covers:

- **Venue Information** — name, area, address, coordinates, phone, images, opening hours
- **Activities** — PlayStation, Billiards, Ping Pong
- **Account** — Venue login, Venue password, initial Owner Password
- **Resources** — PS Rooms, Billiards tables, Ping Pong tables
- **Pricing** — PS5 Single/Multiplayer, PS4 Single/Multiplayer, Billiards, Ping Pong, optional Room Overrides

Venue images persist through onboarding, Venue Details, Edit Venue and navigation.

## 51.3 Venue Accounts

Platform Admin may disable a Venue Account, re-enable it, reset the Venue login password and reset the Owner Password, all **without deleting Venue data**. Resetting the Owner Password notifies the Venue. Venue login password reset in MVP is handled through Platform Admin; there is no Venue self-service Forgot Password flow.

## 51.4 Users

User management displays name, username, phone, email, OTP verification, Verified Player status, Completed Bookings, No-show standing, account status and joined date. It also surfaces any unresolved Customer Account Deletion Request so Support / Platform Admin can complete the existing audited anonymisation process.

## 51.5 Verified Players

Platform Admin may View, Suspend, Revoke and Restore Verified Player status. Every moderation action requires a reason. Admin SUSPENDED and Admin REVOKED are protected states: automatic Trust regain never overrides them (§8).

## 51.6 Global Bookings

**Filters:** date range, area, venue, activity, status, source
**Search:** Booking ID, user name, phone, venue

Booking details expose: Booking ID, user, venue, activity, resource, console, play mode, date, time, duration, Venue price, historical Booking Fee, total, source, status and full lifecycle history.

Platform Admin understands the complete extended lifecycle, including Pending, Venue Response Expired, Auto Cancelled, Late Confirm Requested, Awaiting Customer Confirmation, Customer Accepted, Customer Declined, Customer Confirmation Expired, Rejected After Expiry, Venue Opening Hours Changed, and every operational and platform reason in §33.2.

## 51.7 No-show Records

Admin sees the user, venue, booking, resource, booking date, consecutive count, penalty stage, penalty result and ban information.

## 51.8 Tournaments

Admin can inspect: tournament, host, host type, activity, play mode, venue, resources, participants, capacity, entry, prize, public/private, date and time, deadline and status.

## 51.9 Wallet, Points, Referrals

**Wallet:** user, phone, wallet balance, transaction history — and never Venue gaming revenue. Wallet adjustment is not a Launch action (§46.6).
**Points:** user, current points, booking rewards, referral rewards, transaction history.
**Referrals:** referrer, referral code, referred user, signup date, reward applied, status. The referral reward is globally configurable.

## 51.10 Fees

Admin controls the **Global Booking Fee** (Launch: EGP 0) and the **Tournament Creation Fee** (Launch: EGP 0).

## 51.11 Areas

An Area may be deactivated **only** when no Venue assigned to it has Marketplace Status ACTIVE or SUSPENDED. Such Venues must first move Area or become INACTIVE. Historical references remain unchanged.

---

# 52. NOTIFICATIONS

## 52.1 Channels at Launch

In-App · Push · Email · SMS **for OTP only**

## 52.2 Time-sensitive events — In-App + Push

Booking Created · Booking Confirmed · Booking Rejected · Venue Response Expired · Booking Auto Cancelled · Late Confirmation Received · Late Confirmation Expiring · Late Confirmation Paused · Customer Confirmation Expired · Rejected After Expiry · Customer schedule-conflict failure · Booking Starts Soon · Booking Cancelled · Booking cancelled due to Ban · Venue Opening Hours Changed · Check-in Recorded · Attendance-Unconfirmed Completion · No-show Recorded · No-show Reversed · Check-in Reversed / Attendance Corrected · Temporary Ban activated · Ban Expired · Points Earned · Verified Player Unlocked · Tournament Joined · Tournament Minimum Reached · Tournament Cancelled · Tournament approval / rejection / approval expired · Tournament checkpoint cancellation

Operational and platform causes carry their own notifications:

- `VENUE_EMERGENCY_CLOSURE` and `RESOURCE_EMERGENCY_OUTAGE` cancellations
- `RESOURCE_OWNER_REVIEW_REQUIRED` cancellation
- `VENUE_RESPONSE_INTERRUPTED_BY_EMERGENCY` and `RESOURCE_RESPONSE_INTERRUPTED_BY_EMERGENCY`
- `LATE_CONFIRM_INTERRUPTED_BY_EMERGENCY`
- `VENUE_ACCOUNT_DISABLED`
- `SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR`
- Venue/Resource Emergency start-time cancellation
- ACTIVE Booking operational interruption
- `ACTIVE_SESSION_ATTENDANCE_RECORDING_AFFECTED` (§32.13)
- Tournament Resource Availability Changed, and Tournament cancellation because no operational Resources remain
- Operational refund confirmation, and full Booking Fee refund confirmation once fee functionality is enabled
- Late Confirm eligibility restored after a Venue Account re-enable, where the Product surfaces such state

An Attendance-Unconfirmed notification must explain that **no attendance reward was applied**. Every Booking notification internally references the **specific Booking ID**, so navigation always lands on the right Booking.

## 52.3 Account, security and moderation events — In-App + Email

Password Reset · Phone / Email Change · Venue Owner Password Reset · Review Submitted for Moderation · Review Approved · Review Hidden · Review Removed · Verified Player Suspended / Revoked / Restored · Admin Trust correction

The Customer is notified whenever an Admin materially changes a Ban, a No-show, an Attendance Result, Points, a Booking status or a Referral reward in a way that affects them.

## 52.4 OTP channel

OTP for mandatory phone verification is **SMS**. Email verification may use a separate email OTP or link.

## 52.5 Venue notifications

New Booking Request · Pending Request Expiring · Booking Auto Cancelled · Customer Accepted Late Confirmation · Customer Declined Late Confirmation · Customer Confirmation Expired · New Player Tournament Request · Tournament Approval Request Expiring · Tournament Minimum Reached · Tournament Cancelled · Maintenance Starting Soon · Emergency operational reminders (§42.7) · Owner notification of Emergency Closure, Resource Emergency Outage and Owner-review entry

## 52.6 Platform Admin notifications

Venue Onboarding Completed · Venue Account Disabled · Global Fee Updated · Verified Player Revoked · Tournament Auto Cancelled · User Temporary Ban Activated · Emergency declarations · Resource Owner-review entry · Tournament-cancellation abuse alert (§41.6) · Check-in and Late-Present abuse signals (§27.10) · `SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR` alerts · Account Disable overlapping an ACTIVE Booking (§32.13)

## 52.7 Language

The Customer Mobile App is **English UI** at Launch, built localization-ready for future Arabic RTL; Arabic Customer Mobile UI is not part of the current baseline. The Venue Dashboard is fully English ↔ العربية with RTL. Platform Admin is English only.

---

# PART X — AUDIT, AUTHORIZATION AND CANONICAL REFERENCE

---

# 53. AUDIT AND HISTORICAL INTEGRITY

## 53.1 Historical data principle

Configuration changes affect **future events only**. Historical transactions retain their original values: Booking Fee, referral reward, Booking price, Tournament values applied at creation, and Late Confirm original Booking totals.

## 53.2 Historical snapshots

**Booking:** Resource ID · Resource display name · console type · play mode · Venue price · Booking Fee · start/end · Operating Day · Operating Session start/end · timezone · businessDate · Attendance Result · Points actually granted

**Tournament:** Resources · Resource names · prices · duration · total Venue Cost · creation-time price snapshot · businessDate

**Referral:** the actual reward applied

**Reservation:** `plannedStart` · `plannedEnd` · `actualReservationStart` · `actualReservationReleaseAt` · `releaseReason` — which is what lets historical availability replay explain two Bookings with overlapping planned intervals without a double-booking (§17.2)

**Operational interruption:** `interruptionAt` · `interruptionReason` · Resource/Venue cause · Attendance Result · whether a valid Check-in existed · whether a Late Present existed · Booking Fee refund amount · the initiating system/operational event

**Emergency pause history**, for both the Venue response timer and the Late Confirmation countdown: `pauseStartedAt` · `pauseEndedAt` · `pauseCause` · `accumulatedDuration`

**Account access restriction intervals:** `accountAccessRestrictionIntervals[]` per §32.9

**Owner review cascade:** restriction `enteredAt` · trigger · affected Bookings · affected Manual Bookings · affected Tournaments · ACTIVE session outcomes · restriction resolution · Owner actor

## 53.3 Audit-required actions — canonical list

**Venue operational**

- Check-in recorded and reversed
- Mark Present — Late recorded and reversed
- No-show recorded and reversed
- Attendance correction, including one resulting from a Late Present review
- Maintenance create, edit and delete
- Emergency Closure declaration, extension, end and conversion
- Resource Emergency Outage declaration, extension, end and conversion
- Resource Disabled, Re-enabled, Archived, Restored
- Console change
- Owner Password successful and failed attempts
- Opening Hours change with its affected set
- Closure save with its affected set

**Operational interruption**

- ACTIVE Booking interrupted by Venue Emergency
- ACTIVE Booking interrupted by Resource Emergency
- ACTIVE Booking interrupted by OWNER_REVIEW_REQUIRED
- ACTIVE Manual Booking operational interruption
- ACTIVE Tournament Resource removal
- ACTIVE Tournament operational cancellation because no Resources remain

**Platform and integrity**

- Venue Account disabled and enabled
- PENDING response interrupted by Account Disable
- Venue Account Disable overlapping an ACTIVE Booking
- Platform Attendance Protection evaluation
- `BOOKING_ACCOUNT_DISABLE_ATTENDANCE_PROTECTION`
- Protection neutralised by a later attendance correction
- `SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR`
- OWNER_REVIEW_REQUIRED entry and resolution

**Trust and ledger**

- `BAN_EXTENDED`
- Admin Trust adjustment
- Admin attendance correction
- Points adjustment and compensating transactions
- Referral invalidation
- Booking Fee refund correction where applicable

**Moderation**

- Review moderation actions with reason, date, previous status and new status

## 53.4 Append-only principles

- **Booking History** is append-only; previous lifecycle events must never disappear
- **Points and Wallet history** is append-only; corrections use compensating transactions (§46.3)
- **Trust Input Events** are immutable; corrections neutralise and substitute but never delete (§45.6)
- **Attendance Result** is immutable historical data subject only to audited correction (§28.6)
- **businessDate** and Operating Session snapshots are historical; later Opening Hours changes never rewrite them

## 53.5 Account deletion

The Customer may **initiate Account Deletion inside the Customer Mobile App**. Initiation creates a durable request; Support / Platform Admin completes the existing deletion/anonymisation process. The architecture must support:

- At most one unresolved Account Deletion Request per User
- Account state `DELETED` when the request is completed
- Login disabled on completion
- Removal or anonymisation of PII where appropriate
- Preservation of legally and operationally required Booking, financial and audit history
- Reviews removed from public display
- Tournament participation reconciled under the existing `ACCOUNT_DELETED` rules
- A trace from the Customer request to the completed anonymisation

The Customer initiation route never performs a destructive hard delete of retained historical rows.

---

# 54. AUTHORIZATION MATRIX

## 54.1 Owner Password — required

- Venue pricing changes
- Room Price Override — set and reset
- Cancel a CONFIRMED **Online** Booking
- Opening Hours save that cancels protected Customer Bookings
- Normal Closure save that cancels protected Customer Bookings
- Disable Resource
- Re-enable Resource
- Archive Resource
- Restore / Unarchive Resource
- Change PlayStation console type (PS4 ↔ PS5)
- Change protected Resource configuration where defined
- Maintenance exceeding the rolling 48-hour / 24-hour threshold (§39.2)
- Venue cancellation of a **CONFIRMED** Tournament
- Tournament Operational Termination
- Resolving `OWNER_REVIEW_REQUIRED` — Return to Service, Extend Maintenance, Disable, Archive

"Protected Customer Bookings" means CONFIRMED Online Bookings and AWAITING_CUSTOMER_CONFIRMATION Bookings.

## 54.2 Owner Password — not required

- Accept Booking
- Reject Booking
- Reject After Expiry
- Late Confirm
- Create Manual Booking
- Cancel a Manual Booking before Start
- Check-in
- Mark Present — Late
- No-show
- Maintenance within the allowed threshold
- Opening Hours edit with no protected cancellation
- Normal Closure with no protected cancellation
- Emergency Closure
- Resource Emergency Outage
- Venue cancellation of a WAITING_FOR_PLAYERS Tournament
- Any cancellation that is a **System Operational Consequence** — the Emergency cascades and the OWNER_REVIEW_REQUIRED cascade, including their Manual Booking and Tournament cancellations

All of these remain audited.

## 54.3 Owner Password mechanics

Owner Password is required **per protected action**. There is no 15-minute unlock, no 30-minute unlock and no Owner Session mode; each separate protected action requires fresh validation.

Successful and failed attempts are audited. Validation is rate-limited; the exact thresholds are a Security Architecture choice that does not change Product behaviour. A Platform Admin reset of the Owner Password notifies the Venue.

## 54.4 Actions reserved to Platform Admin

- Reverse Check-in, Mark Present — Late, or No-show
- Attendance Correction
- Trust adjustment, Ban lift, Ban shortening, counter and stage correction
- Points adjustment
- Referral invalidation
- Administrative cancellation or correction of a non-terminal Booking
- Exceptional termination of an ACTIVE Booking
- Cancellation of any non-completed Tournament
- Verified Player Suspend, Revoke, Restore
- Review moderation
- Venue Account disable/enable and password resets
- Global Booking Fee and Tournament Creation Fee configuration
- Area activation and deactivation
- Account deletion / anonymisation

Venue staff can reverse **none** of the attendance actions.

## 54.5 Customer capabilities by state

| Capability | Unverified | Verified | Banned |
|---|---|---|---|
| Browse, view Venues, view history, view standing | Yes | Yes | Yes |
| Create Online Booking | No | Yes | **No** |
| Accept Late Confirmation | No | Yes | **No** |
| Decline Late Confirmation | — | Yes | **Yes** |
| Cancel an existing future Booking | — | Yes | **Yes** |
| Write / edit / delete Reviews | No | Yes | **Yes** |
| Join Tournament | No | Yes | **No** |
| Create Tournament | No | Verified Player only | **No** |

---

# 55. CANONICAL ENUM APPENDIX

## 55.1 Booking Source

`ONLINE` · `MANUAL`

## 55.2 Booking Status

`PENDING` · `VENUE_RESPONSE_EXPIRED` · `AWAITING_CUSTOMER_CONFIRMATION` · `CONFIRMED` · `ACTIVE` · `COMPLETED` · `REJECTED` · `CANCELLED` · `NO_SHOW`

Terminal: COMPLETED, REJECTED, CANCELLED, NO_SHOW. `VENUE_RESPONSE_EXPIRED` is **non-terminal**.

Inventory-blocking statuses: PENDING, AWAITING_CUSTOMER_CONFIRMATION, CONFIRMED, ACTIVE — regardless of Source.

## 55.3 Attendance Result

`ATTENDED` · `ATTENDANCE_UNCONFIRMED` · `ATTENDED_INTERRUPTED` · `ABSENT_NO_SHOW` · `NOT_APPLICABLE`

## 55.4 Booking Reason Code — terminal, closed

See §33.2 for the full list with its groupings. Removed tokens are listed in §33.3.

## 55.5 Booking Event — non-terminal, closed

See §33.4. `detailCode` domains are in §33.5.

## 55.6 Tournament Status

`PENDING_VENUE_APPROVAL` · `WAITING_FOR_PLAYERS` · `CONFIRMED` · `ACTIVE` · `COMPLETED` · `REJECTED` · `CANCELLED`

Terminal: COMPLETED, REJECTED, CANCELLED.

## 55.7 Tournament Reason Code — closed

See §37.13.

## 55.8 Tournament Participant Status

`JOINED` · `LEFT` · `REMOVED` · `INELIGIBLE`

## 55.9 Venue Marketplace Status

`ONBOARDING` · `ACTIVE` · `INACTIVE` · `SUSPENDED`

## 55.10 Venue Account Status

`ENABLED` · `DISABLED`

## 55.11 Resource Base State

`ACTIVE` · `DISABLED` · `ARCHIVED`

## 55.12 Resource Operational Restriction

`NONE` · `OWNER_REVIEW_REQUIRED`

## 55.13 Emergency Status

`ACTIVE` · `AWAITING_CONFIRMATION` · `ENDED` · `CONVERTED`

## 55.14 Ban Instance Status

`ACTIVE` · `EXPIRED` · `LIFTED` · `CORRECTED`

## 55.15 Verified Player — Qualification

`NOT_QUALIFIED` · `QUALIFIED`

## 55.16 Verified Player — Operational Status

`NOT_VERIFIED` · `VERIFIED` · `SUSPENDED` · `REVOKED`

Only `VERIFIED` may create a Player-hosted Tournament.

## 55.17 Review Status

`PENDING_MODERATION` · `ACTIVE` · `HIDDEN` · `REMOVED`

## 55.18 Referral Status

`PENDING` · `REWARDED` · `INVALID`

## 55.19 Trust Input Event Type

See §45.2.

## 55.20 Never infer one domain from another

Booking Status does not determine Attendance Result. Marketplace Status does not determine Account Status. Resource Base State does not determine Operational Restriction. Ban Qualification does not determine Ban Instance. Verified Player Qualification does not determine Operational Verified Player Status. Each domain is read on its own terms.

---

# 56. CONFIGURATION BASELINE

| Rule | Launch value |
|---|---|
| Activities | PlayStation / Billiards / Ping Pong |
| Timezone | Africa/Cairo |
| Minimum Booking duration | 1 hour |
| Booking increment | 30 minutes |
| Maximum Online Booking duration | 6 hours |
| Maximum Manual Booking duration | 6 hours |
| Latest new Booking | 15 minutes before Start |
| Online intake cutoff before session end | 10 minutes |
| Venue response time | 10 minutes |
| No Venue response | → VENUE_RESPONSE_EXPIRED |
| Late Confirm Customer window | 5 minutes, capped at `startAt` |
| Late Confirm valid until | Original Start Time |
| Late Confirm expiring notification | ~1 minute before expiry, only if the window exceeds 60 seconds |
| Maximum active/upcoming Online commitments per Customer | 4 |
| Check-in window | `startAt` → `startAt + 15 min` |
| Mark Present — Late window | `startAt + 15 min` → `endAt` |
| No-show window | `startAt + 15 min` → `endAt` |
| Emergency pause cap, Venue response | 30 minutes cumulative |
| Emergency pause cap, Late Confirmation | 30 minutes cumulative |
| Maintenance Owner Password threshold | More than 24 hours in any rolling 48-hour window, per Resource |
| Booking Fee at Launch | EGP 0, per Booking |
| Booking Fee refund window, Customer-caused | 15 minutes from Booking creation, and before Start |
| Completed Booking Points | +200 |
| Referral reward | +1,000 |
| Points balance floor | 0 |
| Verified Player requirement | 10 Completed Bookings |
| Username change cooldown | 30 days |
| Review cooldown after removal | 30 days |
| OTP validity | 5 minutes |
| OTP attempts per code | 5 |
| OTP resend delay | 60 seconds |
| OTP sends per hour per destination | 5 |
| Phone visibility retention | +2 hours after the applicable point (§34.6) |
| Tournament minimum participants | 8 |
| Tournament maximum participants | 32 |
| Tournament Resources | 1–4 |
| Tournament minimum duration | 1 hour |
| Tournament creation cutoff | 2 hours before Start |
| Tournament checkpoint | 1 hour before Start |
| Tournament Venue approval window | `MIN(requestCreatedAt + 10 min, checkpointAt)` |
| Creator pending approval requests | 1 platform-wide |
| Creator retry cooldown after rejection/expiry | 5 minutes |
| Tournament Creation Fee | EGP 0 |
| Tournament-cancellation abuse alert | 3+ via Resource Emergency Outage in a rolling 30 days, per Venue |
| Initial No-show trigger | 3 consecutive → 7-day ban |
| Next trigger | 2 consecutive → 1-month ban |
| Next trigger | 2 consecutive → 2-month ban |
| Recurring trigger | 3 consecutive → 2-month ban |
| Maximum ban duration | 2 months |
| Money precision | 2 decimal places, half-up |

Every value above is configurable unless this specification states otherwise, and every change applies to **future events only**.

---

# 57. CHANGE CONTROL AND EDITORIAL NOTES

## 57.1 What is frozen

Everything in this document is the frozen Product baseline. It supersedes v4.0 through v4.12 as the single source of truth for Product behaviour. The v4.x documents remain the historical record of how each decision was reached.

## 57.2 What may change without a Product change

- Database schema, indexing and storage choices
- Locking, concurrency and transaction implementation, provided the atomicity guarantees of §31.11 hold
- API shapes, error payload structure and pagination
- Rate-limit thresholds for Owner Password and OTP
- Scheduler and job implementation
- UI layout, copy wording and component structure, provided the required distinctions of §33.6 are preserved
- Date-picker horizons and similar UI bounds that do not change a Product rule
- Monitoring, alerting and reporting implementation

## 57.3 What requires Product Change Control

- Any new member of a closed enum: Booking Reason Code, Booking Event, `detailCode` domain, Tournament Reason Code, Attendance Result, Trust Input Event Type, or any status enum in §55
- Any change to a state machine transition
- Any change to an authorization rule in §54
- Any change to the canonical availability predicate (§16) or a creation/commitment predicate
- Any change to Trust replay ordering, effective-time rules or counter-reset semantics
- Any change to the refund classification of §34.2
- Any change to a value in §56 that this document states as a fixed Product rule rather than a configurable

## 57.4 Editorial notes carried into this baseline

These five points were raised by the Thirteenth-Pass Product Freeze Confirmation Audit as non-blocking. None changes Product behaviour; each is resolved in the text above and recorded here so the resolution is traceable.

**1. Evaluating "materially prevented reliable attendance recording."** The predicate for Platform Attendance Protection is now expressed as a function of the Booking's `accountAccessRestrictionIntervals[]` measured against the Check-in and No-show windows, with Admin correction as the resolution path for genuinely ambiguous cases (§32.6). The predicate itself is unchanged from v4.11 §6.

**2. Tournament operational reason ordering.** The corpus stated no ordering among Tournament operational reasons. §37.14 now gives an explicit numbered priority mirroring the Booking-side order, so exactly one terminal Tournament reason is always deterministic.

**3. Counter resets are two mechanisms, not one list.** §45.9 states both the three canonical counter-reset Trust events and the separate non-event reset that occurs at Ban creation and Ban extension, so the two can no longer be read as contradicting each other.

**4. Protection creation time.** §32.9 states plainly that the protection event is created at attendance resolution, and that at disable time only a protection candidate is recorded — removing the older "already been triggered" phrasing.

**5. Protection brought into being by a correction.** §32.11 covers the case where an Admin correction makes protection applicable for the first time, not only the case where existing protection persists.

## 57.5 Known documentary gap

The **Eleventh-Pass Audit document** was never supplied to the audit chain. Findings F11-02 through F11-04 therefore remain unclassified by reference. This affects audit-history completeness only; it is not a freeze criterion and does not affect any rule in this baseline. The v4.11 clarification document itself is present and fully consolidated here.

## 57.6 Incorporated Product Change Requests

The following versioned Product Change Requests amend this frozen baseline and are part of its current authority:

- **PCR-001 — Tournament Participant Ineligibility Cause & Capacity Reservation** — explicit `BAN` / `ACCOUNT_DELETED` cause, temporary pre-start BAN capacity reservation and boundary-effective restoration.
- **PCR-002 — Customer-Initiated Account Deletion** — Customer App initiation with durable request; existing audited anonymisation process completes it.
- **PCR-003 — Customer Reporting of Reviews** — signed-in Customer reporting path feeding the existing Platform Admin moderation surface without changing Review Status automatically.

These PCRs are the audit trail for the Product-visible changes incorporated on 25 September 2026.

## 57.7 Next phase

With this baseline frozen, the next phase is **Technical Architecture**. No design or code work precedes it.

---

*End of E7GZ PS Product Specification v5.0 — FROZEN BASELINE.*
