// The shared API client (@e7gz/api-client), created from this app's configuration.
import { createApiClient, type ApiClientOptions } from "@e7gz/api-client";
import type { AppConfig } from "../config";

export type ApiClient = ReturnType<typeof createApiClient>;

export function createDashboardApiClient(config: AppConfig, fetch?: ApiClientOptions["fetch"]): ApiClient {
  return createApiClient({
    baseUrl: config.apiBaseUrl,
    getAccessToken: () => config.accessToken,
    ...(fetch ? { fetch } : {}),
  });
}

/** The error code from an error envelope (arch 09 §1.3), if the body is one. */
export function errorCodeOf(body: unknown): string | undefined {
  if (body && typeof body === "object" && "error" in body) {
    const error = (body as { error?: { code?: unknown } }).error;
    return typeof error?.code === "string" ? error.code : undefined;
  }
  return undefined;
}
