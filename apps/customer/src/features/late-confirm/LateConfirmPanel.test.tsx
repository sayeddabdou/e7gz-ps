import { act, fireEvent, render, screen, waitFor } from "@testing-library/react-native";
import { createCustomerApiClient } from "../../api";
import { LateConfirmPanel } from "./LateConfirmPanel";

const config = { apiBaseUrl: "http://127.0.0.1:4010" };
const meta = { correlationId: "c-1", serverTime: "2026-10-12T17:00:00Z" };
const booking = {
  bookingId: "b-1",
  status: "AWAITING_CUSTOMER_CONFIRMATION" as const,
  displayBucket: "upcoming" as const,
  terminalReason: null,
  venue: { id: "v-1", name: "Game Zone", phone: "+201001234567" },
  resource: { name: "Room 3", activity: "PLAYSTATION" as const, consoleType: "PS5" as const, playMode: "MULTIPLAYER" as const },
  startAt: "2026-10-12T18:00:00Z",
  endAt: "2026-10-12T20:00:00Z",
  nominalBookedMinutes: 120,
  venuePriceTotal: { amount: "400.00", currency: "EGP" as const },
  bookingFee: { amount: "0.00", currency: "EGP" as const, displayAsFree: true },
  totalAmount: { amount: "400.00", currency: "EGP" as const },
  venueResponseExpiresAt: null,
  venueResponsePaused: false,
  lateConfirm: { expiresAt: "2026-10-12T17:05:00Z", paused: false },
  attendanceResult: null,
  history: [],
};

const response = (data: unknown, status = 200) => new Response(
  JSON.stringify(status < 400 ? { data, meta } : { error: { code: data }, meta }),
  { status, headers: { "Content-Type": "application/json" } },
);

