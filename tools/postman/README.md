# Postman — Sprint 0 API tests

Everything that works at the end of Sprint 0 (registration, phone OTP, login, refresh rotation, logout for the three
audiences) plus the security rules around it. **83 requests, 435 assertions — all green against `main` @ `6ba7e9f`** (venue and admin sessions use the E7-22 cookie session; the E7-22 collection tests it in depth).

| File | What |
|---|---|
| `E7GZ-PS-Sprint0.postman_collection.json` | The collection (import into Postman) |
| `E7GZ-PS-local.postman_environment.json` | Environment: `baseUrl = http://localhost:8080` |
| `seed-test-accounts.sql` | Venue + admin test accounts (no API creates them yet). Local DB only |

## 0. Team accounts for trying the apps by hand

`seed-team-accounts.sql` creates three people, each with a **venue**, a **Platform Admin** and a **customer** account,
all with the same password. Every venue's **Owner PIN is `1234`** (the Owner Password is a 4-digit PIN, PCR-006). Local database only.

```bash
docker compose exec -T postgres psql -U postgres -d e7gz_ps < tools/postman/seed-team-accounts.sql
```

| Person | Venue Dashboard login | Admin login | Customer login | Password |
|---|---|---|---|---|
| Abdallah | `abdallah` | `abdallah@e7gz.test` | `abdallah` or `+201000000001` | `Abdallah123` |
| Amr | `amr` | `amr@e7gz.test` | `amr` or `+201000000002` | `Amr12345` |
| Sayed | `sayed` | `sayed@e7gz.test` | `sayed` or `+201000000003` | `Sayed123` |

Venues: Abdallah Gaming Lounge, Amr PlayStation Club, Sayed Arena (area Nasr City, ONBOARDING). Re-running the file
also clears login and Owner Password lockouts. The Postman collections below use their own `postman.*` accounts from
`seed-test-accounts.sql`; run that file only when you run the collections.

## 1. Start the backend locally

```bash
docker compose up -d
```

PowerShell (from `backend/`):

```powershell
$env:E7GZ_CORS_ALLOWED_ORIGINS = "http://localhost:5173,http://localhost:5174"
$env:E7GZ_JWT_SECRET = [Convert]::ToBase64String((1..64 | ForEach-Object { Get-Random -Maximum 256 }))
./gradlew bootRun --args='--spring.profiles.active=api'
```

Git Bash (from `backend/`):

```bash
E7GZ_CORS_ALLOWED_ORIGINS="http://localhost:5173,http://localhost:5174" E7GZ_JWT_SECRET="$(python -c 'import secrets,base64;print(base64.b64encode(secrets.token_bytes(64)).decode(),end="")')" ./gradlew bootRun --args='--spring.profiles.active=api'
```

Keep that console visible: the OTP codes are printed there (`DEV SMS to +20…: OTP 123456`).

## 2. Seed the venue and admin accounts (once, and before each re-run)

```bash
docker compose exec -T postgres psql -U postgres -d e7gz_ps < tools/postman/seed-test-accounts.sql
```

Password for every seeded account: `Postman-Test-2026`

| Account | Login | Expected |
|---|---|---|
| Venue (enabled) | `postman.venue` | logs in |
| Venue (disabled) | `postman.venue.disabled` | 403 even with the right password |
| Admin (active) | `postman.admin@e7gz.test` | logs in |
| Admin (inactive) | `postman.inactive@e7gz.test` | 403 even with the right password |

Re-running the seed also clears the login rate-limit counters (10 attempts / 15 min per login + IP), so you can run the
collection again straight away.

## 3. Run

1. Import both JSON files into Postman, select the **E7GZ PS — local** environment.
2. Run the whole collection with the **Collection Runner** (folders 2 and 7 loop with `setNextRequest`, which only works
   in the Runner).
3. **Manual step for phone verification:** after the run, copy customer A's code from the backend console into the
   collection variable `otpCode` and send *"Verify customer A's phone with the SMS code"* (folder 3) by hand, within
   5 minutes. When `otpCode` is empty that request is skipped, so the run stays green.

Command line (same result):

```bash
npx newman run tools/postman/E7GZ-PS-Sprint0.postman_collection.json -e tools/postman/E7GZ-PS-local.postman_environment.json
```

## What is covered

