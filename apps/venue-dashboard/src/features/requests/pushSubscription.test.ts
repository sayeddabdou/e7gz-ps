import { describe, expect, it, vi } from "vitest";
import { registerVenuePush, restoreVenuePushState, revokeVenuePush } from "./pushSubscription";

describe("venue Web Push", () => {
  it("registers the service worker, subscribes with the server VAPID key, then sends PushSubscription.toJSON", async () => {
    const subscription = {
      toJSON: () => ({
        endpoint: "https://fcm.googleapis.com/fcm/send/test",
        keys: { auth: "auth-key", p256dh: "p256dh-key" },
      }),
    } as unknown as PushSubscription;
    const subscribe = vi.fn().mockResolvedValue(subscription);
    const register = vi.fn().mockResolvedValue({ pushManager: { getSubscription: vi.fn().mockResolvedValue(null), subscribe } });
    const create = vi.fn().mockResolvedValue("subscription-1");

    const result = await registerVenuePush({
      getPermission: () => "granted",
      requestPermission: vi.fn(),
      registerServiceWorker: register,
      getVapidPublicKey: vi.fn().mockResolvedValue("AQIDBA"),
      createSubscription: create,
      userAgent: "test-browser",
    });

    expect(result).toEqual({ ok: true, subscriptionId: "subscription-1" });
    expect(register).toHaveBeenCalledWith("/venue-push-sw.js");
    expect(subscribe).toHaveBeenCalledWith(expect.objectContaining({ userVisibleOnly: true }));
    expect(create).toHaveBeenCalledWith({
      endpoint: "https://fcm.googleapis.com/fcm/send/test",
      keys: { auth: "auth-key", p256dh: "p256dh-key" },
      userAgent: "test-browser",
    });
  });

  it("reports unsupported and denied browsers without calling the API", async () => {
    const createSubscription = vi.fn();
    const base = {
      requestPermission: vi.fn(),
      registerServiceWorker: vi.fn(),
      getVapidPublicKey: vi.fn(),
      createSubscription,
      userAgent: "test-browser",
    };
    expect(await registerVenuePush({ ...base, getPermission: () => "unsupported" })).toEqual({ ok: false, reason: "unsupported" });
    expect(await registerVenuePush({ ...base, getPermission: () => "denied" })).toEqual({ ok: false, reason: "denied" });
    expect(createSubscription).not.toHaveBeenCalled();
  });

  it("restores enabled state only when permission, a browser subscription and the stored server id exist", async () => {
    const pushManager = { getSubscription: vi.fn().mockResolvedValue({ endpoint: "https://push.test" }) };
    expect(await restoreVenuePushState({
      getPermission: () => "granted",
      getRegistration: vi.fn().mockResolvedValue({ pushManager }),
      readSubscriptionId: () => "subscription-1",
    })).toBe("enabled");
    expect(await restoreVenuePushState({
      getPermission: () => "granted",
      getRegistration: vi.fn().mockResolvedValue({ pushManager }),
      readSubscriptionId: () => undefined,
    })).toBe("disabled");
  });

  it("revokes the stored server subscription and unsubscribes the browser on logout", async () => {
    const revokeSubscription = vi.fn().mockResolvedValue(undefined);
    const unsubscribe = vi.fn().mockResolvedValue(true);
    const clearSubscriptionId = vi.fn();

    await revokeVenuePush({
      clearSubscriptionId,
      getSubscription: vi.fn().mockResolvedValue({ unsubscribe }),
      readSubscriptionId: () => "subscription-1",
      revokeSubscription,
    });

    expect(revokeSubscription).toHaveBeenCalledWith("subscription-1");
    expect(unsubscribe).toHaveBeenCalledTimes(1);
    expect(clearSubscriptionId).toHaveBeenCalledTimes(1);
  });
});
