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
5. **Version label — NEW.** Each published pair of texts has one version label, a date such as `2026-11-15`. The first approved texts get the date of their approval. The current label and the two public web addresses are deployment settings; development uses a placeholder label. When a text changes, the Product Owner sets a new label; whether existing customers must agree again is decided then, in a new PCR.
6. **Existing accounts — NEW.** Accounts created before this change have no recorded acceptance. Nothing is invented for them. (At launch there are none: the product is not live yet.)
7. **Links everywhere — NEW.** The Customer App shows both links in Profile. The Venue Dashboard (English and Arabic) and the Platform Admin show both links in the account menu or the footer.
8. **Venue accounts — UNCHANGED.** Venue accounts are created by the Platform Admin under an agreement between E7GZ and the venue; there is no sign-up screen and no in-app acceptance for them.
9. **Account deletion — UNCHANGED.** PCR-002 stays as it is. The Privacy Policy describes it.

## Product Owner decisions, 21 November 2026 (E7-125)

1. **Public web addresses.** The two pages are `https://<domain>/privacy` and `https://<domain>/terms`. The domain is not bought yet; it is bought before deployment (E7-101). Until then `E7GZ_LEGAL_TERMS_URL` and `E7GZ_LEGAL_PRIVACY_URL` stay empty in `.env.example`, as today.
2. **Arabic.** Each page carries the English text and the Arabic text on the same page, so each text keeps one address and the apps need no change.
3. **Version label.** The date the Product Owner approves the final texts, e.g. `2026-11-25` (`E7GZ_LEGAL_TERMS_VERSION`).
4. **Company.** E7GZ-PS, El Banafseg 3, New Cairo, Egypt.
5. **Minimum age.** 16. Customers aged 16 or 17 use the app with a parent's or guardian's permission. Sign-up does not ask for the age; the texts state it.
6. **Deletion.** A deletion request is completed within 30 days (PCR-002 process unchanged).
7. **Contact.** One Gmail address for privacy questions and support, which the Product Owner is creating; it moves to an address on the domain later.
8. **Providers.** SMS: SMS Misr. The email and hosting providers are chosen in E7-101 and named in the Privacy Policy then.
9. **No lawyer's check.** The Product Owner decided to launch without a lawyer's check. The texts use plain default wording for data processed outside Egypt, liability, and the courts.
10. **Drafted on 21 Nov 2026 (for the Product Owner's review):** the Tournaments section of the Terms (from FR-TRN-03, -06, -12, -14, -17, -21, -29), the no-show ladder (FR-TRS-13), and the Arabic version of both texts.

## Open items for the Product Owner

| Item | Needed before |
|---|---|
| Approve the two texts, rename them without "DRAFT", record the date here | Deployment (E7-101). E7-124 and E7-122 are built before it, with a placeholder version label (Product Owner decision, 16 November 2026) |
| The contact Gmail address (decision 7) | The texts can be approved |
| The email and hosting providers and the hosting country (decision 8) | The texts can be approved (E7-101) |
| The domain, then publish both pages there (decision 1) | E7-101 |
| Review the Arabic versions (drafted 21 Nov 2026 under the English text in each file, decision 2) | The texts can be approved |
| The web server in front of the API must not log URL query strings: discovery receives the customer's location as `lat`/`lng` parameters, and the Privacy Policy says no location history is kept | E7-101 |

## Traceability

| Item | Change |
|---|---|
| `FR-ACC-01` | Registration also needs the customer's agreement; the accepted version is stored |
| FRS §3.1, §3.2, §3.3 | Each app shows the two links |
| PCR-002 | Unchanged |
| API | `POST /customer/auth/register` gains the accepted version (required); `GET /admin/users/{userId}` shows it |
| Database | A new migration stores the accepted version and instant per customer (E7-124) |
| Build plan | E7-124 (server), E7-122 (apps), E7-109 (App Privacy labels), E7-110 (launch checklist) |
