# Sprint 6 contract (E7-88)

Sprint 6 is tournaments. Unlike earlier sprints, the **writes were built directly** by the Engine lane, without stubs first: E7-91 (create, #135), E7-92 (approve, reject, #138) and E7-93 (join, leave, remove, cancel, operational termination, admin cancel, #139). E7-88 checked them against arch 09 §3.4, §4.1, §4.3 and §5 and keeps them as they are. This contract adds only what was still missing: the **read routes**. They are **stubs**: the controller answers `501` until a task implements it, and `openapi.json` describes the intended success and error responses. Clients build against `pnpm mock` meanwhile.

The read routes sit in their own controllers (`*TournamentQueryController`) so this contract does not touch the controllers of the open PRs #138 and #139. No business logic, persistence or migration is part of E7-88. `Sprint6StubHttpTest` checks that each stub answers `501` through the real security chain; the task that fills a stub replaces its line with its own tests. `Sprint6ContractTest` checks the rules below on the committed contract.

Sources: arch 09 §1, §3.4, §4.1, §4.3, §5 · FRS §4.10 (FR-TRN-01…FR-TRN-33), F-25…F-29 · PCR-001 · PO decision of 26 Nov 2026 (below).

## Included

### Writes (already implemented; not changed by E7-88)

| Surface | Endpoint | Task |
|---|---|---|
| Customer | `POST /customer/tournaments` | E7-91 |
| Customer | `POST /customer/tournaments/{id}/join` · `/join-private` · `/{id}/leave` · `/{id}/participants/{userId}/remove` · `/{id}/cancel` | E7-93 |
| Venue | `POST /venue/tournaments` | E7-91 |
| Venue | `POST /venue/tournaments/{id}/approve` · `/reject` | E7-92 |
| Venue | `POST /venue/tournaments/{id}/cancel` `{ownerPassword?}` · `/operational-termination` `{ownerPassword, reason}` | E7-93 |
| Admin | `POST /admin/tournaments/{id}/cancel` `{reason}` | E7-93 |

### Reads (stubs, 7 operations)

| Surface | Endpoint | Controller | Basis |
|---|---|---|---|
| Customer | `GET /customer/tournaments?areaId=&activity=&cursor=&limit=` — public Discover | `tournament/web/CustomerTournamentQueryController` | arch 09 §3.4 |
| Customer | `GET /customer/tournaments/mine?ended=&cursor=&limit=` | same | PO 26 Nov 2026 |
| Customer | `GET /customer/tournaments/{id}` | same | PO 26 Nov 2026 |
| Venue | `GET /venue/tournaments?ended=&status=&cursor=&limit=` | `tournament/web/VenueTournamentQueryController` | arch 09 §4.1 |
| Venue | `GET /venue/tournaments/{id}` | same | PO 26 Nov 2026 |
| Admin | `GET /admin/tournaments?venueId=&status=&hostType=&cursor=&limit=` | `tournament/web/AdminTournamentQueryController` | arch 09 §5 |
| Admin | `GET /admin/tournaments/{id}` | same | PO 26 Nov 2026 |

## 1. PO decision (26 Nov 2026)

| # | Question | Decision | In the contract |
|---|---|---|---|
| D1 | arch 09 has only the three lists, but FR-TRN-07 lets the creator and the venue view a pending request, E7-95 shows the customer's own tournaments and their status, E7-96 has a participants view, E7-97 a detail screen | **The three lists plus a detail on each surface, plus the customer's own list** | `GET …/{id}` on all three surfaces and `GET /customer/tournaments/mine`; arch 09 §3.4, §4.1 and §5 list them |

## 2. Conventions (resolved from the documents)

| Topic | Convention and basis |
|---|---|
| Who sees what | Discover: only PUBLIC tournaments in WAITING_FOR_PLAYERS or CONFIRMED with `now < startAt` (FR-TRN-06, FR-TRN-07). Customer detail: the creator and anyone with a participation, in any state; any customer for an accepted PUBLIC tournament. Everything else is `404 NOT_FOUND`, never `403`, so a private or pending tournament is not revealed. The venue sees only its own tournaments. |
| Invite code | Never in a list row. In the detail: to the creator of a PRIVATE tournament once the venue accepted it, and to the venue (copy and share, FR-TRN-06); `null` otherwise and while pending (FR-TRN-07). |
| Participants | In the detail: to the organizer (FR-TRN-12: they remove participants), the venue and the Admin; `null` for other customers. Username, status and `joinedAt` only: never a phone or an email (arch 09 §1.1). `INELIGIBLE` is shown as is; its cause (`BAN` / `ACCOUNT_DELETED`) is never answered (arch 09 §3.4, PCR-001). |
| Capacity | `capacityOccupiedCount` = JOINED plus the slots a banned participant keeps before the start (FR-TRN-12, FR-TRN-26), the number Join checks. A full tournament stays in Discover. |
| Timers | `approvalExpiresAt` (pending only) and `checkpointAt` are instants; show countdowns from them and `meta.serverTime`, never device time. |
| Lists | Cursor paging (arch 09 §1.2). Customer `mine` and venue list: `ended=false` (default) not yet ended, soonest `startAt` first; `ended=true` COMPLETED · CANCELLED · REJECTED, newest first. Admin list: newest `createdAt` first. |
| Separate DTOs | Customer routes use only `Customer*` schemas, venue routes `Venue*`, admin routes `Admin*` (plus the envelope, `Money` and the shared code enums) — arch 09 §1.1. `Sprint6ContractTest` checks this for every tournament operation, the writes included. |
| Live updates | New and changed tournaments reach the Dashboard through the existing `TOURNAMENT` signal of the venue stream (E7-44); the Dashboard then re-reads the list. No new signal. |

## 3. For the Clients lane

- **E7-95 (Customer app)**: Discover from `GET /customer/tournaments`, "My tournaments" from `/mine`, the detail from `/{id}`. The share-code button and the participants list (with Remove) only for the creator (`createdByMe`). Show a full tournament as full, not hidden.
- **E7-96 (Dashboard)**: requests on top from `GET /venue/tournaments` (`status=PENDING_VENUE_APPROVAL` for the request list), countdown from `approvalExpiresAt`; the participants view and the invite code from `/{id}`. Ask for the Owner PIN on cancel only when the server answers `423 OWNER_PASSWORD_REQUIRED` (a CONFIRMED tournament); operational termination always asks.
- **E7-97 (Admin)**: list and detail from `GET /admin/tournaments` and `/{id}`; cancel needs a reason.

## 4. Not decided yet

- **Which task implements the reads.** No Sprint 6 task names them (E7-91…E7-94 are writes and cascades). The tournament module is the Engine lane's.
