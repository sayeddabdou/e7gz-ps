# E7GZ PS
# USER STORIES v1.0 — FUNCTIONAL BASELINE

**Status:** Derived working baseline  
**Purpose:** Part 1 of the feature-definition pack  
**Derived from:**  
- `E7GZ PS — Product Specification v5.0 — FROZEN BASELINE`
- `E7GZ PS — Product Requirements Document (PRD) v1.1 — APPROVED FOR BUILD`
- `E7GZ PS — Functional Requirements Specification (FRS) v1.1 — APPROVED FOR BUILD`

> **Authority rule.** This document does not create or change Product behaviour.  
> Product Specification v5.0 remains the authority on behaviour.  
> PRD v1.1 remains the authority on approved scope, priorities and acceptance.  
> FRS v1.1 remains the authority on detailed functional behaviour.

---

# 0. DOCUMENTATION RULE

## 0.1 User Story rule

**Write the User Story only where it adds actor + intent clarity; do not duplicate it mechanically for every tiny action.**

A User Story is therefore written for a meaningful Product capability or user goal, not for every validation rule, timer, state transition, reason code, system event or background job.

Those lower-level details belong in the later companion sections:

1. User Stories — **this document**
2. Acceptance Criteria
3. User Flows
4. Business Rules
5. Validation Rules
6. Error / Edge Cases
7. Out-of-Scope / MVP Exclusions

---

# 1. ACTORS / ROLES

| Actor / Role | Meaning in E7GZ PS |
|---|---|
| **Customer** | Registered user using the Customer Mobile App |
| **Account-Verified Customer** | Customer whose phone has been verified through SMS OTP |
| **Verified Player** | Customer whose operational Verified Player status is `VERIFIED` and may create a Player-hosted Tournament |
| **Tournament Creator** | Verified Player creating a Player-hosted Tournament |
| **Tournament Participant** | Eligible user joining a Tournament |
| **Venue Staff** | Staff using the one shared Venue Account for daily operations |
| **Venue Owner** | Business owner represented operationally through protected actions requiring the Owner Password |
| **Platform Admin** | E7GZ operational/admin user controlling marketplace, moderation, corrections and global configuration |
| **Support / Operations** | Platform-side operational role acting through Platform Admin capabilities |
| **System** | Automated E7GZ lifecycle processing; included as a supporting actor only where needed, not used to manufacture artificial User Stories |

---

# 2. CUSTOMER IDENTITY, ACCOUNT AND PROFILE

## US-CUS-001 — Register and verify an account

**Major Feature:** Registration & Phone Verification  
**Primary Actor:** Customer

> **As a Customer, I want to create an E7GZ PS account and verify my phone number, so that I can use marketplace actions such as booking, tournaments and reviews.**

**Intent / Value:** Establish a trustworthy customer identity without making email verification the marketplace gate.

**Source Traceability:** PRD `FR-ACC-*` · Product Spec §§7, 52.4

---

## US-CUS-002 — Maintain my profile and contact information

**Major Feature:** Profile Management  
**Primary Actor:** Customer

> **As a Customer, I want to maintain my profile, username and verified contact information, so that my account remains accurate and recoverable while my historical activity stays tied to the same User ID.**

**Intent / Value:** Let the customer manage current identity data without rewriting historical records.

**Source Traceability:** PRD `FR-ACC-*` · Product Spec §§7, 53

---

## US-CUS-003 — Understand my account standing

**Major Feature:** Account Standing  
**Primary Actor:** Customer

> **As a Customer, I want to see my current no-show standing, penalty stage and active ban information, so that I understand what marketplace actions are currently available to me and why.**

**Intent / Value:** Make Trust enforcement visible and understandable rather than opaque.

**Source Traceability:** PRD `FR-TRS-*`, `FR-ACC-*` · Product Spec §§9, 48

---

# 3. DISCOVERY AND VENUE BROWSING

## US-CUS-025 — Initiate deletion of my account

**Major Feature:** Account Deletion  
**Primary Actor:** Customer

> **As a Customer, I want to start deleting my E7GZ account from inside the app, so that I can request deletion without contacting Support first.**

**Intent / Value:** Provide an in-app initiation path while preserving the existing audited anonymisation and historical-integrity process.

**Source Traceability:** `FR-ACC-10` · Product Spec §§7.7, 53.5 · PCR-002

---

## US-CUS-004 — Discover venues by where and what I want to play

**Major Feature:** Venue Discovery  
**Primary Actor:** Customer

> **As a Customer, I want to choose an Area or use my location and select an Activity before browsing venues, so that I see relevant places for what I actually want to play.**

**Intent / Value:** Support the core `FIND` step with Activity-aware discovery.

**Source Traceability:** PRD `FR-DIS-01…14` · Product Spec §§20.1–20.2

---

## US-CUS-005 — Compare venues before opening one

**Major Feature:** Venue Cards & Map View  
**Primary Actor:** Customer

> **As a Customer, I want to compare eligible venues using location, price, rating and Nearest Availability, so that I can quickly decide which venue is worth opening.**

