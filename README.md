# E7GZ PS

E7GZ PS is a marketplace for booking PlayStation / gaming rooms at venues in one city. Customers find a venue and book; venues accept, run check-in and operate; a Platform Admin runs the marketplace. This monorepo holds the Spring Boot backend (`backend/`), the three client apps (`apps/`: customer mobile app, Venue Dashboard, Platform Admin), shared packages (`packages/`) and the full product, architecture, design and database documentation (`docs/`).

See **[DEVELOPER-GUIDE.md](DEVELOPER-GUIDE.md)** for folders, ownership and workflow, **[BUILD-PLAN.md](BUILD-PLAN.md)** for sprints and task prompts, and **[CLAUDE.md](CLAUDE.md)** for the coding rules.

## Getting started

**Install once**

| Tool | Who | Notes |
|---|---|---|
| Git | everyone | |
| Java **17 or newer** (any version, any vendor) | backend (Sayed, Amr) | The build always compiles and runs on Java 21 — Gradle downloads it automatically if you don't have it. |
| Node.js 22+ | everyone | pnpm comes with it via corepack: run `corepack enable` once in an **admin** terminal, or prefix commands with `corepack` (`corepack pnpm install`). |
| Docker Desktop | backend; Clients only if running the real backend | Local PostgreSQL, SeaweedFS (file storage), Mailpit (fake email) and the Testcontainers tests. Keep it running while you work. |

**First run**

```bash
git clone <repo-url> && cd e7gz-ps
cd backend && ./gradlew check     # first run downloads Gradle (and Java 21 if needed) — a few minutes
cd .. && pnpm install
```

On Windows use `gradlew.bat check` in PowerShell/cmd, or `./gradlew check` in Git Bash.

**Local environment** (needs Docker Desktop running)

```bash
docker compose up -d              # start database, file storage, fake email
cd backend && ./gradlew bootRun --args="--spring.profiles.active=api"
```

| Service | Address | Login (local only) |
|---|---|---|
| PostgreSQL 16 + PostGIS | `localhost:5433`, database `e7gz_ps` | `postgres` / `postgres` (admin) · app uses `e7gz_app_runtime` / `e7gz_app_runtime` |
| Mailpit (fake inbox) | http://localhost:8025 (SMTP `localhost:1025`) | — |
| SeaweedFS (S3 file storage) | http://localhost:8333 | `e7gz-dev` / `e7gz-dev-secret` |

Port **5433** (not 5432) so an installed PostgreSQL does not clash. `docker compose down` stops everything; `docker compose down -v` also **wipes** the database (it is rebuilt on the next start).

**Database:** every developer has their own local database. You never create tables by hand: when the backend starts, Flyway applies the migrations in `backend/src/main/resources/db/migration/` (`V1__baseline.sql` = the model from `docs/04-erd/physical`, then any newer `V…` files). After a `git pull` with a new migration, just restart the backend. Staging and production get the same migrations (E7-101).
