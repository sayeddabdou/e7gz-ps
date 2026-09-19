# E7GZ PS — THE PLAN

Eight sprints. Small tickets. One ticket at a time with Claude Code.

---

# READ THIS PART ONCE

## You don't need to read the architecture docs

`docs/01-product/` and `docs/02-architecture/` are **not homework for you**. They are fuel for Claude Code.

When you say "build the booking screen", Claude Code reads them and already knows the 20 rules. Without them it would ask you 20 questions, and you'd have to answer them correctly, and a wrong answer becomes a bug six weeks later.

**Your job is this file.** Pick a ticket, paste it, review what comes back, move on.

## How the folders are laid out — screaming architecture

Open the project and it should scream **"this is a venue booking system"**, not "this is a Spring Boot app". So the top level is named after the business, not after the framework:

```
src/main/java/ps/e7gz/
├─ booking/          ← someone books a room
├─ availability/     ← is this room free?
├─ reservation/      ← the calendar that stops double-booking
├─ venue/            ← the venue, its account, its opening hours
├─ resource/         ← rooms and tables
├─ pricing/          ← what it costs
├─ lateconfirm/      ← reviving an expired request
├─ attendance/       ← did they show up?
├─ operations/       ← maintenance, closures, emergencies
├─ tournament/       ← tournaments
├─ trust/            ← no-shows, bans, verified players
├─ loyalty/          ← points, referrals, wallet, fees
├─ review/           ← venue reviews
├─ identity/         ← accounts, OTP, login
├─ notification/     ← push, SMS, email
├─ admin/            ← platform admin
└─ platform/         ← the boring framework plumbing, kept out of the way
```

**There is no `controllers/`, `services/`, `repositories/` at the top level.** Those words tell you nothing about the business. Inside each business folder you get four small layers:

```
booking/
├─ api/              the HTTP endpoints
├─ domain/           the rules — no Spring, no database, just the business
├─ application/      the use cases that wire rules to data
└─ infrastructure/   the database and framework code
```

Two rules that keep it screaming:

1. **A folder owns its tables.** `booking/` is the only code that writes to the booking tables. If `tournament/` needs something from booking, it calls a method in `booking/api/`, never a SQL join.
2. **`domain/` imports nothing from Spring or the database.** You can read the business rules without knowing any framework.

Claude Code already knows this. `CLAUDE.md` tells it on every run.

## How to work a ticket

1. Open Claude Code in this folder.
2. Copy the ticket's **Prompt** and paste it.
3. For tickets marked 🔴, press `shift+tab` first to get a plan, and read the plan before approving.
4. When it's done: `./gradlew check` must pass.
5. Look at the changed files yourself. Especially anything that touches the database.
6. Commit. Move to the next ticket.

**Three habits that will save you weeks:**

- **One ticket at a time.** If it starts building the next ticket too, stop it.
- **If it says "I'll assume…" or "a reasonable default would be…", stop it.** The answer is already written down in the docs. Tell it to go find it. Nine times out of ten the docs say something different from what it assumed.
- **Never let it weaken a database rule to make a test pass.** Especially the "no double booking" rule. If that rule makes a test fail, the test is wrong.

## Sprints at a glance

| Sprint | What you'll have at the end | Length |
|---|---|---|
| **0** | Empty project that runs, and people can sign up | 1 week |
| **1** | A room calendar that physically cannot be double-booked | 2 weeks |
| **2** | A customer books, a venue accepts. End to end. | 2 weeks |
| **3** | It handles the venue being slow, and records who showed up | 2 weeks |
| **4** | No-show tracking, bans, points, referrals | 2 weeks |
| **5** | The venue can run its day — maintenance, closures, emergencies | 2 weeks |
| **6** | Tournaments | 2 weeks |
| **7** | Reviews, admin panel, polish, launch | 2 weeks |

**Total: about 15 weeks.** Sprint 1 blocks everything — don't rush past it.

**Three people, three lanes.** 🅐 backend · 🅑 second backend or full-stack · 🅒 frontend. Tickets in the same sprint with different letters can run at the same time.

---

# SPRINT 0 — GET THE ENGINE RUNNING
*One week. Nothing a customer can see yet.*

