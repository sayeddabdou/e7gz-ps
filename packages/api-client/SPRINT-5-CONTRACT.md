# Sprint 5 contract (E7-77)

Every endpoint below is a **stub**: the controller answers `501` until its owning task implements it, and `openapi.json` describes the intended success and error responses. Clients build against `pnpm mock` meanwhile. No business logic, persistence or migration is part of E7-77. `Sprint5StubHttpTest` checks that each stub answers `501` through the real security chain; the task that fills a stub replaces its line with that task's own tests. `Sprint5ContractTest` checks the rules below on the committed contract.

The change is **additive**, with three exceptions, each a PO decision of 19 Nov 2026 (below): `POST /admin/venue-accounts/{id}/disable` and `/enable` now **require** the `Idempotency-Key` (D4), `POST /venue/opening-hours/commit` gains an optional `emergencyId` (D5), and `ApiErrorCode` gains `OPERATIONAL_STATE_CHANGED` (D6).

Sources: arch 09 §1, §4.2, §4.3, §5 · arch 06 §2.5, §2.7, §2.8 · FRS F-18…F-23, FR-VEN-30…FR-VEN-33, FR-OPS-01…FR-OPS-44 · Spec v5 §15, §39–§43 · ADR-011, ADR-015 · PO decisions of 19 Nov 2026 (below).

## Included (22 operations, plus the two changes to existing routes)

| Surface | Endpoint | Controller | Implemented by |
|---|---|---|---|
| Venue | `GET /venue/maintenance?resourceId=` (D1) · `POST /venue/maintenance` `{resourceId, startAt, endAt, reason, ownerPassword?}` · `PATCH /venue/maintenance/{id}` `{startAt?, endAt?, reason?, ownerPassword?}` · `DELETE /venue/maintenance/{id}` | `operations/web/VenueMaintenanceController` | E7-81 |
| Venue | `GET /venue/closures` (D1) · `POST /venue/closures/preview` `{closureId?, proposal}` (D2) · `POST /venue/closures` · `PATCH /venue/closures/{id}` `{proposal, previewToken, ownerPassword?}` · `DELETE /venue/closures/{id}` | `operations/web/VenueClosureController` | E7-81 |
| Venue | `GET /venue/emergencies` (D1) · `POST /venue/emergencies/venue-closure` `{reason, expectedEndAt}` · `POST /venue/emergencies/resource-outage` `{resourceId, reason, expectedReturnAt}` | `operations/web/VenueEmergencyController` | E7-82 |
| Venue | `POST /venue/emergencies/{id}/extend/preview` `{expectedEndAt}` (D2) · `POST /venue/emergencies/{id}/extend` `{expectedEndAt, previewToken}` · `POST /venue/emergencies/{id}/end` | `operations/web/VenueEmergencyController` | E7-82 (end: E7-84 for the owner-review entry) |
| Venue | `POST /venue/emergencies/{id}/convert/preview` `{target, closure?, maintenance?}` (D2) · `POST /venue/emergencies/{id}/convert` `{conversion, previewToken, ownerPassword?}` | `operations/web/VenueEmergencyController` | E7-82 |
| Venue | `GET /venue/owner-reviews` · `POST /venue/owner-reviews/{resourceId}/resolve` `{action, maintenance?, ownerPassword}` | `operations/web/VenueOwnerReviewController` | E7-84 |
| Admin | `GET /admin/venue-accounts?accountStatus=&cursor=&limit=` (E7-37 carry-forward) | `venue/web/AdminVenueAccountController` | E7-78 |
| Admin | `GET /admin/owner-reviews?venueId=&resolved=&cursor=&limit=` (D3) | `operations/web/AdminOperationsController` | E7-84 |
| Admin | `GET /admin/emergencies?venueId=&status=&cursor=&limit=` (D3) | `operations/web/AdminOperationsController` | E7-82 |
| Changed | `POST /admin/venue-accounts/{id}/disable` · `/enable`: `Idempotency-Key` **required**, wired now (D4) | `venue/web/AdminVenueAccountController` | E7-77 (key); E7-78 (O10 consequences) |
| Changed | `POST /venue/opening-hours/commit`: optional `emergencyId` (D5); with it the commit answers `501` until E7-82 | `venue/web/VenueOpeningHoursController` | E7-82 |

New shared code enums in the contract (they already existed in `ps.e7gz.common.codes` and `ClosedValueContract`): `EmergencyScope`, `EmergencyStatus`, `EmergencyConversionTarget`, `VenueClosureKind`, `OwnerReviewResolution`.

Not in Sprint 5: the customer side needs no new route; E7-87 shows the operational states from the existing booking detail and history codes (`VENUE_RESPONSE_INTERRUPTED_BY_ACCOUNT_DISABLE`, written by E7-78).

## 1. PO decisions (19 Nov 2026)

