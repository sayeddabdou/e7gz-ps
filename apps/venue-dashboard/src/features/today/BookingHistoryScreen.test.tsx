import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { components } from "@e7gz/api-client";
import type { ApiClient } from "../../api";
import { messages, MessagesContext } from "../../i18n";
import { BookingHistoryScreen, dateRangeFor } from "./BookingHistoryScreen";

type Booking = components["schemas"]["VenueBooking"];

const booking: Booking = {
  attendance: null, attendanceResult: null, bookingId: "b-1", businessDate: "2026-10-11", createdAt: "2026-10-11T18:00:00Z",
  customer: { username: "omar_k" }, endAt: "2026-10-11T20:00:00Z", nominalBookedMinutes: 60,
  lateConfirmExpiresAt: null, lateConfirmPaused: false,
  rejectReasonText: null, resource: { activity: "PLAYSTATION", consoleType: "PS5", id: "r-1", name: "Room 1", playMode: "SINGLE" },
  source: "ONLINE", startAt: "2026-10-11T19:00:00Z", status: "CONFIRMED", terminalReason: null,
  venuePriceTotal: { amount: "150.00", currency: "EGP" }, venueResponseExpiresAt: null, venueResponsePaused: false, walkIn: null,
};

function client(pages: Array<{ items: Booking[]; nextCursor: string | null }> = [{ items: [booking], nextCursor: null }]) {
  let page = 0;
  const get = vi.fn().mockImplementation((path: string) => {
    if (path === "/api/v1/venue/availability") return Promise.resolve({ data: { data: { businessDate: "2026-10-11", closed: false, resources: [] }, meta: { correlationId: "c-1", serverTime: "2026-10-11T22:00:00Z" } } });
    if (path === "/api/v1/venue/resources") return Promise.resolve({ data: { data: [{ id: "r-1", name: "Room 1", activity: "PLAYSTATION", consoleType: "PS5", baseState: "ACTIVE", operationalRestriction: "NONE" }], meta: { correlationId: "c-2", serverTime: "2026-10-11T22:00:00Z" } } });
    const answer = pages[Math.min(page, pages.length - 1)]!;
    page += 1;
    return Promise.resolve({ data: { data: answer, meta: { correlationId: "c-3", serverTime: "2026-10-11T22:00:00Z" } } });
  });
  return { api: { GET: get } as unknown as ApiClient, get };
}

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe("Booking history", () => {
  it("calculates every preset from the server business date", () => {
    expect(dateRangeFor("today", "2026-10-11")).toEqual({ from: "2026-10-11", to: "2026-10-11" });
    expect(dateRangeFor("yesterday", "2026-10-11")).toEqual({ from: "2026-10-10", to: "2026-10-10" });
    expect(dateRangeFor("last7", "2026-10-11")).toEqual({ from: "2026-10-05", to: "2026-10-11" });
    expect(dateRangeFor("month", "2026-10-11")).toEqual({ from: "2026-10-01", to: "2026-10-31" });
  });

  it("sends every FR-VEN-38 filter and only approved search hints", async () => {
    const { api, get } = client();
    render(<MessagesContext.Provider value={messages.en}><BookingHistoryScreen api={api} onOpenBooking={vi.fn()} /></MessagesContext.Provider>);
    await screen.findByText("omar_k");

    expect(screen.getByText(/booking ID, customer username, walk-in name or phone, or resource name/i)).toBeTruthy();
    expect(screen.queryByText(/customer phone/i)).toBeNull();
    fireEvent.change(screen.getByLabelText("Date range"), { target: { value: "yesterday" } });
    fireEvent.change(screen.getByLabelText("Activity"), { target: { value: "PLAYSTATION" } });
    fireEvent.change(screen.getByLabelText("Source"), { target: { value: "MANUAL" } });
    fireEvent.change(screen.getByLabelText("Resource"), { target: { value: "r-1" } });
    fireEvent.change(screen.getByLabelText("Status"), { target: { value: "CONFIRMED" } });
    fireEvent.change(screen.getByLabelText("Search"), { target: { value: "  Omar  " } });
    fireEvent.click(screen.getByRole("button", { name: "Apply filters" }));

    await waitFor(() => expect(get).toHaveBeenCalledTimes(4));
    expect(get.mock.calls[3]![1]).toEqual({ params: { query: {
      activity: "PLAYSTATION", from: "2026-10-10", limit: 100, q: "Omar", resourceId: "r-1",
      source: "MANUAL", status: "CONFIRMED", to: "2026-10-10",
    } } });
  });

  it("uses the returned cursor to load more", async () => {
    const { api, get } = client([{ items: [booking], nextCursor: "next" }, { items: [{ ...booking, bookingId: "b-2" }], nextCursor: null }]);
    render(<MessagesContext.Provider value={messages.en}><BookingHistoryScreen api={api} onOpenBooking={vi.fn()} /></MessagesContext.Provider>);
    fireEvent.click(await screen.findByRole("button", { name: "Load more" }));
    await waitFor(() => expect(screen.getAllByText("omar_k")).toHaveLength(2));
    expect(get.mock.calls[3]![1]).toEqual({ params: { query: { cursor: "next", from: "2026-10-11", limit: 100, to: "2026-10-11" } } });
  });

  it("limits search, validates custom date order, and blocks duplicate Apply requests", async () => {
    const { api, get } = client();
    render(<MessagesContext.Provider value={messages.en}><BookingHistoryScreen api={api} onOpenBooking={vi.fn()} /></MessagesContext.Provider>);
    await screen.findByText("omar_k");

    expect(screen.getByLabelText("Search").getAttribute("maxlength")).toBe("100");
    fireEvent.change(screen.getByLabelText("Date range"), { target: { value: "custom" } });
    fireEvent.change(screen.getByLabelText("From business date"), { target: { value: "2026-10-12" } });
    fireEvent.change(screen.getByLabelText("To business date"), { target: { value: "2026-10-11" } });
    fireEvent.click(screen.getByRole("button", { name: "Apply filters" }));
    expect((await screen.findByRole("alert")).textContent).toContain("must be on or before");
    expect(get).toHaveBeenCalledTimes(3);

    let finish: ((value: unknown) => void) | undefined;
    get.mockReturnValueOnce(new Promise((resolve) => { finish = resolve; }));
    fireEvent.change(screen.getByLabelText("From business date"), { target: { value: "2026-10-10" } });
    const apply = screen.getByRole("button", { name: "Apply filters" });
    fireEvent.click(apply);
    fireEvent.click(apply);
    expect(get).toHaveBeenCalledTimes(4);
    expect((screen.getByRole("button", { name: "Applying…" }) as HTMLButtonElement).disabled).toBe(true);
    finish?.({ data: { data: { items: [booking], nextCursor: null }, meta: { correlationId: "c", serverTime: "2026-10-11T22:00:00Z" } } });
    await screen.findByText("omar_k");
  });

  it("renders the filters in Arabic with logical CSS", async () => {
    const { api } = client();
    const { container } = render(<MessagesContext.Provider value={messages.ar}><BookingHistoryScreen api={api} onOpenBooking={vi.fn()} /></MessagesContext.Provider>);
    await screen.findByRole("heading", { name: "سجل الحجوزات" });
    expect(screen.getByRole("button", { name: "تطبيق الفلاتر" })).toBeTruthy();
    expect(container.innerHTML).not.toMatch(/(?:margin|padding|border)-(?:left|right)/u);
  });
});