**Intent / Value:** Reduce the need to call or visit multiple venues just to learn basic availability and price.

**Source Traceability:** PRD `FR-DIS-*` · Product Spec §§20.3–20.4

---

## US-CUS-006 — Inspect a venue in detail

**Major Feature:** Venue Details  
**Primary Actor:** Customer

> **As a Customer, I want to see a venue's images, location, opening hours, ratings, resources, pricing and availability for my selected Activity, so that I can make an informed booking decision.**

**Intent / Value:** Give the customer enough information to move from discovery to booking confidently.

**Source Traceability:** PRD `FR-DIS-11` · Product Spec §§20.5–20.7

---

## US-CUS-007 — Search for an actually available slot

**Major Feature:** Find Available  
**Primary Actor:** Customer

> **As a Customer, I want to search by Area, Activity, date, start time and duration, so that I only see Resources that are available for the entire interval I need.**

**Intent / Value:** Convert a desired session into actionable availability rather than forcing manual slot checking.

**Source Traceability:** PRD `FR-DIS-12`, `FR-AVL-*` · Product Spec §§16, 20.9

---

# 4. ONLINE BOOKING

## US-CUS-008 — Create an online booking request

**Major Feature:** Online Booking Creation  
**Primary Actor:** Account-Verified Customer

> **As an Account-Verified Customer, I want to choose a valid Resource, date, start time and duration and submit a booking request, so that the Venue can confirm a real reservation for me.**

**Intent / Value:** Deliver the core `BOOK` step while protecting inventory and eligibility.

**Source Traceability:** PRD `FR-BOK-*`, `FR-AVL-*` · Product Spec §§19, 21, 22

---

## US-CUS-009 — Know exactly what is happening with my booking

**Major Feature:** My Bookings & Booking Status  
**Primary Actor:** Customer

> **As a Customer, I want every booking to show a clear current state and history, so that I know whether I am waiting for the Venue, confirmed, playing, completed, expired or cancelled.**

**Intent / Value:** Eliminate ambiguity around booking status, especially around non-terminal expiry and operational cancellations.

**Source Traceability:** PRD `FR-BOK-*` · Product Spec §§18, 23, 24, 33

---

## US-CUS-010 — Cancel before the session starts

**Major Feature:** Customer Cancellation  
**Primary Actor:** Customer

> **As a Customer, I want to cancel an eligible future online booking before its start time, so that I can release the commitment without receiving a no-show or Trust penalty.**

**Intent / Value:** Give customers a clear and fair exit before service starts.

**Source Traceability:** PRD `FR-BOK-31…32` · Product Spec §29

---

## US-VEN-001 — Respond to a new booking request

**Major Feature:** Venue Response  
**Primary Actor:** Venue Staff

> **As Venue Staff, I want to accept or reject a Pending booking request within the response window, so that the Customer gets a definite answer and the Resource inventory remains accurate.**

**Intent / Value:** Let the venue control real-world commitment while preventing indefinite reservation holds.

**Source Traceability:** PRD `FR-BOK-*` · Product Spec §23

---

## US-VEN-002 — Recover a missed booking request through Late Confirmation

**Major Feature:** Late Confirmation Initiation  
**Primary Actor:** Venue Staff

> **As Venue Staff, I want to ask a Customer to reactivate an expired booking when the original slot is still valid, so that a missed response does not automatically lose a genuine booking opportunity.**

**Intent / Value:** Recover venue-response failures without creating a second booking or silently reactivating the first one.

**Source Traceability:** PRD `FR-LCF-*` · Product Spec §§24–25

---

## US-CUS-011 — Decide whether to accept a Late Confirmation

**Major Feature:** Late Confirmation Response  
**Primary Actor:** Customer

> **As a Customer, I want to explicitly accept or decline a Venue's Late Confirmation request with a visible countdown, so that an expired booking is never revived without my consent.**

**Intent / Value:** Preserve customer control after the original request has already appeared expired.

**Source Traceability:** PRD `FR-LCF-*` · Product Spec §25

---

## US-CUS-012 — Understand when an operational issue ends my booking

**Major Feature:** Operational Booking Transparency  
**Primary Actor:** Customer

> **As a Customer, I want a booking cancelled by a Venue, Resource, platform or emergency issue to show the real cause, so that I am not blamed for something outside my control.**

**Intent / Value:** Protect customer trust and make dispute resolution defensible.

**Source Traceability:** PRD `FR-BOK-*`, `FR-OPS-*`, `FR-NTF-*` · Product Spec §§31, 33, 40–44, 52

---

# 5. ATTENDANCE, CHECK-IN AND NO-SHOW

## US-VEN-003 — Record that a Customer arrived on time

**Major Feature:** Check-in  
**Primary Actor:** Venue Staff

> **As Venue Staff, I want to check in an arriving Customer during the allowed window, so that attendance is recorded and legitimate rewards and Verified Player progress can be granted.**

**Intent / Value:** Create auditable attendance evidence with minimal counter friction.

**Source Traceability:** PRD `FR-ATT-*` · Product Spec §§27.1–27.2, 28

