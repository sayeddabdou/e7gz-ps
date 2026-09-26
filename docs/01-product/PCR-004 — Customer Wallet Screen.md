# E7GZ PS — PRODUCT CHANGE REQUEST PCR-004
# Customer Wallet Screen

**Status:** APPROVED / INCORPORATED  
**Date:** 26 September 2026  
**Applies to:** Product Specification v5.0 §46.6; amends `FR-PTS-11`

## Change

The Customer Mobile App shows a **read-only wallet screen** at launch: the wallet balance (EGP) and the list of wallet transactions (booking-fee refunds).

- The wallet is still credited only by E7GZ booking-fee refunds and **never pays the venue gaming charge** (`FR-PTS-10` unchanged).
- No top-up, no payment with the wallet, no withdrawal.
- Platform Admin wallet adjustment is still **not** a launch action.
- Points-to-wallet conversion stays deferred (`FR-PTS-12` unchanged).

While the Global Booking Fee is EGP 0 the balance will normally be EGP 0.00; the screen must show that state clearly.

## Rationale

Customers should see refunds they receive as soon as a non-zero booking fee is switched on, without a later app release.

## Traceability

| Item | Change |
|---|---|
| `FR-PTS-11` | "no active wallet screen at launch" → read-only wallet screen at launch; admin wallet adjust still excluded |
| API | new `GET /api/v1/customer/wallet` (balance + transactions) — arch 09 §3.4 |
| Database | none — `loyalty.wallet_account` / `loyalty.wallet_transaction` already exist |
| Build plan | E7-65 (contract), E7-66 (endpoint), E7-74 (screen) |
