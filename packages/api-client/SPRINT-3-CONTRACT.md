# Sprint 3 contract (E7-54)

Every endpoint below is a **stub**: the controller answers `501` until its owning task implements it, and `openapi.json` describes the intended success and error responses. Clients build against `pnpm mock` meanwhile. No business logic, persistence or migration is part of E7-54. `Sprint3StubHttpTest` checks that each stub answers `501` through the real security chain; the task that fills a stub replaces its line with that task's own tests.

Sources: arch 09 §1, §3.1, §3.3, §4.1, §4.3 · arch 07 §7.1 · arch 10 §5.2 · FRS §2.5, §2.6, §4.1, §4.5, §4.6 · ADR-011, ADR-015 · PO decisions of 6 Oct 2026 (E7-39 review) and 11 Oct 2026 (below).

## Included (17 operations)

| Surface | Endpoint | Controller | Implemented by |
|---|---|---|---|
| Customer | `POST /customer/bookings/{id}/late-confirm/accept` · `/decline` | `booking/lateconfirm/web/CustomerLateConfirmController` | E7-58 |
| Venue | `POST /venue/bookings/{id}/late-confirm` | `booking/lateconfirm/web/VenueLateConfirmController` | E7-58 |
| Venue | `POST /venue/bookings/{id}/check-in` · `/late-present` · `/no-show` | `attendance/web/VenueAttendanceController` | E7-59 |
| Venue | `POST /venue/bookings/{id}/cancel-confirmed` `{ownerPassword}` | `booking/web/VenueBookingController` | E7-114 |
| Venue | `GET /venue/overview` → `{todayRevenue, monthRevenue, todayBookings, pendingRequests}` | `booking/web/VenueOverviewController` | E7-115 |
| Customer | `POST /customer/auth/password-reset/request` · `/complete` (public) | `identity/web/CustomerAuthController` | E7-57 |
| Customer | `GET` `PATCH /customer/profile` · `POST …/username` · `…/phone` · `…/phone/verify` | `identity/web/CustomerProfileController` | E7-57 |
| Customer | `POST /customer/profile/email` · `…/email/verify` | `identity/web/CustomerProfileController` | E7-57, once the PO decides email verification |

Changed existing schemas (fields added, nothing removed):

