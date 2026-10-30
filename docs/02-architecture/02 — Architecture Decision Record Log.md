# E7GZ PS
# ARCHITECTURE DECISION RECORD LOG v1.1

**Updated:** 25 September 2026 — aligned to physical model v1.1.27 (ADR-003, 010, 016, 020 amended; ADR-026 to 028 added).

**Purpose.** Record every significant architectural decision, the requirements that force it, and the alternatives that were considered and **rejected** — so that they are not casually revisited later in the build.

**Rule.** A rejected alternative in this log is closed. Reopening one requires a new ADR that supersedes the old, naming the requirement or evidence that changed.

**Status values:** `ACCEPTED` · `DEFERRED` (decided not to build now, with an adoption trigger) · `SUPERSEDED`

| ADR | Title | Status |
|---|---|---|
| 001 | Modular monolith with a separate worker deployment | ACCEPTED |
| 002 | PostgreSQL as the single system of record | ACCEPTED |
| 003 | Database-enforced occupancy and first-committer-wins concurrency | ACCEPTED |
| 004 | Transactional outbox without a message broker | ACCEPTED / RabbitMQ DEFERRED |
| 005 | No Redis at launch; PostgreSQL projection and counters | ACCEPTED / Redis DEFERRED |
| 006 | Database-backed deadline engine with leases | ACCEPTED |
| 007 | Full deterministic trust replay | ACCEPTED |
| 008 | Append-only enforced at the data layer | ACCEPTED |
| 009 | Materialized operating sessions over a rolling horizon | ACCEPTED |
| 010 | Two-layer inventory: exclusive ledger + non-exclusive blocking windows | ACCEPTED |
| 011 | Owner Password as a per-action credential, never a session | ACCEPTED |
| 012 | REST + OpenAPI, surface-scoped route groups | ACCEPTED |
| 013 | React Native + React/TypeScript client stack | ACCEPTED |
| 014 | SSE over `LISTEN/NOTIFY` for the Venue Dashboard | ACCEPTED |
| 015 | Idempotency keys on every commitment-strengthening write | ACCEPTED |
| 016 | Three separate closed enums: reason codes, booking events, API errors | ACCEPTED |
| 017 | `NUMERIC(12,2)` money and `timestamptz` time | ACCEPTED |
| 018 | Payment provider as an unused port | DEFERRED |
| 019 | PostGIS for distance and map eligibility | ACCEPTED |
| 020 | Spring Data JDBC with explicit SQL on inventory paths | ACCEPTED |
| 021 | Language-neutral backend, translation at the surface | ACCEPTED |
| 022 | Eligibility category as a mandatory command declaration | ACCEPTED |
| 023 | Tournament ineligibility cause and pre-start capacity occupancy are separate concepts | ACCEPTED |
| 024 | Customer account deletion: durable request + O19 anonymisation | ACCEPTED |
| 025 | Review reports are abuse-signal records, not Review transitions | ACCEPTED |
| 026 | Database enforcement model: the database owns invariants, the application owns decisions | ACCEPTED |
| 027 | Physical layout: one schema per module, closed `CHECK` lists, the `.dbm` is the identifier authority | ACCEPTED |
| 028 | Versioned platform configuration for Product-configurable values | ACCEPTED |

---

# ADR-001 — Modular monolith with a separate worker deployment

**Status:** ACCEPTED

**Context.** The Product defines multi-entity cascades that must be all-or-nothing. A venue emergency closure atomically cancels bookings across many customers, terminates active sessions, cancels tournaments, releases reservations and holds, writes history and audit, and enqueues notifications — and `FR-OPS-09` states that **"a partial cascade is not an acceptable outcome."** `FR-OPS-29` repeats it for owner review. `NFR-03` generalises it to emergency closure, resource emergency, owner review, opening-hours change and ban activation. Separately, `FR-AVL-10` and `NFR-01` require revalidation and transition to commit in one atomic transaction, spanning the customer's commitment state, the resource reservation and the booking.

**Decision.** One deployable Spring Boot application containing all domain modules, backed by one PostgreSQL database, deployed twice: an **API profile** serving HTTP, and a **worker profile** running schedulers, the outbox dispatcher and projection rebuilds.

**Consequences.**

- Every atomic requirement is satisfied by an ordinary database transaction.
- Module boundaries must be enforced by discipline and tooling rather than by the network — so ArchUnit tests enforce package boundaries, and each module owns its tables (`01 §6.3`).
- A slow provider cannot starve request threads, because providers are only called from the worker tier.
- Scaling is vertical for the database and horizontal for stateless app replicas, which `NFR-15`'s 10× headroom does not challenge at one-city volume.

**Alternatives rejected.**

| Alternative | Rejected because |
|---|---|
| Microservices per bounded context | Every cascade in `NFR-03` becomes a saga with compensations. A compensation *is* an intermediate partial state, which the requirement forbids. It would also spread the availability predicate — `FR-AVL-01` says there is exactly one — across service boundaries |
| Monolith with a shared database across future services | Worst of both: distributed deployment without transactional isolation, and no ownership boundary |
| Serverless functions | Connection-pool pressure against a single Postgres, no long-lived SSE stream, and no natural home for lease-based deadline workers |
| Single deployment running API and workers together | A blocked provider call or a heavy replay consumes request capacity during the 10-minute venue response window (`FR-BOK-13`) |

---

# ADR-002 — PostgreSQL as the single system of record

**Status:** ACCEPTED

**Context.** Four distinct requirements point at capabilities that one engine happens to provide together:

1. `FR-AVL-08` — two active reservations must never overlap on one resource, and `NFR-28` calls any occurrence a Sev-1. This needs a **range exclusion constraint**.
2. `NFR-22` / `PRD §27.3 item 8` — append-only enforcement preferably at the data layer. This needs **triggers and privilege control**.
3. `FR-DIS-01` / `FR-DIS-10` — distance-sorted discovery and map markers. This needs **geospatial indexing**.
4. `NFR-13` — push dispatch within seconds of commit for a 60-second Late Confirm window. This needs a **commit-time wake signal**.

**Decision.** PostgreSQL 16 with the `btree_gist` and `postgis` extensions, as the only system of record. All four capabilities are native: `EXCLUDE USING gist`, `BEFORE UPDATE OR DELETE` triggers plus `REVOKE`, PostGIS `geography` with KNN, and `LISTEN`/`NOTIFY`.

**Consequences.** One backup and recovery story, one consistency model, one operational surface. The correctness of `I1` and `I6` moves out of application code and into schema, where a code defect cannot violate it.

**Alternatives rejected.**

