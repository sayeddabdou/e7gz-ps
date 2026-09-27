# E7GZ PS — Setup Guide

Follow this once to get the project running on your laptop. It takes about **30 minutes** (mostly downloads).
You do **not** need Claude Code for any of it — just the tools below.

> **Abdallah (apps):** you only need parts 1, 2 and 7 to start. Parts 3–6 are for when you want to run the real backend.

---

## What you will have at the end

```
Your laptop
├── the project code (from GitHub)
├── Docker, running 3 small services:
│     PostgreSQL  — the database            → localhost:5433
│     Mailpit     — a fake email inbox       → http://localhost:8025
│     SeaweedFS   — file storage for photos  → localhost:8333
└── the backend (Java), which builds all 78 database tables by itself the first time it starts
```

Every developer has **their own** copy of the database. Nobody creates tables by hand — the table definitions live in
the code, so everyone always has the same structure.

---

## 1. Install the tools (once)

| Tool | Why you need it | Who |
|---|---|---|
| **Git** — git-scm.com | Download the code, send your changes | Everyone |
| **Node.js 22** — nodejs.org | Runs the three apps and their tools | Everyone |
| **Docker Desktop** — docker.com | Runs the database, email and file storage for you, so you don't install them yourself | Backend (Amr). Apps: optional |
| **Java 17 or newer** (any version, any vendor) | Needed to start the build tool. The project itself always uses **Java 21** and downloads it automatically if you don't have it | Backend (Amr). Apps: optional |
| **DBeaver** — dbeaver.io (or IntelliJ Ultimate) | To look inside the database tables | Backend. Apps: optional |

**Already have PostgreSQL installed?** Leave it alone. Our database runs inside Docker on port **5433**, so the two
never clash.

**Windows:** run the commands below in **Git Bash** (installed with Git), or in PowerShell using `.\gradlew.bat`
instead of `./gradlew`.

---

## 2. Get the code (once)

Accept the GitHub invitation from Sayed first, then:

```bash
git clone https://github.com/sayeddabdou/e7gz-ps.git
cd e7gz-ps
```

Open the **`e7gz-ps` folder** (the one with `README.md` in it) in your editor — not only `backend/`, so you also see
`docs/` and the other files.

For the apps, install their packages:

```bash
corepack pnpm install
```

> `corepack` comes with Node.js and runs the right version of `pnpm` for you. If you run `corepack enable` once in an
> **administrator** terminal, you can type `pnpm` alone afterwards.

---

## 3. Start the local services

1. Open **Docker Desktop** and wait until it says it is running (whale icon in the taskbar).
2. In the project folder:

```bash
docker compose up -d
```

**What happens:** Docker reads `docker-compose.yml`, downloads PostgreSQL, Mailpit and SeaweedFS (first time only, a few
minutes) and starts them in the background (`-d`). The first time, PostgreSQL also runs
`infra/local/postgres/init/20_e7gz.sql`, which creates:

- an **empty** database called `e7gz_ps`
- three database users, each with a different job:

| User | Think of it as | Used by |
|---|---|---|
| `e7gz_migrator` | the builder | Flyway, only when the backend starts, to create/update tables |
| `e7gz_app_runtime` | the staff key | the backend for normal work — can **not** delete history or do admin actions |
| `e7gz_admin_runtime` | the manager key | the backend for Platform Admin actions |

Check it worked:

```bash
docker compose ps
```

You should see `postgres`, `mailpit` and `seaweedfs` with status **Up**.

---

## 4. Build the database (start the backend once)

```bash
cd backend
./gradlew bootRun --args="--spring.profiles.active=api"
```

**What happens:**

1. **Gradle** (the Java build tool) downloads itself, Java 21 if needed, and all libraries. First time: a few minutes.
2. The backend starts and **Flyway** connects to the database. Flyway keeps a notebook (a table) of which migration
   files it has already run. On an empty database it runs:
   - `V1__baseline.sql` — the complete database design from `docs/04-erd/physical`: 78 tables, rules and triggers
   - `R__security_baseline.sql` — what each database user is allowed to do
3. You see **`Started E7gzApplication`** → it worked. Stop it with **Ctrl+C**. The database stays built.

Next time it starts in a few seconds: Flyway sees V1 is already done and only re-applies the permissions.

---

## 5. Check that everything works

```bash
./gradlew check
```

