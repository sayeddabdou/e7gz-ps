import * as Notifications from "expo-notifications";
import { createIosPushAdapter } from "./iosPushAdapter";

// E7-43 (PO decision 10 Oct 2026): iPhone push uses the FCM token from React Native Firebase, not the raw APNs token.
function fakeFirebase() {
  const messaging = { name: "messaging" };
  return {
    messaging,
    module: {
      getMessaging: jest.fn(() => messaging),
      registerDeviceForRemoteMessages: jest.fn().mockResolvedValue(undefined),
      getToken: jest.fn().mockResolvedValue("fcm-ios-token"),
      onTokenRefresh: jest.fn(() => () => undefined),
      getInitialNotification: jest.fn().mockResolvedValue({ data: { bookingId: "booking-cold" } }),
      onNotificationOpenedApp: jest.fn<() => void, [unknown, (message: { data?: Record<string, unknown> }) => void]>(() => () => undefined),
    },
  };
}

describe("iPhone push adapter", () => {
  beforeEach(() => jest.clearAllMocks());

  it("registers with APNs and returns the FCM token as platform IOS", async () => {
    const firebase = fakeFirebase();
    const adapter = createIosPushAdapter(firebase.module as never);

    expect(adapter.supported).toBe(true);
    expect(adapter.platform).toBe("IOS");
    expect(await adapter.getToken()).toBe("fcm-ios-token");
    expect(firebase.module.registerDeviceForRemoteMessages).toHaveBeenCalledWith(firebase.messaging);
    expect(Notifications.getDevicePushTokenAsync).not.toHaveBeenCalled();
  });

  it("opens the exact booking from a cold start and from a tap while running", async () => {
    const firebase = fakeFirebase();
    let tap: ((message: { data?: Record<string, unknown> }) => void) | undefined;
    firebase.module.onNotificationOpenedApp = jest.fn((_messaging, listener) => { tap = listener; return () => undefined; });
    const adapter = createIosPushAdapter(firebase.module as never);
    const opened = jest.fn();

    expect(await adapter.getLastOpenedBookingId()).toBe("booking-cold");
    adapter.onNotificationOpen(opened);
    tap?.({ data: { bookingId: "booking-tap" } });
    tap?.({ data: {} });

    expect(opened).toHaveBeenCalledTimes(1);
    expect(opened).toHaveBeenCalledWith("booking-tap");
  });

  it("offers no push, and so no banner, when the build has no Firebase iOS app yet", () => {
    expect(createIosPushAdapter(undefined).supported).toBe(false);
  });
});
