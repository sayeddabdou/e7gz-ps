# E7GZ PS — project rules

Read this file at the start of every session. It is the contract between you and this codebase.

---

## 1. What this is

E7GZ PS is a booking marketplace for gaming and leisure venues in Cairo — PlayStation rooms, billiards and ping pong tables. Three surfaces against **one shared inventory**:

| Surface | Stack | Language at launch |
|---|---|---|
| Customer Mobile App | React Native + TypeScript | English, localization-ready |
| Venue Dashboard | React + TypeScript + Vite | English LTR **and** Arabic RTL |
| Platform Admin | React + TypeScript + Vite | English only |
| Backend | Java 21 + Spring Boot 3 | codes only, never prose |
| Database | PostgreSQL 16 + `btree_gist` + `postgis` | — |

No payments at launch. The booking fee is EGP 0. The customer pays the venue directly, outside E7GZ.

---

## 2. Authority — this is the most important section

1. `docs/01-product/` — **approved Product behaviour.** 356 functional requirements. Frozen.
2. `docs/02-architecture/` — **how we implement it.** 15 documents.
3. The code.

**You may never invent Product behaviour.** If the requirement docs do not answer a question, that is a Product Change Request — **stop and ask the user**. Do not choose a reasonable-sounding default. Specifically, never add:

- a new status, state or transition
- a new reason code, booking event or `detailCode`
- a new timer, deadline or window
- a new authorization rule or eligibility predicate
- a new member to any closed enum

`docs/_archive-superseded/` contains v4.x-era material that is **superseded and wrong**. Never read it, never cite it, never resolve an ambiguity against it.

---

## 3. The seven invariants

Every line of code is answerable to these. A change that weakens one is rejected, even if it makes a test pass.

| # | Invariant |
|---|---|
| **I1** | Two active reservations never overlap on one resource — enforced by a **database exclusion constraint**, not by application code |
| **I2** | Every commitment-strengthening transition revalidates and commits in **one atomic transaction**; first committer wins |
| **I3** | Every terminal booking and tournament carries **exactly one** reason code naming the real cause |
| **I4** | Multi-entity cascades are **all-or-nothing** and persist completely **before** any notification dispatches |
| **I5** | Trust state is **derived** by deterministic replay of an immutable ordered event stream — never an authoritative counter |
| **I6** | Historical values — prices, fees, rewards, attendance, session snapshots — are **never rewritten**; corrections are compensating entries |
| **I7** | Every protected venue action validates the Owner Password **freshly, per action** — no session, no unlock window, no cache |

---

## 4. Ten rules that are not negotiable

1. **One availability predicate.** There is exactly one implementation of the eight ordered checks, in the Availability module. No flow re-implements, shortcuts or caches around it at commit time.
2. **The database is the authority on occupancy.** Application checks are an optimisation; the exclusion constraint is the guarantee.
3. **Atomic means one transaction in one process.** If a design needs a saga for something the Product calls atomic, the design is wrong.
4. **Nothing external inside a transaction.** No SMS, push, email, map, object-store or payment call between `BEGIN` and `COMMIT`.
5. **History is append-only, enforced by the database.** No `UPDATE` or `DELETE` on an append-only table, ever.
6. **Owner Password is never a session.** Per action, in the request body, hashed, audited, rate-limited.
7. **The backend emits codes, not prose.** Not one user-facing sentence ships from the backend, in any channel.
8. **Server time is the only time.** Deadlines are persisted instants. Client countdowns are decoration.
9. **Every command declares its eligibility category** — A, B or C — and the venue check is generated from that declaration.
10. **Never introduce an excluded Product concept** because a framework makes it easy. See §8.

---

## 4b. Screaming architecture — the folder rule

Open this project and it screams **"venue booking system"**, not "Spring Boot app". The top level is named after the business:

```
src/main/java/ps/e7gz/
  booking/ availability/ reservation/ venue/ resource/ pricing/
  lateconfirm/ attendance/ operations/ tournament/ trust/ loyalty/
  review/ identity/ notification/ admin/
  platform/          <- framework plumbing only, kept out of the way
```

**There is no `controllers/`, `services/` or `repositories/` at the top level, ever.** Those names describe the framework, not the product. Inside each business folder:

```
booking/
  api/              HTTP endpoints and DTOs
  domain/           the business rules — NO Spring, NO database, NO annotations
  application/      use cases wiring rules to data
  infrastructure/   database, framework, adapters
```

Four rules, enforced by ArchUnit:

1. `domain/` imports nothing from Spring, JDBC or any framework. The rules must be readable without knowing the stack.
2. A business folder owns its tables and is the only writer to them.
3. Cross-folder reads go through the other folder's `api/` package — never a SQL join across a boundary, never an import of another folder's `domain/` or `infrastructure/`.
4. Dependencies point downward through the layers in `docs/02-architecture/03 — Domain Module Specification.md` §2.1. Never upward.

When you add something new, ask "what part of the business is this?" and put it there. If the honest answer is "it's a Spring thing", it belongs in `platform/`.

---

## 5. Conventions

**Naming.** Database: `snake_case`, singular tables. Java: `ps.e7gz.<business area>` with `api` / `domain` / `application` / `infrastructure`. JSON: `camelCase`, spelled exactly as the FRS spells it (`startAt`, `lateConfirmExpiresAt`, `businessDate`).

**Enums.** `SCREAMING_SNAKE_CASE`, spelled exactly as the FRS spells them. `ABSENT_NO_SHOW` is the attendance result; `NO_SHOW` is the booking status. They are different types and are never interchanged.

**Three separate closed enums** that must never share a member:
- `BookingTerminalReason` — explains a terminal booking
- `BookingEventType` — exactly the 12 of `FR-AUD-14`
- `ApiErrorCode` — validation and transport failures only

