import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { MemoryRouter } from "react-router-dom";
import { createAdminApiClient } from "../../api";
import { VenueList } from "./VenueList";

afterEach(cleanup);

const meta = { correlationId: "c-1", serverTime: "2026-10-04T10:00:00Z" };
const json = (status: number, body: unknown) => new Response(JSON.stringify(body), {
  status,
  headers: { "Content-Type": "application/json" },
});
const area = { id: "a-1", name: "Nasr City", nameAr: "مدينة نصر", active: true, centroid: { lat: 30.0566, lng: 31.3301 } };
const venue = (id: string, name: string, marketplaceStatus: "ONBOARDING" | "ACTIVE" = "ACTIVE") => ({
  id,
  name,
  areaId: "a-1",
  address: "12 Abbas El Akkad St",
  phone: "+201001234567",
  location: { lat: 30.06, lng: 31.33 },
  timezone: "Africa/Cairo",
  activities: ["PLAYSTATION"],
  marketplaceStatus,
});

function renderVenues(fetch: (request: Request) => Promise<Response>) {
  const requests: Request[] = [];
  const api = createAdminApiClient({ apiBaseUrl: "http://api" }, () => "admin-token", async (request) => {
    requests.push(request);
    return fetch(request);
  });
  render(<MemoryRouter><VenueList api={api} /></MemoryRouter>);
  return requests;
}

describe("VenueList", () => {
  it("shows loading and then an explicit empty state", async () => {
    let answerVenues: (response: Response) => void = () => {};
    let answerAreas: (response: Response) => void = () => {};
    const requests = renderVenues((request) => request.url.includes("/venues")
      ? new Promise((resolve) => { answerVenues = resolve; })
      : new Promise((resolve) => { answerAreas = resolve; }));

    expect(screen.getByRole("status").textContent).toBe("Loading…");
    await waitFor(() => expect(requests).toHaveLength(2));
    answerVenues(json(200, { data: { items: [], nextCursor: null }, meta }));
    answerAreas(json(200, { data: [area], meta }));

    expect(await screen.findByText("No venues yet.")).toBeTruthy();
    expect(requests.every((request) => request.headers.get("Authorization") === "Bearer admin-token")).toBe(true);
  });

  it("shows a failed initial load and retries both contract requests", async () => {
    let venueCalls = 0;
    let areaCalls = 0;
    renderVenues(async (request) => {
      if (request.url.includes("/venues")) {
        venueCalls += 1;
        return venueCalls === 1
          ? json(500, { error: { code: "UNEXPECTED_ERROR" }, meta })
          : json(200, { data: { items: [], nextCursor: null }, meta });
      }
      areaCalls += 1;
      return json(200, { data: [area], meta });
    });

    expect((await screen.findByRole("alert")).textContent).toContain("Something went wrong. Try again.");
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));

    expect(await screen.findByText("No venues yet.")).toBeTruthy();
    expect(venueCalls).toBe(2);
    expect(areaCalls).toBe(2);
  });

  it("renders localized statuses and appends the next cursor page", async () => {
    const requests = renderVenues(async (request) => {
      if (request.url.includes("/areas")) return json(200, { data: [area], meta });
      const cursor = new URL(request.url).searchParams.get("cursor");
      return cursor
        ? json(200, { data: { items: [venue("v-2", "Second Arena", "ONBOARDING")], nextCursor: null }, meta })
        : json(200, { data: { items: [venue("v-1", "Newest Arena")], nextCursor: "page-2" }, meta });
    });

    expect((await screen.findByRole("link", { name: "Newest Arena" })).getAttribute("href")).toBe("/venues/v-1");
    expect(screen.getByText("Active")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Load more" }));

    expect(await screen.findByRole("link", { name: "Second Arena" })).toBeTruthy();
    expect(screen.getByText("Onboarding")).toBeTruthy();
    expect(screen.getAllByText("Nasr City")).toHaveLength(2);
    const pageRequest = requests.find((request) => new URL(request.url).searchParams.get("cursor") === "page-2");
    expect(pageRequest).toBeTruthy();
    expect(new URL(pageRequest!.url).searchParams.get("limit")).toBe("20");
    expect(screen.queryByRole("button", { name: "Load more" })).toBeNull();
  });
});
