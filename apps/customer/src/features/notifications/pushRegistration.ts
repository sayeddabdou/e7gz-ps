import * as Notifications from "expo-notifications";
import * as SecureStore from "expo-secure-store";
import { Platform } from "react-native";
import type { components } from "@e7gz/api-client";
import type { ApiClient } from "../../api";
import { en as t } from "../../i18n/en";
import { createIosPushAdapter } from "./iosPushAdapter";

type PlatformCode = components["schemas"]["DevicePlatform"];
type SavedRegistration = { token: string; deviceId: string };

export interface PushAdapter {
  supported: boolean;
  platform: PlatformCode;
  requestPermission(): Promise<boolean>;
  getToken(): Promise<string>;
  onTokenChange(listener: (token: string) => void): () => void;
  getLastOpenedBookingId(): Promise<string | undefined>;
  onNotificationOpen(listener: (bookingId: string) => void): () => void;
}

export interface PushRegistrationStore {
  read(): Promise<SavedRegistration | null>;
  write(value: SavedRegistration): Promise<void>;
  clear(): Promise<void>;
}

export type PushSetupState = "registered" | "denied" | "unsupported" | "failed";
type PushSetupListener = (state: PushSetupState) => void;

export interface PushRegistration {
  start(onOpenBooking?: (bookingId: string) => void, onSetupStateChange?: PushSetupListener): Promise<PushSetupState>;
  revoke(): Promise<void>;
  stop(): void;
}

export function createPushRegistration(
  api: ApiClient,
  adapter: PushAdapter,
  store: PushRegistrationStore,
  retryDelayMs = 5_000,
  maxRetryDelayMs = 5 * 60_000,
): PushRegistration {
  let removeListener: (() => void) | undefined;
  let removeOpenListener: (() => void) | undefined;
  let registrationInFlight: Promise<void> | undefined;
  let retryTimer: ReturnType<typeof setTimeout> | undefined;
  let stopped = false;
  let latestToken: string | undefined;
  let nextRetryDelayMs = retryDelayMs;
  let setupListener: PushSetupListener | undefined;

  const emitState = (state: PushSetupState) => {
    try { setupListener?.(state); } catch { /* A view listener must not affect registration. */ }
  };

  const register = async (token: string) => {
    const saved = await store.read();
    if (saved?.token === token) return;
    const { data, response } = await api.POST("/api/v1/customer/notifications/devices", {
      body: { token, platform: adapter.platform },
    });
    if (!data) throw new DeviceRegistrationError(response.status);
    await store.write({ token, deviceId: data.data.id });
    if (saved?.deviceId) {
      try {
        await api.POST("/api/v1/customer/notifications/devices/{deviceId}/revoke", {
          params: { path: { deviceId: saved.deviceId } },
        });
      } catch {
        // The new token is already registered. A stale-device revoke must not disable push on this device.
      }
    }
  };
  const serializedRegister = (token: string) => {
    const next = (registrationInFlight ?? Promise.resolve()).then(() => register(token));
    registrationInFlight = next.catch(() => undefined);
    return next;
  };
  const clearRetry = () => {
    if (retryTimer) clearTimeout(retryTimer);
    retryTimer = undefined;
  };
  const registerWithRetry = async (token: string): Promise<boolean> => {
    latestToken = token;
    try {
      await serializedRegister(token);
      clearRetry();
      nextRetryDelayMs = retryDelayMs;
      emitState("registered");
      return true;
    } catch (error) {
      emitState("failed");
      if (isPermanentRegistrationError(error)) {
        clearRetry();
        return false;
      }
      if (!stopped && !retryTimer) {
        const delay = nextRetryDelayMs;
        nextRetryDelayMs = Math.min(nextRetryDelayMs * 2, maxRetryDelayMs);
        retryTimer = setTimeout(() => {
          retryTimer = undefined;
          if (latestToken) void registerWithRetry(latestToken);
        }, delay);
      }
      return false;
    }
  };

  return {
    async start(onOpenBooking?: (bookingId: string) => void, onSetupStateChange?: PushSetupListener): Promise<PushSetupState> {
      setupListener = onSetupStateChange;
      if (!adapter.supported) {
        emitState("unsupported");
        return "unsupported";
      }
      stopped = false;
      clearRetry();
      nextRetryDelayMs = retryDelayMs;
      removeOpenListener?.();
      if (onOpenBooking) {
        removeOpenListener = adapter.onNotificationOpen(onOpenBooking);
        void adapter.getLastOpenedBookingId()
          .then((bookingId) => { if (bookingId) onOpenBooking(bookingId); })
          .catch(() => undefined);
      }
      try {
        if (!(await adapter.requestPermission())) {
          emitState("denied");
          return "denied";
        }
        removeListener?.();
        removeListener = adapter.onTokenChange((token) => { void registerWithRetry(token); });
        return await registerWithRetry(await adapter.getToken()) ? "registered" : "failed";
      } catch {
        emitState("failed");
        return "failed";
      }
    },
    async revoke() {
      stopped = true;
      clearRetry();
      removeListener?.();
      removeListener = undefined;
      removeOpenListener?.();
      removeOpenListener = undefined;
      setupListener = undefined;
      const saved = await store.read();
      if (saved) {
        try {
          await api.POST("/api/v1/customer/notifications/devices/{deviceId}/revoke", {
            params: { path: { deviceId: saved.deviceId } },
          });
        } catch {
          // Local logout must complete even when the device is offline.
        } finally {
          await store.clear();
        }
      }
    },
    stop() {
      stopped = true;
      clearRetry();
      removeListener?.();
      removeListener = undefined;
      removeOpenListener?.();
      removeOpenListener = undefined;
      setupListener = undefined;
    },
  };
}