This runs all tests. It must end with **`BUILD SUCCESSFUL`**. The tests start their **own throwaway database** in
Docker (so Docker Desktop must be running) and check:

- the app starts and uses the right database users
- the 124 database rule checks from `docs/04-erd/physical` all pass
- nobody edited `V1__baseline.sql`
- the module rules (a module only talks to another through its `api` package)

---

## 6. Look inside the database

In **DBeaver**: *New Database Connection* → **PostgreSQL** → fill in:

| Field | Value |
|---|---|
| Host | `localhost` |
| Port | **`5433`** (not 5432!) |
| Database | **`e7gz_ps`** (underscore, not dash!) |
| Username | `postgres` |
| Password | `postgres` |

Click **Test Connection** (let it download the driver if it asks) → **Finish**.

The tables are under **Databases → e7gz_ps → Schemas**. A *schema* is a folder of tables, one per topic:
`booking`, `iam` (users and logins), `venue`, `trust`, and so on — 18 in total.

- Double-click a table → **Data** tab shows its rows. Try `config` → `configuration_version` (5 starting settings).
  Most tables are empty until we build features.
- Right-click a schema → **View Diagram** shows how its tables connect.
- Ignore `public` (it belongs to PostGIS, the map extension) and anything starting with `pg_`.

> You are logged in as `postgres`, who can change anything. If you break something, see "Start over" below.

**IntelliJ Ultimate** works the same way: *Database* panel (right side) → **+** → *Data Source* → *PostgreSQL*, same
values; then click **"1 of 22"** next to `e7gz_ps` and tick **All schemas**.

---

## 7. How you work every day

| When | What to do | Why |
|---|---|---|
| Start of the day | Open Docker Desktop → `docker compose up -d` | The database only runs while Docker runs |
| Before starting a task | `git switch main` → `git pull` | Get everyone's merged work |
| Someone merged a new database change | `git pull`, then start the backend | Flyway applies the new migration file to your database automatically |
| End of the day | `docker compose down` | Stops the services; your data is kept |
| Your database is messed up | **Start over:** `docker compose down -v` → `docker compose up -d` → start the backend | `-v` deletes the database; the backend rebuilds it from the migrations |

The task workflow (one branch per Jira task, pull requests, reviews) is in **DEVELOPER-GUIDE.md §6**.

---

## Useful addresses

| What | Where | Login |
|---|---|---|
| Database | `localhost:5433`, database `e7gz_ps` | `postgres` / `postgres` |
| Fake email inbox | http://localhost:8025 | — |
| File storage (S3) | http://localhost:8333 | `e7gz-dev` / `e7gz-dev-secret` |
| Backend | http://localhost:8080 | (login comes with task E7-14) |

These passwords are for your laptop only. Never use them anywhere else.

---

## If something goes wrong

| You see | Meaning | Fix |
|---|---|---|
| `Cannot connect to the Docker daemon` or tests fail right at the start | Docker Desktop is not running | Open Docker Desktop, wait, try again |
| `port is already allocated` | Another program uses 5433, 8025, 8333 or 1025 | Close that program, or tell Sayed |
| `database "e7gz-ps" does not exist` | Dash instead of underscore | Use `e7gz_ps` |
| `Connection refused` when the backend starts | The database is not running | `docker compose up -d` |
| `./gradlew: Permission denied` (Mac/Linux) | The script is not marked executable | `chmod +x gradlew` |
| Java error when running `./gradlew` | Your Java is older than 17 | Install any Java 17 or newer |
| Very slow database on a Mac with M1/M2/M3 | The PostgreSQL image is emulated on Apple chips | Normal — it works, just slower |

Still stuck? Send the error text in the team chat.

---

## Words used in this guide

| Word | Meaning |
|---|---|
| **Docker** | Runs programs (like PostgreSQL) in isolated boxes, so everyone gets the exact same version without installing it |
| **docker compose** | Starts several Docker boxes at once from `docker-compose.yml` |
| **Gradle / `./gradlew`** | The Java build tool: downloads libraries, compiles, runs tests and the app. `gradlew` downloads the right Gradle for you |
| **Flyway** | Builds and updates the database from the files in `backend/src/main/resources/db/migration/` |
| **Migration** | A SQL file that changes the database structure. Never edited after it is merged — changes go in a new file |
| **Schema** | A folder of tables inside the database |
| **Profile (`api` / `worker`)** | The same backend started in two roles: `api` answers the apps, `worker` runs timers and sends notifications |
