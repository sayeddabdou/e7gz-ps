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
| Docker Desktop | backend; Clients only if running the real backend | Local PostgreSQL, MinIO, MailHog (set up by E7-10) and Testcontainers tests. |

**First run**

```bash
git clone <repo-url> && cd e7gz-ps
cd backend && ./gradlew check     # first run downloads Gradle (and Java 21 if needed) — a few minutes
cd .. && pnpm install
```

On Windows use `gradlew.bat check` in PowerShell/cmd, or `./gradlew check` in Git Bash.

**Database:** every developer runs their own local database in Docker (`docker compose up -d`, from E7-10). Migrations in `backend/src/main/resources/db/migration/` build it automatically when the app starts — never share one dev database. Staging and production databases come in E7-101 and use the same migrations.
