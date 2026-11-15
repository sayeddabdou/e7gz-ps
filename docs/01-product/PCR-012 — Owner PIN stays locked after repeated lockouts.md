# E7GZ PS — PRODUCT CHANGE REQUEST PCR-012
# Owner PIN stays locked after repeated lockouts

**Status:** APPROVED — Sayed (Product Owner), 15 November 2026.\
**Date:** 15 November 2026\
**Applies to:** PCR-006 (Owner Password as a 4-digit PIN); `NFR-16`; architecture 10 §3 and §7 (Owner Password attempts: 5 per 15 minutes, then a 15-minute lockout)

## Why

PCR-006 made the Owner Password a PIN of 4 digits, so there are only 10,000 possible PINs. The existing limit (5 wrong PINs, then a 15-minute lockout) opens again by itself, forever. Someone with the Venue Dashboard open, for example a staff member on the shared venue login, can try about 480 PINs a day and find the PIN in about 10 days on average. The PIN protects prices, opening hours, cancelling confirmed bookings and other owner-only actions.

## Change

1. **Short lockout — UNCHANGED.** 5 wrong PINs within 15 minutes lock the Owner PIN of that venue account for 15 minutes (`NFR-16`).
2. **Long lock — NEW.** When the Owner PIN of a venue account is locked 3 times within any 24 hours, it stays locked: every action that needs the Owner PIN is refused for that venue, also with the right PIN and also after the 15 minutes have passed.
3. **Only the Platform Admin ends it — NEW.** The long lock ends when the Platform Admin resets the Owner PIN (`FR-ADM-03`). Nothing else ends it: not time, not a new login, not the venue's own PIN change (which itself needs the PIN).
4. **The Platform Admin is told — NEW.** When a long lock starts, a Platform Admin alert is raised and the event is audited.
5. **What the venue sees — NEW.** The Venue Dashboard shows that the Owner PIN is locked and that the venue must contact E7GZ support. The answer is the same for a right and a wrong PIN, so it reveals nothing about the PIN.
6. **Everything else — UNCHANGED.** Actions that need no Owner PIN keep working during a lock (taking and answering bookings, check-in, venue photos per PCR-006). The PIN is still required per protected action, never cached, and never stored or logged in plain text.

## Numbers

| Rule | Value | Source |
|---|---|---|
| Wrong PINs before a short lockout | 5 within 15 minutes | `NFR-16`, unchanged |
| Short lockout | 15 minutes | `NFR-16`, unchanged |
| Short lockouts before the long lock | **3 within 24 hours** | Product Owner, 15 November 2026 |
| Long lock ends | Platform Admin resets the Owner PIN | Product Owner, 15 November 2026 |

These are fixed Product rules, not configuration (architecture 01 §14 A8).

With this rule a guesser gets at most 15 tries before the PIN is frozen and the Platform Admin is alerted.

## Traceability

| Item | Change |
|---|---|
| PCR-006 | Amended: the long lock is added to the Owner PIN rules |
| `NFR-16`, architecture 10 §3 and §7 | The 5-per-15-minutes limit stays; the long lock is added after it |
| `FR-ADM-03` | Unchanged; the existing reset also ends the long lock |
| API | No new route. A locked PIN answers the existing Owner Password refusal; the Dashboard needs to tell the long lock from the short one (decided in E7-121 with the Clients lane) |
| Database | Possibly a counter or a lock marker on the venue account: a new migration if needed (E7-121) |
| Build plan | E7-121 (backend); the Dashboard text and the Admin alert view follow in the client tasks |
