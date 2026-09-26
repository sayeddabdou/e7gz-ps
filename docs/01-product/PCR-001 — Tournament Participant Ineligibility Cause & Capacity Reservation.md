# E7GZ PS — PRODUCT CHANGE REQUEST PCR-001
# Tournament Participant Ineligibility Cause & Capacity Reservation

**Status:** APPROVED / INCORPORATED  
**Date:** 25 September 2026  
**Applies to:** Product Specification v5.0 §§37.4, 37.11 and dependent requirements

## Change

`INELIGIBLE` remains the single Tournament Participant Status for an ineligible participant. It carries an explicit cause of `BAN` or `ACCOUNT_DELETED`.

- `ACCOUNT_DELETED` has precedence over `BAN`.
- A pre-start `INELIGIBLE/BAN` participant contributes **0** to `activeParticipantCount` but reserves one capacity slot while automatic restoration remains possible.
- `INELIGIBLE/ACCOUNT_DELETED` contributes to neither active count nor capacity occupancy and can never be auto-restored.
- Ban expiry/lift before `startAt` restores a BAN-only participant automatically when the Tournament is non-terminal and the account remains active.
- Checkpoint and Start evaluate Ban restoration at their authoritative boundary instant before making the boundary decision, even when a worker executes late.
- At or after `startAt`, automatic restoration does not occur; a Tournament already cancelled at checkpoint is never resurrected.

## Rationale

Separating checkpoint eligibility from temporary capacity reservation prevents a Ban expiry from overfilling the Tournament while preserving the existing Participant Status domain.

## Traceability

`FR-TRN-12` · `FR-TRN-13` · `FR-TRN-26` · `ADR-023`
