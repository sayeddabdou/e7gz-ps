import { useCallback, useEffect, useMemo, useState, type CSSProperties } from "react";
import { logicalSpacing, webTokens } from "@e7gz/tokens/web";
import { BrowserRouter, NavLink, Navigate, Route, Routes, useNavigate, useParams } from "react-router-dom";
import { createDashboardApiClient } from "../api";
import type { ConfigResult } from "../config";
import { AvailabilitySchedule } from "../features/schedule";
import { PricingEditor, ResourceList, SettingsPage } from "../features/setup";
import { createVenueSessionApi, VenueLoginScreen, type VenueSessionApi } from "../features/auth";
import { LiveRequestsProvider, MissedRequestsScreen, PendingRequestsScreen, useLiveRequests } from "../features/requests";
import { revokeVenuePush } from "../features/requests/pushSubscription";
import { NotificationsScreen } from "../features/notifications";
import { BookingDetailScreen, BookingHistoryScreen, TodayBookingsScreen } from "../features/today";
import { ManualBookingScreen } from "../features/walk-in";
import { directionOf, messages, MessagesContext, type Language, type Messages } from "../i18n";
import "../dashboard.css";

const languageStorageKey = "e7gz.venue.language";

const routes = [
  { path: "/", label: "overview", icon: "⌂" },
  { path: "/pending-requests", label: "pendingRequests", icon: "◷" },
  { path: "/missed-requests", label: "missedRequests", icon: "!" },
  { path: "/notifications", label: "notifications", icon: "◉" },
  { path: "/bookings", label: "bookings", icon: "▣" },
  { path: "/manual-booking", label: "manualBooking", icon: "+" },
  { path: "/rooms", label: "rooms", icon: "□" },
  { path: "/pricing", label: "pricing", icon: "¤" },
  { path: "/schedule", label: "schedule", icon: "▦" },
  { path: "/maintenance", label: "maintenance", icon: "◇" },
  { path: "/reviews", label: "reviews", icon: "☆" },
  { path: "/tournaments", label: "tournaments", icon: "♜" },
  { path: "/booking-history", label: "bookingHistory", icon: "↺" },
  { path: "/settings", label: "settings", icon: "⚙" },
] as const satisfies ReadonlyArray<{ path: string; label: keyof Messages["navigation"]; icon: string }>;

function readStoredLanguage(): Language | undefined {
  try {
    const stored = window.localStorage.getItem(languageStorageKey);
    return stored === "en" || stored === "ar" ? stored : undefined;
  } catch {
    return undefined;
  }
}

function saveLanguage(language: Language): void {
  try {
    window.localStorage.setItem(languageStorageKey, language);
  } catch {
    // The UI still works when storage is unavailable.
  }
}

