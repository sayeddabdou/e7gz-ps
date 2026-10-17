# E7GZ PS — PRODUCT CHANGE REQUEST PCR-008
# Late Confirm schedule conflict as an immediate error, not a notification

**Status:** APPROVED — F1 — Approved by Amr under delegated PO authority; reviewed by Sayed (Product Owner), 13 October 2026.\
**Date:** 13 October 2026\
**Applies to:** Product Specification v5.0 §52.2 (the item "Customer schedule-conflict failure") and §25.12; amends `FR-NTF-02` for that one item only

## Change

1. **A customer schedule conflict at Late Confirm Accept is reported synchronously — REQUIRED.** The Accept fails with `409 LATE_CONFIRM_CUSTOMER_CONFLICT`, the Booking Event `LATE_CONFIRM_FAILED_CUSTOMER_CONFLICT` is recorded and the booking stays AWAITING_CUSTOMER_CONFIRMATION until its own expiry (`FR-LCF-15`, `FR-LCF-16`, unchanged). The Customer App shows a clear, immediate error on the Accept action explaining that the booking overlaps another active booking or commitment of the customer (PENDING, CONFIRMED or ACTIVE). This is the "appropriate message" of §25.12.
2. **No separate notification for it — NOT REQUIRED.** "Customer schedule-conflict failure" is withdrawn from the §52.2 In-App + Push catalogue: no push and no persistent in-app notification is sent, and the notification code `LATE_CONFIRM_SCHEDULE_CONFLICT` is never produced. The customer is already in the app, on the Accept action, when the conflict happens.
3. **The five canonical Late Confirm integration events — UNCHANGED.** `LateConfirmInitiated`, `LateConfirmAccepted`, `LateConfirmDeclined`, `LateConfirmTimedOut` and `LateConfirmWarningDue` (arch 08 §3.2; E7-58 PO decision D2, 12 Oct 2026). A conflicting Accept publishes no integration event, and none is added for it. No command calls `Notifications.notify` for it.

Every other §52.2 and §52.5 Late Confirm notification stays as it is. The six codes E7-55's notification consumer maps from the five events are unchanged: `LATE_CONFIRMATION_RECEIVED` (LateConfirmInitiated), `LATE_CONFIRMATION_EXPIRING` (LateConfirmWarningDue), `CUSTOMER_CONFIRMATION_EXPIRED` (LateConfirmTimedOut), `VENUE_LATE_CONFIRM_ACCEPTED` (LateConfirmAccepted), `VENUE_LATE_CONFIRM_DECLINED` (LateConfirmDeclined) and `VENUE_CUSTOMER_CONFIRMATION_EXPIRED` (LateConfirmTimedOut). `LATE_CONFIRMATION_PAUSED` remains E7-83's.

## Traceability

| Item | Change |
|---|---|
| Spec v5.0 §52.2 / `FR-NTF-02` | "Customer schedule-conflict failure" is no longer an In-App + Push event; the rest of the catalogue is unchanged; marked "amended by PCR-008" |
| Spec v5.0 §25.12 / `FR-LCF-16` | Unchanged; the "appropriate message" is the synchronous Accept error of item 1 |
| API | Unchanged: `POST /customer/bookings/{bookingId}/late-confirm/accept` already declares 409 `LATE_CONFIRM_CUSTOMER_CONFLICT` (arch 09 §1.5, Sprint 3 contract E7-54) |
| Database | Unchanged. The notification catalogue row `LATE_CONFIRM_SCHEDULE_CONFLICT` seeded by `V1__baseline.sql` stays (`V1__baseline.sql` is never edited); it is simply never produced. Removing the row would need its own migration and is not required |
| Customer App | E7-62 (Late Confirm screen) maps `LATE_CONFIRM_CUSTOMER_CONFLICT` on Accept to a clear English message in the i18n resources, keeps the Late Confirmation AWAITING with Decline available until the stored deadline, and creates no notification |
| Build plan | E7-58 (done: 409 and the Booking Event); E7-55 (maps the six codes above; no consumer for `LATE_CONFIRM_SCHEDULE_CONFLICT`); E7-62 (the error message) |
