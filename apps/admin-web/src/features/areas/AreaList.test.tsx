import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { createAdminApiClient } from "../../api";
import { App } from "../../app/App";
import { AreaList } from "./AreaList";

afterEach(cleanup);

const config = { apiBaseUrl: "http://127.0.0.1:4010" };
const meta = { correlationId: "c-1", serverTime: "2026-10-12T17:04:11Z" };
const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

function renderList(fetch: (request: Request) => Promise<Response>) {
  const requests: Request[] = [];
  const api = createAdminApiClient(config, () => "mock-access-token", async (request: Request) => {
    requests.push(request);
    return fetch(request);
  });
  render(<AreaList api={api} />);
  return requests;
}

describe("AreaList", () => {
  it("shows loading, then the areas from the contract, sending the configured token", async () => {
    let answer: (response: Response) => void = () => {};
    const requests = renderList(() => new Promise((resolve) => (answer = resolve)));

    expect(screen.getByRole("status").textContent).toBe("Loading…");
    await waitFor(() => expect(requests).toHaveLength(1)); // the token is awaited before fetch is called
    answer(json(200, { data: [{ id: "a-1", name: "Nasr City", nameAr: "مدينة نصر", active: true, centroid: null }], meta }));

    expect((await screen.findAllByRole("row"))[1]!.textContent).toContain("Nasr Cityمدينة نصرActive");
    expect(requests[0]!.url).toBe("http://127.0.0.1:4010/api/v1/admin/areas");
    expect(requests[0]!.headers.get("Authorization")).toBe("Bearer mock-access-token");
  });

  it("shows the error code of a documented error response, and retries", async () => {
    let calls = 0;
    renderList(async () => (++calls === 1 ? json(422, { error: { code: "AREA_HAS_ACTIVE_VENUES" }, meta }) : json(200, { data: [], meta })));

    const alert = await screen.findByRole("alert");
    expect(alert.textContent).toContain("This area still has active or suspended venues.");
    expect(alert.textContent).not.toContain("AREA_HAS_ACTIVE_VENUES");

    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    expect(await screen.findByText("No areas yet.")).toBeTruthy();
  });

  it("shows a network failure as an error, not as an empty list", async () => {
    renderList(async () => {
      throw new TypeError("fetch failed");
    });

    expect((await screen.findByRole("alert")).textContent).toContain("The server could not be reached.");
  });

  it("maps AREA_HAS_ACTIVE_VENUES when deactivation is refused", async () => {
    renderList(async (request) => request.method === "GET"
      ? json(200, { data: [{ id: "a-1", name: "Nasr City", nameAr: null, active: true, centroid: null }], meta })
      : json(422, { error: { code: "AREA_HAS_ACTIVE_VENUES" }, meta }));
    fireEvent.click(await screen.findByRole("button", { name: "Deactivate" }));
    const alert = await screen.findByRole("alert");
    expect(alert.textContent).toBe("This area still has active or suspended venues.");
    expect(alert.textContent).not.toContain("AREA_HAS_ACTIVE_VENUES");
  });

  it("creates an area and sends optional Arabic name and centroid", async () => {
    const requests: Request[] = [];
    renderList(async (request) => {
      requests.push(request);
      if (request.method === "POST") return json(201, { data: { id: "a-2", name: "Maadi", nameAr: "المعادي", active: true, centroid: { lat: 29.96, lng: 31.25 } }, meta });
      return json(200, { data: [], meta });
    });
    await screen.findByText("No areas yet.");
    fireEvent.click(screen.getByRole("button", { name: "Create area" }));
    fireEvent.change(screen.getByLabelText("English name"), { target: { value: "Maadi" } });
    fireEvent.change(screen.getByLabelText("Arabic name (optional)"), { target: { value: "المعادي" } });
    fireEvent.change(screen.getByLabelText("Centroid (optional lat,lng)"), { target: { value: "29.96,31.25" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(requests.some((request) => request.method === "POST")).toBe(true));
    const create = requests.find((request) => request.method === "POST")!;
    expect(JSON.parse(await create.text())).toEqual({ name: "Maadi", nameAr: "المعادي", centroid: { lat: 29.96, lng: 31.25 } });
    expect(create.headers.get("Idempotency-Key")).toBeNull();
  });

  it("removes optional Arabic name and centroid explicitly when editing", async () => {
    const requests: Request[] = [];
    const area = { id: "a-1", name: "Nasr City", nameAr: "مدينة نصر", active: true, centroid: { lat: 30.0561, lng: 31.3301 } };
    renderList(async (request) => {
      requests.push(request);
      if (request.method === "PATCH") return json(200, { data: { ...area, nameAr: null, centroid: null }, meta });
      return json(200, { data: [area], meta });
    });
    fireEvent.click(await screen.findByRole("button", { name: "Edit" }));
    fireEvent.change(screen.getByLabelText("Arabic name (optional)"), { target: { value: "" } });
    fireEvent.change(screen.getByLabelText("Centroid (optional lat,lng)"), { target: { value: "" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(requests.some((request) => request.method === "PATCH")).toBe(true));
    const update = requests.find((request) => request.method === "PATCH")!;
    expect(JSON.parse(await update.text())).toEqual({ name: "Nasr City", removeNameAr: true, removeCentroid: true });
    expect(update.headers.get("Idempotency-Key")).toBeNull();
  });

  it("activates an inactive area through the documented endpoint", async () => {
    const requests: Request[] = [];
    let active = false;
    renderList(async (request) => {
      requests.push(request);
      if (request.method === "POST") active = true;
      return request.method === "GET"
        ? json(200, { data: [{ id: "a-1", name: "Nasr City", nameAr: null, active, centroid: null }], meta })
        : json(200, { data: { id: "a-1", name: "Nasr City", nameAr: null, active: true, centroid: null }, meta });
    });
    fireEvent.click(await screen.findByRole("button", { name: "Activate" }));
    await waitFor(() => expect(requests.some((request) => request.url.endsWith("/areas/a-1/activate"))).toBe(true));
    expect(requests.find((request) => request.url.endsWith("/areas/a-1/activate"))!.headers.get("Idempotency-Key")).toBeNull();
  });
});

describe("App", () => {
  it("names the missing configuration instead of guessing a backend", () => {
    render(<App config={{ ok: false, missing: ["VITE_API_BASE_URL"] }} />);

    expect(screen.getByRole("alert").textContent).toContain("VITE_API_BASE_URL");
  });
});
