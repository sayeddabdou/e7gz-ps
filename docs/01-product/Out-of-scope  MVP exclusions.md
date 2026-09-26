# E7GZ PS
# OUT-OF-SCOPE & MVP EXCLUSIONS v1.0 — FUNCTIONAL BASELINE

**Status:** Derived functional baseline  
**Authority:** Product Specification v5.0 — FROZEN BASELINE → PRD v1.1 — APPROVED FOR BUILD → FRS v1.1 — APPROVED FOR BUILD  
**Market:** Egypt — initial launch in Cairo  
**Timezone:** Africa/Cairo  
**Currency:** EGP

> **No Product change.** This document is a derived working view of the approved baselines. It does not create new states, transitions, reason codes, timers, authorization rules, validation predicates, Trust semantics, financial rules, or scope. If anything here conflicts with Product Specification v5.0, v5.0 wins.

---

# 0. PURPOSE

This file records deliberate Launch exclusions so they are not rediscovered as missing requirements. An item listed here must not be implemented or exposed as a Launch Product capability unless Product Change Control explicitly reintroduces it.

---

# 1. GLOBAL RELEASE EXCLUSIONS — MUST NOT APPEAR IN LAUNCH SURFACES

The frozen Product Specification explicitly excludes:

- PC Gaming
- Kick Off
- Favorites
- Community Feed
- Elo
- Player Leaderboards
- Match History
- Tournament Brackets
- Tournament Score Tracking
- Tournament Payment Collection
- Prize Settlement
- Food / Drink / Snack Ordering
- Venue POS
- Inventory management
- Supplier Management
- Payroll
- Employee Attendance
- Individual Cashier Accounts
- Venue Manager Accounts
- Separate Venue Owner Accounts
- Full Accounting
- Tax Accounting
- Multi-branch Venue Account Management

---

# 2. CUSTOMER MOBILE APP — MVP EXCLUSIONS / NON-CAPABILITIES

- **Arabic Customer UI is not Launch scope.** Customer App is English at Launch but must remain localization-ready for future Arabic/RTL.
- **No direct Customer hard-delete of retained historical data.** The Customer may initiate Account Deletion in-app, but completion uses the audited Support / Platform Admin anonymisation process and preserves required history.
- **No Customer-side attendance proof** such as QR, PIN, device confirmation or biometrics. Check-in is a Venue staff action.
- ~~No active Customer Wallet screen at Launch.~~ **Changed by PCR-004:** a read-only wallet screen (balance + refund history) ships at Launch; no top-up, no pay-with-wallet.
- **No points-to-wallet conversion at Launch.**
- **No online collection of Venue gaming charges.** Customer ↔ Venue gaming payment remains outside E7GZ.
- **No booking price filter** in Find Available.
- **No Favorites / social feed / Elo / leaderboards / match history.**
- **No Customer pre-emptive opt-out from VENUE_RESPONSE_EXPIRED revival.** A later Late Confirm is an explicit new reactivation request that the Customer may Accept/Decline.
- **No ordinary Customer cancellation after `startAt`.**
- **No automatic Late Confirm acceptance.**

---

# 3. VENUE DASHBOARD — MVP EXCLUSIONS / NON-CAPABILITIES

- **Not a POS, ERP, accounting, tax or reconciliation system.** Revenue summary is operational reporting only.
- **No cash/card/terminal/Visa/Mastercard tracking.**
- **No food/drink/snack ordering, inventory, suppliers, payroll or employee attendance.**
- **No per-cashier, manager or separate owner user accounts.** One shared Venue Account + Owner Password is the Launch model.
- **No multi-branch management.** One Venue account represents one site.
- **No Venue self-service onboarding.** Platform Admin performs onboarding.
- **No Venue self-service Forgot Password flow.** Platform Admin resets Venue login and Owner Password. The Venue may change its own Owner Password when authenticated, but reset is an Admin action.
- **No generic Owner session / unlock window.** Owner Password is fresh per protected action.
- **No generic “End Active Booking Early” action.** Active Booking early termination is limited to frozen emergency/owner-review/Admin paths.
- **No Support/Venue replacement booking shortcut.** Customer booking creation uses the normal Online Booking predicate.
- **No emergency declaration as a bypass for planned future closures or protected Resource changes.**

