# E7GZ PS — Developer Guide

How the three of us build E7GZ PS: who owns what, where code goes, and the exact steps for every task. Read this once, then keep it open.

| File | Use it for |
|---|---|
| `CLAUDE.md` | rules Claude Code follows on every task |
| `BUILD-PLAN.md` | the sprints and the full prompt of every task |
| `DEVELOPER-GUIDE.md` | this file — ownership, folders, workflow |
| Jira project **E7** | status of every task (E7-1…E7-8 = sprints, E7-9…E7-110 = tasks) |

---

## 1. Team and lanes

| Lane | Owner | Builds | Reviews |
|---|---|---|---|
| **Engine** | Amr (Claude Code) | inventory, availability, booking, Late Confirm, attendance, trust, tournaments (backend) | Sayed's backend PRs |
| **Platform** | Sayed (Claude Code · also Product Owner) | foundation, identity, venue setup, pricing, scheduler, outbox, notifications, loyalty, config, operations support, admin APIs, deployment, every `Sx-P0` API contract | Amr's PRs |
| **Clients** | Abdallah | Customer app, Venue Dashboard, Platform Admin | every `Sx-P0` API contract (checks it gives the screens what they need); his PRs are reviewed by Sayed |

Everyone works at the same time. What makes that possible:

1. **Separate folders.** Each lane writes only in its own folders (§3). Fewer shared files = almost no merge conflicts.
2. **The database already exists.** Backend tasks seed the rows they need with SQL; nobody waits for another lane's API to test.
3. **API contract first.** Each sprint starts with an `Sx-P0` task (Sayed) that adds every endpoint of the sprint as a stub (controller + DTOs, returns 501) by **day 2**. Sayed and Amr fill the stubs during the sprint. Abdallah builds the screens **one sprint later**, on the real finished API (Sprint 8 is his catch-up sprint).
4. **Tests first.** Each task's *Done when* list becomes tests before the code.

---

## 2. Folder structure

Created once by **E7-9 (project skeleton)**. Every folder contains a `package-info.java` (backend) or `index.ts` (apps) naming the tasks that fill it — if you don't know where something goes, open that file.

```text
e7gz-ps/
├── CLAUDE.md                    rules for Claude Code (read first)
├── BUILD-PLAN.md                sprints, tasks, prompts
├── DEVELOPER-GUIDE.md           this guide
├── README.md                    how to run the project
├── .gitignore  .gitattributes  .editorconfig
├── .github/
│   ├── workflows/ci.yml         placeholder (E7-11)
│   ├── pull_request_template.md
│   └── CODEOWNERS               lane owners review their folders
├── docker-compose.yml           placeholder (E7-10)
├── package.json                 pnpm root (workspaces)
├── pnpm-workspace.yaml
├── docs/                        product, architecture, design, ERD (already there)
├── backend/                     Java 21 · Spring Boot 3 · Gradle (Kotlin DSL)
│   ├── build.gradle.kts  settings.gradle.kts  gradlew + gradle/wrapper/
│   └── src/
│       ├── main/java/ps/e7gz/
│       │   ├── E7gzApplication.java
│       │   ├── common/          shared kernel — no business rules
│       │   │   ├── clock/  money/  error/  web/  security/  security/owner/
│       │   │   ├── command/     CommandRunner (begin_command, retry)
│       │   │   ├── codes/       closed value sets as enums
│       │   │   └── observability/
│       │   ├── identity/        ┐
│       │   ├── geography/       │
│       │   ├── venue/           │
│       │   ├── resource/        │
│       │   ├── pricing/         │ every module has:
│       │   ├── inventory/       │   api/        interfaces + DTOs other modules may use
│       │   │   ├── ledger/      │   internal/   domain logic + persistence (private)
│       │   │   ├── availability/│   web/        REST controllers + request/response DTOs
│       │   │   └── sweep/       │   package-info.java  owned tables + owner tasks
│       │   ├── operations/      │
│       │   ├── booking/         │
│       │   │   └── lateconfirm/ │
│       │   ├── attendance/      │
│       │   ├── tournament/      │
│       │   ├── trust/           │
│       │   ├── loyalty/         │
│       │   ├── review/          │
│       │   ├── notification/    │
│       │   ├── config/          │
│       │   ├── audit/           │
│       │   ├── admin/           │
│       │   ├── scheduling/      │ (technical)
│       │   └── messaging/       ┘ (technical)
│       ├── main/resources/
│       │   ├── application.yml  application-api.yml  application-worker.yml
│       │   └── db/migration/    V1__baseline.sql + R__security_baseline.sql (E7-10)
│       ├── seed/                dev + perf seed (E7-28)
│       └── test/java/ps/e7gz/
│           ├── architecture/    ArchUnit rules
│           ├── support/         Testcontainers base, fixtures/, boundary/
│           ├── concurrency/  faultinjection/  tournament/
│           └── <module>/        tests mirror main packages
├── apps/
│   ├── customer/                React Native + TS (Expo)
│   │   └── src/ app/ components/ api/ i18n/ theme/
│   │       features/ auth/ discovery/ booking/ my-bookings/ late-confirm/ profile/
│   │                 rewards/ tournaments/ reviews/ notifications/
│   ├── venue-dashboard/         React + TS + Vite · EN/AR
│   │   ├── public/              service worker, alert sound
│   │   └── src/ app/ components/ api/ i18n/ styles/
│   │       features/ auth/ requests/ today/ walk-in/ schedule/ attendance/ setup/
│   │                 operations/ tournaments/ reviews/ settings/
│   └── admin-web/               React + TS + Vite · EN
│       └── src/ app/ components/ api/
│           features/ auth/ dashboard/ areas/ venues/ venue-accounts/ users/ trust/
│                     bans/ verified-players/ points/ configuration/ owner-review/
│                     emergencies/ alerts/ tournaments/ reviews/ account-deletion/
│                     reports/ audit/ notifications/
├── packages/
│   ├── tokens/                  design tokens (E7-18)
│   └── api-client/              openapi.json + generated TS client (E7-11)
├── infra/                       deployment, monitoring (E7-100, E7-101)
└── tools/
    ├── db-tests/                ported DB contract tests (E7-10)
    └── load-tests/              (E7-105)
```

