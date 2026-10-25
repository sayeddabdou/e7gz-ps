# E7GZ PS — PRODUCT CHANGE REQUEST PCR-010
# Points pay the Booking Fee; the Booking Fee is per booking or per hour

**Status:** APPROVED — Sayed (Product Owner), 25 October 2026.\
**Date:** 25 October 2026\
**Applies to:** Product Specification v5.0 §5.1, §5.5, §5.6, §34, §46.6 and §51.10; amends `FR-PTS-12`, `FR-PTS-13`, `FR-ADM-10` and `PCR-009` (what the Wallet pays), and the points-conversion lines of `PCR-004` and the MVP exclusions

## Why

At launch Points can be earned but not spent. The only planned use was a future Points-to-Wallet money conversion with no agreed value (`FR-PTS-12`, "illustratively 2,000 Points → EGP 20"), and even then the Wallet money could only pay a Booking Fee that is EGP 0. Customers who invite friends (+1,000) or attend bookings (+200) need their Points to be worth something real. This change makes Points pay the E7GZ Booking Fee itself, and lets the Platform Admin charge that fee per booking or per hour.

## Change

1. **Booking Fee mode — NEW.** The Platform Admin chooses the Booking Fee mode:
   - **PER_BOOKING** — one fee for the whole booking (the rule until now), e.g. EGP 20;
   - **PER_HOUR** — a fee per hour of the booked duration, e.g. EGP 10 per hour, so a 2-hour booking pays EGP 20.

   The Admin sets both amounts (the per-booking fee and the per-hour fee) and which mode is used. A change applies to bookings created after it only (`FR-AUD-01`, `FR-ADM-11`). **Launch: PER_BOOKING, EGP 0, shown as FREE** — nothing changes at launch.
2. **Per hour, by the half hour — NEW.** In PER_HOUR mode the fee follows the booked nominal duration exactly, in its 30-minute steps (`FR-BOK-01`, `FR-BOK-07`): 1.5 hours at EGP 10 per hour is **EGP 15**. The amount is rounded half-up to 2 decimals, like the venue price.
3. **Stored at creation — UNCHANGED principle.** Each booking stores the fee worked out at its creation; a later change of mode or amount never alters it; Late Confirm reuses the original stored fee (`FR-PTS-13`, `FR-LCF-08`).
4. **Points pay the Booking Fee — NEW; replaces the Points-to-Wallet conversion of `FR-PTS-12`.** Points from any source (attended bookings, referrals, admin adjustments) pay the E7GZ Booking Fee **directly**. They never become Wallet money.
   - **10,000 Points = one fee unit**: one hour of fee in PER_HOUR mode, or the whole fee of one booking in PER_BOOKING mode.
   - The value is **the fee in force on the day of booking**, not a fixed EGP amount: if the fee rises from EGP 10 to EGP 15 per hour, 10,000 Points pay EGP 15.
   - **PER_HOUR:** several units may be used on one booking, and a half hour costs half a unit (**5,000 Points**). As many half hours are paid as the balance covers, up to the whole booking.
   - **PER_BOOKING:** a whole booking fee needs the full 10,000; with fewer, no Points are used.
   - The 10,000 is **configurable by the Platform Admin** and must be an **even** whole number (so a half hour is always a whole number of Points). A change applies to bookings created after it only.
5. **Automatic, Points first — NEW.** In the transaction that creates an Online Booking, with no customer action:
   1. Points pay as much of the fee as the rules of item 4 allow;
   2. the Wallet pays **what is left** only when it holds **all** of it (the `PCR-009` whole-amount rule, applied to the remainder);
   3. anything still left is collected by the payment provider once it exists (ADR-018); until then it stays uncollected, as today.