---

# 4. TOURNAMENTS — MVP EXCLUSIONS / NON-CAPABILITIES

- No brackets.
- No score tracking.
- No per-match scheduling.
- No per-match Resource assignment.
- No Tournament payment collection.
- No prize settlement/distribution.
- Entry fee, prize and Venue cost are informational only.
- No automatic cancellation of an existing Booking to make space for a Tournament. Existing reservations always win.
- No `Run Anyway` override when the mandatory checkpoint has fewer than 8 active participants.
- No Creator cancellation through E7GZ after Tournament becomes CONFIRMED.
- No unrestricted Venue “operational termination” power; only the explicit Owner-Password-protected Tournament Operational Termination path exists for a serious Tournament-specific incident.
- No replacement participant may consume a still-valid pre-start capacity slot reserved for an `INELIGIBLE/BAN` participant.
- No BAN-based automatic restoration exists for `INELIGIBLE/ACCOUNT_DELETED`, and no participant is automatically restored at or after `startAt`.

---

# 5. BOOKING / ATTENDANCE / SUPPORT — MVP EXCLUSIONS

- No Auto Accept for Online Bookings.
- No third Support booking-creation path and no Support Replacement Booking.
- No historical/back-dated Manual Booking.
- No Manual Booking check-in, no-show or Trust/reward participation.
- No ordinary customer check-in proof mechanism beyond Venue staff action.
- No Venue staff reversal of Check-in, Late Present or No-show; these are Platform Admin correction actions.
- No terminal Booking reason invented for validation-only conflicts. Resource/schedule conflicts use validation failure or the defined non-terminal Booking Events.
- No silent restoration of a Booking cancelled by a Ban correction, emergency, account-disable path or owner-review cascade. Rebooking uses normal flow.

---

# 6. REVIEWS / MODERATION — MVP EXCLUSIONS

- A completed Booking is **not required** to review a Venue; therefore “verified purchase only reviews” is not the Launch model.
- Venue has no Review moderation authority; Venue view is read-only.
- `REMOVED` Review cannot be restored.
- A post-removal re-entry Review does not auto-publish; `PENDING_MODERATION` remains pending until Admin decision.
- No public display of Customer Full Name in Reviews; current Username is the public identity.

---

# 7. TRUST / REWARDS / FINANCIAL EXCLUSIONS

- No manual editable authoritative Trust counter/stage as the source of truth; Trust is derived from immutable events.
- No back-dated operational Ban activation from a retroactive correction.
- No concurrent active Ban instances for one Customer.
- No negative Points balance / future Points debt.
- No Wallet adjustment as a Launch Admin action.
- No future-dated change rewriting historical price, fee, points or referral snapshots.
- Booking Fee is **EGP 0 at Launch**. The future non-zero fee model exists, but Launch payment collection/settlement does not.

---

# 8. PLATFORM / MARKET EXCLUSIONS

- Initial market is Egypt, initial launch Cairo, one Product timezone (`Africa/Cairo`) and EGP.
- Additional cities and multi-timezone support are future candidates, not Launch scope.
- Platform Admin controls the marketplace but does not run daily Venue operations.
- Current architecture must not introduce excluded Product concepts merely because a technical framework makes them easy to add.

---

# 9. FUTURE CANDIDATES — RECORDED, NOT COMMITTED

The PRD records the following as possible future work, **not commitments**:

- Non-zero Booking Fee with payment collection and settlement
- Wallet activation and points-to-wallet conversion
- Arabic Customer Mobile UI
- Tournament brackets, scoring and per-match scheduling
- Tournament payment collection and prize settlement
- Kick Off
- Favorites, Community Feed, Elo, leaderboards and match history
- Customer-side check-in proof (QR or PIN)
- Venue self-service onboarding
- Multi-branch Venue accounts and role-based Venue users
- Additional cities and multi-timezone support
- Food, drink and snack ordering / Venue POS

---

# 10. CHANGE-CONTROL RULE

If a team wants to implement an item in this file as a Product-visible Launch capability, it is **not** a normal implementation detail. The proposed behaviour must first be reconciled with Product Specification v5.0 and, where required, introduced through Product Change Control.
