# E7GZ PS
# ACCEPTANCE CRITERIA v1.0 — FUNCTIONAL BASELINE

**Status:** Derived working baseline
**Scope:** Acceptance Criteria only
**Related document:** `E7GZ PS — User Stories v1.0 — Functional Baseline`
**Derived from:**
- `E7GZ PS — Product Specification v5.0 — FROZEN BASELINE`
- `E7GZ PS — Product Requirements Document (PRD) v1.1 — APPROVED FOR BUILD`
- `E7GZ PS — Functional Requirements Specification (FRS) v1.1 — APPROVED FOR BUILD`

> **Authority rule.** This document does not create or change Product behaviour. Product Specification v5.0 remains the authority on behaviour; PRD v1.1 remains the authority on approved scope, priorities and canonical acceptance; FRS v1.1 remains the authority on detailed functional behaviour. If wording here conflicts with an authority, the authority wins and this document must be corrected.

---

# 0. ACCEPTANCE-CRITERIA RULES

- Criteria are written only where they make a major feature objectively testable.
- Criteria do **not** add new states, timers, enums, reason codes, authorisation rules, Product values or implementation choices.
- Exact frozen boundaries are kept where they materially determine pass/fail behaviour.
- Lower-level Business Rules, Validation Rules, Error / Edge Cases and full User Flows remain separate companion documents.
- Acceptance Criterion IDs use `AC-<User Story ID>-NN`.

**Coverage:** 69 / 69 User Stories from the User Stories baseline have Acceptance Criteria in this document.

---

# 2. CUSTOMER IDENTITY, ACCOUNT AND PROFILE

## US-CUS-001 — Register and verify an account

**Traceability:** PRD `FR-ACC-*` · Product Spec §§7, 52.4

- **AC-US-CUS-001-01:** Registration requires Full Name, Username, Phone, Email and Password; Username and Phone are unique, and Username uniqueness is evaluated on a trimmed, case-insensitive normalised form.
- **AC-US-CUS-001-02:** An account becomes Account Verified only after successful SMS OTP verification of the phone number; email verification alone never unlocks marketplace actions.
- **AC-US-CUS-001-03:** Before phone verification, the user may browse and view venues but cannot create an online booking, join or create a tournament, or write a review.
- **AC-US-CUS-001-04:** OTP validity is 5 minutes, with at most 5 attempts per code, resend no sooner than 60 seconds, and no more than 5 sends per hour per destination; issuing a new OTP invalidates the previous one.
- **AC-US-CUS-001-05:** A 6th OTP send inside the same rolling hour to the same destination is refused.

---

## US-CUS-002 — Maintain my profile and contact information

**Traceability:** PRD `FR-ACC-*` · Product Spec §§7, 53

- **AC-US-CUS-002-01:** Full Name may be edited without a cooldown; Username may be changed only when the 30-day username cooldown has elapsed.
- **AC-US-CUS-002-02:** Changing Phone or Email requires successful verification of the new value before the change becomes authoritative.
- **AC-US-CUS-002-03:** A password reset can use verified phone OTP or email verification and does not require the old password.
- **AC-US-CUS-002-04:** Historical entities continue to reference the immutable User ID after profile edits, while public Reviews display the current Username.
- **AC-US-CUS-002-05:** A second Username change attempted inside the 30-day cooldown is refused and exposes the remaining cooldown.

---

## US-CUS-003 — Understand my account standing

**Traceability:** PRD `FR-TRS-*`, `FR-ACC-*` · Product Spec §§9, 48

- **AC-US-CUS-003-01:** Account Standing displays the current penalty stage, consecutive no-show count, current threshold, ban start, ban-until value and remaining time when a ban is active.
- **AC-US-CUS-003-02:** At most one current ACTIVE Ban Instance is presented to the user at a time.
- **AC-US-CUS-003-03:** A banned user can still log in, browse, view history and standing, cancel an existing future booking, decline a Late Confirmation, and write/edit/delete Reviews.
- **AC-US-CUS-003-04:** A banned user cannot create an online booking, accept a Late Confirmation, join a tournament or create a tournament.

---

# 3. DISCOVERY AND VENUE BROWSING

## US-CUS-025 — Initiate deletion of my account

**Traceability:** `FR-ACC-10` · Product Spec §§7.7, 53.5 · PCR-002

- **AC-US-CUS-025-01:** An authenticated Customer can start Account Deletion from inside the Customer Mobile App.
- **AC-US-CUS-025-02:** Initiation creates one durable deletion request linked to the immutable User ID; repeating the action while it is unresolved does not create a second deletion operation.
- **AC-US-CUS-025-03:** Initiation does not hard-delete Booking, financial, audit or other retained Product history.
- **AC-US-CUS-025-04:** When Support / Platform Admin completes the request, the request is linked to the completed anonymisation, login is disabled, PII is removed/anonymised as defined, Reviews leave public display and existing Tournament `ACCOUNT_DELETED` consequences are applied.

---

## US-CUS-004 — Discover venues by where and what I want to play

**Traceability:** PRD `FR-DIS-01…14` · Product Spec §§20.1–20.2

- **AC-US-CUS-004-01:** The discovery sequence is Area / Location → Activity → Venue; Activity is selected before venue discovery.
- **AC-US-CUS-004-02:** With location enabled and no manual Area selected, eligible Venues across Active Areas are returned and are primarily sorted by distance.
- **AC-US-CUS-004-03:** When the Customer manually selects an Area, that Area becomes a hard filter even if location permission remains enabled.
- **AC-US-CUS-004-04:** Normal discovery excludes Venues whose Marketplace Status is ONBOARDING, INACTIVE or SUSPENDED.

---

## US-CUS-005 — Compare venues before opening one

**Traceability:** PRD `FR-DIS-*` · Product Spec §§20.3–20.4

- **AC-US-CUS-005-01:** Every Venue Card shows venue image, venue name, area, distance where available, starting price, Nearest Availability, average rating and review count.
- **AC-US-CUS-005-02:** Nearest Availability is calculated for the currently selected Activity using a minimum booking duration of 1 hour and the canonical availability rules.
- **AC-US-CUS-005-03:** Resources that are DISABLED, ARCHIVED, under OWNER_REVIEW_REQUIRED, or affected by an operational selling block never contribute to Nearest Availability.
- **AC-US-CUS-005-04:** Map View applies the same eligibility rules as List View and its marker preview includes venue name, distance, starting price, Nearest Availability, average rating, review count and a View Venue action.

---

## US-CUS-006 — Inspect a venue in detail

**Traceability:** PRD `FR-DIS-11` · Product Spec §§20.5–20.7

- **AC-US-CUS-006-01:** Venue Details shows images, venue name, area, address, distance, opening hours, rating, Reviews, View Location, selected Activity, Resources, pricing and availability.
- **AC-US-CUS-006-02:** A currently closed Venue remains browsable and priced, including future availability, while new online booking submission remains disabled until a valid online intake window exists.
- **AC-US-CUS-006-03:** A SUSPENDED or INACTIVE Venue is absent from normal discovery, but a valid deep link may still render with booking actions disabled.
- **AC-US-CUS-006-04:** The Venue phone is hidden before a Booking exists; normal counterpart phone visibility begins when an Online Booking becomes PENDING.

---

## US-CUS-007 — Search for an actually available slot

**Traceability:** PRD `FR-DIS-12`, `FR-AVL-*` · Product Spec §§16, 20.9

- **AC-US-CUS-007-01:** Find Available accepts Area, Activity, date, start time and duration as search inputs.
- **AC-US-CUS-007-02:** Only Resources available for the entire requested interval under the canonical availability predicate are returned.
- **AC-US-CUS-007-03:** A Resource affected by a blocking reservation, Maintenance, Closure, relevant Emergency, selling block, non-ACTIVE base state or OWNER_REVIEW_REQUIRED is not returned as available.
- **AC-US-CUS-007-04:** No price filter is offered in this release.

---

# 4. ONLINE BOOKING

## US-CUS-008 — Create an online booking request

**Traceability:** PRD `FR-BOK-*`, `FR-AVL-*` · Product Spec §§19, 21, 22

