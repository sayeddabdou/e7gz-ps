import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { createDashboardApiClient } from "../../api";
import { App } from "../../app/App";
import { messages, MessagesContext } from "../../i18n";
import { ResourceList } from "./ResourceList";

afterEach(cleanup);

const config = { apiBaseUrl: "http://127.0.0.1:4010", accessToken: "mock-access-token" };
const meta = { correlationId: "c-1", serverTime: "2026-10-12T17:04:11Z" };
const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

function renderList(fetch: (request: Request) => Promise<Response>) {
  const requests: Request[] = [];
  const api = createDashboardApiClient(config, async (request: Request) => {
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

    expect((await screen.findByRole("listitem")).textContent).toBe("Room 1 · PlayStation · PS5 · Active");
    expect(requests[0]!.url).toBe("http://127.0.0.1:4010/api/v1/venue/resources");
    expect(requests[0]!.headers.get("Authorization")).toBe("Bearer mock-access-token");
  });

  it("shows the error code of a documented error response, and retries", async () => {
    let calls = 0;
    renderList(async () => (++calls === 1 ? json(401, { error: { code: "PHONE_VERIFICATION_REQUIRED" }, meta }) : json(200, { data: [], meta })));

    const alert = await screen.findByRole("alert");
    expect(alert.textContent).toContain("Could not load the resources.");
    expect(alert.textContent).toContain("PHONE_VERIFICATION_REQUIRED");

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
