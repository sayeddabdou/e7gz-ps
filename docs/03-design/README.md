# UI/UX prototypes

Three interactive prototypes, as zips and extracted. Open any `.dc.html` in a browser.

| Folder | Surface | Use it for |
|---|---|---|
| `E7GZ PS Customer App Prototype/` | Customer Mobile App | screen inventory, the booking flow, My Bookings buckets, Late Confirm copy placement |
| `Venue Dashboard Design System (1)/` | Venue Dashboard | the module set, the availability schedule, the design tokens, and the RTL handling already in the markup |
| `E7GZ PS Platform Admin/` | Platform Admin | the 20 admin modules and their table layouts |

## What these are, and what they are not

They are the **visual and interaction reference**: layout, component inventory, screen flow, tone, spacing, colour.

They are **not** behavioural authority. Where a prototype shows a state, a rule or a message that the FRS does not define, the **FRS wins** and the prototype is out of date. The prototypes carry mock data and were built before the functional baseline was frozen.

Specifically, never take the following from a prototype: status names, reason codes, timer values, eligibility rules, or the exact wording of any error. All of those are defined in `docs/01-product/` and surfaced as codes by the backend.

## What to extract first

Pull the design tokens — colours, typography scale, spacing scale, border radii, component shapes — out of these files into a shared `packages/tokens` used by all three apps. Do that in `WP-00b`, before any screen is built, so the three surfaces stay visually consistent without anyone maintaining it by hand.
