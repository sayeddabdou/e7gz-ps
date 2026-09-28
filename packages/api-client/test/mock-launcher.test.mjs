// `pnpm mock` on the committed production contract (E7-11 "pnpm mock serves the current contract"): the same launcher
// the root script runs, with no --spec, answering documented Sprint 1 endpoints with their contract examples.
import { test } from "node:test";
import assert from "node:assert/strict";
import { runLauncher, startLauncher } from "./support/launcher.mjs";

// Prism only checks that a Bearer header is present (the bearerAuth scheme's shape). That is not authentication:
// the real backend verifies JWTs (E7-14). Likewise it validates the Owner Password field's presence, never its value.
const MOCK_TOKEN = { Authorization: "Bearer mock-access-token" };

function assertEnvelope(body) {
  assert.ok(body && typeof body === "object", "JSON object body");
  assert.deepEqual(Object.keys(body).sort(), ["data", "meta"]);
  assert.equal(typeof body.meta.correlationId, "string");
  assert.match(body.meta.serverTime, /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$/);
}

test("serves the public customer areas endpoint with the documented envelope", async (t) => {
  const { baseUrl } = await startLauncher(t, ["--port", "4031"]);

  const response = await fetch(`${baseUrl}/api/v1/customer/areas`);

  assert.equal(response.status, 200);
  assert.match(response.headers.get("content-type") ?? "", /^application\/json/);
  const body = await response.json();
  assertEnvelope(body);
  assert.ok(Array.isArray(body.data) && body.data.length > 0);
  for (const area of body.data) {
    assert.deepEqual(Object.keys(area).sort(), ["id", "name", "nameAr"]);
    assert.match(area.id, /^[0-9a-f-]{36}$/);
  }
});

test("serves the venue list with a required query parameter and a cursor page", async (t) => {
  const { baseUrl } = await startLauncher(t, ["--port", "4032"]);

  const ok = await fetch(`${baseUrl}/api/v1/customer/venues?activity=PLAYSTATION`);
  assert.equal(ok.status, 200);
  const body = await ok.json();
  assertEnvelope(body);
  assert.ok(Array.isArray(body.data.items));
  assert.ok("nextCursor" in body.data);
  const card = body.data.items[0];
  for (const field of ["id", "name", "imageUrl", "area", "distanceMeters", "startingPrice", "nearestAvailableAt",
    "averageRating", "reviewCount"]) {
    assert.ok(field in card, `venue card has ${field} (FR-DIS-03)`);
  }

  // The contract's own validation: activity is required and must be spelled as the enum.
  assert.equal((await fetch(`${baseUrl}/api/v1/customer/venues`)).status, 422);
  assert.equal((await fetch(`${baseUrl}/api/v1/customer/venues?activity=playstation`)).status, 422);
});

test("public discovery answers without a token; venue and admin routes need a Bearer header (shape only)", async (t) => {
  const { baseUrl } = await startLauncher(t, ["--port", "4033"]);

  assert.equal((await fetch(`${baseUrl}/api/v1/admin/areas`)).status, 401);
  assert.equal((await fetch(`${baseUrl}/api/v1/venue/resources`)).status, 401);

  const areas = await fetch(`${baseUrl}/api/v1/admin/areas`, { headers: MOCK_TOKEN });
  assert.equal(areas.status, 200);
  const adminBody = await areas.json();
  assertEnvelope(adminBody);
  assert.deepEqual(Object.keys(adminBody.data[0]).sort(), ["active", "centroid", "id", "name", "nameAr"]);

  const resources = await fetch(`${baseUrl}/api/v1/venue/resources`, { headers: MOCK_TOKEN });
  assert.equal(resources.status, 200);
  const venueBody = await resources.json();
  assertEnvelope(venueBody);
  assert.deepEqual(Object.keys(venueBody.data[0]).sort(),
    ["activity", "baseState", "consoleType", "id", "name", "operationalRestriction"]);
});

test("validates a protected write's request body shape: ownerPassword present accepted, absent refused", async (t) => {
  const { baseUrl } = await startLauncher(t, ["--port", "4034"]);
  const url = `${baseUrl}/api/v1/venue/resources/c2d3e4f5-a6b7-4c8d-9e0f-1a2b3c4d5e6f/disable`;
  const post = (body) => fetch(url, {
    method: "POST",
    headers: { ...MOCK_TOKEN, "Content-Type": "application/json", "Idempotency-Key": "0f4e5c1a-0000-4000-8000-000000000001" },
    body: JSON.stringify(body),
  });

  const accepted = await post({ ownerPassword: "owner-secret" });
  assert.equal(accepted.status, 200);
  assertEnvelope(await accepted.json());

  assert.equal((await post({})).status, 422);
});

test("answers 404 for a path outside the contract", async (t) => {
  const { baseUrl } = await startLauncher(t, ["--port", "4035"]);

  assert.equal((await fetch(`${baseUrl}/api/v1/customer/not-in-contract`)).status, 404);
});

test("stops serving when the launcher is stopped", async (t) => {
  const { baseUrl, child, exited } = await startLauncher(t, ["--port", "4036"]);
  assert.equal((await fetch(`${baseUrl}/api/v1/customer/areas`)).status, 200);

  child.kill();
  await exited;

  await assert.rejects(fetch(`${baseUrl}/api/v1/customer/areas`));
});

test("a missing contract file fails with a non-zero exit", async () => {
  const { code, output } = await runLauncher(["--spec", "does-not-exist.json"]);

  assert.equal(code, 1);
  assert.match(output, /contract not found/);
});
