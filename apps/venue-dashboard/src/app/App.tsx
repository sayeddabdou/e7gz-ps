// Minimal Dashboard shell: configuration check, EN/AR switch with dir="rtl", one screen. Routing, the auth guard and
// the prototype layout are E7-19.
import { useEffect, useMemo, useState } from "react";
import { logicalSpacing, webTokens } from "@e7gz/tokens/web";
import { createDashboardApiClient } from "../api";
import type { ConfigResult } from "../config";
import { ResourceList } from "../features/setup/ResourceList";
import { directionOf, messages, MessagesContext, type Language } from "../i18n";

export function App({ config, initialLanguage = "en" }: { config: ConfigResult; initialLanguage?: Language }) {
  const [language, setLanguage] = useState<Language>(initialLanguage);
  const t = messages[language];
  const direction = directionOf(language);
  const api = useMemo(() => (config.ok ? createDashboardApiClient(config.config) : undefined), [config]);

  useEffect(() => {
    document.documentElement.lang = language;
    document.documentElement.dir = direction;
  }, [language, direction]);

  return (
    <MessagesContext.Provider value={t}>
      <main
        dir={direction}
        style={{
          ...logicalSpacing.paddingInline(webTokens.spacing["3xl"]),
          backgroundColor: webTokens.color.surface.venueSubtle,
          color: webTokens.color.text.venue,
          fontFamily: webTokens.typography.fontFamily.sans,
          minHeight: "100vh",
        }}
      >
        <header
          style={{
            display: "flex",
            justifyContent: "space-between",
            alignItems: "center",
            gap: webTokens.spacing["3xl"],
          }}
        >
          <h1>{t.appTitle}</h1>
          <button type="button" onClick={() => setLanguage(language === "en" ? "ar" : "en")}>
            {t.switchLanguage}
          </button>
        </header>
        {api ? (
          <ResourceList api={api} />
        ) : (
          <p role="alert">
            {t.configMissing} <code>{config.ok ? "" : config.missing.join(", ")}</code>
          </p>
        )}
      </main>
    </MessagesContext.Provider>
  );
}