| # | Question | Decision | In the contract |
|---|---|---|---|
| D1 | arch 09 §4.2 has no `GET` for closures, maintenance or emergencies, but the Dashboard needs each row's id to edit, delete, end or extend it (the schedule's spans carry none) | **Add the three read-only lists** | `GET /venue/maintenance`, `/closures`, `/emergencies` (rows not yet ended; emergencies: ACTIVE and AWAITING_CONFIRMATION) |
| D2 | FR-VEN-30 ("Save & Cancel Affected Bookings") and FR-OPS-22 ("previews the impact, takes confirmation") need the affected bookings shown before saving; arch 09 has a preview only for opening hours | **Preview + commit, like opening hours** | `…/preview` → `{previewToken, affected[], ownerPasswordRequired}`; the save sends `previewToken`; a grown set is `409 AFFECTED_SET_CHANGED_REFRESH_PREVIEW` |
| D3 | E7-86 wants an owner-review queue and an emergencies monitor; arch 09 §5 has no admin route for them | **Two read-only admin lists**; no admin action (resolution stays with the venue's Owner Password, FR-OPS-33) | `GET /admin/owner-reviews`, `GET /admin/emergencies` |
| D4 | With E7-78 a venue-account disable cancels AWAITING bookings and refunds their Booking Fee (to the Wallet, or as Points; see Refunds below); disable and enable were on the "setup, key optional" list (PO 3 Oct 2026) | **Both require the `Idempotency-Key`** (same rule as the hours commit since E7-113) | `IdempotencyScopeTest.MANDATORY`; the Admin web already sends a key on every write |
| D5 | Converting a venue Emergency into an opening-hours change (FR-OPS-23) | **Through the hours commit** with an optional `emergencyId`; `/convert` takes CLOSURE (venue) or MAINTENANCE · DISABLED · ARCHIVED (resource) | `VenueOpeningHoursCommitRequest.emergencyId`; `/convert` with OPENING_HOURS_CHANGE → `422 CONVERSION_TARGET_NOT_ALLOWED` |
| D6 | No code covers "this Emergency or Owner Review is no longer in the state the action needs" (staff A extends what staff B ended a second earlier) | **New general code `OPERATIONAL_STATE_CHANGED` (409)**, like `BOOKING_STATE_CHANGED`; one row added to arch 09 §1.5 | On extend, end, convert (and their previews), owner-review resolve, maintenance/closure edit or delete of an ended row, and the hours commit with `emergencyId` |

The new routes join arch 09 §4.2 and §5 in a later docs change (D1–D3); only the §1.5 row is edited here, because `ApiErrorCodeTest` requires every code to be listed there.

## 2. Conventions (resolved from the documents)

| Topic | Convention and basis |
|---|---|
| Owner Password | Body only, `writeOnly`, never answered or logged (ADR-011, arch 09 §4.3); redacted from each request's `toString`. Needed: maintenance over the rolling 24-in-48-hours threshold (FR-OPS-03, FR-OPS-04); a Closure or conversion that cancels a protected booking (FR-VEN-30); a conversion to DISABLED or ARCHIVED (the resource's own rule, FR-OPS-23); every owner-review resolution (FR-OPS-33). **Never** to declare, extend or end an Emergency, or to delete (FR-OPS-06, FR-OPS-11, FR-VEN-04). When sent, it is always checked. |
| Idempotency-Key | **Required** on every write that can cancel commitments or move refunds (ADR-015): Closure create and edit, both declarations, extend, end (an over-threshold resource enters Owner Review and its cascade, FR-OPS-27), convert, venue-account disable and enable. **Optional** on maintenance writes (they never cancel, FR-OPS-02), deletes, previews and the owner-review resolution (blocking commitments refuse it, FR-OPS-43). |
| Refunds | Every Booking Fee refund in Sprint 5 (Closure, Emergency, Owner Review cascade, venue-account disable) stays **inside the app**: the part paid with Points comes back as Points, the rest goes to the customer's E7GZ Wallet. No money goes back to a card or bank, and the Wallet cannot be withdrawn (FR-PTS-10, FR-PTS-14…16, PCR-004, PCR-009, PCR-010). No Sprint 5 route sends money out. |
| Times | Every time is an instant (`date-time`); periods are `[startAt, endAt)`. An Emergency starts at the command instant (FR-OPS-06); its expected end must fall inside the current Operating Session (FR-OPS-07, FR-OPS-11) or `422 EMERGENCY_EXPECTED_END_OUT_OF_SESSION`. Dashboards show timers from these instants and `meta.serverTime`, never device time. |
| Owner review refusal | `422 OWNER_REVIEW_RESOLUTION_BLOCKED` carries `details.blockingReservations [{bookingId, period, kind}]` (arch 09 §4.2, FR-OPS-43); the Dashboard shows them and never presents the blocked action as executable. |
| Separate DTOs | Venue routes use only `Venue*` schemas, admin routes only `Admin*` (plus the envelope and the shared code enums) — arch 09 §1.1. |
| Lists | Venue lists are small and unpaged (rows not yet ended). Admin lists use cursor paging (arch 09 §1.2), newest first; the venue-account list is ordered by venue name then id. |

## 3. For the Clients lane

- **E7-85 (Dashboard)**: maintenance and closure forms ask for the Owner PIN only when the server answers `423 OWNER_PASSWORD_REQUIRED` (maintenance) or the preview says `ownerPasswordRequired` (closures, conversions). Declare, extend and end never show a PIN prompt. Show `OPERATIONAL_STATE_CHANGED` as "refresh and look again".
- **E7-86 (Admin)**: venue accounts page from `GET /admin/venue-accounts`; the owner-review queue and the emergencies monitor are read-only and link to the venue's page.
- **E7-87 (Customer app)**: no new route. When a closure, emergency or disable cancels a booking, show its refund as Wallet balance (or Points), never as money back to the card.
- **All three apps**: no screen offers or implies a cash, card or bank refund.
