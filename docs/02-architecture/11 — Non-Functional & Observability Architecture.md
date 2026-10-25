# E7GZ PS
# NON-FUNCTIONAL & OBSERVABILITY ARCHITECTURE v1.1

**Updated:** 25 September 2026 — names and mechanics aligned to physical model v1.1.27 (`04`, `ADR-026`–`ADR-028`).

How each of the 38 non-functional requirements is delivered, measured and alerted on.

---

# 1. CORRECTNESS AND CONSISTENCY

| NFR | Requirement | Mechanism | Verified by |
|---|---|---|---|
| `NFR-01` | Every commitment-strengthening transition revalidates and mutates in **one atomic transaction**; partial commits never acceptable | Modular monolith, one database, one transaction per command (`ADR-001`) | Fault injection mid-transaction leaves no partial state |
| `NFR-02` | First successfully committed valid transition wins; the loser revalidates | `READ COMMITTED` + exclusion constraints as the authority (`ADR-003`) | Concurrency pack: N threads → 1 success, N−1 clean failures |
| `NFR-03` | Multi-entity cascades are all-or-nothing and persist completely **before** notifications dispatch | One orchestration transaction per cascade (`03 §2.4`) + transactional outbox (`ADR-004`) | Fault injection in each of O5–O10, O14–O16 |
| `NFR-04` | Scheduled transitions fire reliably and idempotently; a retried run never double-applies | Durable deadline table, leases, natural idempotency keys (`ADR-006`) | Double-fire and crash-mid-fire tests |
| `NFR-05` | Trust replay is deterministic; as-of replay is reproducible | Pure `replay` over `(effectiveAt, eventSequence)` (`ADR-007`) | Seeded-history replay reproduces stored state exactly |
| `NFR-06` | Idempotency keys for every once-only cascade | `ban_instance_id`, `transition_id`, one protection event per booking — all **unique constraints** (`04 §9`) | Double-replay test never cancels twice |

**The architectural position:** every one of these six is enforced at the data layer or by transaction structure, not by developer discipline. That is deliberate — `R1` calls a customer-visible double booking existential, and an invariant that depends on nobody ever taking a wrong path is not an invariant.

---

# 2. TIME CORRECTNESS

| NFR | Requirement | Mechanism |
|---|---|---|
| `NFR-07` | All scheduling and availability logic uses **absolute instants**; local wall-clock is presentation only | every stored instant is `timestamptz`; operating sessions materialized into absolute ranges (`ADR-009`) |
| `NFR-08` | Africa/Cairo IANA with full DST governs sessions, intake windows and every timer | the zone is stored on the venue and on every session row; DST resolved once at generation |
| `NFR-09` | Price uses **nominal duration**; `endAt = startAt + nominalBookedMinutes` in real elapsed time; a DST transition must never change the price | `booking.nominal_booked_minutes` drives money; `start_at`/`end_at` drive inventory (`04 §4.3`) |
| `NFR-10` | Boundaries are strict — `now < deadline` permits, `now >= deadline` expires; all timers server-authoritative, client countdowns display only | deadlines are persisted instants; the API returns instants, never remaining seconds (`09 §1.2`) |

**DST test cases that must be in the suite** (`FR-VEN-21`, `R10`): a non-existent local start time is not offered; an ambiguous repeated local time is not offered as a duplicate slot; a declared full-day session stays valid when real elapsed time is 23 or 25 hours; a booking priced across a transition keeps the same price.

**Clock discipline.** One injected `Clock` bean — `Instant.now()` is banned by an ArchUnit test. Workers compare against the database clock. NTP on every host; skew above 500 ms alerts.

---

# 3. PERFORMANCE BUDGETS

`NFR-11`, `NFR-12`, `NFR-13`, `NFR-14` and `NFR-15` are marked **TBD / proposed non-product** in the PRD. They are adopted here as engineering budgets, with the stated targets.