- **AC-US-CUS-008-01:** Online booking creation is refused unless the Customer is phone-verified, not banned, below the 4-commitment limit and free of an overlapping online commitment.
- **AC-US-CUS-008-02:** Creation is refused unless Venue, Area and Resource eligibility, operating-session, intake-window, selling-block and reservation-conflict conditions all pass the frozen creation predicate.
- **AC-US-CUS-008-03:** The requested Start Time must be at least 15 minutes in the future; exactly 15 minutes is allowed and 14 minutes 59 seconds is refused.
- **AC-US-CUS-008-04:** Online duration must be 1 to 6 hours in 30-minute increments and fit entirely inside one future Operating Session.
- **AC-US-CUS-008-05:** A successful request creates one PENDING Booking, reserves the Resource and starts the 10-minute Venue response window; there is no auto-accept.
- **AC-US-CUS-008-06:** A fifth active/upcoming online commitment is refused.

---

## US-CUS-009 — Know exactly what is happening with my booking

**Traceability:** PRD `FR-BOK-*` · Product Spec §§18, 23, 24, 33

- **AC-US-CUS-009-01:** My Bookings projects PENDING, AWAITING_CUSTOMER_CONFIRMATION, CONFIRMED and ACTIVE under Upcoming; COMPLETED under Completed; and VENUE_RESPONSE_EXPIRED, CANCELLED, REJECTED and NO_SHOW under Cancelled.
- **AC-US-CUS-009-02:** VENUE_RESPONSE_EXPIRED is displayed to the Customer as an expired/non-response outcome but remains non-terminal internally until the original Start Time.
- **AC-US-CUS-009-03:** An AWAITING_CUSTOMER_CONFIRMATION Booking is actionable and shows the remaining response time.
- **AC-US-CUS-009-04:** Booking History is append-only; lifecycle events remain visible after later revival or correction.

---

## US-CUS-010 — Cancel before the session starts

**Traceability:** PRD `FR-BOK-31…32` · Product Spec §29

- **AC-US-CUS-010-01:** An eligible Online Booking can be cancelled by the Customer only while now < startAt.
- **AC-US-CUS-010-02:** A successful Customer cancellation transitions the Booking to CANCELLED with reason CUSTOMER_CANCELLED, releases the held commitment and creates no no-show or Trust penalty.
- **AC-US-CUS-010-03:** At now >= startAt, normal Customer cancellation is disabled.
- **AC-US-CUS-010-04:** A Customer may still cancel a PENDING Booking while its Venue response timer is paused by an emergency.

---

## US-VEN-001 — Respond to a new booking request

**Traceability:** PRD `FR-BOK-*` · Product Spec §23

- **AC-US-VEN-001-01:** Venue Staff may Accept or Reject a PENDING Booking only before the authoritative response deadline; expiry wins at now >= venueResponseExpiresAt.
- **AC-US-VEN-001-02:** Venue Accept revalidates the existing commitment, Resource and Customer eligibility atomically before the Booking may become CONFIRMED.
- **AC-US-VEN-001-03:** A Venue that becomes INACTIVE or SUSPENDED after request creation may still Accept the existing PENDING commitment if all Category-B operational checks pass.
- **AC-US-VEN-001-04:** Venue Reject requires a venue-supplied reject reason, moves PENDING → REJECTED with terminal reason VENUE_REJECTED, and releases the Resource reservation immediately.
- **AC-US-VEN-001-05:** No response at the deadline moves PENDING → VENUE_RESPONSE_EXPIRED and releases the Resource reservation immediately.

---

## US-VEN-002 — Recover a missed booking request through Late Confirmation

**Traceability:** PRD `FR-LCF-*` · Product Spec §§24–25

- **AC-US-VEN-002-01:** Late Confirm can be initiated only from VENUE_RESPONSE_EXPIRED while the original Start Time is still in the future and Category-B Venue/Resource eligibility passes.
- **AC-US-VEN-002-02:** Initiation is blocked when the Customer already has 4 counted commitments; the Booking remains VENUE_RESPONSE_EXPIRED and no temporary hold is created.
- **AC-US-VEN-002-03:** The Venue receives only the neutral ineligibility message and is not told about the Customer’s other Bookings.
- **AC-US-VEN-002-04:** Successful initiation atomically validates the commitment count and Resource, creates the temporary hold and moves the same Booking ID to AWAITING_CUSTOMER_CONFIRMATION.
- **AC-US-VEN-002-05:** Late Confirm does not create a second Booking and preserves the original Venue, Resource, time, duration, price, fee and history.

---

## US-CUS-011 — Decide whether to accept a Late Confirmation

**Traceability:** PRD `FR-LCF-*` · Product Spec §25

- **AC-US-CUS-011-01:** The Customer response deadline is lateConfirmExpiresAt = MIN(now + 5 minutes, booking.startAt).
- **AC-US-CUS-011-02:** Accept before expiry moves the same Booking to CONFIRMED; Decline moves it to CANCELLED with CUSTOMER_DECLINED_LATE_CONFIRM; no response by the deadline cancels it with CUSTOMER_RECONFIRM_TIMEOUT.
- **AC-US-CUS-011-03:** Decline and timeout carry no Customer penalty; under a future non-zero Booking Fee both are full-refund outcomes.
- **AC-US-CUS-011-04:** Accept revalidates commitment count, Customer schedule conflict, reservation validity, ban/account eligibility and Venue/Resource operational eligibility.
- **AC-US-CUS-011-05:** If Accept conflicts with another active Customer commitment, Accept fails, LATE_CONFIRM_FAILED_CUSTOMER_CONFLICT is recorded, and the Booking remains AWAITING until another valid terminal outcome occurs.
- **AC-US-CUS-011-06:** If the total Late Confirm window is 60 seconds or less, no second expiring notification is sent; if it exceeds 60 seconds, one expiring notification is sent approximately one minute before expiry.

---

## US-CUS-012 — Understand when an operational issue ends my booking

**Traceability:** PRD `FR-BOK-*`, `FR-OPS-*`, `FR-NTF-*` · Product Spec §§31, 33, 40–44, 52

- **AC-US-CUS-012-01:** Operationally caused cancellation uses the authoritative Product reason rather than a generic Customer-fault or Venue-non-response reason.
- **AC-US-CUS-012-02:** Where multiple operational causes compete, exactly one terminal reason is stored according to the frozen reason-priority rules.
- **AC-US-CUS-012-03:** Operationally caused cancellation or interruption never creates a Customer penalty and uses the applicable full-refund treatment when a non-zero fee exists.
- **AC-US-CUS-012-04:** Customer-facing messaging distinguishes Venue non-response, platform account access restriction and genuine Venue/Resource operational failure.
- **AC-US-CUS-012-05:** A notification never states that the Booking was cancelled unless a Product rule actually cancelled it.

---

# 5. ATTENDANCE, CHECK-IN AND NO-SHOW

## US-VEN-003 — Record that a Customer arrived on time

**Traceability:** PRD `FR-ATT-*` · Product Spec §§27.1–27.2, 28

- **AC-US-VEN-003-01:** Check-in is available only while startAt <= now < startAt + 15 minutes; at exactly +15 minutes it is refused.
- **AC-US-VEN-003-02:** Check-in requires no QR code, PIN, Customer device confirmation, biometrics or Owner Password.
- **AC-US-VEN-003-03:** A successful Check-in records CHECK_IN_RECORDED with Booking ID, Venue Account identity and timestamp in audit/history.
- **AC-US-VEN-003-04:** A Booking that completes with qualifying Check-in produces ATTENDED and receives the configured Completed Booking reward and Verified Player progress.

---

## US-VEN-004 — Record a Customer who is present but late

**Traceability:** PRD `FR-ATT-*` · Product Spec §§27.3, 27.5–27.6

- **AC-US-VEN-004-01:** Mark Present — Late is available only while startAt + 15 minutes <= now < endAt, with no valid Check-in and no committed No-show.
- **AC-US-VEN-004-02:** Recording Late Present blocks No-show for that Booking, requires no Owner Password and is audited.
- **AC-US-VEN-004-03:** Late Present grants no Completed Booking Points, no Verified Player progress and does not reset the Consecutive No-show Counter.
- **AC-US-VEN-004-04:** At normal End Time the Booking completes with ATTENDANCE_UNCONFIRMED when Late Present is the attendance evidence.

---

## US-VEN-005 — Record a genuine no-show

**Traceability:** PRD `FR-ATT-*`, `FR-TRS-*` · Product Spec §§27.4–27.7, 45, 48

