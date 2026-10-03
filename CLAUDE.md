# CLAUDE.md — E7GZ PS

E7GZ PS is a marketplace for booking PlayStation / gaming rooms at venues in one city. Customers find a venue and book; venues accept, run check-in and operate; a Platform Admin runs the marketplace.

## 1. Sources of truth (in this order)

1. `docs/01-product/` — Product Specification v5.0 (frozen) › PRD v1.1 › FRS v1.1 › PCR-001/002/003/004/005/006 (an approved PCR overrides the FRS lines it amends). Behaviour, status names, reason codes and timers come **only** from here.
2. `docs/02-architecture/` — the architecture pack (00–14). How to build it. Short name: "arch NN" = `docs/02-architecture/NN — *.md`.
3. `docs/04-erd/physical/` — the database. The `.dbm` is the authority for every schema/table/column/function name. `V1__…sql` is the baseline migration; the security baseline holds all grants.
4. `docs/03-design/` — visual reference only. Never take a status, rule, timer or message from a prototype (see its README).

If two sources disagree, stop and ask the Product Owner (Sayed) — do not pick one yourself. Never use v4.x documents.

## 2. Stack

| Part | Choice |
|---|---|
| Backend | Java 21, Spring Boot 3.x, Gradle; Spring Data JDBC + explicit SQL (JPA only for simple CRUD) |
| DB | PostgreSQL 16 + postgis + btree_gist; Flyway |
| Async | `technical.scheduled_job` lease workers, transactional outbox + `LISTEN/NOTIFY`; no broker, no Redis |
| Customer app | React Native + TypeScript (Expo) |
| Venue Dashboard | React + TypeScript + Vite, EN + AR (RTL) |
| Platform Admin | React + TypeScript + Vite, English |
| API | REST `/api/v1/{customer|venue|admin}/**`, OpenAPI 3.1 generated from code → `packages/api-client` |

## 3. Repository layout

Full tree and folder ownership: `DEVELOPER-GUIDE.md` §2–§3. Every folder's `package-info.java` / `index.ts` names the tasks that fill it.

```
backend/                 Spring Boot app (profiles: api, worker)
  src/main/java/ps/e7gz/common/…     shared kernel (clock, money, error, web, security, command, codes)
  src/main/java/ps/e7gz/<module>/{api,internal,web}
  src/main/resources/db/migration/   V1__baseline.sql, R__security_baseline.sql, later V<yyyyMMddHHmm>__*.sql
apps/customer/           React Native (Expo)
apps/venue-dashboard/    React + Vite
apps/admin-web/          React + Vite
packages/tokens/         design tokens (shared)
packages/api-client/     openapi.json + generated TS client
docs/                    product, architecture, design, ERD (read-only for code tasks)
```

Modules and the tables they own: arch 03 §1. A module calls another only through its `api` package (ArchUnit enforces it).

## 4. Commands

```
docker compose up -d                 # postgres (postgis) on 5433, seaweedfs (S3), mailpit
cd backend && ./gradlew check        # unit + integration (Testcontainers) + ArchUnit
./gradlew bootRun --args='--spring.profiles.active=api'     # or worker; api,worker runs both (venue photo copies need worker)
./gradlew bootRun --args='--spring.profiles.active=admin-bootstrap --e7gz.admin-bootstrap.email=… --e7gz.admin-bootstrap.full-name=…'
                                     # one-off: first Platform Admin (password typed, or E7GZ_ADMIN_BOOTSTRAP_PASSWORD)
pnpm install && pnpm -r build        # all frontends + packages
pnpm api:gen                         # regenerate packages/api-client from backend OpenAPI
pnpm mock                            # Prism mock server from openapi.json
```
(Update this section when the real commands exist.)

## 5. Rules that are easy to get wrong

**Database (ADR-026/027/028)**
- The DB already enforces invariants (exclusion constraints, state machines, snapshots, deadlines, append-only). The app makes decisions; the DB refuses bad writes. Do not duplicate DB rules in Java and do not work around a guard — map its error (arch 09).
- Every business write: one `READ COMMITTED` transaction opened through the `CommandRunner` (arch 04 §2.1), which calls `tournament.begin_command` / `admin.begin_command` with the lock set, and retries on `40001`.
- Producer-only tables (deletion requests, anonymisation, username changes, review reports, reviews, configuration, ledgers) are written **only** by calling their producer functions (arch 04 §2.2).
- Never edit `V1__baseline.sql`. A schema change = new migration `V<yyyyMMddHHmm>__<what>.sql` + update the `.dbm` in `docs/04-erd/physical` + the change report, reviewed by the Product Owner. Timestamped versions stop the three of us clashing.
- Closed value lists are `text` + `CHECK`; Java enums must match them exactly (a test checks this).
- Nothing is hard-deleted where history matters. Tokens/subscriptions are revoked, accounts anonymised.

**Time and money**
- Never call `Instant.now()` etc. Inject `Clock`; inside a command use the command instant.
- Money is `BigDecimal` scale 2 HALF_UP (EGP). No `float`/`double`.

**API**
- Separate DTOs per route group, always (contact visibility and neutral Late Confirm errors depend on it).
- Errors are codes from the `ApiErrorCode` enum (arch 09 §1.5); clients map codes to text. Never send rule text from the backend.
- `Idempotency-Key` is required on every booking-, commitment- or money/points-moving write (arch 09 §1.2, ADR-015). Other writes (e.g. admin area/venue edits) do not need it (PO decision 3 Oct 2026).

**Clients**
- No hard-coded colours (use `packages/tokens`), no hard-coded rule text, no status names invented from prototypes.
- Requirements first, prototype for the look: the FRS/PCRs decide what a screen must show and do; the prototype only decides how it looks. A screen or state the requirements need but the prototype lacks is still built, in the same design (tokens + existing components); list such screens in the PR.
- Dashboard: CSS logical properties only; every screen checked in AR.
- Timers shown from server timestamps, never device time.

## 6. How we work (3 developers in parallel)

- **Lanes:** Engine (inventory, booking, late confirm, attendance, trust, tournaments), Platform (foundation, identity, venue setup, pricing, scheduler, outbox, notifications, loyalty, config, ops support, admin APIs, deploy), Clients (three apps). Each lane owns its modules; changing another lane's module needs that owner's review.
- **Contract first:** each sprint starts with an `Sx-P0` contract task — controller interfaces + DTOs returning 501, merged by day 2. Clients build against `pnpm mock`; backend lanes fill the stubs.
- **Branches:** `E7-<jira#>-short-name` from `main`. Small PRs (< ~400 lines where possible). One teammate reviews; CI must be green. Squash-merge.
- **Other AI tools** (Abdallah: Gemini CLI or a browser chat) use this file too: `node tools/ai-context/bundle.mjs E7-xx` builds one context file with these rules and the task.
- **Every task** in Jira has a Claude Code prompt. Paste it, let Claude Code work, then **review the diff yourself** before opening the PR.
- **Definition of Done:** code + tests (including the task's "done when" items) + OpenAPI regenerated if the API changed + no ArchUnit violations + PR reviewed + Jira moved to Done.
- Daily 15-min sync: what merged, what is blocked, which contract changed.

## 7. When you (Claude Code) work on a task

1. Read this file, then only the docs the task lists. Quote doc section IDs in code comments where a rule comes from.
2. Stay inside the task's scope and module(s). If you need a change elsewhere, stop and say so.
3. Write the tests from the task's "done when" list first.
4. Finish with: `./gradlew check` and/or `pnpm -r lint typecheck build` green, a short summary, and any open question for the PO.
