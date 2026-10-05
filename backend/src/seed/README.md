# backend/src/seed/

Dev and performance seed data — one command fills the database with realistic test data.

Owner task: **E7-28** (Seed data generator). Owner: Sayed.

## Run it

```
docker compose up -d
cd backend
./gradlew seedDev      # small Cairo marketplace to click through (~20 s)
./gradlew seedPerf     # launch-city volume x10 for load tests (~2 min)
```

The seed boots the application context (no web server, no workers), lets Flyway migrate, writes through the modules'
own services (admin onboarding, Owner Password, audit, DB guards — the same path as the API) and stops.

It uses the database in `E7GZ_DB_URL` (default: the docker compose one) and **refuses**:

- a database that is not on this machine (`localhost`, `127.0.0.1`, `[::1]`) — the seeded logins are public;
- a database that already has areas, venues or customers, or a Platform Admin.

To start again: `docker compose down -v && docker compose up -d` (this also clears the local photo store), or create
an empty database and point `E7GZ_DB_URL` at it, e.g.

```
docker compose exec postgres psql -U postgres -c "CREATE DATABASE e7gz_perf TEMPLATE template0"
E7GZ_DB_URL=jdbc:postgresql://localhost:5433/e7gz_perf ./gradlew seedPerf
```

## Dev seed (`seedDev`)

Logins (local only — PO decision 5 Oct 2026): every password is `Dev12345`, every Owner PIN is `1234`.

| Who | Login |
|---|---|
| Platform Admin | `admin@e7gz.local` |
| Customers | phones `01000000001` … `01000000020` (`player01` … `player20`); 18–20 not phone-verified |

| Area | Venue | Login | Status | Rooms / tables | Hours |
|---|---|---|---|---|---|
| Nasr City | Level Up Lounge | `levelup` | ACTIVE | 2× PS5, 2× PS4, 2 pool | 12:00–02:00; Room 1 multiplayer override 100 EGP |
| Nasr City | Pixel Arena | `pixelarena` (AR) | ACTIVE | 3× PS5, 1× PS4 | 14:00–03:00, Friday 13:00–04:00 |
| Nasr City | Cue & Console | `cueconsole` | ONBOARDING | 1× PS4, 1 pool | none, no prices (still being set up) |
| Maadi | Game Zone Maadi | `gamezone` | ACTIVE | 2× PS5, 1× PS4, 2 ping pong | 24 hours |
| Maadi | The Controller Cafe | `controller` (AR) | ACTIVE | 1× PS5, 1× PS4 | 10:00–23:00, closed Monday |
| Maadi | Spin Club | `spinclub` | INACTIVE | 2 pool, 2 ping pong | 16:00–00:00 |

Default prices (EGP/hour): PS4 40 single / 60 multi, PS5 60 / 90, billiards 50, ping pong 35. Operating Sessions are
generated for the normal 120-day horizon.

## Perf seed (`seedPerf`)

Launch city (PO decision 5 Oct 2026): 15 venues, 2,000 customers, six areas. `seedPerf` writes ten times that
(NFR-15, arch 11 §3): 150 venues in the same six areas (~1,000 rooms and tables, prices, 120 days of sessions each),
20,000 customers (~90 % verified). Fixed random seed, so every run has the same shape. Venue logins are
`perf-venue-001` … `perf-venue-150`, customers `01100000001` …; same password and PIN as the dev seed.

Not seeded yet (their modules are not built): bookings, tournaments, reviews, loyalty activity, venue photos.

## Layout

- `java/ps/e7gz/seed/` — `SeedApplication` (entry point), `SeedGuard`, `Seeder` (service calls), `DevSeed`, `PerfSeed`.
- `java/ps/e7gz/admin/internal/SeedAdmins.java` — reaches the package-private first-admin bootstrap.
- `test/java/` — `SeedIntegrationTest` (task `seedTest`, part of `check`): both seeds on fresh databases.

Shared **test** fixture builders (`aVenue()`, `aResource()`, `aUser().verified()`, …) are a separate thing: they live
in `backend/src/test/java/ps/e7gz/support/fixtures/` and write rows directly for tests.
