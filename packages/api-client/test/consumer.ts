// Package consumption (E7-11), checked by `pnpm typecheck`: code outside src/ imports the public export
// "@e7gz/api-client" exactly as an app does, and gets the production contract's types — not `any`.
import { createApiClient, type ApiClientOptions, type components, type paths } from "@e7gz/api-client";
import { expectType, type Equal, type IsAny } from "./support/types.ts";

type Schemas = components["schemas"];

const options: ApiClientOptions = { baseUrl: "http://127.0.0.1:4010", getAccessToken: async () => null };
const api = createApiClient(options);

expectType<Equal<IsAny<paths>, false>>();
// Nullable references keep their null (OpenApiProductionContractTest checks the contract side).
expectType<Equal<Schemas["CustomerVenueCard"]["startingPrice"], Schemas["Money"] | null>>();
expectType<Equal<Schemas["Activity"], "PLAYSTATION" | "BILLIARDS" | "PING_PONG">>();

export async function documentedCallsCompile(): Promise<void> {
  const areas = await api.GET("/api/v1/customer/areas");
  expectType<Equal<typeof areas.data, Schemas["DataResponseListCustomerArea"] | undefined>>();
  void areas;

  const venues = await api.GET("/api/v1/customer/venues", { params: { query: { activity: "PLAYSTATION" } } });
  expectType<Equal<IsAny<typeof venues.data>, false>>();
  void venues;

  const disabled = await api.POST("/api/v1/venue/resources/{resourceId}/disable", {
    params: { path: { resourceId: "c2d3e4f5-a6b7-4c8d-9e0f-1a2b3c4d5e6f" } },
    body: { ownerPassword: "••••••" },
  });
  if (disabled.error && "error" in disabled.error) {
    // The codes this operation declares (@ApiError) plus the general ones its kind can return (arch 09 §1.5,
    // E7-12), across its error statuses. A 500 has no `error` at all: only meta.
    expectType<Equal<typeof disabled.error.error.code,
      | "RESOURCE_HAS_BLOCKING_RESERVATIONS" | "OWNER_PASSWORD_REQUIRED" | "OWNER_PASSWORD_INVALID"
      | "VALIDATION_FAILED" | "UNAUTHENTICATED" | "FORBIDDEN" | "NOT_FOUND" | "RATE_LIMITED">>();
  }
}

type DisableResponses = paths["/api/v1/venue/resources/{resourceId}/disable"]["post"]["responses"];
type Disable422 = DisableResponses[422]["content"]["application/json"];
type Disable423 = DisableResponses[423]["content"]["application/json"];

// Per status: one code for 422, two for 423.
expectType<Equal<Disable422["error"]["code"], "RESOURCE_HAS_BLOCKING_RESERVATIONS">>();
expectType<Equal<Disable423["error"]["code"], "OWNER_PASSWORD_REQUIRED" | "OWNER_PASSWORD_INVALID">>();
// The shared envelope is kept: meta, field and details as ErrorResponse defines them.
expectType<Equal<Disable423["meta"], Schemas["ResponseMeta"]>>();
expectType<Equal<Disable423["error"]["details"], Schemas["ErrorBody"]["details"]>>();
expectType<Equal<Disable423["error"]["field"], Schemas["ErrorBody"]["field"]>>();

const meta: Schemas["ResponseMeta"] = { correlationId: "c-1", serverTime: "2026-10-12T17:04:11Z" };

// `field` and `details` are optional, not nullable: the backend omits them when absent, never sends null (E7-12;
// ErrorEnvelopeHttpTest checks the serialized JSON against openapi.json).
expectType<Equal<Schemas["ErrorBody"]["field"], string | undefined>>();
export const aBareCodedErrorMatchesTheContract: Schemas["ErrorResponse"] = { error: { code: "NOT_FOUND" }, meta };
export const nullIsNotPartOfTheContract: Schemas["ErrorResponse"][] = [
  // @ts-expect-error — field is optional, not nullable
  { error: { code: "NOT_FOUND", field: null }, meta },
  // @ts-expect-error — details is optional, not nullable
  { error: { code: "NOT_FOUND", details: null }, meta },
];

export const declaredCodesAreAccepted: Disable423[] = [
  { error: { code: "OWNER_PASSWORD_REQUIRED" }, meta },
  { error: { code: "OWNER_PASSWORD_INVALID", details: { attemptsLeft: 2 } }, meta },
];

export const unrelatedCodesAreRejected: Disable423[] = [
  // @ts-expect-error — a real ApiErrorCode, but not one this operation declares for 423
  { error: { code: "AREA_INACTIVE" }, meta },
  // @ts-expect-error — the 422 code does not belong to the 423 response
  { error: { code: "RESOURCE_HAS_BLOCKING_RESERVATIONS" }, meta },
];

// General failures (arch 09 §1.4–§1.5, E7-12): a secured call's 401 is UNAUTHENTICATED; a 500 carries only meta.
expectType<Equal<DisableResponses[401]["content"]["application/json"]["error"]["code"], "UNAUTHENTICATED">>();
expectType<Equal<DisableResponses[500]["content"]["application/json"], Schemas["UnexpectedErrorResponse"]>>();
expectType<Equal<keyof Schemas["UnexpectedErrorResponse"], "meta">>();

export async function undocumentedCallsDoNotCompile(): Promise<void> {
  // @ts-expect-error — a path that is not in openapi.json
  await api.GET("/api/v1/__not-in-the-contract__");

  // @ts-expect-error — `activity` is a required query parameter
  await api.GET("/api/v1/customer/venues", { params: { query: {} } });

  await api.GET("/api/v1/customer/venues", {
    // @ts-expect-error — enum spelling must match the contract exactly
    params: { query: { activity: "playstation" } },
  });

  await api.POST("/api/v1/venue/resources/{resourceId}/disable", {
    params: { path: { resourceId: "c2d3e4f5-a6b7-4c8d-9e0f-1a2b3c4d5e6f" } },
    // @ts-expect-error — the Owner Password is required in the body (ADR-011)
    body: {},
  });
}
