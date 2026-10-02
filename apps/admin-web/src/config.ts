// Runtime configuration from Vite env variables (.env.example). Switching between the mock (`pnpm mock`) and a real
// backend is only a matter of VITE_API_BASE_URL — nothing in the code picks one silently.
export interface AppConfig {
  apiBaseUrl: string;
}

export type ConfigResult = { ok: true; config: AppConfig } | { ok: false; missing: string[] };

export function readConfig(env: Record<string, string | boolean | undefined>): ConfigResult {
  const apiBaseUrl = String(env.VITE_API_BASE_URL ?? "").trim();
  if (!apiBaseUrl) {
    return { ok: false, missing: ["VITE_API_BASE_URL"] };
  }
  return { ok: true, config: { apiBaseUrl } };
}