| Alternative | Rejected because |
|---|---|
| MySQL / MariaDB | No range types and no exclusion constraints. `I1` would be application-enforced, which is exactly what `FR-AVL-08` and `R1` say must not happen |
| MongoDB or another document store | Multi-document atomicity exists but is not the natural unit; the cascades in `NFR-03` span many documents and collections, and there is no range-overlap constraint |
| PostgreSQL plus a separate search/analytics store at launch | No launch requirement needs it; `NFR-11` is met by one projection table. A second store adds a consistency story the Product does not ask for |
| Application-level locking as the double-booking guarantee | Rejected on principle: `R1` names a customer-visible double booking as existential, and an invariant that depends on no developer ever taking a wrong path is not an invariant |

---

# ADR-003 — Database-enforced occupancy and first-committer-wins concurrency

**Status:** ACCEPTED
**Closes `PRD §27.3` open item 1.**

**Context.** `NFR-02`: *"Concurrency resolves by first successfully committed valid transition wins, with mandatory revalidation for the loser. The architecture may choose any locking or isolation strategy that delivers this."* `EC-AVAI-001`: N simultaneous bookings for one slot must yield exactly one CONFIRMED path and N−1 **clean** failures with **no partial reservation**.

**Decision.**

1. Transaction isolation is **`READ COMMITTED`** — the PostgreSQL default.
2. The authority on resource occupancy is an **exclusion constraint** on `inventory.resource_reservation`:
   `ex_no_double_booking — EXCLUDE USING gist (resource_id WITH =, planned_range WITH &&) WHERE active_flag AND reservation_type <> 'MAINTENANCE'`.
   Maintenance is outside the constraint because the Product lets Maintenance overlap Maintenance (`01 §14` A1); Maintenance against any other active reservation is refused by the cross-class guard, which serialises on the Resource row and re-reads under `READ COMMITTED` *(amended v1.1)*.
3. The authority on customer commitment overlap is a **second exclusion constraint** on `booking`, scoped by a partial predicate to online commitments in non-terminal states (`FR-BOK-04`).
4. The **4-commitment count** (`FR-BOK-03`) is protected by a `SELECT … FOR UPDATE` on the customer's `iam.user_commitment_guard` row (created automatically for every User), taken inside the command context before the count is read.
5. Before inserting a reservation, the command takes `SELECT … FOR UPDATE` on the resource row. This is a **contention optimisation only**; correctness never depends on it.
6. A `23P01` exclusion violation is caught and translated into a clean, typed domain failure — no booking row, no reservation, no history entry. That is "the loser revalidates and fails cleanly."

**Consequences.** The double-booking invariant survives any application bug, any new flow and any future developer. Load tests measure contention, not correctness. Retry-on-conflict is allowed for idempotent reads but never for commitment creation, which must surface the failure to the caller (`FR-AVL-11`).

**Alternatives rejected.**

| Alternative | Rejected because |
|---|---|
| `SERIALIZABLE` isolation | Would deliver the guarantee, but at the cost of serialization failures on unrelated workloads and a retry loop on every write path. The exclusion constraint gives the same guarantee scoped precisely to the invariant that matters |
| Pessimistic locking alone | Correct only while every code path remembers to take the lock. Fails the `R1` standard |
| Optimistic version columns alone | Detects concurrent edits to one row; does not express "no two rows may overlap in time on this resource" |
| A Redis distributed lock | Puts a correctness invariant outside the correctness boundary (`01 §6.4`), and a lock lease expiry becomes a double-booking path |

---

# ADR-004 — Transactional outbox without a message broker

**Status:** ACCEPTED · **RabbitMQ / Kafka: DEFERRED**
**Closes `PRD §27.3` open item 5.**

**Context.** `FR-NTF-12` — notifications dispatch **after** the transaction commits, never before. `NFR-03` — the cascade must persist completely **before** notifications dispatch. `NFR-13` — dispatch within seconds of commit, because a Late Confirm window may be 60 seconds. `PRD §27.2` — one launch city, manual venue onboarding, low volume.

**Decision.** A transactional outbox: the business transaction writes `outbox_event` rows in the same commit as the business state. A commit-time `pg_notify('outbox')` wakes the dispatcher, which delivers to the push, email, SMS, projection and SSE adapters with retry, exponential backoff and a dead-letter state. **No broker at launch.**

**Consequences.**

- `FR-NTF-12` is structurally guaranteed: an uncommitted transaction leaves no outbox row.
- Dispatch latency is milliseconds under `NOTIFY`, with a low-frequency poll as the safety net for missed notifications (`NOTIFY` is not durable).
- One fewer service to run, secure, monitor and back up.
- The **outbox row is the contract.** Adopting a broker later replaces the dispatcher's transport and nothing else.

**Adoption trigger for a broker.** Any one of: (a) a second independent consumer group needs the same event stream, (b) sustained outbox backlog exceeds 30 seconds at p95, (c) a service is extracted from the monolith and needs cross-process delivery, (d) fan-out exceeds roughly 10⁵ events/day.

**Alternatives rejected.**

| Alternative | Rejected because |
|---|---|
| RabbitMQ from day one | Adds a service, a second delivery guarantee and a failure mode, while the outbox still has to exist to satisfy `FR-NTF-12`. No launch requirement needs routing, topic fan-out or consumer groups |
| Kafka | Retention and replay semantics solve problems this Product does not have; Trust replay reads its own table (`ADR-007`) |
| Publishing directly from the business transaction | Violates `FR-NTF-12` on rollback and couples the booking commit to broker availability — `R9` and `NFR-12` both push the other way |
| `@TransactionalEventListener(AFTER_COMMIT)` with no outbox table | An in-process listener is lost on crash between commit and dispatch, so a notification can be permanently missed. Unacceptable for a 60-second Late Confirm window |

---

# ADR-005 — No Redis at launch

**Status:** ACCEPTED · **Redis: DEFERRED**

**Context.** Four usual reasons to add Redis, checked against the requirements:

| Usual reason | Does a requirement earn it? |
|---|---|
| Distributed locking | **No** — `ADR-003` puts the invariant in the database; a lock outside the correctness boundary would be a regression |
| Session storage | **No** — JWT access tokens are stateless and refresh tokens are stored hashed in PostgreSQL for revocation |
| Rate limiting | **Partially** — `FR-ACC-04` and `NFR-16` require limits, but OTP state already lives in `otp_challenge` and Owner Password attempts must be **audited** anyway, which is a database write regardless |
| Read caching | **Yes, potentially** — `NFR-11` wants a venue list with per-venue Nearest Availability at p95 < 1s |

`FR-AVL-11` explicitly authorises precomputing displayed availability: *"Displayed availability is a snapshot, not a commit guarantee."*

**Decision.** No Redis at launch.

- Nearest Availability is a **PostgreSQL projection table** (`venue_activity_availability`), refreshed by the projection worker on inventory-affecting events and read by discovery with one indexed query.
- Rate-limit counters are PostgreSQL rows with a rolling window, colocated with the audit they must produce.

