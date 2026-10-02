# E7GZ PS — Build Plan

**Basis:** arch 14 (Implementation Roadmap) · **Start:** Sun 27 Sep 2026 · **9 sprints × 2 weeks (to 28 Jan 2027)** · **Folders & workflow:** `DEVELOPER-GUIDE.md` · **Visual map:** `team-map.html` · **Tracking:** Jira project E7 (Workstreams E7-1 … E7-8 = Sprints 0–7; Tasks E7-9 … E7-110).

## How three people work at the same time

1. **The database is already built** (`docs/04-erd/physical`). Any lane can seed the exact rows it needs with SQL, so backend work does not wait for another lane's API.
2. **Three lanes with separate modules** — few merge conflicts:

| Lane | Owner | Owns |
|---|---|---|
| Engine (E) | Amr Heiba | inventory, availability, booking, late confirm, attendance, trust, tournaments (backend) |
| Platform (P) | Sayed abdo | foundation, identity, venue setup, pricing, scheduler, outbox, notifications, loyalty, config, operations support, admin APIs, deployment |
| Clients (C) | Abdallah ELSayed | Customer app (React Native), Venue Dashboard (React), Platform Admin (React) |

3. **Frontend one sprint behind.** Sayed and Amr build the backend of sprint N while Abdallah builds the screens for sprint N−1 on the real, finished API. Each sprint's `P0` task (Sayed) still publishes the endpoints as stubs by day 2, so the contract is fixed early. Changes to a merged contract are announced in the daily sync.
4. **Tests first** from each task's *Done when*; boundary tests use the shared clock tooling.
5. **Day 1 of Sprint 0:** Sayed runs S0-P1 (E7-9), pushes the skeleton to GitHub, and the others clone it.
6. **Abdallah works with free AI tools** — see `AI-HELPERS.md` (Gemini CLI + `AGENTS.md`, or the context bundle for browser chats).
7. **Branch per Jira task, small PRs, one reviewer, green CI** (CLAUDE.md §6). Lanes can be swapped between people — only the Jira assignee changes.

## How to use a task

Open the Jira task → copy the **Claude Code prompt** → run it in Claude Code on your branch → review the diff yourself → open the PR → teammate reviews → merge → Done.

## Sprints

### Sprint 0 — Foundations (27 Sep – 8 Oct 2026)

Everything runs locally and in CI; DB baseline applied; command runner, errors, audit, identity; three app shells.

| ID | Jira | Lane | Task | What it does | Depends on |
|---|---|---|---|---|---|
| S0-P1 | E7-9 | Platform | Project skeleton and folder structure | Create every folder and empty placeholder file, make the backend build, push to GitHub. Everyone pulls this before starting. | — |
| S0-P2 | E7-10 | Platform | Local environment and database baseline | Run the database, file storage and email locally with Docker, and load our finished database into the app. | S0-P1 |
| S0-P3 | E7-11 | Platform | CI pipeline and OpenAPI → TypeScript client | Automatic checks on every pull request, and a generated API client the three apps use. | S0-P1 |
| S0-P4 | E7-12 | Platform | Cross-cutting: clock, money, correlation ID, error envelope | Shared basics: one clock for time, one money type, a request ID in every log, and one error format. | S0-P1 |
| S0-P5 | E7-13 | Platform | Customer registration and phone OTP | Customers sign up and verify their phone with an SMS code. | S0-P2, S0-P4 |
| S0-P6 | E7-14 | Platform | Login, JWT and refresh rotation for three audiences | Log in for customers, venues and admins; keep users signed in safely. | S0-P5 |
| S0-E1 | E7-15 | Engine | Command runner (READ COMMITTED, lock set, retry) | The one safe way every "action" writes to the database (locks, retries). | S0-P2 |
| S0-E2 | E7-16 | Engine | Closed value sets as Java enums, checked against the DB | All fixed lists (statuses, reasons, codes) as Java enums that must match the database. | S0-P2 |
| S0-E3 | E7-17 | Engine | Audit writer | Record who did what, when and why, for every important action. | S0-P4, S0-E1 |
| S0-C1 | E7-18 | Clients | Design tokens package | Pull colours, fonts and spacing from the designs into one shared package. | S0-P1 |
| S0-C2 | E7-19 | Clients | Venue Dashboard and Admin web shells | Empty but running Venue Dashboard (English + Arabic) and Admin website. | S0-C1, S0-P3 |
| S0-C3 | E7-20 | Clients | Customer app shell | Empty but running customer mobile app with its tabs. | S0-C1, S0-P3 |

### Sprint 1 — Venues and the room calendar (11 – 22 Oct 2026)

Venue setup, operating sessions, resources, pricing; reservation ledger, isFree, Nearest Availability, discovery. Roadmap Phase 0 rest + Phase 1.

| ID | Jira | Lane | Task | What it does | Depends on |
|---|---|---|---|---|---|
| S0-C4 | E7-21 | Clients | Auth screens on all three surfaces | Sign-up, OTP and login screens on the app, dashboard and admin. | S0-C2, S0-C3 |
| S1-P0 | E7-22 | Platform | Sprint 1 API contract | Add all Sprint 1 API endpoints as empty stubs by day 2 so screens can start. | S0-P3 |
| S1-P1 | E7-23 | Platform | Areas, venues and marketplace status | Admin creates areas and venues and moves a venue live, paused or closed. | S1-P0 |
| S1-P2 | E7-24 | Platform | Venue account and Owner Password | Venue login account and the Owner Password that protects sensitive actions. | S1-P1, S0-P6 |
| S1-P3 | E7-25 | Platform | Opening hours and operating sessions (DST) | Weekly opening hours turned into real open sessions, correct across clock changes. | S1-P1 |
| S1-P4 | E7-26 | Platform | Resources, activities and state changes | Rooms/consoles of a venue, their status, and a history of every change. | S1-P1 |
| S1-P5 | E7-27 | Platform | Pricing and rounding | Prices per hour and the exact rounding rule. | S1-P4 |
| S1-P6 | E7-28 | Platform | Seed data generator | One command that fills the database with realistic test data. | S1-P3, S1-P4, S1-P5 |
| S1-E1 | E7-29 | Engine | Reservation ledger | The room calendar: hold a time slot and make double-booking impossible. | S0-E1 |
| S1-E2 | E7-30 | Engine | isFree: the eight ordered checks | The single "is this room free?" check with its 8 steps in order. | S1-E1 |
| S1-E3 | E7-31 | Engine | Business date | Which business day a booking belongs to (even after midnight). | S1-E1 |
| S1-E4 | E7-32 | Engine | Concurrency harness and invariant sweep | A test that fires many bookings at once, and a nightly health check. | S1-E1 |
| S1-E5 | E7-33 | Engine | Nearest Availability projection | Pre-compute "next available time" for every venue so lists are fast. | S1-E2 |
| S1-E6 | E7-34 | Engine | Discovery API | The venue list: nearest first, filters, next available time. | S1-E5, S1-P0 |

### Sprint 2 — Book a room end to end (25 Oct – 5 Nov 2026)

Online and walk-in booking, venue accept/reject, deadlines, outbox, notifications, SSE + Web Push. Journeys J1, J8. Roadmap Phase 2.

| ID | Jira | Lane | Task | What it does | Depends on |
|---|---|---|---|---|---|
| S1-C1 | E7-35 | Clients | Dashboard: venue setup screens | Dashboard screens to set up venue profile, hours, rooms and prices. | S1-P0 |
| S1-C2 | E7-36 | Clients | Dashboard: availability schedule | Dashboard grid showing each room's bookings through the day. | S1-P0 |
| S1-C3 | E7-37 | Clients | Admin: areas, venues, venue accounts | Admin screens for areas, venues and venue accounts. | S1-P0, S0-C2 |
| S1-C4 | E7-38 | Clients | Customer app: discovery, map and venue page | App: list of venues, map, and venue page. | S1-P0, S0-C3 |
| S2-P0 | E7-39 | Platform | Sprint 2 API contract | Add all Sprint 2 API endpoints as stubs by day 2. | S1-P0 |
| S2-P1 | E7-40 | Platform | Deadline engine (scheduled_job) | The timer engine that fires deadlines on time (e.g. 10-minute venue reply). | S0-E1 |
| S2-P2 | E7-41 | Platform | Transactional outbox and dispatcher | Send messages only after the action is saved, with retries. | S0-E1 |
| S2-P3 | E7-42 | Platform | Idempotency on writes | Pressing a button twice never creates two bookings. | S0-E1 |
| S2-P4 | E7-43 | Platform | Notifications: catalogue, channels, push and email | Notifications: in-app list, phone push, email, and SMS for codes only. | S2-P2 |
| S2-P5 | E7-44 | Platform | Venue live channel: SSE + Web Push | Live updates on the dashboard, and browser push when it is closed. | S2-P2, S2-P4 |
| S2-P6 | E7-45 | Platform | Boundary test pack scaffold | Test tools to set the clock and check timers to the exact second. | S2-P1 |
| S2-E1 | E7-46 | Engine | Online booking creation (O1) | A customer books a room online (20 checks), the venue gets the request. | S1-E2, S2-P0, S2-P1 |
| S2-E2 | E7-47 | Engine | Venue accept / reject / expiry (O2) | The venue accepts or rejects within 10 minutes, or the request expires. | S2-E1 |
| S2-E3 | E7-48 | Engine | Walk-in / manual booking (O4) | The venue adds a walk-in booking. | S1-E2, S1-E3 |
| S2-E4 | E7-49 | Engine | Cancellation, customer buckets, contact visibility | Customer cancels; booking lists; hide the phone number when not allowed. | S2-E1 |

### Sprint 3 — Late Confirm and attendance (8 – 19 Nov 2026)

Late Confirm, check-in / late / no-show, start-time revalidation, completion; all four timers boundary-tested. Roadmap Phase 3.

| ID | Jira | Lane | Task | What it does | Depends on |
|---|---|---|---|---|---|
| S2-C1 | E7-50 | Clients | Customer app: book a room | App: choose time and duration, see the price, book, wait for the venue. | S2-P0, S1-C4 |
| S2-C2 | E7-51 | Clients | Customer app: My Bookings and push | App: My Bookings, cancel, and receive push notifications. | S2-P0 |
| S2-C3 | E7-52 | Clients | Dashboard: live requests with sound and Web Push | Dashboard: new requests appear live with a sound, accept or reject. | S2-P0 |
| S2-C4 | E7-53 | Clients | Dashboard: walk-ins and today view | Dashboard: add walk-ins and see today's bookings. | S2-P0 |
| S3-P0 | E7-54 | Platform | Sprint 3 API contract | Add all Sprint 3 API endpoints as stubs by day 2. | S2-P0 |
| S3-P1 | E7-55 | Platform | Booking and attendance notifications | Send the right notification for every booking and attendance event. | S2-P4 |
| S3-P2 | E7-56 | Platform | Admin alerts | Alerts to the admin when something is seriously wrong. | S2-P2 |
| S3-P3 | E7-57 | Platform | Profile and account management | Edit profile, change username, phone, email, reset password. | S0-P6 |
| S3-E1 | E7-58 | Engine | Late Confirm (O3) | Late Confirm: the venue offers a late slot, the customer accepts in time. | S2-E2, S2-P6 |
| S3-E2 | E7-59 | Engine | Check-in, late present, no-show (O13) | Check-in, "arrived late", and no-show. | S2-E2 |
| S3-E3 | E7-60 | Engine | Start-time revalidation (O11) | Re-check everything at start time and cancel with the right reason if needed. | S3-E2 |
| S3-E4 | E7-61 | Engine | Completion and attendance defaults (O12) | Finish bookings automatically and set the final attendance result. | S3-E2 |

### Sprint 4 — Trust, bans and rewards (22 Nov – 3 Dec 2026)

Trust events and replay, bans, Verified Player, points, wallet, referrals, configuration; tournament entity built early. Roadmap Phase 4.

| ID | Jira | Lane | Task | What it does | Depends on |
|---|---|---|---|---|---|
| S3-C1 | E7-62 | Clients | Customer app: Late Confirm and status | App: Late Confirm screen with countdown and booking status timeline. | S3-P0 |
| S3-C2 | E7-63 | Clients | Dashboard: attendance and Late Confirm | Dashboard: check-in, late, no-show and Late Confirm buttons. | S3-P0 |
| S3-C3 | E7-64 | Clients | Customer app: profile and account | App: profile and account screens. | S3-P0 |
| S4-P0 | E7-65 | Platform | Sprint 4 API contract | Add all Sprint 4 API endpoints as stubs by day 2. | S3-P0 |
| S4-P1 | E7-66 | Platform | Loyalty ledgers and fee treatment | Points and wallet ledgers, and what happens to the booking fee in every case. | S2-E4 |
| S4-P2 | E7-67 | Platform | Referrals | Invite a friend: referral rewards and reversals. | S4-P1, S0-P5 |
| S4-P3 | E7-68 | Platform | Configuration service (fees and points) | Admin changes the booking fee and points values (from a date onward). | S0-E1 |
| S4-E1 | E7-69 | Engine | Trust events and replay | Trust history of every customer, recalculated exactly from events. | S3-E4 |
| S4-E2 | E7-70 | Engine | Reconciler and attendance corrections | Fix attendance mistakes and recalculate trust correctly. | S4-E1 |
| S4-E3 | E7-71 | Engine | Bans and the ban cascade (O14) | Temporary bans after repeated no-shows, and their effects. | S4-E2 |
| S4-E4 | E7-72 | Engine | Verified Player | The Verified Player badge: earning, losing, restoring it. | S4-E2 |
| S4-E5 | E7-73 | Engine | Tournament entity (early, for Sprint 5) | Basic tournament records early, so venue emergencies can handle them. | S1-E1 |

### Sprint 5 — Running the venue (6 – 17 Dec 2026)

Maintenance, closures, emergencies, owner review, timer pauses, account disable, fault injection. Roadmap Phase 5.

| ID | Jira | Lane | Task | What it does | Depends on |
|---|---|---|---|---|---|
| S4-C1 | E7-74 | Clients | Customer app: standing, points, wallet, referrals | App: my standing, points, wallet and referral code. | S4-P0 |
| S4-C2 | E7-75 | Clients | Admin: trust, bans, Verified Player, points, configuration | Admin: trust fixes, bans, badges, points, fees. | S4-P0 |
| S4-C3 | E7-76 | Clients | Dashboard: Arabic RTL review of built modules | Check every dashboard screen in Arabic and fix layout. | S2-C4 |
| S5-P0 | E7-77 | Platform | Sprint 5 API contract | Add all Sprint 5 API endpoints as stubs by day 2. | S4-P0 |
| S5-P1 | E7-78 | Platform | Venue account disable (O10) and attendance protection | Admin disables a venue account and what happens to its bookings. | S3-E4 |
| S5-P2 | E7-79 | Platform | Fault-injection harness and sellable-gap detector | Tests that break a process halfway and prove nothing is left half-done. | S1-E4 |
| S5-P3 | E7-80 | Platform | Operational notifications | Notifications for emergencies, closures, refunds and admin events. | S2-P4 |
| S5-E1 | E7-81 | Engine | Maintenance and closures | Maintenance windows and venue closures block the calendar. | S1-E2 |
| S5-E2 | E7-82 | Engine | Emergencies and cascades (O7/O8) | Venue or room emergency: cancel or pause affected bookings safely. | S5-E1, S5-P2 |
| S5-E3 | E7-83 | Engine | Timer pause/resume and feasibility | Pause and resume timers during emergencies (30-minute limits). | S5-E2 |
| S5-E4 | E7-84 | Engine | Owner review cascade (O9) | Owner review: lock the venue down when limits are exceeded. | S5-E2, S4-E5 |

### Sprint 6 — Tournaments (20 – 31 Dec 2026)

Full tournament lifecycle, approval, checkpoint, participants, resource loss. Roadmap Phase 6.

| ID | Jira | Lane | Task | What it does | Depends on |
|---|---|---|---|---|---|
| S5-C1 | E7-85 | Clients | Dashboard: operations screens | Dashboard: maintenance, closures and emergency screens. | S5-P0 |
| S5-C2 | E7-86 | Clients | Admin: owner review, disable, alerts | Admin: owner review, disable venue account, alerts inbox. | S5-P0 |
| S5-C3 | E7-87 | Clients | Customer app: operational states | App: show emergency cancellations, pauses and refunds. | S5-P0 |
| S6-P0 | E7-88 | Platform | Sprint 6 API contract | Add all Sprint 6 API endpoints as stubs by day 2. | S5-P0 |
| S6-P1 | E7-89 | Platform | Tournament Owner Password matrix and notifications | Owner Password rules and notifications for tournaments. | S2-P4, S1-P2 |
| S6-P2 | E7-90 | Platform | Tournament reachability and boundary tests | Tests proving every tournament outcome can happen and happens in the right order. | S2-P6 |
| S6-E1 | E7-91 | Engine | Tournament creation and holds (O16 part 1) | Create a tournament (by a player or a venue) and hold the rooms. | S4-E5, S6-P0 |
| S6-E2 | E7-92 | Engine | Approval, rejection, timeout (T7–T9) | The venue approves or rejects in time; otherwise it is cancelled. | S6-E1 |
| S6-E3 | E7-93 | Engine | Join, leave, checkpoint, start, cancel | Players join and leave; minimum check; start; cancel. | S6-E2 |
| S6-E4 | E7-94 | Engine | Resource loss, Verified Player cascade, precedence | Losing rooms, losing a badge, bans and deleted accounts in tournaments. | S6-E3, S4-E4 |

### Sprint 7 — Reviews, admin and launch (3 – 14 Jan 2027)

Reviews and reporting, account deletion, admin modules, reports, observability, deployment, load test. Roadmap Phase 7. Frontend: tournament screens.

