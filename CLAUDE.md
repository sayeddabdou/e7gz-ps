# CLAUDE.md — E7GZ PS

E7GZ PS is a marketplace for booking PlayStation / gaming rooms at venues in one city. Customers find a venue and book; venues accept, run check-in and operate; a Platform Admin runs the marketplace.

## 1. Sources of truth (in this order)

1. `docs/01-product/` — Product Specification v5.0 (frozen) › PRD v1.1 › FRS v1.1 › PCR-001/002/003/004/005/006/007/008 (an approved PCR overrides the FRS lines it amends). Behaviour, status names, reason codes and timers come **only** from here.
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
./gradlew bootRun --args='--spring.profiles.active=api'     # or worker; api,worker runs both (photo copies, Nearest Availability refresh and every outbox event, the OTP SMS included, need worker)
                                     # needs E7GZ_JWT_SECRET (Base64, 64 bytes), E7GZ_OTP_PAYLOAD_KEY and E7GZ_IDEMPOTENCY_FINGERPRINT_KEY (Base64, 32 bytes each: openssl rand -base64 32)
./gradlew bootRun --args='--spring.profiles.active=admin-bootstrap --e7gz.admin-bootstrap.email=… --e7gz.admin-bootstrap.full-name=…'
                                     # one-off: first Platform Admin (password typed, or E7GZ_ADMIN_BOOTSTRAP_PASSWORD)
./gradlew bootRun --args='--spring.profiles.active=availability-rebuild'
                                     # one-off: recompute every Nearest Availability projection row (safe to rerun)
./gradlew seedDev                    # fresh local DB only: 2 areas, 6 venues, 20 customers (logins: backend/src/seed/README.md)
./gradlew seedPerf                   # fresh local DB only: launch volume x10 (150 venues, 20,000 customers), ~2 min
./gradlew discoveryPerf              # E7-34 venue-list p95 on the perf seed (Testcontainers, ~3 min; not in check; CI non-blocking)
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

**Migration safety (PO rule, 4 Oct 2026; `MigrationSafetyTest` enforces it on every build)**
- A migration merged to `main` is never edited, renamed or removed: a deployed database already ran it, and Flyway refuses to start. To undo or fix one, write a NEW migration. `backend/src/test/resources/ps/e7gz/database/migration-lock.txt` holds every migration's checksum; a new migration adds its line at the end in the same PR.
- Every new migration needs, in the same PR: the `.dbm` updated, a change-report entry naming the migration file and its `.dbm` change, a `MigrationFilesTest` check that the `.dbm` matches, and PO review.
- Before adding one, check it against the data already in the tables, not only an empty database: a new `NOT NULL` without a default, a new `CHECK` or `UNIQUE` that old rows break, a dropped or renamed column, table, function or grant still used by Java, SQL, a trigger or another migration. Search the code (`grep`) for every name it changes. If old rows need a backfill, the migration does it.
- `MigrationSafetyTest` applies every migration, one at a time, on a database seeded with realistic rows (`upgrade-seed/V1.sql`), and fails if any step crashes or loses rows. When a migration adds columns, add `upgrade-seed/V<version>.sql` with rows that use them, so later migrations are tested against them too.
- Run `./gradlew check` at least twice before asking for review of a migration PR (Testcontainers runs from scratch each time), and again after merging `main` into the branch.
- After deployment (E7-101): back up first; run the migration on a restored copy of production on staging; check `flyway info` / `validate` before `migrate`; smoke-test after. If production fails, restore the backup and ship a forward fix as a new migration. Never edit `flyway_schema_history` by hand.
- Nothing is hard-deleted where history matters. Tokens/subscriptions are revoked, accounts anonymised.

**Secrets (PO rule, 13 Oct 2026; the repository is public)**
- No secret is ever committed: API keys, tokens, passwords, private keys, VAPID/JWT/OTP/idempotency keys, Firebase files (`google-services.json`, `GoogleService-Info.plist`, service-account JSON), SMS Misr / SMTP credentials, server addresses with credentials, database dumps with real data. Not in code, tests, docs, seeds, `.env.example`, commit messages, PR text or Jira.
- Code reads every secret from an environment variable (`E7GZ_*`; `EXPO_PUBLIC_*` only for values that are public by nature). Commit only the variable **name**, with an empty value in `.env.example` and a line in the README. Tests generate their own throwaway keys in memory (as `FcmPushSenderTest` does).
- Real values live outside git: `.env` / `.env.local` (git-ignored), GitHub → Settings → Secrets and variables → Actions for CI, and the server's environment for deployment.
- **Who holds them:** Sayed (PO) holds every real secret and sends it to a developer **privately** (never in the repo, a PR, Jira or a group chat). If a task needs a new secret, the developer (or the AI tool) stops and asks Sayed; never invents, commits or shares one.
- Before every commit, `git diff --staged` must show no secret. If one was ever pushed, tell Sayed at once: the secret is **revoked and replaced** (deleting the commit is not enough on a public repository).
- Make the repository **private again before E7-101 (deployment) starts**.

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