**Backend rule of thumb:** `web/` = HTTP only · `internal/` = rules + SQL · `api/` = the only thing other modules may call. ArchUnit fails the build if you break this.

---

## 3. Who owns which folder

| Folder | Owner (must review changes) |
|---|---|
| `backend/…/common/, identity/, geography/, venue/, resource/, pricing/, scheduling/, messaging/, notification/, loyalty/, config/, admin/, audit/*` | Sayed |
| `backend/…/inventory/, booking/, attendance/, trust/, tournament/, operations/, review/` | Amr |
| `apps/, packages/tokens/` | Abdallah |
| `packages/api-client/openapi.json (generated), backend/…/*/web/ stubs` | Sayed (author) + Abdallah (consumer) |
| `backend/build.gradle.kts, root package.json, .github/, infra/, docker-compose.yml` | Sayed |
| `docs/` | Sayed (Product Owner) — code tasks never edit docs |

\* `audit/` is written by Amr in E7-17 but reviewed by Sayed afterwards. Need a change in someone else's folder? Say it in the daily sync, then either they do it or they review your PR.

### Every task and where its code goes

#### Sprint 0 — Foundations · 27 Sep – 8 Oct 2026

*Everything runs locally and in CI; DB baseline applied; command runner, errors, audit, identity; three app shells.*

| Jira | Owner | Task | What it does | Needs first | Writes in |
|---|---|---|---|---|---|
| E7-9 | Sayed | **Project skeleton and folder structure** | Create every folder and empty placeholder file, make the backend build, push to GitHub. Everyone pulls this before starting. | — | (repo root files)<br>`…/E7gzApplication.java`<br>`backend/src/test/java/ps/e7gz/architecture/` |
| E7-10 | Sayed | **Local environment and database baseline** | Run the database, file storage and email locally with Docker, and load our finished database into the app. | E7-9 | `docker-compose.yml`<br>`backend/src/main/resources/db/migration/`<br>`backend/src/main/resources/application*.yml`<br>`backend/src/test/java/ps/e7gz/support/`<br>`tools/db-tests/` |
| E7-11 | Sayed | **CI pipeline and OpenAPI → TypeScript client** | Automatic checks on every pull request, and a generated API client the three apps use. | E7-9 | `.github/workflows/`<br>`packages/api-client/`<br>`package.json (scripts api:gen, mock)` |
| E7-12 | Sayed | **Cross-cutting: clock, money, correlation ID, error envelope** | Shared basics: one clock for time, one money type, a request ID in every log, and one error format. | E7-9 | `…/common/clock/`<br>`…/common/money/`<br>`…/common/error/`<br>`…/common/web/` |
| E7-13 | Sayed | **Customer registration and phone OTP** | Customers sign up and verify their phone with an SMS code. | E7-10, E7-12 | `…/identity/` |
| E7-14 | Sayed | **Login, JWT and refresh rotation for three audiences** | Log in for customers, venues and admins; keep users signed in safely. | E7-13 | `…/identity/`<br>`…/common/security/` |
| E7-15 | Amr | **Command runner (READ COMMITTED, lock set, retry)** | The one safe way every "action" writes to the database (locks, retries). | E7-10 | `…/common/command/` |
| E7-16 | Amr | **Closed value sets as Java enums, checked against the DB** | All fixed lists (statuses, reasons, codes) as Java enums that must match the database. | E7-10 | `…/common/codes/` |
| E7-17 | Amr | **Audit writer** | Record who did what, when and why, for every important action. | E7-12, E7-15 | `…/audit/` |
| E7-18 | Abdallah | **Design tokens package** | Pull colours, fonts and spacing from the designs into one shared package. | E7-9 | `packages/tokens/` |
| E7-19 | Abdallah | **Venue Dashboard and Admin web shells** | Empty but running Venue Dashboard (English + Arabic) and Admin website. | E7-18, E7-11 | `apps/venue-dashboard/src/app/`<br>`apps/venue-dashboard/src/i18n/`<br>`apps/admin-web/src/app/` |
| E7-20 | Abdallah | **Customer app shell** | Empty but running customer mobile app with its tabs. | E7-18, E7-11 | `apps/customer/src/app/`<br>`apps/customer/src/i18n/` |