- **AC-US-VEN-005-01:** No-show is available only while startAt + 15 minutes <= now < endAt and only when no valid Check-in and no Mark Present — Late exists.
- **AC-US-VEN-005-02:** A Customer already marked Late Present cannot later be marked No-show for the same Booking.
- **AC-US-VEN-005-03:** Recording a No-show makes the Booking terminal NO_SHOW, sets the default Attendance Result to ABSENT_NO_SHOW and records NO_SHOW_RECORDED.
- **AC-US-VEN-005-04:** The remaining Resource interval is released immediately and may be sold again under normal availability rules.
- **AC-US-VEN-005-05:** Venue Staff cannot reverse a committed No-show; reversal is a Platform Admin action.

---

## US-CUS-013 — Be protected from incorrect attendance penalties

**Traceability:** PRD `FR-ATT-*`, `FR-TRS-*` · Product Spec §§27.8–27.9, 45, 47

- **AC-US-CUS-013-01:** Platform Admin can reverse Check-in, Late Present and No-show and can issue an Attendance Correction; each action requires a mandatory reason and audit record.
- **AC-US-CUS-013-02:** Venue Staff can reverse none of the attendance actions.
- **AC-US-CUS-013-03:** Reversing a No-show never evicts a later valid reservation that obtained the released interval.
- **AC-US-CUS-013-04:** Attendance corrections preserve history and feed Trust replay rather than silently rewriting earlier evidence.
- **AC-US-CUS-013-05:** The latest non-superseded attendance correction is authoritative for Trust effects and cannot double-apply reward, reset or penalty.

---

# 6. MANUAL BOOKING AND SHARED INVENTORY

## US-VEN-006 — Record walk-ins and phone reservations

**Traceability:** PRD `FR-MAN-*`, `FR-AVL-*` · Product Spec §§16, 30

- **AC-US-VEN-006-01:** Manual Booking creation is available only when Venue Account is ENABLED, Marketplace Status is ACTIVE/INACTIVE/SUSPENDED, the Resource is operationally eligible, no blocking conflict exists and Start Time is NOW or FUTURE.
- **AC-US-VEN-006-02:** Manual duration is 1 to 6 hours in 30-minute increments, and creation is blocked if the required pricing configuration does not exist.
- **AC-US-VEN-006-03:** A future Manual Booking is created as CONFIRMED; a walk-in created at current server time is created as ACTIVE.
- **AC-US-VEN-006-04:** Manual Booking may be created outside published Opening Hours and always stores a businessDate; no back-dating is allowed.
- **AC-US-VEN-006-05:** Manual Bookings never use PENDING, VENUE_RESPONSE_EXPIRED, AWAITING_CUSTOMER_CONFIRMATION, REJECTED or NO_SHOW and do not use Customer Check-in.
- **AC-US-VEN-006-06:** Manual Bookings grant no Points, no Verified Player progress and do not count toward the Customer’s 4-commitment limit.

---

## US-VEN-007 — See one schedule for every demand source

**Traceability:** PRD `FR-AVL-*`, `FR-VEN-*` · Product Spec §§16, 17, 50.4

- **AC-US-VEN-007-01:** The Availability Schedule represents Free, Pending, Awaiting Customer Confirmation, Confirmed, Active, Manual, Tournament Hold, Maintenance and Closure states.
- **AC-US-VEN-007-02:** Online, Manual and Tournament demand use the same canonical Resource availability rules rather than separate inventories.
- **AC-US-VEN-007-03:** A Resource is never shown as free when a blocking reservation, hold, Maintenance, Closure, relevant Emergency, selling block or operational restriction makes it unavailable.
- **AC-US-VEN-007-04:** Displayed availability is a snapshot only; any commitment-strengthening action revalidates atomically before commit.

---

# 7. VENUE CONFIGURATION AND OWNER CONTROLS

## US-VEN-008 — Configure Resources for actual Venue operations

**Traceability:** PRD `FR-VEN-*` · Product Spec §§3, 13

- **AC-US-VEN-008-01:** Resource Base State and Resource Operational Restriction are displayed and enforced as independent domains.
- **AC-US-VEN-008-02:** Disable, Archive and Console Change are blocked while any current or future blocking reservation/hold or Resource Maintenance remains unresolved.
- **AC-US-VEN-008-03:** VENUE_RESPONSE_EXPIRED does not block Resource state changes because it holds no reservation.
- **AC-US-VEN-008-04:** Disable, Re-enable, Archive, Restore and protected console/configuration changes require the applicable Owner Password and are audited.
- **AC-US-VEN-008-05:** PlayStation Resources expose only PS4 or PS5 console type, and the console type appears wherever Resource information is shown.

---

## US-OWN-001 — Protect sensitive Venue actions

**Traceability:** PRD `FR-VEN-01…05`, `FR-VEN-46` · Product Spec §§10, 54

- **AC-US-OWN-001-01:** Each protected action requires a fresh Owner Password validation; no timed Owner unlock or Owner session exists.
- **AC-US-OWN-001-02:** Protected actions include pricing changes, Room override set/reset, cancelling a CONFIRMED Online Booking, protected Opening Hours/Closure saves, Resource disable/re-enable/archive/restore, console change, over-threshold Maintenance, confirmed Tournament cancellation, Tournament Operational Termination and OWNER_REVIEW_REQUIRED resolution.
- **AC-US-OWN-001-03:** Daily operational actions listed as unprotected in the baseline remain executable without the Owner Password, subject to their own eligibility rules.
- **AC-US-OWN-001-04:** Successful and failed Owner Password attempts are audited and rate-limited.
- **AC-US-OWN-001-05:** When the Venue changes its own Owner Password, the new value becomes effective immediately and the previous value stops working for the next protected action.

---

## US-OWN-002 — Configure Venue pricing

**Traceability:** PRD `FR-VEN-*`, `FR-BOK-*` · Product Spec §§4, 53

- **AC-US-OWN-002-01:** PlayStation pricing supports Venue-level defaults for PS5 Single, PS5 Multiplayer, PS4 Single and PS4 Multiplayer.
- **AC-US-OWN-002-02:** A PlayStation Room may have a Room Price Override; without an override it inherits the matching Venue console/mode default.
- **AC-US-OWN-002-03:** Billiards and Ping Pong use Venue-level hourly rates only and expose no table-level override, console type or play mode.
- **AC-US-OWN-002-04:** Online Booking, Manual Booking and Tournament pricing use the same approved configuration and creation is blocked when the required configuration is absent.
- **AC-US-OWN-002-05:** Changing protected pricing requires the Owner Password and affects future transactions only; historical prices remain immutable.
- **AC-US-OWN-002-06:** Price calculation uses nominal booked minutes and half-up rounding to 2 decimal places.

---

## US-OWN-003 — Manage opening hours safely

**Traceability:** PRD `FR-VEN-*` · Product Spec §14

- **AC-US-OWN-003-01:** Opening Hours support a default schedule plus weekday overrides of Use Default, Custom or Closed, including cross-midnight sessions.
- **AC-US-OWN-003-02:** Generated Operating Sessions are greater than 0 and no more than 24 hours, may touch at boundaries and may not overlap cyclically across the week.
- **AC-US-OWN-003-03:** Before saving a change, proposed sessions are validated and Tournament conflicts are checked before ordinary Booking impact is previewed.
- **AC-US-OWN-003-04:** Any Tournament conflict blocks the Opening Hours save; no Booking-cancellation preview is committed.
- **AC-US-OWN-003-05:** A change that cancels CONFIRMED Online or AWAITING_CUSTOMER_CONFIRMATION Bookings requires the Owner Password; Pending/Manual-only impact does not.
- **AC-US-OWN-003-06:** The final change and all affected cancellations commit atomically, and a newly created protected Booking between preview and commit forces abort/re-preview.
- **AC-US-OWN-003-07:** ACTIVE sessions are not cancelled solely by an ordinary Opening Hours change.

---

## US-VEN-009 — Schedule a normal closure

**Traceability:** PRD `FR-VEN-*`, `FR-OPS-*` · Product Spec §15

