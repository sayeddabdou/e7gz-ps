import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { createAdminApiClient } from "../../api";
import { ActivityList } from "./ActivityList";

afterEach(cleanup);

const meta = { correlationId: "c-1", serverTime: "2026-10-04T10:00:00Z" };
const json = (status: number, body: unknown) => new Response(JSON.stringify(body), {
  status,
  headers: { "Content-Type": "application/json" },
});

function renderActivities(fetch: (request: Request) => Promise<Response>) {
  const requests: Request[] = [];
  const api = createAdminApiClient({ apiBaseUrl: "http://api" }, () => "admin-token", async (request) => {
    requests.push(request);
    return fetch(request);
  });
  render(<ActivityList api={api} />);
  return requests;
}

describe("ActivityList", () => {
  it("shows loading and then the read-only activity counts from the contract", async () => {
    let answer: (response: Response) => void = () => {};
    const requests = renderActivities(() => new Promise((resolve) => { answer = resolve; }));

    expect(screen.getByRole("status").textContent).toBe("Loading…");
    await waitFor(() => expect(requests).toHaveLength(1));
    answer(json(200, { data: [{ activity: "PLAYSTATION", venueCount: 4 }, { activity: "PING_PONG", venueCount: 2 }], meta }));

    const rows = await screen.findAllByRole("row");
    expect(rows[1]!.textContent).toBe("PLAYSTATION4");
    expect(rows[2]!.textContent).toBe("PING PONG2");
    expect(requests[0]!.url).toBe("http://api/api/v1/admin/activities");
    expect(requests[0]!.headers.get("Authorization")).toBe("Bearer admin-token");
    expect(screen.queryByRole("button")).toBeNull();
  });

  it("shows an explicit empty state", async () => {
    renderActivities(async () => json(200, { data: [], meta }));

    expect(await screen.findByText("No activities yet.")).toBeTruthy();
    expect(screen.queryByRole("table")).toBeNull();
  });

  it("shows an API failure and retries successfully", async () => {
    let calls = 0;
    renderActivities(async () => ++calls === 1
      ? json(500, { error: { code: "UNEXPECTED_ERROR" }, meta })
      : json(200, { data: [{ activity: "BILLIARDS", venueCount: 1 }], meta }));

    expect((await screen.findByRole("alert")).textContent).toContain("Something went wrong. Try again.");
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));

    expect((await screen.findAllByRole("row"))[1]!.textContent).toBe("BILLIARDS1");
    expect(calls).toBe(2);
  });
});
