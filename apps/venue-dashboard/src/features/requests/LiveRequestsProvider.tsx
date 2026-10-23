import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import type { components } from "@e7gz/api-client";
import type { ApiClient } from "../../api";
import { apiErrorOf } from "../../api";
import { useMessages } from "../../i18n";
import { createBrowserAlertTone, readVenueStream, RequestAlertQueue, type VenueStreamSignal } from "./liveStream";
import {
  registerVenuePush,
  restoreVenuePushState,
  storeVenuePushSubscriptionId,
} from "./pushSubscription";
import type { PendingRequestsViewModel } from "./PendingRequestsScreen";
import { createIdempotencyKey } from "../operations";

type Booking = components["schemas"]["VenueBooking"];
type PushState = NonNullable<PendingRequestsViewModel["pushState"]>;
type SoundState = NonNullable<PendingRequestsViewModel["soundState"]>;

const LiveRequestsContext = createContext<PendingRequestsViewModel | undefined>(undefined);

export function LiveRequestsProvider({
  api,
  children,
  streamRequest,
  pollMilliseconds = 30_000,
}: {
  api: ApiClient;
  children: ReactNode;
  streamRequest: (signal: AbortSignal) => Promise<Response>;
  pollMilliseconds?: number;
}) {
  const t = useMessages().requests;
  const [bookings, setBookings] = useState<Booking[]>([]);
  const [clockOffsetMs, setClockOffsetMs] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();
  const [actionError, setActionError] = useState<string>();
  const [pendingAction, setPendingAction] = useState<string>();
  const [streamState, setStreamState] = useState<PendingRequestsViewModel["streamState"]>("connecting");
  const tone = useMemo(() => createBrowserAlertTone(), []);
  const [soundState, setSoundState] = useState<SoundState>(tone.supported ? "disabled" : "unsupported");
  const alertQueue = useRef(new RequestAlertQueue(tone.play, () => setSoundState("disabled")));
  const initialized = useRef(false);
  const bookingsRef = useRef<Booking[]>([]);
  const resolvedBookingIds = useRef(new Set<string>());
  const actionKeys = useRef(new Map<string, string>());
  const pendingActionRef = useRef<string | undefined>(undefined);
  const pushTouched = useRef(false);
  const [pushState, setPushState] = useState<PushState>(() => (
    "Notification" in window && "serviceWorker" in navigator && "PushManager" in window ? "disabled" : "unsupported"
  ));
  const [bookingChangeVersion, setBookingChangeVersion] = useState(0);

  const errorMessage = useCallback((value: unknown) => {
    const code = apiErrorOf(value).code;
    return code && code in t.errors ? t.errors[code as keyof typeof t.errors] : t.errors.unexpected;
  }, [t]);

  const refresh = useCallback(async () => {
    try {
      const result = await api.GET("/api/v1/venue/requests/pending");
      if (!result.data) {
        setError(errorMessage(result.error));
        return;
      }
      const next = result.data.data.filter((booking) => !resolvedBookingIds.current.has(booking.bookingId));
      const serverTime = result.data.meta.serverTime;
      setClockOffsetMs(Date.parse(serverTime) - Date.now());
      if (!initialized.current) {
        alertQueue.current.seed("BOOKING", next.map((booking) => booking.bookingId));
        initialized.current = true;
      } else {
        const previousIds = new Set(bookingsRef.current.map((booking) => booking.bookingId));
        next.filter((booking) => !previousIds.has(booking.bookingId)).forEach((booking) => {
          alertQueue.current.receive({ type: "BOOKING", id: booking.bookingId, reason: "CREATED" });
        });
      }
      bookingsRef.current = next;
      setBookings(next);
      setError(undefined);
    } catch {
      setError(t.errors.network);
    } finally {
      setLoading(false);
    }
  }, [api, errorMessage, t]);

  useEffect(() => {
    const timeout = window.setTimeout(() => { void refresh(); }, 0);
    return () => window.clearTimeout(timeout);
  }, [refresh]);

  useEffect(() => {
    const interval = window.setInterval(() => { void refresh(); }, pollMilliseconds);
    return () => window.clearInterval(interval);
  }, [pollMilliseconds, refresh]);

  useEffect(() => {
    let active = true;
    void restoreVenuePushState({
      getPermission: () => "Notification" in window ? Notification.permission : "unsupported",
      getRegistration: async () => navigator.serviceWorker.getRegistration(),
    }).then((state) => {
      if (active && !pushTouched.current) setPushState(state);
    });
    return () => { active = false; };
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    let reconnectDelay = 1_000;
    let first = true;
    const run = async () => {
      while (!controller.signal.aborted) {
        setStreamState(first ? "connecting" : "reconnecting");
        try {
          const response = await streamRequest(controller.signal);
          if (response.status === 403) {
            setStreamState("stopped");
            return;
          }
          if (!response.ok) throw new Error(`stream-${response.status}`);
          setStreamState("connected");
          if (!first) await refresh();
          first = false;
          const connectedAt = Date.now();
          await readVenueStream(response, (signal: VenueStreamSignal) => {
            alertQueue.current.receive(signal);
            if (signal.type === "BOOKING") {
              setBookingChangeVersion((value) => value + 1);
              void refresh();
              return;
            }
            // TODO(E7-96): Refetch pending tournament requests once the Sprint 6 contract exposes them.
          }, controller.signal);
          if (Date.now() - connectedAt >= 15_000) reconnectDelay = 1_000;
        } catch {
          if (controller.signal.aborted) return;
        }
        setStreamState("reconnecting");
        await new Promise<void>((resolve) => {
          const timeout = window.setTimeout(resolve, reconnectDelay);
          controller.signal.addEventListener("abort", () => { window.clearTimeout(timeout); resolve(); }, { once: true });
        });
        reconnectDelay = Math.min(reconnectDelay * 2, 30_000);
      }
    };
    void run();
    return () => controller.abort();
  }, [refresh, streamRequest]);

  const runAction = useCallback(async (
    bookingId: string,
    signature: string,
    reloadOnAny422: boolean,
    request: (key: string) => Promise<{ data?: unknown; error?: unknown; response?: Response }>,
  ) => {
    if (pendingActionRef.current) return;
    pendingActionRef.current = signature;
    setPendingAction(signature);
    setActionError(undefined);
    try {
      const key = actionKeys.current.get(signature) ?? createIdempotencyKey();
      actionKeys.current.set(signature, key);
      const result = await request(key);
      if (!result.data) {
        const code = apiErrorOf(result.error).code;
        setActionError(errorMessage(result.error));
        if ((reloadOnAny422 && result.response?.status === 422)
          || code === "BOOKING_STATE_CHANGED" || code === "DEADLINE_PASSED" || code === "NOT_FOUND") await refresh();
        return;
      }
      resolvedBookingIds.current.add(bookingId);
      bookingsRef.current = bookingsRef.current.filter((booking) => booking.bookingId !== bookingId);
      setBookings(bookingsRef.current);
      await refresh();
    } catch {
      setActionError(t.errors.network);
    } finally {
      pendingActionRef.current = undefined;
      setPendingAction(undefined);
    }
  }, [errorMessage, refresh, t]);

  const accept = useCallback((bookingId: string) => {
    const signature = `${bookingId}:accept`;
    void runAction(bookingId, signature, true, (key) => api.POST("/api/v1/venue/bookings/{bookingId}/accept", {
      params: { header: { "Idempotency-Key": key }, path: { bookingId } },
    }));
  }, [api, runAction]);

  const reject = useCallback((bookingId: string, reasonText: string) => {
    const signature = `${bookingId}:reject:${reasonText}`;
    void runAction(bookingId, signature, false, (key) => api.POST("/api/v1/venue/bookings/{bookingId}/reject", {
      body: { reasonText },
      params: { header: { "Idempotency-Key": key }, path: { bookingId } },
    }));
  }, [api, runAction]);

  const enableSound = useCallback(() => {
    void tone.unlock().then(() => alertQueue.current.enable()).then(() => setSoundState("enabled")).catch(() => setSoundState("disabled"));
  }, [tone]);

  const enablePush = useCallback(() => {
    pushTouched.current = true;
    setPushState("subscribing");
    void registerVenuePush({
      getPermission: () => "Notification" in window ? Notification.permission : "unsupported",
      requestPermission: () => Notification.requestPermission(),
      registerServiceWorker: (scriptUrl) => navigator.serviceWorker.register(scriptUrl),
      getVapidPublicKey: async () => {
        const result = await api.GET("/api/v1/venue/push-subscriptions/vapid-public-key");
        if (!result.data) throw new Error("vapid-key-failed");
        return result.data.data.publicKey;
      },
      createSubscription: async (subscription) => {
        const result = await api.POST("/api/v1/venue/push-subscriptions", { body: subscription });
        if (!result.data) throw new Error("push-registration-failed");
        return result.data.data.id;
      },
      userAgent: navigator.userAgent,
    }).then((result) => {
      if (result.ok) storeVenuePushSubscriptionId(result.subscriptionId);
      setPushState(result.ok ? "enabled" : result.reason);
    });
  }, [api]);

  const value = useMemo<PendingRequestsViewModel>(() => ({
    bookings, clockOffsetMs, loading, error, actionError, pendingAction, streamState, bookingChangeVersion,
    accept, reject, refresh: () => { void refresh(); }, soundState, pushState, enableSound, enablePush,
  }), [accept, actionError, bookingChangeVersion, bookings, clockOffsetMs, enablePush, enableSound, error, loading, pendingAction, pushState, refresh, reject, soundState, streamState]);

  return <LiveRequestsContext.Provider value={value}>{children}</LiveRequestsContext.Provider>;
}

export function useLiveRequests(): PendingRequestsViewModel {
  const value = useContext(LiveRequestsContext);
  if (!value) throw new Error("useLiveRequests must be used inside LiveRequestsProvider");
  return value;
}
