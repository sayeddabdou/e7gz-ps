# E7GZ PS
# TECHNICAL ARCHITECTURE PACK v1.0 — DOCUMENT MAP & CONVENTIONS

**Status:** Aligned to physical model v1.1.27
**Date:** 25 September 2026
**Product authority:** `E7GZ PS — Product Specification v5.0 (PCR-001 through PCR-004 incorporated) — FROZEN BASELINE`
**Approved scope authority:** `E7GZ PS — Product Requirements Document (PRD) v1.1 — APPROVED FOR BUILD`
**Functional authority:** `E7GZ PS — Functional Requirements Specification (FRS) v1.1 — APPROVED FOR BUILD` — 357 / 357 requirements
**Supporting baselines:** Business Rules v1.0 · Validation Rules v1.0 · User Flows v1.0 · Acceptance Criteria v1.0 · Error & Edge Cases v1.0 · Out-of-Scope & MVP Exclusions v1.0
**Physical data model:** `docs/04-erd/physical/E7GZ-PS-Database-v1.1.27-REVIEW-FIXES.dbm` — authoritative for table, column, constraint and schema names (`04`, `ADR-027`)
**UI/UX input:** Customer App Prototype · Venue Dashboard Design System · Platform Admin prototype · Compliance UI Addendum
**Tournament participant-cause alignment:** 25 September 2026 — the Product baseline now keeps `INELIGIBLE` as the single Participant Status while making `BAN` vs `ACCOUNT_DELETED` explicit causes, with deletion precedence and a distinct pre-start Tournament Capacity Occupancy rule. This pack implements that behaviour without introducing another Product status.

---

## 0.1 What this pack is

This pack is the **Technical Architecture phase output**. It decides how the software is structured and what technology implements the approved functional behaviour. It is written for Backend, Mobile, Web, QA, DevOps and the Product Owner.

Everything here is derived from the 357 approved functional requirements. Where a technology appears, it appears because a requirement earned it, and the requirement is cited.

## 0.2 What this pack deliberately does not do

- It does **not** add, remove, reinterpret or soften Product behaviour. Every behavioural statement is a citation, not a decision.
- It does **not** introduce new states, enums, reason codes, events, timers, authorization rules or eligibility predicates.
- It does **not** treat any v4.x document as authority. **Product Plan v4.0, the v4.x audits and the v4.0-era `Technical Architecture & Detailed Data Model v1.0` are superseded and were not used as inputs.** Only the UI/UX prototypes from the `E7GZ PS` folder were used, and only for surface and interaction constraints.
- It does **not** resolve genuine Product ambiguity silently. Where implementation needs a decision the baseline does not make, it is raised in `§ Open Product Questions` of the relevant document and listed in `01 — Technical Architecture § 14`.

## 0.3 Precedence

1. Product Specification v5.0 — exact behaviour
2. PRD v1.1 — approved requirements, scope, priority
3. FRS v1.1 — functional decomposition and traceability
4. **This pack** — implementation choices inside v5.0 latitude
5. **Physical data model** (`docs/04-erd/`) — the database realisation of this pack

If this pack conflicts with the FRS, the FRS wins and this pack is corrected. For database identifiers (schemas, tables, columns, constraints) the physical model is authoritative and `04` is generated from it; a behavioural disagreement between the physical model and this pack is resolved in favour of the FRS and then corrected in both. If implementing a document here would require changing Product behaviour, that is a Product Change Request, not an implementation choice.

## 0.4 Document map

| # | Document | Answers |
|---|---|---|
| 00 | Document Map & Conventions | How to read this pack |
| **01** | **Technical Architecture** | System context, components, boundaries, module map, integrations, technology decisions, high-level security, caching/messaging/storage |
| 02 | Architecture Decision Record Log | Every major decision, the requirements that force it, and the alternatives explicitly rejected |
| 03 | Domain Module Specification | Per module: responsibilities, entities, services, domain events, dependencies, exposed API surface |
| 04 | Data Model & Database Design | Entity list, ERD, tables, columns, types, keys, constraints, indexes, enums, money and time strategy |
| 05 | Availability & Reservation Engine | The canonical predicate, the reservation ledger, database-enforced non-overlap, concurrency strategy |
| 06 | Booking & Tournament Lifecycle Engine | State machines, transition guards, cascades, sequence diagrams |
| 07 | Trust, Attendance & Enforcement Engine | Event-sourced trust, deterministic replay, ban and Verified Player derivation, idempotent cascades |
| 08 | Scheduling, Timers & Event Architecture | Deadline engine, pause/resume, transactional outbox, domain event catalogue, worker design |
| 09 | API Architecture & Contract Map | REST conventions, versioning, auth, idempotency, error model, endpoint map per surface |
| 10 | Security Architecture | Authentication, authorization matrix, Owner Password, PII and contact retention, rate limiting, audit |
| 11 | Non-Functional & Observability Architecture | Performance budgets, correctness guarantees, metrics, alerting, localization |
| 12 | Deployment, Environments & Operations | Topology, containers, environments, backup and recovery, runbooks |
| 13 | Traceability Matrix | All 357 FR IDs → owning module → tables → endpoints |
| 14 | Implementation Roadmap | Build sequence aligned to the PRD §26 phases, with architecture exit criteria |