- **AC-US-VEN-009-01:** A normal Venue Closure may be full-day or partial-time and applies to the entire Venue for its interval.
- **AC-US-VEN-009-02:** Creating/editing a Closure requires the Owner Password only when protected Customer reservations would be cancelled.
- **AC-US-VEN-009-03:** Affected ordinary cancellations use terminal reason VENUE_CLOSURE and affected Customers are notified.
- **AC-US-VEN-009-04:** A Closure cannot be created retroactively over an ACTIVE session and must begin after unresolved ACTIVE sessions end.
- **AC-US-VEN-009-05:** Any conflict with an existing Tournament blocks the Closure save.
- **AC-US-VEN-009-06:** A Closure may overlap existing Maintenance, but new Maintenance cannot be created over an existing Closure for the same overlap.

---

## US-VEN-010 — Schedule Resource maintenance

**Traceability:** PRD `FR-OPS-*` · Product Spec §39

- **AC-US-VEN-010-01:** Maintenance creation, edit and deletion are audited Resource-level operations.
- **AC-US-VEN-010-02:** Maintenance may never overlap an existing blocking reservation and must not silently cancel any Booking.
- **AC-US-VEN-010-03:** Owner Password is required when the Resource’s Maintenance exceeds 24 hours inside any rolling 48-hour window, including relevant continuous/overlapping periods.
- **AC-US-VEN-010-04:** An edit extending a continuous Maintenance period past the threshold requires the Owner Password.
- **AC-US-VEN-010-05:** Maintenance produced from a Resource Emergency counts toward the same rolling threshold.

---

# 8. EMERGENCIES AND OPERATIONAL RESILIENCE

## US-VEN-011 — Declare an immediate Venue emergency

**Traceability:** PRD `FR-OPS-*` · Product Spec §§38, 40, 42

- **AC-US-VEN-011-01:** A Venue Emergency Closure can be declared immediately without Owner Password, must start NOW, cannot be future-scheduled and requires a mandatory reason.
- **AC-US-VEN-011-02:** Expected End is mandatory, later than Start and no later than the end of the current Operating Session.
- **AC-US-VEN-011-03:** The emergency creates a Venue Operational Selling Block and stops new affected commitments while the block is active.
- **AC-US-VEN-011-04:** The initial cascade affects only reservations overlapping the declared emergency interval and applies the VENUE_EMERGENCY_CLOSURE operational reason where cancellation occurs.
- **AC-US-VEN-011-05:** The emergency write, cancellations/terminations, releases and history commit atomically before notifications are dispatched.
- **AC-US-VEN-011-06:** Ending an emergency early never resurrects reservations already cancelled by the emergency.

---

## US-VEN-012 — Declare a broken Resource immediately

**Traceability:** PRD `FR-OPS-*` · Product Spec §§38, 41, 42

- **AC-US-VEN-012-01:** A Resource Emergency Outage can be declared immediately without Owner Password and requires reason, affected Resource, timestamp, Venue Account identity and expected return-to-service time.
- **AC-US-VEN-012-02:** The outage creates a Resource-level Operational Selling Block only for the affected Resource; other Venue Resources remain independently usable.
- **AC-US-VEN-012-03:** The outage may terminate the ACTIVE Booking on the affected Resource and cancel overlapping future commitments under RESOURCE_EMERGENCY_OUTAGE.
- **AC-US-VEN-012-04:** For an unprotected outage, expected return may not extend beyond the current Operating Session; longer unavailability must move through normal Maintenance/state-change rules.
- **AC-US-VEN-012-05:** The declaration and its cascade are audited and visible to the Venue Owner and Platform Admin.

---

## US-OWN-004 — Resolve a Resource that requires owner review

**Traceability:** PRD `FR-OPS-*` · Product Spec §43

- **AC-US-OWN-004-01:** When the Resource exceeds the frozen 24-hours-in-48-hours threshold, emergency ending evaluates the threshold before making the Resource sellable.
- **AC-US-OWN-004-02:** If Owner review is required, the Emergency may end but the Resource becomes operationalRestriction = OWNER_REVIEW_REQUIRED and remains unavailable.
- **AC-US-OWN-004-03:** Entry triggers the atomic system cascade across affected Online, Manual and Tournament commitments; the cascade itself requires no Owner Password.
- **AC-US-OWN-004-04:** While OWNER_REVIEW_REQUIRED is active, the Resource cannot be used for Online, Manual, Tournament, Nearest Availability or Late Confirm commitment.
- **AC-US-OWN-004-05:** Resolving OWNER_REVIEW_REQUIRED requires Owner Password and offers only Return to Service, Extend Maintenance, Disable Resource or Archive Resource.
- **AC-US-OWN-004-06:** Return to Service succeeds only when no blocking Maintenance or Emergency Selling Block exists and the Resource is otherwise operational.

---

## US-CUS-014 — Keep control when a Late Confirmation timer is paused

**Traceability:** PRD `FR-OPS-34…41` · Product Spec §44

- **AC-US-CUS-014-01:** A relevant Venue or Resource emergency pauses the Customer Late Confirmation countdown only when it affects the booked Venue/Resource and the Booking has not already been cancelled by the cascade.
- **AC-US-CUS-014-02:** The remaining countdown duration is preserved across pause/resume rather than restarted.
- **AC-US-CUS-014-03:** Late Confirmation emergency pauses have a 30-minute cumulative cap for that AWAITING phase.
- **AC-US-CUS-014-04:** The Customer is notified when the countdown is paused and sees the remaining time again on resume.
- **AC-US-CUS-014-05:** While paused, the Customer may still Decline; Decline takes effect immediately as CUSTOMER_DECLINED_LATE_CONFIRM.
- **AC-US-CUS-014-06:** At the cumulative cap, the Booking cancels under LATE_CONFIRM_INTERRUPTED_BY_EMERGENCY, releases its hold/commitment and creates no Customer penalty or Venue non-response blame.

---

# 9. VENUE ACCOUNT ACCESS AND ATTENDANCE PROTECTION

## US-ADM-001 — Disable a Venue account without pretending the Venue physically closed

**Traceability:** PRD `FR-ADM-*`, `FR-ATT-*` · Product Spec §§11, 32, 51.3

- **AC-US-ADM-001-01:** Venue Account Status and Marketplace Status remain independent; disabling the Account does not rewrite Marketplace Status.
- **AC-US-ADM-001-02:** DISABLED blocks ordinary Dashboard login/actions but is not treated as a physical Venue outage.
- **AC-US-ADM-001-03:** PENDING immediately becomes VENUE_RESPONSE_EXPIRED with VENUE_RESPONSE_INTERRUPTED_BY_ACCOUNT_DISABLE and releases its reservation.
- **AC-US-ADM-001-04:** AWAITING_CUSTOMER_CONFIRMATION becomes CANCELLED with VENUE_ACCOUNT_DISABLED and releases the temporary hold.
- **AC-US-ADM-001-05:** A VENUE_RESPONSE_EXPIRED Booking still disabled at original start cancels with VENUE_ACCOUNT_DISABLED.
- **AC-US-ADM-001-06:** CONFIRMED and ACTIVE Bookings are not cancelled solely because the Venue Account is disabled, and re-enable alone does not resurrect cancelled records or trigger a mandatory Customer notification.

---

## US-CUS-015 — Avoid a no-show penalty when platform access prevented reliable attendance recording

**Traceability:** PRD `FR-ATT-*` · Product Spec §32

- **AC-US-CUS-015-01:** Platform Attendance Protection is evaluated only for an ACTIVE Booking whose Venue Account became DISABLED during the session and materially prevented reliable attendance recording.
- **AC-US-CUS-015-02:** Authoritative attendance evidence recorded before the disable remains authoritative and is never erased by protection.
- **AC-US-CUS-015-03:** Protection does not invent attendance, Points or Verified Player progress and does not alter Booking Status or Attendance Result.
- **AC-US-CUS-015-04:** When applicable, BOOKING_ACCOUNT_DISABLE_ATTENDANCE_PROTECTION resets the Consecutive No-show Counter to 0 and does nothing else.
- **AC-US-CUS-015-05:** At most one effective protection event is emitted per Booking even if multiple disable/re-enable intervals occur.
- **AC-US-CUS-015-06:** If the final effective outcome is ATTENDED or ATTENDED_INTERRUPTED, the separate protection reset is not additionally applied.
- **AC-US-CUS-015-07:** A later Admin correction to ABSENT_NO_SHOW neutralises the protection reset so the Booking never receives both the protected reset and a valid no-show increment.

