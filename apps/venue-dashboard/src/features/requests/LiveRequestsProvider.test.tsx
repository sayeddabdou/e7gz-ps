import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { ApiClient } from "../../api";
import type { components } from "@e7gz/api-client";
import { messages, MessagesContext } from "../../i18n";
import { createIdempotencyKey, LiveRequestsProvider, useLiveRequests } from "./LiveRequestsProvider";

function Harness() {
  const model = useLiveRequests();
  return <>
    <span>{model.loading ? "loading" : "ready"}</span>
    <span data-testid="booking-count">{model.bookings.length}</span>
    <span data-testid="stream-state">{model.streamState}</span>
    <button onClick={() => model.accept("b-1")} type="button">accept</button>
    <button onClick={model.enableSound} type="button">enable sound</button>
    {model.actionError ? <span>{model.actionError}</span> : null}
  </>;
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("live requests controller", () => {
  it("uses secure random bytes when randomUUID is unavailable", () => {
    let seed = 0;
    const getRandomValues = ((bytes: Uint8Array) => {
      bytes.forEach((_value, index) => { bytes[index] = (seed + index) % 256; });
      seed += 16;
      return bytes;
    }) as Crypto["getRandomValues"];
    vi.stubGlobal("crypto", {
      getRandomValues,
    } as Crypto);

    const first = createIdempotencyKey();
    const second = createIdempotencyKey();
    expect(first).not.toBe(second);
    expect(first).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/u);
  });

  it("keeps a successfully handled request removed when a stateless mock returns it again", async () => {
    const booking: components["schemas"]["VenueBooking"] = {
      attendanceResult: null,
      bookingId: "b-1",
      businessDate: "2026-10-12",
      createdAt: "2026-10-12T15:00:00Z",
      customer: { username: "omar_k", phone: "+201001234567" },
      endAt: "2026-10-12T20:00:00Z",
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
    const api = {
      GET: vi.fn().mockResolvedValue({
        data: { data: [booking], meta: { correlationId: "c-1", serverTime: "2026-10-12T15:00:00Z" } },
      }),
      POST: vi.fn().mockResolvedValue({
        data: { data: booking, meta: { correlationId: "c-2", serverTime: "2026-10-12T15:00:01Z" } },
      }),
    } as unknown as ApiClient;

    render(<MessagesContext.Provider value={messages.en}>
      <LiveRequestsProvider api={api} streamRequest={() => new Promise<Response>(() => undefined)}>
        <Harness />
      </LiveRequestsProvider>
    </MessagesContext.Provider>);

    await waitFor(() => expect(screen.getByTestId("booking-count").textContent).toBe("1"));
    fireEvent.click(screen.getByRole("button", { name: "accept" }));
    await waitFor(() => expect(screen.getByTestId("booking-count").textContent).toBe("0"));
    expect(api.POST).toHaveBeenCalledTimes(1);
  });

  it("does not refetch booking requests for a tournament signal", async () => {
    const api = {
      GET: vi.fn().mockResolvedValue({
        data: { data: [], meta: { correlationId: "c-1", serverTime: "2026-10-12T15:00:00Z" } },
      }),
      POST: vi.fn(),
    } as unknown as ApiClient;
    const streamRequest = vi.fn().mockResolvedValue(new Response(
      "event: change\ndata: {\"type\":\"TOURNAMENT\",\"id\":\"t-1\",\"reason\":\"REQUESTED\"}\n\n",
      { status: 200 },
    ));

    render(<MessagesContext.Provider value={messages.en}>
      <LiveRequestsProvider api={api} streamRequest={streamRequest} pollMilliseconds={60_000}>
        <Harness />
      </LiveRequestsProvider>
    </MessagesContext.Provider>);

    await waitFor(() => expect(screen.getByText("ready")).toBeTruthy());
    await waitFor(() => expect(streamRequest).toHaveBeenCalledTimes(1));
    expect(api.GET).toHaveBeenCalledTimes(1);
  });

  it("blocks double-submit and reuses the same Idempotency-Key when the same action is retried", async () => {
    const keys: string[] = [];
    let attempts = 0;
    const api = {
      GET: vi.fn().mockResolvedValue({
        data: { data: [], meta: { correlationId: "c-1", serverTime: "2026-10-12T15:00:00Z" } },
      }),
      POST: vi.fn().mockImplementation((_path: string, options: { params: { header: { "Idempotency-Key": string } } }) => {
        keys.push(options.params.header["Idempotency-Key"]);
        attempts += 1;
        return attempts === 1
          ? Promise.resolve({ error: { error: { code: "RATE_LIMITED" } } })
          : Promise.resolve({ data: { data: {}, meta: { correlationId: "c-2", serverTime: "2026-10-12T15:00:01Z" } } });
      }),
    } as unknown as ApiClient;

    render(<MessagesContext.Provider value={messages.en}>
      <LiveRequestsProvider api={api} streamRequest={async () => new Response(undefined, { status: 503 })}>
        <Harness />
      </LiveRequestsProvider>
    </MessagesContext.Provider>);

    await waitFor(() => expect(screen.getByText("ready")).toBeTruthy());
    const accept = screen.getByRole("button", { name: "accept" });
    fireEvent.click(accept);
    fireEvent.click(accept);
    await waitFor(() => expect(keys).toHaveLength(1));
    await waitFor(() => expect(screen.getByText("Too many attempts. Try again later.")).toBeTruthy());

    fireEvent.click(accept);
    await waitFor(() => expect(keys).toHaveLength(2));
    expect(keys[1]).toBe(keys[0]);
  });

  it("refetches after every 422 returned by Accept", async () => {
    const api = {
      GET: vi.fn().mockResolvedValue({
        data: { data: [], meta: { correlationId: "c-1", serverTime: "2026-10-12T15:00:00Z" } },
      }),
      POST: vi.fn().mockResolvedValue({
        error: { error: { code: "RESOURCE_NOT_ELIGIBLE" } },
        response: new Response(undefined, { status: 422 }),
      }),
    } as unknown as ApiClient;

    render(<MessagesContext.Provider value={messages.en}>
      <LiveRequestsProvider api={api} streamRequest={() => new Promise<Response>(() => undefined)}>
        <Harness />
      </LiveRequestsProvider>
    </MessagesContext.Provider>);

    await waitFor(() => expect(api.GET).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByRole("button", { name: "accept" }));
    await waitFor(() => expect(api.GET).toHaveBeenCalledTimes(2));
  });

  it("stops reconnecting and shows an unavailable state when the stream returns 403", async () => {
    const api = {
      GET: vi.fn().mockResolvedValue({
        data: { data: [], meta: { correlationId: "c-1", serverTime: "2026-10-12T15:00:00Z" } },
      }),
      POST: vi.fn(),
    } as unknown as ApiClient;
    const streamRequest = vi.fn().mockResolvedValue(new Response(undefined, { status: 403 }));

    render(<MessagesContext.Provider value={messages.en}>
      <LiveRequestsProvider api={api} streamRequest={streamRequest}>
        <Harness />
      </LiveRequestsProvider>
    </MessagesContext.Provider>);

    await waitFor(() => expect(screen.getByTestId("stream-state").textContent).toBe("stopped"));
    expect(streamRequest).toHaveBeenCalledTimes(1);
  });

  it("does not replay an alert when reconnect delivers the same request signal", async () => {
    const start = vi.fn();
    class FakeAudioContext {
      currentTime = 0;
      destination = {};
      state = "running";
      createGain() {
        return {
          connect: vi.fn(),
          gain: { exponentialRampToValueAtTime: vi.fn(), setValueAtTime: vi.fn() },
        };
      }
      createOscillator() {
        return { connect: vi.fn(), frequency: { value: 0 }, start, stop: vi.fn() };
      }
      resume() { return Promise.resolve(); }
    }
    vi.stubGlobal("AudioContext", FakeAudioContext);
    let releaseFirst: ((response: Response) => void) | undefined;
    const first = new Promise<Response>((resolve) => { releaseFirst = resolve; });
    const frame = "event: change\ndata: {\"type\":\"BOOKING\",\"id\":\"b-1\",\"reason\":\"CREATED\"}\n\n";
    const streamRequest = vi.fn()
      .mockImplementationOnce(() => first)
      .mockResolvedValueOnce(new Response(frame, { status: 200 }))
      .mockImplementation(() => new Promise<Response>(() => undefined));
    const api = {
      GET: vi.fn().mockResolvedValue({
        data: { data: [], meta: { correlationId: "c-1", serverTime: "2026-10-12T15:00:00Z" } },
      }),
      POST: vi.fn(),
    } as unknown as ApiClient;

    render(<MessagesContext.Provider value={messages.en}>
      <LiveRequestsProvider api={api} streamRequest={streamRequest} pollMilliseconds={60_000}>
        <Harness />
      </LiveRequestsProvider>
    </MessagesContext.Provider>);

    await waitFor(() => expect(screen.getByText("ready")).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: "enable sound" }));
    releaseFirst?.(new Response(frame, { status: 200 }));
    await waitFor(() => expect(start).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(streamRequest).toHaveBeenCalledTimes(2), { timeout: 2_000 });
    await new Promise((resolve) => window.setTimeout(resolve, 50));
    expect(start).toHaveBeenCalledTimes(1);
  });
});