## 0.5 Conventions used throughout

**Requirement citations.** `FR-BOK-11`, `NFR-04`, `EC-X-001` refer to the approved baselines. A statement with a citation is **behaviour** and is not negotiable at implementation time. A statement without a citation is an **architecture decision** and is negotiable through normal engineering review.

**Decision markers.**

| Marker | Meaning |
|---|---|
| `[DECIDED]` | Architecture decision, recorded in the ADR log |
| `[DEFERRED]` | Deliberately not built at launch; adoption trigger stated |
| `[ASSUMPTION]` | Implementation reading of a baseline statement; needs Product Owner confirmation |
| `[OPEN]` | Genuine gap; blocks implementation of the named area until resolved |

**Naming.**

- Database identifiers: `snake_case`, singular table names, one schema per owning module (`booking.booking`, `inventory.resource_reservation`). Closed Product value sets are `text` columns with a closed `CHECK` list (`ADR-027`).
- Java packages: `ps.e7gz.<module>` with `api` / `domain` / `application` / `infrastructure` sub-packages.
- JSON fields: `camelCase`, matching the FRS token spelling exactly (`startAt`, `lateConfirmExpiresAt`, `businessDate`).
- Product-visible enum values: `SCREAMING_SNAKE_CASE`, spelled **exactly** as the FRS spells them. `ABSENT_NO_SHOW` is the attendance result; `NO_SHOW` is the booking status; they are never interchanged (`FR-ATT-07`).
- Tournament Participant Status remains `JOINED · LEFT · REMOVED · INELIGIBLE`. `BAN` and `ACCOUNT_DELETED` are **causes of `INELIGIBLE`**, not new statuses. `ACCOUNT_DELETED` outranks `BAN`; implementation must never encode `BANNED_PARTICIPANT`, `DELETED_PARTICIPANT` or equivalent pseudo-statuses.
- **Active Participant Count** and **Tournament Capacity Occupancy** are deliberately different. The checkpoint counts only active participants. A pre-start `INELIGIBLE/BAN` participant with an active account may retain one capacity slot for automatic restoration, while `INELIGIBLE/ACCOUNT_DELETED` retains none.

**Time.** Every stored instant is `timestamptz` in UTC. Every local-time concept carries its IANA zone, which is `Africa/Cairo` for the launch market (`NFR-07`, `NFR-08`). Local wall-clock is presentation and templating only.

**Money.** Every monetary value is `NUMERIC(12,2)` in EGP, rounded half-up per line item, totals summed from rounded line items (`FR-BOK-06`, `NFR-25`). Floating point is never used for money.

**Ranges.** Every interval is half-open `[start, end)`. Every deadline comparison is `now < deadline` permits, `now >= deadline` expires (`FR-AVL-15`, `NFR-10`).

## 0.6 The seven architecture invariants

These are the guarantees the architecture exists to deliver. Every design in this pack is answerable to them, and a change that weakens one is rejected.

| # | Invariant | Source |
|---|---|---|
| **I1** | Two active reservations never overlap on one resource, enforced by the database and not by application code alone (`ex_no_double_booking`; Maintenance, which may overlap Maintenance, is policed by the cross-class guard — `04 §4.1`) | `FR-AVL-08`, `NFR-28`, `BR-X-002` |
| **I2** | Every commitment-strengthening transition revalidates and commits in one atomic transaction; the first successfully committed valid transition wins | `FR-AVL-09`, `FR-AVL-10`, `NFR-01`, `NFR-02` |
| **I3** | Every terminal booking and every terminal tournament carries exactly one reason code from its closed enum, naming the real cause | `FR-BOK-27`, `FR-TRN-31`, `NFR-26`, `BR-X-003` |
| **I4** | Multi-entity cascades are all-or-nothing and persist completely before any notification dispatches | `FR-OPS-09`, `FR-OPS-29`, `NFR-03`, `FR-NTF-12` |
| **I5** | Trust state is derived by deterministic replay of an immutable ordered event stream, reproducible as of any past instant | `FR-TRS-01`, `FR-TRS-06`, `FR-TRS-10`, `NFR-05` |
| **I6** | Historical values — prices, fees, rewards, attendance results, session snapshots — are never rewritten by later configuration; corrections are compensating entries | `FR-AUD-01`, `FR-AUD-04`, `NFR-22`, `BR-X-006` |
| **I7** | Every protected venue action validates the Owner Password freshly, per action, with no session, unlock window or cache | `FR-VEN-02`, `NFR-16`, `BR-X-007` |

---

*Continue to `01 — Technical Architecture.md`.*
