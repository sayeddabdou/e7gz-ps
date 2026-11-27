import { fireEvent, render, screen, waitFor } from "@testing-library/react-native";
import { useState } from "react";
import { createCustomerApiClient } from "../../api";
import { MyBookingsScreen } from "./MyBookingsScreen";

const config = { apiBaseUrl: "http://127.0.0.1:4010" };
const meta = { correlationId: "c-1", serverTime: "2026-10-12T17:00:00Z" };
const booking = {
  bookingId: "b-1",
  status: "VENUE_RESPONSE_EXPIRED" as const,
  displayBucket: "cancelled" as const,
  terminalReason: null,
  venue: { id: "v-1", name: "Game Zone" },
  resource: { name: "Room 3", activity: "PLAYSTATION" as const, consoleType: "PS5" as const, playMode: "MULTIPLAYER" as const },
  startAt: "2026-10-12T18:00:00Z",
  endAt: "2026-10-12T20:00:00Z",
  totalAmount: { amount: "400.00", currency: "EGP" as const },
  venueResponseExpiresAt: null,
};
const detail = {
  ...booking,
  status: "CONFIRMED" as const,
  displayBucket: "upcoming" as const,
  venue: { ...booking.venue, phone: "+201001234567" },
  nominalBookedMinutes: 120,
  venuePriceTotal: { amount: "400.00", currency: "EGP" as const },
  bookingFee: { amount: "0.00", currency: "EGP" as const, displayAsFree: true },
  bookingFeePayment: {
    pointsUsed: 0,
    paidByPoints: { amount: "0.00", currency: "EGP" as const },
    paidByWallet: { amount: "0.00", currency: "EGP" as const },
    leftToPay: { amount: "0.00", currency: "EGP" as const },
  },
  venueResponsePaused: false,
  lateConfirm: null,
  attendanceResult: null,
  history: [],
};
const response = (data: unknown, status = 200) => new Response(
  JSON.stringify(status < 400 ? { data, meta } : { error: data, meta }),
  { status, headers: { "Content-Type": "application/json" } },
);