---

# 10. TOURNAMENTS

## US-PLY-001 — Create a Player-hosted Tournament

**Traceability:** PRD `FR-TRN-*` · Product Spec §§36–37

- **AC-US-PLY-001-01:** Only an operationally VERIFIED Player may create a Player-hosted Tournament.
- **AC-US-PLY-001-02:** Creation supports only PlayStation, Billiards and Ping Pong, 8–32 participants, 1–4 Resources and a minimum duration of 1 hour in 30-minute increments.
- **AC-US-PLY-001-03:** Creation must occur at or before startAt − 2 hours; a request at startAt − 1h59m is refused and one exactly at −2h is allowed.
- **AC-US-PLY-001-04:** Every assigned Resource must pass current availability/operational eligibility and existing reservations always win; Tournament creation never cancels an existing Booking.
- **AC-US-PLY-001-05:** A successful Player-hosted request creates PENDING_VENUE_APPROVAL plus temporary Approval Holds and is not discoverable/joinable until Venue approval.
- **AC-US-PLY-001-06:** A Creator may hold only one PENDING_VENUE_APPROVAL Player-hosted Tournament platform-wide.

---

## US-VEN-013 — Approve or reject a Player-hosted Tournament

**Traceability:** PRD `FR-TRN-*` · Product Spec §37.2

- **AC-US-VEN-013-01:** The Venue approval deadline is MIN(requestCreatedAt + 10 minutes, checkpointAt).
- **AC-US-VEN-013-02:** Venue Accept before the deadline moves PENDING_VENUE_APPROVAL → WAITING_FOR_PLAYERS after atomic eligibility revalidation and converts Approval Holds to Tournament Holds.
- **AC-US-VEN-013-03:** Venue Reject moves the Tournament to REJECTED with VENUE_REJECTED_TOURNAMENT and releases all Approval Holds.
- **AC-US-VEN-013-04:** No response by the approval deadline moves the Tournament to CANCELLED with VENUE_DID_NOT_APPROVE_TOURNAMENT_IN_TIME and releases all Approval Holds.
- **AC-US-VEN-013-05:** Venue Account Disable during PENDING_VENUE_APPROVAL cancels immediately with VENUE_ACCOUNT_DISABLED, releases holds and Creator pending slot, creates no Venue non-response blame and applies no 5-minute retry cooldown.

---

## US-VEN-014 — Create a Venue-hosted Tournament

**Traceability:** PRD `FR-TRN-*` · Product Spec §36

- **AC-US-VEN-014-01:** A Venue-hosted Tournament can be created directly by the Venue without Verified Player status or a separate Venue-approval phase.
- **AC-US-VEN-014-02:** All Tournament parameter, Operating Session, Resource, conflict and pricing-snapshot rules still apply.
- **AC-US-VEN-014-03:** Successful creation obtains standard Tournament Holds on the selected Resources and enters the applicable pre-checkpoint lifecycle.
- **AC-US-VEN-014-04:** Venue-hosted creation never overrides an existing Booking, Tournament hold, Maintenance or Closure.

---

## US-CUS-016 — Discover and join a Tournament

**Traceability:** PRD `FR-TRN-*` · Product Spec §§36.5, 37.4

- **AC-US-CUS-016-01:** Public Tournaments are discoverable only after the Player-hosted request is approved; PENDING_VENUE_APPROVAL Tournaments are not public and cannot be joined.
- **AC-US-CUS-016-02:** Private Tournaments require valid invite access and are not shown in public discovery.
- **AC-US-CUS-016-03:** An Account-Verified, non-banned user may join while status is WAITING_FOR_PLAYERS or CONFIRMED, `now < startAt` and capacity remains **after counting JOINED participants plus any still-valid pre-start BAN-only reserved slots**.
- **AC-US-CUS-016-04:** Participants may leave until startAt; at now >= startAt the start boundary owns the state and join/leave is no longer allowed.
- **AC-US-CUS-016-05:** Participant history remains retained after LEFT, REMOVED or INELIGIBLE transitions.

---

## US-PLY-002 — Manage participants before a Tournament starts

**Traceability:** PRD `FR-TRN-*` · Product Spec §37.4

- **AC-US-PLY-002-01:** Organizer participant management is available only before startAt under the frozen participant lifecycle rules.
- **AC-US-PLY-002-02:** Organizer removal transitions the participant to REMOVED and keeps historical participation.
- **AC-US-PLY-002-03:** An Active Participant count includes only JOINED users who are not banned/deleted and have not left or been removed; BAN-only `INELIGIBLE` never counts toward the checkpoint minimum.
- **AC-US-PLY-002-04:** A temporary pre-start Ban transitions the participant to `INELIGIBLE/BAN`, removes them from Active count but preserves one capacity slot while restoration remains possible. Another join cannot consume that reserved slot.
- **AC-US-PLY-002-05:** A temporary Ban ending/lifting before Start automatically returns a BAN-only `INELIGIBLE` participant to JOINED. If the Ban was already ineffective at the exact checkpoint, the participant is restored for that checkpoint count even when processing runs late; at/after Start no automatic restoration occurs.
- **AC-US-PLY-002-06:** Account deletion transitions/reclassifies the participant to `INELIGIBLE/ACCOUNT_DELETED`, immediately releases any BAN-only reserved slot and permanently defeats BAN-based automatic restoration.
- **AC-US-PLY-002-07:** `BAN → DELETE`, `DELETE → BAN`, and simultaneous Ban/deletion all resolve with `ACCOUNT_DELETED` precedence; later Ban expiry/lift must not restore the participant.
- **AC-US-PLY-002-08:** A ban/deletion before the checkpoint may reduce the Active count and contribute to checkpoint cancellation; after the checkpoint the confirmed Tournament does not auto-cancel merely because the count falls below eight.

---

## US-PLY-003 — Know whether the Tournament is actually confirmed

**Traceability:** PRD `FR-TRN-13…17`, `FR-TRN-26` · Product Spec §§37.4–37.6, 37.11

- **AC-US-PLY-003-01:** At exactly startAt − 1 hour, the mandatory checkpoint runs on every non-terminal Tournament waiting for players.
- **AC-US-PLY-003-02:** At least 8 Active Participants and successful operational revalidation moves the Tournament to CONFIRMED.
- **AC-US-PLY-003-03:** Fewer than 8 Active Participants cancels the Tournament with MINIMUM_PARTICIPANTS_NOT_REACHED and releases all Tournament Holds atomically.
- **AC-US-PLY-003-04:** Checkpoint evaluation uses the exact boundary state: any BAN-only participant whose Ban had already ended/lifted by `checkpointAt` is restored before the Active Participant count; a still-banned participant remains excluded even though their pre-start capacity slot is reserved.
- **AC-US-PLY-003-05:** Marketplace INACTIVE or SUSPENDED alone does not fail an existing-commitment checkpoint when the Venue remains operationally able to honour the Tournament.
- **AC-US-PLY-003-06:** Any assigned Resource failure at the pre-start checkpoint under Resource Emergency or OWNER_REVIEW_REQUIRED cancels the entire Tournament under the applicable operational reason.
- **AC-US-PLY-003-07:** After CONFIRMED, a later drop below 8 does not auto-cancel and there is no Run Anyway override for a failed checkpoint.

---

## US-VEN-015 — End a serious active Tournament through the correct protected path

**Traceability:** PRD `FR-TRN-21…25` · Product Spec §§37.9–37.10

- **AC-US-VEN-015-01:** Tournament Operational Termination is available only for an ACTIVE Tournament and only for a serious Tournament-specific incident, not as a general ordinary cancellation shortcut.
- **AC-US-VEN-015-02:** The action requires the Owner Password and a mandatory reason.
- **AC-US-VEN-015-03:** Successful termination moves ACTIVE → CANCELLED with TOURNAMENT_OPERATIONAL_TERMINATION, releases applicable holds/resources and records the cancelling actor and reason.
- **AC-US-VEN-015-04:** Creator and Participants are notified after commit.
- **AC-US-VEN-015-05:** No equivalent generic early-end action is introduced for an ordinary ACTIVE Booking.

---

# 11. TRUST, BANS AND VERIFIED PLAYER

## US-CUS-017 — Build Verified Player status through real attendance