---

## US-VEN-004 — Record a Customer who is present but late

**Major Feature:** Mark Present — Late  
**Primary Actor:** Venue Staff

> **As Venue Staff, I want to mark a Customer as present after the Product Check-in window has closed, so that a real attendee is not incorrectly punished as a no-show even though they do not earn attendance rewards.**

**Intent / Value:** Separate physical presence from reward-qualifying attendance.

**Source Traceability:** PRD `FR-ATT-*` · Product Spec §§27.3, 27.5–27.6

---

## US-VEN-005 — Record a genuine no-show

**Major Feature:** No-show  
**Primary Actor:** Venue Staff

> **As Venue Staff, I want to record a Customer as a no-show when they are absent in the allowed window, so that the remaining Resource interval can be released and repeated no-shows can affect Trust standing.**

**Intent / Value:** Protect venue capacity and create evidence for the Trust ladder.

**Source Traceability:** PRD `FR-ATT-*`, `FR-TRS-*` · Product Spec §§27.4–27.7, 45, 48

---

## US-CUS-013 — Be protected from incorrect attendance penalties

**Major Feature:** Attendance Fairness  
**Primary Actor:** Customer

> **As a Customer, I want attendance and no-show decisions to be correctable from audit evidence, so that an operational or staff mistake does not permanently damage my Trust standing.**

**Intent / Value:** Make penalties defensible and reversible without deleting history.

**Source Traceability:** PRD `FR-ATT-*`, `FR-TRS-*` · Product Spec §§27.8–27.9, 45, 47

---

# 6. MANUAL BOOKING AND SHARED INVENTORY

## US-VEN-006 — Record walk-ins and phone reservations

**Major Feature:** Manual Booking  
**Primary Actor:** Venue Staff

> **As Venue Staff, I want to create a Manual Booking for a walk-in, phone reservation or private arrangement, so that offline demand uses the same Resource inventory as online bookings and tournaments.**

**Intent / Value:** Make the Dashboard the single operational inventory rather than a separate online-only calendar.

**Source Traceability:** PRD `FR-MAN-*`, `FR-AVL-*` · Product Spec §§16, 30

---

## US-VEN-007 — See one schedule for every demand source

**Major Feature:** Unified Availability Schedule  
**Primary Actor:** Venue Staff

> **As Venue Staff, I want online bookings, manual bookings, tournament holds, maintenance and closures to appear in one availability schedule, so that I can operate the Venue without double-booking a Resource.**

**Intent / Value:** Express the Venue's core product promise: one inventory.

**Source Traceability:** PRD `FR-AVL-*`, `FR-VEN-*` · Product Spec §§16, 17, 50.4

---

# 7. VENUE CONFIGURATION AND OWNER CONTROLS

## US-VEN-008 — Configure Resources for actual Venue operations

**Major Feature:** Resource Management  
**Primary Actor:** Venue Staff / Venue Owner

> **As a Venue operator, I want to manage my Rooms and Tables and their operational state, so that Customer availability reflects what the Venue can actually deliver.**

**Intent / Value:** Keep marketplace inventory aligned with real physical Resources.

**Source Traceability:** PRD `FR-VEN-*` · Product Spec §§3, 13

---

## US-OWN-001 — Protect sensitive Venue actions

**Major Feature:** Owner Password  
**Primary Actor:** Venue Owner

> **As a Venue Owner, I want sensitive pricing, protected cancellation and Resource-status actions to require my Owner Password per action, so that staff can run daily operations without being able to make high-impact owner decisions casually.**

**Intent / Value:** Balance operational speed with owner control.

**Source Traceability:** PRD `FR-VEN-01…05`, `FR-VEN-46` · Product Spec §§10, 54

---

## US-OWN-002 — Configure Venue pricing

**Major Feature:** Pricing  
**Primary Actor:** Venue Owner

> **As a Venue Owner, I want to configure the approved hourly pricing for PlayStation, Billiards and Ping Pong, including permitted PlayStation Room overrides, so that bookings and tournaments use the Venue's current commercial configuration.**

**Intent / Value:** Make displayed and historical pricing deterministic while protecting changes.

**Source Traceability:** PRD `FR-VEN-*`, `FR-BOK-*` · Product Spec §§4, 53

---

## US-OWN-003 — Manage opening hours safely

**Major Feature:** Opening Hours  
**Primary Actor:** Venue Owner / Venue Staff

> **As a Venue operator, I want to configure opening hours and see the impact on existing commitments before saving a disruptive change, so that I do not unknowingly invalidate Customer bookings or tournaments.**

**Intent / Value:** Make opening-hours changes predictable and atomic.

**Source Traceability:** PRD `FR-VEN-*` · Product Spec §14

---

## US-VEN-009 — Schedule a normal closure

**Major Feature:** Venue Closure  
**Primary Actor:** Venue Staff / Venue Owner

> **As a Venue operator, I want to record a planned closure using the normal protected flow, so that future availability and affected commitments reflect a real planned shutdown.**

**Intent / Value:** Separate planned closures from emergencies and apply the correct authorization.

