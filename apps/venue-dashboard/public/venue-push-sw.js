/* global clients, self */
self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (event) => event.waitUntil(clients.claim()));

self.addEventListener("push", (event) => {
  let payload;
  try { payload = event.data ? event.data.json() : {}; } catch { payload = {}; }
  const title = typeof payload.title === "string" ? payload.title : "E7GZ PS";
  const body = typeof payload.body === "string" ? payload.body : "";
  event.waitUntil(self.registration.showNotification(title, {
    body,
    data: payload.data || {},
    icon: "/favicon.ico",
    tag: payload.data?.bookingId || payload.data?.tournamentId || "venue-request",
  }));
});

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  const data = event.notification.data || {};
  const path = data.bookingId ? "/pending-requests" : data.tournamentId ? "/tournaments" : "/";
  event.waitUntil(clients.matchAll({ type: "window", includeUncontrolled: true }).then((windows) => {
    const open = windows.find((windowClient) => "focus" in windowClient);
    return open
      ? open.focus().then(() => open.navigate(path)).catch(() => clients.openWindow(path))
      : clients.openWindow(path);
  }));
});
