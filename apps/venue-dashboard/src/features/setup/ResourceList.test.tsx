import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { createDashboardApiClient } from "../../api";
import { App } from "../../app/App";
import { messages, MessagesContext } from "../../i18n";
import { ResourceList } from "./ResourceList";

afterEach(cleanup);

const config = { apiBaseUrl: "http://127.0.0.1:4010" };
const meta = { correlationId: "c-1", serverTime: "2026-10-12T17:04:11Z" };
const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

function renderList(fetch: (request: Request) => Promise<Response>) {
  const requests: Request[] = [];
  const api = createDashboardApiClient(config, () => "mock-access-token", async (request: Request) => {
    requests.push(request);
    return fetch(request);
  });
  render(
    <MessagesContext.Provider value={messages.en}>
      <ResourceList api={api} />
    </MessagesContext.Provider>,
  );
  return requests;
}

describe("ResourceList", () => {
  it("shows loading, then the resources from the contract, sending the configured token", async () => {
    let answer: (response: Response) => void = () => {};
    const requests = renderList(() => new Promise((resolve) => (answer = resolve)));

    expect(screen.getByRole("status").textContent).toBe("Loading…");
    await waitFor(() => expect(requests).toHaveLength(1)); // the token is awaited before fetch is called
    answer(json(200, {
      data: [{ id: "r-1", activity: "PLAYSTATION", name: "Room 1", consoleType: "PS5", baseState: "ACTIVE", operationalRestriction: "NONE" }],
      meta,
    }));

    const resource = await screen.findByRole("listitem");
    expect(resource.textContent).toContain("Room 1");
    expect(resource.textContent).toContain("PlayStation · PS5 · Active");
    expect(resource.textContent).toContain("Change console");
    expect(resource.textContent).toContain("Disable");
    expect(resource.textContent).toContain("Archive");
    expect(requests[0]!.url).toBe("http://127.0.0.1:4010/api/v1/venue/resources");
    expect(requests[0]!.headers.get("Authorization")).toBe("Bearer mock-access-token");
  });

  it("maps a documented error response through the shared message map, and retries", async () => {
    let calls = 0;
    renderList(async () => (++calls === 1 ? json(403, { error: { code: "FORBIDDEN" }, meta }) : json(200, { data: [], meta })));

    const alert = await screen.findByRole("alert");
    expect(alert.textContent).toContain("This action is not available for this account.");

    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    expect(await screen.findByText("No resources yet.")).toBeTruthy();
  });

  it("shows a network failure as an error, not as an empty list", async () => {
    renderList(async () => {
      throw new TypeError("fetch failed");
    });

    expect((await screen.findByRole("alert")).textContent).toContain("The server could not be reached.");
    expect(screen.queryByText("No resources yet.")).toBeNull();
  });
});

describe("App", () => {
  it("switches to Arabic and mirrors the layout (dir=rtl)", async () => {
    render(<App config={{ ok: false, missing: ["VITE_API_BASE_URL"] }} />);
    expect(screen.getByRole("main").getAttribute("dir")).toBe("ltr");

    fireEvent.click(screen.getByRole("button", { name: "العربية" }));

    expect(screen.getByRole("main").getAttribute("dir")).toBe("rtl");
    expect(document.documentElement.dir).toBe("rtl");
    expect(screen.getByRole("heading", { level: 1 }).textContent).toBe("لوحة تحكم المكان");
  });

  it("names the missing configuration instead of guessing a backend", () => {
    render(<App config={{ ok: false, missing: ["VITE_API_BASE_URL"] }} />);

    expect(screen.getByRole("alert").textContent).toContain("VITE_API_BASE_URL");
  });
});