**Source Traceability:** PRD `FR-VEN-*`, `FR-OPS-*` · Product Spec §15

---

## US-VEN-010 — Schedule Resource maintenance

**Major Feature:** Maintenance  
**Primary Actor:** Venue Staff / Venue Owner

> **As a Venue operator, I want to place a Resource into Maintenance without overriding existing reservations, so that broken or unavailable equipment is taken out of sale through a controlled operational process.**

**Intent / Value:** Protect existing commitments while keeping damaged Resources unsellable.

**Source Traceability:** PRD `FR-OPS-*` · Product Spec §39

---

# 8. EMERGENCIES AND OPERATIONAL RESILIENCE

## US-VEN-011 — Declare an immediate Venue emergency

**Major Feature:** Venue Emergency Closure  
**Primary Actor:** Venue Staff

> **As Venue Staff, I want to declare a genuine Venue Emergency Closure immediately without waiting for the Owner Password, so that new sales stop and affected commitments are resolved honestly when the Venue cannot operate.**

**Intent / Value:** Prioritize safety and truthful serviceability over normal protected-change friction.

**Source Traceability:** PRD `FR-OPS-*` · Product Spec §§38, 40, 42

---

## US-VEN-012 — Declare a broken Resource immediately

**Major Feature:** Resource Emergency Outage  
**Primary Actor:** Venue Staff

> **As Venue Staff, I want to declare an immediate outage on the affected Resource only, so that the broken Resource stops selling while unrelated Resources can keep operating.**

**Intent / Value:** Contain operational failures to their true scope.

**Source Traceability:** PRD `FR-OPS-*` · Product Spec §§38, 41, 42

---

## US-OWN-004 — Resolve a Resource that requires owner review

**Major Feature:** `OWNER_REVIEW_REQUIRED` Resolution  
**Primary Actor:** Venue Owner

> **As a Venue Owner, I want to review a repeatedly unavailable Resource and explicitly return it to service, extend maintenance, disable it or archive it, so that staff cannot repeatedly return a problematic Resource to sale without owner oversight.**

**Intent / Value:** Prevent emergency handling from becoming a bypass around Resource governance.

**Source Traceability:** PRD `FR-OPS-*` · Product Spec §43

---

## US-CUS-014 — Keep control when a Late Confirmation timer is paused

**Major Feature:** Emergency Timer Pause  
**Primary Actor:** Customer

> **As a Customer, I want to know when my Late Confirmation countdown is paused by an emergency and still be able to decline it, so that I am never forced to hold a frozen commitment without understanding what is happening.**

**Intent / Value:** Keep emergency recovery bounded and transparent to the Customer.

**Source Traceability:** PRD `FR-OPS-34…41` · Product Spec §44

---

# 9. VENUE ACCOUNT ACCESS AND ATTENDANCE PROTECTION

## US-ADM-001 — Disable a Venue account without pretending the Venue physically closed

**Major Feature:** Venue Account Disable / Enable  
**Primary Actor:** Platform Admin

> **As a Platform Admin, I want to disable or re-enable Venue account access independently of the Venue's physical operating state, so that I can control platform access without falsely treating an access restriction as a Venue outage.**

**Intent / Value:** Keep marketplace/account governance separate from operational serviceability.

**Source Traceability:** PRD `FR-ADM-*`, `FR-ATT-*` · Product Spec §§11, 32, 51.3

---

## US-CUS-015 — Avoid a no-show penalty when platform access prevented reliable attendance recording

**Major Feature:** Platform Attendance Protection  
**Primary Actor:** Customer

> **As a Customer, I want the platform to protect me when a Venue Account disable materially prevented reliable attendance recording, so that I receive neither an invented reward nor an unfair no-show penalty.**

**Intent / Value:** Prevent platform-caused access problems from corrupting Customer Trust.

**Source Traceability:** PRD `FR-ATT-*` · Product Spec §32

---

# 10. TOURNAMENTS

## US-PLY-001 — Create a Player-hosted Tournament

**Major Feature:** Player-hosted Tournament Creation  
**Primary Actor:** Verified Player

> **As a Verified Player, I want to request a Tournament at an eligible Venue with the required Resources and event details, so that I can organize a real event without overriding the Venue's existing commitments.**

**Intent / Value:** Enable community-organized tournaments while keeping Venue consent and inventory integrity.

**Source Traceability:** PRD `FR-TRN-*` · Product Spec §§36–37

---

## US-VEN-013 — Approve or reject a Player-hosted Tournament

**Major Feature:** Tournament Venue Approval  
**Primary Actor:** Venue Staff

> **As Venue Staff, I want to accept or reject a Player-hosted Tournament request within its approval window, so that the Venue retains control over large Resource commitments requested by a Player.**

**Intent / Value:** Ensure a Player cannot impose a Tournament on a Venue.

**Source Traceability:** PRD `FR-TRN-*` · Product Spec §37.2

---

## US-VEN-014 — Create a Venue-hosted Tournament

**Major Feature:** Venue-hosted Tournament Creation  
**Primary Actor:** Venue Staff

