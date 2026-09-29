// Compile-time half of the backend → OpenAPI → TypeScript chain (E7-11), checked by `tsc -p tsconfig.chain.json`.
// Types come from test/generated/sample.ts, generated from the contract that springdoc wrote for the sample Spring
// controllers in backend OpenApiSampleContractTest. Every @ts-expect-error below is itself an error if the
// expected error ever disappears (TS2578), so each negative case stays meaningful.
import { createTypedClient, type ContractPaths } from "../../src/index.ts";
import type { components, paths } from "../generated/sample.ts";
import { expectType, type Equal, type IsAny } from "../support/types.ts";

type SampleView = components["schemas"]["SampleView"];
type SampleStatus = components["schemas"]["SampleStatus"];
type SampleError = components["schemas"]["SampleError"];

// The Java enum, exactly as Jackson writes it (OpenApiSampleContractTest checks the Java side of this list).
expectType<Equal<SampleStatus, "OPEN" | "LATE_CONFIRMED" | "CLOSED">>();
// Required Java fields are required here; the unannotated one is optional.
expectType<Equal<SampleView, { note?: string; sampleId: string; status: SampleStatus }>>();
expectType<Equal<components["schemas"]["SampleNoteRequest"], { status: SampleStatus; text: string }>>();
// A contract with endpoints passes through ContractPaths unchanged (only an empty one is narrowed).
expectType<Equal<ContractPaths<paths>, paths>>();

const api = createTypedClient<paths>({ baseUrl: "http://127.0.0.1:4010" });
const path = { sampleId: "s-1" };

export async function validCallsCompileWithPreciseTypes(): Promise<void> {
  const read = await api.GET("/api/v1/customer/samples/{sampleId}", { params: { path } });
  expectType<Equal<IsAny<typeof read.data>, false>>();
  expectType<Equal<typeof read.data, SampleView | undefined>>();
  void read;

  const created = await api.POST("/api/v1/customer/samples/{sampleId}/notes", {
    params: { path },
    body: { text: "bring two controllers", status: "LATE_CONFIRMED" },
    headers: { "Idempotency-Key": "0f4e5c1a-0000-4000-8000-000000000001" },
  });
  expectType<Equal<typeof created.data, SampleView | undefined>>();
  expectType<Equal<IsAny<typeof created.error>, false>>();
  // The explicit 422 in its own shape is kept as declared; the general failures (E7-12: 400, 404, 429 and the
  // meta-only 500) join it in the error union.
  expectType<Equal<SampleError extends NonNullable<typeof created.error> ? true : false, true>>();
  if (created.error && "error" in created.error) {
    const code: string = created.error.error.code;
    void code;
  }
}

export async function wrongCallsDoNotCompile(): Promise<void> {
  // @ts-expect-error — the path is not in the contract
  await api.GET("/api/v1/customer/not-in-contract");

  // @ts-expect-error — the method is not in the contract for this path
  await api.DELETE("/api/v1/customer/samples/{sampleId}", { params: { path } });

  // @ts-expect-error — the required path parameter is missing
  await api.GET("/api/v1/customer/samples/{sampleId}", { params: { path: {} } });

  // @ts-expect-error — no params at all, though the path needs {sampleId}
  await api.GET("/api/v1/customer/samples/{sampleId}");

  await api.POST("/api/v1/customer/samples/{sampleId}/notes", {
    params: { path },
    // @ts-expect-error — the required field `text` is missing
    body: { status: "OPEN" },
  });

  await api.POST("/api/v1/customer/samples/{sampleId}/notes", {
    params: { path },
    // @ts-expect-error — `text` must be a string
    body: { text: 42, status: "OPEN" },
  });

  await api.POST("/api/v1/customer/samples/{sampleId}/notes", {
    params: { path },
    // @ts-expect-error — enum spelling must match Java exactly (not lower case)
    body: { text: "x", status: "open" },
  });

  await api.POST("/api/v1/customer/samples/{sampleId}/notes", {
    params: { path },
    // @ts-expect-error — not a constant of the Java enum
    body: { text: "x", status: "LATE_CONFIRM" },
  });

  // @ts-expect-error — the request body is required
  await api.POST("/api/v1/customer/samples/{sampleId}/notes", { params: { path } });
}

// A write in ADR-015's scope declares Idempotency-Key as required; the shared optional header does not relax it.
export async function aRequiredIdempotencyKeyIsRequired(): Promise<void> {
  const body = { text: "bring two controllers", status: "OPEN" } as const;

  await api.POST("/api/v1/customer/samples/{sampleId}/commitments", {
    params: { path, header: { "Idempotency-Key": "0f4e5c1a-0000-4000-8000-000000000002" } },
    body,
  });

  await api.POST("/api/v1/customer/samples/{sampleId}/commitments", {
    // @ts-expect-error — the Idempotency-Key header is required here
    params: { path },
    body,
  });

  // The ordinary write still accepts it without requiring it.
  await api.POST("/api/v1/customer/samples/{sampleId}/notes", { params: { path }, body });
}