**Adoption trigger.** Any one of: (a) `NFR-11` p95 exceeds 1s on production data with the projection in place, (b) rate-limit write volume becomes a measurable share of database write load, (c) API replicas grow past roughly six and connection-pool pressure becomes the binding constraint.

**Alternatives rejected.**

| Alternative | Rejected because |
|---|---|
| Redis from day one for cache + limits | Adds a stateful service, a cache-invalidation story and an availability dependency, for a performance target already met by a projection table at one-city scale |
| In-process caches (Caffeine) for availability | Per-replica caches diverge, so two customers can see different availability — confusing, though not a correctness violation. Rejected for user-visible inconsistency |
| No precomputation, compute Nearest Availability per request | Walks opening hours, eligible resources and the eight-check predicate for every venue in the list; will not hold `NFR-11` as venue count grows |

---

# ADR-006 — Database-backed deadline engine with leases

**Status:** ACCEPTED
**Closes `PRD §27.3` open item 2.**

**Context.** The Product has at least ten scheduled transitions: venue response expiry, Late Confirm expiry, Late Confirm expiring-warning, booking start, booking completion, tournament approval expiry, tournament checkpoint, tournament start, tournament end, ban expiry. `NFR-04` requires reliable, idempotent firing. `NFR-14` requires that **late firing never produces an incorrect outcome**. `FR-OPS-36` requires pause and resume preserving remaining duration. `FR-OPS-39` requires resume to wait for the next valid intake window. `FR-OPS-40` requires an optimistic feasibility test that recomputes against future operating sessions.

**Decision.** A durable `scheduled_job` table — `(type, subject_id, due_at, state, attempt, leased_until, idempotency_key)` — polled by worker replicas with `SELECT … FOR UPDATE SKIP LOCKED`. Each fire re-reads authoritative state and applies the **boundary rule**, then marks the row `DONE` or `SUPERSEDED` in the same transaction as the effect.

**Consequences.**

- A pause is an update to `due_at` plus accumulated pause duration on the booking; a resume recomputes `due_at` from remaining duration and the next valid intake window. Nothing needs to be cancelled and re-sent.
- Multiple worker replicas are safe with no coordination service.
- A missed tick is harmless: the boundary rule, not the execution time, decides.
- Everything is queryable — "which timers are paused right now" is a SQL query, which `NFR-30` and `NFR-32` need.

**Alternatives rejected.**

| Alternative | Rejected because |
|---|---|
| Quartz (clustered) | Extra schema and lifecycle for no gain; awkward for deadlines whose due instant is **recomputed** from venue session data (`FR-OPS-39`) |
| Broker delayed messages / TTL + DLX | A scheduled message cannot be paused, re-derived or inspected. `FR-OPS-36`/`FR-OPS-40` make deadlines mutable state, not fire-and-forget |
| Redis sorted set as the timer store | Puts a Product-critical deadline outside the durable system of record |
| In-memory `@Scheduled` per replica | Lost on restart; duplicated across replicas; fails `NFR-04` |
| Client-side countdown driving the transition | Explicitly forbidden — `NFR-10` makes all timers server-authoritative |

---

# ADR-007 — Full deterministic trust replay

**Status:** ACCEPTED
**Closes `PRD §27.3` open item 3.**

**Context.** `FR-TRS-01` — counters, stages, ban state and Verified Player status are **derived** and never authoritative. `FR-TRS-06` — replay orders exclusively by `effectiveAt ASC, eventSequence ASC`. `FR-TRS-10` — replay computes state as of any time. `NFR-05` — replay is deterministic. `FR-TRS-24` — replay corrects trust state but never rewinds external booking side effects into the past. `NFR-06` — idempotency keys exist for every cascade that must execute once.

**Decision.** Full replay from `trust_event` on every derivation, with a cached projection in `trust_projection` used for reads and rebuilt whenever a new event or a correction is written. No snapshot-plus-delta at launch.

**Rationale.** A user's lifetime event count is realistically tens to low hundreds — `FR-ACC-11` sets Verified Player at ten completed bookings, and `FR-BOK-03` caps concurrent commitments at four. Full replay over a per-user ordered stream is microseconds of work and is the version of the algorithm that is **obviously** deterministic, which is precisely what `NFR-05` and the launch bar ask to be demonstrable.

**Side-effect idempotency.** Replay computes **state**. Side effects are emitted only when replay produces a *new* derived entity, and each is guarded by a unique key:

| Side effect | Guard | Source |
|---|---|---|
| Ban cancellation cascade | `ban_instance_id` unique; cascade-executed flag set in the same transaction | `FR-TRS-21` |
| Tournament cascade on Verified Player change | `transition_id` unique, once per transition | `FR-TRN-20` |
| Attendance protection reset | at most one effective protection event per booking | `FR-ATT-21`, `FR-ATT-22` |

**Adoption trigger for snapshot-plus-delta.** Per-user event count p99 exceeds 5,000, or replay p95 exceeds 50 ms. The design is pre-specified in `07 §5.4` so adoption is mechanical.

**Alternatives rejected.**

| Alternative | Rejected because |
|---|---|
| Authoritative mutable counters | Directly forbidden — `FR-TRS-01` and the exclusion list in `Out-of-Scope §7` |
| Snapshot-plus-delta at launch | Adds a snapshot-invalidation rule that a retroactive correction with an **earlier `effectiveAt`** must handle correctly — a real bug class, bought for a performance problem that does not exist yet |
| Replaying side effects along with state | Would re-cancel bookings on every replay, violating `FR-TRS-21` and `FR-TRS-24` |

---

# ADR-008 — Append-only enforced at the data layer

**Status:** ACCEPTED
**Closes `PRD §27.3` open item 8.**

**Context.** `NFR-22` requires append-only behaviour for booking history, trust events, points and wallet ledgers and audit, and proposes data-layer enforcement as an architecture decision. `FR-AUD-04`, `FR-AUD-05`, `FR-PTS-05` and `I6` all depend on it.

**Decision.** Adopt the PRD's proposal.

1. `BEFORE UPDATE OR DELETE` triggers on every append-only table raise an exception.
2. The application database role has `UPDATE` and `DELETE` **revoked** on those tables.
3. Corrections are new rows: compensating ledger transactions, correction trust events, moderation history rows.
4. Migrations run as a separate, more privileged role, so a deliberate schema change is still possible.

**Append-only tables.** `booking_history` · `trust_event` · `points_transaction` · `wallet_transaction` · `audit_event` · `review_moderation_event` · `booking_timer_pause_history` · `operational_interruption_record` · `outbox_event` (insert + terminal state transition only, handled by a narrower rule).

**Consequences.** An ORM `save()` on a history entity fails loudly in development rather than silently rewriting history in production. Retention and archival need an explicit, audited path.

