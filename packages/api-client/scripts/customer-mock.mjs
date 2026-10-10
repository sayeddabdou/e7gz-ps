// Customer-app manual-test mock. Prism remains the contract authority; this proxy only makes the contract examples
// respond to the selected activity and duration so the E7-50 flow can be tested before E7-46 is implemented.
import { spawn } from "node:child_process";
import http from "node:http";
import path from "node:path";
import { clearInterval, setInterval } from "node:timers";
import { fileURLToPath } from "node:url";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const prismLauncher = path.join(scriptDirectory, "mock.mjs");
const args = process.argv.slice(2);
const option = (name, fallback) => {
  const equals = args.find((value) => value.startsWith(`${name}=`));
  if (equals) return equals.slice(name.length + 1);
  const index = args.indexOf(name);
  return index >= 0 && args[index + 1] ? args[index + 1] : fallback;
};
const host = option("--host", "127.0.0.1");
const port = Number(option("--port", "4010"));
const prismPort = port + 1;
const prismOrigin = `http://127.0.0.1:${prismPort}`;
const quotedAttempts = new Map();
const mockStartedAt = new Date();
const mockBookings = [
  mockBooking({
    bookingId: "018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e6f",
    displayBucket: "upcoming",
    endAt: relativeInstant(2 * 24 * 60 + 120),
    resourceName: "Room 1",
    startAt: relativeInstant(2 * 24 * 60),
    status: "PENDING",
  }),
  mockBooking({
    bookingId: "018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e70",
    displayBucket: "completed",
    endAt: relativeInstant(-2 * 24 * 60),
    resourceName: "Room 2",
    startAt: relativeInstant(-2 * 24 * 60 - 120),
    status: "COMPLETED",
  }),
  mockBooking({
    bookingId: "018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e71",
    displayBucket: "cancelled",
    endAt: relativeInstant(3 * 24 * 60 + 120),
    resourceName: "Room 3",
    startAt: relativeInstant(3 * 24 * 60),
    status: "CANCELLED",
    terminalReason: "CUSTOMER_CANCELLED",
  }),
];
const mockNotifications = [{
  bookingId: mockBookings[0].bookingId,
  code: "BOOKING_CREATED",
  createdAt: relativeInstant(-2),
  id: "018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e72",
  params: {},
  readAt: null,
  tournamentId: null,
}];

const prism = spawn(process.execPath, [prismLauncher, "--host", "127.0.0.1", "--port", String(prismPort)], {
  cwd: path.resolve(scriptDirectory, "../../.."),
  env: process.env,
  stdio: ["ignore", "pipe", "pipe"],
});

let ready = false;
let startupOutput = "";
const readyPromise = new Promise((resolve, reject) => {
  let settled = false;
  let probe;
  let timeout;
  const finish = (callback) => {
    if (settled) return;
    settled = true;
    clearTimeout(timeout);
    clearInterval(probe);
    callback();
  };
  const failStartup = (error) => finish(() => {
    if (prism.exitCode === null) prism.kill();
    reject(error);
  });
  timeout = setTimeout(
    () => failStartup(new Error(`Prism did not start within 60 seconds:\n${startupOutput || "No output was received from Prism."}`)),
    60_000,
  );
  // Prism's logger can remain buffered when its stdout is piped on Windows. Probe the
  // private HTTP port as the source of truth instead of waiting only for log text.
  const probePrism = () => {
    const request = http.get(prismOrigin, (response) => {
      response.resume();
      if (!ready) {
        ready = true;
        finish(resolve);
      }
    });
    request.setTimeout(500, () => request.destroy());
    request.on("error", () => undefined);
  };
  probe = setInterval(probePrism, 100);
  probePrism();
  const receive = (chunk) => {
    const text = chunk.toString();
    startupOutput += text;
    process.stdout.write(text);
    if (!ready && /Prism is listening/u.test(startupOutput)) {
      ready = true;
      finish(resolve);
    }
  };
  prism.stdout.on("data", receive);
  prism.stderr.on("data", receive);
  prism.once("error", (error) => {
    if (!ready) failStartup(new Error(`Prism could not be started: ${error.message}`));
  });
  prism.once("exit", (code) => {
    if (!ready) failStartup(new Error(`Prism exited with ${code}:\n${startupOutput}`));
  });
});

await readyPromise;