### S0-1 🅐 — Create the project skeleton 🔴
**What:** The folder structure above, a database running locally, and a build that passes.
**Done when:** `./gradlew check` passes on an empty project and `docker compose up` gives you a working database.

> **Prompt:** Read CLAUDE.md. Scaffold the project: Gradle, Spring Boot 3, Java 21, package root `ps.e7gz`, one top-level package per business area as listed in BUILD-PLAN.md, each with api/domain/application/infrastructure inside. Add ArchUnit tests that fail if a domain package imports Spring or the database, if one business package imports another's domain or infrastructure, if anything calls `Instant.now()`, or if a money value uses float or double. Wire Flyway with two database roles. Add docker-compose with postgres 16 including btree_gist and postgis, MinIO and MailHog. Add OpenAPI generation and a task that generates TypeScript clients. Add GitHub Actions CI. No business logic — stop when the build passes empty.

### S0-2 🅑 — Accounts, OTP and login
**What:** Sign up, verify your phone by SMS code, log in.
**Done when:** You can register, get a code, verify, and log in. A 6th code request in an hour is refused.

> **Prompt:** Read CLAUDE.md, then `docs/02-architecture/10 — Security Architecture.md` §2 and `docs/02-architecture/04 — Data Model & Database Design.md` §5.1. Build the identity package: registration, username uniqueness, the OTP rules exactly as FR-ACC-04 states them, Argon2id password hashing, JWT plus rotating refresh tokens, and the 30-day username cooldown. Phone verification is the only thing that activates an account. Email verification is separate and never activates anything.

### S0-3 🅒 — Three empty apps that build
**What:** The customer mobile app, the venue dashboard, the admin panel — routing and empty screens only.
**Done when:** All three build. The venue dashboard flips to Arabic right-to-left with one toggle and nothing breaks.

> **Prompt:** Read `docs/02-architecture/01 — Technical Architecture.md` §8.1 and look at the prototypes in `docs/03-design/`. Create three app skeletons: `apps/customer` (React Native + TypeScript), `apps/venue` (React + Vite, with right-to-left support built in from the start using CSS logical properties), `apps/admin` (React + Vite, English only). Pull the colours, spacing and typography out of the prototypes into a shared tokens package. Routing and empty screens only, no API calls.

---

# SPRINT 1 — THE ROOM CALENDAR
*Two weeks. This is the heart of the product. Everything else sits on it.*

> **Why this matters:** a venue's biggest fear is selling the same room twice. This sprint makes that physically impossible at the database level — not "we check for it in the code", but "the database refuses to store it".

### S1-1 🅐 — Venues, rooms and prices
**What:** Areas, venues, venue accounts, rooms and tables, and what each costs.
**Done when:** You can create a venue with PlayStation rooms (PS4/PS5), billiards tables and ping pong tables, each with a price.

> **Prompt:** Read `docs/02-architecture/04 — Data Model & Database Design.md` §5.3 and §5.4. Build the area, venue, venue account, resource and pricing tables with every CHECK constraint listed there. Two things to get right: a resource's base state and its operational restriction are independent columns — a room can be ACTIVE and still unsellable. And PlayStation pricing is per console type × play mode, while billiards and ping pong are a single hourly rate with no per-table override.

### S1-2 🅐 — Opening hours 🔴
**What:** Each venue sets its weekly hours. Handles closing after midnight and daylight-saving changes.
**Done when:** A venue open 4pm–2am works. Two overlapping sessions are refused by the database.

> **Prompt:** Read `docs/02-architecture/02 — Architecture Decision Record Log.md` ADR-009 and `docs/02-architecture/04` §5.3. Build opening hours as a weekly template plus per-day overrides, then generate real dated sessions from it over a rolling 120-day window. Add the exclusion constraint that stops two sessions overlapping. Handle the week wrapping from Sunday to Monday, and handle daylight saving explicitly — test the non-existent hour, the repeated hour, and the 23-hour and 25-hour day.

### S1-3 🅐 — The reservation calendar 🔴🔴 **the most important ticket in the project**
**What:** One table that holds every kind of booking on a room, with a database rule that two can never overlap.
**Done when:** Two overlapping bookings on one room — one succeeds, one fails cleanly, and nothing half-saved is left behind.

