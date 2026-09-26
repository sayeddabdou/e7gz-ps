# UI/UX prototypes

Three interactive prototypes plus one addendum. Open any `.dc.html` in a browser (it needs the `support.js` next to it).

| Folder / file | Surface | Use it for |
|---|---|---|
| `E7GZ PS Customer App Prototype/` | Customer Mobile App | screen inventory, the booking flow, My Bookings buckets, Late Confirm copy placement |
| `E7GZ PS Venue Dashboard/` | Venue Dashboard | the module set, the availability schedule, the design tokens, and the RTL handling already in the markup |
| `E7GZ PS Platform Admin/` | Platform Admin | the 20 admin modules and their table layouts |
| `E7GZ PS — Compliance UI Addendum — Account Deletion & Review Reporting.md` | Customer App + Platform Admin | Delete Account, Report Review, Admin Reports queue and deletion completion (PCR-002, PCR-003) — **authoritative over the prototypes for these flows** |

## What these are, and what they are not

They are the **visual and interaction reference**: layout, component inventory, screen flow, tone, spacing, colour.

They are **not** behavioural authority. Where a prototype shows a state, a rule or a message that the FRS does not define, the **FRS wins** and the prototype is out of date. The prototypes carry mock data and were built before the functional baseline was frozen.

Never take the following from a prototype: status names, reason codes, timer values, eligibility rules, or the exact wording of any error. All of those are defined in `docs/01-product/` and surfaced as codes by the backend (`docs/02-architecture/09 — API Architecture & Contract Map.md`).

## Decided after the prototypes (Product Owner, 26 Sep 2026)

- **Venue Dashboard alerts:** every new booking request and tournament request shows live, plays an **audible alert**, and is also sent as a browser (Web Push) notification when the Dashboard is closed (`02-architecture/01 §14` A6).
- **Platform Admin reports** "terminal-reason distribution" and "attendance mix and check-in capture rate per venue" (`FR-AUD-11`, `FR-AUD-12`) are in the launch scope and need screens in the Admin reporting area (A5).
- **Venue marketplace status:** Admin can move a venue out of ONBOARDING to ACTIVE, INACTIVE or SUSPENDED and switch freely between those three; the UI never offers "back to ONBOARDING" (A9).

## What to extract first

Pull the design tokens — colours, typography scale, spacing scale, border radii, component shapes — out of these files into a shared `packages/tokens` used by all three apps, before any screen is built, so the three surfaces stay visually consistent without anyone maintaining it by hand.
