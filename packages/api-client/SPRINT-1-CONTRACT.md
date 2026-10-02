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
| The endpoint for the separate activation action, and the other marketplace status changes (INACTIVE ↔ ACTIVE ↔ SUSPENDED). | E7-22 / E7-23 | **Added by E7-22**, see §4. |
| Venue account creation, resource creation, `GET /admin/venues/{id}`, `GET PATCH /admin/activities`, `PATCH /venue/resources/{id}` | E7-22 / E7-23 / E7-24 / E7-26 | **E7-22** added the first three (§4); activities and resource PATCH are still open (§4.3). |
| `/venue/settings`, reviews, closures, maintenance, emergencies, tournaments, owner reviews | later sprint contracts | Not Sprint 1. |

No included endpoint depends on an unresolved product decision, so nothing had to be kept apart as a draft.

## Overlap with other tasks, to reconcile rather than redo

- **E7-12:** `ApiErrorCode`, `ErrorResponse` and `DataResponse` belong to E7-12 (error envelope). E7-12 adds the exception mapping, the correlation-ID filter and the generic error codes, and should keep these types.
- **E7-16:** `common/codes/*` enums belong to E7-16. Their spellings are already tested against the database; E7-16 adds the full set.
- **E7-14:** the Bearer scheme only documents the header. Real authentication, and the Swagger UI exposure policy, are E7-14. The mock checks only that a Bearer header is present; that is not authentication.

## 4. What E7-22 added (2 Oct 2026)

### 4.1 Web session for the Venue Dashboard and Platform Admin (Sprint 0 review S-4, PO decision 2 Oct 2026)

**This changes arch 09 §2 for VENUE and ADMIN.** The PO records it in arch 09. The Customer app does not change.

| Operation | Before | Now |
|---|---|---|
| `POST /{venue\|admin}/auth/login` | body `{accessToken, refreshToken}` | body `{accessToken}` (`VenueAccessToken` / `AdminAccessToken`), and a `Set-Cookie` header |
| `POST /{venue\|admin}/auth/refresh` | body `{refreshToken}` | no body. Reads the cookie and answers like login. Any refusal is 401 and clears the cookie. |
| `POST /{venue\|admin}/auth/logout` | body `{refreshToken}` | no body. Revokes the cookie's session, clears the cookie, 204. |

- Cookie: `e7gz_venue_refresh` / `e7gz_admin_refresh`, `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/{venue|admin}/auth; Max-Age=` 7 days / 12 hours.
- CSRF: refresh and logout need **both** an `Origin` from the allowed list **and** `X-Requested-With: e7gz`. Either one missing or wrong is `403 FORBIDDEN`, and nothing changes.
- CORS: only on `/api/v1/venue/**` and `/api/v1/admin/**`, only for `E7GZ_CORS_ALLOWED_ORIGINS` (exact origins; the app refuses to start on a wildcard or a path), with credentials. Any other origin is `403 FORBIDDEN`. Customer routes have no CORS. Local dev: `E7GZ_CORS_ALLOWED_ORIGINS=http://localhost:5173,http://localhost:5174`.
- `SameSite=Strict` means the Dashboard/Admin and the API must share a registrable domain (e.g. `venue.e7gz.ps` + `api.e7gz.ps`; `localhost` on any port works). Deployment (E7-101) must keep that.
- **Clients lane (E7-21):** call login/refresh/logout with `credentials: 'include'`; send `X-Requested-With: e7gz` on refresh and logout; keep the access token in memory only; on page load call refresh to restore the session.

Also: a last security chain denies every path outside `/api/**` and the docs paths (S-15). The container's error dispatch still passes.

### 4.2 Endpoints added (PO decisions 2 Oct 2026)

