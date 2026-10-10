export type PushRegistrationResult = { ok: true; subscriptionId: string } | { ok: false; reason: "denied" | "failed" | "unsupported" };
export type PushRestoreResult = "denied" | "disabled" | "enabled" | "unsupported";

const subscriptionIdStorageKey = "e7gz.venue.pushSubscriptionId";

export interface PushRegistrationDependencies {
  getPermission: () => NotificationPermission | "unsupported";
  requestPermission: () => Promise<NotificationPermission>;
  registerServiceWorker: (scriptUrl: string) => Promise<Pick<ServiceWorkerRegistration, "pushManager">>;
  getVapidPublicKey: () => Promise<string>;
  createSubscription: (subscription: { endpoint: string; keys: { auth: string; p256dh: string }; userAgent: string }) => Promise<string>;
  userAgent: string;
}

export function readVenuePushSubscriptionId(storage: Pick<Storage, "getItem"> = window.localStorage): string | undefined {
  try {
    return storage.getItem(subscriptionIdStorageKey) ?? undefined;
  } catch {
    return undefined;
  }
}

export function storeVenuePushSubscriptionId(subscriptionId: string, storage: Pick<Storage, "setItem"> = window.localStorage): void {
  try {
    storage.setItem(subscriptionIdStorageKey, subscriptionId);
  } catch {
    // Push still works for this session when browser storage is unavailable.
  }
}

export function clearVenuePushSubscriptionId(storage: Pick<Storage, "removeItem"> = window.localStorage): void {
  try {
    storage.removeItem(subscriptionIdStorageKey);
  } catch {
    // There is no local state left that this session can clean up.
  }
}

function applicationServerKey(value: string): Uint8Array<ArrayBuffer> {
  const padding = "=".repeat((4 - value.length % 4) % 4);
  const binary = atob(`${value}${padding}`.replace(/-/gu, "+").replace(/_/gu, "/"));
  const bytes = new Uint8Array(new ArrayBuffer(binary.length));
  for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index);
  return bytes;
}

/** Registers exactly the browser subscription returned by PushSubscription.toJSON() (arch 08 §5, A6). */
export async function registerVenuePush(dependencies: PushRegistrationDependencies): Promise<PushRegistrationResult> {
  try {
    let permission = dependencies.getPermission();
    if (permission === "unsupported") return { ok: false, reason: "unsupported" };
    if (permission === "denied") return { ok: false, reason: "denied" };
    if (permission === "default") permission = await dependencies.requestPermission();
    if (permission !== "granted") return { ok: false, reason: "denied" };

    const registration = await dependencies.registerServiceWorker("/venue-push-sw.js");
    const existing = await registration.pushManager.getSubscription();
    const subscription = existing ?? await registration.pushManager.subscribe({
      applicationServerKey: applicationServerKey(await dependencies.getVapidPublicKey()),
      userVisibleOnly: true,
    });
    const json = subscription.toJSON();
    if (!json.endpoint || !json.keys?.auth || !json.keys.p256dh) return { ok: false, reason: "failed" };
    const subscriptionId = await dependencies.createSubscription({
      endpoint: json.endpoint,
      keys: { auth: json.keys.auth, p256dh: json.keys.p256dh },
      userAgent: dependencies.userAgent,
    });
    return { ok: true, subscriptionId };
  } catch {
    return { ok: false, reason: "failed" };
  }
}

export async function restoreVenuePushState({
  getPermission,
  getRegistration,
  readSubscriptionId = readVenuePushSubscriptionId,
}: {
  getPermission: () => NotificationPermission | "unsupported";
  getRegistration: () => Promise<Pick<ServiceWorkerRegistration, "pushManager"> | undefined>;
  readSubscriptionId?: () => string | undefined;
}): Promise<PushRestoreResult> {
  const permission = getPermission();
  if (permission === "unsupported") return "unsupported";
  if (permission === "denied") return "denied";
  if (permission !== "granted" || !readSubscriptionId()) return "disabled";
  try {
    const registration = await getRegistration();
    return await registration?.pushManager.getSubscription() ? "enabled" : "disabled";
  } catch {
    return "disabled";
  }
}

/** Revokes the server row before logout and unsubscribes locally even when the server cannot be reached. */
export async function revokeVenuePush({
  clearSubscriptionId = clearVenuePushSubscriptionId,
  getSubscription,
  readSubscriptionId = readVenuePushSubscriptionId,
  revokeSubscription,
}: {
  clearSubscriptionId?: () => void;
  getSubscription: () => Promise<Pick<PushSubscription, "unsubscribe"> | null>;
  readSubscriptionId?: () => string | undefined;
  revokeSubscription: (subscriptionId: string) => Promise<void>;
}): Promise<void> {
  const subscriptionId = readSubscriptionId();
  try {
    if (subscriptionId) await revokeSubscription(subscriptionId);
  } catch {
    // Unsubscribing at the push service still prevents delivery to a shared browser.
  } finally {
    clearSubscriptionId();
    try {
      await (await getSubscription())?.unsubscribe();
    } catch {
      // Logout must still complete if the browser has already discarded the subscription.
    }
  }
}
