import { createApiClient, type ApiClientOptions } from "@e7gz/api-client";
import type { TokenPair, TokenStore } from "../auth/tokenStore";
import type { AppConfig } from "../config";

export type ApiClient = ReturnType<typeof createApiClient>;
export type { TokenPair, TokenStore } from "../auth/tokenStore";
export type RefreshAccessToken = (refreshToken: string) => Promise<TokenPair | null>;

export interface CustomerApiClientOptions {
  fetch?: ApiClientOptions["fetch"];
  tokenStore?: TokenStore;
  /** E7-21 supplies the contract-backed refresh call once the auth endpoint exists. */
  refresh?: RefreshAccessToken;
}

export function createCustomerApiClient(
  config: AppConfig,
  argument?: CustomerApiClientOptions | ApiClientOptions["fetch"],
): ApiClient {
  const options = typeof argument === "function" ? { fetch: argument } : (argument ?? {});
  const { tokenStore, refresh } = options;
  const fetch = tokenStore && refresh
    ? createRefreshingFetch(options.fetch ?? globalThis.fetch, tokenStore, refresh)
    : options.fetch;

  return createApiClient({
    baseUrl: config.apiBaseUrl,
    ...(fetch ? { fetch } : {}),
    ...(tokenStore ? { getAccessToken: async () => (await tokenStore.read())?.accessToken } : {}),
  });
}

function createRefreshingFetch(
  fetch: NonNullable<ApiClientOptions["fetch"]>,
  tokenStore: TokenStore,
  refresh: RefreshAccessToken,
): NonNullable<ApiClientOptions["fetch"]> {
  let refreshInFlight: Promise<TokenPair | null> | undefined;

  return async (request) => {
    const retryRequest = request.clone();
    const response = await fetch(request);
    if (response.status !== 401) return response;

    const stored = await tokenStore.read();
    if (!stored) return response;

    refreshInFlight ??= refresh(stored.refreshToken)
      .catch(() => null)
      .finally(() => {
        refreshInFlight = undefined;
      });
    const refreshed = await refreshInFlight;
    if (!refreshed) {
      await tokenStore.clear();
      return response;
    }

    await tokenStore.write(refreshed);
    const headers = new Headers(retryRequest.headers);
    headers.set("Authorization", `Bearer ${refreshed.accessToken}`);
    const retryResponse = await fetch(new Request(retryRequest, { headers }));
    if (retryResponse.status === 401) await tokenStore.clear();
    return retryResponse;
  };
}

/** The error code from an error envelope (arch 09 §1.3), if the body is one. */
export function errorCodeOf(body: unknown): string | undefined {
  if (body && typeof body === "object" && "error" in body) {
    const error = (body as { error?: { code?: unknown } }).error;
    return typeof error?.code === "string" ? error.code : undefined;
  }
  return undefined;
}

/** The field associated with a validation error envelope, if supplied. */
export function errorFieldOf(body: unknown): string | undefined {
  if (body && typeof body === "object" && "error" in body) {
    const error = (body as { error?: { field?: unknown } }).error;
    return typeof error?.field === "string" ? error.field : undefined;
  }
  return undefined;
}
