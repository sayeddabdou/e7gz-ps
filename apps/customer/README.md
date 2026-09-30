# @e7gz/customer

Customer app — React Native + TypeScript (Expo SDK 57). Owner: Abdallah (Clients lane).

## What exists

E7-11 needed a real app compiling against `@e7gz/api-client`, so the minimum was added ahead of E7-20:

| Piece | File |
|---|---|
| Expo managed project (SDK 57, React Native 0.86, react-native-web), ESLint, Jest (`jest-expo`) | `package.json`, `app.json`, `index.ts`, `tsconfig.json`, `eslint.config.js` |
| Configuration from `EXPO_PUBLIC_API_BASE_URL`, no silent default backend | `src/config.ts`, `.env.example` |
| Shared API client with secure token storage and one-shot refresh handling | `src/api/index.ts`, `src/auth/tokenStore.ts` |
| English strings in a resource file | `src/i18n/en.ts` |
| One working screen: active areas from the public `GET /api/v1/customer/areas`, with loading / success / error | `src/features/discovery/AreaList.tsx` (+ test) |
| Bottom-tab shell matching the prototype: Home, Tournaments, Bookings, Profile | `src/app/App.tsx` (+ test) |

## Commands

```
pnpm mock --host 0.0.0.0                       # repo root; 0.0.0.0 so emulators and phones can reach it
pnpm --filter @e7gz/customer web:mock          # the app in a browser against the mock
pnpm --filter @e7gz/customer start:mock        # Expo dev server (Expo Go / emulator)
pnpm --filter @e7gz/customer lint | typecheck | test | build   # build = expo export for web + Android
```

`EXPO_PUBLIC_API_BASE_URL` decides which backend is used. It must be reachable from the device:

| Where the app runs | Mock URL |
|---|---|
| Web, or the iOS simulator on this machine | `http://127.0.0.1:4010` (what `start:mock` / `web:mock` set) |
| Android emulator | `http://10.0.2.2:4010` |
| Physical phone with Expo Go on the same Wi-Fi | `http://<this computer's LAN IP>:4010` |

For the Android emulator or a phone, set the variable yourself, for example `EXPO_PUBLIC_API_BASE_URL=http://10.0.2.2:4010 pnpm --filter @e7gz/customer start`. For a local backend, use the same hosts on port 8080.

The API contract does not define the customer refresh endpoint yet. E7-21 supplies the
`refreshAccessToken` callback when it adds authentication; the transport already guarantees one
retry only and clears secure credentials when refresh or that retry fails.

`AreaList` is a starting point for the area filter of discovery (E7-38).

| Folder | Filled by |
|---|---|
| `src/features/auth/` | E7-21 (Auth screens) |
| `src/features/discovery/` | E7-38 (Discovery, map and venue page) |
| `src/features/booking/` | E7-50 (Book a room) |
| `src/features/my-bookings/` | E7-51 (My Bookings and push), E7-87 (Operational states) |
| `src/features/late-confirm/` | E7-62 (Late Confirm and status) |
| `src/features/profile/` | E7-64 (Profile and account), E7-74 (Standing), E7-106 (Delete account) |
| `src/features/rewards/` | E7-74 (Standing, points, wallet, referrals) |
| `src/features/tournaments/` | E7-95 (Tournaments) |
| `src/features/reviews/` | E7-106 (Reviews, reporting) |
| `src/features/notifications/` | E7-51 (My Bookings and push) |