> **As Venue Staff, I want to create a Venue-hosted Tournament directly on available Resources, so that the Venue can organize its own events without a separate approval step.**

**Intent / Value:** Support Venue-led events as a first-class use of the same shared inventory.

**Source Traceability:** PRD `FR-TRN-*` · Product Spec §36

---

## US-CUS-016 — Discover and join a Tournament

**Major Feature:** Tournament Discovery & Join  
**Primary Actor:** Account-Verified Customer

> **As an Account-Verified Customer, I want to discover eligible public Tournaments or join a private Tournament through its invite access, so that I can participate before the event starts when capacity and eligibility allow.**

**Intent / Value:** Make Tournament participation usable while preserving privacy, eligibility and capacity integrity, including the reserved pre-start slot for a Participant who is temporarily BAN-only ineligible.

**Source Traceability:** PRD `FR-TRN-*` · Product Spec §§36.5, 37.4, 37.11

---

## US-PLY-002 — Manage participants before a Tournament starts

**Major Feature:** Tournament Participant Management  
**Primary Actor:** Tournament Creator / Venue Organizer

> **As a Tournament organizer, I want to see participation and remove participants before Start when permitted, so that the event can be managed while preserving historical participant status.**

**Intent / Value:** Give organizers practical pre-start control without rewriting participant history or confusing Active Participant count with capacity reserved for temporary BAN-only ineligibility.

**Source Traceability:** PRD `FR-TRN-*` · Product Spec §§37.4, 37.11

---

## US-PLY-003 — Know whether the Tournament is actually confirmed

**Major Feature:** Tournament Checkpoint  
**Primary Actor:** Tournament Creator

> **As a Tournament Creator, I want the platform to confirm or cancel the Tournament at the mandatory checkpoint based on active participants and operational eligibility, so that I know before Start whether the event is truly going ahead.**

**Intent / Value:** Avoid indefinite `WAITING_FOR_PLAYERS` events and return held Resources when the minimum cannot be met.

**Source Traceability:** PRD `FR-TRN-14…17` · Product Spec §§37.5–37.6

---

## US-VEN-015 — End a serious active Tournament through the correct protected path

**Major Feature:** Tournament Operational Termination  
**Primary Actor:** Venue Owner / Venue Staff with Owner Password

> **As a Venue Owner, I want a protected way to terminate an ACTIVE Tournament for a serious Tournament-specific incident, so that the Venue can respond to exceptional event-level problems without inventing a generic early-end action for ordinary bookings.**

**Intent / Value:** Provide explicit authority for exceptional active-Tournament termination.

**Source Traceability:** PRD `FR-TRN-21…25` · Product Spec §§37.9–37.10

---

# 11. TRUST, BANS AND VERIFIED PLAYER

## US-CUS-017 — Build Verified Player status through real attendance

**Major Feature:** Verified Player Qualification  
**Primary Actor:** Customer

> **As a Customer, I want qualifying attended bookings to build visible Verified Player progress, so that consistent real attendance can unlock the ability to host Player Tournaments.**

**Intent / Value:** Reward demonstrated marketplace reliability rather than arbitrary account age.

**Source Traceability:** PRD `FR-ACC-*`, `FR-TRS-*` · Product Spec §8

---

## US-CUS-018 — Be treated fairly when Trust history is corrected

**Major Feature:** Trust Replay & Corrections  
**Primary Actor:** Customer

> **As a Customer, I want my Trust standing to be recalculated from immutable evidence when an attendance or no-show mistake is corrected, so that my current counter, ban and Verified Player state reflect the corrected history.**

**Intent / Value:** Make enforcement reconstructible and defensible.

**Source Traceability:** PRD `FR-TRS-*` · Product Spec §§45, 47, 48

---

## US-VEN-016 — Be protected from repeated Customer no-shows

**Major Feature:** No-show Enforcement  
**Primary Actor:** Venue Operator

> **As a Venue operator, I want repeated qualifying Customer no-shows to trigger the defined platform-wide penalty ladder, so that unreliable behaviour has consequences without Venue staff manually deciding punishments.**

**Intent / Value:** Protect Venue capacity with consistent, evidence-based enforcement.

**Source Traceability:** PRD `FR-TRS-*` · Product Spec §48

---

# 12. POINTS, REFERRALS AND REWARDS

## US-CUS-019 — Earn points for qualifying attendance

**Major Feature:** Completed Booking Points  
**Primary Actor:** Customer

> **As a Customer, I want to earn points when a qualifying booking completes with verified attendance, so that reliable participation is visibly rewarded.**

**Intent / Value:** Reward the behaviour the platform wants to reinforce.

**Source Traceability:** PRD `FR-PTS-*` · Product Spec §46

---

## US-CUS-020 — Earn a referral reward after a genuine verified signup

**Major Feature:** Referrals  
**Primary Actor:** Customer / Referrer

> **As a Customer, I want to share my referral and receive the configured reward only after the referred user completes the required phone verification, so that referrals reward real verified acquisition rather than unverified signups.**

