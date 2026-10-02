# E7GZ PS — PRODUCT CHANGE REQUEST PCR-005
# Venue Photos from the Venue Dashboard

**Status:** APPROVED / INCORPORATED  
**Date:** 2 October 2026  
**Applies to:** Product Specification v5.0 §51.2, §20.3, §20.5; amends `FR-ADM-02` and the Owner Password list in `FRS §2.4`

## Change

Venue photos can be managed in two places:

1. **Platform Admin** (unchanged in substance, `FR-ADM-02`): uploads, reorders and removes venue photos during onboarding and on the venue page at any time.
2. **Venue Dashboard** (new): the venue adds, reorders and removes its own photos. **Each change needs the Owner Password** (add photo, reorder, remove), following the Owner Password body convention (arch 09 §4.3).

For both:

- Removing a photo **hides** it. The metadata row and the stored original are kept (`removed_at`), and the removal is audited. Nothing is hard-deleted.
- Photos are shown to customers on the venue card and Venue Details (`FR-DIS-03`, `FR-DIS-11`) in the venue's chosen order. The first photo is the venue-card image.
- Accepted files: JPEG, PNG or WebP, at most 5 MB each, at most 10 visible photos per venue. These limits are technical defaults held in configuration. Photos are uploaded only from the web (Platform Admin, Venue Dashboard); the upload asks the browser for these formats, so iPhone photos arrive as JPEG. Customers never upload photos.
- **Product Owner decisions, 3 Oct 2026:** after upload a background job makes a small (venue card) and a medium (Venue Details) copy without location or camera metadata (GPS/EXIF); customers see only these copies, the original is kept. Signed links: upload 10 minutes, viewing 1 hour. Uploads not confirmed within 24 hours are cleaned up by a nightly job (they were never shown). At launch photos are served straight from storage with signed links; the CDN comes with the deployment task (E7-101). A picture over 50 megapixels is refused at confirm; a photo whose copies cannot be made shows a placeholder and is logged.

## Rationale

Photos are the main thing a customer sees before booking, and venues change their rooms and setups after onboarding. Sending every photo change through the Platform Admin is slow. Photos are public marketplace content, so a change needs the same Owner Password as pricing and protected resource configuration, not just a staff session.

## Traceability

| Item | Change |
|---|---|
| `FR-ADM-02` | Platform Admin still manages photos at onboarding; the venue may also manage them later from the Dashboard |
| `FRS §2.4` | **Owner Password required** list gains: venue photo add, reorder and remove |
| API | arch 09 §4.2 `/venue/images…` (Owner Password on every change) and §5 `/admin/venues/{id}/images…`; new codes `VENUE_IMAGE_NOT_ACCEPTED`, `VENUE_IMAGE_LIMIT_REACHED`, `VENUE_IMAGE_NOT_UPLOADED` (arch 09 §1.5) |
| Database | new migration: `venue.venue_image.uploaded_at` and `removed_at` (+ `.dbm`, change report; Product Owner review) |
| Object storage | arch 12 §2 — removal hides the photo; originals retained |
| Design | the Venue Dashboard prototype has no photo screen; it is built in the existing Dashboard design (tokens + components), EN + AR |
| Build plan | E7-23 (storage, migration, admin endpoints), E7-24 (venue endpoints with Owner Password), E7-35 (Dashboard screen), E7-37 (Admin screen) |
