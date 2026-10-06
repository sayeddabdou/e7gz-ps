# Sprint 2 contract (E7-39)

Every endpoint below is a **stub**: the controller answers `501` until its owning task implements it, and `openapi.json` describes the intended success and error responses. Clients build against `pnpm mock` meanwhile. No business logic, persistence or migration is part of E7-39.

Sources: arch 09 §1, §3.3, §3.4, §4.1 · arch 08 §4–§5 · arch 10 §5.1–§5.2 · ADR-014, ADR-015 · FRS F-04…F-10, F-16 and the FR rows cited in each controller · the `CHECK` lists in `V1__baseline.sql` for every enum (`OpenApiProductionContractTest` checks them against the live database).

## Included (22 operations)

| Surface | Endpoint | Controller | Implemented by |
|---|---|---|---|
| Customer | `POST /customer/bookings` · `GET /customer/bookings/quote` | `booking/web/CustomerBookingController` | E7-46 |
| Customer | `GET /customer/bookings?bucket=` · `GET /customer/bookings/{id}` · `POST …/{id}/cancel` | `booking/web/CustomerBookingController` | E7-49 |
| Customer | `GET /customer/notifications` · `POST …/{id}/read` · `POST …/devices` · `POST …/devices/{id}/revoke` | `notification/web/CustomerNotificationController` | E7-43 |
| Venue | `GET /venue/requests/pending` · `/missed` · `POST /venue/bookings/{id}/accept` · `/reject` · `/reject-after-expiry` | `booking/web/VenueBookingController` | E7-47 |
| Venue | `POST /venue/bookings` (manual / walk-in) · `POST …/{id}/cancel-manual` | `booking/web/VenueBookingController` | E7-48 |
| Venue | `GET /venue/bookings` · `GET /venue/bookings/{id}` | `booking/web/VenueBookingController` | E7-49 |
| Venue | `GET /venue/stream` (SSE) · `GET /venue/push-subscriptions/vapid-public-key` · `POST /venue/push-subscriptions` · `POST …/{id}/revoke` | `notification/web/VenueStreamController`, `VenuePushSubscriptionController` | E7-44 |

Not in Sprint 2: Late Confirm, attendance and `cancel-confirmed` (E7-54, Sprint 3 contract), `/venue/overview`, `/venue/revenue`.

## 1. PO decisions (5 Oct 2026)

| Decision | In the contract |
|---|---|
| Push tokens and browser subscriptions can be switched off through the API. They are **revoked, never deleted** (arch 08 §4). | `POST /customer/notifications/devices/{id}/revoke` and `POST /venue/push-subscriptions/{id}/revoke` → 204. No `DELETE`. |
| Add every endpoint a Sprint 2 task needs, beyond the E7-39 step list. | Request lists, reject after expiry, venue bookings list and detail, notifications inbox, cancel manual booking. |
| The app gets the price from the server before booking. | `GET /customer/bookings/quote?resourceId&playMode&startAt&durationMinutes` → `{hourlyPrice, nominalBookedMinutes, venuePriceTotal, bookingFee, totalAmount}`. Not an availability check and not a hold; the created booking's own amounts are authoritative. Recorded in arch 09 §3.3. |
| The live feed uses the ordinary Bearer header. | The Dashboard reads `GET /venue/stream` with `fetch()` + `ReadableStream` (a browser `EventSource` cannot send headers). No token in any URL. |

## 2. Conventions (resolved from the documents)

