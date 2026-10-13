import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { ApiClient } from "../../api";
import { messages, MessagesContext } from "../../i18n";
import { ManualBookingScreen } from "./ManualBookingScreen";

const resources = [
  { id: "r-1", name: "Room 1", activity: "PLAYSTATION", consoleType: "PS5", baseState: "ACTIVE", operationalRestriction: "NONE" },
  { id: "r-2", name: "Table 1", activity: "BILLIARDS", consoleType: null, baseState: "ACTIVE", operationalRestriction: "NONE" },
] as const;

function api(post = vi.fn().mockResolvedValue({
  data: { data: { bookingId: "b-1" }, meta: { correlationId: "c-2", serverTime: "2026-10-11T12:01:00Z" } },
})) {
  return {
    GET: vi.fn().mockResolvedValue({
      data: { data: resources, meta: { correlationId: "c-1", serverTime: "2026-10-11T12:00:00Z" } },
    }),
    POST: post,
  } as unknown as ApiClient;
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("Manual booking", () => {
  it("creates a start-now walk-in with an idempotency key and blocks double submission", async () => {
    let finish: ((value: unknown) => void) | undefined;
    const post = vi.fn().mockReturnValue(new Promise((resolve) => { finish = resolve; }));
    const client = api(post);
    const open = vi.fn();
    render(<MessagesContext.Provider value={messages.en}><ManualBookingScreen api={client} onOpenBooking={open} /></MessagesContext.Provider>);

    await screen.findByRole("option", { name: "Room 1 · PlayStation · PS5" });
    const submit = screen.getByRole("button", { name: "Create booking" });
    fireEvent.click(submit);
    fireEvent.click(submit);

    expect(post).toHaveBeenCalledTimes(1);
    const options = post.mock.calls[0]![1];
    expect(options.params.header["Idempotency-Key"]).toMatch(/^[0-9a-f-]{36}$/u);
    expect(options.body).toEqual({ durationMinutes: 60, playMode: "SINGLE", resourceId: "r-1" });
    expect((submit as HTMLButtonElement).disabled).toBe(true);
    finish?.({ data: { data: { bookingId: "b-1" }, meta: { correlationId: "c", serverTime: "2026-10-11T12:01:00Z" } } });
    await screen.findByText("Manual booking created.");
    expect((submit as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: "View created booking" }));
    expect(open).toHaveBeenCalledWith("b-1");
  });

  it("converts a future Cairo wall time to an instant and sends optional contact details", async () => {
    const client = api();
    render(<MessagesContext.Provider value={messages.en}><ManualBookingScreen api={client} onOpenBooking={vi.fn()} /></MessagesContext.Provider>);
    await screen.findByRole("option", { name: "Room 1 · PlayStation · PS5" });

    fireEvent.click(screen.getByLabelText("Future start"));
    fireEvent.change(screen.getByLabelText("Start date and time"), { target: { value: "2026-10-11T18:00" } });
    fireEvent.change(screen.getByLabelText("Customer name (optional)"), { target: { value: " Omar " } });
    fireEvent.change(screen.getByLabelText("Customer phone (optional)"), { target: { value: "01001234567" } });
    fireEvent.click(screen.getByRole("button", { name: "Create booking" }));

    await waitFor(() => expect(client.POST).toHaveBeenCalled());
    expect(vi.mocked(client.POST).mock.calls[0]![1]).toMatchObject({
      body: {
        customerName: "Omar",
        customerPhone: "01001234567",
        durationMinutes: 60,
        playMode: "SINGLE",
        resourceId: "r-1",
        startAt: "2026-10-11T15:00:00.000Z",
      },
    });
  });

  it("maps backend codes instead of showing backend prose", async () => {
    const client = api(vi.fn().mockResolvedValue({ error: { error: { code: "MANUAL_BOOKING_BACKDATING_NOT_ALLOWED", message: "raw backend text" } } }));
    render(<MessagesContext.Provider value={messages.en}><ManualBookingScreen api={client} onOpenBooking={vi.fn()} /></MessagesContext.Provider>);
    await screen.findByRole("option", { name: "Room 1 · PlayStation · PS5" });
    fireEvent.click(screen.getByRole("button", { name: "Create booking" }));

    expect((await screen.findByRole("alert")).textContent).toContain("A manual booking cannot start in the past.");
    expect(screen.queryByText("raw backend text")).toBeNull();
  });

  it("renders the form in Arabic without physical-direction CSS", async () => {
    const { container } = render(<MessagesContext.Provider value={messages.ar}><ManualBookingScreen api={api()} onOpenBooking={vi.fn()} /></MessagesContext.Provider>);
    await screen.findByRole("heading", { name: "إضافة حجز يدوي" });
    expect(screen.getByRole("button", { name: "إنشاء الحجز" })).toBeTruthy();
    expect(container.innerHTML).not.toMatch(/(?:margin|padding|border)-(?:left|right)/u);
  });

  it("selects the first eligible resource and disables unavailable options", async () => {
    const client = {
      GET: vi.fn().mockResolvedValue({ data: { data: [
        { ...resources[0], baseState: "DISABLED" },
        resources[1],
      ], meta: { correlationId: "c", serverTime: "2026-10-11T12:00:00Z" } } }),
      POST: vi.fn(),
    } as unknown as ApiClient;
    render(<MessagesContext.Provider value={messages.en}><ManualBookingScreen api={client} onOpenBooking={vi.fn()} /></MessagesContext.Provider>);

    const unavailable = await screen.findByRole("option", { name: /Room 1.*Unavailable/u });
    expect((unavailable as HTMLOptionElement).disabled).toBe(true);
    expect((screen.getByLabelText("Resource") as HTMLSelectElement).value).toBe("r-2");
  });
});