> **Prompt:** Read `docs/02-architecture/05 — Availability & Reservation Engine.md` §3 and ADR-010 in full before writing anything. Build the `resource_reservation` table exactly as specified, including the exclusion constraint, the planned-period vs occupied-period split, and the hold / release / truncate operations. This constraint is the single most important thing in the codebase — never weaken it. Write these three tests first: two overlapping holds, one wins and one fails cleanly; a release frees the slot; a no-show at 8:16 on an 8:00–9:00 booking lets a new booking start at 8:20, with both records surviving.

### S1-4 🅐 — The "is this room free?" check 🔴
**What:** One function that answers it, used by every part of the system. There is never a second one.
**Done when:** It runs eight checks in a fixed order and tells you which check failed.

> **Prompt:** Read `docs/02-architecture/05` §2. Build `isFree` as the eight ordered checks, in exactly that order, returning which check failed. Three things that are easy to get wrong: checks 1 and 2 are a state lookup, not a time-overlap check; an unresolved emergency is never treated as a booking that lasts forever; and a booking's status decides whether it blocks the room, never its source. There must be exactly one implementation — add a test that fails if a second one appears.

### S1-5 🅑 — The stress test
**What:** A test that fires 50 simultaneous bookings at the same slot.
**Done when:** Exactly one succeeds, 49 fail cleanly, and a sweep finds zero overlapping rows.

> **Prompt:** Read `docs/02-architecture/05` §4 and §9. Build a Gradle task `concurrencyTest` that fires N simultaneous bookings at one slot and asserts exactly one success and N−1 clean failures. Add a query that scans for any overlapping reservation on any room, and assert zero rows after every test run. This runs before every commit that touches the calendar, for the rest of the project.

### S1-6 🅑 — "Next available at…"
**What:** The number on a venue card telling the customer the soonest free slot.
**Done when:** A venue list with 50 venues loads in under a second.

> **Prompt:** Read `docs/02-architecture/05` §5. Build the nearest-availability calculation and store the answer in a small lookup table refreshed in the background, plus PostGIS for distance sorting. This table is for display only — add a test proving nothing reads it when actually creating a booking.

**Sprint 1 is done when:** the stress test is green, the overlap sweep finds nothing, and there is only one `isFree` in the codebase.

---

# SPRINT 2 — BOOK A ROOM, END TO END
*Two weeks. At the end of this sprint you can demo the product.*

### S2-1 🅐 — Create a booking 🔴
**What:** The customer picks a room and a time and submits. The system checks 20 things before saying yes.
**Done when:** A booking 15 minutes ahead succeeds; 14 minutes 59 seconds is refused. A 5th simultaneous booking by the same customer is refused.

> **Prompt:** Read `docs/02-architecture/04` §5.7 and `docs/02-architecture/06 — Booking & Tournament Lifecycle Engine.md` §1.3. Build the booking table with all its constraints, including the one that stops a customer holding two overlapping bookings. Then build booking creation as one ordered, named list of exactly twenty conditions in a single class, each citing its source requirement. Add a test asserting the list has exactly twenty entries. There is no twenty-first condition.

### S2-2 🅐 — The 10-minute timer 🔴
**What:** The venue has 10 minutes to answer. If they don't, the request expires by itself.
**Done when:** A timer that fires 5 minutes late produces exactly the same result as one that fires on time.

> **Prompt:** Read `docs/02-architecture/08 — Scheduling, Timers & Event Architecture.md` §2 and ADR-006. Build the scheduled-transition table and the worker that runs it, using database leases so several workers are safe. The worker must never decide an outcome from when it happened to run — it re-reads the current state and applies the deadline rule. Test that firing five minutes late gives the identical outcome.

### S2-3 🅐 — Venue accepts or rejects
**What:** The venue taps Accept or Reject on a request.
**Done when:** A suspended venue can still accept a request that came in before it was suspended.