describe("LateConfirmPanel", () => {
  beforeEach(() => {
    jest.useFakeTimers();
    jest.setSystemTime(new Date("2035-01-01T00:00:00Z"));
  });

  afterEach(() => jest.useRealTimers());

  it("counts down from the server instant, not the device clock", () => {
    const api = createCustomerApiClient(config, jest.fn());
    render(<LateConfirmPanel api={api} booking={booking} serverTime={meta.serverTime} onReload={jest.fn()} />);

    expect(screen.getByText("05:00")).toBeTruthy();
    act(() => jest.advanceTimersByTime(1_000));
    expect(screen.getByText("04:59")).toBeTruthy();
  });

  it("shows the paused state, disables Accept, and leaves Decline available", () => {
    const api = createCustomerApiClient(config, jest.fn());
    render(<LateConfirmPanel api={api} booking={{ ...booking, lateConfirm: { ...booking.lateConfirm, paused: true } }} serverTime={meta.serverTime} onReload={jest.fn()} />);

    expect(screen.getByText("Confirmation temporarily paused due to venue/resource issue.")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Accept Booking" }).props.accessibilityState.disabled).toBe(true);
    expect(screen.getByRole("button", { name: "Decline" }).props.accessibilityState.disabled).toBe(false);
  });

  it("keeps the booking awaiting after a schedule conflict and leaves Decline enabled", async () => {
    const requests: Request[] = [];
    const fetch = jest.fn(async (request: Request) => {
      requests.push(request);
      return response("LATE_CONFIRM_CUSTOMER_CONFLICT", 409);
    });
    const api = createCustomerApiClient(config, fetch);
    const reload = jest.fn(async () => {});
    const keys = ["11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222"];
    render(<LateConfirmPanel api={api} booking={booking} serverTime={meta.serverTime} onReload={reload} idempotencyKeyFactory={() => keys.shift()!} />);

    fireEvent.press(screen.getByRole("button", { name: "Accept Booking" }));

    const conflictMessage = "You can't accept this booking because it overlaps another booking of yours.";
    expect(await screen.findByText(conflictMessage)).toBeTruthy();
    expect(conflictMessage).not.toMatch(/pending|confirmed|active|awaiting/iu);
    expect(screen.queryByText(/AWAITING_CUSTOMER_CONFIRMATION/u)).toBeNull();
    expect(screen.getByText("05:00")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Decline" }).props.accessibilityState.disabled).toBe(false);
    expect(reload).toHaveBeenCalledTimes(1);
    expect(requests[0]?.headers.get("Idempotency-Key")).toBe("11111111-1111-4111-8111-111111111111");
    fireEvent.press(screen.getByRole("button", { name: "Accept Booking" }));
    await waitFor(() => expect(requests).toHaveLength(2));
    expect(requests[1]?.headers.get("Idempotency-Key")).toBe("22222222-2222-4222-8222-222222222222");
  });

  it("prevents double submission and reloads detail after a successful response", async () => {
    let resolveAction!: (value: Response) => void;
    const pending = new Promise<Response>((resolve) => { resolveAction = resolve; });
    const fetch = jest.fn(async () => pending);
    const api = createCustomerApiClient(config, fetch);
    const reload = jest.fn(async () => {});
    render(<LateConfirmPanel api={api} booking={booking} serverTime={meta.serverTime} onReload={reload} />);

    const decline = screen.getByRole("button", { name: "Decline" });
    fireEvent.press(decline);
    fireEvent.press(decline);
    expect(fetch).toHaveBeenCalledTimes(1);
    resolveAction(response({ bookingId: "b-1", status: "CANCELLED", terminalReason: "CUSTOMER_DECLINED_LATE_CONFIRM" }));
    await waitFor(() => expect(reload).toHaveBeenCalledTimes(1));
  });

  it("retains the idempotency key when an API response is ambiguous", async () => {
    const requests: Request[] = [];
    let attempt = 0;
    const fetch = jest.fn(async (request: Request) => {
      requests.push(request);
      attempt += 1;
      return attempt === 1
        ? response(undefined, 500)
        : response({ bookingId: "b-1", status: "CONFIRMED", terminalReason: null });
    });
    const api = createCustomerApiClient(config, fetch);
    const reload = jest.fn(async () => {});
    const key = "33333333-3333-4333-8333-333333333333";
    render(<LateConfirmPanel api={api} booking={booking} serverTime={meta.serverTime} onReload={reload} idempotencyKeyFactory={() => key} />);

    fireEvent.press(screen.getByRole("button", { name: "Accept Booking" }));
    expect(await screen.findByText("Something went wrong. Try again.")).toBeTruthy();
    fireEvent.press(screen.getByRole("button", { name: "Accept Booking" }));

    await waitFor(() => expect(requests).toHaveLength(2));
    expect(requests.map((request) => request.headers.get("Idempotency-Key"))).toEqual([key, key]);
    expect(reload).toHaveBeenCalledTimes(1);
  });

  it("polls while awaiting and reloads immediately when the deadline expires", async () => {
    const api = createCustomerApiClient(config, jest.fn());
    const reload = jest.fn(async () => {});
    const view = render(<LateConfirmPanel api={api} booking={booking} serverTime={meta.serverTime} onReload={reload} pollIntervalMs={10_000} />);

    await act(async () => { jest.advanceTimersByTime(10_000); });
    expect(reload).toHaveBeenCalledTimes(1);
    view.unmount();

    const expiryReload = jest.fn(async () => {});
    render(<LateConfirmPanel api={api} booking={{ ...booking, lateConfirm: { expiresAt: meta.serverTime, paused: false } }} serverTime={meta.serverTime} onReload={expiryReload} />);
    expect(expiryReload).toHaveBeenCalledTimes(1);
  });

  it("turns the actions off when the stored deadline expires", () => {
    const api = createCustomerApiClient(config, jest.fn());
    render(<LateConfirmPanel api={api} booking={{ ...booking, lateConfirm: { expiresAt: meta.serverTime, paused: false } }} serverTime={meta.serverTime} onReload={jest.fn()} />);

    expect(screen.getByText("This late confirmation has expired.")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Accept Booking" }).props.accessibilityState.disabled).toBe(true);
    expect(screen.getByRole("button", { name: "Decline" }).props.accessibilityState.disabled).toBe(true);
  });
});
