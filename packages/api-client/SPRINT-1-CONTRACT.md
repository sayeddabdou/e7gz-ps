# Sprint 1 contract — what E7-11 added ahead of E7-22

E7-11's acceptance criteria need a production contract with real endpoints. With the PO's approval, the Sprint 1 controller contracts (E7-22 step 1) were added early. Every endpoint is a **stub**: the controller answers `501` until its owning task implements it, and `openapi.json` describes the intended success and error responses. Business logic, persistence and authentication are **not** implemented.

Sources:
- arch 09 §1, §3.2, §4.1–§4.3, §5
- arch 03 §4–§9
- ADR-011, ADR-015
- the FRS rows cited in each controller
- the `CHECK` lists in `V1__baseline.sql` for every enum spelling, which `OpenApiProductionContractTest` checks against the live database

## Included (34 operations)

| Surface | Endpoints | Controller | Implemented by |
|---|---|---|---|
| Customer (public) | `GET /customer/areas` | `geography/web/CustomerAreaController` | E7-23 |
| Customer (public) | `GET /customer/venues` · `/venues/map` · `/venues/{venueId}` · `GET /customer/find-available` | `inventory/availability/web/CustomerDiscoveryController` | E7-34 |
| Venue | `GET /venue/availability?date=` | `inventory/availability/web/VenueAvailabilityController` | the schedule task (E7-36 needs it) |
| Venue | `GET /venue/resources` · `/{resourceId}` · `POST …/disable` · `/re-enable` · `/archive` · `/restore` · `/console` | `resource/web/VenueResourceController` | E7-26 |
| Venue | `GET PUT /venue/pricing` · `PUT DELETE /venue/resources/{resourceId}/price-override` | `pricing/web/VenuePricingController` | E7-27 |
| Venue | `GET /venue/opening-hours` · `POST …/preview` · `POST …/commit` | `venue/web/VenueOpeningHoursController` | E7-25 |
| Venue | `POST /venue/owner-password` | `venue/web/VenueOwnerPasswordController` | E7-24 |
| Admin | `GET POST /admin/areas` · `PATCH /{areaId}` · `POST …/activate` · `/deactivate` | `geography/web/AdminAreaController` | E7-23 |
| Admin | `GET POST /admin/venues` · `PATCH /{venueId}` · `POST …/onboarding/complete` | `venue/web/AdminVenueController` | E7-23 |
| Admin | `POST /admin/venue-accounts/{venueAccountId}/disable` · `/enable` · `/reset-login-password` · `/reset-owner-password` | `venue/web/AdminVenueAccountController` | E7-24 |

## 1. Agreed with the Product Owner (28 Sep 2026)

| Decision | Where it shows in the contract |
|---|---|
| Customers select an activity before browsing venue lists or maps. | `activity` is a **required** query parameter on `GET /customer/venues` and `GET /customer/venues/map`. It stays optional on venue details, which a deep link can open (FR-DIS-08). |
| Completing onboarding does **not** activate the venue; activation is a separate admin action. | `POST /admin/venues/{venueId}/onboarding/complete` is documented as ONBOARDING → **INACTIVE**, an existing status (FR-VEN-07: hidden from discovery, Dashboard and manual booking available). The database allows it and forbids any return to ONBOARDING (`tr_127_venue_marketplace_transition`). No activation endpoint is added (see §3). |
| For both venue-login and Owner Password resets, the admin chooses the replacement and gives it to the venue through support. | Both reset endpoints take `{ newPassword }` (write-only) and answer 204. No temporary password, reset link or forced change. Audit applies to both, and the Owner Password reset notifies the venue (FR-ADM-03, FR-VEN-05). |

## 2. Technical conventions (resolved from the documents; the implementing task follows them)

