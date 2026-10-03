# E7GZ PS — PRODUCT CHANGE REQUEST PCR-006
# Owner Password as a 4-digit PIN; Venue Photos without it

**Status:** APPROVED / INCORPORATED  
**Date:** 3 October 2026  
**Applies to:** Product Specification v5.0 §10.2, §54.3; amends `FR-VEN-02`…`FR-VEN-05`, `FR-VEN-46`, `FR-ADM-03` (format of the Owner Password only) and PCR-005 (the Owner Password list in `FRS §2.4`)

## Change

1. **The Owner Password is a PIN of exactly 4 digits** (0–9). This applies wherever it is set: the first Owner Password when the Platform Admin creates the venue account (`FR-ADM-02`), the venue's own change (`FR-VEN-46`) and the Platform Admin reset (`FR-ADM-03`). Anything else is refused as a validation error on that field. Everything else about the Owner Password stays as it is: it is required per protected action with no unlock or session mode (`FR-VEN-02`), it is stored only as an Argon2id hash, both successful and failed attempts are audited (`FR-VEN-05`), and attempts are rate-limited per venue account (5 wrong in 15 minutes, then a 15-minute lockout, arch 10 §7). The name "Owner Password" is kept in the requirements, the API and the database; the screens may call it the Owner PIN.
2. **Venue photo add, reorder and remove no longer need the Owner Password.** PCR-005 had added them to the `FRS §2.4` "Owner Password required" list; they are removed from it again. The other PCR-005 rules are unchanged: removing a photo hides it and is audited, nothing is hard-deleted, each change is audited, and a venue can only see and change its own photos.

The venue login password is not affected: it keeps the password rule (at least 8 characters with a letter and a digit; Product Owner decision, 3 October 2026, E7-24).

## Rationale

The Owner Password is typed at the counter for each protected action, often on a tablet; a short PIN is quicker to enter and easier for the owner to share deliberately. The per-venue lockout keeps guessing slow. Venue photos are low-risk content changes that staff make often, so asking for the Owner Password on every photo change cost more than it protected; the audit trail still records who changed what.

## Traceability

| Item | Change |
|---|---|
| `FR-VEN-02`…`FR-VEN-05`, `FR-VEN-46`, `FR-ADM-03` | The Owner Password is a 4-digit PIN; behaviour, audit and rate limit unchanged |
| `FRS §2.4` (as amended by PCR-005) | **Owner Password required** list: venue photo add, reorder and remove are removed; they are not protected |
| API | `initialOwnerPassword`, the change's `next` and the admin Owner Password reset accept exactly 4 digits (`VALIDATION_FAILED` on the field otherwise). `/venue/images/{id}/confirm` and `/remove` take no body, `PUT /venue/images/order` takes `{imageIds}` only (arch 09 §4.2) |
| Database | No change: `iam.venue_owner_secret.owner_password_hash` already holds only an Argon2id hash |
| Security | arch 10 §3 unchanged in mechanism; with 10,000 possible PINs the 5-in-15-minutes lockout is what keeps guessing slow (Product Owner decision, 3 October 2026: keep the lockout as it is) |
| Design | Venue Dashboard: the Owner Password prompt takes 4 digits; photo add, reorder and remove show no prompt |
| Build plan | E7-24 (backend), E7-35 follow-up (Dashboard photo screen and PIN prompt), E7-37 (admin create account and reset forms) |
