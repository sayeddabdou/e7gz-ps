## Jira

E7-<!-- number --> — <!-- task title -->

## What changed

<!-- One short paragraph. Which module(s)/folder(s)? -->

## How it was tested

<!-- Tests added (map to the task's "Done when" items), commands run, screenshots for UI (EN + AR for the dashboard). -->

## Checklist (DEVELOPER-GUIDE.md §7)

- [ ] Every *Done when* item of the task is a passing test
- [ ] `./gradlew check` and/or `pnpm -r lint typecheck build` green locally and in CI
- [ ] If an endpoint changed: `pnpm api:gen` run and `openapi.json` committed
- [ ] Code only in the task's folders (DEVELOPER-GUIDE.md §3); no edits to `docs/` or `V1__baseline.sql`
- [ ] No hard-coded rule text, status names from prototypes, colours, `Instant.now()`, `float`/`double` money
- [ ] Dashboard screens checked in Arabic (RTL)
- [ ] Reviewer: Sayed ↔ Amr for backend; Sayed for apps; contract PRs also need Abdallah
- [ ] Jira moved to Done with the PR link
