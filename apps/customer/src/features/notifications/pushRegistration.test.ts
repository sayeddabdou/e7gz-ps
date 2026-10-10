import { createPushRegistration, type PushAdapter, type PushRegistrationStore } from "./pushRegistration";

const api = {
  POST: jest.fn(),
} as never;

function adapter(token = "fcm-1"): PushAdapter {
  return {
    supported: true,
    platform: "ANDROID",
    requestPermission: jest.fn().mockResolvedValue(true),
    getToken: jest.fn().mockResolvedValue(token),
    onTokenChange: jest.fn().mockReturnValue(() => undefined),
    getLastOpenedBookingId: jest.fn().mockResolvedValue(undefined),
    onNotificationOpen: jest.fn().mockReturnValue(() => undefined),
  };
}

function store(initial: { token: string; deviceId: string } | null = null): PushRegistrationStore {
  let value = initial;
  return {
    read: jest.fn(async () => value),
    write: jest.fn(async (next) => { value = next; }),
    clear: jest.fn(async () => { value = null; }),
  };
}

describe("push registration", () => {
  beforeEach(() => jest.clearAllMocks());

  it("does not register the same token on every launch", async () => {
    const saved = store({ token: "fcm-1", deviceId: "d-1" });
    const registration = createPushRegistration(api, adapter(), saved);
    await registration.start();
    expect((api as { POST: jest.Mock }).POST).not.toHaveBeenCalled();
  });

  it("registers a new token, follows refresh, and revokes the old device", async () => {
    let listener: ((token: string) => void) | undefined;
    const push = adapter();
    push.onTokenChange = jest.fn((next) => { listener = next; return () => undefined; });
    const saved = store();
    (api as { POST: jest.Mock }).POST
      .mockResolvedValueOnce({ data: { data: { id: "d-1", platform: "ANDROID", createdAt: "2026-10-12T17:00:00Z" } } })
      .mockResolvedValueOnce({ data: { data: { id: "d-2", platform: "ANDROID", createdAt: "2026-10-12T17:01:00Z" } } })
      .mockResolvedValueOnce({});
    const registration = createPushRegistration(api, push, saved);
    await registration.start();
    listener?.("fcm-2");
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect((api as { POST: jest.Mock }).POST).toHaveBeenCalledWith("/api/v1/customer/notifications/devices", expect.objectContaining({ body: { token: "fcm-1", platform: "ANDROID" } }));
    expect((api as { POST: jest.Mock }).POST).toHaveBeenCalledWith("/api/v1/customer/notifications/devices/{deviceId}/revoke", { params: { path: { deviceId: "d-1" } } });
    await registration.revoke();
    expect(saved.clear).toHaveBeenCalled();
  });

  it("does nothing when permission is denied or the platform is unsupported", async () => {
    const denied = adapter();
    denied.requestPermission = jest.fn().mockResolvedValue(false);
    expect(await createPushRegistration(api, denied, store()).start()).toBe("denied");
    expect(await createPushRegistration(api, { ...denied, supported: false }, store()).start()).toBe("unsupported");
    expect((api as { POST: jest.Mock }).POST).not.toHaveBeenCalled();
  });

  it("opens the exact booking from cold-start and foreground notification responses", async () => {
    let openListener: ((bookingId: string) => void) | undefined;
    const push = adapter();
    push.getLastOpenedBookingId = jest.fn().mockResolvedValue("cold-booking");
    push.onNotificationOpen = jest.fn((next) => { openListener = next; return () => undefined; });
    const openBooking = jest.fn();

    await createPushRegistration(api, push, store({ token: "fcm-1", deviceId: "d-1" })).start(openBooking);
    openListener?.("foreground-booking");

    expect(openBooking).toHaveBeenNthCalledWith(1, "cold-booking");
    expect(openBooking).toHaveBeenNthCalledWith(2, "foreground-booking");
  });

  it("keeps listeners active and retries after initial registration fails", async () => {
    jest.useFakeTimers();
    try {
      let openListener: ((bookingId: string) => void) | undefined;
      let tokenListener: ((token: string) => void) | undefined;
      const push = adapter();
      push.onNotificationOpen = jest.fn((next) => { openListener = next; return () => undefined; });
      push.onTokenChange = jest.fn((next) => { tokenListener = next; return () => undefined; });
      const openBooking = jest.fn();
      (api as { POST: jest.Mock }).POST
        .mockRejectedValueOnce(new Error("offline"))
        .mockResolvedValueOnce({ data: { data: { id: "d-1" } } })
        .mockResolvedValueOnce({ data: { data: { id: "d-2" } } });

      const registration = createPushRegistration(api, push, store(), 100);
      expect(await registration.start(openBooking)).toBe("failed");
      openListener?.("booking-while-offline");
      await jest.advanceTimersByTimeAsync(100);
      tokenListener?.("fcm-2");
      await Promise.resolve();
      await Promise.resolve();

      expect(openBooking).toHaveBeenCalledWith("booking-while-offline");
      expect(push.onTokenChange).toHaveBeenCalledTimes(1);
      expect(push.onNotificationOpen).toHaveBeenCalledTimes(1);
      expect((api as { POST: jest.Mock }).POST).toHaveBeenCalledTimes(3);
      registration.stop();
    } finally {
      jest.useRealTimers();
    }
  });

  it("keeps a successful new registration when revoking the old device fails", async () => {
    const saved = store({ token: "old-token", deviceId: "old-device" });
    (api as { POST: jest.Mock }).POST
      .mockResolvedValueOnce({ data: { data: { id: "new-device" } } })
      .mockRejectedValueOnce(new Error("old device offline"));

    const registration = createPushRegistration(api, adapter("new-token"), saved);

    await expect(registration.start()).resolves.toBe("registered");
    await expect(saved.read()).resolves.toEqual({ token: "new-token", deviceId: "new-device" });
    registration.stop();
  });

  it("clears local registration without throwing when revoke is offline", async () => {
    const saved = store({ token: "fcm-1", deviceId: "d-1" });
    (api as { POST: jest.Mock }).POST.mockRejectedValueOnce(new Error("offline"));
    const registration = createPushRegistration(api, adapter(), saved);

    await expect(registration.revoke()).resolves.toBeUndefined();
    expect(saved.clear).toHaveBeenCalledTimes(1);
  });

  it("retries transient failures with capped exponential backoff and reports recovery", async () => {
    jest.useFakeTimers();
    try {
      const states: string[] = [];
      (api as { POST: jest.Mock }).POST
        .mockRejectedValueOnce(new Error("offline"))
        .mockRejectedValueOnce(new Error("offline"))
        .mockRejectedValueOnce(new Error("offline"))
        .mockResolvedValueOnce({ data: { data: { id: "d-1" } } });
      const registration = createPushRegistration(api, adapter(), store(), 100, 250);

      expect(await registration.start(undefined, (state) => states.push(state))).toBe("failed");
      expect((api as { POST: jest.Mock }).POST).toHaveBeenCalledTimes(1);
      await jest.advanceTimersByTimeAsync(100);
      expect((api as { POST: jest.Mock }).POST).toHaveBeenCalledTimes(2);
      await jest.advanceTimersByTimeAsync(200);
      expect((api as { POST: jest.Mock }).POST).toHaveBeenCalledTimes(3);
      await jest.advanceTimersByTimeAsync(249);
      expect((api as { POST: jest.Mock }).POST).toHaveBeenCalledTimes(3);
      await jest.advanceTimersByTimeAsync(1);

      expect((api as { POST: jest.Mock }).POST).toHaveBeenCalledTimes(4);
      expect(states).toEqual(["failed", "failed", "failed", "registered"]);
      registration.stop();
    } finally {
      jest.useRealTimers();
    }
  });

  it("does not retry permanent client errors", async () => {
    jest.useFakeTimers();
    try {
      (api as { POST: jest.Mock }).POST.mockResolvedValueOnce({
        data: undefined,
        response: new Response(null, { status: 403 }),
      });
      const registration = createPushRegistration(api, adapter(), store(), 100, 250);

      expect(await registration.start()).toBe("failed");
      await jest.advanceTimersByTimeAsync(10_000);

      expect((api as { POST: jest.Mock }).POST).toHaveBeenCalledTimes(1);
      registration.stop();
    } finally {
      jest.useRealTimers();
    }
  });
});