| Topic | Convention and basis |
|---|---|
| Paging | `?cursor=&limit=` (arch 09 §1.2). `nextCursor` sits beside the items inside `data`: `PageView {items, nextCursor}`, with null on the last page. |
| Money | `{ "amount": "150.00", "currency": "EGP" }`: a 2-decimal string, never a float (arch 09 §1.2, NFR-25). |
| Times | Instants are RFC 3339 UTC (§1.2). Venue-local wall-clock times (opening hours) are `HH:mm:ss` strings. |
| Weekday | `venue_opening_day.weekday` 0–6 means **0 = Monday … 6 = Sunday**. No database function or Java code reads the column yet, so there was nothing to conflict with. This numbering makes FR-VEN-19 and ADR-009's "Sunday → Monday" wrap the 6 → 0 wrap, and maps directly to `DayOfWeek.getValue() - 1` and `EXTRACT(ISODOW) - 1`. **E7-25's session generator must use it.** |
| Distance, rating, images | `distanceMeters`: integer metres, null without the customer's position. `averageRating`: number, null with no reviews. Image fields are URLs (signed GET URLs, E7-23 / arch 12 §2). |
| Customer position | `lat` and `lng` are optional on list, map and details. FR-DIS-10 and FR-DIS-11 show distance on the map and details too, which needs the position. |
| Map bounds | `bounds=minLng,minLat,maxLng,maxLat`, the usual map-viewport order. |
| Owner Password | Always a write-only body field, never a header (ADR-011, arch 09 §4.3). This includes `DELETE …/price-override`, whose body Spring and openapi-fetch both carry. |
| Idempotency-Key | Every write accepts it (CLAUDE.md §5). An operation in **ADR-015's scope** must declare it with `@RequestHeader(OpenApiConfig.IDEMPOTENCY_KEY)`, and it then stays required. No Sprint 1 write is in that scope (bookings, venue request actions, Late Confirm, attendance, tournaments, admin remediation, points/wallet/referrals), so all are optional today. |
| Error codes | Each `@ApiError` narrows `error.code` to the declared codes for that status, inside the shared `ErrorResponse` envelope (`meta`, `field` and `details` unchanged). |
| Shapes derived from FR text | The venue card (FR-DIS-03), marker (FR-DIS-10), venue details (FR-DIS-06, FR-DIS-11) and find-available result (FR-DIS-12) use the fields those rows list. The schedule slot uses exactly arch 09 §4.1's display states. The opening-hours preview carries `previewToken`, `affected[]` and `ownerPasswordRequired` (FR-VEN-23 steps 5–6; FR-VEN-24/25). |

## 3. Not resolved — owned by later tasks, and none of it blocks E7-11

| Item | Owner | Status in this contract |
|---|---|---|
| Error codes for generic failures (400/401/403/404/429). Arch 09 §1.5 defines none. | E7-12 | 401 is documented on every secured route with **no body schema**, rather than an invented or borrowed code. No generic 400/404/429 responses are documented yet. A 500 carries only a correlation ID (§1.4) and is not modelled. |
| The endpoint for the separate activation action, and the other marketplace status changes (INACTIVE ↔ ACTIVE ↔ SUSPENDED). | E7-22 / E7-23 | Not in arch 09 §5, so not added. |
| Venue account creation, resource creation, `GET /admin/venues/{id}`, `GET PATCH /admin/activities`, `PATCH /venue/resources/{id}` | E7-22 / E7-23 / E7-24 / E7-26 | Listed in arch 09 without request or response fields, or not listed at all. Not added. |
| `/venue/settings`, reviews, closures, maintenance, emergencies, tournaments, owner reviews | later sprint contracts | Not Sprint 1. |

No included endpoint depends on an unresolved product decision, so nothing had to be kept apart as a draft.

## Overlap with other tasks, to reconcile rather than redo

- **E7-12:** `ApiErrorCode`, `ErrorResponse` and `DataResponse` belong to E7-12 (error envelope). E7-12 adds the exception mapping, the correlation-ID filter and the generic error codes, and should keep these types.
- **E7-16:** `common/codes/*` enums belong to E7-16. Their spellings are already tested against the database; E7-16 adds the full set.
- **E7-14:** the Bearer scheme only documents the header. Real authentication, and the Swagger UI exposure policy, are E7-14. The mock checks only that a Bearer header is present; that is not authentication.