**Alternatives rejected.**

| Alternative | Rejected because |
|---|---|
| Application convention only | The PRD itself flags convention as insufficient; one careless `repository.save()` erases evidence the trust model depends on |
| Full temporal tables / system versioning for every table | Heavier than required; the Product names *which* stores are append-only, and immutability elsewhere is achieved by snapshot columns |
| Event sourcing the whole system | Only Trust requires replay semantics. Event-sourcing bookings would fight the atomic-commit and exclusion-constraint design in `ADR-003` |

---

# ADR-009 — Materialized operating sessions over a rolling horizon

**Status:** ACCEPTED

**Context.** `FR-VEN-17` — default schedule plus weekday overrides, cross-midnight supported. `FR-VEN-18` — at most one session per operating day, may touch but not overlap. `FR-VEN-19` — non-overlap validated cyclically across the full week including the Sunday→Monday wrap. `FR-VEN-21` — DST: non-existent local start times not offered, ambiguous repeated times not offered as duplicates, a declared full-day session stays valid at 23 or 25 real hours. `FR-BOK-05` — the booking interval must fit entirely inside **one** future operating session. `FR-BOK-09` — intake stops 10 minutes before the current session ends. `FR-VEN-22` — booking history stores the operating day and local session bounds as an immutable snapshot.

**Decision.** Generate concrete `operating_session` rows — `(venue_id, operating_day, local_start, local_end, starts_at, ends_at, timezone)` — over a **rolling 120-day horizon**, extended nightly and fully regenerated on any opening-hours change. Availability, intake and tournament windows query these rows. Bookings snapshot the session fields at creation.

**Consequences.**

- DST is resolved once, at generation, with explicit non-existent and ambiguous-time handling, instead of at every query.
- `FR-BOK-05` becomes a containment test against one row.
- `FR-OPS-40`'s optimistic feasibility test becomes a forward scan of future session rows — cheap and deterministic.
- Regeneration is part of the atomic opening-hours change transaction (`FR-VEN-23` step 8), so a session row can never disagree with the template.
- The 120-day horizon is a technical bound, permitted as a consistent UI matter by `FR-MAN-11`. Default 120 days as a deployment setting (`01 §14` A2, decided).

**Alternatives rejected.**

| Alternative | Rejected because |
|---|---|
| Compute sessions from the weekly template on every query | Repeats DST resolution and cyclical-wrap logic in every caller; `FR-VEN-19`'s wrap rule is exactly the kind of logic that must exist once |
| Store only local times and convert on read | `NFR-07` makes absolute instants authoritative for inventory; converting on read invites a DST bug in an inventory path |
| Generate the full calendar indefinitely | Unbounded rows for no benefit; regeneration on an hours change becomes expensive |

---

# ADR-010 — Two-layer inventory: exclusive ledger + non-exclusive blocking windows

**Status:** ACCEPTED

**Context.** `FR-AVL-03` lists what makes a resource interval not free: resource state and restriction, an operational selling block, and an overlapping **blocking reservation** from a booking of either source, a Player Tournament Approval Hold, a Tournament Hold, **Maintenance**, a **Venue Closure**, an **Emergency Closure** or a **Resource Emergency Outage**. Putting all of those in one exclusion-constrained table is impossible, because the Product requires some of them to overlap:

- `FR-VEN-33` — a **closure may overlap existing maintenance**.
- `FR-OPS-08` — an **emergency overlaps and terminates ACTIVE bookings**; that overlap is the cascade.
- `FR-OPS-12` — a resource emergency overlaps and cancels future reservations.
- `FR-AVL-02` — an unresolved emergency is **never** treated as an infinitely extended reservation, and a selling block is evaluated **before** any reservation check.

**Decision.** Two layers.

**Layer 1 — the occupancy ledger (`inventory.resource_reservation`)** *(amended v1.1)*. `reservation_type` ∈ `BOOKING` (online and manual; the Booking row carries the lifecycle state), `LATE_CONFIRM_HOLD`, `TOURNAMENT_APPROVAL_HOLD`, `TOURNAMENT_HOLD`, `MAINTENANCE`, `CLOSURE`. The planned interval is immutable; release is one-way (`active_flag`, `actual_reservation_release_at`, `release_reason`). Active Bookings, holds and Closures may never overlap one another (`ex_no_double_booking`, `I1`); Maintenance may overlap Maintenance and a Closure may overlap Maintenance, and nothing else may overlap Maintenance (cross-class guard).

**Layer 2 — non-exclusive blocking windows and states.** Venue emergency closures and resource emergency outages are stored as declared intervals in `operations.emergency` and evaluated by the predicate **before** reservations. (A Venue Closure is also recorded in `operations.venue_closure`; its per-Resource `CLOSURE` ledger rows are what occupancy checks see.) The venue-level and resource-level **operational selling blocks** are *states*, not intervals: they persist beyond the declared interval until staff resolve the emergency (`FR-OPS-17`, `FR-OPS-18`) and are checked first (`FR-AVL-02` checks 1 and 2).

**Consequences.**

- The exclusion constraint protects exactly the invariant that matters — customer-visible double booking.
- The emergency lifecycle can overlap whatever it needs to terminate, which is the whole point of a cascade.
- `FR-AVL-02`'s eight ordered checks map one-to-one onto the two layers: checks 1–2 are the selling-block states, 3–4 are resource columns, 5–7 are Layer 2 intervals, 8 is Layer 1.
- Maintenance sits in Layer 1 but outside the exclusion constraint, because Product §39.2 allows overlapping Maintenance (`01 §14` A1, resolved).

**Alternatives rejected.**

| Alternative | Rejected because |
|---|---|
| One table for everything with the exclusion constraint | Makes `FR-VEN-33` (closure over maintenance) and `FR-OPS-08` (emergency over active booking) impossible to represent |
| One table with **no** exclusion constraint, all checks in code | Abandons `I1`'s database guarantee — the single most important invariant in the Product |
| Modelling an unresolved emergency as an open-ended reservation row | Explicitly forbidden by `FR-AVL-02`: *"an unresolved emergency is never treated as an infinitely extended reservation"* |

---

# ADR-011 — Owner Password as a per-action credential, never a session

**Status:** ACCEPTED

**Context.** `FR-VEN-02` — required per protected action; there is no 15-minute unlock, no 30-minute unlock and no owner session mode; each action revalidates. `FR-VEN-05` — successes and failures audited; validation rate-limited. `FR-VEN-46` — a venue-initiated change takes effect immediately and the old value stops working at once. `NFR-16` — never cached into a session or unlock window. `Out-of-Scope §3` — no generic owner session or unlock window.

**Decision.** The Owner Password is a field on the **command body** of each protected action, verified against an Argon2id hash on the venue record inside the action's own transaction, immediately before the state change. It is never exchanged for a token, never stored in a session, never cached, never placed in a header that a proxy might log, and never echoed in a response.

