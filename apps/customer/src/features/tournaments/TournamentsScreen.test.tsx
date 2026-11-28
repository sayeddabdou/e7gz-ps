import { fireEvent, render, screen, waitFor } from "@testing-library/react-native";
import { createCustomerApiClient } from "../../api";
import { TournamentsScreen } from "./TournamentsScreen";

const config = { apiBaseUrl: "http://127.0.0.1:4010" };
const meta = { correlationId: "c-1", serverTime: "2026-12-01T10:00:00Z" };
const listing = {
  tournamentId: "11111111-1111-4111-8111-111111111111",
  venueId: "22222222-2222-4222-8222-222222222222",
  venueName: "Full Arena",
  hostType: "PLAYER",
  status: "WAITING_FOR_PLAYERS",
  activity: "PLAYSTATION",
  playMode: "SINGLE",
  capacity: 8,
  capacityOccupiedCount: 8,
  startAt: "2026-12-24T16:00:00Z",
  endAt: "2026-12-24T18:00:00Z",
  entryFee: { amount: "0.00", currency: "EGP" },
  prize: { amount: "0.00", currency: "EGP" },
  createdByMe: true,
  myParticipantStatus: "INELIGIBLE",
};

const area = { id: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", name: "Nasr City", nameAr: null };
const venue = {
  id: "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
  name: "Abdallah Gaming Lounge",
  area,
  distanceMeters: null,
  imageUrl: null,
  nearestAvailableAt: null,
  startingPrice: null,
  averageRating: null,
  reviewCount: 0,
};
const venueDetails = {
  id: venue.id,
  name: venue.name,
  area,
  address: "12 Test Street",
  location: { lat: 30.05, lng: 31.33 },
  distanceMeters: null,
  imageUrls: [],
  activities: ["PLAYSTATION"],
  selectedActivity: "PLAYSTATION",
  resources: [{ id: "cccccccc-cccc-4ccc-8ccc-cccccccccccc", name: "Room 1", consoleType: "PS5" }],
  prices: [],
  openingHours: [],
  nearestAvailableAt: null,
  openNow: true,
  opensAt: null,
  bookingEnabled: true,
  averageRating: null,
  reviewCount: 0,
};

const participantUserId = "44444444-4444-4444-8444-444444444444";

function detailFixture(overrides: Record<string, unknown> = {}) {
  return {
    ...listing,
    capacityOccupiedCount: 1,
    checkpointAt: "2026-12-24T15:00:00Z",
    approvalExpiresAt: null,
    terminalReason: null,
    createdByMe: false,
    myParticipantStatus: null,
    creatorParticipates: true,
    inviteCode: null,
    participants: null,
    rooms: [{ resourceId: "33333333-3333-4333-8333-333333333333", name: "Room 1", consoleType: "PS5" }],
    durationMinutes: 120,
    visibility: "PUBLIC",
    ...overrides,
  };
}

function actionClient({
  detail = detailFixture(),
  failActionAttempts = 0,
  actionError,
  onAction,
}: {
  detail?: ReturnType<typeof detailFixture>;
  failActionAttempts?: number;
  actionError?: { code: string; status: number };
  onAction?: (path: string, key: string | null) => void;
} = {}) {
  let actionAttempts = 0;
  return createCustomerApiClient(config, async (request: Request) => {
    const url = new URL(request.url);
    if (request.method === "POST") {
      actionAttempts += 1;
      onAction?.(url.pathname, request.headers.get("Idempotency-Key"));
      if (actionAttempts <= failActionAttempts) throw new TypeError("network unavailable");
      if (actionError) return new Response(JSON.stringify({ error: { code: actionError.code }, meta }), { status: actionError.status, headers: { "Content-Type": "application/json" } });
      const participantStatus = url.pathname.endsWith("/leave") ? "LEFT" : url.pathname.includes("/remove") ? "REMOVED" : "JOINED";
      const data = url.pathname.endsWith("/cancel")
        ? { tournamentId: listing.tournamentId, status: "CANCELLED", terminalReason: "CREATOR_CANCELLED" }
        : { tournamentId: listing.tournamentId, tournamentStatus: detail.status, participantStatus, userId: participantUserId };
      return new Response(JSON.stringify({ data, meta }), { status: 200, headers: { "Content-Type": "application/json" } });
    }
    const data = url.pathname.endsWith("/areas") ? []
      : url.pathname.endsWith("/account-standing") ? {
          consecutiveNoShows: 0,
          penaltyStage: 0,
          currentThreshold: 3,
          currentBan: null,
          verifiedPlayer: { qualification: "QUALIFIED", completedBookings: 10, requiredBookings: 10, status: "VERIFIED" },
        }
        : url.pathname.endsWith(listing.tournamentId) ? detail
          : { items: [{ ...listing, capacityOccupiedCount: detail.capacityOccupiedCount, myParticipantStatus: detail.myParticipantStatus }], nextCursor: null };
    return new Response(JSON.stringify({ data, meta }), { status: 200, headers: { "Content-Type": "application/json" } });
  });
}

async function openTournamentDetail(api = actionClient()) {
  render(<TournamentsScreen api={api} />);
  fireEvent.press(await screen.findByText("Full Arena"));
  await screen.findByText("Join checkpoint");
}

function client(verified: boolean) {
  return createCustomerApiClient(config, async (request: Request) => {
    const url = new URL(request.url);
    const data = url.pathname.endsWith("/areas") ? []
      : url.pathname.endsWith("/account-standing") ? {
          consecutiveNoShows: 0,
          penaltyStage: 0,
          currentThreshold: 3,
          currentBan: null,
          verifiedPlayer: { qualification: "NOT_QUALIFIED", completedBookings: 0, requiredBookings: 10, status: verified ? "VERIFIED" : "NOT_VERIFIED" },
        }
        : url.pathname.endsWith(listing.tournamentId) ? {
            ...listing,
            checkpointAt: "2026-12-01T09:00:00Z",
            approvalExpiresAt: null,
            terminalReason: null,
            creatorParticipates: true,
            inviteCode: null,
            participants: null,
            rooms: [{ resourceId: "33333333-3333-4333-8333-333333333333", name: "Room 1", consoleType: "PS5" }],
            durationMinutes: 120,
            visibility: "PUBLIC",
          }
          : { items: [listing], nextCursor: null };
    return new Response(JSON.stringify({ data, meta }), { status: 200, headers: { "Content-Type": "application/json" } });
  });
}

function creationClient(onCreate?: (body: unknown, key: string | null) => void, serverTime = meta.serverTime, failCreateAttempts = 0) {
  let createAttempts = 0;
  return createCustomerApiClient(config, async (request: Request) => {
    const url = new URL(request.url);
    if (request.method === "POST" && url.pathname.endsWith("/customer/tournaments")) {
      createAttempts += 1;
      onCreate?.(await request.json(), request.headers.get("Idempotency-Key"));
      if (createAttempts <= failCreateAttempts) throw new TypeError("network unavailable");
      return new Response(JSON.stringify({ data: { tournamentId: listing.tournamentId }, meta: { ...meta, serverTime } }), { status: 201, headers: { "Content-Type": "application/json" } });
    }
    const data = url.pathname.endsWith("/areas") ? [area]
      : url.pathname.endsWith("/account-standing") ? {
          consecutiveNoShows: 0,
          penaltyStage: 0,
          currentThreshold: 3,
          currentBan: null,
          verifiedPlayer: { qualification: "QUALIFIED", completedBookings: 10, requiredBookings: 10, status: "VERIFIED" },
        }
        : url.pathname === `/api/v1/customer/venues/${venue.id}` ? venueDetails
          : url.pathname.endsWith(listing.tournamentId) ? {
              ...listing,
              checkpointAt: "2026-12-01T09:00:00Z",
              approvalExpiresAt: null,
              terminalReason: null,
              creatorParticipates: true,
              inviteCode: null,
              participants: null,
              rooms: [{ resourceId: venueDetails.resources[0]!.id, name: "Room 1", consoleType: "PS5" }],
              durationMinutes: 120,
              visibility: "PUBLIC",
            }
          : url.pathname.endsWith("/venues") ? { items: [venue], nextCursor: null }
            : { items: [], nextCursor: null };
    return new Response(JSON.stringify({ data, meta: { ...meta, serverTime } }), { status: 200, headers: { "Content-Type": "application/json" } });
  });
}

async function openCreateForm(onCreate?: (body: unknown, key: string | null) => void, serverTime?: string, failCreateAttempts = 0) {
  render(<TournamentsScreen api={creationClient(onCreate, serverTime, failCreateAttempts)} />);
  fireEvent.press(await screen.findByRole("button", { name: "Create tournament" }));
  fireEvent.press(await screen.findByRole("button", { name: "Nasr City" }));
  fireEvent.press(await screen.findByRole("button", { name: venue.name }));
  fireEvent.press(await screen.findByRole("button", { name: "Room 1" }));
}

describe("TournamentsScreen", () => {
  it("offers creation only to a Verified Player and keeps full tournaments visible", async () => {
    const { unmount } = render(<TournamentsScreen api={client(false)} />);
    expect(await screen.findByText("Full Arena")).toBeTruthy();
    expect(screen.getByText("Full")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Create tournament" })).toBeNull();
    unmount();

    render(<TournamentsScreen api={client(true)} />);
    expect(await screen.findByRole("button", { name: "Create tournament" })).toBeTruthy();
  });

  it("shows an opaque Ineligible status and hides creator cancellation after the checkpoint", async () => {
    render(<TournamentsScreen api={client(true)} />);
    fireEvent.press(await screen.findByText("Full Arena"));

    await waitFor(() => expect(screen.getByText("Ineligible")).toBeTruthy());
    expect(screen.queryByText(/ban/i)).toBeNull();
    expect(screen.queryByRole("button", { name: "Cancel tournament" })).toBeNull();
  });

  it("shows friendly date, time, and duration choices instead of raw scheduling fields", async () => {
    await openCreateForm();

    expect(await screen.findByText("Booking date")).toBeTruthy();
    expect(screen.getByText("Start time")).toBeTruthy();
    expect(screen.getByText("Duration")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Today, Tuesday 1 December" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "6:00 PM" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "1 hour" })).toBeTruthy();
    expect(screen.queryByText("Start date and time (ISO)")).toBeNull();
    expect(screen.queryByText("Duration in minutes")).toBeNull();
  });

  it("submits the selected Cairo instant and duration minutes", async () => {
    const bodies: unknown[] = [];
    await openCreateForm((body) => bodies.push(body), "2026-10-12T10:00:00Z");

    fireEvent.press(screen.getByRole("button", { name: "Today, Monday 12 October" }));
    fireEvent.press(screen.getByRole("button", { name: "6:00 PM" }));
    fireEvent.press(screen.getByRole("button", { name: "1.5 hours" }));
    fireEvent.press(screen.getByRole("button", { name: "Send request" }));

    await waitFor(() => expect(bodies).toHaveLength(1));
    expect(bodies[0]).toEqual(expect.objectContaining({
      durationMinutes: 90,
      startAt: "2026-10-12T15:00:00.000Z",
    }));
  });

  it("requires both a date and a start time and clears the time when the date changes", async () => {
    const bodies: unknown[] = [];
    await openCreateForm((body) => bodies.push(body));

    fireEvent.press(screen.getByRole("button", { name: "Today, Tuesday 1 December" }));
    fireEvent.press(screen.getByRole("button", { name: "6:00 PM" }));
    expect(screen.getByRole("button", { name: "6:00 PM", selected: true })).toBeTruthy();
    fireEvent.press(screen.getByRole("button", { name: "Wednesday 2 December" }));
    expect(screen.getByRole("button", { name: "6:00 PM", selected: false })).toBeTruthy();
    fireEvent.press(screen.getByRole("button", { name: "Send request" }));

    expect(await screen.findByText("Check the information and try again.")).toBeTruthy();
    expect(bodies).toHaveLength(0);
  });

  it("reuses the create idempotency key when the unchanged request is retried after a network failure", async () => {
    const keys: Array<string | null> = [];
    await openCreateForm((_body, key) => keys.push(key), "2026-10-12T10:00:00Z", 1);

    fireEvent.press(screen.getByRole("button", { name: "Today, Monday 12 October" }));
    fireEvent.press(screen.getByRole("button", { name: "6:00 PM" }));
    fireEvent.press(screen.getByRole("button", { name: "Send request" }));
    fireEvent.press(await screen.findByRole("button", { name: "Try again" }));

    await waitFor(() => expect(keys).toHaveLength(2));
    expect(keys[0]).toBeTruthy();
    expect(keys[1]).toBe(keys[0]);
  });

  it("uses a new create idempotency key when the form changes after a network failure", async () => {
    const attempts: Array<{ body: unknown; key: string | null }> = [];
    await openCreateForm((body, key) => attempts.push({ body, key }), "2026-10-12T10:00:00Z", 1);

    fireEvent.press(screen.getByRole("button", { name: "Today, Monday 12 October" }));
    fireEvent.press(screen.getByRole("button", { name: "6:00 PM" }));
    fireEvent.press(screen.getByRole("button", { name: "Send request" }));
    await screen.findByText("The server could not be reached. Try again.");
    fireEvent.changeText(screen.getByLabelText("Capacity (8–32)"), "9");
    fireEvent.press(screen.getByRole("button", { name: "Try again" }));

    await waitFor(() => expect(attempts).toHaveLength(2));
    expect(attempts[0]!.key).toBeTruthy();
    expect(attempts[1]!.key).not.toBe(attempts[0]!.key);
    expect(attempts[1]!.body).toEqual(expect.objectContaining({ capacity: 9 }));
  });

  it("reuses an action idempotency key after a network failure", async () => {
    const keys: Array<string | null> = [];
    await openTournamentDetail(actionClient({ failActionAttempts: 1, onAction: (_path, key) => keys.push(key) }));

    fireEvent.press(screen.getByRole("button", { name: "Join tournament" }));
    await screen.findByText("The server could not be reached. Try again.");
    fireEvent.press(screen.getByRole("button", { name: "Join tournament" }));

    await waitFor(() => expect(keys).toHaveLength(2));
    expect(keys[0]).toBeTruthy();
    expect(keys[1]).toBe(keys[0]);
  });

  it("lets only an eligible organizer confirm removing a joined participant", async () => {
    const paths: string[] = [];
    const detail = detailFixture({
      createdByMe: true,
      participants: [{ userId: participantUserId, username: "player-two", status: "JOINED" }],
    });
    await openTournamentDetail(actionClient({ detail, onAction: (path) => paths.push(path) }));

    fireEvent.press(screen.getByRole("button", { name: "Remove" }));
    expect(await screen.findByText("Remove player-two from this tournament?")).toBeTruthy();
    expect(paths).toHaveLength(0);
    fireEvent.press(screen.getByRole("button", { name: "Yes, remove participant" }));

    await waitFor(() => expect(paths).toEqual([expect.stringContaining(`/participants/${participantUserId}/remove`)]));
  });

  it("hides participant removal after the tournament start time", async () => {
    const detail = detailFixture({
      createdByMe: true,
      startAt: "2026-12-01T09:00:00Z",
      participants: [{ userId: participantUserId, username: "player-two", status: "JOINED" }],
    });
    await openTournamentDetail(actionClient({ detail }));

    expect(screen.queryByRole("button", { name: "Remove" })).toBeNull();
  });

  it("asks the organizer to confirm cancelling a tournament", async () => {
    const paths: string[] = [];
    const detail = detailFixture({ createdByMe: true });
    await openTournamentDetail(actionClient({ detail, onAction: (path) => paths.push(path) }));

    fireEvent.press(screen.getByRole("button", { name: "Cancel tournament" }));
    expect(await screen.findByText("Cancel this tournament?")).toBeTruthy();
    expect(paths).toHaveLength(0);
    fireEvent.press(screen.getByRole("button", { name: "Yes, cancel tournament" }));

    await waitFor(() => expect(paths).toEqual([expect.stringContaining("/cancel")]));
  });

  it("explains that tournament money is informational on create and detail", async () => {
    const moneyNotice = "Entry fee and prize are informational only. E7GZ does not collect, hold, or distribute tournament money.";
    const { unmount } = render(<TournamentsScreen api={creationClient()} />);
    fireEvent.press(await screen.findByRole("button", { name: "Create tournament" }));
    expect(await screen.findByText(moneyNotice)).toBeTruthy();
    unmount();

    await openTournamentDetail();
    expect(await screen.findByText(moneyNotice)).toBeTruthy();
  });

  it("rejects a fractional tournament capacity", async () => {
    const bodies: unknown[] = [];
    await openCreateForm((body) => bodies.push(body), "2026-10-12T10:00:00Z");

    fireEvent.changeText(screen.getByLabelText("Capacity (8–32)"), "8.5");
    fireEvent.press(screen.getByRole("button", { name: "Today, Monday 12 October" }));
    fireEvent.press(screen.getByRole("button", { name: "6:00 PM" }));
    fireEvent.press(screen.getByRole("button", { name: "Send request" }));

    expect(await screen.findByText("Check the information and try again.")).toBeTruthy();
    expect(bodies).toHaveLength(0);
  });

  it("supports joining and leaving from tournament detail", async () => {
    const joinPaths: string[] = [];
    const { unmount } = render(<TournamentsScreen api={actionClient({ onAction: (path) => joinPaths.push(path) })} />);
    fireEvent.press(await screen.findByText("Full Arena"));
    fireEvent.press(await screen.findByRole("button", { name: "Join tournament" }));
    await waitFor(() => expect(joinPaths).toEqual([expect.stringContaining("/join")]));
    unmount();

    const leavePaths: string[] = [];
    await openTournamentDetail(actionClient({ detail: detailFixture({ myParticipantStatus: "JOINED" }), onAction: (path) => leavePaths.push(path) }));
    fireEvent.press(screen.getByRole("button", { name: "Leave tournament" }));
    await waitFor(() => expect(leavePaths).toEqual([expect.stringContaining("/leave")]));
  });

  it("joins a private tournament with an invite code", async () => {
    const paths: string[] = [];
    render(<TournamentsScreen api={actionClient({ onAction: (path) => paths.push(path) })} />);

    fireEvent.press(await screen.findByRole("button", { name: "Join with invite code" }));
    fireEvent.changeText(screen.getByLabelText("Invite code"), " K7Q2M9XD ");
    fireEvent.press(screen.getByRole("button", { name: "Join tournament" }));

    await waitFor(() => expect(paths).toEqual([expect.stringContaining("/join-private")]));
    expect(await screen.findByText("Join checkpoint")).toBeTruthy();
  });

  it("shows the terminal reason on a cancelled tournament", async () => {
    await openTournamentDetail(actionClient({ detail: detailFixture({ status: "CANCELLED", terminalReason: "EMERGENCY_CLOSURE" }) }));
    expect(await screen.findByText("The venue closed because of an emergency.")).toBeTruthy();
  });

  it.each([
    ["TOURNAMENT_CAPACITY_REACHED", 409, "This tournament is full."],
    ["VALIDATION_FAILED", 422, "Check the information and try again."],
  ])("shows a friendly %s action error", async (code, status, message) => {
    await openTournamentDetail(actionClient({ actionError: { code, status } }));
    fireEvent.press(screen.getByRole("button", { name: "Join tournament" }));
    expect(await screen.findByText(message)).toBeTruthy();
  });

  it("loads another discover page and opens My tournaments", async () => {
    const secondListing = { ...listing, tournamentId: "55555555-5555-4555-8555-555555555555", venueName: "Second Arena" };
    const mineListing = { ...listing, tournamentId: "66666666-6666-4666-8666-666666666666", venueName: "Mine Arena" };
    const api = createCustomerApiClient(config, async (request: Request) => {
      const url = new URL(request.url);
      const data = url.pathname.endsWith("/areas") ? []
        : url.pathname.endsWith("/account-standing") ? {
            consecutiveNoShows: 0, penaltyStage: 0, currentThreshold: 3, currentBan: null,
            verifiedPlayer: { qualification: "QUALIFIED", completedBookings: 10, requiredBookings: 10, status: "VERIFIED" },
          }
          : url.pathname.endsWith("/mine") ? { items: [mineListing], nextCursor: null }
            : url.searchParams.get("cursor") === "next-page" ? { items: [secondListing], nextCursor: null }
              : { items: [listing], nextCursor: "next-page" };
      return new Response(JSON.stringify({ data, meta }), { status: 200, headers: { "Content-Type": "application/json" } });
    });
    render(<TournamentsScreen api={api} />);

    fireEvent.press(await screen.findByRole("button", { name: "Load more" }));
    expect(await screen.findByText("Second Arena")).toBeTruthy();
    fireEvent.press(screen.getByRole("button", { name: "My tournaments" }));
    expect(await screen.findByText("Mine Arena")).toBeTruthy();
  });
});
