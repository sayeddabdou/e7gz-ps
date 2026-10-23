import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { ApiClient } from "../../api";
import { messages, MessagesContext } from "../../i18n";
import { NotificationsScreen } from "./NotificationsScreen";

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe("Venue notifications", () => {
  it("renders localized codes, marks unread notifications read, and opens the linked booking", async () => {
    const get = vi.fn().mockResolvedValue({ data: { data: { items: [{ bookingId: "b-1", code: "VENUE_NEW_BOOKING_REQUEST", createdAt: "2026-10-12T15:04:00Z", id: "n-1", params: {}, readAt: null, tournamentId: null }], nextCursor: null }, meta: { correlationId: "c", serverTime: "2026-10-12T15:05:00Z" } } });
    const post = vi.fn().mockResolvedValue({ response: { ok: true } });
    const open = vi.fn();
    render(<MessagesContext.Provider value={messages.en}><NotificationsScreen api={{ GET: get, POST: post } as unknown as ApiClient} onOpenBooking={open} /></MessagesContext.Provider>);

    expect(await screen.findByText("A customer sent a new booking request.")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Open booking" }));
    expect(open).toHaveBeenCalledWith("b-1");
    fireEvent.click(screen.getByRole("button", { name: "Mark as read" }));
    await waitFor(() => expect(post).toHaveBeenCalledWith("/api/v1/venue/notifications/{notificationId}/read", { params: { path: { notificationId: "n-1" } } }));
    expect(await screen.findByText("Read")).toBeTruthy();
  });

  it("uses safe fallback copy for an unknown code", async () => {
    const get = vi.fn().mockResolvedValue({ data: { data: { items: [{ bookingId: null, code: "FUTURE_CODE", createdAt: "2026-10-12T15:04:00Z", id: "n-2", params: {}, readAt: "2026-10-12T15:05:00Z", tournamentId: null }], nextCursor: null }, meta: { correlationId: "c", serverTime: "2026-10-12T15:05:00Z" } } });
    render(<MessagesContext.Provider value={messages.en}><NotificationsScreen api={{ GET: get } as unknown as ApiClient} onOpenBooking={vi.fn()} /></MessagesContext.Provider>);
    expect(await screen.findByText("A venue update is available.")).toBeTruthy();
    expect(screen.queryByText("FUTURE_CODE")).toBeNull();
  });
});