**Implementation.** A single `@OwnerProtected` annotation on command handlers, with the protected/unprotected lists generated from `FRS §2.4`, plus an architecture test asserting that every command in the protected list carries the annotation and no command in the unprotected list does. This makes drift between the two lists a build failure rather than a production bug.

**Alternatives rejected.**

| Alternative | Rejected because |
|---|---|
| Short-lived owner token or elevated session | Explicitly forbidden by `FR-VEN-02` and the exclusion list. The requirement is not about convenience — the owner is usually not at the counter (`P3`) |
| A second login for the owner | `FR-VEN-01` — one shared venue account, no separate owner accounts |
| Owner Password in an HTTP header | Headers leak into access logs and proxies far more readily than bodies; `NFR-21` forbids credentials in logs |

---

# ADR-012 — REST + OpenAPI, surface-scoped route groups

**Status:** ACCEPTED

**Context.** Three clients with sharply different capability sets (`FRS §2.10`, `§2.11`, `§3`). `NFR-18` and `NFR-19` require that the *shape of the response* differ by caller — a phone number present for one caller and absent for another; a real error for one caller and a neutral code for another.

**Decision.** REST + JSON under `/api/v1`, split into three route groups — `/api/v1/customer/**`, `/api/v1/venue/**`, `/api/v1/admin/**` — each with its own authentication requirement, its own DTOs and its own projection rules. OpenAPI 3.1 generated from the code and used to generate typed TypeScript clients for all three surfaces.

**Consequences.** Surface-scoped response shaping becomes structural rather than conditional: the venue DTO simply has no field for the customer's other bookings, so it cannot leak one. Generated clients keep enum spellings identical across backend and all three surfaces — which matters because `NFR-23` validates closed enums at write time.

**Alternatives rejected.**

| Alternative | Rejected because |
|---|---|
| GraphQL | The hard problems are write-path atomicity and per-caller field exposure. A flexible field selector makes `NFR-18`/`NFR-19` harder to guarantee, not easier |
| One shared route group with role-based filtering | Filtering the same DTO by role is where privacy leaks live; three DTOs cannot leak into each other |
| gRPC | No browser-native path for two of the three surfaces; the OpenAPI toolchain is a better fit for a small team |

---

# ADR-013 — React Native + React/TypeScript client stack

**Status:** ACCEPTED

**Context.** One small team. Customer app on iOS and Android (`PRD §5.1`). Venue Dashboard on web with full Arabic RTL (`FR-VEN-40`, `NFR-33`). Admin on web, English only. The supplied UI/UX prototypes for all three surfaces are React with a design system, and the Venue Dashboard prototype already carries `dir` handling.

**Decision.** React Native + TypeScript for the Customer App; React + TypeScript + Vite for both web surfaces; one shared generated API client package; one shared enum/code package generated from OpenAPI.

**RTL.** CSS logical properties (`margin-inline-start`, not `margin-left`), `dir="rtl"` on the document root, mirrored icon set, and **no transformation of stored free text** (`FR-VEN-40`). Language resolves venue account preference → browser storage → English default.

**Alternatives rejected.**

| Alternative | Rejected because |
|---|---|
| Flutter | No team Dart experience; the supplied prototypes are React, so the design system would be rebuilt |
| Two native apps | Roughly double the build for a launch MVP with a three-person team |
| Server-rendered dashboard | The Dashboard is a live operational console with an SSE stream and second-level countdowns |

---

# ADR-014 — SSE over `LISTEN/NOTIFY` for the Venue Dashboard

**Status:** ACCEPTED

**Context.** The venue has 10 minutes to answer (`FR-BOK-13`), and `R2` names venue non-response as the main marketplace risk. `FR-NTF-09` requires venue notification of new requests, expiring requests, auto-cancellations, Late Confirm outcomes, tournament events, maintenance reminders and emergency reminders. `FRS §2.12` requires the venue channel to preserve the notification requirement but does not name a transport.

**Decision.** A single SSE stream per authenticated venue account, `GET /api/v1/venue/stream`. The stream carries **change signals only** — `{type, entityId}` — never business payloads. The client refetches the affected resource on signal. Fan-out across API replicas uses PostgreSQL `LISTEN`/`NOTIFY`, so no broker or shared cache is needed. Web Push covers the case where the Dashboard is not open, and every new request plays an audible alert on the open Dashboard (`01 §14` A6, decided).

**Consequences.** No extra infrastructure. A dropped stream is harmless — the browser reconnects and the client refetches. Because payloads are never trusted, `NOTIFY`'s at-most-once nature and 8 kB payload limit are not correctness concerns. A 30-second poll remains as a backstop.

**Alternatives rejected.**

| Alternative | Rejected because |
|---|---|
| Polling only | To feel immediate inside a 10-minute window, polling must be frequent, which is a constant load for a rare event |
| WebSocket | Bidirectional and stateful for a strictly server-to-client signal; commands already have REST endpoints |
| Broker-backed fan-out | Would reintroduce the broker rejected in `ADR-004` for one feature |
| Push notifications only | Venue staff work with the Dashboard open; push alone is unreliable on desktop and adds latency to the critical path |

---

# ADR-015 — Idempotency keys on every commitment-strengthening write

**Status:** ACCEPTED

**Context.** `NFR-04` — a retried run must not double-apply. The Product path is full of retriable moments: a customer on mobile data submitting a booking, a venue accepting under time pressure, a customer accepting a Late Confirmation with 20 seconds left. A duplicate would consume a second commitment slot against the 4-limit (`FR-BOK-03`) or a second reward (`FR-PTS-01`).

**Decision.** Every `POST`/`PATCH` that creates or strengthens a commitment, or moves money or points, requires an `Idempotency-Key` header. The key plus the endpoint plus the authenticated principal is stored in `idempotency_record` with the response body and status, inserted **in the business transaction**. A replay returns the stored response without re-executing.

**Scope.** Booking create · venue accept/reject/reject-after-expiry · Late Confirm initiate/accept/decline · manual booking create · customer cancel · check-in / late-present / no-show · tournament create/approve/join/leave/cancel · every admin remediation · every points, wallet and referral mutation.

**Alternatives rejected.**

| Alternative | Rejected because |
|---|---|
| Natural-key deduplication only | Works for some paths (one PENDING per resource interval) but not for points, cancellations or attendance actions |
| Client-generated business IDs | Lets a client choose a primary key; conflicts with server-assigned UUIDs and audit provenance |
| No idempotency, rely on the user retrying | `FR-BOK-03` and `FR-PTS-01` make a duplicate a real, user-visible harm |

---

# ADR-016 — Three separate closed enums

**Status:** ACCEPTED

