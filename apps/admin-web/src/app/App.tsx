// Minimal Admin shell: configuration check and one screen. Layout, navigation for the 20 modules and the auth guard
// are E7-19.
import { useMemo } from "react";
import { logicalSpacing, webTokens } from "@e7gz/tokens/web";
import { createAdminApiClient } from "../api";
import type { ConfigResult } from "../config";
import { AreaList } from "../features/areas/AreaList";
import { messages as t } from "../i18n/messages";

export function App({ config }: { config: ConfigResult }) {
  const api = useMemo(() => (config.ok ? createAdminApiClient(config.config) : undefined), [config]);

  return (
    <main
      style={{
        ...logicalSpacing.paddingInline(webTokens.spacing["3xl"]),
        backgroundColor: webTokens.color.surface.adminSubtle,
        color: webTokens.color.text.admin,
        fontFamily: webTokens.typography.fontFamily.sans,
        minHeight: "100vh",
      }}
    >
      <h1>{t.appTitle}</h1>
      {api ? (
        <AreaList api={api} />
      ) : (
        <p role="alert">
          {t.configMissing} <code>{config.ok ? "" : config.missing.join(", ")}</code>
        </p>
      )}
    </main>
  );
}
