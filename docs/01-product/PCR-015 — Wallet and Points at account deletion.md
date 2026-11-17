# E7GZ PS — PRODUCT CHANGE REQUEST PCR-015
# Wallet and Points at account deletion

**Status:** APPROVED — Sayed (Product Owner), 16 November 2026.\
**Date:** 16 November 2026\
**Applies to:** `PCR-002` (Customer-Initiated Account Deletion); `FR-ACC-10`; Product Specification v5.0 §§7.7, 53.5; `PCR-009` and `PCR-010` (what the Wallet and Points are for)

## Why

Since `PCR-009` and `PCR-010` the Wallet and Points have a real use: they pay the Booking Fee. No rule said what happens to them when the Customer deletes the account.

## Change

1. **Lost at deletion — NEW.** When an account is deleted, its Wallet balance and its Points are lost. They are not paid out, not moved to another account, and cannot be claimed later.
2. **The Customer is told first — NEW.** The Delete Account screen shows the Wallet balance and the Points balance and says that both are lost, before the Customer confirms the request.
3. **History stays — UNCHANGED.** The Points and Wallet ledgers are append-only and stay with the anonymised account. Deletion writes no ledger row.
4. **A new account starts at zero — NEW.** A person who registers again after deletion, also with the same phone number, has a new account with no Points and an empty Wallet.
5. **Referrals — UNCHANGED (Product Owner decision, 16 November 2026).** A referral for the new account follows the normal rules (`FR-PTS-06` to `FR-PTS-09`). No trace of the deleted account's phone number is kept to prevent a second reward; the Platform Admin invalidates abuse by hand (`FR-ADM-09`).
6. **Everything else of `PCR-002` — UNCHANGED.**

At launch the Booking Fee is EGP 0, so every Wallet is at EGP 0.00; Points can already be held.

## Traceability

| Item | Change |
|---|---|
| `PCR-002`, `FR-ACC-10` | Amended: the Wallet balance and the Points are lost at deletion, and the Customer is told before confirming |
| `PCR-009`, `PCR-010` | Unchanged |
| `FR-PTS-06` to `FR-PTS-09` | Unchanged |
| API | The account-deletion request shows the Wallet balance and the Points balance that will be lost |
| Database | None |
| Build plan | E7-98 (contract), E7-103 (backend), E7-106 (Customer App); the Terms of Use say it (E7-125) |
