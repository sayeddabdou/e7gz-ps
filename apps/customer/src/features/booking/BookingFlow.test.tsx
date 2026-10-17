import { fireEvent, render, screen } from "@testing-library/react-native";
import type { components } from "@e7gz/api-client";
import { createCustomerApiClient } from "../../api";
import { BookingFlow } from "./BookingFlow";

const config = { apiBaseUrl: "http://127.0.0.1:4010" };
const serverTime = "2026-10-12T15:08:30Z";
const meta = { correlationId: "c-1", serverTime };
const details: components["schemas"]["CustomerVenueDetails"] = {
  id: "7b1f0c9e-2d3a-4e5b-8c6d-9e0f1a2b3c4d",
  name: "Level Up Lounge",
  area: { id: "a-1", name: "Nasr City", nameAr: null },
  address: "12 Test Street",
  location: { lat: 30.05, lng: 31.33 },
  distanceMeters: null,
  imageUrls: [],
  activities: ["PLAYSTATION", "BILLIARDS"],
  selectedActivity: "PLAYSTATION",
  resources: [{ id: "c2d3e4f5-a6b7-4c8d-9e0f-1a2b3c4d5e6f", name: "Room 1", consoleType: "PS5" }],
  prices: [],
  openingHours: [],
  nearestAvailableAt: null,
  openNow: true,
  opensAt: null,
  bookingEnabled: true,
  averageRating: null,
  reviewCount: 0,
};
const quote: components["schemas"]["CustomerBookingQuote"] = {
  hourlyPrice: { amount: "170.50", currency: "EGP" },
  nominalBookedMinutes: 120,
  venuePriceTotal: { amount: "341.00", currency: "EGP" },
  bookingFee: { amount: "0.00", currency: "EGP", displayAsFree: true },
  totalAmount: { amount: "341.00", currency: "EGP" },
};
const booking: components["schemas"]["CustomerBookingDetail"] = {
  attendanceResult: null,
  bookingFee: quote.bookingFee,
  bookingId: "018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e6f",
  displayBucket: "upcoming",
  endAt: "2026-10-12T17:00:00Z",
  history: [],
  lateConfirm: null,
  nominalBookedMinutes: 120,
  resource: { activity: "PLAYSTATION", consoleType: "PS5", name: "Room 1", playMode: "SINGLE" },
  startAt: "2026-10-12T15:00:00Z",
  status: "PENDING",
  terminalReason: null,
  totalAmount: quote.totalAmount,
  venue: { id: details.id, name: details.name, phone: "+201001234567" },
  venuePriceTotal: quote.venuePriceTotal,
  venueResponseExpiresAt: "2026-10-12T15:10:00Z",
  venueResponsePaused: false,
};

const ok = (data: unknown, status = 200, responseMeta = meta) => new Response(
  JSON.stringify({ data, meta: responseMeta }),
  { status, headers: { "Content-Type": "application/json" } },
);
const failed = (code: string, status = 422) => new Response(
  JSON.stringify({ error: { code }, meta }),
  { status, headers: { "Content-Type": "application/json" } },
);

function renderFlow(fetch: jest.Mock, overrides?: Partial<React.ComponentProps<typeof BookingFlow>>, profilePhoneVerified = true) {
  const api = createCustomerApiClient(config, async (request: Request) => request.url.endsWith("/customer/profile")
    ? ok({
        userId: "u-1",
        fullName: "Omar Hassan",
        username: "omar_h",
        phone: "+201012345678",
        phoneVerified: profilePhoneVerified,
        email: "omar@example.com",
        emailVerified: false,
        usernameChangeAvailableAt: null,
        joinedAt: "2026-10-01T09:30:00Z",
      })
    : fetch(request));
  return render(
    <BookingFlow
      api={api}
      initialActivity="PLAYSTATION"
      initialDetails={details}
      initialPhoneVerified
      idempotencyKeyFactory={() => "11111111-1111-4111-8111-111111111111"}
      onBack={() => {}}
      initialServerTime="2026-10-08T12:00:00Z"
      {...overrides}
    />,
  );
}

