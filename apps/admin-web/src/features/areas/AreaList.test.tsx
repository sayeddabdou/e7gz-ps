import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { createAdminApiClient } from "../../api";
import { App } from "../../app/App";
import { AreaList } from "./AreaList";

afterEach(cleanup);

const config = { apiBaseUrl: "http://127.0.0.1:4010", accessToken: "mock-access-token" };
const meta = { correlationId: "c-1", serverTime: "2026-10-12T17:04:11Z" };
const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

function renderList(fetch: (request: Request) => Promise<Response>) {
  const requests: Request[] = [];
  const api = createAdminApiClient(config, async (request: Request) => {
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

    expect((await screen.findByRole("row")).textContent).toBe("Nasr Cityمدينة نصرActive");
    expect(requests[0]!.url).toBe("http://127.0.0.1:4010/api/v1/admin/areas");
    expect(requests[0]!.headers.get("Authorization")).toBe("Bearer mock-access-token");
  });

  it("shows the error code of a documented error response, and retries", async () => {
    let calls = 0;
    renderList(async () => (++calls === 1 ? json(422, { error: { code: "AREA_HAS_ACTIVE_VENUES" }, meta }) : json(200, { data: [], meta })));

    const alert = await screen.findByRole("alert");
    expect(alert.textContent).toContain("Could not load the areas.");
    expect(alert.textContent).toContain("AREA_HAS_ACTIVE_VENUES");

    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    expect(await screen.findByText("No areas yet.")).toBeTruthy();
  });

  it("shows a network failure as an error, not as an empty list", async () => {
    renderList(async () => {
      throw new TypeError("fetch failed");
    });

    expect((await screen.findByRole("alert")).textContent).toContain("The server could not be reached.");
  });
});

describe("App", () => {
  it("names the missing configuration instead of guessing a backend", () => {
    render(<App config={{ ok: false, missing: ["VITE_API_BASE_URL"] }} />);

    expect(screen.getByRole("alert").textContent).toContain("VITE_API_BASE_URL");
  });
});