**Context.** `FR-AUD-13` — Booking Reason Codes and Booking Events are **two separate closed enums**; a token belongs to exactly one. `FR-AUD-14` — the canonical Booking Event list is twelve members and **no API validation-only token belongs in it**, which is why `MANUAL_BOOKING_PRICING_CONFIGURATION_MISSING` is an error code only (`FR-MAN-10`). `FR-AUD-15` — five tokens are deliberately removed and must not be reintroduced. `NFR-23` — unknown values are rejected at write time.

**Decision.** Three distinct Java types, and three distinct closed `CHECK` lists in the database (`ADR-027`) *(amended v1.1 — not PostgreSQL `ENUM` types)*:

1. `BookingTerminalReason` — explains a terminal booking outcome; stored on the booking; `NOT NULL` when terminal.
2. `BookingEventType` — records an action or a non-terminal failure in append-only history; exactly the twelve of `FR-AUD-14`.
3. `ApiErrorCode` — validation and transport failures; never persisted as a booking outcome.

Plus `TournamentTerminalReason` (the twelve of `FR-TRN-32`) and the removed-token list encoded as a **test**, so reintroducing `BOOKING_EXPIRED`, `RESOURCE_CONFLICT`, `CUSTOMER_SCHEDULE_CONFLICT` or `LATE_CONFIRM_RESOURCE_UNAVAILABLE` fails the build.

**Consequences.** A reason code cannot be written into history as an event, and an error code cannot become a terminal reason. `FR-PTS-16`'s guarantee that **every reachable terminal reason has exactly one fee treatment** becomes an exhaustive enum test rather than a hope.

**Alternatives rejected.** One shared enum for all outcome tokens — the exact conflation `FR-AUD-13` was written to prevent. Free-text reason strings — forbidden by `NFR-23`.

---

# ADR-017 — `NUMERIC(12,2)` money and `timestamptz` time

**Status:** ACCEPTED

**Context.** `FR-BOK-06` — price is `hourlyPrice × nominalBookedMinutes ÷ 60`, rounded half-up to 2 decimals per line item, totals summed from rounded line items, stored amount immutable. `NFR-25` repeats it. `FR-BOK-07` and `NFR-09` — price uses nominal duration, inventory uses real elapsed time, and a DST transition must never change the price. `NFR-07`/`NFR-08` — absolute instants, Africa/Cairo with full DST.

**Decision.**

- Money: PostgreSQL `NUMERIC(12,2)`; Java `BigDecimal` with `RoundingMode.HALF_UP`; currency `EGP` stored alongside for future-proofing. Floating point is banned in money paths by an architecture test.
- Time: every instant is `timestamptz` (UTC). Every local concept stores its local value **and** its IANA zone. `endAt = startAt + nominalBookedMinutes` as real elapsed time; `nominalBookedMinutes` alone drives price.
- Intervals: `tstzrange` half-open `[start, end)`.

**Alternatives rejected.** `DOUBLE`/`FLOAT` for money (rounding drift against an immutable historical amount). Storing money in minor units as `BIGINT` (workable, but `NUMERIC` matches the requirement's own vocabulary and PostgreSQL's `round()` is half-up on `NUMERIC`). Naive `timestamp` without zone (would reintroduce the DST bug class `FR-VEN-21` and `R10` warn about).

---

# ADR-018 — Payment provider as an unused port

**Status:** DEFERRED

**Context.** `FR-PTS-13` — global booking fee is EGP 0, per booking (since PCR-010: per booking or per hour, as the Platform Admin chooses), configurable, displayed as FREE; each booking stores the fee applied at creation. `NFR-38` — enabling a non-zero fee must require **no schema or logic redesign**. `PRD §3.4` and `Out-of-Scope §7` exclude payment collection and settlement at launch. `R11` names scope pressure to add payments as a risk.

**Decision.** Define a `PaymentProvider` port — `authorize`, `capture`, `refund`, `status` — with **no implementation and no caller**. Build everything else now: the fee column on `booking` with its historical snapshot, the closed refund-classification mapping from `FR-PTS-15`/`FR-PTS-16`, the wallet ledger, the points ledger and the compensating-transaction model — and, since PCR-009 and PCR-010, the Points and Wallet paying the fee at booking creation and getting it back on refund (they are internal ledgers, not a provider). Nothing in the booking path imports the port.

**Consequences.** `NFR-38` is satisfied structurally: enabling a fee is a configuration change plus one adapter plus wiring the refund classification, which already exists and is already tested against the full terminal-reason enum. No refactor.

**Alternatives rejected.** Building a payment integration now (`R11` — large schedule and risk increase for no launch value). Omitting the fee fields until needed (would force a schema and history migration, violating `NFR-38` and `FR-AUD-10`).

---

# ADR-019 — PostGIS for distance and map eligibility

**Status:** ACCEPTED

**Context.** `FR-DIS-01` — with location enabled and no manual area selection, show eligible venues across active areas sorted primarily by distance. `FR-DIS-10` — map markers positioned by coordinates under the **same eligibility rules as the list**.

**Decision.** Store venue coordinates as PostGIS `geography(Point,4326)` with a GiST index; compute distance and ordering server-side with the KNN operator. The map provider is a **client-side rendering concern only**.

**Consequences.** List and map are guaranteed to agree, because both read one server-side result set — which is what `FR-DIS-10` requires. A map-provider outage degrades rendering but never changes what is bookable (`PRD §27.1` rates it Medium, not blocking).

**Alternatives rejected.** Haversine in application code (moves sorting and paging into the app, and list/map can drift). Client-side distance sorting (client would need the full venue set, and `FR-DIS-05` eligibility would be client-evaluated). A dedicated geo service (no requirement earns another service; PostGIS is an extension, not infrastructure).

---

# ADR-020 — Spring Data JDBC with explicit SQL on inventory paths

**Status:** ACCEPTED

**Context.** The booking commit path depends on the precise order and content of statements: lock, revalidate, insert reservation, insert booking, insert history, insert deadline, insert outbox — with an exclusion-constraint violation being a **normal, expected** outcome that must surface as a clean domain failure (`ADR-003`, `EC-AVAI-001`).

**Decision.** Spring Data JDBC with hand-written SQL for the Availability, Reservation Ledger, Booking and Tournament write paths. Simple CRUD aggregates — areas, activities, images, configuration, notification records — may use the lighter repository abstractions. No Hibernate second-level cache. No lazy loading in a command path.

**Consequences.** The most important transaction in the system is readable, reviewable and testable as SQL. Every write path opens the database command context first and calls producer functions for producer-only tables (`ADR-026`). `23P01` is caught exactly where it is raised, and guard-trigger exceptions are mapped to `ApiErrorCode` at the same boundary. Range types and exclusion constraints are used directly rather than fought through an ORM mapping.

