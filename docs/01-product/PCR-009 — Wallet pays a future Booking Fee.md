# E7GZ PS — PRODUCT CHANGE REQUEST PCR-009
# The Wallet pays the Booking Fee of a future booking

**Status:** APPROVED — Sayed (Product Owner), 24 October 2026.\
**Date:** 24 October 2026\
**Applies to:** Product Specification v5.0 §46.6 and §34; amends `PCR-004` ("no payment with the wallet") and `FR-PTS-10`, `FR-PTS-11`

## Why

A refund of a collected Booking Fee goes to the customer's in-app Wallet, never back to the card: the money stays in E7GZ's bank account. Without a way to spend it, the Wallet has no value for the customer. This change lets the customer use that balance for the Booking Fee of a later booking.

## Change

1. **Refunds go to the Wallet — UNCHANGED.** A refund of a collected Booking Fee is credited to the Wallet as an in-app balance (`FR-PTS-10`). It is never paid back to the card, and the Wallet can never be withdrawn.
2. **The Wallet pays a future Booking Fee — NEW, REQUIRED.** When a customer creates an Online Booking whose stored Booking Fee is above EGP 0 and the Wallet holds **at least the whole fee**, the Wallet pays that fee automatically, in the same transaction that creates the booking. The customer does nothing extra; there is no "pay with wallet" button.
3. **Whole fee only.** When the Wallet holds less than the fee, nothing is taken from it: there is no partial payment. The fee is then collected as without a Wallet (by the payment provider once it exists, ADR-018; until then it stays uncollected, as today).
4. **A refund of a Wallet-paid fee goes back to the Wallet.** The refund rules are unchanged (`FR-PTS-14`, `FR-PTS-15`, `FR-PTS-16`): a customer cancellation within 15 minutes of creation and before the start, or any venue, platform or emergency cause, refunds the whole fee to the Wallet; a later customer cancellation refunds nothing and E7GZ keeps the fee.
5. **Never the venue gaming charge — UNCHANGED.** The Wallet pays only the E7GZ Booking Fee (`FR-PTS-10`). No top-up, no withdrawal, no Platform Admin Wallet adjustment (`FR-PTS-11`).

At launch the Booking Fee is EGP 0, so nothing is paid and every Wallet stays at EGP 0.00; the behaviour starts when a non-zero fee is enabled.

## Traceability

| Item | Change |
|---|---|
| `PCR-004` | "No top-up, no payment with the wallet, no withdrawal" → no top-up, no withdrawal; the Wallet pays a future Booking Fee as item 2 |
| `FR-PTS-10` | The Wallet supports Booking Fee refunds **and pays the Booking Fee of a future booking**; still never the venue gaming charge |
| `FR-PTS-11` | Unchanged: no Platform Admin Wallet adjustment; the customer's Wallet screen stays read-only (the payment happens at booking) |
| `FR-PTS-14`–`FR-PTS-16` | Unchanged; they also decide the refund of a Wallet-paid fee |
| API | No new route and no new request field. `WalletTransactionCause` gains `BOOKING_FEE_PAYMENT`; a Wallet item's `amount` is how much moved (never negative), its `cause` says whether it was credited or spent (arch 09 §3.4) |
| Database | Physical model v1.1.39, `V202610241200__wallet_booking_fee_payment.sql`: a `BOOKING_FEE_PAYMENT` debit in `loyalty.wallet_transaction` (`ck_wallet_tx_source`), the producer `loyalty.pay_fee_from_wallet`, the Wallet payment as the booking's `WALLET` collection receipt (so refunds settle back to the Wallet unchanged) |
| Build plan | E7-66 PR 3 (database, producer, booking-creation call, tests); E7-74 (the Wallet screen lists payments as well as refunds) |
