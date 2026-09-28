# @e7gz/api-client

The API contract and the typed TypeScript client the three apps use (E7-11, ADR-012, arch 09 §1).

| File | What it is |
|---|---|
| `openapi.json` | OpenAPI 3.1 generated from the backend controllers (springdoc). **Committed. Never edit by hand.** |
| `src/generated/schema.ts` | TS types generated from `openapi.json` (openapi-typescript). **Not committed**: rebuilt by `pnpm install` (prepare), `typecheck` and `build`, so consumers always compile against the committed contract. |
| `src/index.ts` | `createApiClient({ baseUrl, getAccessToken, fetch? })`: a typed `fetch` client (openapi-fetch) that adds `Authorization: Bearer` when there is a token. |
| `scripts/` | `api-gen.mjs` (`pnpm api:gen`), `mock.mjs` (`pnpm mock`), `test-chain.mjs` (`pnpm test:chain`) |

## Prerequisites

- Node ≥ 22.13 (`.nvmrc`; eslint 10 sets the minimum) and pnpm 10.34.5. Run `corepack enable` once, or prefix commands with `corepack`. pnpm refuses to install a package that does not support the running Node version (`engineStrict`).
- JDK 21 is downloaded by Gradle. Docker must be running for anything that starts the backend (`pnpm api:gen`, `pnpm test:chain`), because the backend starts against a Testcontainers PostGIS database.

## Commands (from the repo root)

| Command | What it does |
|---|---|
| `pnpm api:gen` | After a controller or DTO change: Gradle `generateOpenApi` writes `openapi.json` from the backend, then the TS types are regenerated. Output is deterministic (sorted keys, LF). Commit `openapi.json`. |
| `pnpm mock` | Prism mock of the committed `openapi.json` (the Sprint 1 contract — see `SPRINT-1-CONTRACT.md`) on http://127.0.0.1:4010. Venue and admin routes need any `Authorization: Bearer <value>`; customer discovery is public. `pnpm mock --host 0.0.0.0` lets emulators and phones reach it. Answers with each response's `example`, validates requests, 404s unknown paths. Prism options pass through (`pnpm mock --port 4020`, `--dynamic`); `--spec <file>` serves another OpenAPI file. Stopping the launcher stops the server. It exits non-zero if the contract is missing, has no endpoints, or Prism fails. |
| `pnpm test:chain` | Backend → OpenAPI → TypeScript chain (needs Docker): sample Spring controllers/DTOs (backend `OpenApiSampleContractTest`) → `backend/build/generated-contracts/sample.openapi.json` → `test/generated/sample.ts` → `tsc -p tsconfig.chain.json` (positive and `@ts-expect-error` negative cases) → `node --test test/chain/` (the wrapper against injected fetch, and the `pnpm mock` launcher on that contract). |
| `pnpm -r lint` / `typecheck` / `build` / `test` | Package checks. `typecheck` also compiles `test/consumer.ts`, which imports the public `@e7gz/api-client` export the way an app will. `test` runs the launcher on the committed contract (documented Sprint 1 endpoints: status, JSON content type, envelope and field shape; Bearer required; request validation; 404; shutdown; failure exit). |

CI (`.github/workflows/ci.yml`):
- `backend` runs `./gradlew check`, which fails when `openapi.json` does not match the controllers.
- `backend` then uploads the sample contract.
- `contract-chain` downloads the sample contract and runs `test:chain`.

## Use in an app

```ts
import { createApiClient } from "@e7gz/api-client";

const api = createApiClient({ baseUrl: config.apiBaseUrl, getAccessToken: () => session.accessToken });
const { data, error, response } = await api.POST("/api/v1/…", {
  params: { path: { … } },
  body: { … },
  headers: { "Idempotency-Key": key }, // the caller creates it, and reuses it when it retries (arch 09 §1.2)
  signal, // optional AbortSignal
});
```

- Paths, parameters, request and response bodies and enum values are typed from the contract. A path, a missing required field or a misspelled enum value does not compile.
- `error` is the documented error body (`{ error: { code, … } }`), and the app maps `code` to its own text (arch 09 §1.3, §1.5).
- A network failure or a cancellation **rejects**; it never resolves as `data`.
- The client never retries and never creates an Idempotency-Key.

## Status of the E7-11 acceptance items

| Item | Evidence |
|---|---|
| A new endpoint regenerates the TS client | The backend check `OpenApiContractTest` fails when `openapi.json` is stale, and `pnpm api:gen` regenerates it deterministically. `test:chain` proves the Spring → OpenAPI → TypeScript chain on sample controllers. |
| The apps compile against it | All three apps depend on `@e7gz/api-client` (`workspace:*`), create the client from env config, call a documented endpoint in a screen with loading / success / error states, and run real lint, typecheck, test and build (Vite for both web apps; Expo export for web + Android). Setup started early for E7-19 / E7-20; see each app's README for what those tasks still own. |
| `pnpm mock` serves the current contract | `test/mock-launcher.test.mjs` starts the same launcher with no `--spec`: `GET /api/v1/customer/areas` and `/customer/venues` return 200 JSON with the envelope; `/admin/areas` and `/venue/resources` return 401 without a Bearer header and 200 with one; a protected write is refused with 422 when `ownerPassword` is missing; unknown paths 404; launcher failures exit non-zero and stopping it stops the server. These are Prism **shape** checks: presence of a Bearer header or a password field, never real authentication (E7-14) or a real Owner Password check (E7-24). |

Owner: Sayed (author) + Abdallah (consumer). Regenerated by every `Sx-P0` contract task.