#### Sprint 1 — Venues and the room calendar · 11 – 22 Oct 2026

*Venue setup, operating sessions, resources, pricing; reservation ledger, isFree, Nearest Availability, discovery. Roadmap Phase 0 rest + Phase 1.*

| Jira | Owner | Task | What it does | Needs first | Writes in |
|---|---|---|---|---|---|
| E7-21 | Abdallah | **Auth screens on all three surfaces** | Sign-up, OTP and login screens on the app, dashboard and admin. | E7-19, E7-20 | `apps/customer/src/features/auth/`<br>`apps/venue-dashboard/src/features/auth/`<br>`apps/admin-web/src/features/auth/` |
| E7-22 | Sayed | **Sprint 1 API contract** | Add all Sprint 1 API endpoints as empty stubs by day 2 so screens can start. | E7-11 | all */web/ packages touched in the sprint (stubs)<br>`packages/api-client/openapi.json` |
| E7-23 | Sayed | **Areas, venues and marketplace status** | Admin creates areas and venues and moves a venue live, paused or closed. | E7-22 | `…/geography/`<br>`…/venue/` |
| E7-24 | Sayed | **Venue account and Owner Password** | Venue login account and the Owner Password that protects sensitive actions. | E7-23, E7-14 | `…/venue/ (venue account)`<br>`…/common/security/owner/` |
| E7-25 | Sayed | **Opening hours and operating sessions (DST)** | Weekly opening hours turned into real open sessions, correct across clock changes. | E7-23 | `…/venue/ (opening hours, sessions)` |
| E7-26 | Sayed | **Resources, activities and state changes** | Rooms/consoles of a venue, their status, and a history of every change. | E7-23 | `…/resource/` |
| E7-27 | Sayed | **Pricing and rounding** | Prices per hour and the exact rounding rule. | E7-26 | `…/pricing/` |
| E7-28 | Sayed | **Seed data generator** | One command that fills the database with realistic test data. | E7-25, E7-26, E7-27 | `backend/src/seed/`<br>`backend/src/test/java/ps/e7gz/support/fixtures/` |
| E7-29 | Amr | **Reservation ledger** | The room calendar: hold a time slot and make double-booking impossible. | E7-15 | `…/inventory/ledger/` |
| E7-30 | Amr | **isFree: the eight ordered checks** | The single "is this room free?" check with its 8 steps in order. | E7-29 | `…/inventory/availability/` |
| E7-31 | Amr | **Business date** | Which business day a booking belongs to (even after midnight). | E7-29 | `…/inventory/availability/ (BusinessDate)` |
| E7-32 | Amr | **Concurrency harness and invariant sweep** | A test that fires many bookings at once, and a nightly health check. | E7-29 | `backend/src/test/java/ps/e7gz/concurrency/`<br>`…/inventory/sweep/` |
| E7-33 | Amr | **Nearest Availability projection** | Pre-compute "next available time" for every venue so lists are fast. | E7-30 | `…/inventory/availability/ (projection)` |
| E7-34 | Amr | **Discovery API** | The venue list: nearest first, filters, next available time. | E7-33, E7-22 | `…/inventory/availability/web/ (discovery)` |
| E7-112 | Amr | **Venue availability schedule API** | The backend for the Dashboard schedule: each room's bookings, holds, maintenance and closures for one business day. | E7-30, E7-31, E7-25, E7-26 | `…/inventory/availability/ (schedule query + web)` |

#### Sprint 2 — Book a room end to end · 25 Oct – 5 Nov 2026

*Online and walk-in booking, venue accept/reject, deadlines, outbox, notifications, SSE + Web Push. Journeys J1, J8. Roadmap Phase 2.*