> **Prompt:** Read `docs/02-architecture/06` §1.2 and §2.1. Build accept, reject with its mandatory free-text reason, reject-after-expiry, and the expiry transition. Two things that are easy to get wrong: accepting an existing request does not require the venue to be marketplace-ACTIVE, and if the accept fails because of an emergency, the booking is cancelled under that operational reason with no blame recorded against the venue.

### S2-4 🅑 — Walk-in bookings
**What:** The venue types in a customer who walked through the door.
**Done when:** A walk-in at 3am for a venue that closes at 2am still works and still lands on the right revenue day.

> **Prompt:** Read `docs/02-architecture/06` §1.4. Build manual bookings: they skip the pending step, may have no registered customer, never count toward the customer's booking limit, never affect trust or points, and always carry a business date. No back-dating, ever.

### S2-5 🅑 — Notifications
**What:** Push, SMS and email that never fire for something that didn't actually happen.
**Done when:** Injecting a failure mid-transaction produces no notification at all.

> **Prompt:** Read `docs/02-architecture/08` §4 and ADR-004. Build the outbox pattern: notifications are written inside the same database transaction as the business change, and a separate worker sends them after the commit. Add retry with backoff and a dead-letter state. Every booking notification must carry the booking ID so the push opens the right screen.

### S2-6 🅒 — Customer app: find and book
**What:** Pick an area, pick an activity, see venues, open one, pick a room and time, book.
**Done when:** You can book a room on a phone.

> **Prompt:** Read `docs/02-architecture/09 — API Architecture & Contract Map.md` §3 and look at the customer prototype in `docs/03-design/`. Build the discovery and booking flow in the order Area → Activity → Venue → Room → play mode if PlayStation → Date → Start time → Duration → Summary → Confirm, plus My Bookings with its three tabs. All text comes from a catalogue keyed by the code the backend returns — no hard-coded sentences. Countdowns are drawn from the server's deadline, never from a timer the phone keeps itself.

### S2-7 🅒 — Venue dashboard: incoming requests
**What:** New requests appear instantly, with a countdown, and Accept / Reject buttons.
**Done when:** A booking made on the phone appears on the dashboard within a second, without refreshing.

> **Prompt:** Read `docs/02-architecture/09` §4 and look at the venue prototype in `docs/03-design/`. Build Overview, Pending Requests, Missed Requests, Accept/Reject, Bookings, Walk-in booking, and the day schedule. Connect the live stream at `GET /api/v1/venue/stream` — it carries a "something changed" signal only, and the screen refetches. Every screen works in Arabic right-to-left from day one, not as a later pass.

**Sprint 2 is done when:** you can book a room from the phone, the venue accepts it on the dashboard, and every finished booking carries exactly one reason for how it ended.

---

# SPRINT 3 — WHEN THE VENUE IS SLOW, AND WHO SHOWED UP
*Two weeks.*

### S3-1 🅐 — Late Confirm 🔴
**What:** The venue missed the 10 minutes. They can still revive the booking until its start time, and the customer gets to say yes or no.
**Done when:** Reviving at 7:59pm for an 8:00pm booking gives exactly 60 seconds and sends no second reminder.

> **Prompt:** Read `docs/02-architecture/06` §2.2. Build Late Confirm: same booking ID, a temporary hold, and a window of five minutes or until the start time, whichever is sooner. Three privacy details: if the customer already has four bookings the venue gets one neutral message that reveals nothing; the customer's own schedule clash is only checked when they accept; and a clashing accept fails without ending the booking. Only create the "expiring soon" reminder when the window is longer than 60 seconds.

### S3-2 🅐 — Check-in, late, no-show
**What:** The venue records whether the customer turned up.
**Done when:** Check-in at 14:59 after the start works; at 15:00 it's refused and "present but late" becomes available instead.

> **Prompt:** Read `docs/02-architecture/07 — Trust, Attendance & Enforcement Engine.md` §7. Build check-in, mark-present-late and no-show with their exact time windows. The attendance result is its own stored field, never worked out from the booking status. A no-show immediately frees the rest of the slot so it can be resold. Venue staff cannot undo any of these — only a platform admin can.

### S3-3 🅐 — Bookings start and finish by themselves
**What:** At the start time the booking goes live; at the end time it completes. Nobody has to press anything.
**Done when:** A booking can never sit in "confirmed" past its start time because a check failed.