**Alternatives rejected.** Full JPA/Hibernate everywhere (implicit flush ordering and dirty checking obscure the exact commit that `I1` and `I2` depend on, and range types map poorly). Raw JDBC everywhere (unnecessary boilerplate for the 80% of the model that is plain CRUD). jOOQ (excellent fit technically; rejected only on team familiarity, and recorded as a reasonable future migration).

---

# ADR-021 — Language-neutral backend, translation at the surface

**Status:** ACCEPTED

**Context.** `FR-VEN-40`/`NFR-33` — full Arabic RTL across every Dashboard module, with stored free text unchanged. `NFR-34` — the Customer App is English at launch and localization-ready, with no hard-coded user-facing strings. `FR-ADM-01` — Admin is English only. `FR-NTF-06` — customer copy must distinguish causes, while **internal codes remain authoritative**.

**Decision.** The backend emits **status codes, reason codes, event codes and error codes only** — never a user-facing sentence, in any surface, any channel, or any notification. Each surface owns its message catalogue keyed by code. The Customer App ships an English catalogue with the structure to add Arabic later. Notification bodies are rendered from a template catalogue keyed by code and recipient language, resolved in the notification worker.

**Consequences.** Adding Arabic to the Customer App is a catalogue plus layout work with zero backend change. `FR-NTF-06`'s three distinct cause messages become three codes, so the internal code stays authoritative while copy can be tuned without a deploy.