const styles = {
  frame: {
    backgroundColor: webTokens.color.surface.canvas,
    color: webTokens.color.text.venue,
    fontFamily: webTokens.typography.fontFamily.sans,
    minHeight: "100vh",
  },
  header: {
    ...logicalSpacing.paddingInline(webTokens.spacing["7xl"]),
    alignItems: "center",
    backgroundColor: webTokens.color.surface.raised,
    borderBlockEnd: `${webTokens.spacing.hairline}px solid ${webTokens.color.border.subtle}`,
    display: "flex",
    gap: webTokens.spacing.xl,
    justifyContent: "space-between",
    paddingBlock: webTokens.spacing.xl,
  },
  brand: { fontSize: webTokens.typography.fontSize.body, marginBlock: 0 },
  shell: {
    backgroundColor: webTokens.color.surface.canvas,
    display: "grid",
    gridTemplateColumns: "250px minmax(0, 1fr)",
    minHeight: "100vh",
  },
  sidebar: {
    backgroundColor: webTokens.color.surface.raised,
    borderInlineEnd: `${webTokens.spacing.hairline}px solid ${webTokens.color.border.subtle}`,
    textAlign: "start",
  },
  brandMark: {
    alignItems: "center",
    backgroundColor: webTokens.color.brand.venue,
    borderRadius: webTokens.radius.lg,
    color: webTokens.color.text.inverse,
    display: "inline-flex",
    fontSize: webTokens.typography.fontSize.label,
    fontWeight: webTokens.typography.fontWeight.extrabold,
    justifyContent: "center",
  },
  brandText: { display: "grid", gap: webTokens.spacing.xxs },
  brandName: { fontSize: webTokens.typography.fontSize.body, fontWeight: webTokens.typography.fontWeight.extrabold },
  brandContext: { color: webTokens.color.text.venueMuted, fontSize: webTokens.typography.fontSize.caption, fontWeight: webTokens.typography.fontWeight.semibold },
  navigation: { display: "grid", gap: webTokens.spacing.xxs },
  navigationLink: {
    alignItems: "center",
    borderRadius: webTokens.radius.lg,
    color: "inherit",
    display: "flex",
    fontSize: webTokens.typography.fontSize.bodySmall,
    fontWeight: webTokens.typography.fontWeight.medium,
    gap: webTokens.spacing.lg,
    paddingBlock: webTokens.spacing.lg,
    paddingInline: webTokens.spacing.xl,
    textDecoration: "none",
  },
  navigationLinkActive: { backgroundColor: webTokens.color.surface.selected, color: webTokens.color.brand.venue, fontWeight: webTokens.typography.fontWeight.bold },
  navIcon: { alignItems: "center", display: "inline-flex", fontSize: webTokens.typography.fontSize.body, inlineSize: webTokens.spacing["5xl"], justifyContent: "center" },
  workspace: { minWidth: 0 },
  topbarTitle: { display: "grid", gap: webTokens.spacing.xxs },
  topbarEyebrow: { color: webTokens.color.text.venueMuted, fontSize: webTokens.typography.fontSize.caption, fontWeight: webTokens.typography.fontWeight.bold },
  topbarActions: { alignItems: "center", display: "flex", flexWrap: "wrap", gap: webTokens.spacing.md },
  languageButton: {
    backgroundColor: webTokens.color.surface.raised,
    border: `${webTokens.spacing.hairline}px solid ${webTokens.color.border.venue}`,
    borderRadius: webTokens.radius.lg,
    color: webTokens.color.text.venue,
    cursor: "pointer",
    font: "inherit",
    fontWeight: webTokens.typography.fontWeight.bold,
    paddingBlock: webTokens.spacing.md,
    paddingInline: webTokens.spacing.xl,
  },
  logoutButton: {
    backgroundColor: webTokens.color.surface.venueSubtle,
    border: `${webTokens.spacing.hairline}px solid ${webTokens.color.border.venue}`,
    borderRadius: webTokens.radius.lg,
    color: webTokens.color.text.venue,
    cursor: "pointer",
    font: "inherit",
    fontWeight: webTokens.typography.fontWeight.semibold,
    paddingBlock: webTokens.spacing.md,
    paddingInline: webTokens.spacing.xl,
  },
  pageHeading: {
    fontSize: webTokens.typography.fontSize.displaySmall,
    fontWeight: webTokens.typography.fontWeight.extrabold,
    lineHeight: webTokens.typography.lineHeight.tight,
    marginBlock: 0,
  },
  content: {
    ...logicalSpacing.paddingInline(webTokens.spacing["8xl"]),
    marginInline: "auto",
    maxWidth: "92rem",
    paddingBlock: webTokens.spacing["8xl"],
    width: "100%",
  },
  alert: {
    ...logicalSpacing.paddingInline(webTokens.spacing["3xl"]),
    paddingBlock: webTokens.spacing["3xl"],
  },
} satisfies Record<string, CSSProperties>;

function PendingRequestsRoute() {
  return <PendingRequestsScreen model={useLiveRequests()} />;
}

function MissedRequestsRoute({ api }: { api: ReturnType<typeof createDashboardApiClient> }) {
  const { bookingChangeVersion } = useLiveRequests();
  return <MissedRequestsScreen api={api} bookingChangeVersion={bookingChangeVersion} />;
}

function NotificationsRoute({ api }: { api: ReturnType<typeof createDashboardApiClient> }) {
  const navigate = useNavigate();
  return <NotificationsScreen api={api} onOpenBooking={(bookingId) => navigate(`/bookings/${bookingId}`)} />;
}

function TodayBookingsRoute({ api }: { api: ReturnType<typeof createDashboardApiClient> }) {
  const navigate = useNavigate();
  return <TodayBookingsScreen api={api} onOpenBooking={(bookingId) => navigate(`/bookings/${bookingId}`)} />;
}

function BookingHistoryRoute({ api }: { api: ReturnType<typeof createDashboardApiClient> }) {
  const navigate = useNavigate();
  return <BookingHistoryScreen api={api} onOpenBooking={(bookingId) => navigate(`/booking-history/${bookingId}`)} />;
}

function ManualBookingRoute({ api }: { api: ReturnType<typeof createDashboardApiClient> }) {
  const navigate = useNavigate();
  return <ManualBookingScreen api={api} onOpenBooking={(bookingId) => navigate(`/bookings/${bookingId}`)} />;
}

function BookingDetailRoute({ api, backLabel, backTo }: { api: ReturnType<typeof createDashboardApiClient>; backLabel?: string; backTo: string }) {
  const navigate = useNavigate();
  const { bookingId } = useParams();
  return bookingId ? <BookingDetailScreen api={api} backLabel={backLabel} bookingId={bookingId} key={bookingId} onBack={() => navigate(backTo)} /> : <Navigate replace to={backTo} />;
}