**Intent / Value:** Encourage growth while protecting against self-referral and invalid rewards.

**Source Traceability:** PRD `FR-PTS-*` · Product Spec §46

---

## US-CUS-021 — See my reward history without confusing it with Venue payments

**Major Feature:** Points & Wallet History  
**Primary Actor:** Customer

> **As a Customer, I want my points and future wallet ledger activity to remain historically traceable, so that rewards and corrections are understandable even though the active Wallet screen and payment collection are not part of Launch.**

**Intent / Value:** Preserve financial/reward integrity without pretending E7GZ is collecting Venue gaming charges at Launch.

**Source Traceability:** PRD `FR-PTS-*` · Product Spec §§5, 46, 53

---

# 13. REVIEWS

## US-CUS-022 — Review a Venue

**Major Feature:** Customer Reviews  
**Primary Actor:** Account-Verified Customer

> **As an Account-Verified Customer, I want to rate and comment on a Venue without needing a completed booking, so that I can share my experience and help other Customers evaluate the Venue.**

**Intent / Value:** Build marketplace trust through verified-account reviews.

**Source Traceability:** PRD `FR-REV-*` · Product Spec §49

---

## US-CUS-023 — Manage my current Review

**Major Feature:** Review Edit / Delete  
**Primary Actor:** Review Author

> **As a Review author, I want to edit or delete my current eligible Review, so that my public opinion can remain accurate while moderation history is preserved.**

**Intent / Value:** Give authors ownership of current content without destroying audit history.

**Source Traceability:** PRD `FR-REV-*` · Product Spec §§49.3–49.6

---

## US-CUS-026 — Report an abusive Review

**Major Feature:** Review Reporting  
**Primary Actor:** Customer

> **As a Customer, I want to report a public Review that I can see, so that abusive user-generated content can be brought to Platform Admin attention.**

**Intent / Value:** Add a user reporting path without allowing reports to auto-moderate content or giving Venues moderation authority.

**Source Traceability:** `FR-REV-11` · Product Spec §49.10 · PCR-003

---

## US-VEN-017 — Understand Customer feedback without moderating it

**Major Feature:** Venue Reviews View  
**Primary Actor:** Venue Staff / Venue Owner

> **As a Venue operator, I want to view my rating, rating distribution and Customer Reviews, so that I can understand feedback without having the power to moderate or remove it.**

**Intent / Value:** Give Venues useful feedback while keeping moderation independent.

**Source Traceability:** PRD `FR-REV-*`, `FR-VEN-*` · Product Spec §49.7

---

# 14. VENUE DASHBOARD OPERATIONS

## US-VEN-018 — Operate the Venue from one Dashboard

**Major Feature:** Venue Dashboard Overview  
**Primary Actor:** Venue Staff

> **As Venue Staff, I want one Dashboard showing current bookings, pending requests, schedule and quick operational actions, so that I can run daily reservations without switching between disconnected tools.**

**Intent / Value:** Make the Dashboard the practical operational home for the Venue.

**Source Traceability:** PRD `FR-VEN-*` · Product Spec §§50.1–50.4

---

## US-VEN-019 — Review booking history and revenue

**Major Feature:** Booking History & Revenue Summary  
**Primary Actor:** Venue Owner / Venue Staff

> **As a Venue operator, I want to filter Booking History and see Online, Manual and Tournament revenue by the canonical business date, so that I can understand Venue activity without turning E7GZ into a POS or accounting system.**

**Intent / Value:** Provide operational reporting while preserving the MVP boundary.

**Source Traceability:** PRD `FR-VEN-*`, `FR-AUD-*` · Product Spec §§17.5, 50.3–50.5

---

## US-VEN-020 — Use the Dashboard in English or Arabic

**Major Feature:** Venue Dashboard Language  
**Primary Actor:** Venue Staff

> **As Venue Staff, I want to use the Venue Dashboard in English or full Arabic RTL, so that daily operations are usable by the people actually working at the Venue.**

**Intent / Value:** Support Launch operations in the Venue's real language context.

**Source Traceability:** PRD `FR-VEN-*` · Product Spec §§2.4, 50.6, 52.7

---

# 15. PLATFORM ADMIN — MARKETPLACE CONTROL

## US-ADM-002 — Onboard a Venue completely

**Major Feature:** Venue Onboarding  
**Primary Actor:** Platform Admin

> **As a Platform Admin, I want to onboard a Venue with its information, Activities, account, Resources, pricing, images and opening hours, so that it enters the marketplace with a complete operational configuration.**

**Intent / Value:** Keep Launch onboarding controlled and internally managed.

**Source Traceability:** PRD `FR-ADM-*` · Product Spec §51.2

---

## US-ADM-003 — Control Venue account access

**Major Feature:** Venue Account Administration  
**Primary Actor:** Platform Admin

> **As a Platform Admin, I want to disable, re-enable and reset Venue credentials without deleting Venue data, so that access problems or marketplace controls can be handled independently of historical operations.**

**Intent / Value:** Preserve data while controlling platform access.

**Source Traceability:** PRD `FR-ADM-*` · Product Spec §51.3