| ID | Jira | Lane | Task | What it does | Depends on |
|---|---|---|---|---|---|
| S6-C1 | E7-95 | Clients | Customer app: tournaments | App: browse, create, join and leave tournaments. | S6-P0 |
| S6-C2 | E7-96 | Clients | Dashboard: tournaments | Dashboard: tournament requests with sound, approve/reject, manage. | S6-P0 |
| S6-C3 | E7-97 | Clients | Admin: tournaments | Admin: tournaments list and cancel. | S6-P0 |
| S7-P0 | E7-98 | Platform | Sprint 7 API contract | Add all Sprint 7 API endpoints as stubs by day 2. | S6-P0 |
| S7-P1 | E7-99 | Platform | Remaining admin modules | Finish any admin module still missing (20 in total). | S7-P0 |
| S7-P2 | E7-100 | Platform | Observability, alerts and abuse signals | Monitoring, alert rules and abuse signals. | S3-P2 |
| S7-P3 | E7-101 | Platform | Deployment, backup and restore drill | Put it online (staging and production), backups and a restore test. | S0-P3 |
| S7-E1 | E7-102 | Engine | Reviews, reporting and moderation | Reviews: write, edit, report, and admin moderation. | S3-E4 |
| S7-E2 | E7-103 | Engine | Account deletion and O19 | Customer asks to delete the account; admin completes it. | S6-E3 |
| S7-E3 | E7-104 | Engine | Reports FR-AUD-11/12 and metrics | Admin reports: why bookings end, attendance per venue. | S3-E4 |
| S7-E4 | E7-105 | Engine | Load test at 10× | Load test at 10 times the expected traffic. | S1-P6, S7-P3 |

### Sprint 8 — Frontend catch-up and launch (17 – 28 Jan 2027)

Frontend finishes the Sprint 7 screens on the finished API; final RTL pass; store builds; launch checklist. Backend: bug fixes and support.

| ID | Jira | Lane | Task | What it does | Depends on |
|---|---|---|---|---|---|
| S7-C1 | E7-106 | Clients | Customer app: reviews, reporting, delete account | App: reviews, report a review, delete account. | S7-P0 |
| S7-C2 | E7-107 | Clients | Admin: moderation, deletion, reports | Admin: moderate reviews, complete deletions, report screens. | S7-P0 |
| S7-C3 | E7-108 | Clients | Dashboard: final RTL pass and reviews view | Final Arabic pass on the dashboard and venue reviews page. | S6-C2 |
| S7-C4 | E7-109 | Clients | Store and web release readiness | Store builds for testers (Play + TestFlight), dashboards online. | S7-P3 |
| S7-C5 | E7-110 | Platform | Launch readiness checklist (PO) | Go through the launch checklist and sign it off. | S7-E4, S7-P3 |

## Prompts

### S0-P1 (E7-9) — Project skeleton and folder structure

**What this task does:** Create every folder and empty placeholder file, make the backend build, push to GitHub. Everyone pulls this before starting.

Lane: Platform · Depends on: —

```text
Read CLAUDE.md, then DEVELOPER-GUIDE.md sections 2 and 3. You are doing Jira E7-9 (S0-P1): create the project skeleton — folders and placeholder files only, no business code.

Goal: after this task every developer knows exactly where their code goes, the backend builds, and the three apps and two packages exist as empty workspaces.

1. Git and root files
   - `git init` at the repo root (docs/ stays untouched). Add .gitignore (Java, Gradle, Node, Expo, IDE, .env, .ai-context/), .gitattributes (LF), .editorconfig.
   - Keep the existing root files CLAUDE.md, AGENTS.md, GEMINI.md, BUILD-PLAN.md, DEVELOPER-GUIDE.md, AI-HELPERS.md, team-map.html and tools/ai-context/ exactly as they are.
   - README.md: one paragraph on the project + "see DEVELOPER-GUIDE.md".
   - .github/pull_request_template.md (Jira key, what changed, how tested, checklist from DEVELOPER-GUIDE.md §7).
   - .github/CODEOWNERS from DEVELOPER-GUIDE.md §3 (use GitHub handles placeholders @amr @abdallah @sayed; I will replace them).
   - .github/workflows/ci.yml and docker-compose.yml: placeholder files containing only a comment "# Filled by E7-11" / "# Filled by E7-10".
   - Root package.json + pnpm-workspace.yaml (workspaces: apps/*, packages/*), scripts that just echo for now.

2. Backend (must build)
   - backend/ with Gradle Kotlin DSL + wrapper, Java 21 toolchain, Spring Boot 3.x (latest 3.x), dependencies: web, jdbc, validation, actuator, security, flyway, postgresql, springdoc-openapi, archunit-junit5, testcontainers (postgresql), junit.
   - E7gzApplication.java and application.yml / application-api.yml / application-worker.yml with only the profile names (no datasource yet — tests must not need a DB in this task; exclude DataSource/Flyway auto-config until E7-10).
   - Create EVERY package in DEVELOPER-GUIDE.md §2 exactly as named. For each module: api/, internal/, web/ sub-packages, each with a package-info.java. The module root package-info.java has a Javadoc listing: the module's purpose (one line from arch 03 §1), the tables it owns (arch 03 §1), and the Jira tasks that fill it (DEVELOPER-GUIDE.md §3). Sub-package package-info.java has one line saying what belongs there.
   - common/ sub-packages (clock, money, error, web, security, security/owner, command, codes, observability) each get a package-info.java naming the task that fills it.
   - backend/src/seed/ and src/test/java/ps/e7gz/{architecture,support,support/fixtures,support/boundary,concurrency,faultinjection,tournament} with package-info.java or .gitkeep.
   - src/main/resources/db/migration/.gitkeep.
   - ArchitectureTest.java with the ArchUnit rules: (a) a module may use another module only through its api package; (b) no package cycles between modules; (c) web never used by internal/api; (d) common never depends on a module. Plus one context-loads test.
   - Do NOT create any other classes, entities, controllers or services.

3. Frontends and packages (empty workspaces)
   - apps/customer, apps/venue-dashboard, apps/admin-web, packages/tokens, packages/api-client: each gets package.json (name @e7gz/<name>, private, scripts that echo "not set up yet — see E7-xx") and README.md saying which tasks fill it.
   - Create the src/ folder tree from DEVELOPER-GUIDE.md §2 for each app. Every folder gets an index.ts containing only a comment naming the owner task(s) and `export {};`. Do not run create-vite / create-expo-app — that is E7-19 / E7-20.
   - apps/venue-dashboard/public/.gitkeep.

4. infra/ and tools/ (db-tests, load-tests) with a README.md naming the owner task.

5. Check: `cd backend && ./gradlew check` passes (ArchUnit + context test); `pnpm install` works at the root. Show me the final tree (`tree -d -L 6` or equivalent) and a short summary. Do not commit — I will review, then commit "E7-9: project skeleton" and push to GitHub with main protected.
```

### S0-P2 (E7-10) — Local environment and database baseline

**What this task does:** Run the database, file storage and email locally with Docker, and load our finished database into the app.

Lane: Platform · Depends on: S0-P1

