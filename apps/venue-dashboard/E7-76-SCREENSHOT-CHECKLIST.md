# E7-76 English / Arabic screenshot checklist

Capture each pair at the same viewport and with the same API data. Keep stored venue, room, customer and reason text byte-identical between the two screenshots. Use the filenames below when attaching the images to the pull request.

| Built screen or state | English (LTR) | Arabic (RTL) | Review points |
|---|---|---|---|
| Sign in | `01-login-en.png` | `01-login-ar.png` | Form direction, labels, error placement |
| Overview | `02-overview-en.png` | `02-overview-ar.png` | Sidebar, KPI order, localized numbers and EGP, schedule |
| Pending Requests | `03-pending-en.png` | `03-pending-ar.png` | Countdown, customer text, action order, sound/push notices |
| Pending rejection form | `04-pending-reject-en.png` | `04-pending-reject-ar.png` | Textarea, validation, confirmation actions |
| Missed / Expired Requests | `05-missed-en.png` | `05-missed-ar.png` | Countdown, prices, Late Confirm and reject actions |
| Notifications | `06-notifications-en.png` | `06-notifications-ar.png` | Unread inline border, Cairo timestamp, action order |
| Today's Bookings | `07-bookings-en.png` | `07-bookings-ar.png` | Business date, time ranges, stored room/customer names |
| Booking detail | `08-booking-detail-en.png` | `08-booking-detail-ar.png` | Detail grid, EGP, contact and history |
| Booking action / Owner PIN state | `09-booking-action-en.png` | `09-booking-action-ar.png` | Confirmation action order and input direction |
| Manual Booking | `10-manual-booking-en.png` | `10-manual-booking-ar.png` | Form fields, radio controls, validation and success state |
| Rooms | `11-rooms-en.png` | `11-rooms-ar.png` | Resource cards, stored names and state labels |
| Resource Owner PIN modal | `12-resource-modal-en.png` | `12-resource-modal-ar.png` | Modal alignment, PIN input, buttons |
| Pricing | `13-pricing-en.png` | `13-pricing-ar.png` | Price inputs, EGP labels, override layout |
| Pricing Owner PIN modal | `14-pricing-modal-en.png` | `14-pricing-modal-ar.png` | Modal and error placement |
| Schedule | `15-schedule-en.png` | `15-schedule-ar.png` | Previous/next order, mirrored timeline, non-flipped clock/state marks |
| Booking History | `16-history-en.png` | `16-history-ar.png` | Filters, table flow, dates, prices and horizontal overflow |
| Settings — Venue profile | `17-profile-en.png` | `17-profile-ar.png` | Stored name/address unchanged, language select |
| Settings — Photos | `18-photos-en.png` | `18-photos-ar.png` | Image controls; photos and non-directional icons are not flipped |
| Settings — Opening Hours | `19-hours-en.png` | `19-hours-ar.png` | Weekday rows, time inputs, affected-booking dates |
| Opening Hours Owner PIN modal | `20-hours-modal-en.png` | `20-hours-modal-ar.png` | Modal, confirmation and errors |
| Loading, error and retry state | `21-error-en.png` | `21-error-ar.png` | Toast/alert direction and retry placement |

## Completion checks

- [ ] All 21 English screenshots are attached to the pull request.
- [ ] All 21 Arabic screenshots are attached to the pull request.
- [ ] Arabic screenshots show `dir="rtl"` on the document root.
- [ ] No stored free text changes between each English/Arabic pair.
- [ ] Directional navigation and timeline flow mirror; logos, photos, play/clock/chart/history marks do not flip.
- [ ] Counts, countdowns, Cairo dates/times and EGP amounts use the shared locale formatter.
- [ ] Modals, alerts, validation states, tables and horizontally scrollable content were reviewed.

Maintenance, Reviews and Tournaments are navigation placeholders in the current build, so they are not included as built screens in this checklist.