**Traceability:** PRD `FR-ACC-*`, `FR-TRS-*` · Product Spec §8

- **AC-US-CUS-017-01:** Verified Player Qualification requires 10 qualifying Completed Bookings with ATTENDED outcome; all 10 may be at the same Venue.
- **AC-US-CUS-017-02:** The Customer sees qualification progress such as 6 / 10 Completed Bookings.
- **AC-US-CUS-017-03:** Manual Bookings and Tournament participation contribute no Verified Player progress.
- **AC-US-CUS-017-04:** Only operational status VERIFIED may create a Player-hosted Tournament.
- **AC-US-CUS-017-05:** A retroactively discovered first qualification activates operational VERIFIED status now, not in the past, unless a protected Admin status prevents it.
- **AC-US-CUS-017-06:** If REVOKED solely by TRUST_CORRECTION and later replay restores qualification, the system automatically restores VERIFIED with a new transition ID; Admin SUSPENDED/REVOKED is never auto-overridden.

---

## US-CUS-018 — Be treated fairly when Trust history is corrected

**Traceability:** PRD `FR-TRS-*` · Product Spec §§45, 47, 48

- **AC-US-CUS-018-01:** Trust state is reproducible from immutable Trust Input Events and is not taken from an authoritative stored counter snapshot.
- **AC-US-CUS-018-02:** Replay order is effectiveAt ascending then eventSequence ascending, with corrections applied at the corrected event’s historical effective position.
- **AC-US-CUS-018-03:** A correction neutralises/supersedes the targeted effect rather than deleting history, and only one effective outcome per Booking contributes to replay.
- **AC-US-CUS-018-04:** A correction that newly qualifies a historical ban activates the Ban Instance at correction time, never back-dated.
- **AC-US-CUS-018-05:** Trust replay never rewinds external past side-effects: already sold Resource slots and already-cancelled Bookings are not resurrected or destroyed retroactively.
- **AC-US-CUS-018-06:** Replaying the same corrected history repeatedly does not duplicate cancellation cascades, rewards, resets or penalties.

---

## US-VEN-016 — Be protected from repeated Customer no-shows

**Traceability:** PRD `FR-TRS-*` · Product Spec §48

- **AC-US-VEN-016-01:** Qualifying consecutive No-shows are tracked globally across E7GZ in booking-start order, not separately per Venue.
- **AC-US-VEN-016-02:** The penalty ladder is 3 consecutive → 7 days, then 2 → 1 month, then 2 → 2 months, then recurring 3 → 2 months.
- **AC-US-VEN-016-03:** A penalty triggers only when a new eligible No-show increments the counter to the current threshold; on trigger the counter resets to 0 and the stage advances.
- **AC-US-VEN-016-04:** Ban activation cancels PENDING, AWAITING and future CONFIRMED Online commitments with USER_BANNED while an ACTIVE Booking continues.
- **AC-US-VEN-016-05:** At most one ACTIVE Ban Instance exists; a later qualification while already banned extends the same instance using the new stage and does not repeat an already executed cascade.
- **AC-US-VEN-016-06:** Ban history remains auditable through ACTIVE, EXPIRED, LIFTED or CORRECTED status.

---

# 12. POINTS, REFERRALS AND REWARDS

## US-CUS-019 — Earn points for qualifying attendance

**Traceability:** PRD `FR-PTS-*` · Product Spec §46

- **AC-US-CUS-019-01:** A qualifying attended completion grants the configured Completed Booking Points value, which is +200 at Launch, and +1 Verified Player progress.
- **AC-US-CUS-019-02:** The reward value is read at the successful attended completion event and the exact granted amount is stored historically.
- **AC-US-CUS-019-03:** No-show, cancelled, rejected, Review and non-qualifying attendance outcomes grant 0 Completed Booking Points and 0 progress.
- **AC-US-CUS-019-04:** Points corrections use compensating ledger transactions and never delete prior history.
- **AC-US-CUS-019-05:** Points balance never becomes negative.

---

## US-CUS-020 — Earn a referral reward after a genuine verified signup

**Traceability:** PRD `FR-PTS-*` · Product Spec §46

- **AC-US-CUS-020-01:** A Referral becomes eligible for reward only after successful phone OTP verification of the referred account, not at signup-record creation.
- **AC-US-CUS-020-02:** Only one referral reward may be applied per referred user and self-referral using the same Phone or Email is blocked.
- **AC-US-CUS-020-03:** Same device or IP may be retained as fraud signals but are not automatic hard blocks in MVP.
- **AC-US-CUS-020-04:** The reward value is the configuration in force at OTP verification and the historical applied amount is stored.
- **AC-US-CUS-020-05:** Referral reward grants no Verified Player progress.
- **AC-US-CUS-020-06:** Invalidating a REWARDED referral creates a compensating reversal for the exact historical reward and records any unrecovered amount without future debt.

---

## US-CUS-021 — See my reward history without confusing it with Venue payments

**Traceability:** PRD `FR-PTS-*` · Product Spec §§5, 46, 53

- **AC-US-CUS-021-01:** Points history and Wallet ledger history are append-only and corrections use compensating transactions.
- **AC-US-CUS-021-02:** The Customer has no active Wallet screen at Launch.
- **AC-US-CUS-021-03:** Wallet is never used to pay the Venue gaming charge.
- **AC-US-CUS-021-04:** A points reversal cannot reduce the effective Points balance below 0; unrecovered reversal amount is audited and is never automatically collected from future earnings.

---

# 13. REVIEWS

## US-CUS-022 — Review a Venue

**Traceability:** PRD `FR-REV-*` · Product Spec §49

- **AC-US-CUS-022-01:** Only an Account-Verified User may create a Review; a Completed Booking is not required.
- **AC-US-CUS-022-02:** A Review requires both a 1–5 star rating and a comment.
- **AC-US-CUS-022-03:** Public display uses the current Username only; Full Name is not public.
- **AC-US-CUS-022-04:** A normal eligible Review is created ACTIVE unless the user is in the post-removal moderation re-entry flow.
- **AC-US-CUS-022-05:** One current/non-terminal Review per User per Venue is enforced.

---

## US-CUS-023 — Manage my current Review

**Traceability:** PRD `FR-REV-*` · Product Spec §§49.3–49.6

- **AC-US-CUS-023-01:** An author may edit or delete their current eligible Review while the Review state permits it.
- **AC-US-CUS-023-02:** Editing a HIDDEN Review leaves it HIDDEN.
- **AC-US-CUS-023-03:** Author deletion removes the public Review but preserves required moderation/audit history.
- **AC-US-CUS-023-04:** A REMOVED Review is terminal and cannot be restored or edited.
- **AC-US-CUS-023-05:** After Admin removal, a new Review for the same Venue is refused for 30 days; after the cooldown it is accepted as PENDING_MODERATION and remains non-public until Admin approval.

---

## US-CUS-026 — Report an abusive Review

**Traceability:** `FR-REV-11` · Product Spec §49.10 · PCR-003

- **AC-US-CUS-026-01:** A signed-in Customer can report a public Review visible to them and must provide a non-empty reason; supporting details are optional.
- **AC-US-CUS-026-02:** Submitting the report creates a durable Review Report linked to the Review and reporter.
- **AC-US-CUS-026-03:** Creating a report does not automatically hide, remove, re-rank or otherwise change the Review.
- **AC-US-CUS-026-04:** Platform Admin can see and resolve the report with a resolution note; if a moderation action is taken, the report may reference that existing valid moderation event.

---

## US-VEN-017 — Understand Customer feedback without moderating it

**Traceability:** PRD `FR-REV-*`, `FR-VEN-*` · Product Spec §49.7

- **AC-US-VEN-017-01:** Venue Review access is view-only; Venue users cannot hide, remove, approve, restore or otherwise moderate a Review.
- **AC-US-VEN-017-02:** The Venue Reviews surface shows average rating, total Reviews, rating distribution and per-Review Username, rating, comment and Review date.
- **AC-US-VEN-017-03:** PENDING_MODERATION and HIDDEN Reviews do not contribute to public Review display/rating as defined by their state.
- **AC-US-VEN-017-04:** Existing Reviews remain visible for SUSPENDED or INACTIVE Venues and Account-Verified Users may still submit new eligible Reviews.

---

# 14. VENUE DASHBOARD OPERATIONS