---

## US-ADM-004 — Investigate any Booking end to end

**Major Feature:** Global Booking Management  
**Primary Actor:** Platform Admin

> **As a Platform Admin, I want to search and inspect any Booking with its full lifecycle, historical pricing, source, statuses, events and terminal reason, so that support cases and disputes can be investigated from evidence.**

**Intent / Value:** Make support and remediation traceable rather than dependent on screenshots or user claims.

**Source Traceability:** PRD `FR-ADM-*`, `FR-AUD-*` · Product Spec §§33, 51.6, 53

---

## US-ADM-005 — Correct attendance and Trust mistakes

**Major Feature:** Admin Remediation  
**Primary Actor:** Platform Admin

> **As a Platform Admin, I want to reverse incorrect attendance evidence and issue audited Trust corrections with a mandatory reason, so that Customer standing can be repaired without deleting or rewriting historical events.**

**Intent / Value:** Provide a controlled correction path for high-impact mistakes.

**Source Traceability:** PRD `FR-ATT-*`, `FR-TRS-*`, `FR-ADM-*` · Product Spec §§27.8–27.9, 45, 47

---

## US-ADM-006 — Moderate Verified Player status

**Major Feature:** Verified Player Moderation  
**Primary Actor:** Platform Admin

> **As a Platform Admin, I want to suspend, revoke or restore operational Verified Player status with a reason, so that marketplace privilege can be moderated without rewriting qualification history.**

**Intent / Value:** Separate historical qualification from current operational privilege.

**Source Traceability:** PRD `FR-ACC-*`, `FR-ADM-*` · Product Spec §§8, 51.5

---

## US-ADM-007 — Moderate Reviews

**Major Feature:** Review Moderation  
**Primary Actor:** Platform Admin

> **As a Platform Admin, I want to hide, restore, approve or remove Reviews through audited moderation actions, so that public marketplace content can be controlled without giving Venues moderation power.**

**Intent / Value:** Protect marketplace quality while preserving moderation history.

**Source Traceability:** PRD `FR-REV-*`, `FR-ADM-*` · Product Spec §49

---

## US-ADM-008 — Inspect and intervene in Tournaments

**Major Feature:** Tournament Administration  
**Primary Actor:** Platform Admin

> **As a Platform Admin, I want to inspect complete Tournament details and cancel a non-completed Tournament when necessary, so that the platform can support exceptional operational or moderation cases.**

**Intent / Value:** Give the platform oversight without taking over normal Venue tournament operations.

**Source Traceability:** PRD `FR-TRN-*`, `FR-ADM-*` · Product Spec §§37.9, 51.8

---

## US-ADM-009 — Manage marketplace Areas

**Major Feature:** Areas Administration  
**Primary Actor:** Platform Admin

> **As a Platform Admin, I want to activate, deactivate and manage Areas subject to Venue-status protections, so that discovery geography can evolve without breaking active marketplace Venues or historical references.**

**Intent / Value:** Keep geography configurable and historically safe.

**Source Traceability:** PRD `FR-VEN-*`, `FR-ADM-*` · Product Spec §§12, 51.11

---

## US-ADM-010 — Configure global platform fees

**Major Feature:** Global Fees  
**Primary Actor:** Platform Admin

> **As a Platform Admin, I want to configure the Global Booking Fee and Tournament Creation Fee from their Launch value of EGP 0, so that future commercial activation can use the already-defined historical and refund model.**

**Intent / Value:** Keep the commercial mechanism configurable while Launch remains fee-free.

**Source Traceability:** PRD `FR-PTS-*`, `FR-ADM-*` · Product Spec §§5, 34, 51.10

---

## US-ADM-011 — Investigate Points and Referral history

**Major Feature:** Points & Referrals Administration  
**Primary Actor:** Platform Admin

> **As a Platform Admin, I want to inspect Points and Referral transactions and apply the permitted audited corrections, so that reward disputes can be resolved without destructive edits.**

**Intent / Value:** Keep reward ledgers explainable and correctable.

**Source Traceability:** PRD `FR-PTS-*`, `FR-TRS-*`, `FR-ADM-*` · Product Spec §§46, 47, 51.9, 53

---

## US-ADM-012 — Receive operational and abuse alerts

**Major Feature:** Platform Operational Monitoring  
**Primary Actor:** Platform Admin

> **As a Platform Admin, I want to receive non-blocking operational, integrity and abuse alerts, so that suspicious or risky patterns can be investigated without automatically punishing Customers or Venues.**

**Intent / Value:** Surface risk while keeping enforcement tied to explicit Product rules.

**Source Traceability:** PRD `FR-ATT-*`, `FR-OPS-*`, `FR-NTF-*` · Product Spec §§27.10, 31.4, 41.6, 52.6

---

## US-ADM-013 — Complete a supported account deletion or anonymisation

**Major Feature:** Account Deletion / Anonymisation  
**Primary Actor:** Platform Admin / Support

> **As a Platform Admin, I want to complete Customer-initiated or Support-initiated account deletion/anonymisation while preserving required operational, financial and audit history, so that personal data can be removed appropriately without destroying historical integrity.**