6. **Shown to the customer — NEW.** Before confirming, the price summary shows the fee, the Points that will be used, what the Wallet will pay and what is left to pay. The booking and the Points history show what was used. The summary shows what would be used at that moment; the booking uses the balances at its creation.
7. **Refunds — rules UNCHANGED, destination per source.** When and whether the fee is refunded is unchanged (`FR-PTS-14` to `FR-PTS-19`, §34). A refund gives back **exactly the Points used** to the Points balance, and the money part goes to the Wallet as today (`PCR-009`, `FR-PTS-10`). A cancellation that refunds nothing returns nothing.
8. **Never the venue gaming charge — UNCHANGED.** Points and Wallet pay only the E7GZ Booking Fee of an Online Booking (`FR-PTS-10`): never the venue price, never a Manual Booking (it has no fee), never the Tournament Creation Fee.
9. **Points rules — UNCHANGED.** Spending Points never changes Trust, Verified Player progress or rewards already granted (`FR-PTS-02`). The balance never goes below 0 (`FR-PTS-03`): a later reversal of a reward that was already spent follows the zero floor, and the shortfall is recorded as `UNRECOVERED_REVERSAL_AMOUNT`, never as debt (`FR-PTS-04`). No new notification type: the booking and its cancellation notifications already tell the customer.

## Worked example

PER_HOUR mode, EGP 10 per hour, 10,000 Points per hour. A customer with **23,000 Points** and **EGP 3** in the Wallet books **2.5 hours**:

| Step | Result |
|---|---|
| Fee | 2.5 h × EGP 10 = **EGP 25** |
| Points | 5 half hours would need 25,000; 23,000 pay 4 half hours: **20,000 Points pay EGP 20**; 3,000 stay |
| Wallet | EGP 5 is left; the Wallet holds EGP 3 < EGP 5, so it pays **nothing** |
| Left to pay | **EGP 5**, by the payment provider |
| Venue cancels later | Full refund: the **20,000 Points come back**, and the collected EGP 5 goes to the Wallet |

PER_BOOKING mode, EGP 20: a customer with 12,000 Points pays the whole fee with 10,000 and keeps 2,000; a customer with 7,000 Points uses none.

## Traceability

| Item | Change |
|---|---|
| `FR-PTS-12` | "Points-to-wallet conversion is deferred; the ratio will be configurable" → Points pay the Booking Fee directly (items 4–5); the configurable value is the Points per fee unit, launch 10,000 |
| `FR-PTS-13` | "charged **per booking** — not per hour" → charged per booking **or per hour**, as the Platform Admin chooses (items 1–3); still EGP 0 and FREE at launch, still stored at creation, Late Confirm still reuses it |
| `FR-ADM-10` | The Admin controls the Global Booking Fee: its mode, the per-booking amount and the per-hour amount; and the Tournament Creation Fee (unchanged) |
| `FR-PTS-10` | Unchanged: never the venue gaming charge. The Wallet pays what Points leave of the fee |
| `FR-PTS-14`–`FR-PTS-19` | Unchanged; they also decide the refund of a fee paid with Points (item 7) |
| `PCR-009` | The Wallet pays the Booking Fee → the Wallet pays **what Points leave** of it, still only when it holds all of that |
| `PCR-004` | "Points-to-wallet conversion stays deferred" → there is no conversion; Points pay the Booking Fee (this PCR) |
| MVP exclusions §2, §9 | "No points-to-wallet conversion at Launch" stays true (there is none); Points pay the fee once it is above EGP 0 |
| arch | 01 §14 A8 (eight configurable values), 03 §16, 04 §5, 09 §3, 11 §7, 13 |
| API | `GET/PUT /admin/fees` gain `bookingFeeMode` and `bookingFeePerHour`; `GET/PUT /admin/points/settings` gains `pointsPerFeeUnit`; the booking quote and the customer booking gain `bookingFeePayment` (Points used, paid by Points, by the Wallet, left to pay); `PointsTransactionCause` gains `BOOKING_FEE_PAYMENT` and `BOOKING_FEE_REFUND` (arch 09 §3.4) |
| Database | Physical model v1.1.40, `V202610251200__points_pay_booking_fee.sql`: configuration keys `booking.fee_mode`, `booking.fee_per_hour`, `loyalty.points_per_fee_unit`; `config.booking_fee_for` (the fee of a booking at an instant, used by the intake guard); `loyalty.booking_fee_points_payment`; the producer `loyalty.pay_booking_fee` (Points, then Wallet); Points refunds in `loyalty.record_refund` |
| Build plan | E7-68 (configuration, fee modes, Points payment and refund, database, contract); carry-forwards to E7-74 (customer screens) and E7-75 (admin screens) |