function ProtectedShell({
  accessToken,
  config,
  direction,
  loggingOut,
  onAccessToken,
  onLanguagePreference,
  onLogout,
  onToggleLanguage,
  onUnauthenticated,
  session,
  t,
}: {
  accessToken: string;
  config: Extract<ConfigResult, { ok: true }>;
  direction: "ltr" | "rtl";
  loggingOut: boolean;
  onAccessToken: (accessToken: string) => void;
  onLanguagePreference: (language: Language) => void;
  onLogout: () => void;
  onToggleLanguage: () => void;
  onUnauthenticated: () => void;
  session: VenueSessionApi;
  t: Messages;
}) {
  const api = useMemo(() => createDashboardApiClient(
    config.config,
    () => accessToken,
    (request) => session.withRefresh(request, onAccessToken, onUnauthenticated),
  ), [accessToken, config, onAccessToken, onUnauthenticated, session]);
  const streamRequest = useCallback((signal: AbortSignal) => session.withRefresh(new Request(
    `${config.config.apiBaseUrl.replace(/\/+$/u, "")}/api/v1/venue/stream`,
    { headers: { Authorization: `Bearer ${accessToken}`, Accept: "text/event-stream" }, signal },
  ), onAccessToken, onUnauthenticated), [accessToken, config, onAccessToken, onUnauthenticated, session]);

  useEffect(() => {
    let active = true;
    void api.GET("/api/v1/venue/settings").then(({ data }) => {
      const preference = data?.data.languagePreference;
      if (active && (preference === "en" || preference === "ar")) onLanguagePreference(preference);
    }).catch(() => undefined);
    return () => { active = false; };
  }, [api, onLanguagePreference]);

  const logoutWithPushRevocation = useCallback(async () => {
    await revokeVenuePush({
      getSubscription: async () => {
        if (!("serviceWorker" in navigator)) return null;
        const registration = await navigator.serviceWorker.getRegistration();
        return await registration?.pushManager.getSubscription() ?? null;
      },
      revokeSubscription: async (subscriptionId) => {
        await api.POST("/api/v1/venue/push-subscriptions/{subscriptionId}/revoke", {
          params: { path: { subscriptionId } },
        });
      },
    });
    onLogout();
  }, [api, onLogout]);

  return (
    <LiveRequestsProvider api={api} streamRequest={streamRequest}>
    <main className="venue-dashboard-shell" data-testid="venue-shell" dir={direction} style={styles.shell}>
      <aside className="venue-sidebar" data-testid="venue-sidebar" style={styles.sidebar}>
        <div className="venue-sidebar-brand">
          <span data-testid="venue-brand-mark" style={styles.brandMark}>E7</span>
          <span style={styles.brandText}>
            <strong style={styles.brandName}>E7GZ PS</strong>
            <span style={styles.brandContext}>{t.appTitle}</span>
          </span>
        </div>
        <nav aria-label={t.navigationLabel} style={styles.navigation}>
          {routes.map((route) => (
            <NavLink
              className="venue-navigation-link"
              key={route.path}
              end={route.path === "/"}
              style={({ isActive }) => ({
                ...styles.navigationLink,
                ...(isActive ? styles.navigationLinkActive : undefined),
              })}
              to={route.path}
            >
              <span aria-hidden="true" style={styles.navIcon}>{route.icon}</span>
              <span>{t.navigation[route.label]}</span>
            </NavLink>
          ))}
        </nav>
      </aside>
      <div className="venue-workspace" style={styles.workspace}>
        <header className="venue-topbar" data-testid="venue-topbar" style={styles.header}>
          <div style={styles.topbarTitle}>
            <span style={styles.topbarEyebrow}>E7GZ PS</span>
            <h1 style={styles.brand}>{t.appTitle}</h1>
          </div>
          <div style={styles.topbarActions}>
            <button style={styles.languageButton} type="button" onClick={onToggleLanguage}>{t.switchLanguage}</button>
            <button disabled={loggingOut} style={styles.logoutButton} type="button" onClick={() => void logoutWithPushRevocation()}>{loggingOut ? t.auth.loggingOut : t.auth.logout}</button>
          </div>
        </header>
        <section className="venue-content" style={styles.content}>
          <Routes>
            {routes.map((route) => (
              <Route
                key={route.path}
                path={route.path}
                element={
                  route.path === "/pending-requests" ? (
                    <PendingRequestsRoute />
                  ) : route.path === "/missed-requests" ? (
                    <MissedRequestsRoute api={api} />
                  ) : route.path === "/notifications" ? (
                    <NotificationsRoute api={api} />
                  ) : route.path === "/bookings" ? (
                    <TodayBookingsRoute api={api} />
                  ) : route.path === "/manual-booking" ? (
                    <ManualBookingRoute api={api} />
                  ) : route.path === "/booking-history" ? (
                    <BookingHistoryRoute api={api} />
                  ) : route.path === "/rooms" ? (
                    <ResourceList api={api} />
                  ) : route.path === "/pricing" ? (
                    <PricingEditor api={api} />
                  ) : route.path === "/schedule" ? (
                    <AvailabilitySchedule api={api} direction={direction} />
                  ) : route.path === "/settings" ? (
                    <SettingsPage api={api} onLanguagePreference={onLanguagePreference} />
                  ) : (
                    <h2 style={styles.pageHeading}>{t.navigation[route.label]}</h2>
                  )
                }
              />
            ))}
            <Route path="/bookings/:bookingId" element={<BookingDetailRoute api={api} backTo="/bookings" />} />
            <Route path="/booking-history/:bookingId" element={<BookingDetailRoute api={api} backLabel={t.bookings.detail.backHistory} backTo="/booking-history" />} />
            <Route path="*" element={<Navigate replace to="/" />} />
          </Routes>
        </section>
      </div>
    </main>
    </LiveRequestsProvider>
  );
}

