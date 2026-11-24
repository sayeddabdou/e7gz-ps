// Runtime configuration from Vite env variables (.env.example). Switching between the mock (`pnpm mock`) and a real
// backend is only a matter of VITE_API_BASE_URL — nothing in the code picks one silently.
export interface AppConfig {
  apiBaseUrl: string;
  privacyPolicyUrl: string;
  termsUrl: string;
}

export type ConfigResult = { ok: true; config: AppConfig } | { ok: false; missing: string[] };

function publicHttpsUrl(value: string | boolean | undefined): string | undefined {
  const candidate = String(value ?? "").trim();
  try {
    const url = new URL(candidate);
    return url.protocol === "https:" && !url.username && !url.password ? candidate : undefined;
  } catch {
    return undefined;
  }
}

export function readConfig(env: Record<string, string | boolean | undefined>): ConfigResult {
  const apiBaseUrl = String(env.VITE_API_BASE_URL ?? "").trim();
  const privacyPolicyUrl = publicHttpsUrl(env.VITE_LEGAL_PRIVACY_URL);
  const termsUrl = publicHttpsUrl(env.VITE_LEGAL_TERMS_URL);
  const missing = [
    !apiBaseUrl ? "VITE_API_BASE_URL" : undefined,
    !privacyPolicyUrl ? "VITE_LEGAL_PRIVACY_URL" : undefined,
    !termsUrl ? "VITE_LEGAL_TERMS_URL" : undefined,
  ].filter((value): value is string => Boolean(value));
  if (!apiBaseUrl || !privacyPolicyUrl || !termsUrl) return { ok: false, missing };
  return { ok: true, config: { apiBaseUrl, privacyPolicyUrl, termsUrl } };
}
