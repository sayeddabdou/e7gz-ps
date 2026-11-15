# E7GZ PS — PRODUCT CHANGE REQUEST PCR-013
# Privacy Policy and Terms at launch; recorded acceptance at sign-up

**Status:** APPROVED (the rules below) — Sayed (Product Owner), 15 November 2026. The two texts in `docs/01-product/legal/` are **DRAFT** until the Product Owner approves them.\
**Date:** 15 November 2026\
**Applies to:** Product Specification v5.0 (customer registration); `FR-ACC-01`; FRS §3.1, §3.2, §3.3

## Why

Nothing in the requirements asked for a Privacy Policy or Terms of Use. Both are needed before launch:

- Apple (App Store Review Guideline 5.1.1) requires a privacy-policy link in App Store Connect and inside the app. Google Play requires the same.
- Egypt's Personal Data Protection Law (Law 151 of 2020) expects that people are told what data is collected and why, and that their consent can be shown.

## Change

1. **Two documents — NEW.** E7GZ PS has a Privacy Policy and Terms of Use. Their text lives in `docs/01-product/legal/`. Claude drafts them, the Product Owner reviews and approves them, and a lawyer should check them before launch (Product Owner decision, 15 November 2026).
2. **Public pages — NEW.** Both documents are published at two public web addresses that the Product Owner provides. The apps link to these addresses; they do not carry a copy of the text.
3. **Agreement at sign-up — NEW.** Customer registration (`FR-ACC-01`) shows "I agree to the Terms of Use and the Privacy Policy" with a link to each. A customer cannot register without agreeing.
4. **Recorded acceptance — NEW.** The server stores, for each customer, which version of the texts they accepted and when. The record is written once at registration and is never changed. A registration without it, or with a version that is not the current one, is refused.
5. **Version label — NEW.** Each published pair of texts has one version label, a date such as `2026-11-15`. The first approved texts get the date of their approval. When a text changes, the Product Owner sets a new label; whether existing customers must agree again is decided then, in a new PCR.
6. **Existing accounts — NEW.** Accounts created before this change have no recorded acceptance. Nothing is invented for them. (At launch there are none: the product is not live yet.)
7. **Links everywhere — NEW.** The Customer App shows both links in Profile. The Venue Dashboard (English and Arabic) and the Platform Admin show both links in the account menu or the footer.
8. **Venue accounts — UNCHANGED.** Venue accounts are created by the Platform Admin under an agreement between E7GZ and the venue; there is no sign-up screen and no in-app acceptance for them.
9. **Account deletion — UNCHANGED.** PCR-002 stays as it is. The Privacy Policy describes it.

## Open items for the Product Owner

| Item | Needed before |
|---|---|
| Approve the two texts (and ideally a lawyer's check) | E7-122 and E7-124 start |
| The legal name and address of the company behind E7GZ PS, and a contact email for privacy requests | The texts can be approved |
| The two public web addresses | E7-122 is finished |
| Arabic versions of the two texts (the Venue Dashboard is bilingual; customers are in Egypt) | Launch |

## Traceability

| Item | Change |
|---|---|
| `FR-ACC-01` | Registration also needs the customer's agreement; the accepted version is stored |
| FRS §3.1, §3.2, §3.3 | Each app shows the two links |
| PCR-002 | Unchanged |
| API | `POST /customer/auth/register` gains the accepted version (required); `GET /admin/users/{userId}` shows it |
| Database | A new migration stores the accepted version and instant per customer (E7-124) |
| Build plan | E7-124 (server), E7-122 (apps), E7-109 (App Privacy labels), E7-110 (launch checklist) |