| Jira | Owner | Task | What it does | Needs first | Writes in |
|---|---|---|---|---|---|
| E7-35 | Abdallah | **Dashboard: venue setup screens** | Dashboard screens to set up venue profile, hours, rooms and prices. | E7-22 | `apps/venue-dashboard/src/features/setup/` |
| E7-36 | Abdallah | **Dashboard: availability schedule** | Dashboard grid showing each room's bookings through the day. | E7-22 | `apps/venue-dashboard/src/features/schedule/` |
| E7-37 | Abdallah | **Admin: areas, venues, venue accounts** | Admin screens for areas, venues and venue accounts. | E7-22, E7-19 | `apps/admin-web/src/features/areas/`<br>`apps/admin-web/src/features/venues/`<br>`apps/admin-web/src/features/venue-accounts/` |
| E7-38 | Abdallah | **Customer app: discovery, map and venue page** | App: list of venues, map, and venue page. | E7-22, E7-20 | `apps/customer/src/features/discovery/` |
| E7-39 | Sayed | **Sprint 2 API contract** | Add all Sprint 2 API endpoints as stubs by day 2. | E7-22 | */web/ stubs<br>`packages/api-client/openapi.json` |
| E7-40 | Sayed | **Deadline engine (scheduled_job)** | The timer engine that fires deadlines on time (e.g. 10-minute venue reply). | E7-15 | `…/scheduling/` |
| E7-41 | Sayed | **Transactional outbox and dispatcher** | Send messages only after the action is saved, with retries. | E7-15 | `…/messaging/ (outbox)` |
| E7-42 | Sayed | **Idempotency on writes** | Pressing a button twice never creates two bookings. | E7-15 | `…/messaging/ (idempotency)` |
| E7-43 | Sayed | **Notifications: catalogue, channels, push and email** | Notifications: in-app list, phone push, email, and SMS for codes only. | E7-41 | `…/notification/` |
| E7-44 | Sayed | **Venue live channel: SSE + Web Push** | Live updates on the dashboard, and browser push when it is closed. | E7-41, E7-43 | `…/notification/ (sse, webpush)` |
| E7-45 | Sayed | **Boundary test pack scaffold** | Test tools to set the clock and check timers to the exact second. | E7-40 | `backend/src/test/java/ps/e7gz/support/boundary/` |
| E7-46 | Amr | **Online booking creation (O1)** | A customer books a room online (20 checks), the venue gets the request. | E7-30, E7-39, E7-40 | `…/booking/` |
| E7-47 | Amr | **Venue accept / reject / expiry (O2)** | The venue accepts or rejects within 10 minutes, or the request expires. | E7-46 | `…/booking/` |
| E7-48 | Amr | **Walk-in / manual booking (O4)** | The venue adds a walk-in booking. | E7-30, E7-31 | `…/booking/ (manual)` |
| E7-49 | Amr | **Cancellation, customer buckets, contact visibility** | Customer cancels; booking lists; hide the phone number when not allowed. | E7-46 | `…/booking/ (cancel, projections, visibility)` |

#### Sprint 3 — Late Confirm and attendance · 8 – 19 Nov 2026

*Late Confirm, check-in / late / no-show, start-time revalidation, completion; all four timers boundary-tested. Roadmap Phase 3.*

