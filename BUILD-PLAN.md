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
6. **Abdallah works with free AI tools** — Gemini CLI or a browser chat, with the context bundle `node tools/ai-context/bundle.mjs E7-xx` (CLAUDE.md rules + the task).
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
| S1-E7 | E7-112 | Engine | Venue availability schedule API | The backend for the Dashboard schedule: each room's bookings, holds, maintenance and closures for one business day. | S1-E2, S1-E3, S1-P3, S1-P4 |

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
| S2-P2 | E7-41 | Platform | Transactional outbox and dispatcher | Send messages only after the action is saved, with retries. | S0-E1, S2-P1 |
| S2-P3 | E7-42 | Platform | Idempotency on writes | Pressing a button twice never creates two bookings. | S0-E1 |
| S2-P4 | E7-43 | Platform | Notifications: catalogue, channels, push and email | Notifications: in-app list, phone push, email, and SMS for codes only. | S2-P2 |
| S2-P5 | E7-44 | Platform | Venue live channel: SSE + Web Push | Live updates on the dashboard, and browser push when it is closed. | S2-P2, S2-P4 |
| S2-P6 | E7-45 | Platform | Boundary test pack scaffold | Test tools to set the clock and check timers to the exact second. | S2-P1 |
| S2-E1 | E7-46 | Engine | Online booking creation (O1) | A customer books a room online (20 checks), the venue gets the request. | S1-E2, S2-P0, S2-P1; S2-P2, S2-P3 before the PR |
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
| S3-P3 | E7-57 | Platform | Profile and account management | Edit profile, change username, phone, email, reset password. | S0-P6, S3-P2 |
| S3-E1 | E7-58 | Engine | Late Confirm (O3) | Late Confirm: the venue offers a late slot, the customer accepts in time. | S2-E2, S2-P6, S3-P1 |
| S3-E5 | E7-113 | Engine | Opening-hours change: cancel affected bookings (O5) | When a venue saves new opening hours, cancel the future bookings the new hours no longer cover, release their holds and tell the customers. | S1-P3, S2-E4, S3-E1, S3-P1 |
| S3-E2 | E7-59 | Engine | Check-in, late present, no-show (O13) | Check-in, "arrived late", and no-show. | S2-E2, S3-P1 |
| S3-E3 | E7-60 | Engine | Start-time revalidation (O11) | Re-check everything at start time and cancel with the right reason if needed. | S3-E2, S3-P1, S3-P2 |
| S3-E4 | E7-61 | Engine | Completion and attendance defaults (O12) | Finish bookings automatically and set the final attendance result. | S3-E2, S3-P1 |
| S3-E6 | E7-114 | Engine | Venue cancels a confirmed booking (FR-VEN-03) | The venue cancels a CONFIRMED online booking with the Owner PIN; the customer gets a full refund classification and a notification. | S3-P0, S2-E2, S3-P1 |
| S3-E7 | E7-115 | Engine | Venue Overview API (FR-VEN-35) | The backend for the Dashboard home page: today's and this month's revenue, today's bookings and pending requests. | S3-P0, S2-E4 |

### Sprint 4 — Trust, bans and rewards (22 Nov – 3 Dec 2026)

Trust events and replay, bans, Verified Player, points, wallet, referrals, configuration; tournament entity built early. Roadmap Phase 4.

| ID | Jira | Lane | Task | What it does | Depends on |
|---|---|---|---|---|---|
| S3-C1 | E7-62 | Clients | Customer app: Late Confirm and status | App: Late Confirm screen with countdown and booking status timeline. | S3-P0, S3-P3 |
| S3-C2 | E7-63 | Clients | Dashboard: attendance and Late Confirm | Dashboard: check-in, late, no-show and Late Confirm buttons. | S3-P0, S3-P1 |
| S3-C3 | E7-64 | Clients | Customer app: profile and account | App: profile and account screens. | S3-P0 |
| S3-C4 | E7-116 | Clients | Dashboard: Overview home page | Dashboard home page: today's and this month's revenue, today's bookings, pending requests, today's schedule and quick actions. | S3-P0 |
| S4-P0 | E7-65 | Platform | Sprint 4 API contract | Add all Sprint 4 API endpoints as stubs by day 2. | S3-P0 |
| S4-P1 | E7-66 | Platform | Loyalty ledgers and fee treatment | Points and wallet ledgers, and what happens to the booking fee in every case. | S2-E4 |
| S4-P2 | E7-67 | Platform | Referrals | Invite a friend: referral rewards and reversals. | S4-P1, S0-P5 |
| S4-P3 | E7-68 | Platform | Configuration service (fees and points) | Admin changes the booking fee and points values (from a date onward). | S0-E1 |
| S4-E1 | E7-69 | Engine | Trust events and replay | Trust history of every customer, recalculated exactly from events. | S3-E4 |
| S4-E2 | E7-70 | Engine | Reconciler and attendance corrections | Fix attendance mistakes and recalculate trust correctly. | S4-E1 |
| S4-E3 | E7-71 | Engine | Bans and the ban cascade (O14) | Temporary bans after repeated no-shows, and their effects. | S4-E2 |
| S4-E4 | E7-72 | Engine | Verified Player | The Verified Player badge: earning, losing, restoring it. | S4-E2 |
| S4-E5 | E7-73 | Engine | Tournament entity (early, for Sprint 5) | Basic tournament records early, so venue emergencies can handle them. | S1-E1 |
| S4-E6 | E7-118 | Engine | Unanswered booking at start time (T6, FR-BOK-23) | A booking the venue never answered is cancelled at its start time with the right reason. | S3-E3, S3-P1 |

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
| S5-E1 | E7-81 | Engine | Maintenance and closures | Maintenance windows and venue closures block the calendar. | S1-E2, S3-E5 |
| S5-E2 | E7-82 | Engine | Emergencies and cascades (O7/O8) | Venue or room emergency: cancel or pause affected bookings safely. | S5-E1, S5-P2 |
| S5-E3 | E7-83 | Engine | Timer pause/resume and feasibility | Pause and resume timers during emergencies (30-minute limits). | S5-E2 |
| S5-E4 | E7-84 | Engine | Owner review cascade (O9) | Owner review: lock the venue down when limits are exceeded. | S5-E2, S4-E5 |

### Sprint 6 — Tournaments (20 – 31 Dec 2026)

Full tournament lifecycle, approval, checkpoint, participants, resource loss. Roadmap Phase 6.

| ID | Jira | Lane | Task | What it does | Depends on |
|---|---|---|---|---|---|
| S5-C1 | E7-85 | Clients | Dashboard: operations screens | Dashboard: maintenance, closures and emergency screens. | S5-P0 |
| S5-C2 | E7-86 | Clients | Admin: owner review, disable, alerts | Admin: owner review, disable venue account, alerts inbox. | S5-P0 |
| S5-C3 | E7-87 | Clients | Customer app: operational states | App: show emergency cancellations, pauses and refunds. | S5-P0, S5-P1 |
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
| S7-E3 | E7-104 | Engine | Reports FR-AUD-11/12 and metrics | Admin reports: why bookings end, attendance per venue. | S3-E4, S3-E7 |
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
| S7-C6 | E7-117 | Clients | Dashboard: revenue screen | Dashboard revenue screen: online, manual and tournament revenue by business date, with an optional activity breakdown. | S7-P0 |

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
2. Marketplace status changes: complete onboarding moves ONBOARDING → INACTIVE and is the only status action while ONBOARDING (PO decisions 28 Sep and 2 Oct 2026); after that ACTIVE / INACTIVE / SUSPENDED switch freely; never back to ONBOARDING (DB enforces; map the error).
3. Venue images to SeaweedFS/S3 with signed GET URLs.

Done when (write these as tests first where possible):
- Tests for each allowed/forbidden status change and area deactivation refusal.
- Tests: photo upload -> confirm -> listed with a signed GET URL; wrong type, too large, an 11th photo and confirm before upload are refused with their codes; a removed photo is no longer listed but its row and object remain; each change has an audit row.
- Test: a venue created at lat 30.0561, lng 31.3301 reads back with the same lat and lng (not swapped).

Carry-forward from the Sprint 0 review (do these too):
- First Platform Admin (bootstrap): add a one-off, audited way to create the first admin (e.g. a command run with the worker profile) that takes the email and reads the password from the secret store or stdin, hashes it with the existing Argon2id PasswordEncoder, and refuses if any admin already exists. Never put a default admin or password in code, config or a migration. (PO decision 2 Oct 2026: no admin MFA at launch.)
- Database rights (PO decision 2 Oct 2026, Sprint 0 review S-6): the public API's role must not be able to write admin accounts. Put the grant change in R__security_baseline.sql (and its docs copy), not a separate migration, because the baseline re-applies every grant after each migration (PO decision 2 Oct 2026, PR #15): revoke INSERT/UPDATE on iam.admin_account (and every other admin-owned table: list them from the .dbm) from e7gz_app_runtime. Move admin login reads (admin/internal/AdminLoginAccounts) to the admin DataSource. Add a test that e7gz_app_runtime cannot INSERT or UPDATE iam.admin_account. This item may update docs/04-erd/physical (.dbm, security baseline, change report) as CLAUDE.md section 5 requires; the PO reviews it.

Carry-forward from E7-22 (do these too):
- Contract from E7-22 (packages/api-client/SPRINT-1-CONTRACT.md section 4, arch 09 section 5): implement GET /admin/venues/{venueId} ({venue, venueAccount or null}) and POST /admin/venues/{venueId}/activate, /deactivate, /suspend with {reason}. onboarding/complete is ONBOARDING -> INACTIVE only.
- A change the venue's current status does not allow (completing onboarding when it is not ONBOARDING, or moving to the status it already has) is 422 MARKETPLACE_STATUS_CHANGE_NOT_ALLOWED, decided in Java before the write. ACTIVE or SUSPENDED in an inactive area is AREA_INACTIVE (gd_venue_area_inactive_status, already mapped). Every change is audited with the admin's reason.

PO decisions 2 Oct 2026 — venue location and photos (do these too):
- Venue photos, Platform Admin side (PCR-005, arch 09 §5, arch 12 §2): an ObjectStore port in the venue module with an S3 adapter (AWS SDK v2; SeaweedFS locally; the bucket is private). Keys venues/{venueId}/{imageId}.{ext}. Implement GET POST /admin/venues/{venueId}/images, POST .../images/{imageId}/confirm, PUT .../images/order {imageIds}, POST .../images/{imageId}/remove (stubs first so the Clients lane can mock them). POST checks contentType (image/jpeg, image/png, image/webp), sizeBytes (max 5 MB) and the limit of 10 visible photos (all three in configuration), creates the venue.venue_image row and returns a short-lived signed PUT. confirm checks the stored object (HEAD: exists, type, size) and sets uploaded_at. remove sets removed_at; nothing is hard-deleted. Lists show only confirmed, not removed photos in sort order, each with a short-lived signed GET URL. Errors: VENUE_IMAGE_NOT_ACCEPTED, VENUE_IMAGE_LIMIT_REACHED, VENUE_IMAGE_NOT_UPLOADED (422; add them to ApiErrorCode). Every change is audited. No object-store call inside a database transaction (arch 01 §15): presign and HEAD run outside the command.
- Expose the photo service through the venue module's api package so E7-24 can add the /venue/images routes with the Owner Password.
- Migration (PCR-005): V<yyyyMMddHHmm>__venue_image_upload_and_removal.sql adds uploaded_at and removed_at (timestamptz, nullable) to venue.venue_image; update the .dbm and the change report in docs/04-erd/physical; the PO reviews it.
- Venue location: store it with ST_SetSRID(ST_MakePoint(lng, lat), 4326)::geography (longitude first) and read it back the same way.

Delivery (PO decision 2 Oct 2026): this task ships as three PRs, merged in order:
- PR 1 (branch E7-23-admin-grants-bootstrap): the Sprint 0 review carry-forward — admin_account grants migration + R__security_baseline update, admin login reads on the admin DataSource, the first-admin bootstrap command (password from stdin, or from an environment variable when there is no terminal).
- PR 2 (branch E7-23-areas-venues-status): areas, venues with location, marketplace status changes and the E7-22 carry-forward.
- PR 3 (branch E7-23-venue-photos): venue photos — ObjectStore + S3 adapter, the venue_image migration, the admin /images routes.
- Each PR passes ./gradlew check on its own and is reviewed separately; do one PR at a time, in this order.

PO decisions 3 Oct 2026 — venue photos (PR 3, do these too):
- Signed upload links last 10 minutes, view links 1 hour (configuration).
- A worker job makes a small (venue card) and a medium (Venue Details) copy of each confirmed photo and strips GPS/EXIF metadata; customers and the admin list get only these copies; the original is kept.
- JPEG, PNG and WebP only (web uploads; the browser converts iPhone photos). Customers never upload photos.
- A nightly job removes uploads not confirmed within 24 hours (object and row state; never shown).
- No CDN at launch: serve straight from storage with signed links. Create the local bucket automatically (docker compose / app start-up in the local profile); staging and production buckets are E7-101.

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
- arch 09 §4.2 (venue settings)
- FR-VEN-40

Do:
1. Admin creates the venue account; venue login already exists (S0-P6).
2. Owner Password: Argon2id hash in iam.venue_owner_secret, set/change with version bump.
3. @OwnerProtected annotation: protected actions carry the password in the command body, verified inside the action transaction; never cached; successes/failures audited and rate-limited.
4. Architecture test that reads the FRS §2.4 protected/unprotected list as data and checks the annotations (risk I3).

Done when (write these as tests first where possible):
- Tests: wrong password audited + rate limited; no session "unlock" exists.
- Tests: venue photo confirm, reorder and remove with a wrong Owner Password return 423 OWNER_PASSWORD_INVALID and are audited; another venue's photo is NOT_FOUND.
- Tests (VenueSettingsHttpTest): GET returns the signed-in venue's profile and language; PATCH changes only the fields sent; a blank name or a bad phone is VALIDATION_FAILED on that field; another venue's data is never read or changed.

Carry-forward from the Sprint 0 review (do these too):
- Password-hashing overload guard (Sprint 0 review S-2): every Argon2id hash and verify (login, registration, Owner Password, later password reset) uses 64 MiB, so a flood can exhaust memory. Put them all behind one bounded gate in identity (e.g. a Semaphore, permits configurable, default 4 per instance). A request that gets no permit within about 2 s fails with 429 RATE_LIMITED, and nothing is written. Test: permits + 1 concurrent hashes, one is refused.

Carry-forward from E7-22 (do these too):
- Contract from E7-22: implement POST /admin/venues/{venueId}/venue-account {loginIdentifier, password, initialOwnerPassword, languagePreference?} -> 201 AdminVenueAccount (ENABLED; language en when absent). Hash both with Argon2id (login password in iam.venue_account, Owner Password in iam.venue_owner_secret); audited; never logged or echoed. DatabaseErrorCodes already maps ux_venue_login_normalized -> VENUE_LOGIN_IDENTIFIER_TAKEN and uq_venue_account_venue -> VENUE_ACCOUNT_ALREADY_EXISTS.
- Venue login, refresh and logout now use the HttpOnly cookie session (E7-22, ADR-029, arch 09 section 2). Keep it as is.

PO decisions 2 Oct 2026 — venue location and photos (do these too):
- Venue photos, Venue Dashboard side (PCR-005, arch 09 §4.2): implement GET POST /venue/images, POST /venue/images/{imageId}/confirm {ownerPassword}, PUT /venue/images/order {imageIds, ownerPassword}, POST /venue/images/{imageId}/remove {ownerPassword}, using the photo service E7-23 exposes in the venue api package. confirm, order and remove are Owner-Password protected; POST (it only returns a signed PUT URL) is not. A venue can only see and change its own photos.
- PCR-005 adds "venue photo add, reorder and remove" to the FRS §2.4 Owner Password required list: add it to the list your architecture test reads.

PO decision 3 Oct 2026 — venue settings (do these too):
- Venue settings (arch 09 §4.2, FR-VEN-40; contract added for E7-35): implement GET PATCH /venue/settings in venue/web/VenueSettingsController (the stub answers 501). GET returns the signed-in venue's profile (venueId, name, areaId, areaName, address, location, phone, timezone, marketplaceStatus, activities) and the venue account's languagePreference. PATCH changes only the fields sent: name, address and phone (same rules as PATCH /admin/venues: not blank when sent, at most 200 characters, phone stored as E.164, VALIDATION_FAILED with error.field) and languagePreference (en | ar, iam.venue_account.language_preference). Area, location, activities, timezone and marketplace status stay admin-only. No Owner Password (not in the FRS §2.4 list); Idempotency-Key optional. A venue only ever reads and changes its own venue.

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

Carry-forward from the E7-29 review (do these too):
- ReservationLedger.create (inventory/ledger) turns 23P01 on ex_no_double_booking into SlotTaken (SLOT_NO_LONGER_AVAILABLE) for every reservation type. Only a BOOKING row should become SlotTaken; for LATE_CONFIRM_HOLD, TOURNAMENT_APPROVAL_HOLD and TOURNAMENT_HOLD the original exception propagates unchanged. Do not change DatabaseErrorCodes (ex_no_double_booking stays SLOT_NO_LONGER_AVAILABLE, arch 09 §1.5): tr_10_resource_reservation_cross_class_guard refuses a hold first with its type-specific P0001, so a raw 23P01 for a hold means the guard was bypassed, a defect (PO decision 3 Oct 2026, corrected).
- Test: a 23P01 on ex_no_double_booking for a non-BOOKING row is not SlotTaken.

