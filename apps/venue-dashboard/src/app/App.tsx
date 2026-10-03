import { useCallback, useEffect, useMemo, useState, type CSSProperties } from "react";
import { logicalSpacing, webTokens } from "@e7gz/tokens/web";
import { BrowserRouter, NavLink, Navigate, Route, Routes } from "react-router-dom";
import { createDashboardApiClient } from "../api";
import type { ConfigResult } from "../config";
import { PricingEditor, ResourceList, SettingsPage } from "../features/setup";
import { createVenueSessionApi, VenueLoginScreen, type VenueSessionApi } from "../features/auth";
import { directionOf, messages, MessagesContext, type Language, type Messages } from "../i18n";

const languageStorageKey = "e7gz.venue.language";

const routes = [
  { path: "/", label: "overview" },
  { path: "/pending-requests", label: "pendingRequests" },
  { path: "/missed-requests", label: "missedRequests" },
  { path: "/bookings", label: "bookings" },
  { path: "/manual-booking", label: "manualBooking" },
  { path: "/rooms", label: "rooms" },
  { path: "/pricing", label: "pricing" },
  { path: "/schedule", label: "schedule" },
  { path: "/maintenance", label: "maintenance" },
  { path: "/reviews", label: "reviews" },
  { path: "/tournaments", label: "tournaments" },
  { path: "/booking-history", label: "bookingHistory" },
  { path: "/settings", label: "settings" },
] as const satisfies ReadonlyArray<{ path: string; label: keyof Messages["navigation"] }>;

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
    backgroundColor: webTokens.color.surface.venueSubtle,
    color: webTokens.color.text.venue,
    fontFamily: webTokens.typography.fontFamily.sans,
    minHeight: "100vh",
  },
  header: {
    ...logicalSpacing.paddingInline(webTokens.spacing["3xl"]),
    alignItems: "center",
    borderBlockEnd: `${webTokens.spacing.hairline}px solid ${webTokens.color.border.venue}`,
    display: "flex",
    gap: webTokens.spacing.lg,
    justifyContent: "space-between",
    paddingBlock: webTokens.spacing.lg,
  },
  brand: { marginBlock: 0 },
  shell: {
    display: "grid",
    gridTemplateColumns: "minmax(13rem, 18rem) minmax(0, 1fr)",
    minHeight: "calc(100vh - 5rem)",
  },
  sidebar: {
    ...logicalSpacing.paddingInline(webTokens.spacing.lg),
    borderInlineEnd: `${webTokens.spacing.hairline}px solid ${webTokens.color.border.venue}`,
    paddingBlock: webTokens.spacing.xl,
    textAlign: "start",
  },
  navigation: { display: "grid", gap: webTokens.spacing.xs },
  navigationLink: {
    borderRadius: webTokens.radius.md,
    color: "inherit",
    paddingBlock: webTokens.spacing.sm,
    paddingInline: webTokens.spacing.md,
    textDecoration: "none",
  },
  navigationLinkActive: { backgroundColor: webTokens.color.surface.selected },
  content: {
    ...logicalSpacing.paddingInline(webTokens.spacing["3xl"]),
    paddingBlock: webTokens.spacing["3xl"],
  },
  alert: {
    ...logicalSpacing.paddingInline(webTokens.spacing["3xl"]),
    paddingBlock: webTokens.spacing["3xl"],
  },
} satisfies Record<string, CSSProperties>;

function ProtectedShell({
  accessToken,
  config,
  direction,
  onAccessToken,
  onLanguagePreference,
  onUnauthenticated,
  session,
  t,
}: {
  accessToken: string;
  config: Extract<ConfigResult, { ok: true }>;
  direction: "ltr" | "rtl";
  onAccessToken: (accessToken: string) => void;
  onLanguagePreference: (language: Language) => void;
  onUnauthenticated: () => void;
  session: VenueSessionApi;
  t: Messages;
}) {
  const api = useMemo(() => createDashboardApiClient(
    config.config,
    () => accessToken,
    (request) => session.withRefresh(request, onAccessToken, onUnauthenticated),
  ), [accessToken, config, onAccessToken, onUnauthenticated, session]);

  return (
    <main dir={direction} style={styles.shell}>
      <aside data-testid="venue-sidebar" style={styles.sidebar}>
        <nav aria-label={t.navigationLabel} style={styles.navigation}>
          {routes.map((route) => (
            <NavLink
              key={route.path}
              end={route.path === "/"}
              style={({ isActive }) => ({
                ...styles.navigationLink,
                ...(isActive ? styles.navigationLinkActive : undefined),
              })}
              to={route.path}
            >
              {t.navigation[route.label]}
            </NavLink>
          ))}
        </nav>
      </aside>
      <section style={styles.content}>
        <Routes>
          {routes.map((route) => (
            <Route
              key={route.path}
              path={route.path}
              element={
                route.path === "/rooms" ? (
                  <ResourceList api={api} />
                ) : route.path === "/pricing" ? (
                  <PricingEditor api={api} />
                ) : route.path === "/settings" ? (
                  <SettingsPage api={api} onLanguagePreference={onLanguagePreference} />
                ) : (
                  <h2>{t.navigation[route.label]}</h2>
                )
              }
            />
          ))}
          <Route path="*" element={<Navigate replace to="/" />} />
        </Routes>
      </section>
    </main>
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
        <div dir={direction} style={styles.frame}>
          <header style={styles.header}>
            <h1 style={styles.brand}>{t.appTitle}</h1>
            <button type="button" onClick={toggleLanguage}>
              {t.switchLanguage}
            </button>
            {accessToken ? <button disabled={loggingOut} type="button" onClick={() => void logout()}>{loggingOut ? t.auth.loggingOut : t.auth.logout}</button> : null}
          </header>
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
              onAccessToken={setAccessToken}
              onLanguagePreference={setLanguagePreference}
              onUnauthenticated={() => setAccessToken(undefined)}
              session={session!}
              t={t}
            />
          )}
        </div>
      </BrowserRouter>
    </MessagesContext.Provider>
  );
}