| Path | Budget | Mechanism | NFR |
|---|---|---|---|
| Venue list with Nearest Availability, one area | **p95 < 1 s** | `venue_activity_availability` projection + one indexed query + PostGIS KNN (`ADR-005`, `ADR-019`) | `NFR-11` |
| Booking creation | **p95 < 2 s** end to end | single-database commit, no external call inside the transaction | `NFR-12` |
| Venue accept | **p95 < 2 s** | same | `NFR-12` |
| Late Confirm initiate / accept | **p95 < 2 s** | same | `NFR-12` |
| Push dispatch after commit | **p95 < 3 s** | `pg_notify` wake, no poll delay (`ADR-004`) | `NFR-13` |
| Scheduled transition firing | **p95 within 2 s of due** | 1-second tick + `NOTIFY` wake for near-term rows | `NFR-14` |
| SSE signal to the Dashboard | **p95 < 1 s** after commit | `LISTEN/NOTIFY` fan-out (`ADR-014`) | `FR-NTF-09` |

**Scale headroom** (`NFR-15`). Launch is one city with a manually onboarded venue set (`PRD §27.2`). The design carries 10× without architectural change because: the app tier is stateless and scales horizontally; every hot query is a bounded index scan; the only global write contention is per resource, and the exclusion constraint localises it. The first thing to hit a limit would be database write throughput, and the first response is a larger instance, not a new architecture.

**Load testing at launch.** Sustained 10× projected peak for 30 minutes, with the concurrency pack running throughout; the invariant sweep must find zero overlapping reservations during and after.

---

# 4. DATA INTEGRITY

| NFR | Requirement | Mechanism |
|---|---|---|
| `NFR-22` | Append-only stores are append-only **in behaviour**; the PRD proposes data-layer enforcement | Adopted — triggers + revoked privileges (`ADR-008`, `04 §1 P7`) |
| `NFR-23` | Closed enums validated at write time; unknown values rejected | `text` columns with closed `CHECK` lists (`04 §1` P5, `ADR-027`) |
| `NFR-24` | `detailCode` validated against the **event-specific** closed set and immutable once recorded | `ck_booking_history_detail_code_scope` + append-only `booking_history` |
| `NFR-25` | Money stored and computed at 2 decimals, half-up; totals are sums of rounded line items | `NUMERIC(12,2)` domain + `BigDecimal HALF_UP`; floats banned by an ArchUnit test |
| `NFR-26` | Every terminal booking and tournament carries exactly **one** reason code; zero or two is a data-integrity defect | `ck_booking_terminal_reason`, `ck_tournament_terminal_reason` + a nightly integrity sweep |

**Nightly integrity sweep** — a scheduled job that raises Sev-1 on any row found:

```sql
-- overlapping active occupancy on one resource  (NFR-28 — must be zero, always)
SELECT a.id, b.id, a.resource_id FROM inventory.resource_reservation a
JOIN inventory.resource_reservation b
  ON a.resource_id = b.resource_id AND a.id < b.id
 AND tstzrange(a.actual_reservation_start, COALESCE(a.actual_reservation_release_at, a.planned_end), '[)')
  && tstzrange(b.actual_reservation_start, COALESCE(b.actual_reservation_release_at, b.planned_end), '[)')
WHERE NOT (   (a.reservation_type = 'MAINTENANCE' AND b.reservation_type IN ('MAINTENANCE','CLOSURE'))
           OR (b.reservation_type = 'MAINTENANCE' AND a.reservation_type IN ('MAINTENANCE','CLOSURE')));

-- terminal booking without a reason  (NFR-26)
SELECT id FROM booking.booking
WHERE status IN ('REJECTED','CANCELLED') AND terminal_reason IS NULL;   -- NO_SHOW carries the NO_SHOW_RECORDED event, not a reason

-- trust projection out of step with replay  (NFR-05)
-- (recomputes replay for users whose last event postdates last_replayed_event_sequence)

-- sellable gap: a resource unusable with no active restriction  (FR-OPS-21)
SELECT r.id FROM resource.resource r
JOIN operations.emergency e ON e.resource_id = r.id AND e.status IN ('ACTIVE','AWAITING_CONFIRMATION')
LEFT JOIN resource.resource_selling_block b ON b.resource_id = r.id AND b.active
WHERE b.id IS NULL;

-- Tournament participant cause/capacity integrity  (FR-TRN-13, FR-TRN-26, ADR-023)
-- 1) INELIGIBLE must carry BAN or ACCOUNT_DELETED; all other statuses carry no cause.
-- 2) a deleted/anonymised account must never remain INELIGIBLE/BAN.
-- 3) capacityOccupiedCount(now) must never exceed tournament.capacity.
```

