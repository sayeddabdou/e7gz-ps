# E7GZ PS — PRODUCT CHANGE REQUEST PCR-011
# Referral code only at launch; the referral link is deferred

**Status:** APPROVED — Sayed (Product Owner), 30 October 2026.\
**Date:** 30 October 2026\
**Applies to:** Product Specification v5.0 §5.7 and §46.4 ("A User receives a Referral Code and Link")

## Why

A referral link needs a public domain and deep linking in the customer app, and neither exists before deployment. The code alone delivers the referral: the friend enters it at sign-up.

## Change

1. **Code only at launch — NEW.** A customer receives a Referral Code and no Referral Link.
2. **Sharing — NEW.** The customer app shares the code as text. The friend types it into the optional referral code field at sign-up.
3. **Referral rules — UNCHANGED.** Eligibility at OTP verification, one reward per referred user, self-referral checks, the stored reward value and invalidation all stay as they are (`FR-PTS-06` to `FR-PTS-09`).
4. **A wrong code never blocks sign-up — UNCHANGED.** It is recorded as an INVALID referral (§46.4) and the app shows no message.
5. **The link is a future candidate**, not a commitment.

## Traceability

| Item | Change |
|---|---|
| Spec §5.7, §46.4 | "Referral Code and Link" → Referral Code only at launch |
| `FR-PTS-06` to `FR-PTS-09` | Unchanged; none of them names a link |
| MVP exclusions §2, §9 | "No referral link at Launch" added; the link is a future candidate |
| API | None: `GET /customer/referrals` already returns the code only |
| Database | None |
| Build plan | E7-67 (backend), E7-74 (the app shares the code as text) |