| Jira | Owner | Task | What it does | Needs first | Writes in |
|---|---|---|---|---|---|
| E7-50 | Abdallah | **Customer app: book a room** | App: choose time and duration, see the price, book, wait for the venue. | E7-39, E7-38 | `apps/customer/src/features/booking/` |
| E7-51 | Abdallah | **Customer app: My Bookings and push** | App: My Bookings, cancel, and receive push notifications. | E7-39 | `apps/customer/src/features/my-bookings/`<br>`apps/customer/src/features/notifications/` |
| E7-52 | Abdallah | **Dashboard: live requests with sound and Web Push** | Dashboard: new requests appear live with a sound, accept or reject. | E7-39 | `apps/venue-dashboard/src/features/requests/`<br>`apps/venue-dashboard/public/ (service worker, alert sound)` |
| E7-53 | Abdallah | **Dashboard: walk-ins and today view** | Dashboard: add walk-ins and see today's bookings. | E7-39 | `apps/venue-dashboard/src/features/today/`<br>`apps/venue-dashboard/src/features/walk-in/` |
| E7-54 | Sayed | **Sprint 3 API contract** | Add all Sprint 3 API endpoints as stubs by day 2. | E7-39 | */web/ stubs<br>`packages/api-client/openapi.json` |
| E7-55 | Sayed | **Booking and attendance notifications** | Send the right notification for every booking and attendance event. | E7-43 | `…/notification/ (triggers)` |
| E7-56 | Sayed | **Admin alerts** | Alerts to the admin when something is seriously wrong. | E7-41 | `…/admin/ (alerts)` |
| E7-57 | Sayed | **Profile and account management** | Edit profile, change username, phone, email, reset password. | E7-14 | `…/identity/ (profile)` |
| E7-58 | Amr | **Late Confirm (O3)** | Late Confirm: the venue offers a late slot, the customer accepts in time. | E7-47, E7-45 | `…/booking/lateconfirm/` |
| E7-113 | Amr | **Opening-hours change: cancel affected bookings (O5)** | When a venue saves new opening hours, cancel the future bookings the new hours no longer cover, release their holds and tell the customers. | E7-25, E7-49, E7-58, E7-55 | `…/booking/ (hours-change cancellation)`<br>`…/venue/internal/ (OpeningHoursCommands)` |
| E7-59 | Amr | **Check-in, late present, no-show (O13)** | Check-in, "arrived late", and no-show. | E7-47 | `…/attendance/` |
| E7-60 | Amr | **Start-time revalidation (O11)** | Re-check everything at start time and cancel with the right reason if needed. | E7-59 | `…/booking/ (start revalidation)` |
| E7-61 | Amr | **Completion and attendance defaults (O12)** | Finish bookings automatically and set the final attendance result. | E7-59 | `…/attendance/ (completion)` |
| E7-114 | Amr | **Venue cancels a confirmed booking (FR-VEN-03)** | The venue cancels a CONFIRMED online booking with the Owner PIN; the customer gets a full refund classification and a notification. | E7-54, E7-47, E7-55 | `…/booking/ (venue cancel confirmed)` |
| E7-115 | Amr | **Venue Overview API (FR-VEN-35)** | The backend for the Dashboard home page: today's and this month's revenue, today's bookings and pending requests. | E7-54, E7-49 | `…/booking/ (overview, revenue read)` |

#### Sprint 4 — Trust, bans and rewards · 22 Nov – 3 Dec 2026

*Trust events and replay, bans, Verified Player, points, wallet, referrals, configuration; tournament entity built early. Roadmap Phase 4.*

| Jira | Owner | Task | What it does | Needs first | Writes in |
|---|---|---|---|---|---|
| E7-62 | Abdallah | **Customer app: Late Confirm and status** | App: Late Confirm screen with countdown and booking status timeline. | E7-54 | `apps/customer/src/features/late-confirm/` |
| E7-63 | Abdallah | **Dashboard: attendance and Late Confirm** | Dashboard: check-in, late, no-show and Late Confirm buttons. | E7-54 | `apps/venue-dashboard/src/features/attendance/` |
| E7-64 | Abdallah | **Customer app: profile and account** | App: profile and account screens. | E7-54 | `apps/customer/src/features/profile/` |
| E7-116 | Abdallah | **Dashboard: Overview home page** | Dashboard home page: today's and this month's revenue, today's bookings, pending requests, today's schedule and quick actions. | E7-54 | `apps/venue-dashboard/src/features/overview/` |
| E7-65 | Sayed | **Sprint 4 API contract** | Add all Sprint 4 API endpoints as stubs by day 2. | E7-54 | */web/ stubs<br>`packages/api-client/openapi.json` |
| E7-66 | Sayed | **Loyalty ledgers and fee treatment** | Points and wallet ledgers, and what happens to the booking fee in every case. | E7-49 | `…/loyalty/` |
| E7-67 | Sayed | **Referrals** | Invite a friend: referral rewards and reversals. | E7-66, E7-13 | `…/loyalty/ (referral)` |
| E7-68 | Sayed | **Configuration service (fees and points)** | Admin changes the booking fee and points values (from a date onward). | E7-15 | `…/config/` |
| E7-69 | Amr | **Trust events and replay** | Trust history of every customer, recalculated exactly from events. | E7-61 | `…/trust/` |
| E7-70 | Amr | **Reconciler and attendance corrections** | Fix attendance mistakes and recalculate trust correctly. | E7-69 | `…/trust/ (reconciler)` |
| E7-71 | Amr | **Bans and the ban cascade (O14)** | Temporary bans after repeated no-shows, and their effects. | E7-70 | `…/trust/ (ban)` |
| E7-72 | Amr | **Verified Player** | The Verified Player badge: earning, losing, restoring it. | E7-70 | `…/trust/ (verified player)` |
| E7-73 | Amr | **Tournament entity (early, for Sprint 5)** | Basic tournament records early, so venue emergencies can handle them. | E7-29 | `…/tournament/` |

#### Sprint 5 — Running the venue · 6 – 17 Dec 2026

*Maintenance, closures, emergencies, owner review, timer pauses, account disable, fault injection. Roadmap Phase 5.*