```text
Read CLAUDE.md first. You are implementing task S0-P2 — "Local environment and database baseline" (Platform lane) of the E7GZ PS build plan.

Read only these docs for this task:
- CLAUDE.md (Database section)
- arch 04 §5–§6
- docs/04-erd/physical/E7GZ-PS-v1.1.27-CHANGE-REPORT.md

Do:
1. docker-compose.yml: PostgreSQL 16 with postgis + btree_gist, SeaweedFS, Mailpit.
2. Copy docs/04-erd/physical/V1__e7gz_ps_baseline_v1_1_27.sql to backend/src/main/resources/db/migration/V1__baseline.sql unchanged, and the security baseline as a Flyway repeatable R__security_baseline.sql.
3. Flyway runs as e7gz_migrator; the app connects as e7gz_app_runtime (api) or e7gz_admin_runtime (admin endpoints) — two datasources.
4. Testcontainers base class (PostGIS image) that applies the migrations once per test run.
5. Port the three shell test scripts from docs/04-erd/physical into a JUnit suite (or run them from a CI job) so the DB contract is checked on every build.

Done when (write these as tests first where possible):
- docker compose up + ./gradlew bootRun starts the app against a migrated DB.
- The DB behaviour/tournament/privilege checks run green in CI.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S0-P3 (E7-11) — CI pipeline and OpenAPI → TypeScript client

**What this task does:** Automatic checks on every pull request, and a generated API client the three apps use.

Lane: Platform · Depends on: S0-P1

```text
Read CLAUDE.md first. You are implementing task S0-P3 — "CI pipeline and OpenAPI → TypeScript client" (Platform lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 09 §1
- ADR-012 in arch 02

Do:
1. GitHub Actions: backend unit + integration (Testcontainers) + ArchUnit; frontends lint + typecheck + build.
2. springdoc generates OpenAPI 3.1 at build time into packages/api-client/openapi.json; generate a typed TS client (openapi-typescript + a small fetch wrapper) into packages/api-client.
3. CI fails if the committed openapi.json is out of date.
4. Add a mock server script (Prism) so the apps can run against the contract before the backend exists: pnpm mock.

Done when (write these as tests first where possible):
- A PR with a new endpoint regenerates the TS client; the apps compile against it.
- pnpm mock serves the current contract.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S0-P4 (E7-12) — Cross-cutting: clock, money, correlation ID, error envelope

**What this task does:** Shared basics: one clock for time, one money type, a request ID in every log, and one error format.

Lane: Platform · Depends on: S0-P1

```text
Read CLAUDE.md first. You are implementing task S0-P4 — "Cross-cutting: clock, money, correlation ID, error envelope" (Platform lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 08 §8 (clock)
- arch 09 §1.3–§1.5 (envelope, status codes, error enum)
- NFR-25, NFR-32

Do:
1. Injectable java.time.Clock bean; ArchUnit bans Instant.now()/LocalDateTime.now()/System.currentTimeMillis() outside the clock adapter.
2. Money value type on BigDecimal (scale 2, HALF_UP, EGP); ArchUnit bans float/double in domain packages.
3. Correlation-ID filter: accept/generate X-Correlation-Id, put it in MDC, return it, pass it to audit and outbox.
4. Response envelope and ApiErrorCode enum exactly as arch 09 §1.5; global exception handler.
5. DB exception mapping: SQLSTATE 23P01 → typed conflict, 40001 → retry signal, guard RAISE EXCEPTION → ApiErrorCode by the mapping paragraph in arch 09.

Done when (write these as tests first where possible):
- Tests prove each ban rule fires.
- A guard exception from the DB reaches the client as the documented error code.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S0-P5 (E7-13) — Customer registration and phone OTP

**What this task does:** Customers sign up and verify their phone with an SMS code.

Lane: Platform · Depends on: S0-P2, S0-P4

```text
Read CLAUDE.md first. You are implementing task S0-P5 — "Customer registration and phone OTP" (Platform lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 10 §2
- FRS 4.1 (FR-ACC-01…06)
- Spec v5 §7.3 (OTP)
- iam.user_account, iam.otp_challenge in arch 04 §9

Do:
1. POST register, send OTP, verify OTP, resend — per arch 09 §3.1.
2. Argon2id password hashing. OTP stored hashed; the DB already enforces 5-min validity, 5 attempts, 60 s resend, 5/hour/destination and supersede — map its errors, do not re-implement them.
3. SMS adapter interface with a dev implementation that logs the code; real provider behind configuration.
4. phone_verified is the marketplace activation gate; email never activates.

Done when (write these as tests first where possible):
- Integration tests cover each OTP limit returning the right error code.
- An unverified user cannot reach booking endpoints (403 with the right code).

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S0-P6 (E7-14) — Login, JWT and refresh rotation for three audiences

**What this task does:** Log in for customers, venues and admins; keep users signed in safely.

Lane: Platform · Depends on: S0-P5

```text
Read CLAUDE.md first. You are implementing task S0-P6 — "Login, JWT and refresh rotation for three audiences" (Platform lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 10 §2
- arch 09 §2

Do:
1. Customer, Venue Account and Admin login endpoints under their route groups (/api/v1/customer|venue|admin).
2. Short-lived JWT access token + rotating refresh token stored hashed in iam.auth_refresh_token; reuse of a rotated token revokes the family.
3. Login rate limits via iam.security_rate_limit_counter with limiter_type LOGIN (added to the DB for this, PO decision A10): subject_key_hash = hash(login identifier + client IP), action_class = CUSTOMER / VENUE / ADMIN; limit values from arch 10 §7 / arch 01 §14 A4. Fails closed if the DB is unavailable.
4. Spring Security config: each route group accepts only its own principal type.

Done when (write these as tests first where possible):
- Tests: token rotation, reuse detection, cross-audience token rejected, rate limit hit.

Carry-forward from E7-13 (do these too):
- verify-phone (E7-13) returns 204 now: change it to 200 {accessToken, refreshToken} per arch 09 §2; regenerate OpenAPI and tell the Clients lane (contract change).
- ApiSecurityConfig (common/security) is the E7-13 minimum: build JWT authentication on it; add login/refresh/logout (and E7-57's password reset) to its PUBLIC_POSTS and to OpenApiProductionContractTest.PUBLIC_OPERATIONS.
- Swagger, /v3/api-docs and /actuator/** are denied (401) by ApiSecurityConfig: decide whether any of them should be public.
- Customer login accepts username OR phone: normalise the phone with identity PhoneNumbers.toE164 and verify with the existing Argon2id PasswordEncoder bean (PasswordHashingConfig); do not create a second encoder.
- Unverified customers may log in (FR-ACC-03); marketplace actions are gated by CustomerActivation.requirePhoneVerified inside each command.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S0-E1 (E7-15) — Command runner (READ COMMITTED, lock set, retry)

**What this task does:** The one safe way every "action" writes to the database (locks, retries).

Lane: Engine · Depends on: S0-P2

```text
Read CLAUDE.md first. You are implementing task S0-E1 — "Command runner (READ COMMITTED, lock set, retry)" (Engine lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 04 §2.1 (command protocol)
- ADR-026
- arch 06 §0–§1 intro

Do:
1. A CommandRunner that opens one READ COMMITTED transaction, calls tournament.begin_command(...) or admin.begin_command(...) with the declared lock set, runs the command body, and retries the whole transaction on SQLSTATE 40001 (bounded, jittered).
2. Commands declare their lock set (users, venues, resources, tournaments) and eligibility category (ADR-022) up front.
3. Expose commandNow() read from the DB command instant; domain code uses it instead of the clock inside commands.
4. Guard exceptions bubble up unchanged to the S0-P4 mapper.

Done when (write these as tests first where possible):
- Integration test: a changed lock set triggers a retry and then succeeds.
- REPEATABLE READ is refused by the DB and surfaces as a clear error.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S0-E2 (E7-16) — Closed value sets as Java enums, checked against the DB

**What this task does:** All fixed lists (statuses, reasons, codes) as Java enums that must match the database.

Lane: Engine · Depends on: S0-P2

```text
Read CLAUDE.md first. You are implementing task S0-E2 — "Closed value sets as Java enums, checked against the DB" (Engine lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 04 §1 P5
- ADR-016, ADR-027
- arch 01 (three separate enum types)

Do:
1. Java enums for every closed list in the model (booking status, reasons, tournament status/reasons, attendance, trust event types, notification codes, etc.).
2. Keep reason codes, booking events and API error codes in three separate types; ArchUnit/unit test proves they share no members.
3. A test that reads every CHECK (... = ANY (ARRAY[...])) list from pg_constraint and asserts the Java enum has exactly the same members.

Done when (write these as tests first where possible):
- Changing a value in Java without the DB (or the reverse) fails the build.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S0-E3 (E7-17) — Audit writer

**What this task does:** Record who did what, when and why, for every important action.

Lane: Engine · Depends on: S0-P4, S0-E1

```text
Read CLAUDE.md first. You are implementing task S0-E3 — "Audit writer" (Engine lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 10 §8
- audit.audit_event in arch 04 §9
- FR-AUD-01…

Do:
1. AuditWriter used inside commands: actor type/id, action, subject, reason, correlation id, redacted payload via an allowlist.
2. No update/delete path exists (DB forbids it anyway).
3. Helper for tests to assert "exactly one audit row for this action".

Done when (write these as tests first where possible):
- Tests: redaction allowlist drops unknown fields; audit row shares the command transaction (rollback removes it).

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S0-C1 (E7-18) — Design tokens package

**What this task does:** Pull colours, fonts and spacing from the designs into one shared package.

Lane: Clients · Depends on: S0-P1

```text
Read CLAUDE.md first. You are implementing task S0-C1 — "Design tokens package" (Clients lane) of the E7GZ PS build plan.

Read only these docs for this task:
- docs/03-design/README.md
- the three .dc.html prototypes

Do:
1. Extract colours, typography scale, spacing, radii, shadows from the prototypes into packages/tokens (TS + CSS variables).
2. Include RTL-safe spacing helpers (logical properties).
3. Export for React (web) and React Native.

Done when (write these as tests first where possible):
- All three apps import tokens from one package; no hard-coded colours in app code (lint rule).

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S0-C2 (E7-19) — Venue Dashboard and Admin web shells

**What this task does:** Empty but running Venue Dashboard (English + Arabic) and Admin website.

Lane: Clients · Depends on: S0-C1, S0-P3

```text
Read CLAUDE.md first. You are implementing task S0-C2 — "Venue Dashboard and Admin web shells" (Clients lane) of the E7GZ PS build plan.

Read only these docs for this task:
- docs/03-design/README.md
- arch 11 §6 (RTL)
- FR-VEN-40, NFR-33

Do:
1. apps/venue-dashboard: React + TS + Vite, router, auth guard, layout shell from the prototype, EN/AR i18n with dir="rtl" switching and CSS logical properties only.
2. apps/admin-web: React + TS + Vite, English only, layout shell and navigation for the 20 admin modules (empty pages).
3. Both use packages/api-client and can run against pnpm mock.

Done when (write these as tests first where possible):
- Both apps build in CI; switching the Dashboard to Arabic mirrors the layout.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S0-C3 (E7-20) — Customer app shell

**What this task does:** Empty but running customer mobile app with its tabs.

Lane: Clients · Depends on: S0-C1, S0-P3

```text
Read CLAUDE.md first. You are implementing task S0-C3 — "Customer app shell" (Clients lane) of the E7GZ PS build plan.

Read only these docs for this task:
- docs/03-design/README.md
- Customer App prototype
- NFR-34

Do:
1. apps/customer: React Native + TypeScript (Expo managed workflow), navigation matching the prototype tabs, i18n-ready (English strings in resource files only).
2. API client from packages/api-client with token storage (secure store) and refresh handling.
3. Runs against pnpm mock.

Done when (write these as tests first where possible):
- App starts on Android emulator and iOS simulator (or Expo Go) and shows the tab shell.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S0-C4 (E7-21) — Auth screens on all three surfaces

**What this task does:** Sign-up, OTP and login screens on the app, dashboard and admin.

Lane: Clients · Depends on: S0-C2, S0-C3

```text
Read CLAUDE.md first. You are implementing task S0-C4 — "Auth screens on all three surfaces" (Clients lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 09 §2, §3.1
- FRS 4.1
- Customer App prototype auth screens
- FRS F-01 (registration and phone verification)
- FRS §3.1, §3.2, §3.3 (what each surface must have)

Do:
1. Customer: register, OTP verify/resend with countdown, login, logout.
2. Venue Dashboard: venue login. Admin: admin login.
3. Show backend error codes through one error-to-message map (never hard-code rule text).

Done when (write these as tests first where possible):
- Flows work against the mock, then against the real backend once S0-P5/P6 merge.

Carry-forward from the Sprint 0 review (do these too):
- Venue Dashboard and Admin web: use the cookie session that E7-22 adds (PO decision 2 Oct 2026, Sprint 0 review S-4). The refresh token is an HttpOnly cookie the page never reads. Keep the access token in memory only, never in localStorage, sessionStorage or IndexedDB. On page load call refresh with credentials: 'include' to restore the session; logout calls the logout endpoint. Send the CSRF header E7-22 defines on refresh/logout.
- Remove the placeholder token: delete VITE_API_ACCESS_TOKEN and AppConfig.accessToken from apps/venue-dashboard and apps/admin-web (config.ts, .env.example, tests). Vite builds every VITE_* value into the public JS bundle (Sprint 0 review S-12).
- Customer app: unchanged. Tokens stay in the JSON body and in SecureStore.
- Raise the timeout of the customer test 'Customer app shell › shows the four prototype tabs': it hit the 5 s Jest limit when CI was busy (Sprint 0 review).

Requirements first, prototype for the look (PO decision 2 Oct 2026):
- The requirement docs in the read list decide WHAT this task must show and do. The prototype decides only HOW it looks. An approved PCR (PCR-001…PCR-005) overrides the FRS lines it amends.
- Before coding, list every screen, state and action the requirements need for this task (including empty, loading, error, not-allowed/ineligible and banned states) and compare it with the prototype.
- Anything the requirements need that the prototype does not show: build it anyway, in the same design: @e7gz/tokens, the components and layout patterns already used in this app and in the prototype. Never invent a new visual style.
- Anything the prototype shows that the requirements do not define (a status, rule, timer, button or message): do not build it; list it as a question for the PO.
- Status names, reason codes, timers and messages come only from docs/01-product and the backend's error codes, never from the prototype.
- Dashboard screens in English and Arabic (RTL); Admin English only; Customer English via i18n files.
- In your summary, list the screens and states you built that are NOT in the prototype, so the reviewer can check them against the requirements.

Carry-forward from E7-22 (do these too; contract in arch 09 section 2 and ADR-029):
- Venue Dashboard and Admin web: call login, refresh and logout with credentials: 'include'. The refresh token is the HttpOnly cookie e7gz_venue_refresh / e7gz_admin_refresh; the page never reads or stores it.
- Login and refresh return only { accessToken }. Keep it in memory only (a module variable or React state), never localStorage, sessionStorage or IndexedDB. Remove any web code that stores or reads a refresh token.
- Refresh and logout send no body and must send the header X-Requested-With: e7gz.
- On page load call refresh: 200 means still logged in; 401 or 403 means show the login screen.
- On a 401 from any API call: refresh once (one refresh in flight at a time), then retry the call; if the refresh fails, go to the login screen.
- Logout: call the logout endpoint, then clear the in-memory token.
- Local dev: the backend needs E7GZ_CORS_ALLOWED_ORIGINS=http://localhost:5173,http://localhost:5174.
- Tests: the token is never written to localStorage or sessionStorage; refresh and logout send the header and credentials; page load restores the session.
- Customer app: unchanged (tokens in the JSON body and SecureStore).

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S1-P0 (E7-22) — Sprint 1 API contract

**What this task does:** Add all Sprint 1 API endpoints as empty stubs by day 2 so screens can start.

Lane: Platform · Depends on: S0-P3

```text
Read CLAUDE.md first. You are implementing task S1-P0 — "Sprint 1 API contract" (Platform lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 09 §3.2, §4.2, §5
- arch 03 (Venue, Resource, Pricing, Availability)

Do:
1. Day 1–2: add controller interfaces + request/response DTOs (separate per route group) for every Sprint 1 endpoint, returning 501.
2. Regenerate openapi.json and the TS client; post the PR to both teammates for review.

Done when (write these as tests first where possible):
- Merged by day 2 so Clients can build against the mock while Engine/Platform implement.

Carry-forward from the Sprint 0 review (do these too):
- Most of Sprint 1's contract already exists: E7-11 added the 34 Sprint 1 operations as 501 stubs (packages/api-client/SPRINT-1-CONTRACT.md). Check what is missing against arch 09 instead of redoing it.
- Web session cookie for VENUE and ADMIN (PO decision 2 Oct 2026, Sprint 0 review S-4): login and refresh put the refresh token in a cookie (HttpOnly; Secure; SameSite=Strict; Path=/api/v1/{venue|admin}/auth; Max-Age = that audience's refresh lifetime). The JSON body returns only accessToken. Refresh and logout read the cookie (no body) and clear it on logout or failure. The CUSTOMER audience is unchanged (JSON body, mobile).
- CSRF protection for the cookie endpoints: refresh and logout require the request's Origin to be in the allowed list and a custom header (e.g. X-Requested-With: e7gz). Test both refusals.
- CORS: allow exactly the configured Dashboard and Admin origins (e7gz.security.cors.allowed-origins, from the environment, no wildcard, credentials allowed only for those origins). Mobile customer routes need no CORS.
- ApiSecurityConfig: add a last SecurityFilterChain that denies every request not matched by the /api/** and docs chains, so a future controller outside /api is never public by accident (Sprint 0 review S-15).
- This changes the arch 09 section 2 token response for VENUE/ADMIN: regenerate OpenAPI, tell the Clients lane (E7-21), and ask the PO to record the change in arch 09.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S1-P1 (E7-23) — Areas, venues and marketplace status

**What this task does:** Admin creates areas and venues and moves a venue live, paused or closed.

Lane: Platform · Depends on: S1-P0

```text
Read CLAUDE.md first. You are implementing task S1-P1 — "Areas, venues and marketplace status" (Platform lane) of the E7GZ PS build plan.

Read only these docs for this task:
- geo.area, venue.venue in arch 04 §9
- FR-VEN-47
- arch 01 §14 A9
- arch 12 §2 (object storage)
- PCR-005 (venue photos)
- arch 09 §1.5 (VENUE_IMAGE_* codes), §5 (venue photos)

Do:
1. Admin API: areas (create, deactivate — DB refuses if venues are ACTIVE/SUSPENDED), venues (create in ONBOARDING, edit profile, location as PostGIS point).
2. Marketplace status changes ONBOARDING → ACTIVE/INACTIVE/SUSPENDED and between the three; never back to ONBOARDING (DB enforces; map the error).
3. Venue images to SeaweedFS/S3 with signed GET URLs.

Done when (write these as tests first where possible):
- Tests for each allowed/forbidden status change and area deactivation refusal.
- Tests: photo upload -> confirm -> listed with a signed GET URL; wrong type, too large, an 11th photo and confirm before upload are refused with their codes; a removed photo is no longer listed but its row and object remain; each change has an audit row.
- Test: a venue created at lat 30.0561, lng 31.3301 reads back with the same lat and lng (not swapped).

Carry-forward from the Sprint 0 review (do these too):
- First Platform Admin (bootstrap): add a one-off, audited way to create the first admin (e.g. a command run with the worker profile) that takes the email and reads the password from the secret store or stdin, hashes it with the existing Argon2id PasswordEncoder, and refuses if any admin already exists. Never put a default admin or password in code, config or a migration. (PO decision 2 Oct 2026: no admin MFA at launch.)
- Database rights (PO decision 2 Oct 2026, Sprint 0 review S-6): the public API's role must not be able to write admin accounts. New migration V<yyyyMMddHHmm>__admin_account_grants.sql plus an update to R__security_baseline.sql: revoke INSERT/UPDATE on iam.admin_account (and every other admin-owned table: list them from the .dbm) from e7gz_app_runtime. Move admin login reads (admin/internal/AdminLoginAccounts) to the admin DataSource. Add a test that e7gz_app_runtime cannot INSERT or UPDATE iam.admin_account. This item may update docs/04-erd/physical (.dbm, security baseline, change report) as CLAUDE.md section 5 requires; the PO reviews it.

Carry-forward from E7-22 (do these too):
- Contract from E7-22 (packages/api-client/SPRINT-1-CONTRACT.md section 4, arch 09 section 5): implement GET /admin/venues/{venueId} ({venue, venueAccount or null}) and POST /admin/venues/{venueId}/activate, /deactivate, /suspend with {reason}. onboarding/complete is ONBOARDING -> INACTIVE only.
- A change the venue's current status does not allow (completing onboarding when it is not ONBOARDING, or moving to the status it already has) is 422 MARKETPLACE_STATUS_CHANGE_NOT_ALLOWED, decided in Java before the write. ACTIVE or SUSPENDED in an inactive area is AREA_INACTIVE (gd_venue_area_inactive_status, already mapped). Every change is audited with the admin's reason.

PO decisions 2 Oct 2026 — venue location and photos (do these too):
- Venue photos, Platform Admin side (PCR-005, arch 09 §5, arch 12 §2): an ObjectStore port in the venue module with an S3 adapter (AWS SDK v2; SeaweedFS locally; the bucket is private). Keys venues/{venueId}/{imageId}.{ext}. Implement GET POST /admin/venues/{venueId}/images, POST .../images/{imageId}/confirm, PUT .../images/order {imageIds}, POST .../images/{imageId}/remove (stubs first so the Clients lane can mock them). POST checks contentType (image/jpeg, image/png, image/webp), sizeBytes (max 5 MB) and the limit of 10 visible photos (all three in configuration), creates the venue.venue_image row and returns a short-lived signed PUT. confirm checks the stored object (HEAD: exists, type, size) and sets uploaded_at. remove sets removed_at; nothing is hard-deleted. Lists show only confirmed, not removed photos in sort order, each with a short-lived signed GET URL. Errors: VENUE_IMAGE_NOT_ACCEPTED, VENUE_IMAGE_LIMIT_REACHED, VENUE_IMAGE_NOT_UPLOADED (422; add them to ApiErrorCode). Every change is audited. No object-store call inside a database transaction (arch 01 §15): presign and HEAD run outside the command.
- Expose the photo service through the venue module's api package so E7-24 can add the /venue/images routes with the Owner Password.
- Migration (PCR-005): V<yyyyMMddHHmm>__venue_image_upload_and_removal.sql adds uploaded_at and removed_at (timestamptz, nullable) to venue.venue_image; update the .dbm and the change report in docs/04-erd/physical; the PO reviews it.
- Venue location: store it with ST_SetSRID(ST_MakePoint(lng, lat), 4326)::geography (longitude first) and read it back the same way.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S1-P2 (E7-24) — Venue account and Owner Password

**What this task does:** Venue login account and the Owner Password that protects sensitive actions.

Lane: Platform · Depends on: S1-P1, S0-P6

```text
Read CLAUDE.md first. You are implementing task S1-P2 — "Venue account and Owner Password" (Platform lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 10 §3 (Owner Password)
- ADR on Owner Password in arch 02
- iam.venue_account, iam.venue_owner_secret
- PCR-005 (venue photos)
- arch 09 §4.2 (venue photos)

Do:
1. Admin creates the venue account; venue login already exists (S0-P6).
2. Owner Password: Argon2id hash in iam.venue_owner_secret, set/change with version bump.
3. @OwnerProtected annotation: protected actions carry the password in the command body, verified inside the action transaction; never cached; successes/failures audited and rate-limited.
4. Architecture test that reads the FRS §2.4 protected/unprotected list as data and checks the annotations (risk I3).

Done when (write these as tests first where possible):
- Tests: wrong password audited + rate limited; no session "unlock" exists.
- Tests: venue photo confirm, reorder and remove with a wrong Owner Password return 423 OWNER_PASSWORD_INVALID and are audited; another venue's photo is NOT_FOUND.

Carry-forward from the Sprint 0 review (do these too):
- Password-hashing overload guard (Sprint 0 review S-2): every Argon2id hash and verify (login, registration, Owner Password, later password reset) uses 64 MiB, so a flood can exhaust memory. Put them all behind one bounded gate in identity (e.g. a Semaphore, permits configurable, default 4 per instance). A request that gets no permit within about 2 s fails with 429 RATE_LIMITED, and nothing is written. Test: permits + 1 concurrent hashes, one is refused.

Carry-forward from E7-22 (do these too):
- Contract from E7-22: implement POST /admin/venues/{venueId}/venue-account {loginIdentifier, password, initialOwnerPassword, languagePreference?} -> 201 AdminVenueAccount (ENABLED; language en when absent). Hash both with Argon2id (login password in iam.venue_account, Owner Password in iam.venue_owner_secret); audited; never logged or echoed. DatabaseErrorCodes already maps ux_venue_login_normalized -> VENUE_LOGIN_IDENTIFIER_TAKEN and uq_venue_account_venue -> VENUE_ACCOUNT_ALREADY_EXISTS.
- Venue login, refresh and logout now use the HttpOnly cookie session (E7-22, ADR-029, arch 09 section 2). Keep it as is.

PO decisions 2 Oct 2026 — venue location and photos (do these too):
- Venue photos, Venue Dashboard side (PCR-005, arch 09 §4.2): implement GET POST /venue/images, POST /venue/images/{imageId}/confirm {ownerPassword}, PUT /venue/images/order {imageIds, ownerPassword}, POST /venue/images/{imageId}/remove {ownerPassword}, using the photo service E7-23 exposes in the venue api package. confirm, order and remove are Owner-Password protected; POST (it only returns a signed PUT URL) is not. A venue can only see and change its own photos.
- PCR-005 adds "venue photo add, reorder and remove" to the FRS §2.4 Owner Password required list: add it to the list your architecture test reads.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S1-P3 (E7-25) — Opening hours and operating sessions (DST)

**What this task does:** Weekly opening hours turned into real open sessions, correct across clock changes.

Lane: Platform · Depends on: S1-P1

```text
Read CLAUDE.md first. You are implementing task S1-P3 — "Opening hours and operating sessions (DST)" (Platform lane) of the E7GZ PS build plan.

Read only these docs for this task:
- ADR-009
- venue.venue_opening_template, venue_opening_day, operating_session
- arch 04 §4

Do:
1. Weekly template + per-day overrides; generator creates operating_session rows (local_start/local_end + IANA timezone → tstzrange) for the booking horizon (deployment setting, default 120 days — A2).
2. Handle non-existent start, ambiguous repeat, 23/25-hour days; ex_session_no_overlap rejects overlaps.
3. Referenced sessions are immutable (DB guard) — regenerate only unreferenced ones.

Done when (write these as tests first where possible):
- DST test cases pass; overlapping session insert fails.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S1-P4 (E7-26) — Resources, activities and state changes

**What this task does:** Rooms/consoles of a venue, their status, and a history of every change.

Lane: Platform · Depends on: S1-P1

```text
Read CLAUDE.md first. You are implementing task S1-P4 — "Resources, activities and state changes" (Platform lane) of the E7GZ PS build plan.

Read only these docs for this task:
- resource.resource, resource_state_change, venue.venue_activity
- arch 04 §9.4

Do:
1. Venue API for resources with both independent status columns, console iff PlayStation, activity types.
2. Every status change writes resource_state_change (append-only).
3. Owner Password where FRS §2.4 requires it.

Done when (write these as tests first where possible):
- Tests for ck_console_iff_playstation and state-change history.

Carry-forward from E7-22 (do these too):
- Contract from E7-22: implement POST /venue/resources {activity, consoleType?, ownerPassword} (Owner Password required, PO decision 2 Oct 2026) and GET/POST /admin/venues/{venueId}/resources {activity, consoleType?} (admin, no Owner Password). The server assigns the name (FR-VEN-41): Room n / Pool Table n / Table n, n = highest existing number for that venue and activity + 1, archived resources included, so a name is never reused (uq_resource_name). Write the resource_state_change row.
- There is no PATCH /venue/resources/{id} (PO decision 2 Oct 2026, arch 09 section 4.2): console and state have their own actions.
- Implement GET /admin/activities (view only, PO decision 2 Oct 2026): the three activities and how many venues offer each (venue.venue_activity). No PATCH.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S1-P5 (E7-27) — Pricing and rounding

**What this task does:** Prices per hour and the exact rounding rule.

Lane: Platform · Depends on: S1-P4

```text
Read CLAUDE.md first. You are implementing task S1-P5 — "Pricing and rounding" (Platform lane) of the E7GZ PS build plan.

Read only these docs for this task:
- pricing.venue_default_price, resource_price_override
- ADR-017
- NFR-25

Do:
1. Venue default prices and per-resource overrides; console change clears overrides (DB trigger).
2. PriceCalculator: round(hourly × minutes / 60, 2) HALF_UP per line item using Money.

Done when (write these as tests first where possible):
- Property test over the formula; matches the DB CHECK on booking rows.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S1-P6 (E7-28) — Seed data generator

**What this task does:** One command that fills the database with realistic test data.

Lane: Platform · Depends on: S1-P3, S1-P4, S1-P5

```text
Read CLAUDE.md first. You are implementing task S1-P6 — "Seed data generator" (Platform lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 11 §3

Do:
1. A dev seed (a few venues in two areas, accounts, users, sessions, prices) and a perf seed (launch-city volume ×10) runnable by one command.
2. Shared test fixture builders (Java) that all lanes use: aVenue(), aResource(), aUser().verified(), etc.

Done when (write these as tests first where possible):
- ./gradlew seedDev and seedPerf work on a fresh DB; fixtures used by at least one test in each lane.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S1-E1 (E7-29) — Reservation ledger

**What this task does:** The room calendar: hold a time slot and make double-booking impossible.

Lane: Engine · Depends on: S0-E1

```text
Read CLAUDE.md first. You are implementing task S1-E1 — "Reservation ledger" (Engine lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 05 §3
- ADR-003
- inventory.resource_reservation

Do:
1. Repository with explicit SQL for create / release (one-way release, actual_reservation_release_at) — no truncation of planned_range.
2. Translate 23P01 from ex_no_double_booking into a typed SlotTaken failure.
3. Seed test data with SQL fixtures so this does not wait for Platform venue APIs.

Done when (write these as tests first where possible):
- Test: two overlapping inserts from two transactions — exactly one commits.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S1-E2 (E7-30) — isFree: the eight ordered checks

**What this task does:** The single "is this room free?" check with its 8 steps in order.

Lane: Engine · Depends on: S1-E1

```text
Read CLAUDE.md first. You are implementing task S1-E2 — "isFree: the eight ordered checks" (Engine lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 05 §2, §2.2
- 01 §15 rule 1 (single canonical predicate)

Do:
1. AvailabilityQuery.isFree implementing the eight checks in order, returning PredicateResult with the failing check index.
2. One implementation only; an ArchUnit/test "predicate singularity" proves only one class constructs the checks.

Done when (write these as tests first where possible):
- EC-AVAI-002 and EC-AVAI-004 from the roadmap Phase 1 exit criteria pass.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S1-E3 (E7-31) — Business date

**What this task does:** Which business day a booking belongs to (even after midnight).

Lane: Engine · Depends on: S1-E1

```text
Read CLAUDE.md first. You are implementing task S1-E3 — "Business date" (Engine lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 04 §4.3
- FR-AVL-14

Do:
1. BusinessDate resolver for in-session and out-of-hours cases (e.g. manual booking at 3 AM for a venue closing at 2 AM).

Done when (write these as tests first where possible):
- Table-driven tests including DST days.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S1-E4 (E7-32) — Concurrency harness and invariant sweep

**What this task does:** A test that fires many bookings at once, and a nightly health check.

Lane: Engine · Depends on: S1-E1

```text
Read CLAUDE.md first. You are implementing task S1-E4 — "Concurrency harness and invariant sweep" (Engine lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 11 §4
- roadmap §10

Do:
1. A reusable harness: N threads race the same slot → exactly 1 success, N−1 clean failures, 0 partial rows.
2. Nightly invariant sweep job (worker profile): overlapping active reservations, terminal rows without reason, etc.; raises an admin alert on violation.

Done when (write these as tests first where possible):
- Harness runs in CI against every inventory change.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S1-E5 (E7-33) — Nearest Availability projection

**What this task does:** Pre-compute "next available time" for every venue so lists are fast.

Lane: Engine · Depends on: S1-E2

```text
Read CLAUDE.md first. You are implementing task S1-E5 — "Nearest Availability projection" (Engine lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 05 §5
- arch 08 §7 (projection worker)
- inventory.venue_activity_availability

Do:
1. Algorithm for next available start per venue per activity.
2. Projection worker refreshes rows on inventory-affecting events; a rebuild command recomputes everything.

Done when (write these as tests first where possible):
- Projection equals a from-scratch computation on seeded data.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S1-E6 (E7-34) — Discovery API

**What this task does:** The venue list: nearest first, filters, next available time.

Lane: Engine · Depends on: S1-E5, S1-P0

```text
Read CLAUDE.md first. You are implementing task S1-E6 — "Discovery API" (Engine lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 09 §3.2
- ADR-019 (PostGIS KNN)
- NFR-11

Do:
1. Venue list with filters (area, activity), KNN distance ordering, Nearest Availability from the projection; venue detail.
2. Customer DTOs only expose customer-visible fields.

Done when (write these as tests first where possible):
- p95 < 1 s on the perf seed (S1-P6) — test recorded in CI as a non-blocking report.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S1-C1 (E7-35) — Dashboard: venue setup screens

**What this task does:** Dashboard screens to set up venue profile, hours, rooms and prices.

Lane: Clients · Depends on: S1-P0

```text
Read CLAUDE.md first. You are implementing task S1-C1 — "Dashboard: venue setup screens" (Clients lane) of the E7GZ PS build plan.

Read only these docs for this task:
- Venue Dashboard prototype
- arch 09 §4.2
- FRS §3.2 (Venue Dashboard)
- FRS F-17 (Opening Hours change)
- FRS §4.8 (Venue model, configuration and Dashboard)
- FRS §2.4 (Owner Password authorization)
- arch 09 §4.3 (Owner Password body convention)
- PCR-005 (venue photos)
- arch 09 §4.2 (venue photos)
- docs/03-design/README.md ("Decided after the prototypes, 2 Oct 2026")

Do:
1. Venue profile, opening hours (template + day overrides), resources, prices, Owner Password prompt component (reusable).
2. Built against the mock first; switch to the real API as tasks merge.

Done when (write these as tests first where possible):
- EN and AR layouts reviewed.
- Photo add, reorder and remove each ask for the Owner Password; upload progress, failure and retry states shown; checked in EN and AR.

Requirements first, prototype for the look (PO decision 2 Oct 2026):
- The requirement docs in the read list decide WHAT this task must show and do. The prototype decides only HOW it looks. An approved PCR (PCR-001…PCR-005) overrides the FRS lines it amends.
- Before coding, list every screen, state and action the requirements need for this task (including empty, loading, error, not-allowed/ineligible and banned states) and compare it with the prototype.
- Anything the requirements need that the prototype does not show: build it anyway, in the same design: @e7gz/tokens, the components and layout patterns already used in this app and in the prototype. Never invent a new visual style.
- Anything the prototype shows that the requirements do not define (a status, rule, timer, button or message): do not build it; list it as a question for the PO.
- Status names, reason codes, timers and messages come only from docs/01-product and the backend's error codes, never from the prototype.
- Every screen you add works in English and Arabic (RTL, CSS logical properties only).
- In your summary, list the screens and states you built that are NOT in the prototype, so the reviewer can check them against the requirements.

Carry-forward from E7-22 (do these too):
- Contract from E7-22: Add room = POST /venue/resources with activity, console (PlayStation only) and the Owner Password prompt. The name is assigned by the server; there is no rename or edit-room screen (no PATCH). Console change and disable/re-enable/archive/restore stay separate actions.

PO decisions 2 Oct 2026 — venue location and photos (do these too):
- Venue photos section in venue setup (PCR-005). The prototype has no photo screen: build it in the existing Dashboard design (tokens + components), EN + AR. List the photos in order and mark the first one as the venue-card photo. Add: pick a file -> POST /venue/images -> PUT the bytes to uploadUrl -> POST .../confirm with the Owner Password prompt. Reorder: one save with the Owner Password. Remove: Owner Password; say it hides the photo. Show upload progress, failure and retry. Map VENUE_IMAGE_NOT_ACCEPTED, VENUE_IMAGE_LIMIT_REACHED, VENUE_IMAGE_NOT_UPLOADED and OWNER_PASSWORD_INVALID through the error-code map; the server decides the limits (the file picker may filter to JPEG/PNG/WebP). Show images only from the signed URLs the API returns.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S1-C2 (E7-36) — Dashboard: availability schedule

**What this task does:** Dashboard grid showing each room's bookings through the day.

Lane: Clients · Depends on: S1-P0

```text
Read CLAUDE.md first. You are implementing task S1-C2 — "Dashboard: availability schedule" (Clients lane) of the E7GZ PS build plan.

Read only these docs for this task:
- Venue Dashboard prototype (schedule)
- FRS §3.2 (Venue Dashboard)
- FRS §4.3 (Availability and Inventory)
- FRS §4.8 (Venue model, configuration and Dashboard)
- arch 09 §4.1–§4.2

Do:
1. Resource × time grid for a business date showing reservations, blocks and free time; date navigation.

Done when (write these as tests first where possible):
- Works in RTL; data from the contract.

Requirements first, prototype for the look (PO decision 2 Oct 2026):
- The requirement docs in the read list decide WHAT this task must show and do. The prototype decides only HOW it looks. An approved PCR (PCR-001…PCR-005) overrides the FRS lines it amends.
- Before coding, list every screen, state and action the requirements need for this task (including empty, loading, error, not-allowed/ineligible and banned states) and compare it with the prototype.
- Anything the requirements need that the prototype does not show: build it anyway, in the same design: @e7gz/tokens, the components and layout patterns already used in this app and in the prototype. Never invent a new visual style.
- Anything the prototype shows that the requirements do not define (a status, rule, timer, button or message): do not build it; list it as a question for the PO.
- Status names, reason codes, timers and messages come only from docs/01-product and the backend's error codes, never from the prototype.
- Every screen you add works in English and Arabic (RTL, CSS logical properties only).
- In your summary, list the screens and states you built that are NOT in the prototype, so the reviewer can check them against the requirements.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S1-C3 (E7-37) — Admin: areas, venues, venue accounts

**What this task does:** Admin screens for areas, venues and venue accounts.

Lane: Clients · Depends on: S1-P0, S0-C2

```text
Read CLAUDE.md first. You are implementing task S1-C3 — "Admin: areas, venues, venue accounts" (Clients lane) of the E7GZ PS build plan.

Read only these docs for this task:
- Platform Admin prototype
- arch 09 §5
- A9 in docs/03-design/README.md
- FRS §3.3 (Platform Admin)
- FRS F-23 (Venue Account Disable), F-37 (admin correction and marketplace control)
- FRS §4.8 (Venue model)
- FRS §4.14 (Platform Admin)
- FRS §2.10 (actor / action authorization)
- FRS FR-DIS-01, FR-DIS-10, FR-DIS-11 (why the venue location matters)
- PCR-005 (venue photos)
- docs/03-design/README.md ("Decided after the prototypes, 2 Oct 2026")

Do:
1. Areas list/create/deactivate; venues list/create/edit; marketplace status control that never offers ONBOARDING after leaving it; create venue account.

Done when (write these as tests first where possible):
- Errors shown via the error-code map.
- Unit tests for the coordinate/URL parser: plain "lat, lng", "@lat,lng,zoom" URL, "?q=lat,lng" URL accepted; out-of-range values, short links and junk refused.
- The venue form sends {lat, lng} in the right order (test with Cairo: lat about 30, lng about 31); the 5 km warning shows and does not block saving.
- Photos can be added, reordered and removed in the wizard and on the venue page, with progress and error states.

Requirements first, prototype for the look (PO decision 2 Oct 2026):
- The requirement docs in the read list decide WHAT this task must show and do. The prototype decides only HOW it looks. An approved PCR (PCR-001…PCR-005) overrides the FRS lines it amends.
- Before coding, list every screen, state and action the requirements need for this task (including empty, loading, error, not-allowed/ineligible and banned states) and compare it with the prototype.
- Anything the requirements need that the prototype does not show: build it anyway, in the same design: @e7gz/tokens, the components and layout patterns already used in this app and in the prototype. Never invent a new visual style.
- Anything the prototype shows that the requirements do not define (a status, rule, timer, button or message): do not build it; list it as a question for the PO.
- Status names, reason codes, timers and messages come only from docs/01-product and the backend's error codes, never from the prototype.
- Platform Admin is English only (FRS §3.3).
- In your summary, list the screens and states you built that are NOT in the prototype, so the reviewer can check them against the requirements.

Carry-forward from E7-22 (do these too):
- Contract from E7-22 (arch 09 section 5): venue page from GET /admin/venues/{venueId}; marketplace status buttons use POST .../onboarding/complete (ONBOARDING -> INACTIVE), .../activate, .../deactivate, .../suspend, each with a reason; never offer ONBOARDING again.
- Create venue account form: POST /admin/venues/{venueId}/venue-account (login identifier, password, initial Owner Password, language). Add rooms during onboarding with GET/POST /admin/venues/{venueId}/resources (activity + console for PlayStation; the name is assigned).
- Activities page is view only: GET /admin/activities (activity + number of venues).
- Add the new codes to the error-code map: VENUE_LOGIN_IDENTIFIER_TAKEN, VENUE_ACCOUNT_ALREADY_EXISTS, MARKETPLACE_STATUS_CHANGE_NOT_ALLOWED.

PO decisions 2 Oct 2026 — venue location and photos (do these too):
- Venue location on create/edit: a map picker with Leaflet + OpenStreetMap tiles (show the OSM attribution; no API key). Centre it on the selected area's centroid if it has one, otherwise the city. The admin drops/drags one pin.
- Paste box: accepts "lat, lng" or a full Google Maps URL containing coordinates ("/@lat,lng,zoom" or "?q=lat,lng") and moves the pin. Anything else (including maps.app.goo.gl short links) shows a field error asking for coordinates and does not move the pin.
- Distance warning: when the area has a centroid and the pin is more than 5 km from it, show a non-blocking warning under the map (the admin can still save; the server does not check this).
- No geocoding (address -> coordinates); the address stays free text. Send location as {lat, lng} (GeoPoint in the generated API client). Leaflet uses [lat, lng]; do not swap them.
- Venue photos (PCR-005, arch 09 §5): a photo step in the onboarding wizard (the prototype's drop zone) and a photos section on the venue page. Add: POST /admin/venues/{venueId}/images -> PUT the bytes to uploadUrl -> POST .../confirm. Reorder (PUT .../images/order) and remove (POST .../remove; it hides the photo). Mark the first photo as the venue-card photo. Show upload progress, failure and retry; map VENUE_IMAGE_NOT_ACCEPTED, VENUE_IMAGE_LIMIT_REACHED and VENUE_IMAGE_NOT_UPLOADED through the error-code map. Show images only from the signed URLs the API returns.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S1-C4 (E7-38) — Customer app: discovery, map and venue page

**What this task does:** App: list of venues, map, and venue page.

Lane: Clients · Depends on: S1-P0, S0-C3

```text
Read CLAUDE.md first. You are implementing task S1-C4 — "Customer app: discovery, map and venue page" (Clients lane) of the E7GZ PS build plan.

Read only these docs for this task:
- Customer App prototype
- arch 09 §3.2
- FRS §3.1 (Customer Mobile App)
- FRS F-02 (discovery and venue details), F-03 (Find Available)
- FRS §4.2 (Discovery and Browsing)

Do:
1. Venue list with distance and next-available, map view, filters, venue detail with activities and prices.

Done when (write these as tests first where possible):
- Location permission handled; works on the mock then the real API.

Requirements first, prototype for the look (PO decision 2 Oct 2026):
- The requirement docs in the read list decide WHAT this task must show and do. The prototype decides only HOW it looks. An approved PCR (PCR-001…PCR-005) overrides the FRS lines it amends.
- Before coding, list every screen, state and action the requirements need for this task (including empty, loading, error, not-allowed/ineligible and banned states) and compare it with the prototype.
- Anything the requirements need that the prototype does not show: build it anyway, in the same design: @e7gz/tokens, the components and layout patterns already used in this app and in the prototype. Never invent a new visual style.
- Anything the prototype shows that the requirements do not define (a status, rule, timer, button or message): do not build it; list it as a question for the PO.
- Status names, reason codes, timers and messages come only from docs/01-product and the backend's error codes, never from the prototype.
- Customer app is English at launch, all text in the i18n resource files (FRS §3.1).
- In your summary, list the screens and states you built that are NOT in the prototype, so the reviewer can check them against the requirements.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S2-P0 (E7-39) — Sprint 2 API contract

**What this task does:** Add all Sprint 2 API endpoints as stubs by day 2.

Lane: Platform · Depends on: S1-P0

```text
Read CLAUDE.md first. You are implementing task S2-P0 — "Sprint 2 API contract" (Platform lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 09 §3.3, §4.1

Do:
1. Controller interfaces + DTOs for booking create/cancel/list/detail, venue requests accept/reject, manual booking, device token, venue push subscription, SSE stream; 501 stubs; regenerate client.

Done when (write these as tests first where possible):
- Merged by day 2.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S2-P1 (E7-40) — Deadline engine (scheduled_job)

**What this task does:** The timer engine that fires deadlines on time (e.g. 10-minute venue reply).

Lane: Platform · Depends on: S0-E1

```text
Read CLAUDE.md first. You are implementing task S2-P1 — "Deadline engine (scheduled_job)" (Platform lane) of the E7GZ PS build plan.

Read only these docs for this task:
- ADR-006
- arch 08 §2.1–§2.4, §9

Do:
1. scheduled_job repository; lease workers with FOR UPDATE SKIP LOCKED; idempotent handlers keyed by natural keys; rearm/recompute.
2. Handler registry so Engine can plug in T1, T4, T5, T6 without touching the engine.
3. Runs only in the worker profile.

Done when (write these as tests first where possible):
- Tests: two workers never run the same job; a crashed lease is retaken; late firing still produces the boundary-correct outcome.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S2-P2 (E7-41) — Transactional outbox and dispatcher

**What this task does:** Send messages only after the action is saved, with retries.

Lane: Platform · Depends on: S0-E1

```text
Read CLAUDE.md first. You are implementing task S2-P2 — "Transactional outbox and dispatcher" (Platform lane) of the E7GZ PS build plan.

Read only these docs for this task:
- ADR-004
- arch 08 §4.1–§4.3

Do:
1. Write outbox rows in the command transaction; pg_notify wake; dispatcher with exponential backoff and dead-letter; admin recover.
2. Nothing dispatches for a rolled-back transaction.

Done when (write these as tests first where possible):
- Tests: rollback → no dispatch; transient failure retried; permanent → dead-letter.

Carry-forward from E7-13 (do these too):
- Move the OTP SMS from identity CustomerRegistration's direct post-commit call to an outbox row written in the same command transaction and delivered by the dispatcher.
- The OTP code must never be stored in plain text in outbox_event.payload (arch 10 §2.2). Get the PO decision before coding: (a) the row carries only the otp_challenge id and the dispatcher generates the code (needs a schema change: code_hash is immutable after insert), or (b) the code is encrypted in the payload with a secret-store key and cleared after dispatch.

Carry-forward from the Sprint 0 review (do these too):
- SMS 'log' provider safety (Sprint 0 review S-1): today a deploy without E7GZ_SMS_PROVIDER starts normally and writes every OTP to the log. Remove the default ('log') from application.yml and refuse to start when e7gz.sms.provider=log unless the active profile is local or test. Test both cases.
- LoggingSmsSender: mask the phone number (keep the last 3 digits, arch 10 section 6), even in development.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S2-P3 (E7-42) — Idempotency on writes

**What this task does:** Pressing a button twice never creates two bookings.

Lane: Platform · Depends on: S0-E1

```text
Read CLAUDE.md first. You are implementing task S2-P3 — "Idempotency on writes" (Platform lane) of the E7GZ PS build plan.

Read only these docs for this task:
- ADR-015
- arch 08 §6

Do:
1. Idempotency-Key header on every write in scope; technical.idempotency_begin/complete; same key + same body → same response; different body → error.

Done when (write these as tests first where possible):
- Tests for replay and conflict.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S2-P4 (E7-43) — Notifications: catalogue, channels, push and email

**What this task does:** Notifications: in-app list, phone push, email, and SMS for codes only.

Lane: Platform · Depends on: S2-P2

```text
Read CLAUDE.md first. You are implementing task S2-P4 — "Notifications: catalogue, channels, push and email" (Platform lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 08 §4.4–§4.5
- Spec v5 §52
- notification.* tables (86 catalogue codes already seeded)

Do:
1. NotificationService.notify(code, recipient, reference, params) validated against the catalogue; creates notification + required deliveries in the same transaction.
2. Adapters: FCM (Android + APNs via FCM) for customers, email (Mailpit in dev), SMS for OTP only.
3. Device token register/revoke API (tokens are revoked, never deleted).

Done when (write these as tests first where possible):
- A catalogued code with missing required channels fails at commit (DB) — test it; push delivered in dev via a fake adapter.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S2-P5 (E7-44) — Venue live channel: SSE + Web Push

**What this task does:** Live updates on the dashboard, and browser push when it is closed.

Lane: Platform · Depends on: S2-P2, S2-P4

```text
Read CLAUDE.md first. You are implementing task S2-P5 — "Venue live channel: SSE + Web Push" (Platform lane) of the E7GZ PS build plan.

Read only these docs for this task:
- ADR-014
- arch 08 §5
- arch 01 §14 A6
- notification.venue_push_subscription

Do:
1. GET /api/v1/venue/stream (change signals only {type, entityId}); fan-out across replicas with LISTEN/NOTIFY; heartbeat and reconnect.
2. Web Push (VAPID) subscribe/revoke API for the Dashboard; send for VENUE_NEW_BOOKING_REQUEST, VENUE_NEW_TOURNAMENT_REQUEST and other venue IN_APP+PUSH codes.

Done when (write these as tests first where possible):
- Two API instances: an event on one reaches a stream on the other.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S2-P6 (E7-45) — Boundary test pack scaffold

**What this task does:** Test tools to set the clock and check timers to the exact second.

Lane: Platform · Depends on: S2-P1

```text
Read CLAUDE.md first. You are implementing task S2-P6 — "Boundary test pack scaffold" (Platform lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 08 §9
- roadmap Phase 2–3 exit criteria

Do:
1. Test utilities: controllable clock + command instant, "run due jobs at T", assertions at ±1 second.
2. Write the Phase 2 boundary tests as failing (@Disabled with task IDs) so Engine turns them green.

Done when (write these as tests first where possible):
- Engine lane uses it in S2-E1/E2.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S2-E1 (E7-46) — Online booking creation (O1)

**What this task does:** A customer books a room online (20 checks), the venue gets the request.

Lane: Engine · Depends on: S1-E2, S2-P0, S2-P1

```text
Read CLAUDE.md first. You are implementing task S2-E1 — "Online booking creation (O1)" (Engine lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 06 §1.3 (20 conditions)
- FRS 4.4
- Spec v5 §21–§23

Do:
1. CreateBookingCommand: the 20 named conditions in one ordered list, each citing its source, with a count assertion (risk I2).
2. Reservation + booking in one command; venue response deadline job (T1); fee snapshot from config.effective_numeric.
3. Outbox: BOOKING_CREATED (customer), VENUE_NEW_BOOKING_REQUEST (venue).

Done when (write these as tests first where possible):
- Boundary: submit at startAt−15:00 ok, −14:59 refused; sessionEnd−11 min ok, −9 min refused.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S2-E2 (E7-47) — Venue accept / reject / expiry (O2)

**What this task does:** The venue accepts or rejects within 10 minutes, or the request expires.

Lane: Engine · Depends on: S2-E1

```text
Read CLAUDE.md first. You are implementing task S2-E2 — "Venue accept / reject / expiry (O2)" (Engine lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 06 §2.1
- FR-BOK-13…16

Do:
1. Accept, reject, reject-after-expiry, response expiry (T1 handler), FR-BOK-16 operational-cause branch.
2. A SUSPENDED venue can still accept a PENDING booking created before suspension.

Done when (write these as tests first where possible):
- Accept at expiresAt−1 s succeeds; at the boundary expiry wins.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S2-E3 (E7-48) — Walk-in / manual booking (O4)

**What this task does:** The venue adds a walk-in booking.

Lane: Engine · Depends on: S1-E2, S1-E3

```text
Read CLAUDE.md first. You are implementing task S2-E3 — "Walk-in / manual booking (O4)" (Engine lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 06 §1.4 (15 conditions)
- FRS manual booking

Do:
1. ManualBookingCommand with its own 15-condition predicate; business date for out-of-hours.

Done when (write these as tests first where possible):
- Manual booking at 3 AM for a venue closing at 2 AM succeeds with a businessDate.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S2-E4 (E7-49) — Cancellation, customer buckets, contact visibility

**What this task does:** Customer cancels; booking lists; hide the phone number when not allowed.

Lane: Engine · Depends on: S2-E1

```text
Read CLAUDE.md first. You are implementing task S2-E4 — "Cancellation, customer buckets, contact visibility" (Engine lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 06 §1.5
- arch 10 §5.1
- NFR-18

Do:
1. Customer cancel (before Start only); release reservation; notifications.
2. Customer booking list buckets; venue booking list; contact-visibility resolver — field absent (not masked) when not allowed.

Done when (write these as tests first where possible):
- Contact redaction tested in every retention case; integrity sweep clean after a seeded run; J1 and J8 end-to-end tests.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S2-C1 (E7-50) — Customer app: book a room

**What this task does:** App: choose time and duration, see the price, book, wait for the venue.

Lane: Clients · Depends on: S2-P0, S1-C4

```text
Read CLAUDE.md first. You are implementing task S2-C1 — "Customer app: book a room" (Clients lane) of the E7GZ PS build plan.

Read only these docs for this task:
- Customer App prototype booking flow
- arch 09 §3.3
- FRS §3.1 (Customer Mobile App)
- FRS F-04 (create online booking)
- FRS §4.4 (Online Booking Lifecycle)
- FRS §2.11 (customer capability matrix by account state)

Do:
1. Choose activity, resource type, date/time, duration; price from API; submit with Idempotency-Key; pending screen with venue response countdown.

Done when (write these as tests first where possible):
- Error codes shown via the map; double-tap does not create two bookings.

Requirements first, prototype for the look (PO decision 2 Oct 2026):
- The requirement docs in the read list decide WHAT this task must show and do. The prototype decides only HOW it looks. An approved PCR (PCR-001…PCR-005) overrides the FRS lines it amends.
- Before coding, list every screen, state and action the requirements need for this task (including empty, loading, error, not-allowed/ineligible and banned states) and compare it with the prototype.
- Anything the requirements need that the prototype does not show: build it anyway, in the same design: @e7gz/tokens, the components and layout patterns already used in this app and in the prototype. Never invent a new visual style.
- Anything the prototype shows that the requirements do not define (a status, rule, timer, button or message): do not build it; list it as a question for the PO.
- Status names, reason codes, timers and messages come only from docs/01-product and the backend's error codes, never from the prototype.
- Customer app is English at launch, all text in the i18n resource files (FRS §3.1).
- In your summary, list the screens and states you built that are NOT in the prototype, so the reviewer can check them against the requirements.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S2-C2 (E7-51) — Customer app: My Bookings and push

**What this task does:** App: My Bookings, cancel, and receive push notifications.

Lane: Clients · Depends on: S2-P0

```text
Read CLAUDE.md first. You are implementing task S2-C2 — "Customer app: My Bookings and push" (Clients lane) of the E7GZ PS build plan.

Read only these docs for this task:
- Customer App prototype My Bookings
- arch 06 §1.5
- FRS §3.1 (Customer Mobile App)
- FRS F-05…F-08, F-10 (venue response, expiry, customer cancellation)
- FRS §4.4 (Online Booking Lifecycle)
- FRS §2.12 (notification matrix)
- FRS §4.15 (Notifications)
- arch 09 §3.3

Do:
1. Buckets exactly as the API returns them; detail; cancel; register device token for push; notification inbox list.

Done when (write these as tests first where possible):
- Push received on a device in dev.

Requirements first, prototype for the look (PO decision 2 Oct 2026):
- The requirement docs in the read list decide WHAT this task must show and do. The prototype decides only HOW it looks. An approved PCR (PCR-001…PCR-005) overrides the FRS lines it amends.
- Before coding, list every screen, state and action the requirements need for this task (including empty, loading, error, not-allowed/ineligible and banned states) and compare it with the prototype.
- Anything the requirements need that the prototype does not show: build it anyway, in the same design: @e7gz/tokens, the components and layout patterns already used in this app and in the prototype. Never invent a new visual style.
- Anything the prototype shows that the requirements do not define (a status, rule, timer, button or message): do not build it; list it as a question for the PO.
- Status names, reason codes, timers and messages come only from docs/01-product and the backend's error codes, never from the prototype.
- Customer app is English at launch, all text in the i18n resource files (FRS §3.1).
- In your summary, list the screens and states you built that are NOT in the prototype, so the reviewer can check them against the requirements.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S2-C3 (E7-52) — Dashboard: live requests with sound and Web Push

**What this task does:** Dashboard: new requests appear live with a sound, accept or reject.

Lane: Clients · Depends on: S2-P0

```text
Read CLAUDE.md first. You are implementing task S2-C3 — "Dashboard: live requests with sound and Web Push" (Clients lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 08 §5
- A6 in docs/03-design/README.md
- FRS §3.2 (Venue Dashboard)
- FRS F-05…F-08 (accept, reject, expiry, reject after expiry)
- FRS §4.4 (Online Booking Lifecycle)
- arch 09 §4.1

Do:
1. SSE client with reconnect; new request list with countdown; accept/reject; audible alert on every new booking/tournament request; Web Push subscription and service worker for when the Dashboard is closed.

Done when (write these as tests first where possible):
- Alert plays once per new request; works in AR.

Requirements first, prototype for the look (PO decision 2 Oct 2026):
- The requirement docs in the read list decide WHAT this task must show and do. The prototype decides only HOW it looks. An approved PCR (PCR-001…PCR-005) overrides the FRS lines it amends.
- Before coding, list every screen, state and action the requirements need for this task (including empty, loading, error, not-allowed/ineligible and banned states) and compare it with the prototype.
- Anything the requirements need that the prototype does not show: build it anyway, in the same design: @e7gz/tokens, the components and layout patterns already used in this app and in the prototype. Never invent a new visual style.
- Anything the prototype shows that the requirements do not define (a status, rule, timer, button or message): do not build it; list it as a question for the PO.
- Status names, reason codes, timers and messages come only from docs/01-product and the backend's error codes, never from the prototype.
- Every screen you add works in English and Arabic (RTL, CSS logical properties only).
- In your summary, list the screens and states you built that are NOT in the prototype, so the reviewer can check them against the requirements.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S2-C4 (E7-53) — Dashboard: walk-ins and today view

**What this task does:** Dashboard: add walk-ins and see today's bookings.

Lane: Clients · Depends on: S2-P0

```text
Read CLAUDE.md first. You are implementing task S2-C4 — "Dashboard: walk-ins and today view" (Clients lane) of the E7GZ PS build plan.

Read only these docs for this task:
- Venue Dashboard prototype
- FRS §3.2 (Venue Dashboard)
- FRS F-16 (Manual Booking)
- FRS §4.7 (Manual Booking)
- arch 09 §4.1

Do:
1. Manual booking form; today's bookings by resource; booking detail with contact only when the API returns it.

Done when (write these as tests first where possible):
- RTL checked.

Requirements first, prototype for the look (PO decision 2 Oct 2026):
- The requirement docs in the read list decide WHAT this task must show and do. The prototype decides only HOW it looks. An approved PCR (PCR-001…PCR-005) overrides the FRS lines it amends.
- Before coding, list every screen, state and action the requirements need for this task (including empty, loading, error, not-allowed/ineligible and banned states) and compare it with the prototype.
- Anything the requirements need that the prototype does not show: build it anyway, in the same design: @e7gz/tokens, the components and layout patterns already used in this app and in the prototype. Never invent a new visual style.
- Anything the prototype shows that the requirements do not define (a status, rule, timer, button or message): do not build it; list it as a question for the PO.
- Status names, reason codes, timers and messages come only from docs/01-product and the backend's error codes, never from the prototype.
- Every screen you add works in English and Arabic (RTL, CSS logical properties only).
- In your summary, list the screens and states you built that are NOT in the prototype, so the reviewer can check them against the requirements.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S3-P0 (E7-54) — Sprint 3 API contract

**What this task does:** Add all Sprint 3 API endpoints as stubs by day 2.

Lane: Platform · Depends on: S2-P0

```text
Read CLAUDE.md first. You are implementing task S3-P0 — "Sprint 3 API contract" (Platform lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 09 §3.3, §4.1

Do:
1. Stubs + DTOs for Late Confirm, attendance actions, profile/account endpoints; regenerate client.

Done when (write these as tests first where possible):
- Merged by day 2.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S3-P1 (E7-55) — Booking and attendance notifications

**What this task does:** Send the right notification for every booking and attendance event.

Lane: Platform · Depends on: S2-P4

```text
Read CLAUDE.md first. You are implementing task S3-P1 — "Booking and attendance notifications" (Platform lane) of the E7GZ PS build plan.

Read only these docs for this task:
- Spec v5 §52.2
- arch 08 §4.5 (copy rules)

Do:
1. Wire every customer booking/Late Confirm/attendance code listed in §52.2 to the correct trigger; EN message templates in the apps (backend sends codes + params only).
2. Copy-rule tests from arch 08 §4.5.

Done when (write these as tests first where possible):
- Each code has a test proving it is emitted exactly once for its event.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S3-P2 (E7-56) — Admin alerts

**What this task does:** Alerts to the admin when something is seriously wrong.

Lane: Platform · Depends on: S2-P2

```text
Read CLAUDE.md first. You are implementing task S3-P2 — "Admin alerts" (Platform lane) of the E7GZ PS build plan.

Read only these docs for this task:
- admin.admin_alert
- arch 12 §6
- arch 11 §5.4

Do:
1. Raise/acknowledge admin alerts; severity; admin API list/ack; used by integrity errors and the invariant sweep.

Done when (write these as tests first where possible):
- SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR produces an alert with the full payload.

Carry-forward from the Sprint 0 review (do these too):
- Alert on refresh-token reuse: when AuthSessions.refresh detects a reused rotated token (today only log.warn), raise an admin alert (arch 10 section 2.3: 'revokes the entire chain and alerts'). Sprint 0 review S-9.
- Alert when an account reaches the per-account login limit added by E7-57.
- Audit Platform Admin and Venue Account login success and failure with the AuditWriter: account id when known, audience and outcome only, never the identifier, the password or the IP in clear. If no alert or audit catalogue code fits, ask the PO before adding one.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S3-P3 (E7-57) — Profile and account management

**What this task does:** Edit profile, change username, phone, email, reset password.

Lane: Platform · Depends on: S0-P6

```text
Read CLAUDE.md first. You are implementing task S3-P3 — "Profile and account management" (Platform lane) of the E7GZ PS build plan.

Read only these docs for this task:
- FRS 4.1
- Spec v5 §7.5 (username cooldown)

Do:
1. Edit full name, username (30-day cooldown enforced by DB), phone change with OTP, email change, password reset via OTP; PASSWORD_RESET / PHONE_CHANGED / EMAIL_CHANGED notifications.

Done when (write these as tests first where possible):
- Cooldown error mapped; tokens revoked on password reset.

Carry-forward from E7-13 (do these too):
- Password reset and change use the same rule as registration: identity PasswordPolicy.isAcceptable (VALIDATION RULES §2.1: at least 8 characters, at least one letter and one digit; 400 VALIDATION_FAILED on field password).

Carry-forward from the Sprint 0 review (do these too):
- Replace unverified registrations (PO decision 2 Oct 2026, Sprint 0 review S-3): when someone registers with a phone or username held by an account that was never phone-verified, the old account is replaced (phone and username released, its OTP challenges superseded, its refresh tokens revoked) and the new registration goes ahead. A verified account still returns PHONE_TAKEN / USERNAME_TAKEN. uq_user_account_phone_e164 covers unverified rows today, and anonymisation is an admin-only producer. If this needs a schema or function change, make it a new migration + .dbm + change report for PO review (CLAUDE.md section 5). Test: registering on an unverified phone succeeds, and the old account can no longer log in.
- Verifying through resend sets a new password: a phone verification that started from /otp/send (by phone, without the registration's own challenge) must include a new password in the same request (PasswordPolicy). It sets the password and revokes every refresh token of that account, so an account someone else created with your phone cannot be kept by them. Contract change: regenerate OpenAPI and tell the Clients lane.
- Per-account login limit (PO decision 2 Oct 2026, Sprint 0 review S-7): in addition to the existing per (identifier + IP) limit, count failed logins per account across all IPs: 50 failures in 1 hour pause login for that account for 15 minutes (iam.security_rate_limit_counter, limiter_type LOGIN, its own action_class such as CUSTOMER_ACCOUNT / VENUE_ACCOUNT / ADMIN_ACCOUNT, cooldown_until; no schema change, but note the new action_class values in the change report). All three audiences. Fails closed like the existing limiter. E7-56 raises the alert.
- Password reset and change hash through the Argon2id gate from E7-24.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S3-E1 (E7-58) — Late Confirm (O3)

**What this task does:** Late Confirm: the venue offers a late slot, the customer accepts in time.

Lane: Engine · Depends on: S2-E2, S2-P6

```text
Read CLAUDE.md first. You are implementing task S3-E1 — "Late Confirm (O3)" (Engine lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 06 §2.2
- arch 08 §2.6
- arch 10 §5.2
- FRS 4.5

Do:
1. Initiate, accept, decline, timeout; T2 and T3; warning job only when the window > 60 s; neutral venue-facing failure shaping.

Done when (write these as tests first where possible):
- 7:59 PM initiation → exactly 60 s and no warning; 7:50 PM → 5 min and one warning; a customer at 4 commitments: blocked, no hold, venue message reveals nothing.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S3-E2 (E7-59) — Check-in, late present, no-show (O13)

**What this task does:** Check-in, "arrived late", and no-show.

Lane: Engine · Depends on: S2-E2

```text
Read CLAUDE.md first. You are implementing task S3-E2 — "Check-in, late present, no-show (O13)" (Engine lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 07 §7
- FRS 4.6

Do:
1. Check-in window, Mark Present — Late, no-show; release reservation at the no-show instant; attendance_record rules.

Done when (write these as tests first where possible):
- Check-in at +14:59 ok, +15:00 refused and Late Present offered; Late-Present cannot become no-show.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S3-E3 (E7-60) — Start-time revalidation (O11)

**What this task does:** Re-check everything at start time and cancel with the right reason if needed.

Lane: Engine · Depends on: S3-E2

```text
Read CLAUDE.md first. You are implementing task S3-E3 — "Start-time revalidation (O11)" (Engine lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 05 §7
- arch 12 §6.2

Do:
1. At Start: revalidate, apply the seven-level reason priority, SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR + admin alert when inventory contradicts state.

Done when (write these as tests first where possible):
- Forcing a resource DISABLED behind the predicate produces the integrity error + alert.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S3-E4 (E7-61) — Completion and attendance defaults (O12)

**What this task does:** Finish bookings automatically and set the final attendance result.

Lane: Engine · Depends on: S3-E2

```text
Read CLAUDE.md first. You are implementing task S3-E4 — "Completion and attendance defaults (O12)" (Engine lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 07 §7.3

Do:
1. Completion job; the eight attendance-default rules; ATTENDANCE_UNCONFIRMED path with no points.
2. Enable all four timer boundary tests (±1 s) and journeys J2–J5.

Done when (write these as tests first where possible):
- Boundary pack green.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S3-C1 (E7-62) — Customer app: Late Confirm and status

**What this task does:** App: Late Confirm screen with countdown and booking status timeline.

Lane: Clients · Depends on: S3-P0

```text
Read CLAUDE.md first. You are implementing task S3-C1 — "Customer app: Late Confirm and status" (Clients lane) of the E7GZ PS build plan.

Read only these docs for this task:
- Customer App prototype (Late Confirm copy placement)
- FRS §3.1 (Customer Mobile App)
- FRS F-09 (Late Confirmation)
- FRS §4.5 (Late Confirmation)
- FRS §2.5 (timer and boundary matrix)
- arch 09 §3.3

Do:
1. Late Confirm received screen with exact countdown, accept/decline, expired states; booking status timeline.

Done when (write these as tests first where possible):
- Countdown driven by server expires_at, not device time.

Requirements first, prototype for the look (PO decision 2 Oct 2026):
- The requirement docs in the read list decide WHAT this task must show and do. The prototype decides only HOW it looks. An approved PCR (PCR-001…PCR-005) overrides the FRS lines it amends.
- Before coding, list every screen, state and action the requirements need for this task (including empty, loading, error, not-allowed/ineligible and banned states) and compare it with the prototype.
- Anything the requirements need that the prototype does not show: build it anyway, in the same design: @e7gz/tokens, the components and layout patterns already used in this app and in the prototype. Never invent a new visual style.
- Anything the prototype shows that the requirements do not define (a status, rule, timer, button or message): do not build it; list it as a question for the PO.
- Status names, reason codes, timers and messages come only from docs/01-product and the backend's error codes, never from the prototype.
- Customer app is English at launch, all text in the i18n resource files (FRS §3.1).
- In your summary, list the screens and states you built that are NOT in the prototype, so the reviewer can check them against the requirements.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S3-C2 (E7-63) — Dashboard: attendance and Late Confirm

**What this task does:** Dashboard: check-in, late, no-show and Late Confirm buttons.

Lane: Clients · Depends on: S3-P0

```text
Read CLAUDE.md first. You are implementing task S3-C2 — "Dashboard: attendance and Late Confirm" (Clients lane) of the E7GZ PS build plan.

Read only these docs for this task:
- Venue Dashboard prototype
- FRS §3.2 (Venue Dashboard)
- FRS F-09, F-12, F-13, F-14 (Late Confirmation, check-in, Present — Late, no-show)
- FRS §4.5, §4.6 (Late Confirmation; Attendance)
- FRS §2.5 (timer and boundary matrix)
- arch 09 §4.1

Do:
1. Check-in, Mark Present — Late, no-show actions with their windows; initiate Late Confirm; neutral failure message.

Done when (write these as tests first where possible):
- Actions disabled outside windows returned by the API.

Requirements first, prototype for the look (PO decision 2 Oct 2026):
- The requirement docs in the read list decide WHAT this task must show and do. The prototype decides only HOW it looks. An approved PCR (PCR-001…PCR-005) overrides the FRS lines it amends.
- Before coding, list every screen, state and action the requirements need for this task (including empty, loading, error, not-allowed/ineligible and banned states) and compare it with the prototype.
- Anything the requirements need that the prototype does not show: build it anyway, in the same design: @e7gz/tokens, the components and layout patterns already used in this app and in the prototype. Never invent a new visual style.
- Anything the prototype shows that the requirements do not define (a status, rule, timer, button or message): do not build it; list it as a question for the PO.
- Status names, reason codes, timers and messages come only from docs/01-product and the backend's error codes, never from the prototype.
- Every screen you add works in English and Arabic (RTL, CSS logical properties only).
- In your summary, list the screens and states you built that are NOT in the prototype, so the reviewer can check them against the requirements.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S3-C3 (E7-64) — Customer app: profile and account

**What this task does:** App: profile and account screens.

Lane: Clients · Depends on: S3-P0

```text
Read CLAUDE.md first. You are implementing task S3-C3 — "Customer app: profile and account" (Clients lane) of the E7GZ PS build plan.

Read only these docs for this task:
- Customer App prototype profile
- FRS §3.1 (Customer Mobile App)
- FRS §4.1 (Accounts, Identity and Verification)
- arch 09 §3.1

Do:
1. Profile edit, username change with cooldown message, phone/email change with OTP, password reset.

Done when (write these as tests first where possible):
- All error codes mapped.

Requirements first, prototype for the look (PO decision 2 Oct 2026):
- The requirement docs in the read list decide WHAT this task must show and do. The prototype decides only HOW it looks. An approved PCR (PCR-001…PCR-005) overrides the FRS lines it amends.
- Before coding, list every screen, state and action the requirements need for this task (including empty, loading, error, not-allowed/ineligible and banned states) and compare it with the prototype.
- Anything the requirements need that the prototype does not show: build it anyway, in the same design: @e7gz/tokens, the components and layout patterns already used in this app and in the prototype. Never invent a new visual style.
- Anything the prototype shows that the requirements do not define (a status, rule, timer, button or message): do not build it; list it as a question for the PO.
- Status names, reason codes, timers and messages come only from docs/01-product and the backend's error codes, never from the prototype.
- Customer app is English at launch, all text in the i18n resource files (FRS §3.1).
- In your summary, list the screens and states you built that are NOT in the prototype, so the reviewer can check them against the requirements.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S4-P0 (E7-65) — Sprint 4 API contract

**What this task does:** Add all Sprint 4 API endpoints as stubs by day 2.

Lane: Platform · Depends on: S3-P0

```text
Read CLAUDE.md first. You are implementing task S4-P0 — "Sprint 4 API contract" (Platform lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 09 §3.4, §5

Do:
1. Stubs + DTOs for standing, points, referrals, the customer wallet (GET /customer/wallet: balance + transactions, read-only — PO decision A11 / PCR-004), and admin trust/ban/VP/points/config endpoints; regenerate client. Still no admin wallet adjust (FR-PTS-11).

Done when (write these as tests first where possible):
- Merged by day 2.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S4-P1 (E7-66) — Loyalty ledgers and fee treatment

**What this task does:** Points and wallet ledgers, and what happens to the booking fee in every case.

Lane: Platform · Depends on: S2-E4

```text
Read CLAUDE.md first. You are implementing task S4-P1 — "Loyalty ledgers and fee treatment" (Platform lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 04 §4.6
- arch 03 §16
- loyalty.* producers

Do:
1. Call the loyalty producers (post_points, record_fee_receipt, record_refund, settle_refund); MIN compensation rule; fee treatment as an exhaustive switch with no default branch.
2. Customer wallet read endpoint GET /customer/wallet: balance and transaction history from loyalty.wallet_account / loyalty.wallet_transaction (read-only; the wallet is only credited by booking-fee refunds and never pays the venue gaming charge).

Done when (write these as tests first where possible):
- Every terminal reason maps to a fee treatment (test fails on a new unmapped reason).

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S4-P2 (E7-67) — Referrals

**What this task does:** Invite a friend: referral rewards and reversals.

Lane: Platform · Depends on: S4-P1, S0-P5

```text
Read CLAUDE.md first. You are implementing task S4-P2 — "Referrals" (Platform lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 03 §16
- FR-PTS-06

Do:
1. Referral code on signup; value read at OTP verification; reward/invalidate via producers.

Done when (write these as tests first where possible):
- Reversing +1,000 at 300 points → balance 0 and 700 unrecovered.

Carry-forward from E7-13 (do these too):
- Reward or invalidate the PENDING referral inside the verify-phone transaction (identity VerifyPhoneCommand): loyalty.assert_referral refuses the commit otherwise ("OTP success must reconcile referral reward/invalidity before commit").
- The referral code format (8 characters, no look-alike letters; identity ReferralCodes) was chosen in E7-13: confirm it with the PO.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S4-P3 (E7-68) — Configuration service (fees and points)

**What this task does:** Admin changes the booking fee and points values (from a date onward).

Lane: Platform · Depends on: S0-E1

```text
Read CLAUDE.md first. You are implementing task S4-P3 — "Configuration service (fees and points)" (Platform lane) of the E7GZ PS build plan.

Read only these docs for this task:
- ADR-028
- arch 11 (config scope)
- arch 01 §14 A8

Do:
1. Read effective values via config.effective_numeric; admin changes via config.change_configuration / loyalty.configure; only fees and points are configurable (A8).

Done when (write these as tests first where possible):
- Changing the booking fee affects only bookings created after the change.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S4-E1 (E7-69) — Trust events and replay

**What this task does:** Trust history of every customer, recalculated exactly from events.

Lane: Engine · Depends on: S3-E4

```text
Read CLAUDE.md first. You are implementing task S4-E1 — "Trust events and replay" (Engine lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 07 §3
- arch 04 §4.5

Do:
1. Emit trust events from attendance outcomes; pure replay and resolveEffectiveOutcomes; as-of queries; replay never writes (prove with a read-only connection).

Done when (write these as tests first where possible):
- Seeded replay reproduces history; reversing a Monday no-show on Friday changes the Monday position.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S4-E2 (E7-70) — Reconciler and attendance corrections

**What this task does:** Fix attendance mistakes and recalculate trust correctly.

Lane: Engine · Depends on: S4-E1

```text
Read CLAUDE.md first. You are implementing task S4-E2 — "Reconciler and attendance corrections" (Engine lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 07 §4

Do:
1. Reconciler with the three idempotency keys; correction chains → one effective outcome.

Done when (write these as tests first where possible):
- Three chained corrections → exactly one effective outcome; double replay creates nothing twice.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S4-E3 (E7-71) — Bans and the ban cascade (O14)

**What this task does:** Temporary bans after repeated no-shows, and their effects.

Lane: Engine · Depends on: S4-E2

```text
Read CLAUDE.md first. You are implementing task S4-E3 — "Bans and the ban cascade (O14)" (Engine lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 07 §5, §5.4

Do:
1. ban_qualification vs ban_instance; one active ban; MAX extension; cascade once via trust producers; BAN notifications.

Done when (write these as tests first where possible):
- Second qualification during an active ban → one instance, advanced stage, BAN_EXTENDED.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S4-E4 (E7-72) — Verified Player

**What this task does:** The Verified Player badge: earning, losing, restoring it.

Lane: Engine · Depends on: S4-E2

```text
Read CLAUDE.md first. You are implementing task S4-E4 — "Verified Player" (Engine lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 07 §6

Do:
1. Qualification, operational status, transitions, admin protection.

Done when (write these as tests first where possible):
- Revoke by correction then re-qualify → VERIFIED with two transition IDs and no admin action.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S4-E5 (E7-73) — Tournament entity (early, for Sprint 5)

**What this task does:** Basic tournament records early, so venue emergencies can handle them.

Lane: Engine · Depends on: S1-E1

```text
Read CLAUDE.md first. You are implementing task S4-E5 — "Tournament entity (early, for Sprint 5)" (Engine lane) of the E7GZ PS build plan.

Read only these docs for this task:
- roadmap §7 dependency note
- arch 04 §4.4

Do:
1. Repositories/aggregates for tournament, tournament_resource, participants and hold reservation kinds — enough for owner-review and emergency cascades to find and cancel tournaments. No UI.

Done when (write these as tests first where possible):
- A test creates a tournament with holds via the aggregate.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S4-C1 (E7-74) — Customer app: standing, points, wallet, referrals

**What this task does:** App: my standing, points, wallet and referral code.

Lane: Clients · Depends on: S4-P0

```text
Read CLAUDE.md first. You are implementing task S4-C1 — "Customer app: standing, points, wallet, referrals" (Clients lane) of the E7GZ PS build plan.

Read only these docs for this task:
- Customer App prototype
- PCR-004 (Customer Wallet Screen: a read-only wallet IS shown at launch; it overrides FRS §3.1 'hidden-at-launch Wallet')
- FRS §3.1 (Customer Mobile App)
- FRS F-32…F-35 (bans, Verified Player, points, referrals)
- FRS §4.11, §4.12 (Trust and bans; Points, Referrals, Wallet and Fees)
- FRS §2.11 (customer capability matrix)
- arch 09 §3.4

Do:
1. Account standing (ban, Verified Player), points history, referral code sharing.
2. Wallet screen (PO decision A11 / PCR-004): balance and transaction history, read-only, from GET /customer/wallet. It explains that the wallet holds booking-fee refunds. No top-up, no pay-with-wallet.

Done when (write these as tests first where possible):
- Matches API codes; no rule text hard-coded.

Requirements first, prototype for the look (PO decision 2 Oct 2026):
- The requirement docs in the read list decide WHAT this task must show and do. The prototype decides only HOW it looks. An approved PCR (PCR-001…PCR-005) overrides the FRS lines it amends.
- Before coding, list every screen, state and action the requirements need for this task (including empty, loading, error, not-allowed/ineligible and banned states) and compare it with the prototype.
- Anything the requirements need that the prototype does not show: build it anyway, in the same design: @e7gz/tokens, the components and layout patterns already used in this app and in the prototype. Never invent a new visual style.
- Anything the prototype shows that the requirements do not define (a status, rule, timer, button or message): do not build it; list it as a question for the PO.
- Status names, reason codes, timers and messages come only from docs/01-product and the backend's error codes, never from the prototype.
- Customer app is English at launch, all text in the i18n resource files (FRS §3.1).
- In your summary, list the screens and states you built that are NOT in the prototype, so the reviewer can check them against the requirements.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S4-C2 (E7-75) — Admin: trust, bans, Verified Player, points, configuration

**What this task does:** Admin: trust fixes, bans, badges, points, fees.

Lane: Clients · Depends on: S4-P0

```text
Read CLAUDE.md first. You are implementing task S4-C2 — "Admin: trust, bans, Verified Player, points, configuration" (Clients lane) of the E7GZ PS build plan.

Read only these docs for this task:
- Platform Admin prototype
- arch 09 §5
- FRS §3.3 (Platform Admin)
- FRS F-30…F-35 (trust replay, attendance correction, bans, Verified Player, points, referrals)
- FRS §4.11, §4.12, §4.14
- FRS §2.10 (actor / action authorization)

Do:
1. Trust corrections, ban lift, Verified Player actions, points adjustment, fees/points configuration with effective-from display.

Done when (write these as tests first where possible):
- Every admin write asks for a reason where the API requires it.

Requirements first, prototype for the look (PO decision 2 Oct 2026):
- The requirement docs in the read list decide WHAT this task must show and do. The prototype decides only HOW it looks. An approved PCR (PCR-001…PCR-005) overrides the FRS lines it amends.
- Before coding, list every screen, state and action the requirements need for this task (including empty, loading, error, not-allowed/ineligible and banned states) and compare it with the prototype.
- Anything the requirements need that the prototype does not show: build it anyway, in the same design: @e7gz/tokens, the components and layout patterns already used in this app and in the prototype. Never invent a new visual style.
- Anything the prototype shows that the requirements do not define (a status, rule, timer, button or message): do not build it; list it as a question for the PO.
- Status names, reason codes, timers and messages come only from docs/01-product and the backend's error codes, never from the prototype.
- Platform Admin is English only (FRS §3.3).
- In your summary, list the screens and states you built that are NOT in the prototype, so the reviewer can check them against the requirements.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S4-C3 (E7-76) — Dashboard: Arabic RTL review of built modules

**What this task does:** Check every dashboard screen in Arabic and fix layout.

Lane: Clients · Depends on: S2-C4

```text
Read CLAUDE.md first. You are implementing task S4-C3 — "Dashboard: Arabic RTL review of built modules" (Clients lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 11 §6
- risk I8

Do:
1. Go through every Dashboard screen built so far in AR; fix mirroring, number/date formatting, icons that must not flip.

Done when (write these as tests first where possible):
- Screenshot checklist attached to the PR.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S5-P0 (E7-77) — Sprint 5 API contract

**What this task does:** Add all Sprint 5 API endpoints as stubs by day 2.

Lane: Platform · Depends on: S4-P0

```text
Read CLAUDE.md first. You are implementing task S5-P0 — "Sprint 5 API contract" (Platform lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 09 §4.2, §5

Do:
1. Stubs + DTOs for maintenance, closures, emergencies, owner review, account disable; regenerate client.

Done when (write these as tests first where possible):
- Merged by day 2.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S5-P1 (E7-78) — Venue account disable (O10) and attendance protection

**What this task does:** Admin disables a venue account and what happens to its bookings.

Lane: Platform · Depends on: S3-E4

```text
Read CLAUDE.md first. You are implementing task S5-P1 — "Venue account disable (O10) and attendance protection" (Platform lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 06 §2.8
- arch 07 §7.5

Do:
1. Disable/re-enable with the five per-state effects; restriction intervals; Platform Attendance Protection.

Done when (write these as tests first where possible):
- Two disable/re-enable cycles in one session → one protection event and one reset.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S5-P2 (E7-79) — Fault-injection harness and sellable-gap detector

**What this task does:** Tests that break a process halfway and prove nothing is left half-done.

Lane: Platform · Depends on: S1-E4

```text
Read CLAUDE.md first. You are implementing task S5-P2 — "Fault-injection harness and sellable-gap detector" (Platform lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 12 §4
- arch 11 §4

Do:
1. Inject a failure at each step of a cascade and assert full rollback; add the sellable-gap check to the invariant sweep.

Done when (write these as tests first where possible):
- Used by S5-E2 and S5-E4 tests.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S5-P3 (E7-80) — Operational notifications

**What this task does:** Notifications for emergencies, closures, refunds and admin events.

Lane: Platform · Depends on: S2-P4

```text
Read CLAUDE.md first. You are implementing task S5-P3 — "Operational notifications" (Platform lane) of the E7GZ PS build plan.

Read only these docs for this task:
- Spec v5 §52.2 (operational), §52.5, §52.6

Do:
1. Wire the operational customer codes, venue codes and admin codes (emergencies, owner review, maintenance starting soon, refunds).

Done when (write these as tests first where possible):
- Each code emitted exactly once per event.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S5-E1 (E7-81) — Maintenance and closures

**What this task does:** Maintenance windows and venue closures block the calendar.

Lane: Engine · Depends on: S1-E2

```text
Read CLAUDE.md first. You are implementing task S5-E1 — "Maintenance and closures" (Engine lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 04 §9.7
- ADR-010
- A1 (overlapping maintenance allowed)

Do:
1. Maintenance windows in Layer 1 with the range_agg rolling threshold (Owner Password over the threshold).
2. Venue closures: record the closure in operations.venue_closure, run the closure cascade (release affected commitments), then insert one CLOSURE row per resource in inventory.resource_reservation (arch 05 §3 table). The availability check reads both. Both steps are needed — they do not conflict.

Done when (write these as tests first where possible):
- Splitting a 30-hour maintenance into two windows with a 1-minute gap still demands the Owner Password.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S5-E2 (E7-82) — Emergencies and cascades (O7/O8)

**What this task does:** Venue or room emergency: cancel or pause affected bookings safely.

Lane: Engine · Depends on: S5-E1, S5-P2

```text
Read CLAUDE.md first. You are implementing task S5-E2 — "Emergencies and cascades (O7/O8)" (Engine lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 06 §2.5
- arch 05 §3.1

Do:
1. Venue and resource emergencies, status lifecycle incl. conversion, selling blocks (one active row per subject), all-or-nothing cascades.

Done when (write these as tests first where possible):
- Fault injection at every step leaves no partial cascade; a resource emergency does not block other resources.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S5-E3 (E7-83) — Timer pause/resume and feasibility

**What this task does:** Pause and resume timers during emergencies (30-minute limits).

Lane: Engine · Depends on: S5-E2

```text
Read CLAUDE.md first. You are implementing task S5-E3 — "Timer pause/resume and feasibility" (Engine lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 08 §2.5
- FR-OPS-34…41

Do:
1. Pause/resume with the two 30-minute counters; optimistic feasibility test.

Done when (write these as tests first where possible):
- 12 min venue + 19 min resource pause → cancels at 30 min under the resource reason; feasibility deterministic.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S5-E4 (E7-84) — Owner review cascade (O9)

**What this task does:** Owner review: lock the venue down when limits are exceeded.

Lane: Engine · Depends on: S5-E2, S4-E5

```text
Read CLAUDE.md first. You are implementing task S5-E4 — "Owner review cascade (O9)" (Engine lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 06 §2.7

Do:
1. Owner review entry and cascade (no Owner Password anywhere in it), including tournaments.

Done when (write these as tests first where possible):
- END EMERGENCY on an over-threshold resource leaves it unsellable under owner review; J6, J7, J11 pass.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S5-C1 (E7-85) — Dashboard: operations screens

**What this task does:** Dashboard: maintenance, closures and emergency screens.

Lane: Clients · Depends on: S5-P0

```text
Read CLAUDE.md first. You are implementing task S5-C1 — "Dashboard: operations screens" (Clients lane) of the E7GZ PS build plan.

Read only these docs for this task:
- Venue Dashboard prototype
- FRS §3.2 (Venue Dashboard)
- FRS F-18…F-21 (maintenance, venue emergency, resource outage, timer pause/resume)
- FRS §4.9 (Maintenance, Emergencies and Operational Restrictions)
- FRS §2.4 (Owner Password authorization)
- arch 09 §4.2, §4.3

Do:
1. Maintenance, closures, venue/resource emergency declare/end, owner review banner; Owner Password prompts.

Done when (write these as tests first where possible):
- AR checked.

Requirements first, prototype for the look (PO decision 2 Oct 2026):
- The requirement docs in the read list decide WHAT this task must show and do. The prototype decides only HOW it looks. An approved PCR (PCR-001…PCR-005) overrides the FRS lines it amends.
- Before coding, list every screen, state and action the requirements need for this task (including empty, loading, error, not-allowed/ineligible and banned states) and compare it with the prototype.
- Anything the requirements need that the prototype does not show: build it anyway, in the same design: @e7gz/tokens, the components and layout patterns already used in this app and in the prototype. Never invent a new visual style.
- Anything the prototype shows that the requirements do not define (a status, rule, timer, button or message): do not build it; list it as a question for the PO.
- Status names, reason codes, timers and messages come only from docs/01-product and the backend's error codes, never from the prototype.
- Every screen you add works in English and Arabic (RTL, CSS logical properties only).
- In your summary, list the screens and states you built that are NOT in the prototype, so the reviewer can check them against the requirements.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S5-C2 (E7-86) — Admin: owner review, disable, alerts

**What this task does:** Admin: owner review, disable venue account, alerts inbox.

Lane: Clients · Depends on: S5-P0

```text
Read CLAUDE.md first. You are implementing task S5-C2 — "Admin: owner review, disable, alerts" (Clients lane) of the E7GZ PS build plan.

Read only these docs for this task:
- Platform Admin prototype
- FRS §3.3 (Platform Admin)
- FRS F-22, F-23, F-24 (owner review, venue account disable, attendance protection)
- FRS §4.9, §4.14
- arch 09 §5

Do:
1. Owner review queue, venue account disable/enable, emergencies monitor, admin alerts inbox with acknowledge.

Done when (write these as tests first where possible):
- Reasons required where the API requires them.

Requirements first, prototype for the look (PO decision 2 Oct 2026):
- The requirement docs in the read list decide WHAT this task must show and do. The prototype decides only HOW it looks. An approved PCR (PCR-001…PCR-005) overrides the FRS lines it amends.
- Before coding, list every screen, state and action the requirements need for this task (including empty, loading, error, not-allowed/ineligible and banned states) and compare it with the prototype.
- Anything the requirements need that the prototype does not show: build it anyway, in the same design: @e7gz/tokens, the components and layout patterns already used in this app and in the prototype. Never invent a new visual style.
- Anything the prototype shows that the requirements do not define (a status, rule, timer, button or message): do not build it; list it as a question for the PO.
- Status names, reason codes, timers and messages come only from docs/01-product and the backend's error codes, never from the prototype.
- Platform Admin is English only (FRS §3.3).
- In your summary, list the screens and states you built that are NOT in the prototype, so the reviewer can check them against the requirements.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S5-C3 (E7-87) — Customer app: operational states

**What this task does:** App: show emergency cancellations, pauses and refunds.

Lane: Clients · Depends on: S5-P0

```text
Read CLAUDE.md first. You are implementing task S5-C3 — "Customer app: operational states" (Clients lane) of the E7GZ PS build plan.

Read only these docs for this task:
- Spec v5 §52.2 operational codes
- FRS §3.1 (Customer Mobile App)
- FRS F-19, F-20, F-21 (emergencies and timer pause as the customer sees them)
- FRS §4.9 (Operational Restrictions)
- FRS §2.12 (notification matrix)
- arch 09 §3.3

Do:
1. Show operational cancellations, interruptions, paused timers and refunds using the codes.

Done when (write these as tests first where possible):
- Every operational code has a message.

Requirements first, prototype for the look (PO decision 2 Oct 2026):
- The requirement docs in the read list decide WHAT this task must show and do. The prototype decides only HOW it looks. An approved PCR (PCR-001…PCR-005) overrides the FRS lines it amends.
- Before coding, list every screen, state and action the requirements need for this task (including empty, loading, error, not-allowed/ineligible and banned states) and compare it with the prototype.
- Anything the requirements need that the prototype does not show: build it anyway, in the same design: @e7gz/tokens, the components and layout patterns already used in this app and in the prototype. Never invent a new visual style.
- Anything the prototype shows that the requirements do not define (a status, rule, timer, button or message): do not build it; list it as a question for the PO.
- Status names, reason codes, timers and messages come only from docs/01-product and the backend's error codes, never from the prototype.
- Customer app is English at launch, all text in the i18n resource files (FRS §3.1).
- In your summary, list the screens and states you built that are NOT in the prototype, so the reviewer can check them against the requirements.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S6-P0 (E7-88) — Sprint 6 API contract

**What this task does:** Add all Sprint 6 API endpoints as stubs by day 2.

Lane: Platform · Depends on: S5-P0

```text
Read CLAUDE.md first. You are implementing task S6-P0 — "Sprint 6 API contract" (Platform lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 09 §3.4, §4

Do:
1. Stubs + DTOs for tournaments (customer, venue, admin); regenerate client.

Done when (write these as tests first where possible):
- Merged by day 2.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S6-P1 (E7-89) — Tournament Owner Password matrix and notifications

**What this task does:** Owner Password rules and notifications for tournaments.

Lane: Platform · Depends on: S2-P4, S1-P2

```text
Read CLAUDE.md first. You are implementing task S6-P1 — "Tournament Owner Password matrix and notifications" (Platform lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 10 §3.2
- Spec v5 §52 tournament codes

Do:
1. @OwnerProtected on the tournament actions listed in the matrix; wire tournament notifications and abuse alerts.

Done when (write these as tests first where possible):
- Matrix test reads the FRS list as data.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S6-P2 (E7-90) — Tournament reachability and boundary tests

**What this task does:** Tests proving every tournament outcome can happen and happens in the right order.

Lane: Platform · Depends on: S2-P6

```text
Read CLAUDE.md first. You are implementing task S6-P2 — "Tournament reachability and boundary tests" (Platform lane) of the E7GZ PS build plan.

Read only these docs for this task:
- roadmap Phase 6 exit criteria

Do:
1. Exhaustive reachability test for every tournament status/reason; priority test; boundary tests (−1h59m refused, −2h ok).

Done when (write these as tests first where possible):
- Tests written first, turned green by the Engine tasks.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S6-E1 (E7-91) — Tournament creation and holds (O16 part 1)

**What this task does:** Create a tournament (by a player or a venue) and hold the rooms.

Lane: Engine · Depends on: S4-E5, S6-P0

```text
Read CLAUDE.md first. You are implementing task S6-E1 — "Tournament creation and holds (O16 part 1)" (Engine lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 06 §3.4
- arch 05 §3.2
- arch 03 §7

Do:
1. Player- and venue-hosted creation; cost snapshot; creation fee; Approval Hold → Tournament Hold; one pending request per creator.

Done when (write these as tests first where possible):
- A player request against a resource with a confirmed booking fails and the booking is untouched.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S6-E2 (E7-92) — Approval, rejection, timeout (T7–T9)

**What this task does:** The venue approves or rejects in time; otherwise it is cancelled.

Lane: Engine · Depends on: S6-E1

```text
Read CLAUDE.md first. You are implementing task S6-E2 — "Approval, rejection, timeout (T7–T9)" (Engine lane) of the E7GZ PS build plan.

Read only these docs for this task:
- FR-TRN-10
- Spec v5 §36.3, §37.2–37.3

Do:
1. Approval window MIN(created+10 min, checkpoint), cutoff startAt−2h, REJECTED/VENUE_REJECTED_TOURNAMENT, timeout → CANCELLED/VENUE_DID_NOT_APPROVE_TOURNAMENT_IN_TIME, 5-min retry.

Done when (write these as tests first where possible):
- Disabling the account during PENDING_VENUE_APPROVAL frees the creator slot with no cooldown.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S6-E3 (E7-93) — Join, leave, checkpoint, start, cancel

**What this task does:** Players join and leave; minimum check; start; cancel.

Lane: Engine · Depends on: S6-E2

```text
Read CLAUDE.md first. You are implementing task S6-E3 — "Join, leave, checkpoint, start, cancel" (Engine lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 06 §3.4, §3.7
- ADR-023

Do:
1. Participants, capacity, checkpoint, start, cancellation authority, participant cause reconciler, activeParticipantCount / capacityOccupiedCount.

Done when (write these as tests first where possible):
- 7 participants at the checkpoint → cancelled and released in the same transaction; BAN-only reserved slot rules pass.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S6-E4 (E7-94) — Resource loss, Verified Player cascade, precedence

**What this task does:** Losing rooms, losing a badge, bans and deleted accounts in tournaments.

Lane: Engine · Depends on: S6-E3, S4-E4

```text
Read CLAUDE.md first. You are implementing task S6-E4 — "Resource loss, Verified Player cascade, precedence" (Engine lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 06 §3.5, §3.6
- arch 07 §5.7

Do:
1. Resource loss before/during; O15 cascade once per transition; ban/deletion precedence.

Done when (write these as tests first where possible):
- Losing 3 of 4 resources continues; losing the 4th cancels; stored cost unchanged; J10 passes.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S6-C1 (E7-95) — Customer app: tournaments

**What this task does:** App: browse, create, join and leave tournaments.

Lane: Clients · Depends on: S6-P0

```text
Read CLAUDE.md first. You are implementing task S6-C1 — "Customer app: tournaments" (Clients lane) of the E7GZ PS build plan.

Read only these docs for this task:
- Customer App prototype
- PCR-001 (participant ineligibility cause and capacity reservation)
- FRS §3.1 (Customer Mobile App)
- FRS F-25, F-27, F-28, F-29 (player-hosted tournament, checkpoint, start/completion, interruption)
- FRS §4.10 (Tournaments)
- FRS §2.2 (tournament transition matrix)
- arch 09 §3.4

Do:
1. Browse, create request, join, leave, status and cancellation messages.

Done when (write these as tests first where possible):
- Codes mapped.

Requirements first, prototype for the look (PO decision 2 Oct 2026):
- The requirement docs in the read list decide WHAT this task must show and do. The prototype decides only HOW it looks. An approved PCR (PCR-001…PCR-005) overrides the FRS lines it amends.
- Before coding, list every screen, state and action the requirements need for this task (including empty, loading, error, not-allowed/ineligible and banned states) and compare it with the prototype.
- Anything the requirements need that the prototype does not show: build it anyway, in the same design: @e7gz/tokens, the components and layout patterns already used in this app and in the prototype. Never invent a new visual style.
- Anything the prototype shows that the requirements do not define (a status, rule, timer, button or message): do not build it; list it as a question for the PO.
- Status names, reason codes, timers and messages come only from docs/01-product and the backend's error codes, never from the prototype.
- Customer app is English at launch, all text in the i18n resource files (FRS §3.1).
- In your summary, list the screens and states you built that are NOT in the prototype, so the reviewer can check them against the requirements.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S6-C2 (E7-96) — Dashboard: tournaments

**What this task does:** Dashboard: tournament requests with sound, approve/reject, manage.

Lane: Clients · Depends on: S6-P0

```text
Read CLAUDE.md first. You are implementing task S6-C2 — "Dashboard: tournaments" (Clients lane) of the E7GZ PS build plan.

Read only these docs for this task:
- Venue Dashboard prototype
- PCR-001 (participant ineligibility cause and capacity reservation)
- FRS §3.2 (Venue Dashboard)
- FRS F-25…F-29 (tournaments)
- FRS §4.10 (Tournaments)
- FRS §2.4 (Owner Password authorization)
- arch 09 §4.1–§4.3

Do:
1. Incoming tournament requests with audible alert + approve/reject, venue-hosted creation, participants view.

Done when (write these as tests first where possible):
- AR checked.

Requirements first, prototype for the look (PO decision 2 Oct 2026):
- The requirement docs in the read list decide WHAT this task must show and do. The prototype decides only HOW it looks. An approved PCR (PCR-001…PCR-005) overrides the FRS lines it amends.
- Before coding, list every screen, state and action the requirements need for this task (including empty, loading, error, not-allowed/ineligible and banned states) and compare it with the prototype.
- Anything the requirements need that the prototype does not show: build it anyway, in the same design: @e7gz/tokens, the components and layout patterns already used in this app and in the prototype. Never invent a new visual style.
- Anything the prototype shows that the requirements do not define (a status, rule, timer, button or message): do not build it; list it as a question for the PO.
- Status names, reason codes, timers and messages come only from docs/01-product and the backend's error codes, never from the prototype.
- Every screen you add works in English and Arabic (RTL, CSS logical properties only).
- In your summary, list the screens and states you built that are NOT in the prototype, so the reviewer can check them against the requirements.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S6-C3 (E7-97) — Admin: tournaments

**What this task does:** Admin: tournaments list and cancel.

Lane: Clients · Depends on: S6-P0

```text
Read CLAUDE.md first. You are implementing task S6-C3 — "Admin: tournaments" (Clients lane) of the E7GZ PS build plan.

Read only these docs for this task:
- Platform Admin prototype
- PCR-001 (participant ineligibility cause and capacity reservation)
- FRS §3.3 (Platform Admin)
- FRS §4.10 (Tournaments)
- arch 09 §5

Do:
1. Tournaments list/detail, admin cancel, abuse alerts.

Done when (write these as tests first where possible):
- —

Requirements first, prototype for the look (PO decision 2 Oct 2026):
- The requirement docs in the read list decide WHAT this task must show and do. The prototype decides only HOW it looks. An approved PCR (PCR-001…PCR-005) overrides the FRS lines it amends.
- Before coding, list every screen, state and action the requirements need for this task (including empty, loading, error, not-allowed/ineligible and banned states) and compare it with the prototype.
- Anything the requirements need that the prototype does not show: build it anyway, in the same design: @e7gz/tokens, the components and layout patterns already used in this app and in the prototype. Never invent a new visual style.
- Anything the prototype shows that the requirements do not define (a status, rule, timer, button or message): do not build it; list it as a question for the PO.
- Status names, reason codes, timers and messages come only from docs/01-product and the backend's error codes, never from the prototype.
- Platform Admin is English only (FRS §3.3).
- In your summary, list the screens and states you built that are NOT in the prototype, so the reviewer can check them against the requirements.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S7-P0 (E7-98) — Sprint 7 API contract

**What this task does:** Add all Sprint 7 API endpoints as stubs by day 2.

Lane: Platform · Depends on: S6-P0

```text
Read CLAUDE.md first. You are implementing task S7-P0 — "Sprint 7 API contract" (Platform lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 09 §3.4, §5
- docs/03-design Compliance UI Addendum

Do:
1. Stubs + DTOs for reviews, review reports, account deletion, remaining admin modules, reports FR-AUD-11/12; regenerate client.

Done when (write these as tests first where possible):
- Merged by day 2.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S7-P1 (E7-99) — Remaining admin modules

**What this task does:** Finish any admin module still missing (20 in total).

Lane: Platform · Depends on: S7-P0

```text
Read CLAUDE.md first. You are implementing task S7-P1 — "Remaining admin modules" (Platform lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 09 §5 (20 modules)

Do:
1. Check each of the 20 admin modules against arch 09 §5 and implement the missing backend endpoints.

Done when (write these as tests first where possible):
- A checklist of the 20 modules in the PR, all ticked.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S7-P2 (E7-100) — Observability, alerts and abuse signals

**What this task does:** Monitoring, alert rules and abuse signals.

Lane: Platform · Depends on: S3-P2

```text
Read CLAUDE.md first. You are implementing task S7-P2 — "Observability, alerts and abuse signals" (Platform lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 11 §5
- arch 11 §5.6

Do:
1. Metrics for every PRD §4.2 metric, Sev-1…3 alert rules, dashboards; abuse-signal pipeline with no write path into Trust/Venue/Booking (ArchUnit test).

Done when (write these as tests first where possible):
- Alert rule fires in a staging drill.

Carry-forward from the Sprint 0 review (do these too):
- Log redaction (NFR-21, arch 10 section 6, Sprint 0 review S-10): JSON logs with a field redaction allowlist, plus the required unit test that no field named like a credential (password, token, otp, secret, authorization, ...) survives serialization; phone numbers masked to the last 3 digits.
- Correlation ID from the internet (Sprint 0 review S-13): today any client's X-Correlation-Id is reused for audit and outbox rows, so an attacker can mix their records into another flow's trail. Generate the ID server-side for every public request and log the client's value as a separate field (or accept it only from the trusted edge).

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S7-P3 (E7-101) — Deployment, backup and restore drill

**What this task does:** Put it online (staging and production), backups and a restore test.

Lane: Platform · Depends on: S0-P3

```text
Read CLAUDE.md first. You are implementing task S7-P3 — "Deployment, backup and restore drill" (Platform lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 12 §1, §3, §4, §5

Do:
1. Staging + production on a managed container host (api + worker), managed PostgreSQL 16 with PITR, S3 bucket, secrets; release process; timed restore drill written up.

Done when (write these as tests first where possible):
- Restore drill done and timed (launch criterion 10).

Carry-forward from E7-13 (do these too):
- Put E7GZ_SMS_PROVIDER=sms-misr and all E7GZ_SMS_MISR_* values (username, password, sender token, template token; ENVIRONMENT=LIVE in production only) in the secret store.
- Set a per-IP rate limit on the public auth endpoints (/api/v1/customer/auth/**) at the load balancer (arch 10 §7): it protects the SMS credit from abuse.
- Open the /actuator/health liveness and readiness probes (arch 12 §1) in ApiSecurityConfig; all of /actuator/** is denied now.
- Add a backup SMS provider behind the SmsSender interface (arch 12 §6.5, risk R8).

Carry-forward from the Sprint 0 review (do these too):
- Extend the per-IP rate limit at the load balancer to /api/v1/venue/auth/** and /api/v1/admin/auth/** as well as /api/v1/customer/auth/** (Sprint 0 review S-2).
- Security headers for the Dashboard and Admin at the CDN/edge (arch 10 section 6, Sprint 0 review S-4): HSTS (max-age at least 1 year, includeSubDomains), Content-Security-Policy (default-src 'self'; connect-src the API origin; frame-ancestors 'none'), X-Content-Type-Options: nosniff, Referrer-Policy: strict-origin-when-cross-origin. HSTS on the API too. Set E7GZ_CORS_ALLOWED_ORIGINS per environment (E7-22).
- Real client IP (Sprint 0 review S-8): set server.forward-headers-strategy with the load balancer as the only trusted proxy, so request.getRemoteAddr() is the client's IP and a spoofed X-Forwarded-For is ignored. Test it on staging.
- Do this first, as soon as staging exists (Sprint 0 review S-11): prove the migrations run on the chosen managed PostgreSQL. Flyway uses SET ROLE postgres and V1 creates postgis/btree_gist, but managed databases give no superuser. If ownership must change, raise it with the PO.
- CI security scanning (Sprint 0 review S-14): Dependabot (Gradle + pnpm), CodeQL (Java + TypeScript), gitleaks secret scan, pnpm audit --prod and a Gradle dependency vulnerability check; fail on high or critical.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S7-E1 (E7-102) — Reviews, reporting and moderation

**What this task does:** Reviews: write, edit, report, and admin moderation.

Lane: Engine · Depends on: S3-E4

```text
Read CLAUDE.md first. You are implementing task S7-E1 — "Reviews, reporting and moderation" (Engine lane) of the E7GZ PS build plan.

Read only these docs for this task:
- ADR-025
- arch 04 §4.7
- PCR-003

Do:
1. create/edit/delete own review, report (one open report per reporter), admin moderate/resolve via the review.* producers; rating projection.

Done when (write these as tests first where possible):
- Duplicate open report → REVIEW_REPORT_ALREADY_OPEN.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S7-E2 (E7-103) — Account deletion and O19

**What this task does:** Customer asks to delete the account; admin completes it.

Lane: Engine · Depends on: S6-E3

```text
Read CLAUDE.md first. You are implementing task S7-E2 — "Account deletion and O19" (Engine lane) of the E7GZ PS build plan.

Read only these docs for this task:
- ADR-024
- PCR-002
- arch 10 (anonymisation)

Do:
1. Customer request: call iam.request_account_deletion.
2. Admin completion: call ONLY iam.anonymise_user inside admin.begin_command — it anonymises the user and completes the open deletion request itself (it calls iam.complete_account_deletion_request internally). Never call complete_account_deletion_request from Java.
3. Tournament participant precedence in the same transaction.

Done when (write these as tests first where possible):
- Deleted account: PII blanked, tokens revoked, reviews removed, participants INELIGIBLE/ACCOUNT_DELETED.

Carry-forward from E7-13 (do these too):
- The ACCOUNT_DELETED branch of identity CustomerActivation is tested only with a fixture row: add an end-to-end test on an account anonymised through iam.anonymise_user.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S7-E3 (E7-104) — Reports FR-AUD-11/12 and metrics

**What this task does:** Admin reports: why bookings end, attendance per venue.

Lane: Engine · Depends on: S3-E4

```text
Read CLAUDE.md first. You are implementing task S7-E3 — "Reports FR-AUD-11/12 and metrics" (Engine lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 13 (FR-AUD-11, FR-AUD-12)
- PRD §4.2

Do:
1. Terminal-reason distribution (customer/venue/operational split) and attendance mix + check-in capture rate per venue; admin API.

Done when (write these as tests first where possible):
- Numbers match a hand-computed seeded dataset.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S7-E4 (E7-105) — Load test at 10×

**What this task does:** Load test at 10 times the expected traffic.

Lane: Engine · Depends on: S1-P6, S7-P3

```text
Read CLAUDE.md first. You are implementing task S7-E4 — "Load test at 10×" (Engine lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 11 §3

Do:
1. k6/Gatling scenarios for discovery, booking, accept, Late Confirm at 10× projected peak with the invariant sweep running.

Done when (write these as tests first where possible):
- NFR-11/12 met; zero invariant violations.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S7-C1 (E7-106) — Customer app: reviews, reporting, delete account

**What this task does:** App: reviews, report a review, delete account.

Lane: Clients · Depends on: S7-P0

```text
Read CLAUDE.md first. You are implementing task S7-C1 — "Customer app: reviews, reporting, delete account" (Clients lane) of the E7GZ PS build plan.

Read only these docs for this task:
- Compliance UI Addendum (authoritative)
- PCR-002 (Customer-Initiated Account Deletion)
- PCR-003 (Customer Reporting of Reviews)
- FRS F-01A (account deletion), F-36 (reviews)
- FRS §4.13 (Reviews)
- arch 09 §3.4

Do:
1. Write/edit/delete review, report review, Delete Account flow.

Done when (write these as tests first where possible):
- Matches the addendum.

Requirements first, prototype for the look (PO decision 2 Oct 2026):
- The requirement docs in the read list decide WHAT this task must show and do. The prototype decides only HOW it looks. An approved PCR (PCR-001…PCR-005) overrides the FRS lines it amends.
- Before coding, list every screen, state and action the requirements need for this task (including empty, loading, error, not-allowed/ineligible and banned states) and compare it with the prototype.
- Anything the requirements need that the prototype does not show: build it anyway, in the same design: @e7gz/tokens, the components and layout patterns already used in this app and in the prototype. Never invent a new visual style.
- Anything the prototype shows that the requirements do not define (a status, rule, timer, button or message): do not build it; list it as a question for the PO.
- Status names, reason codes, timers and messages come only from docs/01-product and the backend's error codes, never from the prototype.
- Customer app is English at launch, all text in the i18n resource files (FRS §3.1).
- In your summary, list the screens and states you built that are NOT in the prototype, so the reviewer can check them against the requirements.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S7-C2 (E7-107) — Admin: moderation, deletion, reports

**What this task does:** Admin: moderate reviews, complete deletions, report screens.

Lane: Clients · Depends on: S7-P0

```text
Read CLAUDE.md first. You are implementing task S7-C2 — "Admin: moderation, deletion, reports" (Clients lane) of the E7GZ PS build plan.

Read only these docs for this task:
- Compliance UI Addendum
- A5
- PCR-002 (Customer-Initiated Account Deletion)
- PCR-003 (Customer Reporting of Reviews)
- FRS §3.3 (Platform Admin)
- FRS §4.13, §4.16 (Reviews; Audit and Reporting, FR-AUD-11/12)
- arch 09 §5

Do:
1. Review moderation, reports queue, account deletion completion, FR-AUD-11/12 report screens.

Done when (write these as tests first where possible):
- —

Requirements first, prototype for the look (PO decision 2 Oct 2026):
- The requirement docs in the read list decide WHAT this task must show and do. The prototype decides only HOW it looks. An approved PCR (PCR-001…PCR-005) overrides the FRS lines it amends.
- Before coding, list every screen, state and action the requirements need for this task (including empty, loading, error, not-allowed/ineligible and banned states) and compare it with the prototype.
- Anything the requirements need that the prototype does not show: build it anyway, in the same design: @e7gz/tokens, the components and layout patterns already used in this app and in the prototype. Never invent a new visual style.
- Anything the prototype shows that the requirements do not define (a status, rule, timer, button or message): do not build it; list it as a question for the PO.
- Status names, reason codes, timers and messages come only from docs/01-product and the backend's error codes, never from the prototype.
- Platform Admin is English only (FRS §3.3).
- In your summary, list the screens and states you built that are NOT in the prototype, so the reviewer can check them against the requirements.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S7-C3 (E7-108) — Dashboard: final RTL pass and reviews view

**What this task does:** Final Arabic pass on the dashboard and venue reviews page.

Lane: Clients · Depends on: S6-C2

```text
Read CLAUDE.md first. You are implementing task S7-C3 — "Dashboard: final RTL pass and reviews view" (Clients lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 11 §6
- FRS §3.2 (Venue Dashboard: reviews are view-only)
- FRS §4.13 (Reviews)

Do:
1. Final AR pass across all modules; venue reviews view; notification sound/push settings.

Done when (write these as tests first where possible):
- Screenshot checklist.

Requirements first, prototype for the look (PO decision 2 Oct 2026):
- The requirement docs in the read list decide WHAT this task must show and do. The prototype decides only HOW it looks. An approved PCR (PCR-001…PCR-005) overrides the FRS lines it amends.
- Before coding, list every screen, state and action the requirements need for this task (including empty, loading, error, not-allowed/ineligible and banned states) and compare it with the prototype.
- Anything the requirements need that the prototype does not show: build it anyway, in the same design: @e7gz/tokens, the components and layout patterns already used in this app and in the prototype. Never invent a new visual style.
- Anything the prototype shows that the requirements do not define (a status, rule, timer, button or message): do not build it; list it as a question for the PO.
- Status names, reason codes, timers and messages come only from docs/01-product and the backend's error codes, never from the prototype.
- Every screen you add works in English and Arabic (RTL, CSS logical properties only).
- In your summary, list the screens and states you built that are NOT in the prototype, so the reviewer can check them against the requirements.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S7-C4 (E7-109) — Store and web release readiness

**What this task does:** Store builds for testers (Play + TestFlight), dashboards online.

Lane: Clients · Depends on: S7-P3

```text
Read CLAUDE.md first. You are implementing task S7-C4 — "Store and web release readiness" (Clients lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 12 §4

Do:
1. App icons/splash, EAS builds, Play internal testing + TestFlight; Dashboard and Admin deployed to staging.

Done when (write these as tests first where possible):
- Internal testers can install the app.

Carry-forward from the Sprint 0 review (do these too):
- Release builds (Sprint 0 review S-17): Android usesCleartextTraffic=false, iOS App Transport Security with no exceptions, and an https API base URL only.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S7-C5 (E7-110) — Launch readiness checklist (PO)

**What this task does:** Go through the launch checklist and sign it off.

Lane: Platform · Depends on: S7-E4, S7-P3

```text
Read CLAUDE.md first. You are implementing task S7-C5 — "Launch readiness checklist (PO)" (Platform lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 14 §8 (launch readiness bar)
- arch 13

Do:
1. Walk the 10 launch criteria; confirm every M requirement in the traceability matrix has a merged task and a passing test; list gaps as new Jira tasks.

Done when (write these as tests first where possible):
- Signed-off checklist in docs/.

Carry-forward from E7-13 (do these too):
- Check the SMS Misr result codes (identity SmsMisrSmsSender.RESULT_CODES, success 4901) against SMS Misr's official API document.
- Confirm with the PO: customer phone numbers are limited to Egyptian mobiles (010/011/012/015; identity PhoneNumbers).
- Confirm with the Engine lane: eligibility category A for the account commands (register, send OTP, verify phone), which have no Venue in scope.

Carry-forward from the Sprint 0 review (do these too):
- Re-confirm two accepted risks from the Sprint 0 review (PO, 2 Oct 2026): (1) no MFA for Platform Admin at launch (S-5); (2) registration and resend reveal whether a phone is registered, accepted for MVP and slowed by the per-IP limit (S-16).

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```