| Schema | Added | Filled by |
|---|---|---|
| `CustomerBookingDetail` | `lateConfirm: {expiresAt, paused} \| null` — while AWAITING_CUSTOMER_CONFIRMATION only (arch 09 §3.3) | E7-58 (E7-49's detail reads it) |
| `VenueBooking` | `lateConfirmExpiresAt \| null`, `lateConfirmPaused`, `attendance: {checkInWindow, lateWindow, checkedInAt, latePresentAt, noShowAt} \| null` | E7-58, E7-59 (E7-49's list and detail read them) |

Until those tasks land, the implemented routes that return these schemas send `lateConfirm: null`, `lateConfirmExpiresAt: null`, `lateConfirmPaused: false` and `attendance: null`. None of the bookings they return is AWAITING or has started.

Added by E7-55 (PO decision 12 Oct 2026, FR-NTF-09), implemented in the same PR, not a stub:

| Surface | Endpoint | Controller | Implemented by |
|---|---|---|---|
| Venue | `GET /venue/notifications?cursor=&limit=` → `PageView<VenueNotification>`, newest first, same shape as `GET /customer/notifications` | `notification/web/VenueNotificationController` | E7-55 (page: E7-63) |
| Venue | `POST /venue/notifications/{id}/read` → 204; already read changes nothing; another venue's → 404 | `notification/web/VenueNotificationController` | E7-55 |

`VenueNotification.code` is a `notification_catalogue` code; the Dashboard renders the text in EN and AR from the code (ADR-021). The booking codes it receives today: `VENUE_NEW_BOOKING_REQUEST`, `VENUE_PENDING_REQUEST_EXPIRING`, `VENUE_BOOKING_AUTO_CANCELLED`, each with `bookingId` and empty `params`.

Added by E7-56 (PO decision 15 Oct 2026, NFR-29), implemented in the same PR, not a stub:

| Surface | Endpoint | Controller | Implemented by |
|---|---|---|---|
| Admin | `GET /admin/alerts?acknowledged=&kind=&severity=&cursor=&limit=` → `PageView<AdminAlert>`; open alerts (`acknowledged=false`) by default, newest first | `admin/web/AdminAlertController` | E7-56 (page: E7-86) |
| Admin | `POST /admin/alerts/{alertId}/acknowledge` `{reason?}` → 200 `AdminAlert`; the same admin again returns the same alert; another admin → 409 `ADMIN_ALERT_ALREADY_ACKNOWLEDGED`; unknown → 404 | `admin/web/AdminAlertController` | E7-56 |

`AdminAlert.kind` and `severity` are open text: the kinds raised today are `SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR`, `BOOKING_INTEGRITY_FAILURE`, `INVARIANT_VIOLATION`, `OUTBOX_DEAD_LETTER`, `REFRESH_TOKEN_REUSE` and `TOURNAMENT_CANCELLATION_ABUSE` (`ACCOUNT_LOGIN_LIMIT_REACHED` from E7-57); severities `SEV_1`, `SEV_2`, `SEV_3` and `RISK` (the abuse signal). `payload` is diagnostic data (ids, states, instants), never rule text: the Admin web shows it as data.

Not in Sprint 3: `GET /customer/account-standing` (Sprint 4, E7-65), `POST /customer/account/deletion-request` (Sprint 7), `GET /venue/revenue` (Sprint 7, E7-104).

## 1. PO decisions (11 Oct 2026)

| # | Question | Decision | In the contract |
|---|---|---|---|
| D1 | Email change needs verification of the new value (FR-ACC-07), but email verification was deferred on 30 Sep 2026 | **Stub both routes now; implementation waits for the email-verification decision** | `POST /customer/profile/email {email}` → 201 `{otpChallengeId}` and `POST /customer/profile/email/verify {otpChallengeId, code}` → the profile. The same pair verifies the current email (FR-ACC-05) or changes it to a new one (FR-ACC-07) |
| D2 | Phone change is two steps but arch 09 §3.1 lists one route | **New route `POST /customer/profile/phone/verify`**, recorded in arch 09 §3.1 | `POST /customer/profile/phone {phone}` → 201 `{otpChallengeId}`; `…/phone/verify {otpChallengeId, code}` → the profile |
| D3 | Password reset shape (FR-ACC-09); email reset is deferred | **Phone OTP only. `complete` → 204 and every refresh token is revoked, so the customer logs in again. An unknown or never-verified phone on `request` is 404 `NOT_FOUND`**, like `/otp/send` | `request {phone}` → 201 `{otpChallengeId}`; `complete {otpChallengeId, code, password}` → 204 |
| D4 | Venue Late Confirm when the venue is not inside an operating session now, the original interval no longer fits the hours, or the area is inactive (FR-LCF-02) | **Reuse existing codes**: `INTERVAL_NOT_WITHIN_ONE_SESSION`, `AREA_INACTIVE`. No new enum member | On `POST /venue/bookings/{id}/late-confirm` and the customer's accept |
| D5 | Which bookings `todayBookings` counts on the Overview (FR-VEN-35 does not say) | **Every booking of the venue on today's business date, online and manual, whatever its status** — cancelled, rejected and no-show included | `VenueOverview.todayBookings` |
| D6 | arch 09 §3.3's `pauseCauseCode` | **Only `paused`, no reason.** A reason can be added later as a non-breaking field once a closed list of pause causes exists | `CustomerBookingLateConfirm = {expiresAt, paused}` |

## 2. Conventions (resolved from the documents)

| Topic | Convention and basis |
|---|---|
| Idempotency-Key | **Required** on the seven Sprint 3 booking writes: Late Confirm accept / decline / send, check-in, late present, no-show, cancel-confirmed (arch 09 §3.3/§4.1, ADR-015). **Optional** on the profile and password-reset writes: no commitment, money or points. `IdempotencyScopeTest` classifies every write. |
| Owner Password | Only `cancel-confirmed` (FR-VEN-03, arch 09 §4.1 "yes"): `{ownerPassword}` in the body, write-only, never a header (ADR-011); absent or blank → 423 `OWNER_PASSWORD_REQUIRED`, wrong → 423 `OWNER_PASSWORD_INVALID`. Late Confirm and attendance are "no" (FRS §2.4). The owner-password matrix row `CANCEL_CONFIRMED_ONLINE_BOOKING` stays `built=no` until E7-114. |
| Neutral Late Confirm | The venue route declares `CUSTOMER_NOT_ELIGIBLE_FOR_LATE_CONFIRM` as its **only** customer-ineligibility code, with no details or field (FR-LCF-05, NFR-19, arch 10 §5.2). Its other codes concern the booking, the venue or the room. Its success body is `{bookingId, status, lateConfirmExpiresAt}`, with no reason. `Sprint3ContractTest` checks that no customer-revealing code (`COMMITMENT_LIMIT_REACHED`, `OVERLAPPING_COMMITMENT`, `ACCOUNT_BANNED_FOR_THIS_ACTION`, `ACCOUNT_DELETED`, `CUSTOMER_NOT_ELIGIBLE`, `LATE_CONFIRM_CUSTOMER_CONFLICT`, …) is declared on any status. The customer's schedule conflict is checked only on the customer's accept (FR-LCF-15): `409 LATE_CONFIRM_CUSTOMER_CONFLICT`. |
| Timers | Every time is an instant (`date-time`), never remaining seconds (NFR-10, arch 09 §1.2): `lateConfirm.expiresAt`, `VenueBooking.lateConfirmExpiresAt`, the attendance windows, `usernameChangeAvailableAt`. Clients count down against `meta.serverTime`, never the device clock. |
| Late Confirm pause | `paused: true` while an emergency or selling block pauses the timer (FR-OPS-41): Accept is refused with `VENUE_SELLING_BLOCK_ACTIVE` / `RESOURCE_SELLING_BLOCK_ACTIVE`, Decline still works. arch 09 §3.3's illustrative `pauseCauseCode` is left out: the pause cause has no closed code list in the model (only free text in the pause history), and the venue response timer carries only its flag too. |
| Attendance windows | `VenueBooking.attendance` (ONLINE only; `null` for MANUAL, FR-MAN-04) gives `checkInWindow` = `[startAt, startAt + 15 min)` and `lateWindow` = `[startAt + 15 min, endAt)` as half-open `{opensAt, closesAt}` (arch 07 §7.1, FRS §2.5), plus the evidence instants (`checkedInAt`, `latePresentAt`, `noShowAt`). Evidence reversed by an admin is absent. The Dashboard enables each action from these values and the server time, never from its own +15-minute rule (E7-63). |
| Attendance errors | Outside its window, or with blocking evidence, each action answers its own 422: `CHECK_IN_WINDOW_CLOSED` (also before startAt and at exactly +15:00), `LATE_PRESENT_NOT_AVAILABLE`, `NO_SHOW_NOT_AVAILABLE`. A manual booking answers the same code (it has no attendance actions, FR-MAN-04). A booking no longer in progress → 409 `BOOKING_STATE_CHANGED`. Each returns `VenueAttendance {bookingId, status, attendanceResult, checkedInAt, latePresentAt, noShowAt}`. |
| Cancel confirmed | Only a CONFIRMED **online** booking; no time limit while CONFIRMED (PO decision 6 Oct 2026). Anything else (PENDING, ACTIVE, already cancelled, a manual booking, which uses `cancel-manual`) → 409 `BOOKING_STATE_CHANGED`; another venue's → 404. Returns the `VenueBooking`. |
| Late Confirm responses | `booking.lateconfirm` is its own module (arch 03), so its routes return their own small DTOs instead of the booking module's: the customer gets `CustomerLateConfirmOutcome {bookingId, status, terminalReason}` and reads the full booking from `GET /customer/bookings/{id}`. |
| Overview | Money as in SPRINT-2-CONTRACT.md (`{amount: "150.00", currency: "EGP"}`). Today and this month are business dates in the venue's time zone; revenue counts only COMPLETED bookings (PO decision 6 Oct 2026). No payment method, no cash/card split (FR-VEN-36). The Dashboard sums nothing. |
| Profile | `PATCH /customer/profile` edits `fullName` only; username, phone and email have their own routes (FR-ACC-07). `CustomerProfile` = `{userId, fullName, username, phone, phoneVerified, email, emailVerified, usernameChangeAvailableAt, joinedAt}`; standing, Verified Player and referral code are Sprint 4's. The new password field is `password` (VALIDATION RULES §2.1, 400 `VALIDATION_FAILED` on field `password`, as at registration). |
| Separate DTOs | Customer routes use only `Customer*` schemas, venue routes only `Venue*` (arch 09 §1.1); `Sprint3ContractTest` walks every reference. |
