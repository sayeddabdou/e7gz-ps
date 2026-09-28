import { useMemo, type CSSProperties, type ReactNode } from "react";
import { logicalSpacing, webTokens } from "@e7gz/tokens/web";
import { BrowserRouter, NavLink, Navigate, Route, Routes } from "react-router-dom";
import { createAdminApiClient } from "../api";
import type { ConfigResult } from "../config";
import { AreaList } from "../features/areas/AreaList";
import { messages as t } from "../i18n/messages";

const modules = [
  { path: "/", label: "dashboard" },
  { path: "/areas", label: "areas" },
  { path: "/venues", label: "venues" },
  { path: "/venue-onboarding", label: "venueOnboarding" },
  { path: "/venue-accounts", label: "venueAccounts" },
  { path: "/activities", label: "activities" },
  { path: "/users", label: "users" },
  { path: "/verified-players", label: "verifiedPlayers" },
  { path: "/reviews", label: "reviewsModeration" },
  { path: "/bookings", label: "globalBookings" },
  { path: "/no-shows", label: "noShowRecords" },
  { path: "/tournaments", label: "tournaments" },
  { path: "/wallet", label: "wallet" },
  { path: "/points", label: "points" },
  { path: "/referrals", label: "referrals" },
  { path: "/fees", label: "platformFees" },
  { path: "/global-rules", label: "globalRules" },
  { path: "/notifications", label: "notifications" },
  { path: "/search", label: "globalSearch" },
  { path: "/settings", label: "settings" },
] as const satisfies ReadonlyArray<{ path: string; label: keyof typeof t.modules }>;

const styles = {
  frame: {
    backgroundColor: webTokens.color.surface.adminSubtle,
    color: webTokens.color.text.admin,
    fontFamily: webTokens.typography.fontFamily.sans,
    minHeight: "100vh",
  },
  header: {
    ...logicalSpacing.paddingInline(webTokens.spacing["3xl"]),
    borderBlockEnd: `${webTokens.spacing.hairline}px solid ${webTokens.color.border.admin}`,
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
    borderInlineEnd: `${webTokens.spacing.hairline}px solid ${webTokens.color.border.admin}`,
    paddingBlock: webTokens.spacing.xl,
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
} satisfies Record<string, CSSProperties>;

function ProtectedShell({ config }: { config: Extract<ConfigResult, { ok: true }> }) {
  const api = useMemo(() => createAdminApiClient(config.config), [config]);

  return (
    <main style={styles.shell}>
      <aside style={styles.sidebar}>
        <nav aria-label={t.navigationLabel} style={styles.navigation}>
          {modules.map((module) => (
            <NavLink
              key={module.path}
              end={module.path === "/"}
              style={({ isActive }) => ({
                ...styles.navigationLink,
                ...(isActive ? styles.navigationLinkActive : undefined),
              })}
              to={module.path}
            >
              {t.modules[module.label]}
            </NavLink>
          ))}
        </nav>
      </aside>
      <section style={styles.content}>
        <Routes>
          {modules.map((module) => {
            let content: ReactNode = <h2>{t.modules[module.label]}</h2>;
            if (module.path === "/areas") content = <AreaList api={api} />;
            return <Route key={module.path} path={module.path} element={content} />;
          })}
          <Route path="*" element={<Navigate replace to="/" />} />
        </Routes>
      </section>
    </main>
  );
}

export function App({ config }: { config: ConfigResult }) {
  return (
    <BrowserRouter>
      <div style={styles.frame}>
        <header style={styles.header}>
          <h1 style={styles.brand}>{t.appTitle}</h1>
        </header>
        {!config.ok ? (
          <main style={styles.content}>
            <p role="alert">
              {t.configMissing} <code>{config.missing.join(", ")}</code>
            </p>
          </main>
        ) : !config.config.accessToken ? (
          <main style={styles.content}>
            <h2>{t.authRequiredTitle}</h2>
            <p>{t.authRequiredDescription}</p>
          </main>
        ) : (
          <ProtectedShell config={config} />
        )}
      </div>
    </BrowserRouter>
  );
}
