import * as Notifications from "expo-notifications";
import type { PushAdapter } from "./pushRegistration";

// iPhone push goes through FCM to APNs (architecture A7). expo-notifications returns the raw APNs token on iOS, which
// FCM cannot use, so the FCM registration token and the notification taps come from React Native Firebase.
// PO decision, 10 Oct 2026 (E7-43): iPhone push is required.

type Messaging = typeof import("@react-native-firebase/messaging");
type RemoteMessage = { data?: Record<string, unknown> } | null;

/**
 * React Native Firebase, or undefined when this build has no Firebase iOS app yet (Expo Go, or a build made before the
 * PO's Apple Developer and Firebase accounts exist - they are added before deployment, E7-101). Then push is simply
 * not offered: the in-app Notifications page is the fallback, with no banner.
 */
export function loadFirebaseMessaging(): Messaging | undefined {
  try {
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const app = require("@react-native-firebase/app") as typeof import("@react-native-firebase/app");
    app.getApp();
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    return require("@react-native-firebase/messaging") as Messaging;
  } catch {
    return undefined;
  }
}

export function createIosPushAdapter(firebase: Messaging | undefined = loadFirebaseMessaging()): PushAdapter {
  const messaging = () => firebase!.getMessaging();
  return {
    supported: firebase !== undefined,
    platform: "IOS",
    async requestPermission() {
      const current = await Notifications.getPermissionsAsync();
      if (current.granted || current.ios?.status === Notifications.IosAuthorizationStatus.PROVISIONAL) return true;
      const requested = await Notifications.requestPermissionsAsync();
      return requested.granted || requested.ios?.status === Notifications.IosAuthorizationStatus.PROVISIONAL;
    },
    async getToken() {
      await firebase!.registerDeviceForRemoteMessages(messaging());
      return firebase!.getToken(messaging());
    },
    onTokenChange(listener) {
      return firebase!.onTokenRefresh(messaging(), listener);
    },
    async getLastOpenedBookingId() {
      return bookingIdOf(await firebase!.getInitialNotification(messaging()));
    },
    onNotificationOpen(listener) {
      return firebase!.onNotificationOpenedApp(messaging(), (message: RemoteMessage) => {
        const bookingId = bookingIdOf(message);
        if (bookingId) listener(bookingId);
      });
    },
  };
}

function bookingIdOf(message: RemoteMessage): string | undefined {
  const bookingId = message?.data?.bookingId;
  return typeof bookingId === "string" && bookingId.length > 0 ? bookingId : undefined;
}