| Jira | Owner | Task | What it does | Needs first | Writes in |
|---|---|---|---|---|---|
| E7-74 | Abdallah | **Customer app: standing, points, wallet, referrals** | App: my standing, points, wallet and referral code. | E7-65 | `apps/customer/src/features/rewards/`<br>`apps/customer/src/features/profile/ (standing)` |
| E7-75 | Abdallah | **Admin: trust, bans, Verified Player, points, configuration** | Admin: trust fixes, bans, badges, points, fees. | E7-65 | `apps/admin-web/src/features/trust/`<br>`apps/admin-web/src/features/bans/`<br>`apps/admin-web/src/features/verified-players/`<br>`apps/admin-web/src/features/points/`<br>`apps/admin-web/src/features/configuration/` |
| E7-76 | Abdallah | **Dashboard: Arabic RTL review of built modules** | Check every dashboard screen in Arabic and fix layout. | E7-53 | `apps/venue-dashboard/src/ (all features)` |
| E7-77 | Sayed | **Sprint 5 API contract** | Add all Sprint 5 API endpoints as stubs by day 2. | E7-65 | */web/ stubs<br>`packages/api-client/openapi.json` |
| E7-78 | Sayed | **Venue account disable (O10) and attendance protection** | Admin disables a venue account and what happens to its bookings. | E7-61 | `…/venue/ (account disable)`<br>`…/attendance/ (protection)` |
| E7-79 | Sayed | **Fault-injection harness and sellable-gap detector** | Tests that break a process halfway and prove nothing is left half-done. | E7-32 | `backend/src/test/java/ps/e7gz/faultinjection/`<br>`…/inventory/sweep/` |
| E7-80 | Sayed | **Operational notifications** | Notifications for emergencies, closures, refunds and admin events. | E7-43 | `…/notification/ (triggers)` |
| E7-81 | Amr | **Maintenance and closures** | Maintenance windows and venue closures block the calendar. | E7-30, E7-113 | `…/operations/ (maintenance, closure)` |
| E7-82 | Amr | **Emergencies and cascades (O7/O8)** | Venue or room emergency: cancel or pause affected bookings safely. | E7-81, E7-79 | `…/operations/ (emergency)` |
| E7-83 | Amr | **Timer pause/resume and feasibility** | Pause and resume timers during emergencies (30-minute limits). | E7-82 | `…/operations/ (pause)` |
| E7-84 | Amr | **Owner review cascade (O9)** | Owner review: lock the venue down when limits are exceeded. | E7-82, E7-73 | `…/operations/ (owner review)` |

#### Sprint 6 — Tournaments · 20 – 31 Dec 2026

*Full tournament lifecycle, approval, checkpoint, participants, resource loss. Roadmap Phase 6.*

| Jira | Owner | Task | What it does | Needs first | Writes in |
|---|---|---|---|---|---|
| E7-85 | Abdallah | **Dashboard: operations screens** | Dashboard: maintenance, closures and emergency screens. | E7-77 | `apps/venue-dashboard/src/features/operations/` |
| E7-86 | Abdallah | **Admin: owner review, disable, alerts** | Admin: owner review, disable venue account, alerts inbox. | E7-77 | `apps/admin-web/src/features/owner-review/`<br>`apps/admin-web/src/features/venue-accounts/`<br>`apps/admin-web/src/features/emergencies/`<br>`apps/admin-web/src/features/alerts/` |
| E7-87 | Abdallah | **Customer app: operational states** | App: show emergency cancellations, pauses and refunds. | E7-77 | `apps/customer/src/features/my-bookings/ (operational states)` |
| E7-88 | Sayed | **Sprint 6 API contract** | Add all Sprint 6 API endpoints as stubs by day 2. | E7-77 | */web/ stubs<br>`packages/api-client/openapi.json` |
| E7-89 | Sayed | **Tournament Owner Password matrix and notifications** | Owner Password rules and notifications for tournaments. | E7-43, E7-24 | `…/common/security/owner/`<br>`…/notification/ (triggers)` |
| E7-90 | Sayed | **Tournament reachability and boundary tests** | Tests proving every tournament outcome can happen and happens in the right order. | E7-45 | `backend/src/test/java/ps/e7gz/tournament/` |
| E7-91 | Amr | **Tournament creation and holds (O16 part 1)** | Create a tournament (by a player or a venue) and hold the rooms. | E7-73, E7-88 | `…/tournament/` |
| E7-92 | Amr | **Approval, rejection, timeout (T7–T9)** | The venue approves or rejects in time; otherwise it is cancelled. | E7-91 | `…/tournament/` |
| E7-93 | Amr | **Join, leave, checkpoint, start, cancel** | Players join and leave; minimum check; start; cancel. | E7-92 | `…/tournament/` |
| E7-94 | Amr | **Resource loss, Verified Player cascade, precedence** | Losing rooms, losing a badge, bans and deleted accounts in tournaments. | E7-93, E7-72 | `…/tournament/` |