export function App({
  config,
  preferredLanguage,
}: {
  config: ConfigResult;
  preferredLanguage?: Language;
}) {
  const [language, setLanguage] = useState<Language>(() => preferredLanguage ?? readStoredLanguage() ?? "en");
  const [accessToken, setAccessToken] = useState<string>();
  const [restoringSession, setRestoringSession] = useState(config.ok);
  const [loggingOut, setLoggingOut] = useState(false);
  const t = messages[language];
  const direction = directionOf(language);
  const apiBaseUrl = config.ok ? config.config.apiBaseUrl : undefined;
  const session = useMemo(() => apiBaseUrl ? createVenueSessionApi(apiBaseUrl) : undefined, [apiBaseUrl]);

  useEffect(() => {
    document.documentElement.lang = language;
    document.documentElement.dir = direction;
  }, [language, direction]);

  useEffect(() => {
    if (!session) return undefined;
    let active = true;
    void session.refresh()
      .then((result) => {
        if (active && result.ok) setAccessToken(result.accessToken);
      })
      .catch(() => undefined)
      .finally(() => {
        if (active) setRestoringSession(false);
      });
    return () => { active = false; };
  }, [session]);

  const toggleLanguage = () => {
    const nextLanguage = language === "en" ? "ar" : "en";
    setLanguage(nextLanguage);
    saveLanguage(nextLanguage);
  };

  const setLanguagePreference = useCallback((nextLanguage: Language) => {
    setLanguage(nextLanguage);
    saveLanguage(nextLanguage);
  }, []);
  const unauthenticate = useCallback(() => setAccessToken(undefined), []);

  const logout = async () => {
    if (!session) return;
    setLoggingOut(true);
    try {
      await session.logout();
    } finally {
      setAccessToken(undefined);
      setLoggingOut(false);
    }
  };

  return (
    <MessagesContext.Provider value={t}>
      <BrowserRouter>
        <div
          className="venue-dashboard-root"
          dir={direction}
          style={{
            ...styles.frame,
            fontFamily: direction === "rtl" ? webTokens.typography.fontFamily.rtl : webTokens.typography.fontFamily.sans,
          }}
        >
          {!accessToken ? <header className="venue-public-header" style={styles.header}>
            <div className="venue-public-brand"><span style={styles.brandMark}>E7</span><h1 style={styles.brand}>{t.appTitle}</h1></div>
            <button style={styles.languageButton} type="button" onClick={toggleLanguage}>{t.switchLanguage}</button>
          </header> : null}
          {!config.ok ? (
            <main dir={direction} style={styles.alert}>
              <p role="alert">
                {t.configMissing} <code>{config.missing.join(", ")}</code>
              </p>
            </main>
          ) : restoringSession ? (
            <main dir={direction} style={styles.alert}><p role="status">{t.auth.restoring}</p></main>
          ) : !accessToken ? (
            <VenueLoginScreen apiBaseUrl={config.config.apiBaseUrl} language={language} onAuthenticated={setAccessToken} />
          ) : (
            <ProtectedShell
              accessToken={accessToken}
              config={config}
              direction={direction}
              loggingOut={loggingOut}
              onAccessToken={setAccessToken}
              onLanguagePreference={setLanguagePreference}
              onLogout={() => void logout()}
              onToggleLanguage={toggleLanguage}
              onUnauthenticated={unauthenticate}
              session={session!}
              t={t}
            />
          )}
        </div>
      </BrowserRouter>
    </MessagesContext.Provider>
  );
}