> **Prompt:** Read `docs/02-architecture/05` §7. Build the start transition with its revalidation and the seven-level priority for deciding why it failed, plus completion and attendance resolution. Every failure produces a definite ending with exactly one reason.

### S3-4 🅒 — Customer app: booking status and Late Confirm
**What:** The customer can see exactly what's happening with their booking, and respond to a revival request.
**Done when:** The Late Confirm screen shows a live countdown and the Accept / Decline buttons work.

> **Prompt:** Read `docs/02-architecture/09` §3.3. Build the booking detail screen with its full history, and the Late Confirm accept and decline screens. The copy must make clear the original request expired and the venue is asking to reactivate it. The countdown is drawn from the server's deadline.

---

# SPRINT 4 — NO-SHOWS, BANS AND REWARDS
*Two weeks.*

### S4-1 🅐 — The trust log 🔴🔴
**What:** Every attendance outcome is written to a log that is never edited. The no-show count, the ban, the verified-player status are all worked out by replaying that log.
**Done when:** Replaying a seeded history reproduces the stored numbers exactly. Replaying twice cancels nothing twice.

> **Prompt:** Read `docs/02-architecture/07` §2, §3 and §4 in full. Build the append-only trust event log and the replay function. Replay must be pure — no writes, no cancellations, no notifications. Prove it with a test that runs replay on a read-only database connection. A correction inherits the date of the event it corrects, which is what makes reversing a Monday no-show on Friday change the Monday position in the sequence.

### S4-2 🅐 — Bans and verified players
**What:** Three no-shows in a row = a 7-day ban. The ladder escalates. Ten completed bookings = verified player.
**Done when:** A second offence during an active ban extends that one ban rather than creating a second, and doesn't re-cancel anything.

> **Prompt:** Read `docs/02-architecture/07` §5 and §6. Build the reconciler, the ban ladder, the "at most one active ban" rule and the extension formula, plus verified-player qualification and status. Three unique keys make replaying safe — test double-replay explicitly. A status revoked purely by a correction restores itself when the correction is reversed; one revoked by an admin does not.

### S4-3 🅑 — Points, referrals, wallet
**What:** 200 points for a completed booking, 1,000 for a referral. A wallet that exists but has no screen yet.
**Done when:** Reversing a 1,000-point referral for someone holding 300 points leaves them at 0 and records the 700 as unrecovered, never as debt.

> **Prompt:** Read `docs/02-architecture/04` §5.12. Build the append-only points and wallet ledgers, referrals, and the refund classification. The refund lookup must be an exhaustive switch with no default branch, so adding a new ending without a refund rule fails to compile. Build the payment provider interface with no implementation and no caller, and add a test proving nothing imports it.

### S4-4 🅒 — Customer app: standing, points, referrals
**Done when:** The customer can see "6 / 10 completed bookings", their points, their referral code, and their ban if they have one.

---

# SPRINT 5 — RUNNING THE VENUE
*Two weeks. The hardest sprint. Don't compress it.*

### S5-1 🅐 — Maintenance and closures
**What:** A room out for repair; the venue closed for a day.
**Done when:** Splitting 30 hours of maintenance into two windows with a one-minute gap still requires the owner's password.

> **Prompt:** Read `docs/02-architecture/04` §5.6. Build maintenance windows — which sit inside the reservation calendar, so overlapping a booking is impossible — and venue closures, which sit outside it because a closure is allowed to overlap existing maintenance. Add the rolling 24-hours-in-48 threshold that escalates to the owner.

### S5-2 🅐 — Emergencies 🔴🔴
**What:** A console dies mid-session. The power goes out. The venue declares it and everything affected is cancelled at once — or nothing is.
**Done when:** Injecting a failure halfway through leaves the system exactly as it was before.

> **Prompt:** Read `docs/02-architecture/06` §2.5 and §2.6. Build venue and room emergencies, the selling blocks, the status lifecycle including conversion, and the all-or-nothing cascade. Every consequence — cancellations, terminated sessions, released rooms, audit records, queued notifications — must be saved before any notification is sent. Build a fault-injection harness that injects a failure at every step and asserts the system is left untouched. And never release a block while the room is still broken with nothing replacing it.