**Intent / Value:** Support account lifecycle management without making historical transactions disappear.

**Source Traceability:** PRD `FR-ACC-*`, `FR-ADM-*` · Product Spec §53.5

---

# 16. NOTIFICATIONS AND COMMUNICATION

## US-CUS-024 — Receive time-critical booking and tournament notifications

**Major Feature:** Customer Notifications  
**Primary Actor:** Customer

> **As a Customer, I want time-sensitive booking, attendance, ban, points and Tournament events to reach me through the defined channels and open the correct entity, so that I can react before short deadlines expire.**

**Intent / Value:** Make timer-driven Product behaviour usable in practice.

**Source Traceability:** PRD `FR-NTF-*` · Product Spec §§52.1–52.4

---

## US-VEN-021 — Receive operational Venue notifications

**Major Feature:** Venue Notifications  
**Primary Actor:** Venue Staff / Venue Owner

> **As a Venue operator, I want timely notifications about booking requests, expiring actions, Tournament approvals, maintenance and emergency reminders, so that operational commitments do not fail simply because staff missed a screen.**

**Intent / Value:** Support the Venue's real-time operational workload.

**Source Traceability:** PRD `FR-NTF-*` · Product Spec §52.5

---

# 17. AUDIT, HISTORY AND DISPUTE RESOLUTION

## US-ADM-014 — Reconstruct what actually happened

**Major Feature:** Audit & Historical Integrity  
**Primary Actor:** Platform Admin / Support

> **As a Platform Admin or Support operator, I want append-only Booking, Trust, attendance, reward, emergency and configuration history, so that I can reconstruct what actually happened at the time of a dispute or correction.**

**Intent / Value:** Make important decisions explainable and prevent later configuration changes from rewriting the past.

**Source Traceability:** PRD `FR-AUD-*`, `FR-TRS-*` · Product Spec §53

---

## US-VEN-022 — Trust one inventory across every reservation source

**Major Feature:** Inventory Integrity  
**Primary Actor:** Venue Operator

> **As a Venue operator, I want online bookings, manual bookings and Tournaments to compete for the same canonical Resource inventory, so that E7GZ never sells the same Resource twice for overlapping active occupancy.**

**Intent / Value:** Express the central operational promise of the Product.

**Source Traceability:** PRD `FR-AVL-*` · Product Spec §§16–17

---

# 18. USER STORY COVERAGE SUMMARY

The stories above intentionally cover **major Product goals and actor intentions**, not every Functional Requirement.

They are organized around the meaningful capabilities of the three approved Product surfaces:

- **Customer Mobile App**
  - Account & verification
  - Discovery
  - Venue details / Find Available
  - Online booking
  - My Bookings
  - Late Confirmation
  - Cancellation
  - Attendance fairness
  - Account Standing
  - Verified Player
  - Points / Referrals
  - Reviews
  - Tournaments
  - Notifications

- **Venue Dashboard**
  - Booking response
  - Late Confirmation initiation
  - Attendance operations
  - Manual Booking
  - Unified schedule
  - Resource management
  - Pricing
  - Opening hours / closures
  - Maintenance
  - Emergencies
  - Owner review
  - Owner Password
  - Tournament operations
  - Reviews view
  - Revenue / Booking History
  - English / Arabic RTL
  - Venue notifications

- **Platform Admin**
  - Venue onboarding
  - Venue account control
  - Global Booking investigation
  - Attendance / Trust corrections
  - Verified Player moderation
  - Review moderation
  - Tournament oversight
  - Areas
  - Global fees
  - Points / Referral administration
  - Operational / abuse alerts
  - Account deletion / anonymisation
  - Audit / dispute resolution

---

# 19. DELIBERATELY NOT EXPANDED INTO SEPARATE USER STORIES

The following are important Product rules, but **do not need standalone User Stories** because actor + intent is already expressed by the larger feature stories:

- Individual timer boundaries such as `now < deadline`
- Every individual Booking Reason Code
- Every Booking Event token
- Each `detailCode`
- Each atomic state transition
- Each refund-mapping row
- Each race / locking requirement
- Each audit field
- Each notification event name
- Every Owner Password yes/no row
- Every individual validation predicate
- System-only trust replay ordering
- Background scheduler execution
- Canonical enum membership

These belong in **Business Rules, Validation Rules, Acceptance Criteria, User Flows and Error / Edge Cases**, not as artificially duplicated User Stories.

---

# 20. NEXT DOCUMENTS IN THIS PACK

To complete the requested feature-definition pack, the next documents should be generated from the same frozen authorities in this order:

1. **Acceptance Criteria**
2. **User Flows**
3. **Business Rules**
4. **Validation Rules**
5. **Error / Edge Cases**
6. **Out-of-Scope / MVP Exclusions**

For every major feature, those documents will identify:
- actors / roles
- inputs
- outputs
- happy path
- alternate paths
- error paths
- business rules
- validation rules
- acceptance criteria

without reopening or changing the frozen Product.
