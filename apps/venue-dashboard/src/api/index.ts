// The shared API client (@e7gz/api-client), created from this app's configuration.
import { createApiClient, type ApiClientOptions } from "@e7gz/api-client";
import type { AppConfig } from "../config";

export type ApiClient = ReturnType<typeof createApiClient>;

export function createDashboardApiClient(
  config: AppConfig,
  getAccessToken?: ApiClientOptions["getAccessToken"],
  fetch?: ApiClientOptions["fetch"],
): ApiClient {
  return createApiClient({
    baseUrl: config.apiBaseUrl,
    ...(getAccessToken ? { getAccessToken } : {}),
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

export type ApiErrorDetails = Record<string, unknown>;

/** The structured error body used by setup flows; backend message text is deliberately ignored. */
export function apiErrorOf(body: unknown): { code?: string; field?: string; details?: ApiErrorDetails } {
  if (!body || typeof body !== "object" || !("error" in body)) return {};
  const error = (body as { error?: { code?: unknown; field?: unknown; details?: unknown } }).error;
  return {
    ...(typeof error?.code === "string" ? { code: error.code } : {}),
    ...(typeof error?.field === "string" ? { field: error.field } : {}),
    ...(error?.details && typeof error.details === "object" ? { details: error.details as ApiErrorDetails } : {}),
  };
}