## US-VEN-018 — Operate the Venue from one Dashboard

**Traceability:** PRD `FR-VEN-*` · Product Spec §§50.1–50.4

- **AC-US-VEN-018-01:** The Venue Dashboard exposes the approved modules: Overview, Pending Requests, Missed/Expired Requests, Bookings, Manual Booking, Rooms, Pricing, Availability, Maintenance & Closures, Reviews, Tournaments, Booking History and Settings.
- **AC-US-VEN-018-02:** Overview exposes Today’s Revenue, This Month Revenue, Today’s Bookings, Pending Requests, Today’s Schedule, Quick Manual Booking and Quick Create Tournament.
- **AC-US-VEN-018-03:** Pending-response, Late Confirmation, Manual Booking, attendance and other operational actions obey the same underlying eligibility and authorization rules as their canonical flows.
- **AC-US-VEN-018-04:** Dashboard actions are unavailable when Venue Account Status is DISABLED.

---

## US-VEN-019 — Review booking history and revenue

**Traceability:** PRD `FR-VEN-*`, `FR-AUD-*` · Product Spec §§17.5, 50.3–50.5

- **AC-US-VEN-019-01:** Venue reporting exposes Online Booking Revenue, Manual Booking Revenue and Tournament Revenue, with optional Activity breakdown across PlayStation, Billiards and Ping Pong.
- **AC-US-VEN-019-02:** All Venue revenue aggregation uses stored businessDate as the canonical revenue-day key.
- **AC-US-VEN-019-03:** Booking History supports the frozen Date Range and Activity filters plus Source, Resource, Status and Search.
- **AC-US-VEN-019-04:** E7GZ does not track cash, card, Visa, Mastercard or payment-terminal settlement in the Venue Dashboard and is not a POS/accounting/reconciliation system.

---

## US-VEN-020 — Use the Dashboard in English or Arabic

**Traceability:** PRD `FR-VEN-*` · Product Spec §§2.4, 50.6, 52.7

- **AC-US-VEN-020-01:** Venue Dashboard supports English LTR and full Arabic RTL across sidebar, header, KPIs, tables, filters, forms, buttons, modals, drawers, statuses, pricing, revenue, Reviews, Tournaments, Opening Hours, Booking History, notifications and settings.
- **AC-US-VEN-020-02:** Free-text stored data is displayed exactly as entered and is not automatically translated.
- **AC-US-VEN-020-03:** Language preference resolves in order: Venue Account preference → browser/device storage → English default.
- **AC-US-VEN-020-04:** Switching language changes layout direction appropriately without changing stored Product data.

---

# 15. PLATFORM ADMIN — MARKETPLACE CONTROL

## US-ADM-002 — Onboard a Venue completely

**Traceability:** PRD `FR-ADM-*` · Product Spec §51.2

- **AC-US-ADM-002-01:** Venue onboarding is Platform-Admin managed and is not a Venue self-service flow at Launch.
- **AC-US-ADM-002-02:** Onboarding captures Venue information, Area, address, coordinates, phone, images, Opening Hours, supported Activities, Venue login, Venue password, initial Owner Password, Resources and pricing.
- **AC-US-ADM-002-03:** Only PlayStation, Billiards and Ping Pong can be configured as supported Activities.
- **AC-US-ADM-002-04:** Venue images persist through onboarding, Venue Details, Edit Venue and normal navigation.

---

## US-ADM-003 — Control Venue account access

**Traceability:** PRD `FR-ADM-*` · Product Spec §51.3

- **AC-US-ADM-003-01:** Platform Admin can disable and re-enable a Venue Account without deleting Venue configuration, Bookings, Tournaments, Reviews or historical data.
- **AC-US-ADM-003-02:** Platform Admin can reset the Venue login password and Owner Password.
- **AC-US-ADM-003-03:** Resetting the Owner Password notifies the Venue.
- **AC-US-ADM-003-04:** There is no Venue self-service Forgot Password flow in MVP.
- **AC-US-ADM-003-05:** Re-enabling the Venue Account alone does not automatically restore previously cancelled lifecycle records.

---

## US-ADM-004 — Investigate any Booking end to end

**Traceability:** PRD `FR-ADM-*`, `FR-AUD-*` · Product Spec §§33, 51.6, 53

- **AC-US-ADM-004-01:** Global Bookings supports filters for date range, Area, Venue, Activity, Status and Source and search by Booking ID, user name, phone or Venue.
- **AC-US-ADM-004-02:** Booking detail exposes Booking ID, user, Venue, Activity, Resource, console, play mode, date/time, duration, Venue price, historical Booking Fee, total, Source, Status and full lifecycle history.
- **AC-US-ADM-004-03:** Lifecycle history includes expiry, Late Confirm, Customer response, Reject After Expiry, Opening Hours effects and all operational/platform terminal causes without deleting prior steps.
- **AC-US-ADM-004-04:** Historical values displayed in Booking detail remain the values stored for that Booking even after later configuration changes.

---

## US-ADM-005 — Correct attendance and Trust mistakes

**Traceability:** PRD `FR-ATT-*`, `FR-TRS-*`, `FR-ADM-*` · Product Spec §§27.8–27.9, 45, 47

- **AC-US-ADM-005-01:** Only Platform Admin may reverse Check-in, Late Present or No-show and issue Attendance Correction; every such action requires a reason and audit record.
- **AC-US-ADM-005-02:** Admin Trust remediation operates through immutable correction/adjustment events rather than deleting prior Trust inputs.
- **AC-US-ADM-005-03:** A correction recalculates derived no-show counter, penalty stage, Ban and Verified Player state from the corrected event stream.
- **AC-US-ADM-005-04:** If a wrong No-show reversal caused a released interval to be resold, correction never evicts the later valid customer.
- **AC-US-ADM-005-05:** There is no Support Replacement Booking or special Admin booking-creation bypass; any new Customer booking uses the normal online predicate.

---

## US-ADM-006 — Moderate Verified Player status

**Traceability:** PRD `FR-ACC-*`, `FR-ADM-*` · Product Spec §§8, 51.5

- **AC-US-ADM-006-01:** Verified Player administration supports View, Suspend, Revoke and Restore.
- **AC-US-ADM-006-02:** Suspend, Revoke and Restore actions require a reason and create an immutable operational transition record.
- **AC-US-ADM-006-03:** Admin SUSPENDED and Admin REVOKED statuses are not automatically overridden by later Trust qualification regain.
- **AC-US-ADM-006-04:** Admin Restore produces VERIFIED when the user is currently qualified and NOT_VERIFIED when they are not qualified.

---

## US-ADM-007 — Moderate Reviews

**Traceability:** PRD `FR-REV-*`, `FR-ADM-*` · Product Spec §49

- **AC-US-ADM-007-01:** Platform Admin can Hide an ACTIVE Review, Restore a HIDDEN Review, Remove a Review, Approve a PENDING_MODERATION Review and Remove a PENDING_MODERATION Review.
- **AC-US-ADM-007-02:** Every moderation action requires a reason and records action, reason, date, previous status and new status.
- **AC-US-ADM-007-03:** A REMOVED Review cannot be restored by any Admin action.
- **AC-US-ADM-007-04:** A PENDING_MODERATION Review remains non-public and excluded from rating until explicit Admin approval; no timer auto-publishes it.
- **AC-US-ADM-007-05:** The Review author is notified on submission for moderation, approval, hiding and removal as defined by the notification catalogue.

---

## US-ADM-008 — Inspect and intervene in Tournaments

**Traceability:** PRD `FR-TRN-*`, `FR-ADM-*` · Product Spec §§37.9, 51.8

- **AC-US-ADM-008-01:** Platform Admin can inspect Tournament host, host type, Activity, play mode, Venue, Resources, participants, capacity, entry, prize, visibility, date/time, deadline and Status.
- **AC-US-ADM-008-02:** Platform Admin may cancel any non-completed Tournament under the applicable Admin cancellation path.
- **AC-US-ADM-008-03:** Admin cancellation stores the terminal reason, cancelling actor and audit history and releases applicable Tournament holds in the same transaction.
- **AC-US-ADM-008-04:** A COMPLETED Tournament is not reopened by an Admin cancellation action.

---

## US-ADM-009 — Manage marketplace Areas

