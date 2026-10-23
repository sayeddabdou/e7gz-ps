import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { components } from "@e7gz/api-client";
import { messages, MessagesContext } from "../../i18n";
import { PendingRequestsScreen, type PendingRequestsViewModel } from "./PendingRequestsScreen";

type Booking = components["schemas"]["VenueBooking"];

const booking: Booking = {
  attendance: null,
  attendanceResult: null,
  bookingId: "018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e6f",
  businessDate: "2026-10-12",
  createdAt: "2026-10-12T15:00:00Z",
  customer: { username: "omar_k", phone: "+201001234567" },
  endAt: "2026-10-12T20:00:00Z",
  lateConfirmExpiresAt: null,
  lateConfirmPaused: false,
  nominalBookedMinutes: 120,
  rejectReasonText: null,
  resource: { activity: "PLAYSTATION", consoleType: "PS5", id: "r-1", name: "Room 3", playMode: "SINGLE" },
  source: "ONLINE",
  startAt: "2026-10-12T18:00:00Z",
  status: "PENDING",
  terminalReason: null,
  venuePriceTotal: { amount: "300.00", currency: "EGP" },
  venueResponseExpiresAt: "2026-10-12T15:10:00Z",
  venueResponsePaused: false,
  walkIn: null,
};

function model(overrides: Partial<PendingRequestsViewModel> = {}): PendingRequestsViewModel {
  return {
    bookings: [booking],
    bookingChangeVersion: 0,
    clockOffsetMs: Date.parse("2026-10-12T15:00:00Z") - Date.now(),
    error: undefined,
    loading: false,
    pendingAction: undefined,
    streamState: "connected",
    actionError: undefined,
    accept: vi.fn(),
    reject: vi.fn(),
    refresh: vi.fn(),
    ...overrides,
  };
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("Pending requests", () => {
  it("formats request times in the venue's Africa/Cairo timezone", () => {
    const format = vi.spyOn(Date.prototype, "toLocaleString");
    render(<MessagesContext.Provider value={messages.en}><PendingRequestsScreen model={model()} /></MessagesContext.Provider>);

    expect(format).toHaveBeenCalledWith("en-US", { timeZone: "Africa/Cairo" });
  });

  it("uses the server clock for the expiry countdown and requires a reject reason", () => {
    const viewModel = model();
    render(<MessagesContext.Provider value={messages.en}><PendingRequestsScreen model={viewModel} /></MessagesContext.Provider>);

    expect(screen.getByText("10:00")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Reject" }));
    fireEvent.click(screen.getByRole("button", { name: "Confirm rejection" }));
    expect(screen.getByText("Enter a reason before rejecting this request.")).toBeTruthy();
    expect(viewModel.reject).not.toHaveBeenCalled();

    fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "Room under maintenance" } });
    fireEvent.click(screen.getByRole("button", { name: "Confirm rejection" }));
    expect(viewModel.reject).toHaveBeenCalledWith(booking.bookingId, "Room under maintenance");
  });

  it("disables accept while the server says the response timer is paused but still allows rejection", () => {
    render(<MessagesContext.Provider value={messages.en}>
      <PendingRequestsScreen model={model({ bookings: [{ ...booking, venueResponsePaused: true }] })} />
    </MessagesContext.Provider>);

    expect((screen.getByRole("button", { name: "Accept" }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText("Accept is unavailable while the response timer is paused. You can still reject the request.")).toBeTruthy();
    expect((screen.getByRole("button", { name: "Reject" }) as HTMLButtonElement).disabled).toBe(false);
  });

  it("keeps a visible confirmation after browser notifications are enabled", () => {
    render(<MessagesContext.Provider value={messages.en}>
      <PendingRequestsScreen model={model({ pushState: "enabled" })} />
    </MessagesContext.Provider>);

    expect(screen.getByText("Browser notifications are enabled.")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Enable browser notifications" })).toBeNull();
  });

  it("keeps a visible confirmation after request sound is enabled", () => {
    render(<MessagesContext.Provider value={messages.en}>
      <PendingRequestsScreen model={model({ soundState: "enabled" })} />
    </MessagesContext.Provider>);

    expect(screen.getByText("Request sound is enabled.")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Enable sound" })).toBeNull();
  });

  it("renders the complete request flow in Arabic with logical CSS", () => {
    const { container } = render(
      <MessagesContext.Provider value={messages.ar}>
        <PendingRequestsScreen model={model()} />
      </MessagesContext.Provider>,
    );

    expect(screen.getByRole("heading", { name: "طلبات الحجز المعلقة" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "قبول" })).toBeTruthy();
    expect(container.innerHTML).not.toMatch(/(?:margin|padding|border)-(?:left|right)/u);
  });
});
