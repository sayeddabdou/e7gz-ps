import { useEffect, useMemo, useState, type ReactNode } from "react";
import { BrowserRouter, NavLink, Navigate, Route, Routes } from "react-router-dom";
import { createAdminApiClient } from "../api";
import type { ConfigResult } from "../config";
import { AreaList } from "../features/areas/AreaList";
import { ActivityList } from "../features/activities/ActivityList";
import { AdminLoginScreen, createAdminSessionApi, type AdminSessionApi } from "../features/auth";
import { Onboarding } from "../features/venues/Onboarding";
import { VenueDetail } from "../features/venues/VenueDetail";
import { VenueList } from "../features/venues/VenueList";
import { messages as t } from "../i18n/messages";

const modules = [
  { path: "/", label: "dashboard", icon: "▦" },
  { path: "/areas", label: "areas", icon: "◇" },
  { path: "/venues", label: "venues", icon: "▱" },
  { path: "/venue-onboarding", label: "venueOnboarding", icon: "+" },
  { path: "/venue-accounts", label: "venueAccounts", icon: "⌁" },
  { path: "/activities", label: "activities", icon: "⌘" },
  { path: "/users", label: "users", icon: "♙" },
  { path: "/verified-players", label: "verifiedPlayers", icon: "✓" },
  { path: "/reviews", label: "reviewsModeration", icon: "☆" },
  { path: "/bookings", label: "globalBookings", icon: "□" },
  { path: "/no-shows", label: "noShowRecords", icon: "⌁" },
  { path: "/tournaments", label: "tournaments", icon: "♕" },
  { path: "/wallet", label: "wallet", icon: "▣" },
  { path: "/points", label: "points", icon: "⊙" },
  { path: "/referrals", label: "referrals", icon: "⌯" },
  { path: "/fees", label: "platformFees", icon: "▤" },
  { path: "/global-rules", label: "globalRules", icon: "⚙" },
  { path: "/notifications", label: "notifications", icon: "◉" },
  { path: "/search", label: "globalSearch", icon: "⌕" },
  { path: "/settings", label: "settings", icon: "⚙" },
] as const satisfies ReadonlyArray<{ path: string; label: keyof typeof t.modules; icon: string }>;

function ProtectedShell({
  accessToken,
  config,
  onAccessToken,
  onUnauthenticated,
  onLogout,
  loggingOut,
  session,
}: {
  accessToken: string;
  config: Extract<ConfigResult, { ok: true }>;
  onAccessToken: (accessToken: string) => void;
  onUnauthenticated: () => void;
  onLogout: () => void;
  loggingOut: boolean;
  session: AdminSessionApi;
}) {
  const api = useMemo(() => createAdminApiClient(
    config.config,
    () => accessToken,
    (request) => session.withRefresh(request, onAccessToken, onUnauthenticated),
  ), [accessToken, config, onAccessToken, onUnauthenticated, session]);

  return (
    <main className="admin-shell">
      <aside className="admin-sidebar">
        <div className="admin-brand">
          <span className="admin-brand-mark" aria-hidden="true">E7</span>
          <span className="admin-brand-copy"><strong>E7GZ PS</strong><small>Platform Admin</small></span>
        </div>
        <nav aria-label={t.navigationLabel} className="admin-navigation">
          {modules.map((module) => (
            <NavLink
              key={module.path}
              end={module.path === "/"}
              className={({ isActive }) => `admin-navigation-link${isActive ? " is-active" : ""}`}
              data-icon={module.icon}
              to={module.path}
            >
              <span>{t.modules[module.label]}</span>
            </NavLink>
          ))}
        </nav>
      </aside>
      <div className="admin-workspace">
        <header className="admin-topbar">
          <div className="admin-topbar-spacer" />
          <div className="admin-profile"><span className="admin-avatar">PA</span><span><strong>Platform Admin</strong><small>Administrator</small></span></div>
          <button className="button button--secondary" disabled={loggingOut} type="button" onClick={onLogout}>{loggingOut ? t.auth.loggingOut : t.auth.logout}</button>
        </header>
        <section className="admin-content">
          <Routes>
            {modules.map((module) => {
              let content: ReactNode = <section className="admin-page admin-placeholder"><div className="page-heading"><span className="page-eyebrow">Platform administration</span><h2>{t.modules[module.label]}</h2></div></section>;
              if (module.path === "/areas") content = <AreaList api={api} />;
              if (module.path === "/venues") content = <VenueList api={api} />;
              if (module.path === "/venue-onboarding") content = <Onboarding api={api} />;
              if (module.path === "/activities") content = <ActivityList api={api} />;
              return <Route key={module.path} path={module.path} element={content} />;
            })}
            <Route path="/venues/:venueId" element={<VenueDetail api={api} />} />
            <Route path="*" element={<Navigate replace to="/" />} />
          </Routes>
        </section>
      </div>
    </main>
  );
}

export function App({ config }: { config: ConfigResult }) {
  const [accessToken, setAccessToken] = useState<string>();
  const [restoringSession, setRestoringSession] = useState(config.ok);
  const [loggingOut, setLoggingOut] = useState(false);
  const apiBaseUrl = config.ok ? config.config.apiBaseUrl : undefined;
  const session = useMemo(() => apiBaseUrl ? createAdminSessionApi(apiBaseUrl) : undefined, [apiBaseUrl]);

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
    <BrowserRouter>
      <div className="admin-frame">
        {!config.ok ? (
          <main className="admin-standalone">
            <h1>{t.appTitle}</h1>
            <p role="alert">
              {t.configMissing} <code>{config.missing.join(", ")}</code>
            </p>
          </main>
        ) : restoringSession ? (
          <main className="admin-standalone"><p role="status">{t.auth.restoring}</p></main>
        ) : !accessToken ? (
          <AdminLoginScreen apiBaseUrl={config.config.apiBaseUrl} onAuthenticated={setAccessToken} />
        ) : (
          <ProtectedShell
            accessToken={accessToken}
            config={config}
            onAccessToken={setAccessToken}
            onUnauthenticated={() => setAccessToken(undefined)}
            onLogout={() => void logout()}
            loggingOut={loggingOut}
            session={session!}
          />
        )}
      </div>
    </BrowserRouter>
  );
}
