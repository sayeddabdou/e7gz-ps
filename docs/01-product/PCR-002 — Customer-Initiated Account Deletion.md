# E7GZ PS — PRODUCT CHANGE REQUEST PCR-002
# Customer-Initiated Account Deletion

**Status:** APPROVED / INCORPORATED  
**Date:** 25 September 2026  
**Applies to:** Product Specification v5.0 §§7.7, 53.5; `FR-ACC-10`

## Change

The Customer Mobile App provides an in-app **Delete Account** action that lets an authenticated Customer initiate deletion of their account.

Initiation creates a durable Account Deletion Request. It does **not** hard-delete retained Product history and does not bypass the existing Support / Platform Admin anonymisation process. The existing deletion/anonymisation process completes the request by disabling login, removing/anonymising PII where appropriate, preserving legally and operationally required Booking / financial / audit history, removing the Customer's Reviews from public display, and applying the existing Tournament `ACCOUNT_DELETED` consequence.

At most one unresolved deletion request may exist for the same User. Repeating the initiation while one is unresolved returns the existing request rather than creating a second deletion operation.

No self-service cancellation/undo deadline or automatic completion deadline is introduced by this PCR.

## Rationale

Customers who can create an account must be able to start account deletion from inside the Customer App while E7GZ retains the existing audited anonymisation and historical-integrity model.

## Traceability

`FR-ACC-10` · `FR-TRN-26` · `FR-REV-06` · O19