**Traceability:** PRD `FR-VEN-*`, `FR-ADM-*` · Product Spec §§12, 51.11

- **AC-US-ADM-009-01:** Areas have Active or Inactive status and only Active Areas can be assigned to new Venues.
- **AC-US-ADM-009-02:** Deactivating an Area is refused while any assigned Venue has Marketplace Status ACTIVE or SUSPENDED.
- **AC-US-ADM-009-03:** To deactivate the Area, affected Venues must first move to another Area or become INACTIVE.
- **AC-US-ADM-009-04:** Area deactivation does not rewrite historical Area references and does not block Manual Booking solely because the Area is inactive.

---

## US-ADM-010 — Configure global platform fees

**Traceability:** PRD `FR-PTS-*`, `FR-ADM-*` · Product Spec §§5, 34, 51.10

- **AC-US-ADM-010-01:** Platform Admin can configure the Global Booking Fee and Tournament Creation Fee.
- **AC-US-ADM-010-02:** Both fees are EGP 0 at Launch; the Booking Fee is shown to Customers as FREE.
- **AC-US-ADM-010-03:** A changed fee configuration applies only to future events and never rewrites the stored historical fee on an existing Booking/Tournament.
- **AC-US-ADM-010-04:** Late Confirm reuses the original Booking’s stored Booking Fee instead of reading the current global value.
- **AC-US-ADM-010-05:** Every reachable terminal Booking reason maps deterministically to exactly one fee treatment once a non-zero Booking Fee is enabled.

---

## US-ADM-011 — Investigate Points and Referral history

**Traceability:** PRD `FR-PTS-*`, `FR-TRS-*`, `FR-ADM-*` · Product Spec §§46, 47, 51.9, 53

- **AC-US-ADM-011-01:** Admin Points view exposes user, current Points, booking rewards, referral rewards and transaction history.
- **AC-US-ADM-011-02:** Admin Referral view exposes referrer, referral code, referred user, signup date, reward applied and current Referral status.
- **AC-US-ADM-011-03:** Permitted Points adjustments and Referral invalidations are audited and use compensating transactions rather than deleting history.
- **AC-US-ADM-011-04:** Wallet adjustment is not a Launch Admin action; Points adjustment is available because Points are active.

---

## US-ADM-012 — Receive operational and abuse alerts

**Traceability:** PRD `FR-ATT-*`, `FR-OPS-*`, `FR-NTF-*` · Product Spec §§27.10, 31.4, 41.6, 52.6

- **AC-US-ADM-012-01:** Platform Admin receives the defined operational/integrity alerts, including emergency declarations, OWNER_REVIEW_REQUIRED entry, integrity errors and account disable overlapping an ACTIVE Booking.
- **AC-US-ADM-012-02:** A Venue that causes 3 or more Tournament cancellations through Resource Emergency Outage in a rolling 30 days produces the defined investigative abuse alert.
- **AC-US-ADM-012-03:** Check-in and Late-Present monitoring may surface unusually high/reversal/late-pattern signals for review.
- **AC-US-ADM-012-04:** Operational and abuse signals are non-blocking and never automatically revoke Verified Player status, suspend a Venue, reverse attendance evidence or ban a user.

---

## US-ADM-013 — Delete or anonymize an account through the supported admin path

**Traceability:** PRD `FR-ACC-*`, `FR-ADM-*` · Product Spec §53.5

- **AC-US-ADM-013-01:** Platform Admin can complete a Customer-initiated deletion request or a supported Support/Admin deletion path through the same audited anonymisation process.
- **AC-US-ADM-013-02:** A processed deletion supports Account state DELETED and disables login.
- **AC-US-ADM-013-03:** PII is removed or anonymised where appropriate while legally/operationally required Booking, financial and audit history is preserved.
- **AC-US-ADM-013-04:** The user’s Reviews are removed from public display while required moderation/audit history remains.

---

# 16. NOTIFICATIONS AND COMMUNICATION

## US-CUS-024 — Receive time-critical booking and tournament notifications

**Traceability:** PRD `FR-NTF-*` · Product Spec §§52.1–52.4

- **AC-US-CUS-024-01:** Time-sensitive booking, Tournament and Trust events use In-App + Push according to the frozen notification catalogue; SMS is reserved for OTP.
- **AC-US-CUS-024-02:** Every Booking notification internally references the exact Booking ID and navigation opens that Booking, including a Booking revived through Late Confirm.
- **AC-US-CUS-024-03:** Attendance-unconfirmed completion messaging explains that no attendance reward was applied.
- **AC-US-CUS-024-04:** Customer messaging distinguishes ordinary Venue non-response, Venue Account access restriction and genuine operational causes.
- **AC-US-CUS-024-05:** No notification is emitted for a transaction that rolls back, and notification dispatch occurs only after successful commit.
- **AC-US-CUS-024-06:** Re-enabling a Venue Account alone sends no mandatory Customer notification.

---

## US-VEN-021 — Receive operational Venue notifications

**Traceability:** PRD `FR-NTF-*` · Product Spec §52.5

- **AC-US-VEN-021-01:** Venue operational notifications cover new Booking requests, Pending expiry, Booking auto-cancellation and all Late Confirm outcomes defined in the catalogue.
- **AC-US-VEN-021-02:** Venue notifications cover new Player Tournament requests, approval expiry, minimum reached and Tournament cancellation events.
- **AC-US-VEN-021-03:** Maintenance Starting Soon and emergency operational reminders are surfaced to the Venue where defined.
- **AC-US-VEN-021-04:** The Venue Owner is notified of Emergency Closure, Resource Emergency Outage and OWNER_REVIEW_REQUIRED entry.
- **AC-US-VEN-021-05:** Operational notifications are dispatched after the authoritative transaction commits.

---

# 17. AUDIT, HISTORY AND DISPUTE RESOLUTION

## US-ADM-014 — Reconstruct what actually happened

**Traceability:** PRD `FR-AUD-*`, `FR-TRS-*` · Product Spec §53

- **AC-US-ADM-014-01:** Booking History, Points/Wallet history and Trust Input Events are append-only; previous lifecycle or ledger rows never disappear.
- **AC-US-ADM-014-02:** Historical snapshots retain the Booking/Tournament/Referral/Reservation values defined by Product Spec §53 even after later configuration changes.
- **AC-US-ADM-014-03:** Every action in the canonical audit-required action list produces a retrievable audit record.
- **AC-US-ADM-014-04:** Audit records identify the actor/source and timestamp and retain mandatory reasons where the action requires one.
- **AC-US-ADM-014-05:** Attendance correction, Trust correction, reward correction and moderation use audited replacement/compensating semantics rather than destructive history edits.
- **AC-US-ADM-014-06:** An end-to-end Booking that expires, is Late Confirmed, then becomes CONFIRMED/ACTIVE/COMPLETED retains every lifecycle step in order.

---

## US-VEN-022 — Trust one inventory across every reservation source

**Traceability:** PRD `FR-AVL-*` · Product Spec §§16–17

- **AC-US-VEN-022-01:** A single canonical availability predicate governs Online Booking, Manual Booking, Late Confirm, Tournament creation/holds and Nearest Availability.
- **AC-US-VEN-022-02:** Two active Resource reservations never overlap on the same Resource; concurrent attempts resolve with one successful valid commit and clean losers.
- **AC-US-VEN-022-03:** Booking Status, not Booking Source, determines whether a Booking blocks inventory; PENDING, AWAITING, CONFIRMED and ACTIVE block when applicable, while VENUE_RESPONSE_EXPIRED and terminal Bookings do not.
- **AC-US-VEN-022-04:** Historical planned intervals may overlap after early release, but actual active reservation occupancy never overlaps.
- **AC-US-VEN-022-05:** Reservation history stores planned and actual reservation/release timing sufficient to explain later historical overlaps.
- **AC-US-VEN-022-06:** Every commitment-strengthening transition revalidates eligibility atomically before it can obtain or strengthen a Resource commitment.

---

# ACCEPTANCE CRITERIA COVERAGE CHECK

- User Stories expected: **69**
- User Stories represented: **69**
- Missing User Stories: **0**
- Extra User Story IDs: **0**
- This is a derived Acceptance Criteria layer; it does not supersede the canonical Acceptance Criteria already present in PRD v1.1.

**Next companion document when requested:** User Flows.