| Folder | Checks |
|---|---|
| 0 · Platform & security basics | Swagger, OpenAPI and actuator are not public (401); correlation ID echoed / replaced when malformed; unknown API route without a token is 401 (no route discovery) |
| 1 · Public discovery | Public stubs answer without a token (501 until E7-23 / E7-34); required / invalid query params → 400; a bad Bearer token is ignored on public routes |
| 2 · Registration & OTP | Validation (phone, password rule, email, empty body); 201 register; `USERNAME_TAKEN` (case/space-insensitive); `PHONE_TAKEN` (any phone format); `OTP_RESEND_TOO_SOON`; unknown phone 404; malformed code 400; unknown challenge `OTP_INVALID`; 5 wrong codes then `OTP_ATTEMPTS_EXCEEDED` |
| 3 · Customer sessions | Unverified customer can log in; login by username or phone (any format); wrong password and unknown user give the same 401; JWT is HS512, 15 min, only `sub/typ/jti/iat/exp/ver`; refresh rotates; **reusing an old refresh token revokes the whole chain**; a customer refresh token sent as the venue cookie is refused (401); logout; refresh after logout 401; phone verification (manual code) |
| 4 · Route groups | No / garbage / tampered token 401; customer token on venue or admin routes 403 |
| 5 · Venue | Wrong password 401; login (trimmed, case-insensitive) returns the access token in the body and the refresh token only in an HttpOnly `e7gz_venue_refresh` cookie; venue token accepted on venue routes, 403 on admin and customer routes; refresh and logout with the cookie + `Origin` + `X-Requested-With: e7gz` (rotation, cookie cleared on logout, 401 after); **disabled account 403** |
| 6 · Admin | Same as venue for the admin audience (`e7gz_admin_refresh` cookie, 12 h); **inactive admin 403** |
| 7 · Rate limit | 10 wrong logins 401, the 11th `RATE_LIMITED` (429) |
| Every response | `X-Correlation-Id` is a UUID and equals `meta.correlationId`; error bodies have a code and never a message or stack trace |

## Not testable from Postman yet

- **Sprint 1 endpoints** (areas, venues, resources, pricing, opening hours, owner password, venue accounts): still 501
  stubs. When each task merges, change its test from 501 to the real result.
- **OTP expiry (5 min)** and **5 sends per hour**: need waiting (5 min / 60 s between sends). Covered by the backend
  integration tests (`CustomerRegistrationHttpTest`).
- **Account disabled mid-session** (token stops working at once): needs a DB update between requests; covered by
  `AccessTokenSecurityTest`. To try by hand: log in as `postman.venue`, run
  `UPDATE iam.venue_account SET account_status='DISABLED' WHERE login_identifier='postman.venue';`, call
  `GET /api/v1/venue/resources` → 401. Re-running the seed does not undo this; set `account_status` back to
`ENABLED` with another `UPDATE`.

## E7-24 — venue account, Owner Password, venue photos, settings

`E7GZ-PS-E7-24.postman_collection.json` — **45 requests, 139 assertions, all green** on the E7-24 branch. Start the
backend with **both** profiles (`--spring.profiles.active=api,worker`), seed, then run **from the repository root**
(the photo upload reads `tools/postman/fixtures/venue-photo.jpg`):

```bash
postman collection run tools/postman/E7GZ-PS-E7-24.postman_collection.json -e tools/postman/E7GZ-PS-local.postman_environment.json --working-dir .
```

Each run creates its own area, two venues and a venue account through the admin API, so it can be re-run at once.
It covers: account creation (weak password, a non-PIN Owner Password, duplicate account, taken login), settings, photos
with no Owner PIN (PCR-006), the venue's own Owner PIN change (4 digits, no unlock between two changes), the lockout
after 5 wrong PINs (429), and the admin reset, disable and enable buttons.

The seed now also gives `postman.venue` an Owner PIN (`1234`), and re-seeding clears the Owner Password lockout
counters.

## E7-25 — opening hours

`E7GZ-PS-E7-25.postman_collection.json`: **39 requests, 140 assertions, all green** on the E7-25 branch (run twice
in a row). Backend with the `api` profile, seed, then:

```bash
postman collection run tools/postman/E7GZ-PS-E7-25.postman_collection.json -e tools/postman/E7GZ-PS-local.postman_environment.json
```

Each run creates its own area, two venues and two venue accounts, so it can be re-run at once.
It covers:
- the empty state before the first save (version 0, every day CLOSED);
- 401 with no token and 403 with an admin token;
- validation: overlaps (including the Sunday → Monday wrap), touching sessions, the full-day rule, duplicate or malformed days;
- preview → commit, with no Owner PIN needed;
- the preview token: a different proposal, a made-up token, or another venue's token gives 409 and saves nothing;
- three successive saves: plain, overnight with a custom Friday, and full day;
- one venue never seeing another's hours.

Not covered here: affected bookings and the tournament block need bookings and tournaments, which have no API yet.
They, the session rows and the DST cases are in the backend tests (`OpeningHoursHttpTest`, `SessionGeneratorTest`,
`SessionPlanTest`).
