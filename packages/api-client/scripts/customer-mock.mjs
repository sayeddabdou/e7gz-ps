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

function shutdown() {
  server.close();
  prism.kill();
}
process.once("SIGINT", shutdown);
process.once("SIGTERM", shutdown);
