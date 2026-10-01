import { errorCodeOf } from "../../api";
import type { ApiErrorCode } from "./errorMessages";

const csrfHeaders = { "X-Requested-With": "e7gz" } as const;

export type SessionResult =
  | { ok: true; accessToken: string }
  | { ok: false; code?: ApiErrorCode };

export interface VenueSessionApi {
  login(loginIdentifier: string, password: string): Promise<SessionResult>;
  refresh(): Promise<SessionResult>;
  logout(): Promise<void>;
  withRefresh(
    input: Request,
    onAccessToken: (accessToken: string) => void,
    onUnauthenticated: () => void,
  ): Promise<Response>;
}

function accessTokenOf(body: unknown): string | undefined {
  if (!body || typeof body !== "object" || !("data" in body)) return undefined;
  const data = (body as { data?: { accessToken?: unknown } }).data;
  return typeof data?.accessToken === "string" ? data.accessToken : undefined;
}

async function bodyOf(response: Response): Promise<unknown> {
  try {
    return await response.json();
  } catch {
    return undefined;
  }
}

export function createVenueSessionApi(apiBaseUrl: string, request: typeof fetch = fetch): VenueSessionApi {
  const baseUrl = apiBaseUrl.replace(/\/+$/u, "");
  let refreshInFlight: Promise<SessionResult> | undefined;
  const send = async (path: string, init: RequestInit): Promise<SessionResult> => {
    const response = await request(`${baseUrl}${path}`, { credentials: "include", method: "POST", ...init });
    const body = await bodyOf(response);
    const accessToken = accessTokenOf(body);
    if (response.ok && accessToken) return { ok: true, accessToken };
    return { ok: false, code: errorCodeOf(body) as ApiErrorCode | undefined };
  };
  const refresh = () => {
    refreshInFlight ??= send("/api/v1/venue/auth/refresh", { headers: csrfHeaders })
      .finally(() => { refreshInFlight = undefined; });
    return refreshInFlight;
  };

  return {
    login: (loginIdentifier, password) => send("/api/v1/venue/auth/login", {
      body: JSON.stringify({ loginIdentifier, password }),
      headers: { "Content-Type": "application/json" },
    }),
    refresh,
    async logout() {
      await request(`${baseUrl}/api/v1/venue/auth/logout`, {
        credentials: "include",
        headers: csrfHeaders,
        method: "POST",
      });
    },
    async withRefresh(input, onAccessToken, onUnauthenticated) {
      const retryInput = input.clone();
      const response = await request(input);
      if (response.status !== 401) return response;

      const refreshed = await refresh();
      if (!refreshed.ok) {
        onUnauthenticated();
        return response;
      }

      onAccessToken(refreshed.accessToken);
      const headers = new Headers(retryInput.headers);
      headers.set("Authorization", `Bearer ${refreshed.accessToken}`);
      const retried = await request(new Request(retryInput, { headers }));
      if (retried.status === 401) onUnauthenticated();
      return retried;
    },
  };
}