### S5-3 🅐 — Owner review
**What:** A room that keeps breaking gets locked until the owner personally signs it off.
**Done when:** The cascade cancels everything on that room automatically, with no owner password needed anywhere in it.

> **Prompt:** Read `docs/02-architecture/06` §2.7. Build owner-review entry, its system cascade, and resolution with its four options behind the owner password. Owner review is not a pause — affected bookings are cancelled, never left hanging.

### S5-4 🅐 — Pausing timers during an emergency 🔴
**What:** The 10-minute clock stops while the venue is dealing with a fire, and resumes where it left off.
**Done when:** Paused 12 minutes by one emergency and 19 by another, it cancels at 30 minutes — the counter does not reset when the emergency type changes.

> **Prompt:** Read `docs/02-architecture/08` §2.5. Build pause and resume with the remaining time preserved, the two separate 30-minute caps, and the feasibility test that decides whether to cancel now or keep waiting. The feasibility test must be a pure function — test that evaluating it twice on the same state gives the same answer. While paused, the customer can still decline.

### S5-5 🅑 — Disabling a venue account
**What:** The platform can cut off a venue's dashboard without pretending the venue physically closed.
**Done when:** A live session keeps running, and a customer whose attendance couldn't be recorded because of it is protected from a no-show penalty.

> **Prompt:** Read `docs/02-architecture/06` §2.8 and `docs/02-architecture/07` §7.5. Build account disable with its five per-state effects, and attendance protection with its six conditions. Two disable cycles in one session produce exactly one protection event. The customer's notification must not contain the word "cancelled".

### S5-6 🅒 — Venue dashboard: operations screens
**Done when:** A venue can declare an emergency, schedule maintenance, close for a day and change its hours — and always sees what it's about to cancel before it confirms.

---

# SPRINT 6 — TOURNAMENTS
*Two weeks.*

### S6-1 🅐 — Tournament lifecycle 🔴
**What:** A player or a venue creates a tournament, it holds rooms, players join, and an hour before the start it either confirms or cancels.
**Done when:** A tournament with 7 players at the checkpoint cancels and releases the rooms in the same transaction.

> **Prompt:** Read `docs/02-architecture/06` §3. Build tournaments as their own entity — never as a booking. Approval holds that become real holds, participants, the checkpoint at exactly one hour before the start, cancellation authority, resource loss and the cost snapshot. Existing bookings always win: creating a tournament can never cancel one. There is no "run anyway" override.

### S6-2 🅒 — Tournament screens
**Done when:** A player can discover a public tournament, join with an invite code, and see whether it's confirmed.

---

# SPRINT 7 — REVIEWS, ADMIN, LAUNCH
*Two weeks.*

### S7-1 🅑 — Reviews
**Done when:** A review removed by an admin can never be restored, and the author waits 30 days before writing another.

### S7-2 🅒 — Admin panel
**Done when:** An admin can onboard a venue, fix a wrong no-show, moderate a review and change the global fee — each with a reason, each written to the audit log.

### S7-3 🅒 — Arabic right-to-left pass
**Done when:** Every dashboard screen reads correctly in Arabic, with stored text unchanged.

### S7-4 🅐 — Monitoring and the launch checklist
**Done when:** The ten launch checks in `docs/02-architecture/11` §8 pass as an automated suite, and you have restored the database from a backup once and timed it.

---

# IF YOU'RE RUNNING OUT OF TIME

Cut scope, not quality. In this order:

1. **Sprint 6, tournaments** — saves 2 weeks. The booking marketplace works fine without it.
2. **Sprint 4, trust and bans** — saves 2 weeks. Keep recording attendance so you can switch it on later.
3. **Reviews** — saves 3 days.
4. **Points and referrals** — saves 4 days.

**Never cut:** the room calendar, the "is it free" check, the 10-minute timer, the reason codes, or the emergency cascade. Those are what stop you selling one room twice and what let you explain any outcome to an angry venue owner. Everything else is a feature. Those are the product.

Take any cut to the Product Owner as a written change request. Don't decide it in a sprint planning meeting.
