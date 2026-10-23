import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { components } from "@e7gz/api-client";
import type { ApiClient } from "../../api";
import { messages, MessagesContext } from "../../i18n";
import { MissedRequestsScreen } from "./MissedRequestsScreen";

type Booking = components["schemas"]["VenueBooking"];
const booking: Booking = {
  attendance: null, attendanceResult: null, bookingId: "b-1", businessDate: "2026-10-12", createdAt: "2026-10-12T15:00:00Z",
  customer: { username: "omar_k", phone: "+201001234567" }, endAt: "2026-10-12T20:00:00Z", lateConfirmExpiresAt: null, lateConfirmPaused: false,
  nominalBookedMinutes: 120, rejectReasonText: null, resource: { activity: "PLAYSTATION", consoleType: "PS5", id: "r-1", name: "Room 3", playMode: "SINGLE" },
  source: "ONLINE", startAt: "2026-10-12T18:00:00Z", status: "VENUE_RESPONSE_EXPIRED", terminalReason: null,
  venuePriceTotal: { amount: "300.00", currency: "EGP" }, venueResponseExpiresAt: null, venueResponsePaused: false, walkIn: null,
};

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe("Missed requests", () => {
  it("uses server time, shows returned contact data, and refreshes on a booking signal", async () => {
    const get = vi.fn().mockResolvedValue({ data: { data: [booking], meta: { correlationId: "c", serverTime: "2026-10-12T17:55:00Z" } } });
    const client = { GET: get } as unknown as ApiClient;
    const { rerender } = render(<MessagesContext.Provider value={messages.en}><MissedRequestsScreen api={client} bookingChangeVersion={0} /></MessagesContext.Provider>);
    expect(await screen.findByText("omar_k")).toBeTruthy();
    expect(screen.getByText("+201001234567")).toBeTruthy();
    expect(screen.getByLabelText("Time until booking starts").textContent).toBe("05:00");
    rerender(<MessagesContext.Provider value={messages.en}><MissedRequestsScreen api={client} bookingChangeVersion={1} /></MessagesContext.Provider>);
    await waitFor(() => expect(get).toHaveBeenCalledTimes(2));
  });

  it("sends an idempotency key and maps ineligible Late Confirm neutrally", async () => {
    const get = vi.fn().mockResolvedValue({ data: { data: [booking], meta: { correlationId: "c", serverTime: "2026-10-12T17:55:00Z" } } });
    const post = vi.fn().mockResolvedValue({ error: { error: { code: "CUSTOMER_NOT_ELIGIBLE_FOR_LATE_CONFIRM" } }, response: { status: 422 } });
    render(<MessagesContext.Provider value={messages.en}><MissedRequestsScreen api={{ GET: get, POST: post } as unknown as ApiClient} bookingChangeVersion={0} /></MessagesContext.Provider>);
    fireEvent.click(await screen.findByRole("button", { name: "Initiate Late Confirm" }));
    expect(await screen.findByText("Late Confirm is not available for this request.")).toBeTruthy();
    expect(post.mock.calls[0]![1].params.header["Idempotency-Key"]).toMatch(/^[0-9a-f-]{36}$/u);
  });

  it("maps a Late Confirm resource conflict and refreshes the list", async () => {
    const get = vi.fn().mockResolvedValue({ data: { data: [booking], meta: { correlationId: "c", serverTime: "2026-10-12T17:55:00Z" } } });
    const post = vi.fn().mockResolvedValue({ error: { error: { code: "LATE_CONFIRM_RESOURCE_CONFLICT" } }, response: { status: 409 } });
    render(<MessagesContext.Provider value={messages.en}><MissedRequestsScreen api={{ GET: get, POST: post } as unknown as ApiClient} bookingChangeVersion={0} /></MessagesContext.Provider>);
    fireEvent.click(await screen.findByRole("button", { name: "Initiate Late Confirm" }));
    expect(await screen.findByText("The room is no longer free for the original time.")).toBeTruthy();
    await waitFor(() => expect(get).toHaveBeenCalledTimes(2));
  });

  it("requires confirmation before rejecting after expiry", async () => {
    const get = vi.fn().mockResolvedValue({ data: { data: [booking], meta: { correlationId: "c", serverTime: "2026-10-12T17:55:00Z" } } });
    const post = vi.fn().mockResolvedValue({ data: { data: booking, meta: { correlationId: "c", serverTime: "2026-10-12T17:55:01Z" } } });
    render(<MessagesContext.Provider value={messages.en}><MissedRequestsScreen api={{ GET: get, POST: post } as unknown as ApiClient} bookingChangeVersion={0} /></MessagesContext.Provider>);
    fireEvent.click(await screen.findByRole("button", { name: "Reject after expiry" }));
    expect(post).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Confirm rejection" }));
    await waitFor(() => expect(post).toHaveBeenCalledTimes(1));
    expect(await screen.findByText("The expired request was rejected.")).toBeTruthy();
  });
});