#### Sprint 7 — Reviews, admin and launch · 3 – 14 Jan 2027

*Reviews and reporting, account deletion, admin modules, reports, observability, deployment, load test. Roadmap Phase 7. Frontend: tournament screens.*

| Jira | Owner | Task | What it does | Needs first | Writes in |
|---|---|---|---|---|---|
| E7-95 | Abdallah | **Customer app: tournaments** | App: browse, create, join and leave tournaments. | E7-88 | `apps/customer/src/features/tournaments/` |
| E7-96 | Abdallah | **Dashboard: tournaments** | Dashboard: tournament requests with sound, approve/reject, manage. | E7-88 | `apps/venue-dashboard/src/features/tournaments/` |
| E7-97 | Abdallah | **Admin: tournaments** | Admin: tournaments list and cancel. | E7-88 | `apps/admin-web/src/features/tournaments/` |
| E7-98 | Sayed | **Sprint 7 API contract** | Add all Sprint 7 API endpoints as stubs by day 2. | E7-88 | */web/ stubs<br>`packages/api-client/openapi.json` |
| E7-99 | Sayed | **Remaining admin modules** | Finish any admin module still missing (20 in total). | E7-98 | `…/admin/` |
| E7-100 | Sayed | **Observability, alerts and abuse signals** | Monitoring, alert rules and abuse signals. | E7-56 | `…/common/observability/`<br>`infra/monitoring/` |
| E7-101 | Sayed | **Deployment, backup and restore drill** | Put it online (staging and production), backups and a restore test. | E7-11 | `infra/` |
| E7-102 | Amr | **Reviews, reporting and moderation** | Reviews: write, edit, report, and admin moderation. | E7-61 | `…/review/` |
| E7-103 | Amr | **Account deletion and O19** | Customer asks to delete the account; admin completes it. | E7-93 | `…/identity/ (deletion)` |
| E7-104 | Amr | **Reports FR-AUD-11/12 and metrics** | Admin reports: why bookings end, attendance per venue. | E7-61, E7-115 | `…/admin/ (reports)` |
| E7-105 | Amr | **Load test at 10×** | Load test at 10 times the expected traffic. | E7-28, E7-101 | `tools/load-tests/` |

#### Sprint 8 — Frontend catch-up and launch · 17 – 28 Jan 2027

*Frontend finishes the Sprint 7 screens on the finished API; final RTL pass; store builds; launch checklist. Backend: bug fixes and support.*

| Jira | Owner | Task | What it does | Needs first | Writes in |
|---|---|---|---|---|---|
| E7-106 | Abdallah | **Customer app: reviews, reporting, delete account** | App: reviews, report a review, delete account. | E7-98 | `apps/customer/src/features/reviews/`<br>`apps/customer/src/features/profile/ (delete account)` |
| E7-107 | Abdallah | **Admin: moderation, deletion, reports** | Admin: moderate reviews, complete deletions, report screens. | E7-98 | `apps/admin-web/src/features/reviews/`<br>`apps/admin-web/src/features/account-deletion/`<br>`apps/admin-web/src/features/reports/` |
| E7-108 | Abdallah | **Dashboard: final RTL pass and reviews view** | Final Arabic pass on the dashboard and venue reviews page. | E7-96 | `apps/venue-dashboard/src/ (all features)`<br>`apps/venue-dashboard/src/features/reviews/` |
| E7-109 | Abdallah | **Store and web release readiness** | Store builds for testers (Play + TestFlight), dashboards online. | E7-101 | `apps/customer/ (app config, assets)`<br>`infra/` |
| E7-110 | Sayed | **Launch readiness checklist (PO)** | Go through the launch checklist and sign it off. | E7-105, E7-101 | `docs/launch-readiness.md` |
| E7-117 | Abdallah | **Dashboard: revenue screen** | Dashboard revenue screen: online, manual and tournament revenue by business date, with an optional activity breakdown. | E7-98 | `apps/venue-dashboard/src/features/revenue/` |

`…/` = `backend/src/main/java/ps/e7gz/`. The full Claude Code prompt of each task is in its Jira description and in `BUILD-PLAN.md`.

---

## 4. How we work — the rhythm

