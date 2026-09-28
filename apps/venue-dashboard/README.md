# @e7gz/venue-dashboard

Venue Dashboard — React + TypeScript + Vite, EN + AR (RTL). Owner: Abdallah (Clients lane).

## What exists (set up early by E7-11, with the PO's approval)

E7-11 needed a real app compiling against `@e7gz/api-client`, so the minimum was added ahead of E7-19:

| Piece | File |
|---|---|
| Vite + React 19 + TS project, ESLint, Vitest (happy-dom) | `package.json`, `vite.config.ts`, `tsconfig.json`, `eslint.config.js`, `index.html` |
| Configuration from env, no silent default backend | `src/config.ts`, `.env.example` |
| Shared API client created from that config | `src/api/index.ts` |
| EN/AR messages and `dir="rtl"` switching (just what the screen needs) | `src/i18n/index.ts`, `src/app/App.tsx` |
| One working screen: resources from `GET /api/v1/venue/resources`, with loading / success / error | `src/features/setup/ResourceList.tsx` (+ test) |

## Commands

```
pnpm mock                                           # repo root: Prism mock on http://127.0.0.1:4010
pnpm --filter @e7gz/venue-dashboard dev:mock        # http://localhost:5173 against the mock
pnpm --filter @e7gz/venue-dashboard lint | typecheck | test | build
```

Against a real backend: set `VITE_API_BASE_URL` (e.g. `http://localhost:8080`) and run `dev`. Venue login does not exist yet (E7-14 / E7-21), so `VITE_API_ACCESS_TOKEN` is a placeholder the mock accepts and the real backend will not.

## Still to do in E7-19 (not done here)

- Router, auth guard, and the layout shell from the prototype.
- Full EN/AR i18n with language resolution: account preference → browser storage → English (FR-VEN-40).
- Design tokens from `packages/tokens` (E7-18). There are no colours here yet, only logical-property spacing.
- "Switching the Dashboard to Arabic mirrors the layout" across the real layout. Today only the one screen is checked (`ResourceList.test.tsx`).

`ResourceList` is a starting point for E7-35. Reuse or replace it.

| Folder | Filled by |
|---|---|
| `src/features/auth/` | E7-21 (Auth screens) |
| `src/features/requests/` | E7-52 (Live requests with sound and Web Push) |
| `src/features/today/` | E7-53 (Walk-ins and today view) |
| `src/features/walk-in/` | E7-53 (Walk-ins and today view) |
| `src/features/schedule/` | E7-36 (Availability schedule) |
| `src/features/attendance/` | E7-63 (Attendance and Late Confirm) |
| `src/features/setup/` | E7-35 (Venue setup screens) |
| `src/features/operations/` | E7-85 (Operations screens) |
| `src/features/tournaments/` | E7-96 (Tournaments) |
| `src/features/reviews/` | E7-108 (Final RTL pass and reviews view) |
| `src/features/settings/` | E7-19 / later dashboard tasks (venue settings) |