---

# 5. OBSERVABILITY

## 5.1 The three signals

| Signal | Tooling | Retention |
|---|---|---|
| **Structured logs** — JSON, one line per request or worker unit, with `correlationId`, principal type, endpoint, outcome code, duration | any JSON log sink | 30 days hot, 1 year cold |
| **Metrics** — Micrometer → Prometheus-compatible | counters, gauges, histograms | 13 months |
| **Traces** — OpenTelemetry, sampled; always-on for cascade orchestrations | any OTLP backend | 7 days |

**`correlationId` is the spine** (`NFR-32`). It enters at the edge, flows through the command, into every audit record, every outbox row, every notification and every log line. A whole emergency cascade is one query in three systems.

## 5.2 Business metrics — instrumented from day one (`NFR-27`)

The PRD requires **every** metric in its `§4.2` to be measurable at launch. They are emitted as application metrics and are also derivable from the database, so a metrics outage never loses the number.

**Marketplace liquidity (G1)**

| Metric | Source |
|---|---|
| Booking request rate per active customer per week | `booking` where `source='ONLINE'` |
| Request-to-confirmed rate | CONFIRMED ÷ all online requests |
| Time to venue response — median seconds PENDING → accept/reject | `booking_history` |
| **Venue response expiry rate** | `VENUE_RESPONSE_EXPIRED` ÷ all requests — the direct measure of venue responsiveness (`R2`) |
| **Late Confirm recovery rate** | revived to CONFIRMED ÷ all `VENUE_RESPONSE_EXPIRED` |
| Search-to-book conversion | requests ÷ venue detail views |

**Inventory integrity (G2)**

| Metric | Source |
|---|---|
| **Double-booking incidents — target zero, any occurrence is Sev-1** | the nightly sweep and a constraint-violation counter (`NFR-28`) |
| Manual booking share | `source='MANUAL'` ÷ all |
| Resource utilisation | reserved resource-hours ÷ open resource-hours |

**Outcome truthfulness (G3)**

| Metric | Source |
|---|---|
| Terminal reason distribution, split customer / venue / operational / platform | `booking.terminal_reason` grouped by a cause-category mapping |
| Operationally caused cancellation rate | the four operational and platform buckets ÷ all terminal |
| **Unreasoned terminal bookings — target zero** | the integrity sweep |

**Trust health (G4)**

| Metric | Source |
|---|---|
| Attendance mix across the five results | `attendance_record.result` |
| **Check-in capture rate** — `ATTENDED` ÷ bookings that reached ACTIVE | surfaced **per venue** (`R3`) |
| No-show rate; ban incidence by stage | `trust_event`, `ban_instance` |
| **Admin correction rate** — corrections ÷ attendance events; a rising rate signals an operational problem, not a customer problem | `booking_history` |

**Venue autonomy and platform load (G5, G6)**

| Metric | Source |
|---|---|
| Emergency declarations per venue per month, by type | `emergency` |
| **Emergencies left in AWAITING_CONFIRMATION past expected end** | `emergency` — measures forgotten operational blocks (`NFR-30`) |
| Resources entering `OWNER_REVIEW_REQUIRED` | `owner_review` |
| Admin interventions per 100 bookings | `audit_event` |

**Engagement**

| Metric | Source |
|---|---|
| Verified Player conversion | `trust_projection` |
| Tournament fill rate — passing checkpoint ÷ created | `tournament` |
| Review coverage | `review` where `status='ACTIVE'` |
| Open Review reports | `review_report` where `resolved_at IS NULL` |
| Open Account Deletion requests | `account_deletion_request` where `completed_at IS NULL` |
| Referral conversion — REWARDED ÷ created | `referral` |

## 5.3 Technical metrics