`MANUAL_BOOKING_PRICING_CONFIGURATION_MISSING` belongs **only** to `ApiErrorCode`.

**Time.** Every instant is `timestamptz` in UTC. Local concepts carry their IANA zone — `Africa/Cairo`. `Instant.now()` is banned; use the injected `Clock`.

**Money.** `NUMERIC(12,2)` and `BigDecimal` with `RoundingMode.HALF_UP`, currency EGP. Floating point is banned in money paths.

**Ranges.** Half-open `[start, end)`. Every deadline: `now < deadline` permits, `now >= deadline` expires.

**Idempotency.** Every commitment-strengthening, money-moving or points-moving write requires an `Idempotency-Key`.

---

## 6. Where the answer lives

| Question | Document |
|---|---|
| What is the Product behaviour? | `docs/01-product/…FRS v1.1…` §4 — the 356-requirement catalogue |
| What are the state machines, timers, authorization? | same file, §2 matrices |
| Why is the architecture shaped this way? | `docs/02-architecture/01 — Technical Architecture.md` |
| Why this technology and what was rejected? | `02 — Architecture Decision Record Log.md` |
| Which module owns this? | `03 — Domain Module Specification.md` |
| What is the schema? | `04 — Data Model & Database Design.md` |
| What does the schema look like? | `docs/04-erd/` — browsable ERD, DBML, per-module diagrams |
| How does availability and concurrency work? | `05 — Availability & Reservation Engine.md` |
| How does a booking or tournament move state? | `06 — Booking & Tournament Lifecycle Engine.md` |
| How does trust, attendance or banning work? | `07 — Trust, Attendance & Enforcement Engine.md` |
| How do timers, the outbox and events work? | `08 — Scheduling, Timers & Event Architecture.md` |
| What is the endpoint and its error code? | `09 — API Architecture & Contract Map.md` |
| Auth, Owner Password, privacy, audit? | `10 — Security Architecture.md` |
| Performance budgets, metrics, alerts? | `11 — Non-Functional & Observability Architecture.md` |
| Deployment, environments, backup? | `12 — Deployment, Environments & Operations.md` |
| Which requirement does this code serve? | `13 — Traceability Matrix.md` |
| What do we build next? | `14 — Implementation Roadmap.md` and `/BUILD-PLAN.md` |
| What does the screen look like? | `docs/03-design/` |

---

## 7. Definition of done for any task

1. The behaviour matches the cited `FR-` requirement **exactly** — no more, no less.
2. Tests cover the happy path **and** the boundary at the deadline and ±1 second.
3. The relevant edge cases from `docs/01-product/Error  Edge Cases.md` are tested.
4. No invariant in §3 is weakened.
5. The code cites its `FR-` IDs in a comment on the class or method.
6. Constraint-level enforcement is in the migration, not only in Java.
7. `./gradlew check` passes, including the ArchUnit rules.

---

## 8. Never add these

From the approved exclusion list. They are not missing features; they are deliberate exclusions.

PC gaming · Kick Off · favorites · community feed · Elo · leaderboards · match history · tournament brackets · tournament scoring · per-match scheduling · payment collection · prize settlement · food and drink ordering · venue POS · inventory · supplier management · payroll · employee attendance · individual cashier accounts · venue manager accounts · separate owner accounts · full accounting · tax accounting · multi-branch venues · Arabic customer UI · customer self-service account delete · an active customer wallet screen · auto-accept for bookings · back-dated manual bookings · venue staff reversal of attendance · a "Run Anyway" tournament override · an admin booking-creation path.

---

## 9. Known failure modes — do not do these

These are the specific wrong turns this codebase is vulnerable to.

| Tempting shortcut | Why it is wrong |
|---|---|
| Dropping or relaxing the exclusion constraint to make a test pass | That constraint **is** invariant I1. Fix the test |
| Reading the Nearest Availability projection on a commit path | It is a snapshot. Commit paths call the live predicate |
| Turning Owner Password into a token or a 15-minute unlock | Explicitly forbidden by `FR-VEN-02` |
| Adding an enum member to represent a new failure | Use an `ApiErrorCode`, or stop and ask |
| Making a cascade eventually consistent to simplify it | `NFR-03` forbids partial cascades |
| Having replay cancel bookings directly | Replay is **pure**. The reconciler emits side effects, guarded by unique keys |
| Returning a user-facing message from the backend | Codes only |
| Computing a deadline at read time | Deadlines are persisted instants |
| Using `Instant.now()` | Use the injected `Clock` or boundary tests become untestable |
| Inferring one status domain from another | Fourteen independent domains. Marketplace status never implies account status, booking status never implies attendance result |
| "Simplifying" `VENUE_RESPONSE_EXPIRED` into a terminal state | It is **non-terminal** and revivable until `startAt` |

---

## 10. Commands

```bash
docker compose up -d            # postgres + minio + mailhog
./gradlew flywayMigrate         # apply migrations as e7gz_migrator
./gradlew test                  # unit + integration (Testcontainers)
./gradlew check                 # + ArchUnit boundary and convention rules
./gradlew concurrencyTest       # the N-threads-one-slot pack — run before every commit to inventory
./gradlew openApiGenerate       # OpenAPI spec + TypeScript clients for all three surfaces
```

---

## 11. Working style in this repo

- **One ticket per session.** `/BUILD-PLAN.md` defines them, grouped into sprints. Do not drift outside the current ticket.
- **Read the named documents before writing code.** Not the whole pack — the ones the package names.
- **When the spec is silent, stop and ask.** Do not pick a sensible default.
- **Write the failing test first** for anything touching an invariant.
- **Commit per work package**, with the `FR-` IDs in the commit message.