class DeviceRegistrationError extends Error {
  constructor(readonly status: number) {
    super(`Device registration failed (${status})`);
  }
}

function isPermanentRegistrationError(error: unknown): boolean {
  return error instanceof DeviceRegistrationError
    && error.status >= 400
    && error.status < 500
    && error.status !== 408
    && error.status !== 429;
}

const registrationKey = "e7gz.customer.pushRegistration";

export function createSecurePushRegistrationStore(): PushRegistrationStore {
  return {
    async read() {
      if (!(await SecureStore.isAvailableAsync())) return null;
      const value = await SecureStore.getItemAsync(registrationKey);
      if (!value) return null;
      try {
        const parsed = JSON.parse(value) as Partial<SavedRegistration>;
        return typeof parsed.token === "string" && typeof parsed.deviceId === "string"
          ? { token: parsed.token, deviceId: parsed.deviceId }
          : null;
      } catch { return null; }
    },
    async write(value) {
      if (await SecureStore.isAvailableAsync()) await SecureStore.setItemAsync(registrationKey, JSON.stringify(value));
    },
    async clear() {
      if (await SecureStore.isAvailableAsync()) await SecureStore.deleteItemAsync(registrationKey);
    },
  };
}

export function createExpoPushAdapter(): PushAdapter {
  // iPhone: FCM token and taps from React Native Firebase (architecture A7; PO decision 10 Oct 2026, E7-43).
  if (Platform.OS === "ios") return createIosPushAdapter();
  const platform: PlatformCode = Platform.OS === "web" ? "WEB" : "ANDROID";
  return {
    // Android: expo-notifications' device token is the FCM registration token. Web has no customer push.
    supported: Platform.OS === "android",
    platform,
    async requestPermission() {
      const current = await Notifications.getPermissionsAsync();
      if (current.granted || current.ios?.status === Notifications.IosAuthorizationStatus.PROVISIONAL) return true;
      const requested = await Notifications.requestPermissionsAsync();
      return requested.granted || requested.ios?.status === Notifications.IosAuthorizationStatus.PROVISIONAL;
    },
    async getToken() {
      if (Platform.OS === "android") {
        await Notifications.setNotificationChannelAsync("booking-updates", {
          name: t.notifications.channelName,
          importance: Notifications.AndroidImportance.HIGH,
        });
      }
      const token = await Notifications.getDevicePushTokenAsync();
      return typeof token.data === "string" ? token.data : JSON.stringify(token.data);
    },
    onTokenChange(listener) {
      const subscription = Notifications.addPushTokenListener((token) => listener(typeof token.data === "string" ? token.data : JSON.stringify(token.data)));
      return () => subscription.remove();
    },
    async getLastOpenedBookingId() {
      return bookingIdFromResponse(await Notifications.getLastNotificationResponseAsync());
    },
    onNotificationOpen(listener) {
      const subscription = Notifications.addNotificationResponseReceivedListener((response) => {
        const bookingId = bookingIdFromResponse(response);
        if (bookingId) listener(bookingId);
      });
      return () => subscription.remove();
    },
  };
}

function bookingIdFromResponse(response: Notifications.NotificationResponse | null): string | undefined {
  const bookingId = response?.notification.request.content.data?.bookingId;
  return typeof bookingId === "string" && bookingId.length > 0 ? bookingId : undefined;
}

Notifications.setNotificationHandler({
  handleNotification: async () => ({
    shouldShowBanner: true,
    shouldShowList: true,
    shouldPlaySound: false,
    shouldSetBadge: false,
  }),
});
