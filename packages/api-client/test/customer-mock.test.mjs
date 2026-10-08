import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

const packageDirectory = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const launcher = path.join(packageDirectory, "scripts/customer-mock.mjs");

test("customer manual-test mock filters activities and prices the requested duration", async (t) => {
  const port = 4120;
  const child = spawn(process.execPath, [launcher, "--port", String(port)], {
    cwd: path.resolve(packageDirectory, "../.."),
    stdio: ["ignore", "pipe", "pipe"],
  });
  t.after(() => child.kill());
  let output = "";
  await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error(`mock did not start:\n${output}`)), 30_000);
    const receive = (chunk) => {
      output += chunk.toString();
      if (output.includes("Customer smart mock is listening")) {
        clearTimeout(timeout);
        resolve();
      }
    };
    child.stdout.on("data", receive);
    child.stderr.on("data", receive);
    child.once("exit", (code) => reject(new Error(`mock exited with ${code}:\n${output}`)));
  });

  const origin = `http://127.0.0.1:${port}`;
  const playStation = await getJson(`${origin}/api/v1/customer/venues?activity=PLAYSTATION&lat=30.05&lng=31.33`);
  const billiards = await getJson(`${origin}/api/v1/customer/venues?activity=BILLIARDS&lat=30.05&lng=31.33`);
  assert.equal(playStation.data.items.length, 1);
  assert.equal(billiards.data.items.length, 0);

  const quote = await getJson(`${origin}/api/v1/customer/bookings/quote?resourceId=c2d3e4f5-a6b7-4c8d-9e0f-1a2b3c4d5e6f&playMode=SINGLE&startAt=2026-10-12T18%3A00%3A00Z&durationMinutes=180`, { headers: { Authorization: "Bearer mock-access-token" } });
  assert.equal(quote.data.nominalBookedMinutes, 180);
  assert.equal(quote.data.hourlyPrice.amount, "150.00");
  assert.equal(quote.data.venuePriceTotal.amount, "450.00");
  assert.equal(quote.data.totalAmount.amount, "450.00");

  const bookingResponse = await fetch(`${origin}/api/v1/customer/bookings`, {
    body: JSON.stringify({
      durationMinutes: 180,
      playMode: "SINGLE",
      resourceId: "c2d3e4f5-a6b7-4c8d-9e0f-1a2b3c4d5e6f",
      startAt: "2026-10-12T18:00:00Z",
    }),
    headers: {
      Authorization: "Bearer mock-access-token",
      "Content-Type": "application/json",
      "Idempotency-Key": "11111111-1111-4111-8111-111111111111",
    },
    method: "POST",
  });
  if (bookingResponse.status !== 201) assert.fail(`Expected 201, received ${bookingResponse.status}: ${await bookingResponse.text()}`);
  const booking = await bookingResponse.json();
  assert.equal(booking.data.venueResponsePaused, false);
  assert.equal(booking.data.nominalBookedMinutes, 180);
  assert.equal(booking.data.totalAmount.amount, "450.00");
  assert.equal(Date.parse(booking.data.venueResponseExpiresAt) - Date.parse(booking.meta.serverTime), 10 * 60_000);
});

async function getJson(url, init) {
  const response = await fetch(url, init);
  if (response.status !== 200) assert.fail(`Expected 200, received ${response.status}: ${await response.text()}`);
  return response.json();
}
