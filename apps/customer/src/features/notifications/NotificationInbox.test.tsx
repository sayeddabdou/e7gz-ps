import { fireEvent, render, screen, waitFor } from "@testing-library/react-native";
import { createCustomerApiClient } from "../../api";
import { NotificationInbox } from "./NotificationInbox";

const config = { apiBaseUrl: "http://127.0.0.1:4010" };
const meta = { correlationId: "c-1", serverTime: "2026-10-12T17:00:00Z" };
const response = (data: unknown, status = 200) => new Response(
  JSON.stringify(status < 400 ? { data, meta } : { error: data, meta }),
  { status, headers: { "Content-Type": "application/json" } },
);

describe("NotificationInbox", () => {
  it("renders code-mapped text, marks unread rows read, and opens referenced bookings", async () => {
    const requests: Request[] = [];
    const onBooking = jest.fn();
    const api = createCustomerApiClient(config, async (request: Request) => {
      requests.push(request);
      if (request.url.endsWith("/read")) return new Response(null, { status: 204 });
      return response({ items: [{
        id: "n-1", code: "BOOKING_CONFIRMED", bookingId: "b-1", tournamentId: null,
        params: { venueName: "Game Zone", message: "do not show this" }, readAt: null,
        createdAt: "2026-10-12T16:00:00Z",
      }], nextCursor: null });
    });
    render(<NotificationInbox api={api} onBack={() => undefined} onOpenBooking={onBooking} />);

    expect(await screen.findByText("The venue confirmed your booking at Game Zone.")).toBeTruthy();
    expect(screen.queryByText("do not show this")).toBeNull();
    fireEvent.press(screen.getByText("The venue confirmed your booking at Game Zone."));
    await waitFor(() => expect(requests.some((request) => request.url.endsWith("/notifications/n-1/read"))).toBe(true));
    expect(onBooking).toHaveBeenCalledWith("b-1");
  });

  it("supports empty and retryable error states", async () => {
    let failed = true;
    const api = createCustomerApiClient(config, async () => {
      if (failed) { failed = false; return response({ code: "RATE_LIMITED" }, 429); }
      return response({ items: [], nextCursor: null });
    });
    render(<NotificationInbox api={api} onBack={() => undefined} onOpenBooking={() => undefined} />);
    expect(await screen.findByText("Too many requests. Try again later.")).toBeTruthy();
    fireEvent.press(screen.getByText("Retry"));
    expect(await screen.findByText("No notifications yet")).toBeTruthy();
  });
});
