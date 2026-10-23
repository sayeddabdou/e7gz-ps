import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { components } from "@e7gz/api-client";
import type { ApiClient } from "../../api";
import { messages, MessagesContext } from "../../i18n";
import { BookingDetailScreen, TodayBookingsScreen } from "./TodayBookingsScreen";

type Booking = components["schemas"]["VenueBooking"];

const booking: Booking = {
  attendance: null,
  attendanceResult: null,
  bookingId: "b-1",
  businessDate: "2026-10-12",
  createdAt: "2026-10-11T21:30:00Z",
  customer: { username: "omar_k", phone: "+201001234567" },
  endAt: "2026-10-12T00:30:00Z",
  lateConfirmExpiresAt: null,
  lateConfirmPaused: false,
  nominalBookedMinutes: 60,
  rejectReasonText: null,
  resource: { activity: "PLAYSTATION", consoleType: "PS5", id: "r-1", name: "Room 1", playMode: "SINGLE" },
  source: "ONLINE",
  startAt: "2026-10-11T23:30:00Z",
  status: "CONFIRMED",
  terminalReason: null,
  venuePriceTotal: { amount: "150.00", currency: "EGP" },
  venueResponseExpiresAt: null,
  venueResponsePaused: false,
  walkIn: null,
};

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("Today's bookings", () => {
  it("uses the server's current business date through an overnight session, follows pagination, and groups by resource", async () => {
    const get = vi.fn()
      // 22:00Z is 01:00 Cairo on Oct 12, but the open 18:00–02:00 session still belongs to Oct 11.
      .mockResolvedValueOnce({ data: { data: { businessDate: "2026-10-11", closed: false, resources: [] }, meta: { correlationId: "c-1", serverTime: "2026-10-11T22:00:00Z" } } })
      .mockResolvedValueOnce({ data: { data: { items: [booking], nextCursor: "next" }, meta: { correlationId: "c-2", serverTime: "2026-10-11T22:00:01Z" } } })
      .mockResolvedValueOnce({ data: { data: { items: [{ ...booking, bookingId: "b-2" }], nextCursor: null }, meta: { correlationId: "c-3", serverTime: "2026-10-11T22:00:02Z" } } });
    const client = { GET: get } as unknown as ApiClient;
    render(<MessagesContext.Provider value={messages.en}><TodayBookingsScreen api={client} onOpenBooking={vi.fn()} /></MessagesContext.Provider>);

    expect(await screen.findByRole("heading", { name: "Room 1" })).toBeTruthy();
    expect(screen.getAllByText("omar_k")).toHaveLength(2);
    expect(get.mock.calls[0]).toEqual(["/api/v1/venue/availability"]);
    expect(get.mock.calls[1]![1]).toEqual({ params: { query: { from: "2026-10-11", limit: 100, to: "2026-10-11" } } });
    expect(get.mock.calls[2]![1]).toEqual({ params: { query: { cursor: "next", from: "2026-10-11", limit: 100, to: "2026-10-11" } } });
  });

  it("stops when a mock repeats its pagination cursor", async () => {
    const get = vi.fn()
      .mockResolvedValueOnce({ data: { data: { businessDate: "2026-10-12", closed: false, resources: [] }, meta: { correlationId: "c-1", serverTime: "2026-10-12T10:00:00Z" } } })
      .mockResolvedValue({ data: { data: { items: [booking], nextCursor: "repeated" }, meta: { correlationId: "c-2", serverTime: "2026-10-12T10:00:01Z" } } });
    render(<MessagesContext.Provider value={messages.en}><TodayBookingsScreen api={{ GET: get } as unknown as ApiClient} onOpenBooking={vi.fn()} /></MessagesContext.Provider>);
    expect(await screen.findByText("omar_k")).toBeTruthy();
    expect(get).toHaveBeenCalledTimes(3);
    expect(screen.getAllByText("omar_k")).toHaveLength(1);
  });

  it("opens booking detail from a booking card", async () => {
    const open = vi.fn();
    const client = { GET: vi.fn()
      .mockResolvedValueOnce({ data: { data: { businessDate: "2026-10-12", closed: false, resources: [] }, meta: { correlationId: "c-1", serverTime: "2026-10-11T21:30:00Z" } } })
      .mockResolvedValueOnce({ data: { data: { items: [booking], nextCursor: null }, meta: { correlationId: "c-2", serverTime: "2026-10-11T21:30:01Z" } } }) } as unknown as ApiClient;
    render(<MessagesContext.Provider value={messages.en}><TodayBookingsScreen api={client} onOpenBooking={open} /></MessagesContext.Provider>);
    fireEvent.click(await screen.findByRole("button", { name: "View booking" }));
    expect(open).toHaveBeenCalledWith("b-1");
  });

  it("refreshes today's data on demand", async () => {
    const get = vi.fn()
      .mockResolvedValueOnce({ data: { data: { businessDate: "2026-10-12", closed: false, resources: [] }, meta: { correlationId: "c-1", serverTime: "2026-10-12T10:00:00Z" } } })
      .mockResolvedValueOnce({ data: { data: { items: [booking], nextCursor: null }, meta: { correlationId: "c-2", serverTime: "2026-10-12T10:00:01Z" } } })
      .mockResolvedValueOnce({ data: { data: { businessDate: "2026-10-12", closed: false, resources: [] }, meta: { correlationId: "c-3", serverTime: "2026-10-12T10:01:00Z" } } })
      .mockResolvedValueOnce({ data: { data: { items: [], nextCursor: null }, meta: { correlationId: "c-4", serverTime: "2026-10-12T10:01:01Z" } } });
    render(<MessagesContext.Provider value={messages.en}><TodayBookingsScreen api={{ GET: get } as unknown as ApiClient} onOpenBooking={vi.fn()} /></MessagesContext.Provider>);
    await screen.findByText("omar_k");
    fireEvent.click(screen.getByRole("button", { name: "Refresh" }));
    expect(await screen.findByText("No bookings are scheduled for today's business date.")).toBeTruthy();
    expect(get).toHaveBeenCalledTimes(4);
  });
});