| Endpoint | Basis | Implemented by |
|---|---|---|
| `POST /admin/venues/{venueId}/activate` · `/deactivate` · `/suspend` `{reason}` | FR-VEN-07; arch 03 §5 `setMarketplaceStatus(…, reason)`. One verb per target status (PO). The DB transition guard refuses illegal moves. | E7-23 |
| `GET /admin/venues/{venueId}` → `{venue, venueAccount \| null}` | arch 09 §5 `GET /admin/venues` (derived) | E7-23 |
| `POST /admin/venues/{venueId}/venue-account` `{loginIdentifier, password, initialOwnerPassword, languagePreference?}` → 201 `AdminVenueAccount` | FR-ADM-02, FR-VEN-01 (PO: admin creates it). The field is `initialOwnerPassword`, not `ownerPassword`, because it sets the password rather than authorising an action (arch 09 §4.3 reserves `ownerPassword` for that). | E7-24 |
| `POST /venue/resources` `{activity, consoleType?, ownerPassword}` and `GET POST /admin/venues/{venueId}/resources` `{activity, consoleType?}` | FR-ADM-02 (admin onboarding) + Dashboard (PO: both). No `name`: FR-VEN-41 fixes names, so the server assigns Room n / Pool Table n / Table n. | E7-26 |

### 4.3 PO decisions on the open points (2 Oct 2026), recorded in arch 09

| # | Question | Decision | In the contract |
|---|---|---|---|
| Q1 | `PATCH /venue/resources/{id}` | **Dropped.** Names are fixed (FR-VEN-41), the activity is fixed, and console and state have their own actions. | Not in arch 09 §4.2 any more. |
| Q2 | `GET PATCH /admin/activities` | **View only.** | `GET /admin/activities` → `[{activity, venueCount}]` (E7-26). No PATCH. |
| Q3 | Owner Password to add a room from the Dashboard | **Yes.** | `POST /venue/resources` needs `ownerPassword`, answers 423. The admin route does not. |
| Q4 | Missing error codes | **Three new codes** (arch 09 §1.5) | `VENUE_LOGIN_IDENTIFIER_TAKEN` and `VENUE_ACCOUNT_ALREADY_EXISTS` (both already mapped from their DB constraints), and `MARKETPLACE_STATUS_CHANGE_NOT_ALLOWED`. All 422. |

The implementing tasks (E7-23, E7-24, E7-26, E7-35, E7-37) carry these as "Carry-forward from E7-22" lines in BUILD-PLAN and `tools/ai-context/tasks.json`.

## 5. Added after E7-22 (PO decisions, 2 Oct 2026)

### 5.1 Venue photos (PCR-005)

Not in `openapi.json` yet: E7-23 adds the admin routes and E7-24 the venue routes, each with its stubs first so the Clients lane can build against `pnpm mock`.

| Endpoint | Owner Password | Implemented by |
|---|---|---|
| `GET POST /admin/venues/{venueId}/images` · `POST …/images/{imageId}/confirm` · `PUT …/images/order` `{imageIds}` · `POST …/images/{imageId}/remove` | no | E7-23 |
| `GET POST /venue/images` · `POST /venue/images/{imageId}/confirm` · `PUT /venue/images/order` · `POST /venue/images/{imageId}/remove` | yes on confirm, order and remove | E7-24 |

Upload is two steps (arch 12 §2): `POST …/images` `{contentType, sizeBytes}` → `{image, uploadUrl, uploadExpiresAt}`; the client PUTs the bytes to `uploadUrl`; `confirm` makes the photo visible. Remove hides the photo (`removed_at`), never hard-deletes it. Limits: JPEG/PNG/WebP, 5 MB, 10 visible photos. New codes (arch 09 §1.5, all 422): `VENUE_IMAGE_NOT_ACCEPTED`, `VENUE_IMAGE_LIMIT_REACHED`, `VENUE_IMAGE_NOT_UPLOADED`. Admin and venue views return each photo with a short-lived signed GET URL.

### 5.2 Venue location input

No API change: venue create/edit still sends `location: {lat, lng}`. The Platform Admin form uses a map picker plus a coordinate paste box (docs/03-design/README.md, "Decided after the prototypes, 2 Oct 2026").

### 5.3 Areas, venues and marketplace status (E7-23, PO decisions 2 Oct 2026)

