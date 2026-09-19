# E7GZ PS — START HERE

Everything needed to build this product is in this folder. This page is the map.

---

## 1. What is here

```
E7GZ-PS/
├─ START-HERE.md          ← you are here
├─ CLAUDE.md              ← the rules Claude Code reads every session. Do not delete.
├─ BUILD-PLAN.md          ← THE PLAN. 8 sprints, 36 small tickets. Read this one.
├─ jira-import.csv        ← import straight into Jira: 8 epics, 36 stories
├─ docs/
│  ├─ 01-product/         ← APPROVED BEHAVIOUR. Frozen. 9 documents.
│  │    PRD v1.1 · FRS v1.1 (356 requirements) · Business Rules · Validation Rules
│  │    User Stories (67) · Acceptance Criteria · User Flows (37) · Edge Cases (91)
│  │    Out-of-Scope & MVP Exclusions
│  ├─ 02-architecture/    ← HOW WE BUILD IT. 15 documents.
│  │    Architecture · ADR log · Modules · Data model · Availability engine
│  │    Lifecycle engine · Trust engine · Scheduling · API · Security
│  │    NFR & observability · Deployment · Traceability · Roadmap
│  ├─ 03-design/          ← the three UI prototypes, zipped and extracted
│  ├─ 04-erd/             ← the ERD. Open e7gz-schema.html in a browser.
│  └─ _archive-superseded/  ← v4.x material. DO NOT READ. Kept only for history.
└─ (your code goes here)
```

**Want to see the shape of it?** Open `docs/04-erd/e7gz-schema.html` in a browser — 63 tables, an ER diagram per module, and the constraints that carry a product guarantee marked. `E7GZ-PS.dbml` in the same folder imports into dbdiagram.io if you want to pan and zoom.

**The one rule about this folder:** `docs/01-product/` is the authority on *what the product does*. `docs/02-architecture/` is the authority on *how we build it*. Neither is edited during the build. If you need to change behaviour, that is a Product Change Request — not a code decision.

---

## 2. Your first three days

### Day 1 — five decisions and six accounts

**Five decisions.** They are listed in `docs/02-architecture/01 — Technical Architecture.md` §14. Two of them change the database schema, so they block the first migration:

| | Decision | Blocks |
|---|---|---|
| A1 | Can two maintenance windows on the same resource overlap? *(recommended: no)* | the schema |
| A2 | Is a 120-day rolling operating-session horizon acceptable? *(recommended: yes)* | the schema |
| A4 | Approve the proposed Owner Password / login / per-IP rate limits | nothing — approve later |
| A5 | Are `FR-AUD-11` and `FR-AUD-12` launch-optional? | nothing |
| A6 | Is SSE + Web Push acceptable as the venue notification channel? | nothing |

**Six accounts.** GitHub · Jira · a managed PostgreSQL 16 for dev · an SMS provider for Egypt **with a fallback chosen** (this is the one dependency that can block launch entirely) · a Firebase project for push · an S3-compatible bucket.

### Day 2 — make this folder a repository

```bash
cd "C:\Users\sayed\Desktop\Tstt"
git init
git add .
git commit -m "Product baseline v1.1 + technical architecture v1.0"
```

Then push it. From this point the specification is version-controlled alongside the code, which is what stops the two drifting apart.

### Day 3 — Jira

| Level | What | Count |
|---|---|---|
| Epic | the 8 build phases | 8 |
| Story | the 67 user stories, acceptance criteria pasted from the Acceptance Criteria doc | 67 |
| Task | the 33 work packages in `BUILD-PLAN.md` | 33 |
| Sub-task | the 91 edge cases, under the story each belongs to | 91 |

Four rules that make it traceable:

- **Labels are FR IDs.** `FR-BOK-11`, `FR-AVL-08`. Then `JQL: labels = FR-AVL-08` answers "is this requirement built?" — the traceability matrix becomes a live query.
- **Components are the 21 modules** from `docs/02-architecture/03`.
- **Definition of Done** is §7 of `CLAUDE.md`.
- **The 37 user flows** become integration-test tickets in the phase where each completes.

Do not create a ticket per requirement. 356 tickets is noise; the 67 stories cover them and the labels carry the trace.

---

## 3. Then start building

Open Claude Code in this folder and work through `BUILD-PLAN.md`, one package per session, in order.

The very first one is `WP-00 — Repository and skeleton`. The first one that matters is `WP-05 — The reservation ledger`, because that single database constraint is what makes the whole product trustworthy.

---

## 4. How to drive Claude Code well on this project

**The thing that makes this fast is not typing speed — it is that the specification is complete.** Almost every project is slow because nobody can answer "what should happen when…". Here, someone already did, 356 times. Protect that advantage:

| Do | Don't |
|---|---|
| One work package per session | Let it wander into the next package "while it's there" |
| Make it read the documents the package names | Dump all 24 documents into context |
| Use plan mode for anything marked 🔴 and read the plan | Approve a plan you skimmed |
| Make it write the failing test first for invariant work | Accept code with tests written afterwards to match |
| Stop it the moment it invents behaviour the docs don't state | Accept a "sensible default" |
| Run `concurrencyTest` before every inventory commit | Trust that the constraint is still there |
| Commit per package, with the FR IDs in the message | Batch a phase into one commit |

**The single most valuable habit:** when Claude Code says something like *"I'll assume the venue can…"* or *"a reasonable default would be…"*, stop and check the FRS. Nine times out of ten the FRS already answers it, differently.

**The specific failure to watch for:** a test fails because of the exclusion constraint, and the fix looks like relaxing the constraint. It is never the fix. That constraint is invariant I1. §9 of `CLAUDE.md` lists the rest of these traps — read it once yourself, so you recognise them in a diff.

---

## 5. How long this actually takes

| | Without Claude Code | With Claude Code, 3 people |
|---|---|---|
| **To the launch bar** | 6–9 months | **≈ 14 weeks** |

What compresses: schema, endpoints, DTOs, migrations, test scaffolding, all three UIs, the admin panel — most of the volume.

What does not: the concurrency and cascade correctness work, the trust replay semantics, and your own review. That is thinking, not typing.

**One month is not achievable for the full 356 requirements.** If a month is a hard constraint, cut scope — `BUILD-PLAN.md` ends with the cut list, in the order I would cut it, and what must never be cut. Take that cut to the Product Owner as a Change Request, not to a sprint planning session.

---

## 6. If you only remember five things

1. `docs/01-product/` decides what happens. Never argue with it in code.
2. The exclusion constraint in `resource_reservation` is the product. Never weaken it.
3. Anything the Product calls atomic is one database transaction in one process.
4. History is append-only. Corrections are new rows, never edits.
5. When the spec is silent, stop and ask a human. Do not pick a default.