| Area | Metrics |
|---|---|
| HTTP | rate, error rate by code, latency histogram per route group |
| Database | connection pool saturation, slow queries, lock waits, **`23P01` exclusion-violation rate** (expected, but a spike means contention) |
| Deadline worker | queue depth, **firing lag** (`fired_at − due_at`), leases held, retries, `FAILED` count |
| Outbox | pending depth, **dispatch lag**, per-channel success rate, `DEAD_LETTER` count |
| Notifications | per-channel delivery success, provider latency, invalid-token pruning |
| SSE | open streams, disconnect rate |
| Projections | refresh lag per projection |
| Providers | SMS / push / email success rate and latency |

## 5.4 Alerting

| Severity | Condition | NFR |
|---|---|---|
| **Sev-1** | any overlapping active reservation detected | `NFR-28` |
| **Sev-1** | any `SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR` — carries the full diagnostic payload | `NFR-29`, `FR-BOK-28` |
| **Sev-1** | a terminal booking with zero or two reason codes | `NFR-26` |
| **Sev-1** | trust projection diverges from replay | `NFR-05` |
| **Sev-1** | a sellable gap detected | `FR-OPS-21` |
| **Sev-1** | Tournament Participant cause/capacity invariant violated — deleted account still `INELIGIBLE/BAN`, invalid cause shape, or capacity occupancy above capacity | `FR-TRN-13`, `FR-TRN-26`, `ADR-023` |
| **Sev-2** | outbox dispatch lag > 30 s, or any `DEAD_LETTER` | `NFR-13` |
| **Sev-2** | deadline firing lag > 60 s, or any `FAILED` transition | `NFR-14` |
| **Sev-2** | SMS provider success rate < 95 % over 15 min | `R8` |
| **Sev-2** | push provider success rate < 95 % over 15 min | `R9` |
| **Sev-3** | an emergency in `AWAITING_CONFIRMATION` past its expected end | `NFR-30`, `FR-OPS-25` |
| **Sev-3** | a resource under owner review for more than 24 h | `NFR-30` |
| **Sev-3** | clock skew > 500 ms on any host | `NFR-10` |

## 5.5 Operational notifications that are Product, not ops (`NFR-30`, `FR-OPS-25`)

These go to the **venue, the owner and Platform Admin** through the notification system, not to an engineering pager:

- an expected end is reached with the emergency still active
- a return-to-service time is reached with the outage still active
- manual confirmation is required
- a resource has entered `OWNER_REVIEW_REQUIRED`

The purpose is stated in the requirement: **so operational blocks are not forgotten.**

## 5.6 Abuse signals — surfaced, never acted on (`NFR-31`)

Computed by the projection worker and written **only** to `admin_alert`:

check-in rate · check-in reversal rate · Verified Player progress concentration by venue · check-ins immediately before the 15-minute deadline · Late Present rate · repeated Late Present on the same users · Late Present reversals · suspicious repeated customer/venue patterns · ≥3 tournament cancellations by resource emergency at one venue in a rolling 30 days.

**Architectural guarantee:** the signal pipeline has **no write path** into Trust, Venue status or Booking. An ArchUnit test enforces it. Every consequence requires explicit admin action (`FR-ATT-28`).

---

# 6. LOCALIZATION AND ACCESSIBILITY

| NFR | Requirement | Mechanism |
|---|---|---|
| `NFR-33` | Venue Dashboard supports full Arabic RTL across every module in `FR-VEN-40`; **stored free text is never transformed** | `dir="rtl"`, CSS logical properties, mirrored icons; the backend stores and returns free text byte-identical (`ADR-021`) |
| `NFR-34` | Customer app is English at launch and **localization-ready**: no hard-coded user-facing strings, layouts tolerate RTL mirroring | all copy in a catalogue keyed by backend code; logical properties in layout |
| `NFR-35` | All currency is EGP, formatted consistently across the three surfaces | money is a `{amount, currency}` pair in every payload; one shared formatter package |
| `NFR-36` | Interactive elements meet common mobile accessibility expectations — touch target, contrast, screen-reader labelling | design-system tokens with minimum 44×44 targets and WCAG AA contrast; labels on every control |

