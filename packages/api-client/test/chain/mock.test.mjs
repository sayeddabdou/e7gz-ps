// The `pnpm mock` launcher (scripts/mock.mjs) on the contract generated from the backend's sample controllers
// (OpenApiSampleContractTest). Run by `pnpm test:chain`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { runLauncher, sampleContract, startLauncher } from "../support/launcher.mjs";

const SAMPLE = "/api/v1/customer/samples/s-1";

test("serves a documented endpoint with the example built from the Java DTO", async (t) => {
  const { baseUrl } = await startLauncher(t, ["--spec", sampleContract, "--port", "4021"]);

  const response = await fetch(`${baseUrl}${SAMPLE}`);

  assert.equal(response.status, 200);
  assert.match(response.headers.get("content-type") ?? "", /^application\/json/);
  assert.deepEqual(await response.json(), { sampleId: "s-1", status: "OPEN", note: "window seat" });
});

test("accepts a valid request body and refuses one the contract forbids", async (t) => {
  const { baseUrl } = await startLauncher(t, ["--spec", sampleContract, "--port", "4022"]);
  const post = (body) =>
    fetch(`${baseUrl}${SAMPLE}/notes`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });

  const created = await post({ text: "bring two controllers", status: "LATE_CONFIRMED" });
  assert.equal(created.status, 201);
  assert.deepEqual(Object.keys(await created.json()).sort(), ["note", "sampleId", "status"]);

  const misspelledEnum = await post({ text: "x", status: "open" });
  assert.equal(misspelledEnum.status, 422);
});

test("refuses a write without its required Idempotency-Key", async (t) => {
  const { baseUrl } = await startLauncher(t, ["--spec", sampleContract, "--port", "4027"]);
  const post = (headers) =>
    fetch(`${baseUrl}${SAMPLE}/commitments`, {
      method: "POST",
      headers: { "Content-Type": "application/json", ...headers },
      body: JSON.stringify({ text: "x", status: "OPEN" }),
    });

  // Missing a required header is a malformed request: 400 VALIDATION_FAILED, as documented (arch 09 §1.5, E7-12).
  assert.equal((await post({})).status, 400);
  assert.equal((await post({ "Idempotency-Key": "0f4e5c1a-0000-4000-8000-000000000003" })).status, 201);
});

test("answers 404 for a path outside the contract", async (t) => {
  const { baseUrl } = await startLauncher(t, ["--spec", sampleContract, "--port", "4023"]);

  const response = await fetch(`${baseUrl}/api/v1/customer/not-in-contract`);

  assert.equal(response.status, 404);
});

test("forwards Prism options and does not add a default that was given", async (t) => {
  // --port given → used; --host=… given in = form → the launcher's default host is not added as well.
  const { baseUrl } = await startLauncher(t, ["--spec", sampleContract, "--port", "4024", "--host=127.0.0.1"]);

  assert.equal(baseUrl, "http://127.0.0.1:4024");
  assert.equal((await fetch(`${baseUrl}${SAMPLE}`)).status, 200);
});

test("stops serving when the launcher is stopped", async (t) => {
  const { baseUrl, child, exited } = await startLauncher(t, ["--spec", sampleContract, "--port", "4025"]);
  assert.equal((await fetch(`${baseUrl}${SAMPLE}`)).status, 200);

  child.kill();
  await exited;

  await assert.rejects(fetch(`${baseUrl}${SAMPLE}`));
});

test("propagates a Prism failure as a non-zero exit", async () => {
  const dir = mkdtempSync(path.join(tmpdir(), "e7gz-mock-"));
  const broken = path.join(dir, "broken.openapi.json");
  // Valid JSON with an operation, but a $ref Prism cannot resolve.
  writeFileSync(broken, JSON.stringify({
    openapi: "3.1.0",
    info: { title: "broken", version: "1" },
    paths: { "/x": { get: { responses: { 200: { $ref: "#/components/responses/Missing" } } } } },
  }));

  const { code, output } = await runLauncher(["--spec", broken, "--port", "4026"]);

  assert.notEqual(code, 0);
  assert.match(output, /MissingPointerError/); // Prism's own error, not the launcher's pre-checks
  assert.doesNotMatch(output, /Prism is listening/);
});

test("refuses a contract without endpoints", async () => {
  const dir = mkdtempSync(path.join(tmpdir(), "e7gz-mock-"));
  const empty = path.join(dir, "empty.openapi.json");
  writeFileSync(empty, JSON.stringify({ openapi: "3.1.0", info: { title: "empty", version: "1" }, paths: {} }));

  const { code, output } = await runLauncher(["--spec", empty]);

  assert.equal(code, 1);
  assert.match(output, /has no endpoints/);
});
