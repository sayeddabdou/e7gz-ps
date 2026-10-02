# @e7gz/admin-web

Platform Admin — React + TypeScript + Vite, English. Owner: Abdallah (Clients lane).

## What exists (set up early by E7-11, with the PO's approval)

E7-11 needed a real app compiling against `@e7gz/api-client`, so the minimum was added ahead of E7-19:

| Piece | File |
|---|---|
| Vite + React 19 + TS project, ESLint, Vitest (happy-dom) | `package.json`, `vite.config.ts`, `tsconfig.json`, `eslint.config.js`, `index.html` |
| Configuration from env, no silent default backend | `src/config.ts`, `.env.example` |
| Shared API client created from that config | `src/api/index.ts` |
| English strings in one place | `src/i18n/messages.ts` |
| One working screen: areas from `GET /api/v1/admin/areas`, with loading / success / error | `src/features/areas/AreaList.tsx` (+ test) |

## Commands

```
pnpm mock                                     # repo root: Prism mock on http://127.0.0.1:4010
pnpm --filter @e7gz/admin-web dev:mock        # http://localhost:5174 against the mock
pnpm --filter @e7gz/admin-web lint | typecheck | test | build
```

Against a real backend: set `VITE_API_BASE_URL` (e.g. `http://localhost:8080`) and run `dev`. Admin authentication uses the backend's HttpOnly refresh cookie; the short-lived access token stays in memory and is never configured or stored by the browser app.

For local cookie-session testing, start the backend with both web origins allowed:

```powershell
$env:E7GZ_CORS_ALLOWED_ORIGINS="http://localhost:5173,http://localhost:5174"
```

## Shell foundation

- The layout shell, navigation for the 20 admin modules, and auth guard were added in E7-19.
- Shared design tokens come from `packages/tokens` (E7-18).
- E7-21 restores the cookie session on page load and provides login/logout without browser token storage.

`AreaList` is a starting point for E7-37 (areas list, create, deactivate). Reuse or replace it.

| Folder | Filled by |
|---|---|
| `src/features/auth/` | E7-21 (Auth screens) |
| `src/features/dashboard/` | E7-19 / E7-99 (Admin home) |
| `src/features/areas/` | E7-37 (Areas, venues, venue accounts) |
| `src/features/venues/` | E7-37 (Areas, venues, venue accounts) |
| `src/features/venue-accounts/` | E7-37 (Venue accounts), E7-86 (Disable venue account) |
| `src/features/users/` | E7-99 (Remaining admin modules) |
| `src/features/trust/` | E7-75 (Trust, bans, Verified Player, points, configuration) |
| `src/features/bans/` | E7-75 |
| `src/features/verified-players/` | E7-75 |
| `src/features/points/` | E7-75 |
| `src/features/configuration/` | E7-75 |
| `src/features/owner-review/` | E7-86 (Owner review, disable, alerts) |
| `src/features/emergencies/` | E7-86 |
| `src/features/alerts/` | E7-86 |
| `src/features/tournaments/` | E7-97 (Tournaments) |
| `src/features/reviews/` | E7-107 (Moderation, deletion, reports) |
| `src/features/account-deletion/` | E7-107 |
| `src/features/reports/` | E7-107 |
| `src/features/audit/` | E7-99 (Remaining admin modules) |
| `src/features/notifications/` | E7-99 (Remaining admin modules) |