- While a venue is ONBOARDING, `POST …/onboarding/complete` (→ INACTIVE) is its only status action; `activate`, `deactivate` and `suspend` answer `MARKETPLACE_STATUS_CHANGE_NOT_ALLOWED` until then. Moving to the status the venue already has is also `MARKETPLACE_STATUS_CHANGE_NOT_ALLOWED`.
- `PATCH /admin/venues/{id}` with `activities` replaces the venue's list (at least one). Removing an activity that rooms, prices or availability still use is the new `VENUE_ACTIVITY_IN_USE` (422).
- An unknown `areaId` in a venue body is `VALIDATION_FAILED` with `error.field = areaId`; an inactive one is `AREA_INACTIVE`.
- Area `activate` / `deactivate` on an area already in that state changes nothing (200, no audit row).
- Audited: venue status changes (reason, previous and new status) and area activate/deactivate. Area and venue create/edit are not audited.
- `PATCH /admin/areas/{id}` and `PATCH /admin/venues/{id}`: a field left out is unchanged; a text field that is sent must not be blank (`VALIDATION_FAILED`, `error.field` = that field). An area's optional values are cleared with `removeNameAr: true` / `removeCentroid: true`; sending a value together with its remove flag is `VALIDATION_FAILED` on the flag.
- Area names are unique (trimmed, case-insensitive): create or rename to a taken name is `AREA_NAME_TAKEN` (422). Area name / Arabic name and venue name / address are at most 200 characters.
- Venue `phone` accepts an Egyptian mobile (010/011/012/015) or landline written with `0`, `20`, `+20` or `0020` (spaces, dashes and brackets ignored) and is stored and returned as E.164 (`+20…`); anything else is `VALIDATION_FAILED` on `phone`.
- `GET /admin/venues` is ordered newest first (creation time, then id), so editing a venue never moves it between cursor pages.

### 5.4 PO decisions, 3 Oct 2026

- `Idempotency-Key` is required only on booking-, commitment- and money/points-moving writes (arch 09 §1.2); admin area and venue writes do not take it.
- The admin may edit an ACTIVE venue's address, pin or area while bookings exist; customers see the new details, no notification at launch.
- Venue names need not be unique (branches of a chain); area names are unique.
- Venue photos: signed view links last 1 hour, upload links 10 minutes; customers get resized copies without GPS/EXIF (see PCR-005).
- Renaming an area changes the name everywhere, including on old bookings (bookings keep the area id, not a copy of its name). PO decision 3 Oct 2026.

### 5.5 Venue photo routes (contract only, E7-23 PR 3 early contract)

Admin `/admin/venues/{id}/images…` work (E7-23 PR 3). Venue `/venue/images…` answer 501 until E7-24; build against `pnpm mock` meanwhile.

- Upload is two steps (arch 12 §2): `POST …/images {contentType, sizeBytes}` → `{image, uploadUrl, uploadExpiresAt}`; `PUT` the file bytes to `uploadUrl` with the same `Content-Type` header (no `Authorization` header); then `POST …/images/{id}/confirm`. Only a confirmed photo is listed or shown.
- Venue Dashboard: `confirm`, `PUT …/images/order {imageIds}` and `POST …/images/{id}/remove` carry `ownerPassword` in the body (arch 09 §4.3); the admin routes do not.
- `order` lists every visible photo exactly once; the first is the venue-card photo. A confirmed photo goes last.
- `remove` hides the photo (204); it is never hard-deleted.
- The copies are made by the worker within about a minute of confirm; a PNG copy stays PNG, JPEG and WebP copies are JPEG (PO decision 3 Oct 2026).
- `smallUrl` (venue card) and `mediumUrl` (Venue Details) are signed links valid until `urlsExpireAt` (1 hour); both are `null` until the resized copies are made, so show a placeholder and reload the list later. Never cache the links past `urlsExpireAt`.
- Errors: `VENUE_IMAGE_NOT_ACCEPTED` (`error.field` = `contentType` or `sizeBytes`), `VENUE_IMAGE_LIMIT_REACHED` (start or confirm), `VENUE_IMAGE_NOT_UPLOADED` (confirm), `OWNER_PASSWORD_REQUIRED` / `OWNER_PASSWORD_INVALID` (venue writes), `NOT_FOUND` for an unknown or removed photo.