PO decision 3 Oct 2026 — reads from other modules (Amr's question; do these too):
- Yes: isFree reads Venue, Resource, Operations and the Reservation Ledger only through small read-only interfaces published in each owner's api package (arch 03; ArchUnit). All SQL stays in the owner module's internal package; Availability holds no SQL on their tables. No new tables, no schema change, no caching (arch 01 §15 rule 1).
- Keep each interface to what the eight checks need (arch 05 §2.2), for example: venue — venueBlocked(venueId, at) [check 1]; resource — the Resource's venueId, baseState and restriction, and resourceBlocked(resourceId, at) [checks 2–4]; operations — declared emergency interval, maintenance and closure overlaps for a period [checks 5–7]; ledger — hasBlockingOverlap(resourceId, period) on ReservationLedger [check 8]. Every read also accepts the caller's CommandContext so a commit-time check reads on the command's own transaction.
- Ledger and Operations are Engine (your lane). Venue and Resource are Platform: keep those additions to the api interface + one internal JDBC class each, and ask Sayed to review them; E7-26 (resources) will build on the resource one, so do not add anything else there.
- Tests: each new read is tested in its owner module against the database; the predicate tests use them, not direct SQL.

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

PO decision 3 Oct 2026 — current business date for the Dashboard (from the E7-36 review; do these too):
- The business date stays as Product Spec §17.5 defines it (the Operating Day of the session; a night that crosses midnight is one business day — the PO kept this rule). The Dashboard must not guess today's business date from the device clock. Add currentBusinessDate(venueId) to the resolver's api (the command/request instant from the injected Clock, never Instant.now()). Make the date query parameter of GET /api/v1/venue/availability optional in the contract: when absent the server uses currentBusinessDate and returns it in VenueSchedule.businessDate (the stub still answers 501 until the endpoint is implemented). Regenerate OpenAPI (pnpm api:gen) and tell Abdallah (E7-36): the schedule's first load sends no date and navigates from the returned businessDate.
- Test: at 01:00 Africa/Cairo for a venue open 18:00–02:00, currentBusinessDate is the previous calendar day; at 03:00 (after closing, no session) it is that calendar day.

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

Decision on record:
- PO decision 3 Oct 2026 (confirmed 4 Oct): the NFR-28 overlap check compares planned_range of active rows only, not the arch 11 section 4 actual_reservation_start range.

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
- FR-DIS-03, FR-DIS-09, FR-DIS-11
- PCR-005 (venue photos)

Do:
1. Venue list with filters (area, activity), KNN distance ordering, Nearest Availability from the projection; venue detail.
2. Customer DTOs only expose customer-visible fields.

Done when (write these as tests first where possible):
- p95 < 1 s on the perf seed (S1-P6) — test recorded in CI as a non-blocking report.

PO decisions 3 Oct 2026 (do these too):
- Venue photos (FR-DIS-03, FR-DIS-11, PCR-005): the card imageUrl is the first visible photo's small copy, Venue Details imageUrls are the medium copies in the venue's order; take the signed URLs (valid 1 hour) from the venue module's photo service in venue/api (E7-23). A venue without photos has imageUrl null and imageUrls [].
- The venue phone is never in a customer discovery or Venue Details response: it opens only when a booking becomes PENDING (FR-DIS-09). Keep it out of every Customer* DTO.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S1-E7 (E7-112) — Venue availability schedule API

**What this task does:** The backend for the Dashboard schedule: each room's bookings, holds, maintenance and closures for one business day.

Lane: Engine · Depends on: S1-E2, S1-E3, S1-P3, S1-P4

```text
Read CLAUDE.md first. You are implementing task S1-E7 — "Venue availability schedule API" (Engine lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 09 §4.1 (availability schedule payload)
- FR-VEN-37
- arch 05 §2.3 (what blocks)
- arch 04 §4.3
- Product Spec §17.5 (businessDate)

Do:
1. Implement GET /api/v1/venue/availability in inventory/availability/web/VenueAvailabilityController (the stub answers 501): the signed-in venue only, one business date, every non-archived Resource with its slots. Date optional: when absent use currentBusinessDate from E7-31.
2. Display state per span (FR-VEN-37, only these nine): ledger BOOKING row -> the Booking's status PENDING / CONFIRMED / ACTIVE, or MANUAL when the Booking's source is MANUAL; LATE_CONFIRM_HOLD -> AWAITING_CUSTOMER_CONFIRMATION; TOURNAMENT_APPROVAL_HOLD and TOURNAMENT_HOLD -> TOURNAMENT_HOLD; MAINTENANCE -> MAINTENANCE; CLOSURE -> CLOSURE; time inside an Operating Session with nothing on it -> FREE. Only active ledger rows count (released rows never show). bookingId is set for booking-backed spans only.
3. PO decision 3 Oct 2026 — hours shown: the grid covers that business date's Operating Sessions (first open to last close). A row of that business date outside the sessions (e.g. a 3 AM manual booking) is still shown and widens the range. A day with no Operating Session returns closed = true and every Resource with no slots.
4. PO decision 3 Oct 2026 — emergencies: the declared interval of an Emergency Closure (whole venue) or a Resource Emergency Outage (that Resource) shows as CLOSURE. No new display state.
5. PO decision 3 Oct 2026 — room states: ARCHIVED Resources are left out. DISABLED Resources are listed with their existing spans, but their empty time is not FREE (no slot, so the Dashboard leaves it blank).
6. Contract change: add closed (boolean, required) to VenueSchedule. Regenerate OpenAPI (pnpm api:gen) and tell Abdallah (E7-36): show a closed-day message when closed = true, and leave gaps without a slot blank.
7. Reads only through the owner modules' api packages (the E7-30 decision): Venue (Operating Sessions of the business date), Resource (resources, base state), Operations (maintenance, closures, declared emergency intervals), Reservation Ledger (active rows in a range) and Booking (status and source for booking ids). Add a small read-only interface where one is missing; all SQL stays in the owner module. Venue and Resource additions are reviewed by Sayed. Read-only endpoint: no command, no Idempotency-Key.

Done when (write these as tests first where possible):
- Tests (VenueAvailabilityHttpTest, SQL fixtures): each of the nine display states appears from its source row; a released row does not show; a session crossing midnight shows its 01:00 booking on the previous business date; a 3 AM manual booking widens the range; a closed day returns closed = true; an emergency interval shows as CLOSURE; an archived room is absent and a disabled room has no FREE slot; another venue's data is never returned; no date -> the current business date.

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

PO decisions 3 Oct 2026 (do these too):
- Error text: write the on-screen text for every new error code you map (English and Arabic) in the error-code map; the Product Owner approves it in your PR. Never take text from the backend.
- Venue profile (PO decision 3 Oct 2026, arch 09 §4.2): GET /venue/settings shows name, area, address, location, phone, activities, marketplace status and language; PATCH /venue/settings edits name, address, phone and languagePreference only, with no Owner Password prompt. Area, location, activities and status are read-only on the Dashboard (the Platform Admin changes them). The language saved here is the account preference (FR-VEN-40: account preference → browser storage → English). Map VALIDATION_FAILED on name, address and phone through the error-code map. Build against pnpm mock until E7-24 implements it.

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
- Marketplace status control (PO decision 2 Oct 2026, E7-23): while a venue is ONBOARDING, offer only "Complete onboarding" (it moves the venue to INACTIVE); offer Activate, Deactivate and Suspend only after that, and never the status the venue already has. The server still decides (MARKETPLACE_STATUS_CHANGE_NOT_ALLOWED).
- Venue activities editor: saving sends the full list and replaces the venue's activities (at least one). Map the new code VENUE_ACTIVITY_IN_USE (an activity that still has rooms or prices cannot be removed) and VALIDATION_FAILED on field areaId (unknown area) through the error-code map.
- Areas and venues API details (PO decisions 2 Oct 2026, E7-23 PR 2): area names are unique - map AREA_NAME_TAKEN; name, Arabic name and address fields are at most 200 characters; the venue phone accepts an Egyptian mobile or landline and comes back as +20... (show VALIDATION_FAILED on phone); the venue list is newest first; an area's Arabic name and centroid are removed with removeNameAr / removeCentroid (PATCH); blank text in an edit is VALIDATION_FAILED on that field.

PO decisions 3 Oct 2026 (do these too):
- Error text: write the on-screen text for every new error code you map (English) in the error-code map; the Product Owner approves it in your PR. Never take text from the backend.

PO decision 3 Oct 2026 — PCR-006 (do these too):
- The Owner Password is a 4-digit PIN (PCR-006). In the create-venue-account form, initialOwnerPassword takes exactly 4 digits (numeric keypad, maxlength 4); reset-owner-password's newPassword too. The venue login password keeps the password rule (8+ characters, a letter and a digit). Map VALIDATION_FAILED on initialOwnerPassword, password and newPassword through the error-code map. Call it 'Owner PIN' on screen; the API field names stay ownerPassword / initialOwnerPassword.

PO decisions 4 Oct 2026 — pricing and venue accounts (do these too):
- No pricing in Admin onboarding: the venue sets its own prices in the Venue Dashboard after its first login (GET PUT /venue/pricing, E7-27; Dashboard screen E7-35). There is no admin pricing endpoint: do not build a pricing step or price fields in the wizard, and the wizard's success text must not say the venue was created with initial pricing. (This changes the pricing part of FR-ADM-02; the PO will record it in a PCR.)
- Venue accounts: creating the account, disable/enable and the two password resets stay on each venue's page. Keep the /venue-accounts menu item as a placeholder in this task; E7-86 turns it into the list of all venue accounts using GET /admin/venue-accounts (contract E7-77, backend E7-78). Do not call an endpoint that is not in openapi.json.

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
- FR-DIS-09 (venue phone visibility)

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

PO decisions 3 Oct 2026 (do these too):
- Venue photos: show the card image (imageUrl) on the list and map preview and the gallery (imageUrls) on Venue Details; show a placeholder when there is none. The URLs expire after 1 hour, so reload them with the screen data, never cache them for longer.
- Never show a venue phone in discovery or Venue Details; it appears only on a booking once it is PENDING (FR-DIS-09).

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

Carry-forward from E7-23 (PO decisions 3 Oct 2026, do these too):
- Move the two venue photo jobs (venue/internal/VenuePhotoJobs: make the small and medium copies, and the nightly clean-up of uploads not confirmed within 24 hours) from their Spring timers onto technical.scheduled_job, keeping the same behaviour and tests; then remove @EnableScheduling from VenuePhotoJobs.

Carry-forward from the E7-32 review (PO decisions 4 Oct 2026, do these too):
- Before coding, add one migration V<yyyyMMddHHmm>__scheduled_job_finish_and_correlation.sql (+ update the .dbm + change report, PO review): technical.job_finish accepts SUPERSEDED (sets completed_at), and PENDING with an optional new due time (p_due_at) so a job that fired too early or must back off can be moved later; the guard allows due_at to change on LEASED -> PENDING only through job_finish. Never change a job's status with a plain UPDATE.
- Same migration: add technical.scheduled_job.correlation_id uuid NOT NULL (existing rows take their id). A job created by a command stores that command's correlation ID; a nightly system job uses its own job id. The worker opens CorrelationId.open(job.correlationId()) around each job and closes it afterwards; every retry reuses the same ID.
- Nightly system jobs: type = the job name (e.g. INVARIANT_SWEEP), subject_type = SYSTEM, subject_id = a fixed UUID derived from the type (UUID.nameUUIDFromBytes), idempotency_key = TYPE:{business date, Africa/Cairo}. Each run inserts the next night's row in the same transaction as its own DONE (ON CONFLICT on the idempotency key does nothing); the worker creates the first row at start-up if none is PENDING.
- Move every Spring timer onto technical.scheduled_job: the nightly invariant sweep (E7-32), the venue photo clean-up, and the nightly operating-session extension (venue/internal/OperatingSessionJobs, E7-25), plus the photo copies above. Afterwards no @Scheduled / @EnableScheduling remains.
- Invariant sweep handler: calls only ps.e7gz.inventory.sweep.api.InvariantSweep.run() (no copied SQL, no sweep.internal imports, no single InvariantCheck calls). It runs outside any transaction, because the sweep opens its own read-only REPEATABLE READ snapshot; the job is finished in its own transaction afterwards. Add one line to ReadOnlyInvariantSweep so it fails when called inside an existing transaction (PROPAGATION_NEVER); Amr reviews that line. Tests: the sweep runs through the registered handler with a correlation scope open, is not run by @Scheduled, and does not start in the api profile.

Carry-forward from the Sprint 1 review (PO decision 8 Oct 2026, do these too):
- Also move the Nearest Availability refresh timer (inventory/availability/internal/NearestAvailabilityJobs, E7-33 follow-up: every venue / activity pair every minute, e7gz.availability.projection.refresh-every) onto technical.scheduled_job, keeping its behaviour and tests: a failing pair is logged and skipped, and the pass repeats every interval. It counts among the timers of the line above: afterwards no @Scheduled / @EnableScheduling remains.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S2-P2 (E7-41) — Transactional outbox and dispatcher

**What this task does:** Send messages only after the action is saved, with retries.

Lane: Platform · Depends on: S0-E1, S2-P1

```text
Read CLAUDE.md first. You are implementing task S2-P2 — "Transactional outbox and dispatcher" (Platform lane) of the E7GZ PS build plan.

Read only these docs for this task:
- ADR-004
- arch 08 §3.2, §4.1–§4.3
- arch 10 §2.4
- arch 05 §5.3, arch 08 §7

Do:
1. Write outbox rows in the command transaction; pg_notify wake; dispatcher with exponential backoff and dead-letter.
2. Nothing dispatches for a rolled-back transaction.

Done when (write these as tests first where possible):
- Tests: rollback → no dispatch; transient failure retried; permanent → dead-letter.

Before you start (PO review 9 Oct 2026, do these too):
- Branch from main only after E7-40 (PR #54, deadline engine) is merged: it turned NearestAvailabilityJobs into a recurring job on technical.scheduled_job and uses worker database connections.
- Consumers plug in like E7-40's job handlers: an outbox consumer interface in messaging.api (the event types it takes, and handle(event)), found as beans, so notifications (E7-43) and the venue live channel (E7-44) are added without editing the dispatcher. Every consumer must be safe to run twice: a retry runs every consumer of the event again.
- Database connections: the worker profile has 5 app connections and the deadline worker already uses 3 (one LISTEN 'sched' + 2 threads). Share one listening connection for 'sched' and 'outbox', or raise the worker pool in application-worker.yml (arch 12 section 1.4); say which in the PR.
- The database sends no NOTIFY itself: the outbox writer calls pg_notify('outbox') in the command transaction (delivered only at commit), as ScheduledJobs does for 'sched'. The writer stores CorrelationId.require() in correlation_id; the dispatcher opens CorrelationId.open(event.correlationId()) around each event.
- A DEAD_LETTER outbox event is final in the database (DISPATCHED and DEAD_LETTER are terminal; only the technical.outbox_* functions change delivery state): log it at ERROR (Sev-2). The admin alert is E7-56. There is no 'admin recover' in this task: the admin 'try again' for a failed delivery to a person moves to E7-43 (notification.recover_dead_letter), PO decision 9 Oct 2026.

PO decisions at the start of work (9 Oct 2026, do these too):
- Order: events are taken in id order, but an event waiting for its retry does NOT hold back later events of the same aggregate. This replaces "processes one aggregate's events in sequence" of arch 08 section 4.2 for this task.
- The Nearest Availability consumer lives in inventory.availability.internal (Engine lane: Amr reviews that part); venue.api VenueActivities gets ofVenue(venueId) and inArea(areaId) for it.
- LoggingSmsSender (local development only) keeps writing the code to the log, with the phone masked to its last 3 digits. SmsMisrSmsSender keeps its own mask (+2010*****678).
- E7GZ_OTP_PAYLOAD_KEY (Base64 of 32 bytes) is required: neither the api nor the worker starts without it, as with E7GZ_JWT_SECRET. Locally, run api,worker to receive an OTP.

Carry-forward from E7-13 (do these too):
- Move the OTP SMS from identity CustomerRegistration's direct post-commit call to an outbox row written in the same command transaction and delivered by the dispatcher.
- The OTP code is never stored in plain text in outbox_event.payload (arch 10 section 2.2). PO decision 9 Oct 2026: the payload carries the code encrypted (AES-GCM) with a key from the secret store (a new setting; name it in the PR, e.g. E7GZ_OTP_PAYLOAD_KEY; a test-only key in tests); the dispatcher decrypts it only to send the SMS. An outbox payload can never change after insert (guard_outbox), so the ciphertext is not cleared: a code is useless after its 5 minutes. Never log the code or the key.

Carry-forward from the Sprint 0 review (do this too):
- LoggingSmsSender: mask the phone number (keep the last 3 digits, arch 10 section 6), even in development. (PO decision 9 Oct 2026: no start-up guard against the 'log' SMS provider; the E7-101 release checklist sets and checks E7GZ_SMS_PROVIDER.)

Carry-forward from E7-23 (do these too): publish the two integration events E7-23 could not, because the outbox did not exist yet - VenueMarketplaceStatusChanged {venueId, previousStatus, newStatus} from VenueCommands.ChangeStatus (arch 08 section 3.2), and AreaDeactivated {areaId} from AreaCommands.SetActive when an area is deactivated (arch 03 section 4: availability projection refresh). Write each in the same command transaction; the code marks both places with a comment naming E7-41.

Carry-forward from the Sprint 1 review (PO decisions 8 and 9 Oct 2026, do these too):
- Event-driven Nearest Availability refresh (arch 05 section 5.3, arch 08 section 7): a projection consumer refreshes, through ps.e7gz.inventory.availability.api.NearestAvailabilityProjection.refresh, every venue / activity pair of the venue an inventory-affecting event names (AreaDeactivated: every venue of the area). It takes every inventory-affecting event type of arch 08 section 3.2 (ReservationHeld/Released, the Resource state events, MaintenanceCreated/Edited/Deleted, ClosureCreated, the Emergency events, OpeningHoursChanged, VenuePricingChanged, RoomOverrideChanged, VenueMarketplaceStatusChanged, AreaDeactivated), but this task WRITES only the two E7-23 events above (PO decision 9 Oct 2026): the other producers add their events in later tasks, and until then the one-minute pass (NearestAvailabilityJobs, a recurring job since E7-40) covers them. Keep that pass as the safety net: time passing also moves rows. Test: VenueMarketplaceStatusChanged and AreaDeactivated refresh their pairs without waiting for the pass.

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

Carry-forward from E7-24 (do these too):
- An admin Owner Password reset must notify the venue (FR-ADM-03, FR-VEN-05). POST /admin/venue-accounts/{id}/reset-owner-password already exists (VenueAccountCommands.ResetOwnerPassword, audit OWNER_PASSWORD_RESET); send the catalogued VENUE_OWNER_PASSWORD_RESET notification to the venue through the outbox in that same transaction once the catalogue exists. The notification never contains the password.

Carry-forward from E7-39 (do these too):
- Contract from E7-39 (packages/api-client/SPRINT-2-CONTRACT.md; stubs already in the controllers named there): implement GET /customer/notifications (newest first, cursor paging), POST /customer/notifications/{notificationId}/read (204; reading an already-read one changes nothing), POST /customer/notifications/devices {token, platform} -> 201 CustomerDevice (a token already active is seen again, not duplicated), and POST /customer/notifications/devices/{deviceId}/revoke -> 204 (sets revoked_at, never deletes; PO decision 5 Oct 2026). Controller: notification/web/CustomerNotificationController.

Carry-forward from E7-41 (PO decisions 9 Oct 2026, do these too):
- Admin 'try again' for a notification delivery in DEAD_LETTER (for example the provider was down and is back): an admin command that calls notification.recover_dead_letter(deliveryId, reason) inside admin.begin_command (admin runtime role only; the function writes the audit row). The route is not in arch 09 or the Sprint 2 contract yet: add it to the contract first (admin route group, e.g. POST /admin/notification-deliveries/{deliveryId}/recover with a reason) and regenerate the OpenAPI client. An outbox event in DEAD_LETTER stays final; only deliveries are recovered.
- The OTP SMS already goes through the outbox since E7-41 (code encrypted in the payload, PO decision 9 Oct 2026): keep it there, and never put the code in notification params.

PO decisions at the start of work (10 Oct 2026, do these too):
- Any signed-in customer may register a push device and receives notifications, phone verified or not (verification gates only the actions that need it: booking, tournaments, reviews).
- EMAIL deliveries go to the email given at registration, verified or not (email verification is deferred); revisit when email verification ships.
- Push and email text: a template catalogue keyed by code and language in the notification worker (ADR-021); the backend stores and returns codes only. E7-43 builds the mechanism and the copy for the code it sends itself (VENUE_OWNER_PASSWORD_RESET); E7-55, E7-80 and E7-89 add the English copy for the codes they emit, and the PO reviews the wording in each PR.
- A push token already active for another customer (shared phone) moves to the customer registering it now: the old row is revoked (revoked_at, never deleted) and a new active row is created in the same transaction.

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

Carry-forward from E7-39 (do these too):
- Contract from E7-39 (packages/api-client/SPRINT-2-CONTRACT.md; stubs already in the controllers named there): GET /venue/stream sends `event: change` with data {type, id, reason} (arch 08 section 5; this replaces {type, entityId} in step 1): type BOOKING | TOURNAMENT, reason CREATED (new booking request) | REQUESTED (new tournament request) | CHANGED; `: heartbeat` every 15 s. Authentication is the ordinary Bearer header: the Dashboard reads the stream with fetch() + ReadableStream, never EventSource, and no token goes in the URL (PO decision 5 Oct 2026). Controller: notification/web/VenueStreamController.
- Web Push routes (PO decision 5 Oct 2026): GET /venue/push-subscriptions/vapid-public-key -> {publicKey}; POST /venue/push-subscriptions {endpoint (https), keys {p256dh, auth}, userAgent?} -> 201 (an endpoint already active is seen again, not duplicated); POST /venue/push-subscriptions/{subscriptionId}/revoke -> 204 (sets revoked_at, never deletes). Controller: notification/web/VenuePushSubscriptionController.

Carry-forward from E7-43 (PO decision 10 Oct 2026, do this too):
- Venue PUSH deliveries created before this task wait PENDING (E7-43's NotificationDeliveryWorker has no Web Push adapter and leaves them). Add the venue Web Push sender to that worker (render the text from the E7-43 template catalogue in the venue account's language) so these waiting deliveries and every new one are sent, through the same outbox retry and dead-letter rules.

PO decisions at the start of work (10 Oct 2026, do these too):
- Start this task only after E7-43 (notifications) is merged into main; branch from main.
- Disabled venue account (FR-VEN-08, arch 08 section 5): publish a VenueAccountDisabled outbox event from the venue-account disable command (in the same command transaction), and its consumer tells every open stream of that venue account to close at once through NOTIFY. Backup: each 15-second heartbeat also re-reads the account status and closes the stream if it is DISABLED (only open streams are checked; a disabled account cannot log in, so nothing reconnects).
- VAPID keys (the server's own Web Push signing keys; no provider account): new settings E7GZ_VAPID_PUBLIC_KEY, E7GZ_VAPID_PRIVATE_KEY and E7GZ_VAPID_SUBJECT, used when the Web Push provider is 'vapid'. Locally the default 'log' sender writes venue pushes to the worker log (like customer push), so no developer needs the keys, and the api serves a fixed dev public key. Name the settings in the PR.
- VAPID subject (the contact address push services may use): a setting only, no real address in the repo; dev uses mailto:dev@e7gz.local. The real address (a team address created before launch) is chosen in E7-101.

PO decisions in the E7-44/E7-47 review (10 Oct 2026):
- Live channel: BookingConfirmed, BookingRejected, VenueResponseExpired and BookingCancelled (E7-47) each send `event: change` {type BOOKING, id, reason CHANGED}, so the Pending and Missed lists refresh at once. Each later producer task adds its own events to VenueStreamSignals.
- Disabling a venue account also revokes every active Web Push subscription of that account in the same command (revoked_at set, never deleted). After a re-enable the Dashboard subscribes again when it is next opened.
- WaitingVenuePushes takes a transaction advisory lock, so two workers starting together queue each waiting venue push once.

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

Lane: Engine · Depends on: S1-E2, S2-P0, S2-P1; S2-P2, S2-P3 before the PR

```text
Read CLAUDE.md first. You are implementing task S2-E1 — "Online booking creation (O1)" (Engine lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 06 §1.3 (20 conditions)
- FRS 4.4
- Spec v5 §21–§23

Do:
1. CreateBookingCommand: the 20 named conditions in one ordered list, each citing its source, with a count assertion (risk I2).
2. Reservation + booking in one command; venue response deadline job (T1); fee snapshot from config.effective_numeric.
3. Outbox: BookingCreated and ReservationHeld in the command transaction (see the PO decisions below; the customer and venue notifications are E7-43's).

Done when (write these as tests first where possible):
- Boundary: submit at startAt−15:00 ok, −14:59 refused; sessionEnd−11 min ok, −9 min refused.

Carry-forward from E7-39 (do these too):
- Contract from E7-39 (packages/api-client/SPRINT-2-CONTRACT.md; stubs already in the controllers named there): implement POST /customer/bookings (Idempotency-Key required; 201 CustomerBookingDetail) with the error codes declared in booking/web/CustomerBookingController.
- Quote (PO decision 5 Oct 2026): implement GET /customer/bookings/quote?resourceId&playMode&startAt&durationMinutes -> {hourlyPrice, nominalBookedMinutes, venuePriceTotal, bookingFee {amount, currency, displayAsFree}, totalAmount}, using exactly the same price and fee code as creation (FR-BOK-06, FR-BOK-07, FR-PTS-13). It is not an availability check and holds nothing. Test: the quote equals the amounts of a booking created with the same input.
- Error codes (PO decision 5 Oct 2026): a venue with no price for the activity fails under FR-BOK-11 item 20 as 422 RESOURCE_NOT_ELIGIBLE.

PO decisions 9 Oct 2026 (Amr's pre-implementation review; do these too):
- Condition order: CreateBookingCommand uses the numbering and names of arch 06 section 1.3 (= FR-BOK-11); each entry also cites its Spec v5 section 22 number (e.g. arch 06 #11 = Spec 22 #17). The two lists hold the same twenty conditions and Spec 22 says all must pass, so which code wins when several fail is not a product rule: run #12-#15 (period shape) first, then one AvailabilityQuery.isFree call covers #10, #11, #16, #17 and #18. Tests: each condition failing alone, not combinations.
- E7-41 (outbox) and E7-42 (idempotency) are Sayed's and not on main yet: start now with everything else, write no temporary outbox or idempotency code, and open the PR only after both are merged. Then merge main, write the outbox events below and wire the Idempotency-Key (replay test: same key + same body returns the same booking, no second booking).
- Reads from other modules: same rule as E7-30 (PO decision 3 Oct 2026): small read-only interfaces in each owner's api package, SQL in one JDBC class in its internal package, the caller's CommandContext on every read, no new tables, each read tested in its owner module. Reuse VenueSellingBlocks, OperatingSessions, ResourceCatalog and ActiveCommitments; add only what is missing: venue eligibility (marketplace status, account status, area status), the Resource name, the effective booking fee (config.effective_numeric('booking.fee', at)), the customer's phone-verified and ban status. Venue, resource, config and identity are Platform: Sayed reviews those additions. Trust is Engine.
- Outbox: BOOKING_CREATED and VENUE_NEW_BOOKING_REQUEST are notification catalogue codes (Spec v5 52.2, 52.5; seeded in V1), produced by E7-43's notification consumer, not by this task. This task writes one integration event BookingCreated {bookingId, customerId, venueId, resourceId, startAt, endAt} with correlationId and occurredAt (arch 08 section 3.2, arch 01 section 11.1), and ReservationLedger.create writes ReservationHeld {reservationId, resourceId, venueId, period} for the reservation it creates, both in the command transaction; E7-41's projection consumer refreshes that venue's Nearest Availability rows from ReservationHeld. Test: a refused or rolled-back creation writes neither event.
- Error mapping: #1 403 PHONE_VERIFICATION_REQUIRED; #2 403 ACCOUNT_BANNED_FOR_THIS_ACTION; #3 COMMITMENT_LIMIT_REACHED; #4 OVERLAPPING_COMMITMENT; #5 marketplace not ACTIVE, #6 venue account DISABLED, #7 area not ACTIVE: 422 VENUE_NOT_ELIGIBLE_FOR_NEW_ACTIVITY (the customer is never told which); #8, #9 ONLINE_INTAKE_WINDOW_CLOSED; #10 VENUE_SELLING_BLOCK_ACTIVE; #11 RESOURCE_SELLING_BLOCK_ACTIVE; #12 BOOKING_LEAD_TIME_TOO_SHORT; #13, #14 BOOKING_DURATION_INVALID; #15 INTERVAL_NOT_WITHIN_ONE_SESSION; #16 (DISABLED or ARCHIVED), #17 owner review, #19 activity mismatch, #20 (incl. no price) 422 RESOURCE_NOT_ELIGIBLE; #18, including an emergency interval, maintenance or closure (availability checks 5-8; Spec v5 16.3 counts them as blocking reservations), 409 SLOT_NO_LONGER_AVAILABLE.
- Unknown resourceId on create and quote: 404 NOT_FOUND (what AvailabilityQuery.isFree already throws; arch 09 section 1.5). Add @ApiError(status = 404, codes = NOT_FOUND) to both operations in CustomerBookingController and regenerate packages/api-client.

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

Carry-forward from E7-39 (do these too):
- Contract from E7-39 (packages/api-client/SPRINT-2-CONTRACT.md; stubs already in the controllers named there): implement GET /venue/requests/pending (PENDING) and GET /venue/requests/missed (VENUE_RESPONSE_EXPIRED before startAt), and POST /venue/bookings/{bookingId}/accept, /reject {reasonText}, /reject-after-expiry (Idempotency-Key required, no Owner Password), each answering the VenueBooking DTO of booking/web/VenueBookingController.
- Error codes (PO decision 5 Oct 2026): accept, reject or reject-after-expiry on a booking no longer in the needed state (e.g. the customer cancelled a second earlier) is 409 BOOKING_STATE_CHANGED (new in ApiErrorCode and arch 09 section 1.5). An accept stopped by an emergency or owner review (FR-BOK-16) cancels the booking under that cause AND answers the matching 422 (VENUE_SELLING_BLOCK_ACTIVE, RESOURCE_SELLING_BLOCK_ACTIVE or RESOURCE_NOT_ELIGIBLE). The reject reasonText is at most 200 characters.

PO decisions during work (10 Oct 2026; recorded after merge in the E7-44/E7-47 review):
- D1: Accept and Reject withdraw T1 (VENUE_RESPONSE_EXPIRY) by moving it to SUPERSEDED (ScheduledJobs.supersede: PENDING, PAUSED or FAILED only). A job a worker holds LEASED is left to that worker, whose fire re-reads the booking and supersedes it itself.
- D2: An Accept stopped by an operational cause (FR-BOK-16) commits the cancellation together with its 422 stored under the Idempotency-Key (Idempotency.wrapAnswer, CommandAnswer.refused); a replay with the same key answers the same 422 without running again.
- D3: The operational causes are read each from its own state, in FR-BOK-23's priority: owner review (RESOURCE_OWNER_REVIEW_REQUIRED, 422 RESOURCE_NOT_ELIGIBLE), resource emergency (RESOURCE_EMERGENCY_OUTAGE, 422 RESOURCE_SELLING_BLOCK_ACTIVE), venue emergency (VENUE_EMERGENCY_CLOSURE, 422 VENUE_SELLING_BLOCK_ACTIVE). Any other failing check (a selling block without an emergency, a disabled resource, the customer) refuses and changes nothing. A missing or mismatched reservation is a data defect (D11), never a refusal.
- D4: New integration events, each with the BookingCreated fields {bookingId, customerId, venueId, resourceId, startAt, endAt}: BookingConfirmed; BookingRejected + terminalReason (VENUE_REJECTED or REJECTED_AFTER_EXPIRY; never the reason text); VenueResponseExpired + venueResponseExpiresAt; BookingCancelled + terminalReason. ReservationLedger.release publishes ReservationReleased.
- D5: Accept and Reject only while now < venue_response_expires_at (the stored deadline); at or after it DEADLINE_PASSED - expiry wins. Reject After Expiry only while now < startAt. T1 dates the history row and the release at the stored deadline, not the worker's run time. A booking no longer in the needed state is 409 BOOKING_STATE_CHANGED.
- D6: A paused response timer has no running deadline: Accept is refused and the booking is never expired by the clock alone.
- D7: Order of the checks: not PENDING, then a paused timer, then the deadline, then the revalidation. The pause is checked before the FR-BOK-16 cancel.
- D8: A paused Accept is an ordinary refusal, not stored under the Idempotency-Key, so a retry with the same key is evaluated afresh. Code: RESOURCE_SELLING_BLOCK_ACTIVE for a resource-level pause, otherwise VENUE_SELLING_BLOCK_ACTIVE (also for a pause still waiting after its emergency ended, FR-OPS-39).
- D9: A customer not eligible at Accept (banned, deleted or restricted; FR-BOK-14) is 422 CUSTOMER_NOT_ELIGIBLE (new in ApiErrorCode and arch 09 section 1.5). Neutral: the venue is never told why. The booking stays PENDING; the venue may reject it, and unanswered it expires as a missed request like any other (confirmed in the review; the ban cascade cancels the customer's bookings separately).
- D10: Exactly one BookingConfirmed per Accept, never a BookingAccepted.
- D11: A broken reservation or ledger invariant under a booking is a data defect: logged at ERROR with the booking and correlation IDs, the whole command rolled back (no Idempotency-Key record), a generic 500 without rule text. No Platform Admin alert until E7-56 exists.
- E7-44/E7-47 review: the venue may Reject while an emergency pauses the response timer (FR-OPS-34 disables only Accept). No deadline check (D6); the pause ends with the rejection - added to cumulative_emergency_pause_duration (FR-OPS-37) and its open operations.booking_timer_pause_history row closed (FR-OPS-41, through operations.api.ResponseTimerPauses) - and the PAUSED T1 is superseded. This replaces the 409 BOOKING_STATE_CHANGED first merged (fix: branch E7-47-review-fixes).

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
- FRS FR-MAN-09 (business date)
- arch 06 §2.3 (BOOKING_START / BOOKING_COMPLETE)
- arch 08 §3.2 (integration events)

Do:
1. ManualBookingCommand with its own 15-condition predicate; business date for out-of-hours.
2. PCR-007 first, in its own commit (PO decision 10 Oct 2026, E7-48, Part A1): write docs/01-product/PCR-007 — Cancel an ACTIVE Manual Booking.md (same format as PCR-006). Rule: venue staff may cancel a Manual Booking that is CONFIRMED or ACTIVE, without the Owner Password, reason VENUE_CANCELLED_MANUAL_BOOKING. On an ACTIVE one: status ACTIVE -> CANCELLED, the room is free from the cancel instant (reservation released at the command instant), the scheduled BOOKING_COMPLETE job is cancelled, attendance stays NOT_APPLICABLE, fee treatment NOT_APPLICABLE, no Trust/points effect. It amends FRS F-16 item 7, FR-MAN-08 and arch 06 §1.4 (Cancellation row): update those lines and say "amended by PCR-007".
3. PCR-007 migration (PO decision 10 Oct 2026, E7-48, Part A2): the DB refuses ACTIVE -> CANCELLED with VENUE_CANCELLED_MANUAL_BOOKING (booking state guard in V1__baseline.sql, "WHEN OLD.status='ACTIVE' AND NEW.status='CANCELLED'"). Write a new migration V<yyyyMMddHHmm>__manual_booking_active_cancel.sql that allows it ONLY for source='MANUAL' and actor VENUE. Follow every Migration safety rule in CLAUDE.md §5: .dbm updated, change-report entry, MigrationFilesTest check, migration-lock.txt line, upgrade-seed if needed, grep every changed function name, ./gradlew check at least twice. Do not edit V1__baseline.sql.

Done when (write these as tests first where possible):
- Manual booking at 3 AM for a venue closing at 2 AM succeeds with a businessDate: at 03:00 Cairo after a 14:00-02:00 session it has no session snapshot and businessDate = Saturday, the Cairo calendar date of startAt (FR-MAN-09) - on a controlled command clock (CommandInstants), plus a real-DB integration test of the business-date and snapshot rules.
- A 01:00-03:00 booking in a 14:00-02:00 session (starts inside, ends after it) gets no session snapshot and businessDate = Saturday.
- Cancelling an ACTIVE manual booking (PCR-007) frees the room from the cancel instant (reservation released at the command instant) and cancels its BOOKING_COMPLETE job.
- A future manual booking schedules BOOKING_START at startAt; a walk-in schedules BOOKING_COMPLETE at endAt.
- PCR-007 and its migration are in: MigrationSafetyTest and MigrationFilesTest green, migration-lock.txt line added, ./gradlew check green twice.

Carry-forward from E7-39 (do these too):
- Contract from E7-39 (packages/api-client/SPRINT-2-CONTRACT.md; stubs already in the controllers named there): implement POST /venue/bookings {resourceId, playMode?, startAt?, durationMinutes, customerName?, customerPhone?} -> 201 VenueBooking (startAt absent = walk-in starting now at server time, FR-MAN-04; customerPhone normalised to E.164 like the venue phone) and POST /venue/bookings/{bookingId}/cancel-manual (FR-MAN-08 as amended by PCR-007: CANCELLED / VENUE_CANCELLED_MANUAL_BOOKING from CONFIRMED - before or after startAt - or ACTIVE, no Owner Password; see D5). Idempotency-Key required on both. Controller: booking/web/VenueBookingController.
- Error codes (PO decision 5 Oct 2026): a manual booking on an ONBOARDING venue is 403 FORBIDDEN; cancel-manual on a booking already cancelled or completed is 409 BOOKING_STATE_CHANGED.

Carry-forward from E7-45 (PO decision 10 Oct 2026, do this too):
- The boundary tests of this task are already written and switched off (@Disabled("E7-48: ...")): backend/src/test/java/ps/e7gz/support/boundary/pending/ManualBookingBusinessDateBoundaryTest.java. Move the class next to this task's code, replace each fail(...) with the steps its comment names, using the boundary pack ps.e7gz.support.boundary (Boundary, CommandInstants, Deadlines, DueJobs, BoundaryAssertions; see its package-info), and remove @Disabled. The task is done only when they pass. The pack and these tests come with E7-45: merge main once E7-45 is merged.

PO decisions 10 Oct 2026 (Sayed, E7-48; quote "PO decision 10 Oct 2026, E7-48" in code comments):
- D1: Business date: a 03:00 Cairo booking after a session ending 02:00 has no session snapshot and businessDate = the Cairo calendar date of startAt (Saturday), FR-MAN-09. Fix the comment in ManualBookingBusinessDateBoundaryTest (it says "the date the 14:00 session opened on" - wrong), then enable the test and move it per the pending package-info.
- D2: A booking that starts inside a session but ends after it: no snapshot, Cairo date of startAt (the DB snapshot trigger only allows a snapshot when the whole interval fits). Add a test showing a 01:00-03:00 booking in a 14:00-02:00 session gets Saturday.
- D3: Tests: controlled command clock for the exact 03:00 case + a real-DB integration test for the business-date and snapshot rules.
- D4: Errors: overlap with closure, emergency closure, resource emergency outage, maintenance -> 409 SLOT_NO_LONGER_AVAILABLE; a selling block active now -> VENUE_SELLING_BLOCK_ACTIVE / RESOURCE_SELLING_BLOCK_ACTIVE (same as online); ONBOARDING venue -> 403 FORBIDDEN; disabled venue account: the security filter already returns 401 UNAUTHENTICATED (AccessTokenSecurityTest) - keep the in-command check for the race and map it to 403 FORBIDDEN, the HTTP test expects 401; missing pricing -> MANUAL_BOOKING_PRICING_CONFIGURATION_MISSING (FR-MAN-10).
- D5: cancel-manual: on an ONLINE booking -> 409 BOOKING_STATE_CHANGED; on CANCELLED or COMPLETED -> 409 BOOKING_STATE_CHANGED; on CONFIRMED (before or after startAt) or ACTIVE -> allowed (PCR-007).
- D6: Events: future manual -> BookingConfirmed; walk-in -> BookingStarted (already in arch 08 §3.2; add the constant to IntegrationEvents). Payload has source: MANUAL, customerId null. Fix the BOOKING_CONFIRMED javadoc ("accepted a PENDING Online Booking"). Map BookingStarted in VenueStreamSignals. Cancel emits BookingCancelled. Do not emit ReservationHeld yourself (the ledger does). messaging/ and notification/ are Platform lane files: list them in the PR for Sayed's review.
- D7: API: add missing 403/404 annotations, run pnpm api:gen, tell the Clients lane what changed.
- D8: Jobs: future manual -> schedule BOOKING_START at startAt (same subject/key form as VenueAnswers.BOOKING_START). Walk-in -> schedule BOOKING_COMPLETE at endAt (name from arch 06 §2.3, not BOOKING_COMPLETION). Handlers belong to E7-60 / E7-61; do not write them.
- D9: Attendance: leave creation/update to E7-61; manual stays NOT_APPLICABLE.
- D10: customerName and customerPhone: trim, blank -> NULL; phone normalised to E.164.
- Keep the 15-condition manual predicate separate from the online one. Do not change E7-46/E7-47 behaviour.

PO decisions 11 Oct 2026 (Sayed, PR #67 review; quote "PO decision 11 Oct 2026, E7-48" in code comments):
- D11: PCR-007 is approved as written.
- D12: An ACTIVE manual booking may be cancelled after endAt if its completion job has not run.
- D13: The deferred history provenance check is approved instead of changing CommandRunner. Its recorded-actor limitation is explicitly accepted.
- D14: E7-60 must schedule BOOKING_COMPLETE at endAt whenever a booking (ONLINE or MANUAL) transitions CONFIRMED → ACTIVE.

Constraints: stay inside this task's module(s), except the Platform-lane files of D6 (messaging/, notification/), which you list in the PR for Sayed's review; do not edit docs/ except PCR-007 and the lines it amends (FRS F-16 item 7, FR-MAN-08, arch 06 §1.4); never edit V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4 (./gradlew check green twice, pnpm -r lint typecheck build green), then give me a short summary, the files changed, and any question for the Product Owner. Do not push or open the PR — Sayed reviews first.
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
- D1 - complete contact-visibility retention matrix, on every customer route (list, detail, cancel response and its replay) and every venue route (list, detail, request lists, action responses and their replays): the counterpart phone is present just before and ABSENT from the JSON (key missing - not null, not masked, not empty) at and after each retention point: PENDING / AWAITING_CUSTOMER_CONFIRMATION / CONFIRMED / ACTIVE visible; terminal before startAt -> terminalAt + 2h; terminal at or after startAt, NO_SHOW included -> endAt + 2h; VENUE_RESPONSE_EXPIRED -> venueResponseExpiredAt + 2h; VENUE_RESPONSE_EXPIRED then REJECTED_AFTER_EXPIRY or CANCELLED after expiry + 2h stays hidden permanently; terminal before expiry + 2h -> min(expiry + 2h, terminalAt + 2h); a Late Confirm reopens it through AWAITING_CUSTOMER_CONFIRMATION and it stays visible after Accept; a MANUAL booking's walk-in name/phone is exempt.
- D2 - customer cancel: PENDING, emergency-paused PENDING (pause closed with the cancellation) and CONFIRMED cancel at startAt - 1s -> CANCELLED / CUSTOMER_CANCELLED with exactly one reason, reservation released at the command instant, one history row (actor CUSTOMER), fee decision recorded, T1 / BOOKING_START superseded; at startAt and on ACTIVE -> 422 DEADLINE_PASSED; VENUE_RESPONSE_EXPIRED, AWAITING_CUSTOMER_CONFIRMATION, COMPLETED, REJECTED, CANCELLED and NO_SHOW -> 409 BOOKING_STATE_CHANGED; another customer's booking -> 404 NOT_FOUND with no idempotency record; missing Idempotency-Key -> 400; a replay answers the same response.
- D3 - venue q: a positive test for each searchable field (exact booking ID, current customer username, walk-in name, walk-in phone, resource name) and a negative test: searching a customer's phone number finds nothing.
- D4 - customer cancel writes exactly one BookingCancelled outbox event and no customer notification row (deferred to E7-55); GET /venue/bookings with no from/to returns all dates newest first, cursor paginated, limit > 100 -> 400 VALIDATION_FAILED; from/to are inclusive business dates.
- NFR-19 - the venue booking history never shows LATE_CONFIRM_FAILED_BOOKING_LIMIT or LATE_CONFIRM_FAILED_CUSTOMER_CONFLICT (test plants both events and asserts they are absent from GET /venue/bookings/{bookingId}).
- The integrity sweep (InvariantSweep: NFR-26 terminal reason, NFR-28 overlap) reports zero violations after a seeded run of booking flows (create, accept, reject, expiry, customer cancel, manual walk-in and its cancel).
- J1 (find and book: discovery -> quote -> create PENDING -> venue accept -> CONFIRMED in the upcoming bucket) and J8 (walk-in ACTIVE in GET /venue/bookings and its detail) end-to-end tests over HTTP.
- D3 amendment - an exact username finds that customer's bookings; the same username in another case and with surrounding spaces finds them too (username-login normalisation); a partial username finds nothing; the customer's phone finds nothing; an anonymised customer's username finds nothing; one username lookup per search.
- D4 ordering - GET /venue/bookings is ordered by start_at DESC then booking id DESC, and cursor pages follow that order without repeats or gaps.

Carry-forward from E7-39 (do these too):
- Contract from E7-39 (packages/api-client/SPRINT-2-CONTRACT.md; stubs already in the controllers named there): implement GET /customer/bookings?bucket=upcoming|completed|cancelled, GET /customer/bookings/{bookingId}, POST /customer/bookings/{bookingId}/cancel (Idempotency-Key required; DEADLINE_PASSED at or after startAt), GET /venue/bookings (filters of FR-VEN-38; from/to are business dates, inclusive) and GET /venue/bookings/{bookingId} with its history. Controllers: booking/web/CustomerBookingController, VenueBookingController.
- The venue booking history never shows LATE_CONFIRM_FAILED_BOOKING_LIMIT or LATE_CONFIRM_FAILED_CUSTOMER_CONFLICT (NFR-19, arch 10 section 5.2). Add a test.
- Error codes (PO decision 5 Oct 2026): customer cancel on a booking already terminal is 409 BOOKING_STATE_CHANGED.

PO decisions 11 Oct 2026 (Sayed, E7-49; quote "PO decision 11 Oct 2026, E7-49" in code comments):
- D1: After venueResponseExpiredAt + 2h, counterpart phones remain hidden permanently, even if the booking later becomes REJECTED/CANCELLED, unless Late Confirm reopens it through AWAITING_CUSTOMER_CONFIRMATION. If terminal before expiry+2h, visibility ends at min(expiry+2h, terminalAt+2h). MANUAL exempt. Hidden fields must be absent from JSON.
- D2: PENDING/CONFIRMED customer cancellation before startAt allowed (including emergency-paused PENDING). ACTIVE or deadline reached -> 422 DEADLINE_PASSED. VENUE_RESPONSE_EXPIRED/AWAITING and all terminal statuses -> 409 BOOKING_STATE_CHANGED. Other customer's booking -> 404 NOT_FOUND.
- D3: Venue q searches exact booking ID, current customer username, walk-in name/phone, resource name. Never customer phone. Add a negative search test.
- D4: Customer cancel publishes BookingCancelled outbox event; customer notification deferred to E7-55. Venue list with no date filters returns all dates, newest first, cursor paginated, limit <= 100.

PO decisions 11 Oct 2026, second set (Sayed, E7-49 search and order; quote "PO decision 11 Oct 2026, E7-49" in code comments):
- D3 amendment (exact username): q matches a customer only when it equals their current username exactly, after the same trim and case normalisation as username login; a partial username never matches. The other D3 fields are unchanged: exact booking ID, walk-in name, walk-in phone, resource name. The customer phone is never searchable. One username lookup per search, never a per-customer loop, and never LoginAccountDirectory's combined phone-or-username lookup. If identity/api has no username-only lookup, add one minimal read-only lookup returning the user ID and excluding anonymised accounts; list every Platform-lane file it changes for Sayed's review.
- D4 ordering approved: the venue booking list keeps ORDER BY start_at DESC, booking id DESC, and its cursor follows the same (start_at, id) order. Do not change it.

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

Carry-forward from E7-43 (PO decision 10 Oct 2026, do these too):
- iPhone push is required (arch 01 decision A7: iOS goes through FCM to APNs). Get the FCM registration token on both iOS and Android with @react-native-firebase/messaging (expo-notifications' getDevicePushTokenAsync returns the raw APNs token on iOS, which FCM cannot use) and register it with POST /customer/notifications/devices {token, platform: IOS or ANDROID}; re-register on token refresh; revoke on logout.
- Use an EAS development build (Expo Go cannot receive real push); add the Firebase config plugin, the iOS push-notification capability and background mode remote-notification, and ask for notification permission in the app.
- Never show an 'iOS push disabled' banner: the in-app Notifications page is the fallback when permission is denied, on every platform. Until the PO's Apple Developer account and the APNs key exist (set up together before deployment, E7-101), 'Push received on a device in dev' is checked on Android; the iPhone check is done at the E7-101 release smoke test.

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

Carry-forward from the Sprint 2 review (PO decisions 12 Oct 2026, do these too):
- Venue bookings list (PO decision 12 Oct 2026; FR-VEN-38). The Dashboard 'Bookings' and 'Booking history' pages are titles only, and no task built them. From GET /venue/bookings (E7-49): date range (today, yesterday, last 7 days, this month, custom; from/to are business dates, inclusive), activity, source, resource, status, and the q search (exact booking ID, current customer username, walk-in name or phone, resource name - never the customer phone); newest first, cursor paging, limit at most 100. Booking detail from GET /venue/bookings/{bookingId} with its history, the customer phone only when the API returns it, and the walk-in name/phone for a MANUAL booking. EN and AR.

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

Carry-forward from E7-39 review (do these too):
- Contract for the plan gaps (PO decision 6 Oct 2026 (E7-39 review: arch 09 §4.1 lists the route, but no task owned it)): add stubs POST /venue/bookings/{bookingId}/cancel-confirmed {ownerPassword} (Idempotency-Key required; 423 OWNER_PASSWORD_REQUIRED / OWNER_PASSWORD_INVALID; 409 BOOKING_STATE_CHANGED) for S3-E6, and GET /venue/overview -> {todayRevenue, monthRevenue, todayBookings, pendingRequests} (Money as in SPRINT-2-CONTRACT.md) for S3-E7 and S3-C4.

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
1. Wire every customer booking/Late Confirm/attendance code listed in §52.2 to the correct trigger, except LATE_CONFIRM_SCHEDULE_CONFLICT (withdrawn by PCR-008: a conflicting Late Confirm Accept is the synchronous 409 LATE_CONFIRM_CUSTOMER_CONFLICT, with no notification); EN message templates in the apps (backend sends codes + params only).
2. Copy-rule tests from arch 08 §4.5.

Done when (write these as tests first where possible):
- Each code has a test proving it is emitted exactly once for its event.

Carry-forward from E7-43 (PO decision 10 Oct 2026, do this too):
- Add the English push and email copy (title/body, email subject/body) for every PUSH or EMAIL notification code this task emits to the notification template catalogue E7-43 built (ADR-021: keyed by code and language, rendered in the notification worker; never stored or sent by the API). The PO reviews the wording in the PR.

Carry-forward from E7-44 (PO decision 10 Oct 2026, do this too):
- Venue notifications are Web Push in the venue account's language (FR-VEN-40), so for every VENUE notification code this task emits add both the English and the Arabic push copy (title/body) to the template catalogue (templates_en / templates_ar). Until a code has its copy, its venue push is closed TEMPLATE_MISSING and only the in-Dashboard list shows it. The PO reviews both wordings in the PR.

Carry-forward from the Sprint 2 review (PO decisions 12 Oct 2026, do these too):
- Start this task first in Sprint 3: until it is merged no booking event creates a notification. The Sprint 2 review (12 Oct 2026) ran a full booking flow (create, accept, reject, expiry, reject after expiry, customer cancel) and found 0 notification rows. E7-43 built the mechanism only; the only Notifications.notify call today is VENUE_OWNER_PASSWORD_RESET.
- Venue booking notifications (Spec v5 §52.5, FR-NTF-09) are in this task too, not only the §52.2 customer codes: VENUE_NEW_BOOKING_REQUEST on BookingCreated (decision A6: Web Push while the Dashboard is closed), VENUE_PENDING_REQUEST_EXPIRING, VENUE_BOOKING_AUTO_CANCELLED, VENUE_LATE_CONFIRM_ACCEPTED, VENUE_LATE_CONFIRM_DECLINED and VENUE_CUSTOMER_CONFIRMATION_EXPIRED. Wire each one whose trigger exists in main when you start (merge main for E7-58's Late Confirm events), and list the triggers you wired in the PR. Each is created exactly once per event by the one notification consumer (it must be idempotent: a retry runs every consumer again).
- VENUE_PENDING_REQUEST_EXPIRING needs a new timer at venue_response_expires_at - 3 minutes (minute 7 of the 10-minute window; PO decision 12 Oct 2026). The timer is superseded by Accept, Reject, customer cancel and expiry (as T1 is, VenueAnswers.venueResponseExpiryKey), and pauses with the response timer.
- Venue in-app notification list (PO decision 12 Oct 2026; FR-NTF-09 - venue notifications are stored with an IN_APP delivery but nothing reads them): add to the contract first, then implement, GET /venue/notifications (the signed-in venue account's notifications, newest first, cursor paging, same shape as GET /customer/notifications) and POST /venue/notifications/{notificationId}/read (204; reading an already-read one changes nothing; another account's notification is 404). Record the routes in arch 09 section 4.1 as E7-43 did for its admin route, regenerate packages/api-client, and tell the Clients lane: E7-63 builds the Dashboard page.
- One wording per code: the customer app renders in-app text from the code (apps/customer/src/features/notifications/notificationMessages.ts, E7-51) while push and email text come from the backend template catalogue. Use the same wording in both and let the PO review them together in this PR. The app reads params.venueName for BOOKING_CONFIRMED, BOOKING_REJECTED and BOOKING_STARTS_SOON: send it (the venue name at the event), or change the app in the same PR.
- Tests: a created booking gives exactly one VENUE_NEW_BOOKING_REQUEST with a PUSH delivery sent by the venue Web Push sender (the log sender in dev); an accepted, rejected, expired and customer-cancelled booking each give exactly one customer notification of the right code, with the Booking ID as reference (FR-NTF-04).
- VENUE_BOOKING_AUTO_CANCELLED split (PO decision 12 Oct 2026): this task sends it for the system cancellations that exist in Sprint 3 - an Accept stopped by an operational cause (FR-BOK-16, E7-47) and the start-time revalidation cancellations (E7-60). E7-80 adds the emergency, owner-review and closure cascades in Sprint 5. One notification per cancelled booking, from one place only.

Carry-forward from the Sprint 3 review (PO decisions 12 Oct 2026, do these too):
- One notification path (PO decision 12 Oct 2026): build ONE outbox consumer in notification/internal that turns integration events into booking, Late Confirm and attendance notifications - the only place they are created (no producer task calls Notifications.notify for them). This task wires the events that exist when it starts (BookingCreated, BookingConfirmed, BookingRejected, VenueResponseExpired, BookingCancelled, BookingStarted; the Late Confirm events if E7-58 is merged). Make adding an event a small documented change - one mapping entry, its copy and its exactly-once test - because E7-58, E7-59, E7-60, E7-61, E7-113 and E7-114 add their own codes there after this task merges. Step 1's 'every customer code' therefore means: the codes whose events exist now, plus the time-based reminders below; the trigger tasks add the rest.
- BOOKING_STARTS_SOON (Spec v5 §52.2) is a timer of this task (PO decision 12 Oct 2026): 20 minutes before startAt of a CONFIRMED online booking (scheduled on Accept; a booking confirmed less than 20 minutes before its start gets none), superseded when the booking is cancelled or no longer CONFIRMED, fired from the stored startAt, once per booking. Manual bookings have no customer and get none.

Carry-forward from F1 / PCR-008 (Amr under delegated PO authority, 13 Oct 2026; reviewed by Sayed), do this too:
- The Late Confirm codes are the six mapped from E7-58's five canonical events: LateConfirmInitiated -> LATE_CONFIRMATION_RECEIVED; LateConfirmWarningDue -> LATE_CONFIRMATION_EXPIRING; LateConfirmTimedOut -> CUSTOMER_CONFIRMATION_EXPIRED and VENUE_CUSTOMER_CONFIRMATION_EXPIRED; LateConfirmAccepted -> VENUE_LATE_CONFIRM_ACCEPTED; LateConfirmDeclined -> VENUE_LATE_CONFIRM_DECLINED. E7-58 adds them to this task's consumer after this task merges (PO decision 13 Oct 2026: E7-55 merges first). No consumer, mapping, copy or test for LATE_CONFIRM_SCHEDULE_CONFLICT; keep its V1 catalogue row and the existing customer-app translation key (removing either is a separate approved change).
- A booking confirmed through Late Confirm Accept gets no BOOKING_STARTS_SOON reminder (PO decision 13 Oct 2026): LateConfirmAccepted arms no reminder, and Late Confirm publishes no BookingConfirmed (E7-58 D2).
- A customer notification shows the venue name the event carries (payload venueName, as in E7-58's Late Confirm events), otherwise the venue's name when the notification is created (PO decision 13 Oct 2026).

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
- Alert when an account reaches the per-account login limit added by E7-57: moved to E7-57 (PO decision 15 Oct 2026, below).
- Audit Platform Admin and Venue Account login success and failure with the AuditWriter: account id when known, audience and outcome only, never the identifier, the password or the IP in clear. If no alert or audit catalogue code fits, ask the PO before adding one.

Carry-forward from the E7-32 review (PO decisions 4 Oct 2026, do these too):
- Before coding, add one migration (+ update the .dbm + change report, PO review): a SECURITY DEFINER producer admin.raise_alert(kind, severity, subject_type, subject_id, payload, correlation_id) returning the alert id, executable by e7gz_app_runtime and e7gz_admin_runtime. Do not give e7gz_app_runtime INSERT or UPDATE on admin.admin_alert. AdminAlertService.raise calls it, so an alert can commit in the same transaction as an Engine command (e.g. SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR).
- Severity is stored as SEV_1, SEV_2 or SEV_3 (arch 11 section 5.4). The column stays open text: the existing RISK alert (TOURNAMENT_CANCELLATION_ABUSE) stays valid, so no closed enum may reject it.
- Nightly invariant sweep findings: kind INVARIANT_VIOLATION, severity SEV_1, subject type and id from the violation, payload = the invariant code plus every fact of the violation. One alert per violation. An Admin-side bean implements ps.e7gz.inventory.sweep.api.InvariantViolationReporter and takes the correlation ID from CorrelationId.require() (opened by the E7-40 worker), never a new one per violation. One failing alert is logged and does not stop the others. Do not change ReadOnlyInvariantSweep.
- Acknowledge (replaces 'acknowledge twice fails'): the same admin acknowledging again succeeds and returns the same alert (DB rule in admin.acknowledge_alert); a different admin gets an error mapped to an ApiErrorCode (ask the PO if no existing code fits).
- Tests: a clean sweep raises no alert; a planted violation raises exactly one alert carrying its facts and the sweep's correlation ID; the reporter changes no Inventory, Booking or Trust row.

Carry-forward from the Sprint 2 review (PO decisions 12 Oct 2026, do these too):
- Publish the inventory-affecting events of the Platform commands (PO decision 12 Oct 2026). IntegrationEvents.INVENTORY_AFFECTING lists them and the Nearest Availability consumer already takes them, but E7-41 left their producers to later tasks and none took them: ResourceDisabled, ResourceReEnabled, ResourceArchived, ResourceRestored and ResourceConsoleChanged from resource/internal ResourceCommands (E7-26), VenuePricingChanged and RoomOverrideChanged from the pricing commands (E7-27), and ResourceRestrictionChanged wherever a restriction command already exists (otherwise E7-84 adds it). Each in the same command transaction, with venueId (and resourceId where there is one), constants in IntegrationEvents. Test: each event refreshes its venue's Nearest Availability rows without waiting for the one-minute pass (as E7-41's test does). A live Dashboard signal for these would be a contract change (VenueStreamSignal.type is BOOKING or TOURNAMENT only): do not add one without asking the PO.
- Booking integrity failure alert (E7-47 D11, PO decision 10 Oct 2026): VenueAnswers.integrityFailure and CustomerCancellation.integrityFailure (booking/internal) only log at ERROR and roll the command back ('No Platform Admin alert until E7-56 exists'). Raise a SEV_1 alert with the booking ID, the detail and the correlation ID. The command is rolled back, so the alert must commit in its own transaction after the rollback, never inside the failed command. Alert kind BOOKING_INTEGRITY_FAILURE (PO decision 12 Oct 2026). booking/ files are Engine lane: Amr reviews that part.
- Outbox dead-letter alert (E7-41: 'The admin alert is E7-56'): an outbox event that reaches DEAD_LETTER (today the dispatcher only logs it at ERROR, Sev-2) raises one SEV_2 alert of kind OUTBOX_DEAD_LETTER (PO decision 12 Oct 2026) with the event id, type, aggregate, last error and the event's correlation ID. A notification delivery in DEAD_LETTER raises no alert (PO decision 12 Oct 2026): the admin 'try again' route of E7-43 handles it.

PO decisions 15 Oct 2026 (E7-56 preparation, do these too):
- Admin alert routes (no contract had them): add them in this task, contract commit first - GET /admin/alerts (open alerts by default; acknowledged, kind and severity filters; newest first, cursor paging) and POST /admin/alerts/{alertId}/acknowledge {reason?} -> 200 AdminAlert. Regenerate packages/api-client, tell the Clients lane (E7-86 builds the inbox) and record both routes in arch 09 section 4.1 as E7-55 did.
- A different admin acknowledging an alert already acknowledged is 409 ADMIN_ALERT_ALREADY_ACKNOWLEDGED (new code).
- Booking integrity alerts cover every site that logs an integrity failure: VenueAnswers, CustomerCancellation, ManualCancellation and LateConfirmCommands (its T2/T3 handlers included).
- SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR has no producer until E7-60: build the typed alert in admin.api (booking ID, resource ID, unexpected state, startAt, detection time, conflicting source when known, correlation ID; SEV_1) and meet 'done when' with a test through it. E7-60 raises it.
- The login-limit alert moves to E7-57, which raises it through this task's alert API.
- New names: alert kinds REFRESH_TOKEN_REUSE (SEV_2) and ACCOUNT_LOGIN_LIMIT_REACHED (SEV_3, raised by E7-57); audit actions ADMIN_LOGIN_SUCCEEDED, ADMIN_LOGIN_FAILED, VENUE_ACCOUNT_LOGIN_SUCCEEDED and VENUE_ACCOUNT_LOGIN_FAILED (payload: audience and outcome only).
- A failed login for an unknown identifier is not audited: there is no account to be the subject (audit_event.subject_id is NOT NULL), and the rate limiter covers it.
- From the E7-58 review: add 'Open the Dashboard to view the booking.' to the English VENUE_CUSTOMER_CONFIRMATION_EXPIRED push body, as the Arabic has.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S3-P3 (E7-57) — Profile and account management

**What this task does:** Edit profile, change username, phone, email, reset password.

Lane: Platform · Depends on: S0-P6, S3-P2

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
- Per-account login limit (PO decision 2 Oct 2026, Sprint 0 review S-7): in addition to the existing per (identifier + IP) limit, count failed logins per account across all IPs: 50 failures in 1 hour pause login for that account for 15 minutes (iam.security_rate_limit_counter, limiter_type LOGIN, its own action_class such as CUSTOMER_ACCOUNT / VENUE_ACCOUNT / ADMIN_ACCOUNT, cooldown_until; no schema change, but note the new action_class values in the change report). All three audiences. Fails closed like the existing limiter. This task raises the alert through E7-56 (PO decision 15 Oct 2026, below).
- Password reset and change hash through the Argon2id gate from E7-24.

Carry-forward from E7-54 (PO decisions 11 Oct 2026, do this too):
- Fill the stubs of the Sprint 3 contract (E7-54, packages/api-client/SPRINT-3-CONTRACT.md) and keep their shapes: CustomerProfileController and the password-reset routes of CustomerAuthController. Phone change is two steps: POST /customer/profile/phone {phone} -> 201 {otpChallengeId} (PHONE_CHANGE code to the new phone), then POST /customer/profile/phone/verify {otpChallengeId, code} -> the profile. Password reset is by phone OTP only: request {phone} -> 201 {otpChallengeId}, an unknown or never-verified phone is 404 NOT_FOUND; complete {otpChallengeId, code, password} -> 204 and every refresh token of the account is revoked. POST /customer/profile/email and /email/verify stay 501 until the PO decides email verification: do not build them (no EMAIL_CHANGED yet). Replace this task's lines in backend/src/test/java/ps/e7gz/openapi/Sprint3StubHttpTest.java with your own tests.

PO decision 15 Oct 2026 (from E7-56, do this too):
- Alert when an account reaches the per-account login limit (moved here from E7-56): raise an admin alert of kind ACCOUNT_LOGIN_LIMIT_REACHED, severity SEV_3, with the account id and audience only - never the identifier, the password or the IP. Identity must not depend on admin.api (admin already depends on identity: a module cycle), so raise it through E7-56's port identity.api.SecurityAlerts: add one method there (e.g. accountLoginLimitReached(audience, accountId)) and implement it in admin/internal AdminAlertReporters, as refreshTokenReused is. Merge main once E7-56 is merged.

PO decisions 16 Oct 2026 (E7-57, do these too):
- Release an unverified account without a schema change: the row stays (nothing is hard-deleted); its phone becomes 'released:' || id (a placeholder, as anonymisation writes 'anonymised:' || id), its username released_<id>, its full name, email and password hash are emptied so nobody can log in, its open OTP codes are superseded and its refresh tokens revoked. Record the placeholder values in the change report.
- An unverified holder inside its 30-day username cooldown keeps its username: the new registration gets USERNAME_TAKEN (its phone is still released when the phone was the conflict).

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S3-E1 (E7-58) — Late Confirm (O3)

**What this task does:** Late Confirm: the venue offers a late slot, the customer accepts in time.

Lane: Engine · Depends on: S2-E2, S2-P6, S3-P1

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

Carry-forward from E7-45 (PO decision 10 Oct 2026, do this too):
- The boundary tests of this task are already written and switched off (@Disabled("E7-58: ...")): backend/src/test/java/ps/e7gz/support/boundary/pending/LateConfirmBoundaryTest.java. Move the class next to this task's code, replace each fail(...) with the steps its comment names, using the boundary pack ps.e7gz.support.boundary (Boundary, CommandInstants, Deadlines, DueJobs, BoundaryAssertions; see its package-info), and remove @Disabled. The task is done only when they pass. The pack and these tests come with E7-45: merge main once E7-45 is merged.

Carry-forward from E7-54 (PO decisions 11 Oct 2026, do this too):
- Fill the stubs of the Sprint 3 contract (E7-54, packages/api-client/SPRINT-3-CONTRACT.md) and keep their shapes: booking/lateconfirm/web CustomerLateConfirmController (accept / decline -> CustomerLateConfirmOutcome) and VenueLateConfirmController (send -> VenueLateConfirmation); also fill CustomerBookingDetail.lateConfirm {expiresAt, paused} and VenueBooking.lateConfirmExpiresAt / lateConfirmPaused. Venue-side failures reuse existing codes: the venue not inside an operating session now, or the interval no longer within the opening hours -> INTERVAL_NOT_WITHIN_ONE_SESSION; an inactive area -> AREA_INACTIVE. The pause is only the paused flag, with no reason field. Replace this task's lines in backend/src/test/java/ps/e7gz/openapi/Sprint3StubHttpTest.java with your own tests.

PO decisions 12 Oct 2026 (E7-58 discovery, do these too):
- D1: Keep FR-LCF-15/16: the venue may always send a Late Confirm; only Accept checks the customer's schedule. Migration authorised: a new V<yyyyMMddHHmm>__late_confirm_awaiting_overlap.sql drops and re-adds ex_customer_no_overlap under the same name with WHERE (commitment_period IS NOT NULL AND status <> 'AWAITING_CUSTOMER_CONFIRMATION'). Do not change booking.derive_commitment_period: AWAITING keeps its commitment_period, so ck_booking_commitment_period_shape and the 4-commitment count stay as they are. Same PR: the .dbm, a change-report entry, MigrationFilesTest, the migration-lock.txt line, and ./gradlew check twice (CLAUDE.md §5).
- D1: Accept checks the customer's overlap itself under the iam.user_commitment_guard lock. On a conflict it commits the LATE_CONFIRM_FAILED_CUSTOMER_CONFLICT history row, answers 409 LATE_CONFIRM_CUSTOMER_CONFLICT and the booking stays AWAITING - do not rely on a 23P01, which would roll the history row back. O1 condition 4 is unchanged: a new booking overlapping an AWAITING one is still refused (FR-BOK-04); add a test for it, since the database no longer covers that pair.
- D2: Publish only the five canonical events LateConfirmInitiated, LateConfirmAccepted, LateConfirmDeclined, LateConfirmTimedOut and LateConfirmWarningDue (arch 08 §3.2); O3 publishes no BookingConfirmed or BookingCancelled. Add the constants to messaging.api.IntegrationEvents and map Initiated, Accepted, Declined and TimedOut in notification.internal.stream.VenueStreamSignals (WarningDue is for the customer only). Both are Platform-lane files: list them in the PR for Sayed's review.
- D3: Decline never checks the paused flag (FR-OPS-41) and works while now < the stored late_confirmation.expires_at (ck_late_confirmation_response_shape). Keeping expires_at valid during a pause is E7-83's. Accept while paused is refused (FR-OPS-41 allows only Decline): propose its code in the PR, following E7-47 D8.
- D4: A banned or deactivated customer at initiation answers only 422 CUSTOMER_NOT_ELIGIBLE_FOR_LATE_CONFIRM and records no booking event. LATE_CONFIRM_FAILED_BOOKING_LIMIT is recorded only for the 4-commitment limit (FR-LCF-05): commit that history row and answer the same 422 (no hold; the booking stays VENUE_RESPONSE_EXPIRED). The customer's schedule conflict is never checked at initiation (FR-LCF-15). arch 09 §4.1 and arch 10 §5.2 were updated to match.
- Defaults: paused T2 and T3 handlers defer processing, like T1 (re-arming them is E7-83's); an ONBOARDING venue is 403 FORBIDDEN (as E7-48); the five @Disabled E7-45 boundary tests are implemented without weakening any production database guard.

Carry-forward from the Sprint 3 review (PO decisions 12 Oct 2026, do these too):
- One notification path (PO decision 12 Oct 2026): booking, Late Confirm and attendance notifications are created only by E7-55's notification consumer from integration events - never by calling Notifications.notify in this task. Map your five events there (the Late Confirm codes of Spec v5 §52.2 and §52.5 they trigger, e.g. LATE_CONFIRMATION_RECEIVED, LATE_CONFIRMATION_EXPIRING from LateConfirmWarningDue, CUSTOMER_CONFIRMATION_EXPIRED, VENUE_LATE_CONFIRM_ACCEPTED, VENUE_LATE_CONFIRM_DECLINED, VENUE_CUSTOMER_CONFIRMATION_EXPIRED), with the EN copy (and AR for venue codes) and one exactly-once test per code. E7-55 runs first in Sprint 3: merge main once it is merged.

Carry-forward from F1 / PCR-008 (Amr under delegated PO authority, 13 Oct 2026; reviewed by Sayed), do these too:
- No notification code for a conflicting Accept: it is the synchronous 409 LATE_CONFIRM_CUSTOMER_CONFLICT and the Booking Event only (PCR-008); LATE_CONFIRM_SCHEDULE_CONFLICT is never produced.
- After E7-55 merges (PO decision 13 Oct 2026: E7-55 first), merge main and add the six codes as entries of BookingNotificationRules.RULES (notification/internal; each event payload carries bookingId), the EN push copy (and AR for the venue codes) in notification/templates_*.properties, and one exactly-once test per code in BookingNotificationsIntegrationTest. Sayed reviews these Platform-lane files.
- LateConfirmAccepted arms no BOOKING_STARTS_SOON reminder (PO decision 13 Oct 2026): a late-confirmed booking gets none.
- Keep venueName (the venue name at the event) in the Late Confirm event payloads: the customer notifications show it (PO decision 13 Oct 2026).

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S3-E5 (E7-113) — Opening-hours change: cancel affected bookings (O5)

**What this task does:** When a venue saves new opening hours, cancel the future bookings the new hours no longer cover, release their holds and tell the customers.

Lane: Engine · Depends on: S1-P3, S2-E4, S3-E1, S3-P1

```text
Read CLAUDE.md first. You are implementing task S3-E5 — "Opening-hours change: cancel affected bookings (O5)" (Engine lane) of the E7GZ PS build plan.

Read only these docs for this task:
- FR-VEN-23, FR-VEN-24, FR-VEN-25, FR-VEN-26, FR-VEN-28
- arch 06 §2.4
- arch 03 §5 (impact preview token)
- FRS §2.4 (Owner Password)
- packages/api-client/SPRINT-1-CONTRACT.md §5.7

Do:
1. Replace the 501 in OpeningHoursCommands.Commit (venue/internal, E7-25): in the same transaction, cancel every affected Booking with VENUE_OPENING_HOURS_CHANGED and release its ledger row (BOOKING or LATE_CONFIRM_HOLD). Venue must not depend on Booking: declare a port in venue.api (like HoursChangeBookings) and implement it in the Booking module.
2. AWAITING bookings (FR-VEN-28): the Late Confirmation timer stops immediately, the hold is released, and the customer is notified explicitly.
3. Owner Password (FR-VEN-25, FRS §2.4): required only when the affected set has a CONFIRMED Online or an AWAITING booking. Use a separate @OwnerProtected("HOURS_CLOSURE_SAVE_CANCELLING_PROTECTED") command and flip that matrix row to built=yes. Missing: 423 OWNER_PASSWORD_REQUIRED. Wrong: 423 OWNER_PASSWORD_INVALID, audited.
4. Step 9: notify each affected Online customer through the outbox.
5. Keep from E7-25: the preview-token check (409 AFFECTED_SET_CHANGED_REFRESH_PREVIEW), the tournament block (422), ACTIVE never cancelled. PO decisions 3 Oct 2026: a Manual Booking made outside opening hours is never affected; a day whose session a booking references keeps its old session row; no audit row or outbox event for the hours change itself.

Done when (write these as tests first where possible):
- Tests: pending Online and Manual cancellations alone commit without the Owner Password; a CONFIRMED Online or AWAITING cancellation needs it (423 without it, audited when wrong); the hours, the sessions and all cancellations commit together or not at all; an AWAITING booking's hold is released and its Late Confirm stopped; a booking created after the preview gives 409; one notification per affected Online customer is enqueued.

Carry-forward from the Sprint 3 review (PO decisions 12 Oct 2026, do these too):
- Notifications (PO decision 12 Oct 2026; replaces 'notify ... through the outbox' in steps 2 and 4): each cancelled booking publishes BookingCancelled with terminalReason VENUE_OPENING_HOURS_CHANGED, and you add VENUE_OPENING_HOURS_CHANGED (Spec v5 §52.2) to E7-55's notification consumer with copy and an exactly-once test; never call Notifications.notify. The done-when 'one notification per affected Online customer' still holds. The PO decision of 3 Oct 2026 is unchanged: no event for the hours change itself.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S3-E2 (E7-59) — Check-in, late present, no-show (O13)

**What this task does:** Check-in, "arrived late", and no-show.

Lane: Engine · Depends on: S2-E2, S3-P1

```text
Read CLAUDE.md first. You are implementing task S3-E2 — "Check-in, late present, no-show (O13)" (Engine lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 07 §7
- FRS 4.6

Do:
1. Check-in window, Mark Present — Late, no-show; release reservation at the no-show instant; attendance_record rules.

Done when (write these as tests first where possible):
- Check-in at +14:59 ok, +15:00 refused and Late Present offered; Late-Present cannot become no-show.

Carry-forward from E7-45 (PO decision 10 Oct 2026, do this too):
- The boundary tests of this task are already written and switched off (@Disabled("E7-59: ...")): backend/src/test/java/ps/e7gz/support/boundary/pending/CheckInBoundaryTest.java. Move the class next to this task's code, replace each fail(...) with the steps its comment names, using the boundary pack ps.e7gz.support.boundary (Boundary, CommandInstants, Deadlines, DueJobs, BoundaryAssertions; see its package-info), and remove @Disabled. The task is done only when they pass. The pack and these tests come with E7-45: merge main once E7-45 is merged.

Carry-forward from E7-54 (PO decisions 11 Oct 2026, do this too):
- Fill the stubs of the Sprint 3 contract (E7-54, packages/api-client/SPRINT-3-CONTRACT.md) and keep their shapes: attendance/web VenueAttendanceController (-> VenueAttendance) and VenueBooking.attendance {checkInWindow, lateWindow, checkedInAt, latePresentAt, noShowAt} for ONLINE bookings (null for MANUAL); windows half-open [startAt, startAt + 15 min) and [startAt + 15 min, endAt); evidence reversed by an admin is absent. Errors as declared: CHECK_IN_WINDOW_CLOSED (also before startAt and at exactly +15:00), LATE_PRESENT_NOT_AVAILABLE, NO_SHOW_NOT_AVAILABLE; a manual booking answers the same code; a booking no longer in progress is 409 BOOKING_STATE_CHANGED. Replace this task's lines in backend/src/test/java/ps/e7gz/openapi/Sprint3StubHttpTest.java with your own tests.

Carry-forward from the Sprint 3 review (PO decisions 12 Oct 2026, do these too):
- Events (arch 08 §3.2): publish CheckInRecorded, LatePresentRecorded and NoShowRecorded in each action's command transaction, with the BookingCreated fields {bookingId, customerId, venueId, resourceId, startAt, endAt}; never BookingNoShowRecorded (PO decision 12 Oct 2026: one fact, one event). Add the constants to messaging.api.IntegrationEvents and map each in VenueStreamSignals as {type BOOKING, reason CHANGED} (PO decision 10 Oct 2026, E7-44 review: each producer task adds its own events), so other open Dashboards refresh.
- One notification path (PO decision 12 Oct 2026): booking, Late Confirm and attendance notifications are created only by E7-55's notification consumer from integration events - never by calling Notifications.notify in this task. Map your events there (CHECK_IN_RECORDED, NO_SHOW_RECORDED and any other attendance code of Spec v5 §52.2 they trigger) with EN copy and one exactly-once test per code. E7-55 runs first in Sprint 3: merge main once it is merged.

Carry-forward from the E7-59/E7-60 review (PO decisions 19 and 20 Oct 2026, do these too):
- Accepted as built (PR #81): the delayed-start no-show is kept but changed by R1, delivered in E7-60's PR #82 (check-in, Mark Present - Late and no-show all start a late-start booking first, as T4 would). PR #81 and #82 merge together, #81 first. The third consecutive no-show needs E7-71's ban: the pinned test stays until E7-71 replaces it, and E7-71 must be merged before E7-101.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S3-E3 (E7-60) — Start-time revalidation (O11)

**What this task does:** Re-check everything at start time and cancel with the right reason if needed.

Lane: Engine · Depends on: S3-E2, S3-P1, S3-P2

```text
Read CLAUDE.md first. You are implementing task S3-E3 — "Start-time revalidation (O11)" (Engine lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 05 §7
- arch 12 §6.2

Do:
1. At Start: revalidate, apply the seven-level reason priority, SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR + admin alert when inventory contradicts state.

Done when (write these as tests first where possible):
- Forcing a resource DISABLED behind the predicate produces the integrity error + alert.

Carry-forward from E7-45 (PO decision 10 Oct 2026, do this too):
- The boundary tests of this task are already written and switched off (@Disabled("E7-60: ...")): backend/src/test/java/ps/e7gz/support/boundary/pending/BookingStartBoundaryTest.java. Move the class next to this task's code, replace each fail(...) with the steps its comment names, using the boundary pack ps.e7gz.support.boundary (Boundary, CommandInstants, Deadlines, DueJobs, BoundaryAssertions; see its package-info), and remove @Disabled. The task is done only when they pass. The pack and these tests come with E7-45: merge main once E7-45 is merged.

Carry-forward from E7-48 (PO decision 10 Oct 2026, do this too):
- The BOOKING_START handler also starts future MANUAL bookings: E7-48 schedules BOOKING_START at startAt for a CONFIRMED manual booking (same subject/key form as VenueAnswers.BOOKING_START). MANUAL never uses check-in or NO_SHOW (FR-MAN-04).
- D14 (PO decision 11 Oct 2026, E7-48): schedule BOOKING_COMPLETE at endAt whenever a booking (ONLINE or MANUAL) transitions CONFIRMED → ACTIVE (same subject/key form as E7-48: BOOKING_COMPLETE:<bookingId>).

Carry-forward from the Sprint 2 review (PO decisions 12 Oct 2026, do these too):
- Overdue jobs on existing databases: Accept (E7-47) and future manual bookings (E7-48) have scheduled BOOKING_START since Sprint 2 with no handler, so on any database that ran Sprint 2 these jobs wait PENDING and all become due when this handler deploys - hours or days late, some after the booking's endAt. Until then CONFIRMED bookings never start and the customer's commitment slot stays held (COMMITMENT_LIMIT_REACHED on long-lived dev or staging data). Decide from the booking and its stored startAt, never from when the job runs (arch 08 section 2.2). Test: a BOOKING_START fired long after its due time, also after endAt, gives the on-time outcome, and the BOOKING_COMPLETE it schedules (D14) fires right after.

Carry-forward from the Sprint 3 review (PO decisions 12 Oct 2026, do these too):
- Events: the start publishes BookingStarted (already in IntegrationEvents and VenueStreamSignals since E7-48); a revalidation cancellation publishes BookingCancelled with its terminalReason (already mapped). Do not publish them twice for the same transition.
- One notification path (PO decision 12 Oct 2026): booking, Late Confirm and attendance notifications are created only by E7-55's notification consumer from integration events - never by calling Notifications.notify in this task. Map the start-time cancellations there: VENUE_BOOKING_AUTO_CANCELLED (the Sprint 3 half of the E7-55/E7-80 split) and the customer code of each cancellation reason that exists now, with copy and one exactly-once test per code. E7-55 runs first in Sprint 3: merge main once it is merged.

PO decision 15 Oct 2026 (from E7-56, do this too):
- Raise the SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR admin alert through E7-56's typed alert in admin.api (SEV_1, the full payload of arch 12 section 6.2), in the same command as the cancellation. Merge main once E7-56 is merged.

Carry-forward from the E7-59/E7-60 review (PO decisions 19 and 20 Oct 2026, do these too):
- R1 (review of PR #82): a late start resolves the same way for check-in, Mark Present - Late and no-show. On a CONFIRMED booking whose BOOKING_START is still waiting, inside the action's window: if the start (StartTimeResolution, judged at startAt) would start, start it first as T4 does (CONFIRMED -> ACTIVE, SYSTEM history dated startAt, BookingStarted, BOOKING_COMPLETE at endAt, the start job withdrawn), then record the action in the same command - a no-show then goes ACTIVE -> NO_SHOW and the BOOKING_COMPLETE it scheduled is superseded; if it would cancel, cancel under its start-time reason with the committed 409 BOOKING_STATE_CHANGED stored under the Idempotency-Key; otherwise the pure 409, nothing written. Reuse StartTimeResolution and the start transition, never a copy; update the venueCheckIn, venueMarkLatePresent and venueMarkNoShow descriptions and regenerate openapi.json; replace E7-59's checkInAndLatePresentOnADelayedStartAreRefusedAndLeaveTheStartWaiting. Tests: each action behind a late start for each outcome, a race with the real T4 starts once, key replay.
- Merge: PR #81 (E7-59) and #82 merge together, #81 first. Bring #82 up to date by merging main into it (no rebase, no force-push), check its diff and rerun CI.
- Accepted as built: VENUE_ACCOUNT_DISABLED is not a start reason for a CONFIRMED booking (FR-BOK-27); an orphaned selling block at startAt writes nothing and raises one SEV_1 (an accepted exception to FR-BOK-26: no product flow creates it, the data is fixed by hand); a blocker that began after startAt is left to E7-82/E7-84; customers get the generic BOOKING_AUTO_CANCELLED for VENUE_CLOSURE and SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S3-E4 (E7-61) — Completion and attendance defaults (O12)

**What this task does:** Finish bookings automatically and set the final attendance result.

Lane: Engine · Depends on: S3-E2, S3-P1

```text
Read CLAUDE.md first. You are implementing task S3-E4 — "Completion and attendance defaults (O12)" (Engine lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 07 §7.3

Do:
1. Completion job; the eight attendance-default rules; ATTENDANCE_UNCONFIRMED path with no points.
2. Enable all four timer boundary tests (±1 s) and journeys J2–J5.

Done when (write these as tests first where possible):
- Boundary pack green.

Carry-forward from E7-45 (PO decision 10 Oct 2026, do this too):
- The boundary tests of this task are already written and switched off (@Disabled("E7-61: ...")): backend/src/test/java/ps/e7gz/support/boundary/pending/BookingCompletionBoundaryTest.java. Move the class next to this task's code, replace each fail(...) with the steps its comment names, using the boundary pack ps.e7gz.support.boundary (Boundary, CommandInstants, Deadlines, DueJobs, BoundaryAssertions; see its package-info), and remove @Disabled. The task is done only when they pass. The pack and these tests come with E7-45: merge main once E7-45 is merged.

Carry-forward from E7-48 (PO decision 10 Oct 2026, do this too):
- The BOOKING_COMPLETE handler also completes MANUAL bookings (E7-48 schedules BOOKING_COMPLETE at endAt for a walk-in): skip the attendance-default rules for MANUAL - attendance stays NOT_APPLICABLE, no Trust/points (FR-MAN-06) - and stay cancellation-safe: an ACTIVE manual booking cancelled under PCR-007 has its BOOKING_COMPLETE cancelled, and a fire that finds the booking no longer ACTIVE changes nothing.

Carry-forward from the Sprint 2 review (PO decisions 12 Oct 2026, do these too):
- Overdue jobs on existing databases: walk-ins have scheduled BOOKING_COMPLETE since E7-48 with no handler, so on any database that ran Sprint 2 they wait PENDING (the booking stays ACTIVE) and all become due when this handler deploys. Complete as of the stored endAt, never the run time. Test: a BOOKING_COMPLETE fired hours late completes the booking dated at endAt.

Carry-forward from the Sprint 3 review (PO decisions 12 Oct 2026, do these too):
- Events (arch 08 §3.2): publish BookingCompleted and AttendanceResolved in the completion transaction (BookingCreated fields, plus attendanceResult on AttendanceResolved). Add the constants to IntegrationEvents and map BookingCompleted in VenueStreamSignals as {type BOOKING, reason CHANGED} (PO decision 10 Oct 2026, E7-44 review).
- One notification path (PO decision 12 Oct 2026): booking, Late Confirm and attendance notifications are created only by E7-55's notification consumer from integration events - never by calling Notifications.notify in this task. Map ATTENDANCE_UNCONFIRMED_COMPLETION there (FR-NTF-05: it says no attendance reward was applied), with copy and an exactly-once test; points notifications are Sprint 4 (E7-66). E7-55 runs first in Sprint 3: merge main once it is merged.

Carry-forward from the E7-59/E7-60 review (PO decisions 19 and 20 Oct 2026, do these too):
- The 10th attended completion: until E7-72 exists, trust.assert_user_integrity refuses a qualified user with no initial VERIFIED transition, so a customer's 10th attended completion fails T5 until the job is FAILED and the booking stays ACTIVE. E7-61 may still merge (PO decision 19 Oct 2026): pin today's behaviour in a test that E7-72 replaces. E7-72 must be merged before E7-101; FAILED BOOKING_COMPLETE jobs left on dev databases are fixed by hand (re-seed or re-arm; PO decision 20 Oct 2026).
- One writer of booking Trust outcomes: generalise E7-59's trust.api.NoShowOutcomes into trust.api.BookingOutcomes; E7-69, E7-70 and E7-72 reuse it.
- After R1 lands in E7-60 (PR #82), merge main: a no-show behind a late start goes through ACTIVE, so aNoShowBeforeADelayedT4LeavesNoStartAndNoCompletion changes.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S3-E6 (E7-114) — Venue cancels a confirmed booking (FR-VEN-03)

**What this task does:** The venue cancels a CONFIRMED online booking with the Owner PIN; the customer gets a full refund classification and a notification.

Lane: Engine · Depends on: S3-P0, S2-E2, S3-P1

```text
Read CLAUDE.md first. You are implementing task S3-E6 — "Venue cancels a confirmed booking (FR-VEN-03)" (Engine lane) of the E7GZ PS build plan.

Read only these docs for this task:
- FR-VEN-03, FR-VEN-05
- FRS §2.4 (Owner Password) and the FRS state table row CONFIRMED → CANCELLED (VENUE_CANCELLED_CONFIRMED_BOOKING)
- FR-PTS-15 (full refund classification)
- arch 09 §4.1, §4.3
- packages/api-client/SPRINT-2-CONTRACT.md §3 (BOOKING_STATE_CHANGED)

Do:
1. Fill the POST /venue/bookings/{bookingId}/cancel-confirmed {ownerPassword} stub (E7-54): CONFIRMED Online booking → CANCELLED with terminal reason VENUE_CANCELLED_CONFIRMED_BOOKING; release the reservation and the customer commitment; fee treatment FULL_REFUND (FR-PTS-15). Idempotency-Key required.
2. Owner Password (FR-VEN-03): a separate @OwnerProtected("CANCEL_CONFIRMED_ONLINE_BOOKING") command; flip that row of backend/src/test/resources/architecture/owner-password-actions.tsv to built=yes. Missing: 423 OWNER_PASSWORD_REQUIRED; wrong: 423 OWNER_PASSWORD_INVALID; every attempt audited (FR-VEN-05).
3. A booking that is not CONFIRMED (already cancelled, ACTIVE, a Manual booking, another venue's booking) is 409 BOOKING_STATE_CHANGED or 404; Manual bookings use cancel-manual (FR-MAN-08). Notify the customer through the outbox in the same transaction (catalogue code from E7-55).
4. No time limit (PO decision 6 Oct 2026): allowed while the booking is CONFIRMED, even if startAt has passed and the start job has not run yet. An ACTIVE booking is not cancelled this way (operational termination belongs to emergencies, Sprint 5).

Done when (write these as tests first where possible):
- Tests: cancel with the PIN → CANCELLED / VENUE_CANCELLED_CONFIRMED_BOOKING, reservation released, one customer notification; no PIN → 423 REQUIRED; wrong PIN → 423 INVALID and audited; a PENDING or already-cancelled booking → 409 BOOKING_STATE_CHANGED; a Manual booking is refused; OwnerPasswordMatrixTest green.

Carry-forward from E7-54 (PO decisions 11 Oct 2026, do this too):
- The stub and its request/response are in the Sprint 3 contract (E7-54, packages/api-client/SPRINT-3-CONTRACT.md): keep the shape. Replace this task's line in backend/src/test/java/ps/e7gz/openapi/Sprint3StubHttpTest.java with your own tests.

Carry-forward from the Sprint 3 review (PO decisions 12 Oct 2026, do these too):
- Notifications (PO decision 12 Oct 2026; replaces 'Notify the customer through the outbox in the same transaction' of step 3): publish BookingCancelled with terminalReason VENUE_CANCELLED_CONFIRMED_BOOKING and add its customer code to E7-55's notification consumer with copy and an exactly-once test; never call Notifications.notify. The done-when 'one customer notification' still holds.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S3-E7 (E7-115) — Venue Overview API (FR-VEN-35)

**What this task does:** The backend for the Dashboard home page: today's and this month's revenue, today's bookings and pending requests.

Lane: Engine · Depends on: S3-P0, S2-E4

```text
Read CLAUDE.md first. You are implementing task S3-E7 — "Venue Overview API (FR-VEN-35)" (Engine lane) of the E7GZ PS build plan.

Read only these docs for this task:
- FR-VEN-35, FR-VEN-36
- arch 09 §4.1 (revenue payload rules)
- arch 04 §4.3 (business date)

Do:
1. Fill the GET /venue/overview stub (E7-54): todayRevenue and monthRevenue (Money, aggregated on businessDate in the venue's time zone), today's bookings count, pending requests count. Today's schedule stays GET /venue/availability (E7-112); quick manual booking and quick create tournament are Dashboard buttons, not data.
2. Build the revenue sum once, as a read in the booking module's api package (online + manual now; tournament revenue joins in Sprint 6), so Revenue (GET /venue/revenue, Sprint 7, E7-104) reuses it. No payment method, cash/card split or reconciliation fields (FR-VEN-36).
3. Revenue counts only COMPLETED bookings (PO decision 6 Oct 2026); CONFIRMED, ACTIVE and every cancelled or rejected booking count nothing.

Done when (write these as tests first where possible):
- Tests: a seeded day with completed, confirmed, active, cancelled and other-venue bookings (online and manual) gives exactly the hand-computed numbers (only COMPLETED counts); a booking after midnight inside the previous business day counts on that day; money is BigDecimal scale 2.

Carry-forward from E7-54 (PO decisions 11 Oct 2026, do this too):
- todayBookings counts every booking of the venue on today's business date, online and manual, whatever its status - cancelled, rejected and no-show included (PO decision 11 Oct 2026, E7-54). Put it in the seeded-day test. Keep the VenueOverview shape of the Sprint 3 contract (packages/api-client/SPRINT-3-CONTRACT.md) and replace this task's line in backend/src/test/java/ps/e7gz/openapi/Sprint3StubHttpTest.java with your own tests.

PO decision 3 Oct 2026 (E7-31), restated 12 Oct 2026 - which day is "today" (do this too):
- Today is BusinessDates.currentBusinessDate(venueId) (inventory api, E7-31), from the injected Clock: the Operating Day of the session still running, so a night that crosses midnight is one business day. Never the Cairo calendar date (LocalDate.ofInstant) and never Instant.now().
- Test: for a venue open 18:00-02:00 Africa/Cairo, at 01:00 todayRevenue and todayBookings count the previous calendar day's business date (the session is still running); at 03:00 (after closing, no session) they count that calendar day.

Carry-forward from the Sprint 2 review (PO decisions 12 Oct 2026, do these too):
- Nearest Availability on a 15-minute grid (PO decision 12 Oct 2026): the projection's 'next available' start rounds UP to the next :00, :15, :30 or :45 in Africa/Cairo wall time - the earliest start on that grid that is at least now + 15 minutes (FR-BOK-02) and that the canonical predicate finds free inside one session. Today it is now + 15 minutes to the second (e.g. 19:37:21). The server still accepts any start minute for a booking (no grid rule); the app offers only grid starts (E7-62). Code: inventory/availability (the Nearest Availability projection and its candidate starts). Tests: at 19:22:41 the next available is 19:45:00; at exactly 19:30:00 it is 19:45:00; a session opening at 12:10 gives 12:15; on a DST change day the grid stays on Cairo wall time.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S3-C1 (E7-62) — Customer app: Late Confirm and status

**What this task does:** App: Late Confirm screen with countdown and booking status timeline.

Lane: Clients · Depends on: S3-P0, S3-P3

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
- Accept answered 409 LATE_CONFIRM_CUSTOMER_CONFLICT shows the localized message, keeps AWAITING and the countdown, and leaves Decline enabled; the message names no status (PCR-008).

Requirements first, prototype for the look (PO decision 2 Oct 2026):
- The requirement docs in the read list decide WHAT this task must show and do. The prototype decides only HOW it looks. An approved PCR (PCR-001…PCR-005) overrides the FRS lines it amends.
- Before coding, list every screen, state and action the requirements need for this task (including empty, loading, error, not-allowed/ineligible and banned states) and compare it with the prototype.
- Anything the requirements need that the prototype does not show: build it anyway, in the same design: @e7gz/tokens, the components and layout patterns already used in this app and in the prototype. Never invent a new visual style.
- Anything the prototype shows that the requirements do not define (a status, rule, timer, button or message): do not build it; list it as a question for the PO.
- Status names, reason codes, timers and messages come only from docs/01-product and the backend's error codes, never from the prototype.
- Customer app is English at launch, all text in the i18n resource files (FRS §3.1).
- In your summary, list the screens and states you built that are NOT in the prototype, so the reviewer can check them against the requirements.

Carry-forward from E7-54 (PO decisions 11 Oct 2026, do this too):
- Build against the Sprint 3 contract (E7-54, packages/api-client/SPRINT-3-CONTRACT.md): the countdown uses CustomerBookingDetail.lateConfirm.expiresAt against meta.serverTime. While lateConfirm.paused, show the timer as paused (no reason is sent - PO decision 11 Oct 2026), Accept disabled, Decline still available. Accept and decline return only {bookingId, status, terminalReason}: reload GET /customer/bookings/{id} afterwards. Map every error code the two routes declare.

Carry-forward from the Sprint 2 review (PO decisions 12 Oct 2026, do these too):
- Booking time picker (apps/customer/src/features/booking/BookingFlow.tsx, E7-50): hide start times earlier than meta.serverTime + 15 minutes (FR-BOK-02), and starts whose start + the chosen duration ends after the session closes (FR-BOK-05). The quote checks neither, so today the customer finds out only at Confirm (BOOKING_LEAD_TIME_TOO_SHORT, INTERVAL_NOT_WITHIN_ONE_SESSION).
- Online booking is possible only while the venue is open now and more than 10 minutes before its session ends, even for a later day (FR-BOK-08, FR-BOK-09). Say so before Confirm using CustomerVenueDetails.openNow and bookingEnabled; today ONLINE_INTAKE_WINDOW_CLOSED appears only at Confirm. The PO reviews the wording in the PR.
- The 'waiting for the venue' screen (PendingBooking in BookingFlow.tsx) is a dead end: add a way to My Bookings, and refresh the booking (GET /customer/bookings/{bookingId}) until it leaves PENDING - polling, and on push once E7-55 sends it - showing the new status and its timeline.
- Add NOT_FOUND to the booking error map (apps/customer/src/features/booking/errorMessages.ts): E7-46 answers 404 for an unknown or removed room.
- A customer whose phone is not verified sees the not-allowed state before choosing a time, with the way to verify the phone (FRS section 2.11), from GET /customer/profile phoneVerified (E7-57); today they fail only at Confirm. A banned customer keeps the ACCOUNT_BANNED_FOR_THIS_ACTION message until the standing screen exists (E7-74).
- Fix the flaky apps/customer/src/features/my-bookings/MyBookingsScreen.test.tsx: it failed once in a full pnpm -r test while the backend suite ran and passed 5 of 5 alone (look for a too-short waitFor or a fake-timer race).
- Start-time grid (PO decision 12 Oct 2026): the booking time picker offers starts every 15 minutes (:00, :15, :30, :45, Africa/Cairo wall time) instead of every 30. The server does not enforce the grid (no new rule or error code). Nearest Availability rounds up to the same grid (E7-115), so the 'next available' time on a venue card is always a start the picker offers.

Carry-forward from F1 / PCR-008 (Amr under delegated PO authority, 13 Oct 2026; reviewed by Sayed), do these too:
- Recognise the error: when POST /customer/bookings/{bookingId}/late-confirm/accept answers 409 with error.code LATE_CONFIRM_CUSTOMER_CONFLICT, handle it as its own case - not as a generic failure, LATE_CONFIRM_RESOURCE_CONFLICT or BOOKING_STATE_CHANGED.
- Show a clear, immediate message on the Late Confirm screen, from the i18n resource files (English at launch; no hard-coded text, no text from the backend). Suggested copy for PO review: "You can't accept this booking because it overlaps another booking of yours."
- Make no claim about the other booking: never say it is "confirmed" (it may be PENDING, CONFIRMED or ACTIVE), and name no other booking, venue or time (the response carries none).
- The Late Confirmation stays AWAITING_CUSTOMER_CONFIRMATION: reload GET /customer/bookings/{bookingId}; never show it as cancelled, expired or confirmed.
- Decline stays available until the stored deadline: the countdown keeps running from lateConfirm.expiresAt against meta.serverTime, and Decline stays enabled until then (also while lateConfirm.paused). Accept may be retried before the deadline; a new Idempotency-Key is a new attempt, the same key replays the same 409.
- No notification: no local notification, push or inbox entry for this error, and no LATE_CONFIRM_SCHEDULE_CONFLICT expected from the server.
- Tests: a screen test - Accept answered with 409 LATE_CONFIRM_CUSTOMER_CONFLICT shows the localized message, keeps the AWAITING state and the countdown, and leaves Decline enabled; and a test that the message text contains no status name.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S3-C2 (E7-63) — Dashboard: attendance and Late Confirm

**What this task does:** Dashboard: check-in, late, no-show and Late Confirm buttons.

Lane: Clients · Depends on: S3-P0, S3-P1

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

Carry-forward from E7-39 review (do these too):
- Add the "Cancel booking" action on a CONFIRMED online booking (PO decision 6 Oct 2026 (E7-39 review: arch 09 §4.1 lists the route, but no task owned it)): POST /venue/bookings/{bookingId}/cancel-confirmed asks for the Owner PIN in the body, shows 423 REQUIRED/INVALID and 409 BOOKING_STATE_CHANGED from the code map; not offered on Manual bookings (they use cancel-manual).

Carry-forward from E7-54 (PO decisions 11 Oct 2026, do this too):
- Build against the Sprint 3 contract (E7-54, packages/api-client/SPRINT-3-CONTRACT.md): enable Check-in, Mark Present - Late and No-show from VenueBooking.attendance (checkInWindow, lateWindow, evidence instants) against meta.serverTime; a MANUAL booking (attendance null) shows no attendance actions. A Late Confirm refused for the customer shows only the neutral CUSTOMER_NOT_ELIGIBLE_FOR_LATE_CONFIRM message; venue-side errors have their own codes (INTERVAL_NOT_WITHIN_ONE_SESSION, AREA_INACTIVE, selling blocks, ...).

Carry-forward from the Sprint 2 review (PO decisions 12 Oct 2026, do these too):
- Missed / Expired requests page (PO decision 12 Oct 2026; FRS F-08, FR-BOK-21). The backend exists since E7-47; the Dashboard /missed-requests page is a title only, and E7-52 is done. Build it from GET /venue/requests/missed: each request with its room, start and the time left to start from meta.serverTime; Reject After Expiry (POST /venue/bookings/{bookingId}/reject-after-expiry, Idempotency-Key, no Owner PIN; 409 BOOKING_STATE_CHANGED and 422 DEADLINE_PASSED at or after startAt refresh the list); refresh on the stream's CHANGED signal and on the 30-second poll, as the Pending list does. Initiate Late Confirm (step 1) from this list. EN and AR.
- Venue notifications page (PO decision 12 Oct 2026; FR-NTF-09): from GET /venue/notifications and POST /venue/notifications/{notificationId}/read, which E7-55 adds to the contract (build against pnpm mock until it is merged). Newest first, cursor paging, mark as read, text from the code in the account's language (EN and AR); a booking notification opens that booking.
- Show customer.phone on the Pending and Missed request cards when the API returns it (FR-DIS-09: visible from PENDING; absent from the JSON once hidden). Today the Pending card shows only the username.

PO decision 15 Oct 2026 (E7-53 review, do this too):
- Add a 'Cancel walk-in' action to the booking detail of a MANUAL booking that is CONFIRMED or ACTIVE (FR-MAN-08, PCR-007): POST /venue/bookings/{bookingId}/cancel-manual (E7-48; no Owner PIN; Idempotency-Key required, kept on retry), with a confirmation step and the 409 BOOKING_STATE_CHANGED message. No task built it (E7-53 did not have it in scope). EN and AR.

Carry-forward from the E7-59/E7-60 review (PO decisions 19 and 20 Oct 2026, do these too):
- Check-in, Mark Present - Late and No-show can answer 409 BOOKING_STATE_CHANGED after the booking was cancelled at its start (a late start judged at startAt, E7-59/E7-60): reload the booking and show its new state; never show it as a no-show.

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

Carry-forward from E7-54 (PO decisions 11 Oct 2026, do this too):
- Build against the Sprint 3 contract (E7-54, packages/api-client/SPRINT-3-CONTRACT.md): phone change is two screens (POST /customer/profile/phone -> enter the code -> POST /customer/profile/phone/verify); password reset is by phone only (request -> code + new password -> 204, then back to login: every session has ended). Email change and email verification answer 501 until the PO decides email verification: leave those screens out and list them as not built.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S3-C4 (E7-116) — Dashboard: Overview home page

**What this task does:** Dashboard home page: today's and this month's revenue, today's bookings, pending requests, today's schedule and quick actions.

Lane: Clients · Depends on: S3-P0

```text
Read CLAUDE.md first. You are implementing task S3-C4 — "Dashboard: Overview home page" (Clients lane) of the E7GZ PS build plan.

Read only these docs for this task:
- Venue Dashboard prototype
- FRS §3.2 (Venue Dashboard)
- FR-VEN-34, FR-VEN-35
- arch 09 §4.1

Do:
1. Overview page from GET /venue/overview: today's revenue, this month's revenue, today's bookings, pending requests (links to the requests list of E7-52); today's schedule reusing the schedule component of E7-36; a quick manual booking button that opens the walk-in form of E7-53; a quick create tournament button only once tournaments exist (Sprint 6) — until then leave it out.

Done when (write these as tests first where possible):
- Every number comes from the API (no sums in the browser); money shown from the API string; RTL checked.

Requirements first, prototype for the look (PO decision 2 Oct 2026):
- The requirement docs in the read list decide WHAT this task must show and do. The prototype decides only HOW it looks. An approved PCR (PCR-001…PCR-005) overrides the FRS lines it amends.
- Before coding, list every screen, state and action the requirements need for this task (including empty, loading, error, not-allowed/ineligible and banned states) and compare it with the prototype.
- Anything the requirements need that the prototype does not show: build it anyway, in the same design: @e7gz/tokens, the components and layout patterns already used in this app and in the prototype. Never invent a new visual style.
- Anything the prototype shows that the requirements do not define (a status, rule, timer, button or message): do not build it; list it as a question for the PO.
- Status names, reason codes, timers and messages come only from docs/01-product and the backend's error codes, never from the prototype.
- Every screen you add works in English and Arabic (RTL, CSS logical properties only).
- In your summary, list the screens and states you built that are NOT in the prototype, so the reviewer can check them against the requirements.

Carry-forward from E7-54 (PO decisions 11 Oct 2026, do this too):
- todayBookings counts every booking of today, whatever its status - cancelled, rejected and no-show included (PO decision 11 Oct 2026, E7-54). Show the number as the API sends it; no filtering in the browser.

PO decision 3 Oct 2026 (E7-31), restated 12 Oct 2026 - which day is "today" (do this too):
- Today is the server's business date (BusinessDates.currentBusinessDate, E7-31): the Operating Day of the session still running, so after midnight inside a session it is still the previous calendar day. The Dashboard never works out today from the device clock or a Cairo calendar date: load today's schedule with GET /venue/availability WITHOUT a date and show the businessDate it returns; today's figures come from GET /venue/overview as sent.
- Test: the Overview's first schedule load sends no date, and the date shown is the businessDate from the response (e.g. a response with the previous calendar day's businessDate shows that day, whatever the device clock says).

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

Carry-forward from E7-65 (Sprint 4 contract, PO decisions 17 Oct 2026, do these too):
- Fill the stubs of the Sprint 4 contract (E7-65, packages/api-client/SPRINT-4-CONTRACT.md) and keep their shapes: loyalty/web CustomerLoyaltyController GET /customer/points and GET /customer/wallet (applied amounts only, the balance with every page); AdminLoyaltyController GET /admin/wallet, GET /admin/users/{userId}/wallet, GET /admin/points, GET /admin/users/{userId}/points (requested, applied and unrecovered amounts) and POST /admin/points/adjust {userId, amount, reason} (a non-zero amount; a deduction applies MIN(balance, requested) and records the shortfall, FR-PTS-03, FR-PTS-04). Every admin write requires the Idempotency-Key and a reason (D6). Replace this task's lines in backend/src/test/java/ps/e7gz/openapi/Sprint4StubHttpTest.java with your own tests.
- D4: also build GET /admin/users?q=&cursor=&limit= and GET /admin/users/{userId} (identity/web AdminUserController, FR-ADM-04): no other task owns them. Read standing and Verified Player through small read-only ports in trust.api (Engine reviews them), and points and wallet from loyalty.

Carry-forward from the E7-59/E7-60 review (PO decisions 19 and 20 Oct 2026, do these too):
- Reuse loyalty.api.BookingRewards (added by E7-59) for every booking outcome's Loyalty reconciliation (loyalty.reconcile_booking); don't add a second entry point.

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

Carry-forward from E7-65 (Sprint 4 contract, PO decisions 17 Oct 2026, do these too):
- Fill the stubs of the Sprint 4 contract (E7-65, packages/api-client/SPRINT-4-CONTRACT.md) and keep their shapes: GET /customer/referrals (my code and my referrals; the friend's identity is not shown), GET /admin/referrals?status=&q= and POST /admin/referrals/{referralId}/invalidate {reason} -> AdminReferralInvalidation (recorded as ADMIN_FRAUD; a REWARDED referral also gets the compensating transaction under the zero floor, FR-PTS-09; an INVALID referral -> 409 ADMIN_ACTION_NOT_ALLOWED, D5). Idempotency-Key and reason required (D6). Replace this task's lines in backend/src/test/java/ps/e7gz/openapi/Sprint4StubHttpTest.java with your own tests.

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

Carry-forward from E7-65 (Sprint 4 contract, PO decisions 17 Oct 2026, do these too):
- Fill the stubs of the Sprint 4 contract (E7-65, packages/api-client/SPRINT-4-CONTRACT.md): GET/PUT /admin/fees (config/web AdminFeeController, config.change_configuration) and the new GET/PUT /admin/points/settings (D3; loyalty/web AdminLoyaltyController, loyalty.configure) for attendedPoints, referralPoints and walletRefundsEnabled. Each value with its effectiveFrom; a change takes effect now, for later events only (FR-ADM-11). Omitted fields keep their value; a value equal to the current one changes nothing (skip it: change_configuration refuses an unchanged value); none given -> 400 VALIDATION_FAILED. The Idempotency-Key is required on both PUTs (D6). Replace this task's lines in backend/src/test/java/ps/e7gz/openapi/Sprint4StubHttpTest.java with your own tests.

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

Carry-forward from the E7-59/E7-60 review (PO decisions 19 and 20 Oct 2026, do these too):
- One writer of booking Trust outcomes: reuse trust.api.BookingOutcomes (E7-61's generalisation of E7-59's NoShowOutcomes); don't add a second writer.

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

Carry-forward from E7-65 (Sprint 4 contract, PO decisions 17 Oct 2026, do these too):
- Fill the four admin attendance stubs of the Sprint 4 contract (E7-65, packages/api-client/SPRINT-4-CONTRACT.md) (D1; attendance/web AdminAttendanceController): reverse-check-in, reverse-late-present, reverse-no-show {reason} and correct {correctedOutcome, reason} (NOT_APPLICABLE -> 400 on correctedOutcome), each returning AdminAttendance. A state that does not allow the action (no evidence, a manual booking, the wrong booking state) -> 409 ADMIN_ACTION_NOT_ALLOWED (D5). Idempotency-Key and reason required (D6). Replace this task's lines in backend/src/test/java/ps/e7gz/openapi/Sprint4StubHttpTest.java with your own tests.

Carry-forward from the E7-59/E7-60 review (PO decisions 19 and 20 Oct 2026, do these too):
- Build on E7-59's attendance evidence (attendance.attendance_record; reversed evidence is absent from VenueBooking.attendance) and on trust.api.BookingOutcomes.

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

Carry-forward from E7-65 (Sprint 4 contract, PO decisions 17 Oct 2026, do these too):
- Fill the stubs of the Sprint 4 contract (E7-65, packages/api-client/SPRINT-4-CONTRACT.md): GET /customer/account-standing (consecutiveNoShows, penaltyStage, currentThreshold, currentBan; E7-72 fills verifiedPlayer), POST /admin/users/{userId}/trust-adjustment {counterAfter, penaltyStageAfter, reason} (the ADMIN_TRUST_ADJUSTMENT v1 absolute payload; counterAfter at or above the stage's threshold -> 400 on counterAfter) and POST /admin/users/{userId}/bans/{banId}/lift {reason}, /shorten {endsAt, reason} and /correct {reason}. D2: /correct is kept; the database accepts CORRECTED only when corrected Trust history no longer supports the ban (trust.assert_ban_lifecycle_audit) - decide how the route meets that and ask the PO if unclear. A ban no longer ACTIVE, or a correction the history does not support -> 409 ADMIN_ACTION_NOT_ALLOWED (D5). Idempotency-Key and reason required (D6). Replace this task's lines in backend/src/test/java/ps/e7gz/openapi/Sprint4StubHttpTest.java with your own tests.

Carry-forward from the E7-59/E7-60 review (PO decisions 19 and 20 Oct 2026, do these too):
- Replace E7-59's pinned test theThirdConsecutiveNoShowNeedsE771sBanAndUntilThenRollsBackWhole with the real ban. E7-71 must be merged before E7-101: until then a third consecutive no-show is a 500 for the venue.
- A ban against a late-start booking (Spec v5 §48.6 does not cover it): a booking still CONFIRMED after its startAt because T4 is late is treated as started - it continues and is not cancelled with USER_BANNED (the database's cascade check only requires CONFIRMED bookings whose startAt is in the future). Test that the late T4 then still starts that booking (the ban guard does not refuse it).
- ADMIN_USER_BAN_ACTIVATED (§52.6) goes only to the admin alert inbox: a typed alert in admin.api.NewAdminAlert raised through AdminAlerts (E7-56), no per-admin notification. No migration: the alert kind is open text (ck_admin_alert_values). admin.api is Platform lane: list it in the PR for Sayed's review.
- Merge rule: whichever of E7-71 and E7-72 merges second first merges main in and goes green on CI.

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

Carry-forward from E7-65 (Sprint 4 contract, PO decisions 17 Oct 2026, do these too):
- Fill the stubs of the Sprint 4 contract (E7-65, packages/api-client/SPRINT-4-CONTRACT.md): the verifiedPlayer part of GET /customer/account-standing (qualification, completedBookings, requiredBookings, status), GET /admin/verified-players?status= and POST /admin/verified-players/{userId}/suspend and /revoke (only from VERIFIED) and /restore (from SUSPENDED or REVOKED: VERIFIED if qualified now, else NOT_VERIFIED), each {reason}; any other status -> 409 ADMIN_ACTION_NOT_ALLOWED (D5). Idempotency-Key and reason required (D6). Replace this task's lines in backend/src/test/java/ps/e7gz/openapi/Sprint4StubHttpTest.java with your own tests.

Carry-forward from the E7-59/E7-60 review (PO decisions 19 and 20 Oct 2026, do these too):
- The initial VERIFIED transition unblocks E7-61's 10th attended completion (trust.assert_user_integrity): replace E7-61's pinned test with it. E7-72 must be merged before E7-101.
- ADMIN_VERIFIED_PLAYER_REVOKED (§52.6) goes only to the admin alert inbox, as in E7-71: a typed alert in admin.api.NewAdminAlert, no migration, listed in the PR for Sayed's review.
- Merge rule: whichever of E7-71 and E7-72 merges second first merges main in and goes green on CI.

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

### S4-E6 (E7-118) — Unanswered booking at start time (T6, FR-BOK-23)

**What this task does:** A booking the venue never answered (still VENUE_RESPONSE_EXPIRED) is cancelled at its start time with the right reason.

Lane: Engine · Depends on: S3-E3, S3-P1

```text
Read CLAUDE.md first. You are implementing task S4-E6 — "Unanswered booking at start time (T6, FR-BOK-23)" (Engine lane) of the E7GZ PS build plan.

Read only these docs for this task:
- FRS FR-BOK-23
- arch 08 §1 (T6), §2.2
- Spec v5 §24.3

Do:
1. Schedule T6 (VENUE_RESPONSE_EXPIRED resolution) at booking.start_at when a booking becomes VENUE_RESPONSE_EXPIRED (E7-47's T1 expiry); the job supersedes itself when the booking has left that state (Late Confirm, reject after expiry, customer cancel).
2. At startAt, a booking still VENUE_RESPONSE_EXPIRED becomes CANCELLED with exactly one reason by FR-BOK-23's priority, judged at startAt (arch 08 §2.2): RESOURCE_OWNER_REVIEW_REQUIRED > RESOURCE_EMERGENCY_OUTAGE > VENUE_EMERGENCY_CLOSURE > VENUE_ACCOUNT_DISABLED > VENUE_DID_NOT_RESPOND_IN_TIME (the last only when no overriding cause exists); its full refund classification and BookingCancelled, once.

Done when (write these as tests first where possible):
- A booking still VENUE_RESPONSE_EXPIRED at startAt is CANCELLED with the right reason; one second before startAt nothing happens; a job fired late gives the on-time outcome.

PO decisions 19 and 20 Oct 2026 (E7-59/E7-60 review, do these too):
- D1, D3: a Venue Closure, an Opening Hours change, or a room DISABLED or ARCHIVED at that startAt is not a reason: the frozen list wins, so it is VENUE_DID_NOT_RESPOND_IN_TIME (no migration).
- D2: the customer gets one notification, the generic BOOKING_AUTO_CANCELLED (no specific code exists), through E7-55's consumer, even though they were told at expiry that the venue didn't respond; one exactly-once test.
- The boundary test is already written and switched off: backend/src/test/java/ps/e7gz/support/boundary/pending/BookingStartBoundaryTest.java (T6 only, after E7-60). Move it next to this task's code, replace fail(...) using the boundary pack ps.e7gz.support.boundary, and remove @Disabled. Reuse E7-60's start-time reads (operations.api.OperationalStateAt) for the causes; don't copy them.

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

Carry-forward from E7-65 (Sprint 4 contract, PO decisions 17 Oct 2026, do these too):
- Build against the Sprint 4 contract (E7-65, packages/api-client/SPRINT-4-CONTRACT.md): the standing has exactly one currentBan or null, and its remaining time is currentBan.endsAt against meta.serverTime; points show applied amounts; referrals show my code and my referrals' status, not who signed up; the wallet is read-only and shows 0.00 clearly.

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

Carry-forward from E7-65 (Sprint 4 contract, PO decisions 17 Oct 2026, do these too):
- Build against the Sprint 4 contract (E7-65, packages/api-client/SPRINT-4-CONTRACT.md): every admin write asks for a reason (required, 1-500 characters) and sends an Idempotency-Key; 409 ADMIN_ACTION_NOT_ALLOWED means "refresh and look again"; fees and points settings show effectiveFrom; the user page reads GET /admin/users/{userId}; the attendance correction asks for the chosen outcome; there is no wallet adjustment.

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

Carry-forward from E7-37 (PO decision 4 Oct 2026, do these too):
- Add the stub for GET /admin/venue-accounts in venue/web/AdminVenueAccountController (FR-ADM-03: the Platform Admin sees every venue account in one list), answering 501 until E7-78: cursor pagination like GET /admin/venues (PageView), ordered by venue name then id, optional filter accountStatus (ENABLED | DISABLED). Each row is AdminVenueAccount (id, venueId, loginIdentifier, accountStatus) plus venueName and languagePreference; never a password, hash or Owner PIN. Regenerate the client so E7-86 can build against pnpm mock.

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

Carry-forward from E7-24 (PO decision 3 Oct 2026; do these too):
- POST /admin/venue-accounts/{id}/disable and /enable already exist (VenueAccounts.setStatus -> VenueAccountCommands.SetAccountStatus): they change iam.venue_account.account_status with the admin's reason, audit VENUE_ACCOUNT_DISABLED / VENUE_ACCOUNT_ENABLED and block the Dashboard at once. Add the O10 consequences inside that same command and transaction: PENDING -> VENUE_RESPONSE_EXPIRED with the account-disable event, AWAITING cancelled under VENUE_ACCOUNT_DISABLED, PENDING_VENUE_APPROVAL tournaments cancelled (the database refuses the disable until they are), and the iam.venue_account_restriction_interval rows (open on disable, close on enable). Do not add a second disable endpoint.

Carry-forward from E7-37 (PO decision 4 Oct 2026, do these too):
- Implement GET /admin/venue-accounts (stub from E7-77): read-only, one row per iam.venue_account with its venue's name, ordered by venue name then id, cursor pagination like GET /admin/venues, optional accountStatus filter. Tests: accounts of every venue are listed; the filter works; paging returns each account exactly once; the JSON never contains a password, hash or Owner PIN; venue and customer tokens are refused.

Carry-forward from the Sprint 2 review (PO decisions 12 Oct 2026, do these too):
- Customer copy must tell the causes apart (FR-NTF-06): an ordinary expiry is 'Venue did not respond in time', but a PENDING expired by a venue account disable (FR-VEN-09) is 'Venue is temporarily unable to confirm this booking'. No contract change is needed: on that PENDING -> VENUE_RESPONSE_EXPIRED transition write the booking history event VENUE_RESPONSE_INTERRUPTED_BY_ACCOUNT_DISABLE (already in BookingEventType and returned in CustomerBookingDetail.history). Test: the customer booking detail of a booking expired by a disable carries that event; an ordinary expiry does not. E7-87 shows the text.

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

Carry-forward from E7-43 (PO decision 10 Oct 2026, do this too):
- Add the English push and email copy (title/body, email subject/body) for every PUSH or EMAIL notification code this task emits to the notification template catalogue E7-43 built (ADR-021: keyed by code and language, rendered in the notification worker; never stored or sent by the API). The PO reviews the wording in the PR.

Carry-forward from E7-44 (PO decision 10 Oct 2026, do this too):
- Venue notifications are Web Push in the venue account's language (FR-VEN-40), so for every VENUE notification code this task emits add both the English and the Arabic push copy (title/body) to the template catalogue (templates_en / templates_ar). Until a code has its copy, its venue push is closed TEMPLATE_MISSING and only the in-Dashboard list shows it. The PO reviews both wordings in the PR.

Carry-forward from the Sprint 2 review (PO decisions 12 Oct 2026, do these too):
- VENUE_BOOKING_AUTO_CANCELLED split (PO decision 12 Oct 2026): E7-55 already sends it for an Accept stopped by an operational cause (FR-BOK-16) and the start-time revalidation cancellations (E7-60). This task adds it for the emergency, owner-review and closure cascades, through the same code path, once per cancelled booking - never a second notification for a cancellation E7-55 already covers. Test: each cascade cancellation gives exactly one VENUE_BOOKING_AUTO_CANCELLED.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S5-E1 (E7-81) — Maintenance and closures

**What this task does:** Maintenance windows and venue closures block the calendar.

Lane: Engine · Depends on: S1-E2, S3-E5

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

Carry-forward from the E7-30 review (do these too):
- maintenance_window and venue_closure have no 'deleted' column yet. If maintenance or closure delete is built as a soft delete (a new column), the availability predicate's reads must skip deleted rows: OperationalOverlaps.maintenanceOverlaps and closureOverlaps (operations/internal/JdbcOperationalOverlaps, checks 6-7 of isFree), plus a test that a deleted window no longer blocks.
- Carry-forward from E7-113: the closure cascade cancels affected Bookings the same way an opening-hours change does (FRS §2.4 "Hours/Closure save cancelling protected bookings"). Reuse the cancellation E7-113 built (the Booking port, stopping Late Confirm, the outbox notification, the Owner Password only for a CONFIRMED Online or AWAITING booking) with reason VENUE_CLOSURE; do not build a second cancellation path.

Carry-forward from the Sprint 2 review (PO decisions 12 Oct 2026, do these too):
- Publish MaintenanceCreated, MaintenanceEdited, MaintenanceDeleted and ClosureCreated in the same command transaction as each change (arch 08 section 3.2; IntegrationEvents.INVENTORY_AFFECTING already lists them and the Nearest Availability consumer takes them, but nothing writes them yet). Add the constants to IntegrationEvents; the cancellations publish BookingCancelled, which the venue live channel already signals. Test: each event refreshes the venue's Nearest Availability rows without waiting for the one-minute pass.

Carry-forward from the E7-59/E7-60 review (PO decisions 19 and 20 Oct 2026, do these too):
- Keep a Venue Closure from overlapping a CONFIRMED booking: E7-60's start check (closurePreventsService) cancels for any overlapping closure, even one created after startAt.

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

Carry-forward from the Sprint 2 review (PO decisions 12 Oct 2026, do these too):
- Publish EmergencyDeclared, EmergencyExtended, EmergencyEnded and EmergencyConverted in the same command transaction as each change (arch 08 section 3.2; IntegrationEvents.INVENTORY_AFFECTING already lists them and the Nearest Availability consumer takes them, but nothing writes them yet). Add the constants to IntegrationEvents. Test: each event refreshes the venue's Nearest Availability rows without waiting for the one-minute pass.

Carry-forward from the E7-59/E7-60 review (PO decisions 19 and 20 Oct 2026, do these too):
- When an Emergency is declared, a booking still CONFIRMED after its startAt because T4 was late must be handled: its valid start at startAt, then the interruption at the Emergency's own time (Spec v5 §31.7). Today E7-60's T4 refuses such a start and writes nothing (Undetermined).

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

Carry-forward from E7-45 (PO decision 10 Oct 2026, do this too):
- The boundary tests of this task are already written and switched off (@Disabled("E7-83: ...")): backend/src/test/java/ps/e7gz/support/boundary/pending/TimerPauseResumeBoundaryTest.java. Move the class next to this task's code, replace each fail(...) with the steps its comment names, using the boundary pack ps.e7gz.support.boundary (Boundary, CommandInstants, Deadlines, DueJobs, BoundaryAssertions; see its package-info), and remove @Disabled. The task is done only when they pass. The pack and these tests come with E7-45: merge main once E7-45 is merged.

Carry-forward from E7-58 (PO decision 12 Oct 2026, do this too):
- The customer may Decline a Late Confirmation at any moment of a pause (FR-OPS-41), but the database accepts DECLINED only while responded_at < late_confirmation.expires_at (ck_late_confirmation_response_shape). E7-58 does not handle the pause: keep expires_at valid throughout a pause (the persisted deadline may move, never beyond the booking's startAt) or propose a migration to the PO. Also re-arm the T2/T3 jobs E7-58 leaves deferred while paused. Test: a Decline after the original deadline but during a pause succeeds under CUSTOMER_DECLINED_LATE_CONFIRM.

Carry-forward from the Sprint 2 review (PO decisions 12 Oct 2026, do these too):
- Re-arm a FAILED T1 (E7-47, 'reported to the PO'): when T1 fires while its response timer is paused, VenueAnswers.expire throws, so the job backs off and can end FAILED. Resume must re-arm the booking's VENUE_RESPONSE_EXPIRY job with the remaining time (FR-OPS-36) whether it is PAUSED or FAILED. Test: pause, T1 fires during the pause and ends FAILED, resume, and the booking expires once at the moved deadline.
- PO decision 12 Oct 2026: an Accept on a pause still waiting after its emergency ended (FR-OPS-39) keeps answering VENUE_SELLING_BLOCK_ACTIVE, as VenueAnswers.pausedRefusal does today. Keep that when you change pause and resume.

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

Carry-forward from the E7-59/E7-60 review (PO decisions 19 and 20 Oct 2026, do these too):
- When an Owner Review starts, a booking still CONFIRMED after its startAt because T4 was late must be handled: its valid start at startAt, then the interruption at the review's own time (Spec v5 §31.7). Today E7-60's T4 refuses such a start and writes nothing (Undetermined).

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

Carry-forward from E7-37 (PO decision 4 Oct 2026, do these too):
- Venue accounts page (/venue-accounts, replaces the E7-37 placeholder): list every venue account from GET /admin/venue-accounts (venue name, login, status, language) with a status filter, 'Load more' paging (cursor) and empty, loading and error states. A row opens that venue's page. Disable/enable from this page uses the same required-reason dialog as the venue page.

Constraints: stay inside this task's module(s); do not edit docs/ or V1__baseline.sql; follow CLAUDE.md §5.
Finish by running the checks in CLAUDE.md §4, then give me a short summary, the files changed, and any question for the Product Owner. Do not open the PR — I will review first.
```

### S5-C3 (E7-87) — Customer app: operational states

**What this task does:** App: show emergency cancellations, pauses and refunds.

Lane: Clients · Depends on: S5-P0, S5-P1

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

Carry-forward from the Sprint 2 review (PO decisions 12 Oct 2026, do these too):
- Show the second expiry text of FR-NTF-06, 'Venue is temporarily unable to confirm this booking', for a VENUE_RESPONSE_EXPIRED booking whose CustomerBookingDetail.history has the VENUE_RESPONSE_INTERRUPTED_BY_ACCOUNT_DISABLE event (E7-78 writes it). Today My Bookings (apps/customer/src/features/my-bookings/bookingText.ts) shows 'Venue did not respond in time' for every VENUE_RESPONSE_EXPIRED.

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

Carry-forward from E7-43 (PO decision 10 Oct 2026, do this too):
- Add the English push and email copy (title/body, email subject/body) for every PUSH or EMAIL notification code this task emits to the notification template catalogue E7-43 built (ADR-021: keyed by code and language, rendered in the notification worker; never stored or sent by the API). The PO reviews the wording in the PR.

Carry-forward from E7-44 (PO decision 10 Oct 2026, do this too):
- Venue notifications are Web Push in the venue account's language (FR-VEN-40), so for every VENUE notification code this task emits add both the English and the Arabic push copy (title/body) to the template catalogue (templates_en / templates_ar). Until a code has its copy, its venue push is closed TEMPLATE_MISSING and only the in-Dashboard list shows it. The PO reviews both wordings in the PR.

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

Carry-forward from E7-39 review (do these too):
- Contract for the plan gap (PO decision 6 Oct 2026 (E7-39 review: arch 09 §4.1 lists the route, but no task owned it)): add the stub GET /venue/revenue?from=&to=&breakdown=activity (business dates, inclusive) -> totals for online, manual and tournament revenue per business date with an optional activity breakdown; no payment-method fields (FR-VEN-36). Used by E7-104 and S7-C6.

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

Carry-forward from the E7-59/E7-60 review (PO decisions 19 and 20 Oct 2026, do these too):
- An admin "restart a FAILED job" action using ScheduledJobs.rearm (technical.job_rearm), with a mandatory reason and an audit row, so a FAILED BOOKING_START or BOOKING_COMPLETE can be recovered once its data is fixed (PO decision 19 Oct 2026).

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

Carry-forward from E7-23 (PO decisions 3 Oct 2026, do these too):
- Create the venue-photo buckets for staging and production (private, versioning on) and their access keys in the secret store.
- Put venue photos behind the CDN (arch 12 §2); signed view links stay valid 1 hour.
- Configure object storage per environment from the secret store: E7GZ_S3_ENDPOINT, E7GZ_S3_PUBLIC_ENDPOINT (the address browsers and apps reach, used in signed links), E7GZ_S3_REGION, E7GZ_S3_BUCKET, E7GZ_S3_ACCESS_KEY, E7GZ_S3_SECRET_KEY, and E7GZ_S3_CREATE_BUCKET=false; set the bucket CORS rule to the Venue Dashboard and Platform Admin origins (GET, PUT, HEAD). Run at least one worker, which makes the photo copies.

Carry-forward from the migration safety rule (PO decision 4 Oct 2026, do these too):
- Every release that carries a migration: take a backup (or PITR mark) first; restore a copy of production onto staging and run the release's migrations on it; run `flyway info` and `flyway validate` before `flyway migrate`; smoke-test the API and worker after. Production migrates only after all of this passed on the copy.
- The release pipeline fails if `flyway validate` reports a changed, renamed or missing versioned migration, and never runs `flyway repair` or edits flyway_schema_history automatically.
- If a production migration fails: restore the backup, keep the old release running, and ship the fix as a NEW migration (CLAUDE.md section 5, Migration safety). Write this into the release runbook and rehearse it once in the restore drill.

Carry-forward from E7-41 (PO decisions 9 Oct 2026, do these too):
- Put the OTP payload encryption key (the setting E7-41 added, e.g. E7GZ_OTP_PAYLOAD_KEY) in the secret store for staging and production; never reuse the test key.
- There is no start-up guard against the 'log' SMS provider (PO decision 9 Oct 2026): the release checklist verifies E7GZ_SMS_PROVIDER=sms-misr in staging and production before go-live, and a smoke test sends one real OTP.

Carry-forward from E7-42 (PO decision 10 Oct 2026, do this too):
- Put the idempotency fingerprint key (E7GZ_IDEMPOTENCY_FINGERPRINT_KEY, the setting E7-42 added: Base64 of 32 random bytes) in the secret store for staging and production, the same value on every replica of an environment; never reuse the test key. Neither the api nor the worker starts without it. Changing it later only makes a retry across the change of a request with a secret field (Owner Password) a 422.

Carry-forward from E7-43 (PO decision 10 Oct 2026, do these too):
- Before deployment, ask the PO for the provider accounts and set each one up together, step by step: the Apple Developer Program account, the Firebase project, and the SMS Misr account (the PO opens them; nothing is created without him). Until then everything runs with the dev adapters (fake push, Mailpit, log SMS).
- iPhone push (arch 01 decision A7): in the Apple Developer account create an APNs auth key (.p8) and enable Push Notifications for the customer app's bundle id; in the Firebase project register the iOS and Android apps, upload the APNs key, and create a service account for the FCM HTTP v1 API. Put the service-account credentials (the setting E7-43 added) in the secret store per environment, separate staging and production Firebase projects (arch 12: FCM test project for staging), never in the repo.
- Email: an SMTP provider account for staging and production; its host, port, user and password (the settings E7-43 added) in the secret store.
- Release smoke test: one real push reaches an iPhone and an Android phone through FCM, and one real email arrives.

Carry-forward from E7-44 (PO decisions 10 Oct 2026, do these too):
- Venue Web Push: generate one VAPID key pair per environment (staging and production, never the dev key) and put E7GZ_VAPID_PUBLIC_KEY and E7GZ_VAPID_PRIVATE_KEY in the secret store, the same pair on every replica; set the Web Push provider to 'vapid'. Changing the pair later makes every venue browser subscribe again.
- VAPID subject: ask the PO for the contact address (a team address such as support@..., created before launch) and set E7GZ_VAPID_SUBJECT to mailto:<it>. Release smoke test: one real Web Push reaches a closed Venue Dashboard in Chrome.

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

Lane: Engine · Depends on: S3-E4, S3-E7

```text
Read CLAUDE.md first. You are implementing task S7-E3 — "Reports FR-AUD-11/12 and metrics" (Engine lane) of the E7GZ PS build plan.

Read only these docs for this task:
- arch 13 (FR-AUD-11, FR-AUD-12)
- PRD §4.2

Do:
1. Terminal-reason distribution (customer/venue/operational split) and attendance mix + check-in capture rate per venue; admin API.

Done when (write these as tests first where possible):
- Numbers match a hand-computed seeded dataset.

Carry-forward from E7-39 review (do these too):
- Also implement GET /venue/revenue (PO decision 6 Oct 2026 (E7-39 review: arch 09 §4.1 lists the route, but no task owned it); FR-VEN-36): online + manual + tournament revenue aggregated on businessDate with an optional activity breakdown, reusing the revenue read built in S3-E7 (Overview); only COMPLETED bookings count (PO decision 6 Oct 2026). No payment method, cash/card split or reconciliation fields. Test against a hand-computed seeded dataset.

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

### S7-C6 (E7-117) — Dashboard: revenue screen

**What this task does:** Dashboard revenue screen: online, manual and tournament revenue by business date, with an optional activity breakdown.

Lane: Clients · Depends on: S7-P0

```text
Read CLAUDE.md first. You are implementing task S7-C6 — "Dashboard: revenue screen" (Clients lane) of the E7GZ PS build plan.

Read only these docs for this task:
- Venue Dashboard prototype
- FRS §3.2 (Venue Dashboard)
- FR-VEN-36
- arch 09 §4.1 (revenue payload)

Do:
1. Revenue page from GET /venue/revenue?from=&to=&breakdown=activity: totals for online, manual and tournament revenue per business date, optional activity breakdown, date-range filter. Never show a payment method, cash/card split or reconciliation (FR-VEN-36).

Done when (write these as tests first where possible):
- Every number comes from the API; date ranges are business dates; RTL checked.

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