**Language resolution** on the Dashboard (`FR-VEN-40`): venue account preference → browser storage → English default.
**Arabic Customer UI is explicitly out of scope at launch** (`PRD §3.4`) — the readiness is structural, not a shipped locale.

---

# 7. CONFIGURABILITY

| NFR | Requirement | Mechanism |
|---|---|---|
| `NFR-37` | Every value in v5.0 §56 is configurable unless stated otherwise, and every change applies to **future events only** | Configurable values are exactly the registered `config.configuration_key` rows (table below); `config.system_configuration` + append-only `config.configuration_version` with `effective_from`; consumers **snapshot** the value at the moment the requirement names (`03 §17.3`) |
| `NFR-38` | Enabling a non-zero booking fee requires **no schema or logic redesign** | fee column and fee modes, refund classification, wallet and points ledgers (including Points and Wallet paying the fee, PCR-009/PCR-010) all built and tested at launch; `PaymentProvider` port defined with **no caller** (`ADR-018`) |

**What is configuration, and what is not.**

| Configurable (`config` keys, `ADR-028`) | **Not** configurable — Product rules |
|---|---|
| Global booking fee (EGP 0) | The no-show ladder: 3→7 days, 2→1 month, 2→2 months, recurring every 3→2 months (`FR-TRS-13`) |
| Tournament creation fee (EGP 0) | The ten-minute venue response window |
| Completed-booking points (200) | The five-minute Late Confirm window |
| Referral reward (1000) | The 15-minute lead time and check-in window |
| Loyalty wallet-refunds switch (off) | The 4-commitment limit |
| Booking fee mode: per booking or per hour (per booking; PCR-010) | The 24h-in-48h maintenance threshold |
| Per-hour booking fee (EGP 0; PCR-010) | The 30-minute booking-duration step (`FR-BOK-01`) |
| Points per fee unit (10,000, even; PCR-010 — replaces the deferred Points-to-wallet ratio) | |

**Scope of `NFR-37` (`01 §14` A8, decided 26 Sep 2026; extended by PCR-010 on 25 Oct 2026).** Configurable values are exactly the eight keys above; the timers, limits and the Verified Player requirement (10) are fixed Product rules held in code and database constraints.

Core no-show rules remain Product rules rather than normal editable admin values, and are held as constants with a change-control comment.

**The `NFR-38` test.** A CI test asserts that nothing under `ps.e7gz.booking`, `ps.e7gz.loyalty` or `ps.e7gz.tournament` imports `PaymentProvider`, and a separate test sets the fee to a non-zero value and asserts that every terminal reason still resolves to exactly one fee treatment with the correct refund outcome (`FR-PTS-14`, `FR-PTS-15`, `FR-PTS-16`). That is what makes "no redesign" a verified claim rather than an aspiration.

---

# 8. THE LAUNCH READINESS BAR (`PRD §4.3`)

The product is launch-ready when all of the following hold. This table is the architecture's own definition of done.

| # | Criterion | Evidence |
|---|---|---|
| 1 | Every **M** requirement implemented and testable | `13 — Traceability Matrix` shows no unassigned FR |
| 2 | **Zero known double-booking paths** | concurrency pack green; invariant sweep clean; predicate singularity test green |
| 3 | Every terminal booking reason and every tournament reason is **reachable** and mapped to a fee treatment | exhaustive enum test over both closed enums + `feeTreatmentFor` with no default branch |
| 4 | Trust replay reproduces current state for a seeded history and as-of state at arbitrary past points | seeded-replay and as-of tests |
| 5 | The full emergency cascade is **atomic under fault injection** | fault injection in O7, O8, O9 leaves no partial state and no sellable gap |
| 6 | Every metric in `PRD §4.2` is instrumented | metric inventory test |
| 7 | All three interrupted-by-emergency reason codes are reachable | seeded end-to-end run |
| 8 | The optimistic feasibility test gives the same decision on repeated evaluation of identical state | determinism test |
| 9 | Boundary tests at ±1 second pass on every timer | boundary pack |
| 10 | A restore from backup has been drilled and timed | `12 §5` |

---

*End of Non-Functional & Observability Architecture v1.0. Continue to `12 — Deployment, Environments & Operations.md`.*