async function reachSummary() {
  fireEvent.press(screen.getByRole("button", { name: "Room 1 · PS5" }));
  fireEvent.press(screen.getByRole("button", { name: "Monday 12 October" }));
  fireEvent.press(screen.getByRole("button", { name: "6:00 PM" }));
  fireEvent.press(screen.getByRole("button", { name: "2 hours" }));
  fireEvent.press(screen.getByRole("button", { name: "Review price" }));
  await screen.findByRole("header", { name: "Booking summary" });
}

describe("BookingFlow", () => {
  it("shows required selection and no-resource states", () => {
    const fetch = jest.fn();
    renderFlow(fetch, { initialDetails: { ...details, resources: [] } });
    expect(screen.getByRole("header", { name: "Book a room" })).toBeTruthy();
    expect(screen.getByText("No rooms are available for this activity.")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Review price" }).props.accessibilityState.disabled).toBe(true);
  });

  it("uses date buttons and AM/PM start-time buttons", () => {
    const fetch = jest.fn();
    renderFlow(fetch, { initialResourceId: details.resources[0]!.id });

    expect(screen.getByText("Choose a date to see start times.")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Review price" }).props.accessibilityState.disabled).toBe(true);

    fireEvent.press(screen.getByRole("button", { name: "Today, Thursday 8 October" }));
    expect(screen.getByRole("button", { name: "Review price" }).props.accessibilityState.disabled).toBe(true);

    fireEvent.press(screen.getByRole("button", { name: "5:00 PM" }));
    expect(screen.getByRole("button", { name: "5:00 PM" }).props.accessibilityState.selected).toBe(true);
    expect(screen.getByRole("button", { name: "Review price" }).props.accessibilityState.disabled).toBe(false);
    expect(fetch).not.toHaveBeenCalled();
  });

  it("hides starts before the server-time lead boundary and keeps exactly 15 minutes", () => {
    const fetch = jest.fn();
    renderFlow(fetch, { initialResourceId: details.resources[0]!.id });

    fireEvent.press(screen.getByRole("button", { name: "Today, Thursday 8 October" }));
    expect(screen.queryByRole("button", { name: "3:00 PM" })).toBeNull();
    expect(screen.getByRole("button", { name: "3:15 PM" })).toBeTruthy();
  });

  it("reloads resources when activity changes and retries a failed reload", async () => {
    let calls = 0;
    const billiards = {
      ...details,
      selectedActivity: "BILLIARDS" as const,
      resources: [{ id: "b2d3e4f5-a6b7-4c8d-9e0f-1a2b3c4d5e6f", name: "Table 2", consoleType: null }],
    };
    const fetch = jest.fn(async () => {
      calls += 1;
      return calls === 1 ? failed("NOT_FOUND", 404) : ok(billiards);
    });
    renderFlow(fetch);

    fireEvent.press(screen.getByRole("button", { name: "Billiards" }));
    expect(await screen.findByText("That venue is no longer available.")).toBeTruthy();
    fireEvent.press(screen.getByText("Retry"));
    expect(await screen.findByRole("button", { name: "Table 2" })).toBeTruthy();
    expect(screen.queryByText("Play mode")).toBeNull();
  });

  it("requests the server quote and displays its exact decimal amounts", async () => {
    const requests: Request[] = [];
    const fetch = jest.fn(async (request: Request) => { requests.push(request); return ok(quote); });
    renderFlow(fetch);

    await reachSummary();

    expect(screen.getByText("170.50 EGP")).toBeTruthy();
    expect(screen.getAllByText("341.00 EGP")).toHaveLength(2);
    expect(screen.getByText("Free")).toBeTruthy();
    expect(screen.getByText("Room 1 · PS5")).toBeTruthy();
    expect(screen.getByText("Mon, Oct 12, 2026")).toBeTruthy();
    expect(screen.getByText("6:00 PM")).toBeTruthy();
    expect(screen.getByText("Single")).toBeTruthy();
    expect(requests).toHaveLength(1);
    expect(requests[0]!.url).toContain("resourceId=c2d3e4f5-a6b7-4c8d-9e0f-1a2b3c4d5e6f");
    expect(requests[0]!.url).toContain("playMode=SINGLE");
    expect(requests[0]!.url).toContain("startAt=2026-10-12T15%3A00%3A00.000Z");
    expect(requests[0]!.url).toContain("durationMinutes=120");
  });

  it("moves an after-midnight time onto the next calendar date", async () => {
    const requests: Request[] = [];
    const fetch = jest.fn(async (request: Request) => { requests.push(request); return ok(quote); });
    renderFlow(fetch, {
      initialDetails: {
        ...details,
        openingHours: [{ weekday: 0, closed: false, opensAtLocal: "18:00:00", closesAtLocal: "02:00:00" }],
      },
      initialResourceId: details.resources[0]!.id,
    });

    fireEvent.press(screen.getByRole("button", { name: "Monday 12 October" }));
    fireEvent.press(screen.getByRole("button", { name: "12:00 AM" }));
    fireEvent.press(screen.getByRole("button", { name: "2 hours" }));
    fireEvent.press(screen.getByRole("button", { name: "Review price" }));

    expect(await screen.findByRole("header", { name: "Booking summary" })).toBeTruthy();
    expect(requests[0]!.url).toContain("startAt=2026-10-12T21%3A00%3A00.000Z");
    expect(screen.getByText("Tue, Oct 13, 2026")).toBeTruthy();
    expect(screen.getByText("12:00 AM")).toBeTruthy();
  });

  it("uses 15-minute starts and hides times that cannot fit before session close", () => {
    const fetch = jest.fn();
    renderFlow(fetch, {
      initialDetails: {
        ...details,
        openingHours: [{ weekday: 0, closed: false, opensAtLocal: "18:00:00", closesAtLocal: "02:00:00" }],
      },
      initialResourceId: details.resources[0]!.id,
    });

    fireEvent.press(screen.getByRole("button", { name: "Monday 12 October" }));
    expect(screen.getByRole("button", { name: "6:15 PM" })).toBeTruthy();
    fireEvent.press(screen.getByRole("button", { name: "2 hours" }));
    expect(screen.getByRole("button", { name: "12:00 AM" })).toBeTruthy();
    expect(screen.queryByRole("button", { name: "12:15 AM" })).toBeNull();
  });

  it("blocks time selection from the profile response until the customer's phone is verified", async () => {
    const fetch = jest.fn();
    const openProfile = jest.fn();
    renderFlow(fetch, { initialPhoneVerified: undefined, onOpenProfile: openProfile }, false);

    fireEvent.press(screen.getByRole("button", { name: "Monday 12 October" }));
    expect(await screen.findByText("Verify your phone before choosing a booking time.")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "6:00 PM" })).toBeNull();
    fireEvent.press(screen.getByRole("button", { name: "Go to Profile" }));
    expect(openProfile).toHaveBeenCalledTimes(1);
  });

  it("shows the intake notice but lets the server make the final submission decision", async () => {
    const fetch = jest.fn(async (request: Request) => request.method === "GET"
      ? ok(quote)
      : failed("ONLINE_INTAKE_WINDOW_CLOSED", 422));
    renderFlow(fetch, { initialDetails: { ...details, openNow: false } });
    await reachSummary();

    expect(screen.getByText("Online booking intake is currently closed for this venue.")).toBeTruthy();
    const confirm = screen.getByRole("button", { name: "Confirm booking" });
    expect(confirm.props.accessibilityState.disabled).toBe(false);
    fireEvent.press(confirm);
    expect(await screen.findByText("The venue is no longer accepting online requests in this operating session.")).toBeTruthy();
  });

  it("refuses a quote returned for a different duration instead of showing a false total", async () => {
    const requests: Request[] = [];
    const fetch = jest.fn(async (request: Request) => { requests.push(request); return ok(quote); });
    renderFlow(fetch);

    fireEvent.press(screen.getByRole("button", { name: "Room 1 · PS5" }));
    fireEvent.press(screen.getByRole("button", { name: "Monday 12 October" }));
    fireEvent.press(screen.getByRole("button", { name: "6:00 PM" }));
    fireEvent.press(screen.getByRole("button", { name: "3 hours" }));
    fireEvent.press(screen.getByRole("button", { name: "Review price" }));

    expect(await screen.findByText("The server returned a price for a different duration. Please retry when the booking service is available.")).toBeTruthy();
    expect(screen.queryByRole("header", { name: "Booking summary" })).toBeNull();
    expect(requests[0]!.url).toContain("durationMinutes=180");
  });

  it("prevents double-submit and sends the generated key with the contract body", async () => {
    const requests: Request[] = [];
    let resolveCreate!: (response: Response) => void;
    const createResponse = new Promise<Response>((resolve) => { resolveCreate = resolve; });
    const fetch = jest.fn(async (request: Request) => {
      requests.push(request);
      return request.method === "GET" ? ok(quote) : createResponse;
    });
    renderFlow(fetch);
    await reachSummary();

    const confirm = screen.getByRole("button", { name: "Confirm booking" });
    fireEvent.press(confirm);
    fireEvent.press(confirm);
    expect(requests.filter((request) => request.method === "POST")).toHaveLength(1);

    const createRequest = requests.find((request) => request.method === "POST")!;
    expect(createRequest.headers.get("Idempotency-Key")).toBe("11111111-1111-4111-8111-111111111111");
    expect(await createRequest.clone().json()).toEqual({
      resourceId: "c2d3e4f5-a6b7-4c8d-9e0f-1a2b3c4d5e6f",
      playMode: "SINGLE",
      startAt: "2026-10-12T15:00:00.000Z",
      durationMinutes: 120,
    });
    resolveCreate(ok(booking, 201));
    expect(await screen.findByRole("header", { name: "Booking request sent" })).toBeTruthy();
  });

  it("reuses the same idempotency key when retrying the same failed attempt", async () => {
    const requests: Request[] = [];
    let creates = 0;
    const fetch = jest.fn(async (request: Request) => {
      requests.push(request);
      if (request.method === "GET") return ok(quote);
      creates += 1;
      if (creates === 1) throw new TypeError("connection lost after submit");
      return ok(booking, 201);
    });
    renderFlow(fetch);
    await reachSummary();

    fireEvent.press(screen.getByRole("button", { name: "Confirm booking" }));
    expect(await screen.findByText("The server could not be reached. Retry the same booking attempt.")).toBeTruthy();
    fireEvent.press(screen.getByRole("button", { name: "Retry booking" }));
    expect(await screen.findByRole("header", { name: "Booking request sent" })).toBeTruthy();

    const keys = requests
      .filter((request) => request.method === "POST")
      .map((request) => request.headers.get("Idempotency-Key"));
    expect(keys).toEqual([
      "11111111-1111-4111-8111-111111111111",
      "11111111-1111-4111-8111-111111111111",
    ]);
  });

  it("keeps the attempt key when the summary is reopened without changing the request", async () => {
    const keys: (string | null)[] = [];
    let creates = 0;
    const fetch = jest.fn(async (request: Request) => {
      if (request.method === "GET") return ok(quote);
      keys.push(request.headers.get("Idempotency-Key"));
      creates += 1;
      if (creates === 1) throw new TypeError("connection lost after submit");
      return ok(booking, 201);
    });
    renderFlow(fetch);
    await reachSummary();

    fireEvent.press(screen.getByRole("button", { name: "Confirm booking" }));
    expect(await screen.findByText("The server could not be reached. Retry the same booking attempt.")).toBeTruthy();
    fireEvent.press(screen.getByRole("button", { name: "Edit booking" }));
    fireEvent.press(screen.getByRole("button", { name: "Review price" }));
    await screen.findByRole("header", { name: "Booking summary" });
    fireEvent.press(screen.getByRole("button", { name: "Confirm booking" }));

    expect(await screen.findByRole("header", { name: "Booking request sent" })).toBeTruthy();
    expect(keys).toEqual([
      "11111111-1111-4111-8111-111111111111",
      "11111111-1111-4111-8111-111111111111",
    ]);
  });

  it("maps account capability failures instead of validating the server rules locally", async () => {
    const fetch = jest.fn(async (request: Request) => request.method === "GET" ? ok(quote) : failed("PHONE_VERIFICATION_REQUIRED", 403));
    renderFlow(fetch);
    await reachSummary();
    fireEvent.press(screen.getByRole("button", { name: "Confirm booking" }));
    expect(await screen.findByText("Verify your phone before creating a booking.")).toBeTruthy();
  });

  it("shows quote errors with retry", async () => {
    let calls = 0;
    const fetch = jest.fn(async () => {
      calls += 1;
      return calls === 1 ? failed("BOOKING_DURATION_INVALID") : ok({ ...quote, nominalBookedMinutes: 60 });
    });
    renderFlow(fetch);
    fireEvent.press(screen.getByRole("button", { name: "Room 1 · PS5" }));
    fireEvent.press(screen.getByRole("button", { name: "Monday 12 October" }));
    fireEvent.press(screen.getByRole("button", { name: "6:00 PM" }));
    fireEvent.press(screen.getByRole("button", { name: "Review price" }));
    expect(await screen.findByText("Choose a valid duration.")).toBeTruthy();
    fireEvent.press(screen.getByRole("button", { name: "Retry price" }));
    expect(await screen.findByRole("header", { name: "Booking summary" })).toBeTruthy();
  });

  it("starts the pending countdown from server instants and handles paused timers", async () => {
    const fetch = jest.fn(async (request: Request) => request.method === "GET" ? ok(quote) : ok(booking, 201));
    const first = renderFlow(fetch);
    await reachSummary();
    fireEvent.press(screen.getByRole("button", { name: "Confirm booking" }));
    expect(await screen.findByText("01:30")).toBeTruthy();
    first.unmount();

    const pausedFetch = jest.fn(async (request: Request) => request.method === "GET" ? ok(quote) : ok({ ...booking, venueResponsePaused: true }, 201));
    renderFlow(pausedFetch);
    await reachSummary();
    fireEvent.press(screen.getByRole("button", { name: "Confirm booking" }));
    expect(await screen.findByText("Venue response countdown paused")).toBeTruthy();
  });

  it("shows the deadline-passed state when the server instants have no time remaining", async () => {
    const expired = { ...booking, venueResponseExpiresAt: serverTime };
    const fetch = jest.fn(async (request: Request) => request.method === "GET" ? ok(quote) : ok(expired, 201));
    renderFlow(fetch);
    await reachSummary();
    fireEvent.press(screen.getByRole("button", { name: "Confirm booking" }));
    expect(await screen.findByText("The venue response deadline has passed. Refresh My Bookings for the latest status.")).toBeTruthy();
  });

  it("links to My Bookings and polls until the pending booking changes", async () => {
    const openMyBookings = jest.fn();
    const confirmed = {
      ...booking,
      status: "CONFIRMED" as const,
      history: [{
        at: "2026-10-12T15:09:00Z",
        kind: "STATUS_TRANSITION" as const,
        eventType: null,
        fromStatus: "PENDING" as const,
        toStatus: "CONFIRMED" as const,
        terminalReason: null,
      }],
    };
    const fetch = jest.fn(async (request: Request) => {
      if (request.method === "POST") return ok(booking, 201);
      if (request.url.includes(`/bookings/${booking.bookingId}`)) return ok(confirmed);
      return ok(quote);
    });
    renderFlow(fetch, { onOpenMyBookings: openMyBookings, pendingPollIntervalMs: 10 });
    await reachSummary();
    fireEvent.press(screen.getByRole("button", { name: "Confirm booking" }));

    const viewBookings = await screen.findByRole("button", { name: "View My Bookings" });
    fireEvent.press(viewBookings);
    expect(openMyBookings).toHaveBeenCalledTimes(1);
    expect(await screen.findByRole("header", { name: "Booking status updated" })).toBeTruthy();
    expect(screen.getAllByText(/Confirmed/u).length).toBeGreaterThan(0);
  });
});
