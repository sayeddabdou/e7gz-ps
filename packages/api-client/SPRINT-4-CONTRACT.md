# Sprint 4 contract (E7-65)

Every endpoint below is a **stub**: the controller answers `501` until its owning task implements it, and `openapi.json` describes the intended success and error responses. Clients build against `pnpm mock` meanwhile. No business logic, persistence or migration is part of E7-65. `Sprint4StubHttpTest` checks that each stub answers `501` through the real security chain; the task that fills a stub replaces its line with that task's own tests. `Sprint4ContractTest` checks the rules below on the committed contract.

The change is **additive only**: no existing path or schema changed; `ApiErrorCode` gained one member.

Sources: arch 09 §1, §3.1, §3.4, §5 · arch 07 §5, §6, §7.4, §8 · arch 01 §14 A8 · FRS §2.10, §2.11, §4.11, §4.12, §4.14 · PCR-004 · ADR-015, ADR-028 · PO decisions of 17 Oct 2026 (below).

## Included (30 operations)

| Surface | Endpoint | Controller | Implemented by |
|---|---|---|---|
| Customer | `GET /customer/account-standing` | `trust/web/CustomerAccountStandingController` | E7-71 (counter, stage, ban), E7-72 (Verified Player) |
| Customer | `GET /customer/points?cursor=&limit=` | `loyalty/web/CustomerLoyaltyController` | E7-66 |
| Customer | `GET /customer/referrals?cursor=&limit=` | `loyalty/web/CustomerLoyaltyController` | E7-67 |
| Customer | `GET /customer/wallet?cursor=&limit=` — read-only (PCR-004) | `loyalty/web/CustomerLoyaltyController` | E7-66 |
| Admin | `GET /admin/users?q=&cursor=&limit=` · `GET /admin/users/{userId}` | `identity/web/AdminUserController` | **no task yet** (D4) |
| Admin | `POST /admin/users/{userId}/trust-adjustment` `{counterAfter, penaltyStageAfter, reason}` | `trust/web/AdminTrustController` | E7-71 |
| Admin | `POST /admin/users/{userId}/bans/{banId}/lift` `{reason}` · `/shorten` `{endsAt, reason}` · `/correct` `{reason}` | `trust/web/AdminTrustController` | E7-71 |
| Admin | `GET /admin/verified-players?status=&cursor=&limit=` · `POST /admin/verified-players/{userId}/suspend` · `/revoke` · `/restore` `{reason}` | `trust/web/AdminVerifiedPlayerController` | E7-72 |
| Admin | `POST /admin/bookings/{bookingId}/attendance/reverse-check-in` · `/reverse-late-present` · `/reverse-no-show` `{reason}` · `/correct` `{correctedOutcome, reason}` | `attendance/web/AdminAttendanceController` | E7-70 |
| Admin | `GET /admin/wallet?q=` · `GET /admin/users/{userId}/wallet` | `loyalty/web/AdminLoyaltyController` | E7-66 |
| Admin | `GET /admin/points?q=` · `GET /admin/users/{userId}/points` · `POST /admin/points/adjust` `{userId, amount, reason}` | `loyalty/web/AdminLoyaltyController` | E7-66 |
| Admin | `GET /admin/referrals?status=&q=` · `POST /admin/referrals/{referralId}/invalidate` `{reason}` | `loyalty/web/AdminLoyaltyController` | E7-67 |
| Admin | `GET PUT /admin/points/settings` `{attendedPoints?, referralPoints?, walletRefundsEnabled?, reason}` | `loyalty/web/AdminLoyaltyController` | E7-68 |
| Admin | `GET PUT /admin/fees` `{bookingFee?, tournamentCreationFee?, reason}` | `config/web/AdminFeeController` | E7-68 |
| Admin | `GET /admin/global-rules` — the no-show ladder, read-only (D7) | `trust/web/AdminGlobalRulesController` | E7-71 |

New shared code enum: `WalletTransactionCause` (`BOOKING_FEE_REFUND`, the only value `ck_wallet_tx_launch_source` allows), registered in `ClosedValueContract`.

Not in Sprint 4: `GET /admin/no-shows` (Sprint 7, S7-P1 "remaining admin modules"); account deletion and anonymisation (Sprint 7); `POST /admin/wallet/adjust` **never** exists (FR-PTS-11, PCR-004).

## 1. PO decisions (17 Oct 2026)