| When | What |
|---|---|
| **Sprint day 1** (Sunday) | 30-min planning: each person picks their lane's tasks for the sprint in order of *Needs first*; Platform starts the `Sx-P0` contract |
| **Day 2** | Contract PR merged (Abdallah approves it). Abdallah builds the previous sprint's screens on the real API |
| **Every day** | 15-min sync (call or chat): *merged yesterday · doing today · blocked by*. Contract changes are announced here |
| **Every day** | Review teammates' PRs **before** starting new work — a PR should not wait more than one day |
| **Sprint last day** (Thursday of week 2) | 45-min demo on the real backend + retro (keep / change). Unfinished tasks move to the next sprint with a note |

Jira statuses: **To Do → In Progress → Done**. A task is *In Progress* from the first commit until its PR is merged. Only one *In Progress* task per person at a time (two if one is waiting for review).

---

## 5. First day: skeleton → GitHub → everyone pulls

1. **Sayed** runs the E7-9 prompt in Claude Code, reviews the result, then pushes it:
   `git init && git add . && git commit -m "E7-9: project skeleton" && git branch -M main && git remote add origin <repo-url> && git push -u origin main`
2. **Sayed** protects `main` on GitHub (Settings → Branches: require PR, 1 approval, passing checks) and adds Amr and Abdallah as collaborators.
3. **Amr and Abdallah** clone once: `git clone <repo-url>`.
4. From now on nobody commits to `main` directly — every task is its own branch (below).
5. **Abdallah** uses his free AI tool with the context bundle: `node tools/ai-context/bundle.mjs E7-xx` (CLAUDE.md rules + the task in one file).

Visual version with each person's tasks: `team-map.html` (open it in a browser) or the shared Team Map link.

## 6. One task, step by step

```text
1. Jira: assign yourself, move to In Progress.
2. git switch main && git pull
   git switch -c E7-46-online-booking            # E7-<key>-<short-name>
3. Open Claude Code (Sayed, Amr) or Gemini CLI (Abdallah — with `node tools/ai-context/bundle.mjs E7-xx`) in the repo root. Paste the task's prompt from Jira.
4. Let it work. Answer its questions. If it asks a PRODUCT question, ask Sayed — never guess.
5. Review the diff yourself (git diff main). You must understand every line you push.
6. Run the checks:  cd backend && ./gradlew check     |    pnpm -r lint typecheck build
7. Commit:  git commit -m "E7-46: online booking creation (O1)"   (small commits are fine)
8. git fetch && git rebase origin/main   → fix conflicts → re-run checks
9. git push -u origin HEAD → open PR titled "E7-46: Online booking creation (O1)", fill the template.
10. Reviewer approves + CI green → Squash and merge → delete branch → Jira: Done.
```

If a task is too big for one PR (> ~400 changed lines), split it into several PRs with the same Jira key (`E7-46 part 1/2`).

---

## 7. Definition of Done (PR checklist)

- [ ] Every *Done when* item of the task is a passing test
- [ ] `./gradlew check` and/or `pnpm -r lint typecheck build` green locally and in CI
- [ ] If an endpoint changed: `pnpm api:gen` run and `openapi.json` committed
- [ ] Code only in the task's folders (§3); no edits to `docs/` or `V1__baseline.sql`
- [ ] No hard-coded rule text, status names from prototypes, colours, `Instant.now()`, `float`/`double` money
- [ ] Dashboard screens checked in Arabic (RTL)
- [ ] Reviewer: Sayed ↔ Amr for backend; Sayed for apps; contract PRs also need Abdallah
- [ ] Jira moved to Done with the PR link

---

## 8. Avoiding conflicts

| Shared thing | Rule |
|---|---|
| `packages/api-client/openapi.json` | Never edit by hand. Regenerate after rebasing (`pnpm api:gen`) and commit the result |
| `pnpm-lock.yaml`, `gradle` files | Add dependencies in their own small PR, or tell the owner (Sayed) first |
| Database changes | Almost never needed — the DB is complete. If you truly need one: new file `V<yyyyMMddHHmm>__what.sql` (timestamp avoids clashes), update the `.dbm` + change report, Sayed approves |
| `common/` classes | Owned by Sayed; propose changes in the sync first |
| Enums in `common/codes/` | Generated from the DB lists (E7-16); never add a value in Java only |
| A contract already merged | Change it only in a new PR announced in the sync; Clients updates the screens |

Rebase on `main` at least once a day. Keep branches short-lived (1–3 days).

---

## 9. When you are blocked

1. **Waiting for another task?** Backend: seed the DB directly and keep going. Frontend: use `pnpm mock` for anything not finished yet.
2. **Product question** (a rule, a message, a timer)? Ask Sayed with the doc section. Answers are added to `docs/`, never only in chat.
3. **Architecture question?** Check the arch doc the task lists; if still unclear, raise it in the sync — the owner of that folder decides.
4. **Claude Code went off track?** `git restore .`, tighten the prompt (add the file/section it missed), run again. Don't merge code you can't explain.
