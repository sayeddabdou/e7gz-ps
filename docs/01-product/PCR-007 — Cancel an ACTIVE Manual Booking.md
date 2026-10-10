# E7GZ PS — PRODUCT CHANGE REQUEST PCR-007
# Cancel an ACTIVE Manual Booking

**Status:** APPROVED\
**Date:** 10 October 2026\
**Applies to:** Product Specification v5.0 §30.8; amends FRS F-16 item 7, `FR-MAN-08` and arch 06 §1.4 (Cancellation row)

## Change

1. **Venue staff may cancel a Manual Booking that is CONFIRMED or ACTIVE**, without the Owner Password, reason `VENUE_CANCELLED_MANUAL_BOOKING`. Before this change the ordinary venue cancellation was a before-Start action on a CONFIRMED Manual Booking only.
2. **On an ACTIVE Manual Booking:**
   - status ACTIVE → CANCELLED;
   - the room is free from the cancel instant: the reservation is released at the command instant;
   - the scheduled `BOOKING_COMPLETE` job is cancelled;
   - attendance stays NOT_APPLICABLE;
   - fee treatment is NOT_APPLICABLE;
   - no Trust or points effect.

Online Bookings are not affected: Owner Password protection for Booking cancellation still applies to CONFIRMED Online Customer Bookings only (§30.8).

## Traceability

| Item | Change |
|---|---|
| FRS F-16 item 7 | The venue cancellation covers CONFIRMED and ACTIVE Manual Bookings; marked "amended by PCR-007" |
| `FR-MAN-08` | CONFIRMED or ACTIVE, no Owner Password, `VENUE_CANCELLED_MANUAL_BOOKING`, fee treatment NOT_APPLICABLE; marked "amended by PCR-007" |
| arch 06 §1.4 | Cancellation row: from CONFIRMED or ACTIVE; marked "amended by PCR-007" |
| Database | The booking state guard (`booking.guard_booking_transition`) refuses ACTIVE → CANCELLED with `VENUE_CANCELLED_MANUAL_BOOKING`. A new migration `V<yyyyMMddHHmm>__manual_booking_active_cancel.sql` allows it only for `source = 'MANUAL'` and actor VENUE, under every migration safety rule of CLAUDE.md §5. `V1__baseline.sql` is not edited |
| API | `POST /venue/bookings/{bookingId}/cancel-manual`: allowed on CONFIRMED (before or after startAt) or ACTIVE; on an ONLINE booking, or one already CANCELLED or COMPLETED, 409 `BOOKING_STATE_CHANGED` (Product Owner decision, 10 Oct 2026, E7-48) |
| Build plan | E7-48 (this change, the migration and cancel-manual); E7-61 (`BOOKING_COMPLETE` handler stays cancellation-safe for MANUAL) |