| # | Question | Decision | In the contract |
|---|---|---|---|
| D1 | Admin attendance remediation (arch 09 §5) is not named in the S4-P0 task, but E7-70 and the admin trust screen need it | **In the Sprint 4 contract** | The four `/admin/bookings/{id}/attendance/…` routes |
| D2 | arch 09 §5 lists `/bans/{banId}/lift · /shorten · /correct`; the database accepts `CORRECTED` only when corrected trust history no longer supports the ban (`trust.assert_ban_lifecycle_audit`), and counter/stage fixes are the trust adjustment | **Ban routes sit under the user; `/correct` is kept** | `POST /admin/users/{userId}/bans/{banId}/correct {reason}`; E7-71 decides how it meets the database rule; refused → `409 ADMIN_ACTION_NOT_ALLOWED` |
| D3 | A8 makes five values configurable; arch 09 §5 has only `/admin/fees` for the two fees | **New `GET PUT /admin/points/settings`** for completed-booking points, referral points and the wallet-refund switch; `/admin/fees` keeps the two fees | `AdminPointsSettings`, `AdminFees` |
| D4 | Admin trust and points actions need to find a user and see their standing; no task owns `GET /admin/users` | **List and detail in the Sprint 4 contract**; an implementing task is still to be added | `AdminUserSummary` (FR-ADM-04 columns), `AdminUserDetail` |
| D5 | No `ApiErrorCode` covers an admin action refused by the target's state | **One general code `ADMIN_ACTION_NOT_ALLOWED` (409)** | On ban lift / shorten / correct, Verified Player suspend / revoke / restore, the four attendance routes and referral invalidation |
| D6 | Idempotency-Key on the admin writes | **Required on every remediation and both configuration writes** | `IdempotencyScopeTest.MANDATORY`; `loyalty.configure` already takes a request id |
| D7 | (20 Oct) E7-75 lists a read-only global-rules screen, but the route was left to Sprint 7 | **`GET /admin/global-rules` joins the Sprint 4 contract**, built by E7-71 (it owns the ladder constants) | `AdminGlobalRules {noShowLadder: [{penaltyStage, consecutiveNoShows, banDuration (ISO-8601 period), recurring}]}`; no write route (NFR-37, A8) |

## 2. Conventions (resolved from the documents)

| Topic | Convention and basis |
|---|---|
| Reason | Every admin write carries a required `reason` (1–500 characters): FR-AUD-06, FR-TRS-27, FR-ADM-05. Empty or missing → `400 VALIDATION_FAILED` on `reason`. Kept in the audit row; the customer is notified of the change (FR-NTF-11). |
| Timers | Every time is an instant (`date-time`). The ban's "remaining time" (FR-TRS-15) is `endsAt` against `meta.serverTime`; no field carries seconds (NFR-10). |
| Money and points | Wallet balances, wallet amounts and fees are `Money` (`{amount: "5.00", currency: "EGP"}`, NFR-25). Points are integers. |
| Standing | `CustomerAccountStanding = {consecutiveNoShows, penaltyStage, currentThreshold, currentBan, verifiedPlayer}`: exactly one current ban or `null` (FR-TRS-15); `verifiedPlayer` gives `qualification` + progress (`completedBookings` / `requiredBookings`) and the operational `status` as separate fields (FR-ACC-11). Open to every customer state (FRS §2.11). |
| Points reversal | The customer sees the **applied** amount only (a reversal of 1,000 at 300 shows `-300`). The admin sees `requestedAmount`, `amount`, `unrecoveredAmount` (UNRECOVERED_REVERSAL_AMOUNT, never a debt) and `balanceAfter` (FR-PTS-03, FR-PTS-04, FR-PTS-09). `POST /admin/points/adjust` takes a non-zero `amount`. |
| Referrals | The customer sees their code and their referrals' status, sign-up and reward, **not** who signed up. The admin sees the FR-ADM-09 columns. Invalidation of a REWARDED referral returns the compensating transaction as `reversal`. |
| Attendance | `correct` takes `correctedOutcome` (`AttendanceResult`: ATTENDED, ATTENDED_INTERRUPTED, ATTENDANCE_UNCONFIRMED or ABSENT_NO_SHOW; `NOT_APPLICABLE` → `400`) — no generic undo (FR-ATT-13, arch 07 §7.4). Reversals follow arch 07 §7.4. |
| Configuration | Changes take effect **now**, for later events only (`ck_config_future_only`, FR-ADM-11); each value is shown with `effectiveFrom`. Omitted fields keep their value; a value equal to the current one changes nothing; none given → `400`. |
| Wallet | Read-only everywhere: `Sprint4ContractTest` fails if any `/wallet` path gets a write method. |
| Separate DTOs | Customer routes use only `Customer*` schemas (plus `Money` and the shared code enums); admin routes never use `Customer*` or `Venue*` (arch 09 §1.1). |
| Lists | Cursor paging (arch 09 §1.2). `q` matches part of the full name, username or email, or the exact phone, trimmed and case-insensitive. |

## 3. For the Clients lane

- **E7-74 (customer app):** standing, points, referrals and wallet screens; the wallet explains it holds booking-fee refunds and shows `0.00` clearly (PCR-004).
- **E7-75 (admin web):** every write asks for a reason; `409 ADMIN_ACTION_NOT_ALLOWED` means "refresh and look again"; fees and points settings show `effectiveFrom`; the user page reads `GET /admin/users/{userId}`.