**Alternatives rejected.** Backend-rendered copy (would put Arabic RTL string handling and three surfaces' tone in the backend, and make `FR-NTF-06` a backend change). Returning both a code and a default English string (the string always ends up rendered, and the catalogue rots).

---

# ADR-022 — Eligibility category as a mandatory command declaration

**Status:** ACCEPTED

**Context.** `FRS §2.3` and `PRD §7.2` define three eligibility categories — A New-Activity Marketplace, B Existing-Commitment Operational, C Internal Venue Operation — and the PRD states plainly: *"Getting the category wrong is the most common source of incorrect behaviour."* The consequences are concrete: `FR-BOK-15` — a SUSPENDED venue may still accept a PENDING booking; `FR-LCF-01` — Late Confirm is category B; `FR-MAN-02` — manual booking is category C and allows INACTIVE and SUSPENDED but never ONBOARDING; `FR-TRN-16` — at a tournament checkpoint, "venue eligible" means operationally able to honour, not eligible for new marketplace activity.

**Decision.** Every command class **must** declare its eligibility category. The venue-eligibility check is generated from that declaration, not written per handler. An ArchUnit test fails the build if any command handler that touches a venue lacks a declaration. The category-to-rule mapping exists in exactly one place, derived from `FRS §2.3`.

**Consequences.** The Product's most frequent source of error becomes a compile-and-build-time concern. A new command cannot silently default to the strictest or the loosest rule. Review has one line to check.

**Alternatives rejected.** Per-handler eligibility checks (the drift the PRD warns about). Inferring the category from the actor (wrong — a venue performs both category B and category C actions, and the difference decides whether a SUSPENDED venue may act).

---

# ADR-023 — Tournament ineligibility cause and pre-start capacity occupancy are separate concepts

**Status:** ACCEPTED

**Context.** `FR-TRN-13` and `FR-TRN-26` make the participant rule explicit: `INELIGIBLE` remains one Participant Status, while its cause is `BAN` or `ACCOUNT_DELETED`. A temporary Ban removes the participant from the Active Participant count but, before `startAt`, may preserve one capacity slot so automatic restoration cannot overfill the Tournament. Account deletion is permanent, releases that slot immediately and has precedence over `BAN`. A Ban that is already ineffective at the exact checkpoint or Start boundary must be treated as expired there even when the worker runs later.

**Decision.**

1. Persist **one status plus one cause**. No `BANNED_PARTICIPANT`, `DELETED_PARTICIPANT` or equivalent Product status is introduced. `ineligible_reason` is `NULL` outside `INELIGIBLE` and is restricted to `BAN | ACCOUNT_DELETED` when `INELIGIBLE`.
2. Define two canonical queries with different purposes:
   - `activeParticipantCount(tournamentId, at)` — checkpoint truth: `JOINED`, account active, no Ban effective at `at`.
   - `capacityOccupiedCount(tournamentId, at)` — join-capacity truth: `JOINED` plus `INELIGIBLE/BAN` whose account is still active and whose automatic restoration right still exists at `at < startAt`. `ACCOUNT_DELETED` never occupies capacity.
3. Ban activation before Start transitions an eligible `JOINED` participant to `INELIGIBLE/BAN` in the same logical Trust reconciliation. Ban expiry/lift before Start restores that row to `JOINED`; restoration consumes the already-reserved slot rather than looking for a new one.
4. Account anonymisation/deletion is an atomic cross-module orchestration: `JOINED` or `INELIGIBLE/BAN` becomes `INELIGIBLE/ACCOUNT_DELETED`, which immediately releases any BAN-only reserved slot. If deletion and Ban compete, deletion wins.
5. Scheduler correctness uses the **Product boundary instant**, not worker time. Before resolving the checkpoint or Start, overdue BAN-only restorations that were effective by that boundary are reconciled first. At or after `startAt`, no automatic restoration occurs. A cancelled Tournament is never resurrected.

**Consequences.** Join capacity cannot be implemented as `COUNT(status='JOINED')`; checkpoint count cannot be implemented as capacity occupancy. Account deletion, Ban activation/end and Tournament boundary handlers all share the same participant-reconciliation contract. The public Product status set remains unchanged.

**Alternatives rejected.** Extra Participant statuses for ban/deletion (would change the Product enum). Treating `INELIGIBLE` as occupying no capacity in all cases (can overfill before an automatic Ban restoration). Counting BAN-only reserved slots toward the checkpoint minimum (would confirm a Tournament with fewer than eight active participants). Evaluating Ban expiry at worker run time (late workers would change Product outcomes).

---




---

# ADR-024 — Customer account deletion uses a durable request plus the existing anonymisation orchestration

**Status:** Accepted  
**Source:** `FR-ACC-10`, PCR-002

**Context.** The Customer must be able to initiate deletion inside the app, while the existing data model preserves legally/operationally required history through anonymisation rather than destructive deletion.

**Decision.** Add `iam.account_deletion_request`. Customer initiation creates or reuses the one unresolved request for the User. It does not mutate historical aggregates. O19 remains the only completion orchestration: Admin/Support completes anonymisation, applies Tournament/Review consequences and atomically closes the request by linking it to `account_anonymisation`.

**Consequences.** The Customer API can satisfy in-app deletion initiation without duplicating or bypassing the existing audited deletion semantics. There is no new account status enum or deletion timer.

---

# ADR-025 — Review reports are abuse-signal records, not Review state transitions

**Status:** Accepted  
**Source:** `FR-REV-11`, PCR-003

**Context.** Customers need an in-product way to report abusive Review content. Existing Review moderation is already a closed audited state machine.

**Decision.** Add `review.review_report` with a controlled one-time resolution. Submission records reporter, Review, reason/details and correlation metadata, but never changes Review state or rating contribution. O20 resolves the report; a resolution may link an existing `review_moderation_event` when Admin separately performs a valid moderation action.

**Consequences.** Reporting cannot become a hidden moderation shortcut. Venues remain view-only; Admin moderation remains governed by the existing Review state machine.

---

# ADR-026 — Database enforcement model: the database owns invariants, the application owns decisions

**Status:** ACCEPTED
**Source:** `I1`–`I7`, `NFR-01`–`NFR-05`, `NFR-22`, `NFR-23`, `NFR-26`; physical model v1.1.x

**Context.** The physical model hardens every module with guard triggers, deferred completeness checks, private-write producers and a shared command context. v1.0 of this pack said "business logic lives in the application, not in stored procedures". Without a stated split, the same rule could be implemented twice and drift, or not at all.

**Decision.**

1. **The database owns invariants** that must hold whatever code path writes: exclusion constraints; state-machine graphs, permitted reasons per source state and terminal immutability (Booking, Tournament, Participant, Review, Emergency, Ban, Verified Player, Late Confirmation); closed value sets; snapshot immutability; append-only history and ledgers; Product-fixed deadline arithmetic stored on rows; commit-time completeness of cascades (deferred constraint triggers); anonymisation completeness.
2. **The application owns decisions**: the single canonical availability predicate (`01 §15` rule 1), eligibility, which transition to take and which reason under the frozen priority, cascade orchestration, notifications, localisation. Where the database re-checks an eligibility fact (e.g. Venue `ACTIVE` at Online Booking creation), it is an assertion of the same rule, never a second definition; a mismatch is a defect in one of them.
3. **Command protocol.** Every business write runs in one `READ COMMITTED` transaction that opens a command context (`tournament.begin_command`, `admin.begin_command`, `trust.begin_command`, `loyalty.begin_command`), which takes all row locks in one fixed order and fixes the command instant. Producer-only tables are written through their producer functions using private write tokens (`04 §2`).
4. Guard exceptions are programming or race outcomes. The API layer maps them to `ApiErrorCode`; they are never shown as prose.

**Consequences.** A forgotten rule in Java is refused at commit. Java tests exercise flows; database tests (`docs/04-erd/physical/test_*.sh`) exercise invariants. Changing a Product rule means changing both the Java decision and, where it is an invariant, the migration — in the same change.

**Alternatives rejected.** Invariants only in Java (fails `R1`: correctness depends on every path remembering). All logic in stored procedures (flows become untestable and unreviewable in the team's main language).

---

# ADR-027 — Physical layout: one schema per module, closed `CHECK` lists, the `.dbm` is the identifier authority

**Status:** ACCEPTED
**Supersedes:** the "single schema `e7gz`" and "PostgreSQL `ENUM` types" statements of Data Model v1.0

**Decision.**

1. One PostgreSQL schema per owning module: `iam`, `geo`, `venue`, `resource`, `pricing`, `inventory`, `operations`, `booking`, `attendance`, `tournament`, `trust`, `loyalty`, `review`, `notification`, `config`, `audit`, `admin`, `technical` (`04 §3`).
2. Closed Product value sets are `text` columns with a closed `CHECK` list spelled exactly as the FRS spells them. Adding a member is a migration **and** a Product Change Request.
3. The pgModeler model in `docs/04-erd/physical/` is the authority for schema, table, column, constraint, index and trigger names; `04` is generated from it. Other documents cite those names.

**Consequences.** Module ownership is visible in every qualified name and privileges can be granted per schema. `CHECK` lists are visible in the model and diff cleanly in migrations; `ENUM` alteration restrictions disappear.

**Alternatives rejected.** Single schema (ownership only by convention). PostgreSQL `ENUM` types (cannot remove a member; awkward to model in pgModeler round-trips).

---

# ADR-028 — Versioned platform configuration for Product-configurable values

**Status:** ACCEPTED
**Source:** `NFR-37`, `FR-ADM-10`, `FR-ADM-11`, `FR-PTS-01`, `FR-PTS-07`

**Decision.** Product-configurable values live in `config.configuration_key` (registry with type, bounds, owner module), `config.configuration_version` (append-only, future-only `effective_from`, Admin and reason) and `config.system_configuration` (current value, always equal to the latest version). Loyalty keys change only through `loyalty.configure`; all other keys through `config.change_configuration`. Consumers snapshot the value effective at the Product's anchor instant (`config.effective_numeric(key, at)`), and Booking/Tournament creation guards check fee snapshots against it.

Launch keys: `booking.fee` = 0, `tournament.creation_fee` = 0, `loyalty.attended_points` = 200, `loyalty.referral_points` = 1000, `loyalty.wallet_refunds_enabled` = false. Everything else in Product §56 is currently held as a Product rule in code and constraints (`11 §7`), confirmed by the Product Owner (`01 §14` A8, decided 26 Sep 2026). A value made configurable later is added as a key without redesign.

**Consequences.** A fee change never rewrites history; an audit and an outbox event are written with every change.

---

# ADR-029 — Venue Dashboard and Platform Admin keep the refresh token in an HttpOnly cookie

**Status:** ACCEPTED (Product Owner decision, 2 Oct 2026 — Sprint 0 security review S-4; implemented by E7-22)
**Source:** `Security §2.3`, `Security §1` S6, `09 §2`

**Decision.** For the two browser surfaces (audiences VENUE and ADMIN) login and refresh return only the access token in the body and set the refresh token as an `HttpOnly; Secure; SameSite=Strict` cookie on `/api/v1/{venue|admin}/auth`, lasting the audience's refresh lifetime. Refresh and logout read the cookie (no body), require an allowed `Origin` and `X-Requested-With: e7gz`, and clear the cookie on logout or any refusal. CORS allows exactly the configured web origins, with credentials. The Customer mobile app keeps the token pair in the JSON body and in device secure storage.

**Why.** A 7-day venue token or a 12-hour admin token in `localStorage` turns any XSS into account takeover. An HttpOnly cookie cannot be read by script; `SameSite=Strict` plus the Origin and custom-header check stop cross-site use of it.

**Consequences.** The Dashboard/Admin and the API must share a registrable domain (e.g. `venue.e7gz.ps` and `api.e7gz.ps`) for a `SameSite=Strict` cookie to be sent. Each deployment sets `E7GZ_CORS_ALLOWED_ORIGINS`; empty means no browser origin is allowed. Rotation and reuse detection are unchanged.

---

*End of ADR Log v1.1. Continue to `03 — Domain Module Specification.md`.*
