// Runtime behaviour of the fetch wrapper (src/index.ts) against injected fetch, typed by the sample contract
// generated from the backend (see client.types.ts). Run by `pnpm test:chain`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { createTypedClient, type ApiClientOptions } from "../../src/index.ts";
import type { paths } from "../generated/sample.ts";

const SAMPLE = "/api/v1/customer/samples/{sampleId}";
const NOTES = "/api/v1/customer/samples/{sampleId}/notes";
const path = { sampleId: "s-1" };

/** A fetch that records every request and answers with `respond`. */
function recordingFetch(respond: (request: Request) => Response | Promise<Response>) {
  const requests: Request[] = [];
  const fetch = async (input: Request) => {
    requests.push(input);
    return respond(input);
  };
  return { fetch, requests };
}

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

function client(fetch: ApiClientOptions["fetch"], options: Partial<ApiClientOptions> = {}) {
  return createTypedClient<paths>({ baseUrl: "https://api.example.test", fetch, ...options });
}

test("sends requests to the configured base URL", async () => {
  const { fetch, requests } = recordingFetch(() => json(200, { sampleId: "s-1", status: "OPEN" }));

  await client(fetch, { baseUrl: "https://api.example.test/" }).GET(SAMPLE, { params: { path } });

  assert.equal(requests.length, 1);
  assert.equal(requests[0]!.method, "GET");
  assert.equal(requests[0]!.url, "https://api.example.test/api/v1/customer/samples/s-1");
});

test("attaches a token that is fetched asynchronously", async () => {
  const { fetch, requests } = recordingFetch(() => json(200, { sampleId: "s-1", status: "OPEN" }));
  const getAccessToken = () => new Promise<string>((resolve) => setTimeout(() => resolve("jwt-1"), 5));

  await client(fetch, { getAccessToken }).GET(SAMPLE, { params: { path } });

  assert.equal(requests[0]!.headers.get("Authorization"), "Bearer jwt-1");
});

test("sends no Authorization header when signed out", async () => {
  for (const signedOut of [() => null, () => undefined, async () => null, () => ""]) {
    const { fetch, requests } = recordingFetch(() => json(200, { sampleId: "s-1", status: "OPEN" }));

    await client(fetch, { getAccessToken: signedOut }).GET(SAMPLE, { params: { path } });

    assert.equal(requests[0]!.headers.has("Authorization"), false);
  }
  const { fetch, requests } = recordingFetch(() => json(200, { sampleId: "s-1", status: "OPEN" }));
  await client(fetch).GET(SAMPLE, { params: { path } });
  assert.equal(requests[0]!.headers.has("Authorization"), false);
});

test("keeps caller headers, including a caller-supplied Authorization", async () => {
  const { fetch, requests } = recordingFetch(() => json(201, { sampleId: "s-1", status: "OPEN" }));
  const api = client(fetch, { getAccessToken: () => "jwt-1" });

  await api.POST(NOTES, {
    params: { path },
    body: { text: "x", status: "OPEN" },
    headers: { "Idempotency-Key": "key-1", "X-Correlation-Id": "corr-1" },
  });
  await api.GET(SAMPLE, { params: { path }, headers: { Authorization: "Bearer caller" } });

  assert.equal(requests[0]!.headers.get("Idempotency-Key"), "key-1");
  assert.equal(requests[0]!.headers.get("X-Correlation-Id"), "corr-1");
  assert.equal(requests[0]!.headers.get("Authorization"), "Bearer jwt-1");
  assert.deepEqual(await requests[0]!.json(), { text: "x", status: "OPEN" });
  assert.equal(requests[1]!.headers.get("Authorization"), "Bearer caller");
});

test("returns the typed body on success", async () => {
  const { fetch } = recordingFetch(() => json(200, { sampleId: "s-1", status: "LATE_CONFIRMED", note: "n" }));

  const { data, error, response } = await client(fetch).GET(SAMPLE, { params: { path } });

  assert.equal(response.status, 200);
  assert.equal(error, undefined);
  assert.equal(data?.status, "LATE_CONFIRMED");
  assert.equal(data?.note, "n");
});

test("returns a documented API error as `error`, not as data", async () => {
  const { fetch } = recordingFetch(() => json(422, { error: { code: "SAMPLE_CLOSED" } }));

  const { data, error, response } = await client(fetch).POST(NOTES, {
    params: { path },
    body: { text: "x", status: "CLOSED" },
  });

  assert.equal(response.status, 422);
  assert.equal(data, undefined);
  assert.ok(error && "error" in error, "a coded failure, not the meta-only 500");
  assert.equal(error.error.code, "SAMPLE_CLOSED");
});

test("rejects on a network failure instead of resolving as a success, and does not retry", async () => {
  let calls = 0;
  const fetch = async () => {
    calls++;
    throw new TypeError("fetch failed");
  };

  await assert.rejects(
    client(fetch).POST(NOTES, { params: { path }, body: { text: "x", status: "OPEN" } }),
    (error: unknown) => error instanceof TypeError && error.message === "fetch failed",
  );
  assert.equal(calls, 1);
});

test("adds no Idempotency-Key of its own", async () => {
  const { fetch, requests } = recordingFetch(() => json(201, { sampleId: "s-1", status: "OPEN" }));

  await client(fetch).POST(NOTES, { params: { path }, body: { text: "x", status: "OPEN" } });

  assert.equal(requests[0]!.headers.has("Idempotency-Key"), false);
});

test("passes the caller's AbortSignal through, so a request can be cancelled", async () => {
  const controller = new AbortController();
  const fetch = (request: Request) =>
    new Promise<Response>((_resolve, reject) => {
      request.signal.addEventListener("abort", () => reject(request.signal.reason));
    });

  const pending = client(fetch).GET(SAMPLE, { params: { path }, signal: controller.signal });
  controller.abort();

  await assert.rejects(pending, (error: unknown) => error instanceof DOMException && error.name === "AbortError");
});
