// Minimal Admin shell: configuration check and one screen. Layout, navigation for the 20 modules and the auth guard
// are E7-19.
import { useMemo } from "react";
import { createAdminApiClient } from "../api";
import type { ConfigResult } from "../config";
import { AreaList } from "../features/areas/AreaList";
import { messages as t } from "../i18n/messages";

export function App({ config }: { config: ConfigResult }) {
  const api = useMemo(() => (config.ok ? createAdminApiClient(config.config) : undefined), [config]);

  return (
    <main style={{ paddingInline: "1rem" }}>
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
