# E7GZ PS — PRODUCT CHANGE REQUEST PCR-014
# A Customer blocks a reviewer

**Status:** APPROVED — Sayed (Product Owner), 16 November 2026.\
**Date:** 16 November 2026\
**Applies to:** Product Specification v5.0 §49 (Reviews); adds to `PCR-003` (Customer Reporting of Reviews); new requirement `FR-REV-12`

## Why

Apple (App Store Review Guideline 1.2) requires that an app showing content written by its users lets a user report that content **and block the user who wrote it**. Reporting exists (`PCR-003`). Blocking does not, in any requirement or task.

## Change

1. **Block — NEW.** A signed-in Customer can block the author of a public Review, from that Review in the Customer Mobile App.
2. **What the Customer then sees — NEW.** The Customer no longer sees any Review written by the blocked User, on any Venue.
3. **Nobody else is affected — NEW.** A block changes nothing for anyone else: not the Review's status, not the Venue's rating, not what other Customers or the Venue see, and not moderation. It is not a report.
4. **The author is never told — NEW.** No notification, and nothing in the author's own view shows a block.
5. **Unblock — NEW.** Profile has a list of blocked users; the Customer can unblock one, and that User's Reviews are shown again.
6. **Reporting — UNCHANGED.** `PCR-003` stays as it is. A Customer may report a Review, block its author, or both.
7. **Venues and the Platform Admin — UNCHANGED.** A Venue cannot block anyone. The Platform Admin's moderation is unchanged; the Platform Admin does not need to see who blocked whom.
8. **Account deletion.** When either account is deleted (`PCR-002`), the block has no further effect.

## Traceability

| Item | Change |
|---|---|
| `FR-REV-12` (new) | A Customer can block and unblock the author of a Review; a blocked author's Reviews are hidden from that Customer only |
| `PCR-003`, `FR-REV-11` | Unchanged |
| Spec §49 | Review statuses, the rating and moderation are unchanged |
| API | Customer routes to block a Review's author, to unblock, and to list my blocked users; every Customer read of Reviews leaves out Reviews by users the reader blocked |
| Database | A new migration: one table (the Customer, the blocked User, the instant) |
| Build plan | E7-98 (contract), E7-102 (backend), E7-106 (Customer App), E7-109 (App Review notes) |