describe("Booking detail", () => {
  it.each(["CANCELLED", "VENUE_RESPONSE_EXPIRED", "COMPLETED", "REJECTED"] as const)("hides check-in for a %s booking even when its API window is open", async (status) => {
    const attendanceBooking = { ...booking, status, attendance: { checkInWindow: { opensAt: "2026-10-12T18:00:00Z", closesAt: "2026-10-12T18:15:00Z" }, lateWindow: { opensAt: "2026-10-12T18:15:00Z", closesAt: "2026-10-12T19:00:00Z" }, checkedInAt: null, latePresentAt: null, noShowAt: null } };
    const get = vi.fn().mockResolvedValue({ data: { data: { booking: attendanceBooking, history: [] }, meta: { correlationId: "c", serverTime: "2026-10-12T18:05:00Z" } } });
    render(<MessagesContext.Provider value={messages.en}><BookingDetailScreen api={{ GET: get } as unknown as ApiClient} bookingId="b-1" onBack={vi.fn()} /></MessagesContext.Provider>);
    await screen.findByText(messages.en.bookings.today.statuses[status]);
    expect(screen.queryByRole("button", { name: "Check in" })).toBeNull();
  });

  it("hides late-present and no-show for a cancelled booking even when its API window is open", async () => {
    const attendanceBooking = { ...booking, status: "CANCELLED" as const, attendance: { checkInWindow: { opensAt: "2026-10-12T18:00:00Z", closesAt: "2026-10-12T18:15:00Z" }, lateWindow: { opensAt: "2026-10-12T18:15:00Z", closesAt: "2026-10-12T19:00:00Z" }, checkedInAt: null, latePresentAt: null, noShowAt: null } };
    const get = vi.fn().mockResolvedValue({ data: { data: { booking: attendanceBooking, history: [] }, meta: { correlationId: "c", serverTime: "2026-10-12T18:20:00Z" } } });
    render(<MessagesContext.Provider value={messages.en}><BookingDetailScreen api={{ GET: get } as unknown as ApiClient} bookingId="b-1" onBack={vi.fn()} /></MessagesContext.Provider>);
    await screen.findByText("Cancelled");
    expect(screen.queryByRole("button", { name: "Mark present — late" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Mark no-show" })).toBeNull();
  });

  it("uses API attendance windows and server time for check-in", async () => {
    const attendanceBooking = { ...booking, status: "ACTIVE" as const, attendance: { checkInWindow: { opensAt: "2026-10-12T18:00:00Z", closesAt: "2026-10-12T18:15:00Z" }, lateWindow: { opensAt: "2026-10-12T18:15:00Z", closesAt: "2026-10-12T19:00:00Z" }, checkedInAt: null, latePresentAt: null, noShowAt: null } };
    const get = vi.fn().mockResolvedValue({ data: { data: { booking: attendanceBooking, history: [] }, meta: { correlationId: "c", serverTime: "2026-10-12T18:05:00Z" } } });
    const post = vi.fn().mockResolvedValue({ data: { data: { bookingId: "b-1", status: "ACTIVE", attendanceResult: null, checkedInAt: "2026-10-12T18:05:00Z", latePresentAt: null, noShowAt: null }, meta: { correlationId: "c", serverTime: "2026-10-12T18:05:00Z" } } });
    render(<MessagesContext.Provider value={messages.en}><BookingDetailScreen api={{ GET: get, POST: post } as unknown as ApiClient} bookingId="b-1" onBack={vi.fn()} /></MessagesContext.Provider>);
    fireEvent.click(await screen.findByRole("button", { name: "Check in" }));
    await waitFor(() => expect(post).toHaveBeenCalledWith("/api/v1/venue/bookings/{bookingId}/check-in", expect.objectContaining({ params: { header: { "Idempotency-Key": expect.stringMatching(/^[0-9a-f-]{36}$/u) }, path: { bookingId: "b-1" } } })));
  });

  it("requires confirmation before recording a no-show in the API late window", async () => {
    const attendanceBooking = { ...booking, status: "ACTIVE" as const, attendance: { checkInWindow: { opensAt: "2026-10-12T18:00:00Z", closesAt: "2026-10-12T18:15:00Z" }, lateWindow: { opensAt: "2026-10-12T18:15:00Z", closesAt: "2026-10-12T19:00:00Z" }, checkedInAt: null, latePresentAt: null, noShowAt: null } };
    const get = vi.fn().mockResolvedValue({ data: { data: { booking: attendanceBooking, history: [] }, meta: { correlationId: "c", serverTime: "2026-10-12T18:20:00Z" } } });
    const post = vi.fn().mockResolvedValue({ data: { data: { bookingId: "b-1", status: "NO_SHOW", attendanceResult: "ABSENT_NO_SHOW", checkedInAt: null, latePresentAt: null, noShowAt: "2026-10-12T18:20:00Z" }, meta: { correlationId: "c", serverTime: "2026-10-12T18:20:00Z" } } });
    render(<MessagesContext.Provider value={messages.en}><BookingDetailScreen api={{ GET: get, POST: post } as unknown as ApiClient} bookingId="b-1" onBack={vi.fn()} /></MessagesContext.Provider>);
    fireEvent.click(await screen.findByRole("button", { name: "Mark no-show" }));
    expect(post).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Confirm no-show" }));
    await waitFor(() => expect(post.mock.calls[0]![0]).toBe("/api/v1/venue/bookings/{bookingId}/no-show"));
  });

  it("requires a four-digit Owner PIN to cancel a confirmed online booking", async () => {
    const get = vi.fn().mockResolvedValue({ data: { data: { booking, history: [] }, meta: { correlationId: "c", serverTime: "2026-10-12T17:00:00Z" } } });
    const post = vi.fn().mockResolvedValue({ data: { data: { ...booking, status: "CANCELLED" }, meta: { correlationId: "c", serverTime: "2026-10-12T17:00:01Z" } } });
    render(<MessagesContext.Provider value={messages.en}><BookingDetailScreen api={{ GET: get, POST: post } as unknown as ApiClient} bookingId="b-1" onBack={vi.fn()} /></MessagesContext.Provider>);
    fireEvent.click(await screen.findByRole("button", { name: "Cancel confirmed booking" }));
    const confirm = screen.getByRole("button", { name: "Confirm booking cancellation" });
    expect(confirm.hasAttribute("disabled")).toBe(true);
    fireEvent.change(screen.getByLabelText("Owner PIN"), { target: { value: "1234" } });
    fireEvent.click(confirm);
    await waitFor(() => expect(post).toHaveBeenCalledWith("/api/v1/venue/bookings/{bookingId}/cancel-confirmed", expect.objectContaining({ body: { ownerPassword: "1234" } })));
    expect(await screen.findByText("The confirmed booking was cancelled.")).toBeTruthy();
  });

  it("clears an invalid Owner PIN and does not reload on a 423", async () => {
    const get = vi.fn().mockResolvedValue({ data: { data: { booking, history: [] }, meta: { correlationId: "c", serverTime: "2026-10-12T17:00:00Z" } } });
    const post = vi.fn().mockResolvedValue({ error: { error: { code: "OWNER_PASSWORD_INVALID" } }, response: { status: 423 } });
    render(<MessagesContext.Provider value={messages.en}><BookingDetailScreen api={{ GET: get, POST: post } as unknown as ApiClient} bookingId="b-1" onBack={vi.fn()} /></MessagesContext.Provider>);
    fireEvent.click(await screen.findByRole("button", { name: "Cancel confirmed booking" }));
    const input = screen.getByLabelText("Owner PIN") as HTMLInputElement;
    fireEvent.change(input, { target: { value: "1234" } });
    fireEvent.click(screen.getByRole("button", { name: "Confirm booking cancellation" }));
    expect(await screen.findByText("The Owner PIN is incorrect.")).toBeTruthy();
    expect(input.value).toBe("");
    expect(get).toHaveBeenCalledTimes(1);
  });

  it("confirms manual cancellation and keeps its idempotency key for a network retry", async () => {
    const manual = { ...booking, source: "MANUAL" as const, customer: null, walkIn: { name: "Omar", phone: null } };
    const get = vi.fn().mockResolvedValue({ data: { data: { booking: manual, history: [] }, meta: { correlationId: "c", serverTime: "2026-10-12T17:00:00Z" } } });
    const post = vi.fn().mockRejectedValueOnce(new Error("offline")).mockResolvedValueOnce({ data: { data: { ...manual, status: "CANCELLED" }, meta: { correlationId: "c", serverTime: "2026-10-12T17:00:01Z" } } });
    render(<MessagesContext.Provider value={messages.en}><BookingDetailScreen api={{ GET: get, POST: post } as unknown as ApiClient} bookingId="b-1" onBack={vi.fn()} /></MessagesContext.Provider>);
    fireEvent.click(await screen.findByRole("button", { name: "Cancel walk-in" }));
    fireEvent.click(screen.getByRole("button", { name: "Confirm walk-in cancellation" }));
    expect(await screen.findByText("The server could not be reached.")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Confirm walk-in cancellation" }));
    await waitFor(() => expect(post).toHaveBeenCalledTimes(2));
    expect(post.mock.calls[0]![1].params.header["Idempotency-Key"]).toBe(post.mock.calls[1]![1].params.header["Idempotency-Key"]);
    expect(await screen.findByText("The walk-in booking was cancelled.")).toBeTruthy();
  });

  it("shows the customer name while hiding the contact block when phone is absent", async () => {
    const get = vi.fn().mockResolvedValue({ data: { data: { booking, history: [] }, meta: { correlationId: "c", serverTime: "2026-10-11T21:30:00Z" } } });
    const { rerender } = render(<MessagesContext.Provider value={messages.en}><BookingDetailScreen api={{ GET: get } as unknown as ApiClient} bookingId="b-1" onBack={vi.fn()} /></MessagesContext.Provider>);
    expect(await screen.findByText("+201001234567")).toBeTruthy();

    get.mockResolvedValue({ data: { data: { booking: { ...booking, customer: { username: "omar_k" } }, history: [] }, meta: { correlationId: "c", serverTime: "2026-10-11T21:30:00Z" } } });
    rerender(<MessagesContext.Provider value={messages.en}><BookingDetailScreen api={{ GET: get } as unknown as ApiClient} bookingId="b-2" onBack={vi.fn()} /></MessagesContext.Provider>);
    await waitFor(() => expect(screen.queryByText("+201001234567")).toBeNull());
    expect(screen.getByText("omar_k")).toBeTruthy();
    expect(screen.queryByText("Customer phone")).toBeNull();
    expect(screen.queryByRole("heading", { name: "Customer contact" })).toBeNull();
  });

  it("renders mapped append-only history without exposing raw event codes", async () => {
    const history = [
      { at: "2026-10-11T18:00:00Z", kind: "STATUS_TRANSITION" as const, fromStatus: null, toStatus: "PENDING" as const, terminalReason: null, eventType: null },
      { at: "2026-10-11T18:05:00Z", kind: "BOOKING_EVENT" as const, fromStatus: null, toStatus: null, terminalReason: null, eventType: "CHECK_IN_RECORDED" as const },
    ];
    const get = vi.fn().mockResolvedValue({ data: { data: { booking, history }, meta: { correlationId: "c", serverTime: "2026-10-11T21:30:00Z" } } });
    render(<MessagesContext.Provider value={messages.en}><BookingDetailScreen api={{ GET: get } as unknown as ApiClient} bookingId="b-1" onBack={vi.fn()} /></MessagesContext.Provider>);

    expect(await screen.findByRole("heading", { name: "Booking history" })).toBeTruthy();
    expect(screen.getByText(/Check-in recorded/u)).toBeTruthy();
    expect(screen.queryByText(/CHECK_IN_RECORDED/u)).toBeNull();
  });

  it("maps terminal reasons and shows the venue rejection text", async () => {
    const rejected = { ...booking, status: "REJECTED" as const, terminalReason: "VENUE_REJECTED" as const, rejectReasonText: "Room under maintenance" };
    const get = vi.fn().mockResolvedValue({ data: { data: { booking: rejected, history: [] }, meta: { correlationId: "c", serverTime: "2026-10-11T21:30:00Z" } } });
    render(<MessagesContext.Provider value={messages.en}><BookingDetailScreen api={{ GET: get } as unknown as ApiClient} bookingId="b-1" onBack={vi.fn()} /></MessagesContext.Provider>);

    expect(await screen.findByText("Rejected by the venue")).toBeTruthy();
    expect(screen.getByText("Room under maintenance")).toBeTruthy();
    expect(screen.queryByText("VENUE_REJECTED")).toBeNull();
  });
});