const server = http.createServer(async (request, response) => {
  try {
    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);
    const requestBody = Buffer.concat(chunks);
    const url = new URL(request.url ?? "/", `http://${request.headers.host ?? "localhost"}`);
    const headers = new Headers();
    Object.entries(request.headers).forEach(([name, value]) => {
      if (value !== undefined && !["host", "content-length", "connection"].includes(name)) headers.set(name, Array.isArray(value) ? value.join(",") : value);
    });
    const preferredStatus = customerManualTestStatus(request.method ?? "GET", url.pathname);
    if (preferredStatus) headers.set("Prefer", `code=${preferredStatus}`);
    const upstream = await fetch(`${prismOrigin}${url.pathname}${url.search}`, {
      body: requestBody.length ? requestBody : undefined,
      headers,
      method: request.method,
    });
    const contentType = upstream.headers.get("content-type") ?? "";
    let body = Buffer.from(await upstream.arrayBuffer());
    if (contentType.includes("application/json") && upstream.ok) {
      const document = JSON.parse(body.toString("utf8"));
      await makeCustomerExampleDynamic(document, url, request.method ?? "GET", requestBody, headers);
      body = Buffer.from(JSON.stringify(document));
    }
    response.statusCode = upstream.status;
    upstream.headers.forEach((value, name) => {
      if (!["content-length", "transfer-encoding", "connection"].includes(name.toLowerCase())) response.setHeader(name, value);
    });
    response.setHeader("content-length", String(body.length));
    response.end(body);
  } catch (error) {
    const body = Buffer.from(JSON.stringify({ error: { code: "INTERNAL_ERROR" } }));
    response.writeHead(500, { "content-length": String(body.length), "content-type": "application/json" });
    response.end(body);
    console.error(error);
  }
});

server.listen(port, host, () => console.log(`Customer smart mock is listening on http://${host}:${port}`));

async function makeCustomerExampleDynamic(document, url, method, requestBody, headers) {
  if (method === "GET" && url.pathname === "/api/v1/customer/bookings") {
    const bucket = url.searchParams.get("bucket");
    document.data.items = mockBookings.filter((booking) => booking.displayBucket === bucket).map(bookingSummary);
    document.data.nextCursor = null;
    document.meta.serverTime = new Date().toISOString();
    return;
  }

  const bookingDetailMatch = url.pathname.match(/^\/api\/v1\/customer\/bookings\/([^/]+)$/u);
  if (method === "GET" && bookingDetailMatch) {
    const booking = mockBookings.find(({ bookingId }) => bookingId === bookingDetailMatch[1]);
    if (booking) {
      document.data = bookingDetail(booking);
      document.meta.serverTime = new Date().toISOString();
      return;
    }
  }

  const cancellationMatch = url.pathname.match(/^\/api\/v1\/customer\/bookings\/([^/]+)\/cancel$/u);
  if (method === "POST" && cancellationMatch) {
    const booking = mockBookings.find(({ bookingId }) => bookingId === cancellationMatch[1]);
    if (booking) {
      const previousStatus = booking.status;
      booking.status = "CANCELLED";
      booking.displayBucket = "cancelled";
      booking.terminalReason = "CUSTOMER_CANCELLED";
      booking.venueResponseExpiresAt = null;
      booking.history.push(statusTransition(previousStatus, "CANCELLED", "CUSTOMER_CANCELLED"));
      document.data = bookingDetail(booking);
      document.meta.serverTime = new Date().toISOString();
    }
    return;
  }

  if (method === "GET" && url.pathname === "/api/v1/customer/notifications") {
    document.data.items = mockNotifications.map((notification) => structuredClone(notification));
    document.data.nextCursor = null;
    document.meta.serverTime = new Date().toISOString();
    return;
  }

  const notificationReadMatch = url.pathname.match(/^\/api\/v1\/customer\/notifications\/([^/]+)\/read$/u);
  if (method === "POST" && notificationReadMatch) {
    const notification = mockNotifications.find(({ id }) => id === notificationReadMatch[1]);
    if (notification) notification.readAt = new Date().toISOString();
    return;
  }

  const activity = url.searchParams.get("activity");
  if (method === "GET" && (url.pathname === "/api/v1/customer/venues" || url.pathname === "/api/v1/customer/venues/map")) {
    const items = Array.isArray(document.data) ? document.data : document.data?.items;
    if (activity && Array.isArray(items)) {
      const checks = await Promise.all(items.map((item) => venueOffers(item.id, activity, headers)));
      const filtered = items.filter((_, index) => checks[index]);
      if (Array.isArray(document.data)) document.data = filtered;
      else {
        document.data.items = filtered;
        if (!filtered.length) document.data.nextCursor = null;
      }
    }
    return;
  }

  if (method === "GET" && /^\/api\/v1\/customer\/venues\/[^/]+$/u.test(url.pathname) && activity) {
    if (document.data?.selectedActivity !== activity || !document.data?.activities?.includes(activity)) {
      document.data.selectedActivity = null;
      document.data.resources = [];
      document.data.prices = [];
      document.data.nearestAvailableAt = null;
    }
    return;
  }

  if (method === "GET" && url.pathname === "/api/v1/customer/bookings/quote") {
    const duration = Number(url.searchParams.get("durationMinutes"));
    if (Number.isInteger(duration) && document.data?.hourlyPrice) {
      applyDurationPrice(document.data, duration);
      quotedAttempts.set(quoteKey({
        durationMinutes: duration,
        playMode: url.searchParams.get("playMode"),
        resourceId: url.searchParams.get("resourceId"),
        startAt: url.searchParams.get("startAt"),
      }), structuredClone(document.data));
    }
    return;
  }

  if (method === "POST" && url.pathname === "/api/v1/customer/bookings" && requestBody.length) {
    const request = JSON.parse(requestBody.toString("utf8"));
    const savedQuote = quotedAttempts.get(quoteKey(request));
    if (savedQuote && document.data) {
      const serverTime = new Date();
      document.data.nominalBookedMinutes = request.durationMinutes;
      document.data.venuePriceTotal = savedQuote.venuePriceTotal;
      document.data.bookingFee = savedQuote.bookingFee;
      document.data.totalAmount = savedQuote.totalAmount;
      document.data.startAt = request.startAt;
      document.data.endAt = new Date(Date.parse(request.startAt) + request.durationMinutes * 60_000).toISOString();
      document.data.venueResponsePaused = false;
      document.data.venueResponseExpiresAt = new Date(serverTime.valueOf() + 10 * 60_000).toISOString();
      document.meta.serverTime = serverTime.toISOString();
    }
  }
}

