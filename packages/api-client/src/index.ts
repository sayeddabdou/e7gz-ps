// Typed API client generated from openapi.json (E7-11, ADR-012).
// openapi.json comes from the backend controllers (`pnpm api:gen`); src/generated/ is rebuilt from it and not committed.
//
// Deliberately small: no retries (a retried write must reuse the caller's Idempotency-Key — arch 09 §1.2, ADR-015 —
// so retrying belongs to the caller), no generated Idempotency-Key, no error-code-to-text mapping (each app owns its
// message catalogue — arch 09 §1.3). Caller headers and `signal` (cancellation) pass through to fetch unchanged.
import createClient, { type Client, type ClientOptions, type Middleware } from "openapi-fetch";
import type { paths as GeneratedPaths } from "./generated/schema";

export type { components } from "./generated/schema";

/**
 * openapi-typescript writes a contract without endpoints as `paths = Record<string, never>`, which would let any path
 * string compile. A contract without endpoints must accept none; any other contract is used as generated.
 */
export type ContractPaths<P> = string extends keyof P ? Record<never, never> : P;

/** The production contract's paths (openapi.json). */
export type paths = ContractPaths<GeneratedPaths>;

export interface ApiClientOptions {
  /** Backend origin (paths already start with /api/v1), or the mock server from `pnpm mock`: http://127.0.0.1:4010. */
  baseUrl: string;
  /** The current access JWT, or nothing when signed out. Sent as `Authorization: Bearer` (arch 09 §1.2). */
  getAccessToken?: () => string | null | undefined | Promise<string | null | undefined>;
  /** Replaces the global fetch (tests, React Native polyfills). */
  fetch?: ClientOptions["fetch"];
}

/** A client for any OpenAPI `paths` type; the apps use {@link createApiClient}. */
export function createTypedClient<Paths extends object>(options: ApiClientOptions): Client<Paths> {
  const client = createClient<Paths>({
    baseUrl: options.baseUrl,
    ...(options.fetch ? { fetch: options.fetch } : {}),
  });
  const { getAccessToken } = options;
  if (getAccessToken) {
    client.use(bearerToken(getAccessToken));
  }
  return client;
}

/** The client for the E7GZ PS API — every path, parameter and response typed from the contract. */
export function createApiClient(options: ApiClientOptions): Client<paths> {
  return createTypedClient<paths>(options);
}

function bearerToken(getAccessToken: NonNullable<ApiClientOptions["getAccessToken"]>): Middleware {
  return {
    async onRequest({ request }) {
      // A caller that sets Authorization itself keeps it.
      if (request.headers.has("Authorization")) {
        return request;
      }
      const token = await getAccessToken();
      if (token) {
        request.headers.set("Authorization", `Bearer ${token}`);
      }
      return request;
    },
  };
}
