// Runtime configuration (.env.example). Expo inlines EXPO_PUBLIC_* only for direct `process.env.EXPO_PUBLIC_…`
// references. Switching between the mock and a real backend is only EXPO_PUBLIC_API_BASE_URL — no silent default.
export interface AppConfig {
  apiBaseUrl: string;
}

export type ConfigResult = { ok: true; config: AppConfig } | { ok: false; missing: string[] };

export function readConfig(apiBaseUrl: string | undefined = process.env.EXPO_PUBLIC_API_BASE_URL): ConfigResult {
  const trimmed = apiBaseUrl?.trim();
  return trimmed ? { ok: true, config: { apiBaseUrl: trimmed } } : { ok: false, missing: ["EXPO_PUBLIC_API_BASE_URL"] };
}