async function venueOffers(venueId, activity, headers) {
  const result = await fetch(`${prismOrigin}/api/v1/customer/venues/${encodeURIComponent(venueId)}?activity=${encodeURIComponent(activity)}`, { headers });
  if (!result.ok) return false;
  const document = await result.json();
  return document.data?.selectedActivity === activity && document.data?.activities?.includes(activity);
}

function applyDurationPrice(quote, durationMinutes) {
  const hourlyCents = moneyToCents(quote.hourlyPrice.amount);
  const feeCents = moneyToCents(quote.bookingFee.amount);
  const venueCents = (hourlyCents * BigInt(durationMinutes) + 30n) / 60n;
  quote.nominalBookedMinutes = durationMinutes;
  quote.venuePriceTotal = { amount: centsToMoney(venueCents), currency: quote.hourlyPrice.currency };
  quote.totalAmount = { amount: centsToMoney(venueCents + feeCents), currency: quote.hourlyPrice.currency };
}

function moneyToCents(amount) {
  const [whole, fraction = ""] = String(amount).split(".");
  return BigInt(whole) * 100n + BigInt(fraction.padEnd(2, "0").slice(0, 2));
}

function centsToMoney(cents) {
  return `${cents / 100n}.${String(cents % 100n).padStart(2, "0")}`;
}

function quoteKey(value) {
  return [value.resourceId, value.startAt, value.durationMinutes, value.playMode ?? ""].join("|");
}

function relativeInstant(minutes) {
  return new Date(mockStartedAt.valueOf() + minutes * 60_000).toISOString();
}

function mockBooking({ bookingId, displayBucket, endAt, resourceName, startAt, status, terminalReason = null }) {
  return {
    attendanceResult: status === "COMPLETED" ? "ATTENDED" : null,
    bookingFee: { amount: "0.00", currency: "EGP", displayAsFree: true },
    bookingId,
    displayBucket,
    endAt,
    history: [statusTransition(null, status, terminalReason, startAt)],
    nominalBookedMinutes: 120,
    resource: { activity: "PLAYSTATION", consoleType: "PS4", name: resourceName, playMode: "SINGLE" },
    startAt,
    status,
    terminalReason,
    totalAmount: { amount: "300.00", currency: "EGP" },
    venue: { id: "7b1f0c9e-2d3a-4e5b-8c6d-9e0f1a2b3c4d", name: "Level Up Lounge", phone: "+201001234567" },
    venuePriceTotal: { amount: "300.00", currency: "EGP" },
    venueResponseExpiresAt: status === "PENDING" ? relativeInstant(10) : null,
    venueResponsePaused: false,
  };
}

function statusTransition(fromStatus, toStatus, terminalReason = null, at = new Date().toISOString()) {
  return { at, eventType: null, fromStatus, kind: "STATUS_TRANSITION", terminalReason, toStatus };
}

function bookingSummary(booking) {
  return structuredClone({
    bookingId: booking.bookingId,
    displayBucket: booking.displayBucket,
    endAt: booking.endAt,
    resource: booking.resource,
    startAt: booking.startAt,
    status: booking.status,
    terminalReason: booking.terminalReason,
    totalAmount: booking.totalAmount,
    venue: booking.venue,
    venueResponseExpiresAt: booking.venueResponseExpiresAt,
  });
}

function bookingDetail(booking) {
  return structuredClone(booking);
}

function customerManualTestStatus(method, pathname) {
  if (method === "GET" && pathname === "/api/v1/customer/bookings") return 200;
  if (method === "GET" && /^\/api\/v1\/customer\/bookings\/[^/]+$/u.test(pathname) && !pathname.endsWith("/quote")) return 200;
  if (method === "POST" && /^\/api\/v1\/customer\/bookings\/[^/]+\/cancel$/u.test(pathname)) return 200;
  if (method === "GET" && pathname === "/api/v1/customer/notifications") return 200;
  return undefined;
}

function shutdown() {
  server.close();
  prism.kill();
}
process.once("SIGINT", shutdown);
process.once("SIGTERM", shutdown);