describe("MyBookingsScreen", () => {
  it("uses API buckets directly and shows the required expiry text", async () => {
    const requests: Request[] = [];
    const api = createCustomerApiClient(config, async (request: Request) => {
      requests.push(request);
      const url = new URL(request.url);
      const bucket = url.searchParams.get("bucket");
      return response({ items: bucket === "cancelled" ? [booking] : [], nextCursor: null });
    });
    render(<MyBookingsScreen api={api} />);

    expect(await screen.findByText("No upcoming bookings")).toBeTruthy();
    fireEvent.press(screen.getByText("Cancelled"));
    expect(await screen.findByText("Game Zone")).toBeTruthy();
    expect(screen.getAllByText("Venue did not respond in time").length).toBeGreaterThan(0);
    expect(requests.some((request) => request.url.includes("bucket=cancelled"))).toBe(true);
  });

  it("shows the phone only when the detail response contains it", async () => {
    let includePhone = true;
    const api = createCustomerApiClient(config, async (request: Request) => {
      if (request.url.includes("/bookings/b-1")) {
        return response(includePhone ? detail : { ...detail, venue: booking.venue });
      }
      return response({ items: [{ ...booking, status: "CONFIRMED", displayBucket: "upcoming" }], nextCursor: null });
    });
    const view = render(<MyBookingsScreen api={api} />);

    fireEvent.press(await screen.findByText("Game Zone"));
    expect(await screen.findByText("+201001234567")).toBeTruthy();
    expect(screen.queryByText("Points used")).toBeNull();
    view.unmount();
    includePhone = false;
    render(<MyBookingsScreen api={api} />);
    fireEvent.press(await screen.findByText("Game Zone"));
    await screen.findByText("Booking details");
    expect(screen.queryByText("Venue phone")).toBeNull();
    expect(screen.queryByText(/contact is no longer/iu)).toBeNull();
  });

  it("shows the stored fee split on a paid booking detail", async () => {
    const paidDetail = {
      ...detail,
      bookingFee: { amount: "25.00", currency: "EGP" as const, displayAsFree: false },
      bookingFeePayment: {
        pointsUsed: 20000,
        paidByPoints: { amount: "20.00", currency: "EGP" as const },
        paidByWallet: { amount: "5.00", currency: "EGP" as const },
        leftToPay: { amount: "0.00", currency: "EGP" as const },
      },
    };
    const api = createCustomerApiClient(config, async (request: Request) => request.url.includes("/bookings/b-1")
      ? response(paidDetail)
      : response({ items: [{ ...booking, status: "CONFIRMED", displayBucket: "upcoming" }], nextCursor: null }));
    render(<MyBookingsScreen api={api} />);

    fireEvent.press(await screen.findByText("Game Zone"));
    expect(await screen.findByText("20,000 points")).toBeTruthy();
    expect(screen.getByText("Points used")).toBeTruthy();
    expect(screen.getByText("Paid by wallet")).toBeTruthy();
  });

  it("shows a free booking fee without a payment split", async () => {
    const api = createCustomerApiClient(config, async (request: Request) => request.url.includes("/bookings/b-1")
      ? response(detail)
      : response({ items: [booking], nextCursor: null }));
    render(<MyBookingsScreen api={api} openBookingId="b-1" />);

    await screen.findByText("Booking details");
    expect(screen.getByText("Booking fee")).toBeTruthy();
    expect(screen.getByText("Free")).toBeTruthy();
    expect(screen.queryByText("Points used")).toBeNull();
    expect(screen.queryByText("Paid by points")).toBeNull();
    expect(screen.queryByText("Paid by wallet")).toBeNull();
    expect(screen.queryByText("Left to pay")).toBeNull();
  });

  it("opens the exact booking supplied by a push notification", async () => {
    const requests: Request[] = [];
    const api = createCustomerApiClient(config, async (request: Request) => {
      requests.push(request);
      if (request.url.endsWith("/bookings/push-booking")) return response(detail);
      return response({ items: [], nextCursor: null });
    });
    const consumed = jest.fn();
    function PushBookingHost() {
      const [openBookingId, setOpenBookingId] = useState<string | undefined>("push-booking");
      return (
        <MyBookingsScreen
          api={api}
          openBookingId={openBookingId}
          onOpenBookingConsumed={() => { consumed(); setOpenBookingId(undefined); }}
        />
      );
    }

    render(<PushBookingHost />);

    expect(await screen.findByText("Booking details")).toBeTruthy();
    expect(requests.some((request) => request.url.endsWith("/bookings/push-booking"))).toBe(true);
    expect(consumed).not.toHaveBeenCalled();
    fireEvent.press(screen.getByText("‹"));
    expect(consumed).toHaveBeenCalledTimes(1);
    expect(await screen.findByText("No upcoming bookings")).toBeTruthy();
  });

  it("keeps a conflicting Late Confirm awaiting after the detail reload", async () => {
    const awaiting = {
      ...detail,
      status: "AWAITING_CUSTOMER_CONFIRMATION" as const,
      lateConfirm: { expiresAt: "2026-10-12T17:05:00Z", paused: false },
    };
    let detailReads = 0;
    const api = createCustomerApiClient(config, async (request: Request) => {
      if (request.url.endsWith("/bookings/b-1/late-confirm/accept")) {
        return response({ code: "LATE_CONFIRM_CUSTOMER_CONFLICT" }, 409);
      }
      if (request.url.includes("/bookings/b-1")) {
        detailReads += 1;
        return response(awaiting);
      }
      return response({ items: [{ ...booking, status: awaiting.status, displayBucket: "upcoming" }], nextCursor: null });
    });
    render(<MyBookingsScreen api={api} idempotencyKeyFactory={() => "44444444-4444-4444-8444-444444444444"} />);

    fireEvent.press(await screen.findByText("Game Zone"));
    fireEvent.press(await screen.findByRole("button", { name: "Accept Booking" }));

    expect(await screen.findByText("You can't accept this booking because it overlaps another booking of yours.")).toBeTruthy();
    expect(screen.getByText(/^\d{2}:\d{2}$/u)).toBeTruthy();
    expect(screen.getByRole("button", { name: "Decline" }).props.accessibilityState.disabled).toBe(false);
    expect(detailReads).toBeGreaterThanOrEqual(2);
  });

  it("prevents double cancellation and reuses the idempotency key on retry", async () => {
    const cancelRequests: Request[] = [];
    let cancellationAttempt = 0;
    let releaseFirst!: () => void;
    const firstCancel = new Promise<void>((resolve) => { releaseFirst = resolve; });
    const api = createCustomerApiClient(config, async (request: Request) => {
      if (request.url.endsWith("/bookings/b-1/cancel")) {
        cancelRequests.push(request);
        cancellationAttempt += 1;
        if (cancellationAttempt === 1) {
          await firstCancel;
          return response({ code: "RATE_LIMITED" }, 429);
        }
        return response({ ...detail, status: "CANCELLED", displayBucket: "cancelled", terminalReason: "CUSTOMER_CANCELLED" });
      }
      if (request.url.includes("/bookings/b-1")) return response(detail);
      return response({ items: [{ ...booking, status: "CONFIRMED", displayBucket: "upcoming" }], nextCursor: null });
    });
    const idempotencyKey = "33333333-3333-4333-8333-333333333333";
    render(<MyBookingsScreen api={api} idempotencyKeyFactory={() => idempotencyKey} />);

    fireEvent.press(await screen.findByText("Game Zone"));
    fireEvent.press(await screen.findByText("Cancel booking"));
    const confirm = screen.getByText("Yes, cancel booking");
    fireEvent.press(confirm);
    fireEvent.press(confirm);
    await waitFor(() => expect(cancelRequests).toHaveLength(1), { timeout: 5_000 });
    releaseFirst();
    expect(await screen.findByText("Too many requests. Try again later.")).toBeTruthy();
    fireEvent.press(screen.getByText("Retry cancellation"));
    await waitFor(() => expect(cancelRequests).toHaveLength(2), { timeout: 5_000 });
    expect(cancelRequests[0]?.headers.get("Idempotency-Key")).toBe(idempotencyKey);
    expect(cancelRequests[1]?.headers.get("Idempotency-Key")).toBe(idempotencyKey);
    expect(await screen.findByText("Booking cancelled")).toBeTruthy();
  });

  it("shows mapped history events and the server response deadline", async () => {
    const pendingDetail = {
      ...detail,
      status: "PENDING" as const,
      venueResponseExpiresAt: "2026-10-12T17:10:00Z",
      history: [{
        at: "2026-10-12T17:00:00Z",
        kind: "BOOKING_EVENT" as const,
        eventType: "VENUE_RESPONSE_INTERRUPTED_BY_ACCOUNT_DISABLE" as const,
        fromStatus: null,
        toStatus: null,
        terminalReason: null,
      }],
    };
    const api = createCustomerApiClient(config, async (request: Request) => {
      if (request.url.includes("/bookings/b-1")) return response(pendingDetail);
      return response({ items: [{ ...booking, status: "PENDING", displayBucket: "upcoming" }], nextCursor: null });
    });
    render(<MyBookingsScreen api={api} />);

    fireEvent.press(await screen.findByText("Game Zone"));

    expect(await screen.findByText(/Due by .*8:10 PM/)).toBeTruthy();
    expect(screen.getByText(/Venue is temporarily unable to confirm this booking/)).toBeTruthy();
    expect(screen.queryByText("VENUE_RESPONSE_INTERRUPTED_BY_ACCOUNT_DISABLE")).toBeNull();
  });

  it("shows the account-disable expiry copy from history instead of ordinary non-response", async () => {
    const disabledExpiry = {
      ...detail,
      status: "VENUE_RESPONSE_EXPIRED" as const,
      displayBucket: "cancelled" as const,
      history: [{
        at: "2026-10-12T17:00:00Z",
        kind: "BOOKING_EVENT" as const,
        eventType: "VENUE_RESPONSE_INTERRUPTED_BY_ACCOUNT_DISABLE" as const,
        fromStatus: null,
        toStatus: null,
        terminalReason: null,
      }],
    };
    const api = createCustomerApiClient(config, async (request: Request) => request.url.includes("/bookings/b-1")
      ? response(disabledExpiry)
      : response({ items: [], nextCursor: null }));
    render(<MyBookingsScreen api={api} openBookingId="b-1" />);

    expect((await screen.findAllByText("Venue is temporarily unable to confirm this booking")).length).toBeGreaterThan(0);
    expect(screen.queryByText("Venue did not respond in time")).toBeNull();
  });

  it("shows operational cancellation and returns the Booking Fee to Wallet and Points", async () => {
    const cancelled = {
      ...detail,
      status: "CANCELLED" as const,
      displayBucket: "cancelled" as const,
      terminalReason: "VENUE_EMERGENCY_CLOSURE" as const,
      bookingFee: { amount: "20.00", currency: "EGP" as const, displayAsFree: false },
      bookingFeePayment: {
        pointsUsed: 10_000,
        paidByPoints: { amount: "10.00", currency: "EGP" as const },
        paidByWallet: { amount: "10.00", currency: "EGP" as const },
        leftToPay: { amount: "0.00", currency: "EGP" as const },
      },
    };
    const api = createCustomerApiClient(config, async (request: Request) => request.url.includes("/bookings/b-1")
      ? response(cancelled)
      : response({ items: [], nextCursor: null }));
    render(<MyBookingsScreen api={api} openBookingId="b-1" />);

    expect(await screen.findByText("Operational update")).toBeTruthy();
    expect(screen.getByText("Cancelled because of a venue emergency.")).toBeTruthy();
    expect(screen.getByText("Booking Fee refund")).toBeTruthy();
    expect(screen.getByText("10,000 points return to your Points balance.")).toBeTruthy();
    expect(screen.getByText("Any paid money part returns to your E7GZ Wallet balance.")).toBeTruthy();
    expect(screen.queryByText(/cash|card|bank/iu)).toBeNull();
  });

  it("keeps cancellation available without showing a stale venue-response countdown while paused", async () => {
    const paused = {
      ...detail,
      status: "PENDING" as const,
      venueResponseExpiresAt: "2026-10-12T17:05:00Z",
      venueResponsePaused: true,
    };
    const api = createCustomerApiClient(config, async (request: Request) => request.url.includes("/bookings/b-1")
      ? response(paused)
      : response({ items: [], nextCursor: null }));
    render(<MyBookingsScreen api={api} openBookingId="b-1" />);

    expect(await screen.findByText("Venue response countdown is paused.")).toBeTruthy();
    expect(screen.queryByText(/\d{2}:\d{2} remaining/u)).toBeNull();
    expect(screen.queryByText("Time remaining")).toBeNull();
    expect(screen.getByRole("button", { name: "Cancel booking" })).toBeTruthy();
  });
});