| Topic | Convention and basis |
|---|---|
| Idempotency-Key | **Required** (declared with `@RequestHeader`) on the seven booking writes: create, customer cancel, accept, reject, reject after expiry, manual create, cancel manual (arch 09 §3.3/§4.1, ADR-015). Optional on the notification and push writes (CLAUDE.md §5). `Sprint2ContractTest` checks both. |
| Owner Password | None of these actions takes it (arch 09 §4.1 "no"). |
| Separate DTOs | Customer routes use only `Customer*` schemas, venue routes only `Venue*` (arch 09 §1.1); `Sprint2ContractTest` walks every reference. |
| Contact visibility | `CustomerBookingVenue.phone` and `VenueBookingCustomer.phone` are **optional and never null**: absent from the payload when the retention rule hides them (NFR-18, arch 10 §5.1). A manual booking's typed name/phone are in `walkIn` and exempt (FR-DIS-09). |
| Neutral Late Confirm | The venue booking history must never show `LATE_CONFIRM_FAILED_BOOKING_LIMIT` or `LATE_CONFIRM_FAILED_CUSTOMER_CONFLICT` (NFR-19, arch 10 §5.2). The venue DTO has no field for the customer's other commitments. |
| Bucket | `?bucket=upcoming|completed|cancelled` and `displayBucket` are lower case, as arch 09 §3.3 writes them (FR-BOK-19). |
| Timers | `venueResponseExpiresAt` is an instant, never a countdown (NFR-10); `venueResponsePaused` is true while an emergency pauses it (FR-BOK-32). |
| History | arch 09 §3.3's `{at, code}` is illustrative; the contract carries the `booking.booking_history` row as codes: `{at, kind, fromStatus, toStatus, terminalReason, eventType}`. |
| Manual booking | `startAt` absent = walk-in starting now (server time, FR-MAN-04). `playMode` required for PLAYSTATION only. |
| Reject reason | `reasonText` required, not blank, at most 200 characters (FR-BOK-17, `ck_booking_reject_reason_text`; the cap is a PO decision, 5 Oct 2026). |
| SSE | `event: change` + `data: {type, id, reason}`; `: heartbeat` every 15 s (arch 08 §5). `type` = `BOOKING` / `TOURNAMENT`. `reason` = `CREATED` (new booking request), `REQUESTED` (new tournament request), `CHANGED` (anything else). The Dashboard plays the alert on `CREATED` / `REQUESTED`. `pnpm mock` serves one literal event. |
| Push | Customer: `{token, platform}` (`notification.device_token`). Venue: `PushSubscription.toJSON()` = `{endpoint (https), keys: {p256dh, auth}}` + optional `userAgent` (`notification.venue_push_subscription`); the VAPID public key comes from `GET /venue/push-subscriptions/vapid-public-key`. |

## 3. PO decisions on the open questions (5 Oct 2026), recorded in arch 09 / arch 08

| # | Question | Decision | In the contract |
|---|---|---|---|
| Q1 | An action on a booking that is no longer in the needed state (e.g. the customer cancelled a second before the venue's Accept) | **New code `BOOKING_STATE_CHANGED` (409)**: refresh and look again | On customer cancel, venue accept, reject, reject after expiry and cancel manual |
| Q2 | Accept stopped by an emergency or owner review (FR-BOK-16) | **An error.** The booking is cancelled under the operational cause, and the venue gets the matching 422 | `VENUE_SELLING_BLOCK_ACTIVE`, `RESOURCE_SELLING_BLOCK_ACTIVE` or `RESOURCE_NOT_ELIGIBLE` |
| Q3 | Manual booking on an ONBOARDING venue (FR-MAN-02) | **`FORBIDDEN` (403)** | Described on `POST /venue/bookings` |
| Q4 | Online booking when the venue has no price for the activity (FR-BOK-11 item 20) | **`RESOURCE_NOT_ELIGIBLE` (422)** | Described on `POST /customer/bookings` |
| Q5 | Record the new routes and decisions in the docs | **Yes** | arch 09 §1.5, §3.3, §3.4, §4.1; arch 08 §5 |
| Q6 | The three SSE reasons `CREATED` / `REQUESTED` / `CHANGED` | **Kept** | `VenueStreamSignalReason` |
| Q7 | Length of the venue's reject reason | **At most 200 characters** | `VenueBookingRejectRequest.reasonText` |
