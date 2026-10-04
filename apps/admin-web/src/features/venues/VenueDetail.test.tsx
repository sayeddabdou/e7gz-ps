import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { createAdminApiClient } from "../../api";
import { VenueDetail } from "./VenueDetail";

afterEach(cleanup);
const meta = { correlationId: "c-1", serverTime: "2026-10-03T10:00:00Z" };
const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });
const venue = (marketplaceStatus: "ONBOARDING" | "ACTIVE" | "INACTIVE" | "SUSPENDED") => ({ id: "v-1", name: "Arena", areaId: "a-1", address: "Street", phone: "+201001234567", location: { lat: 30, lng: 31 }, timezone: "Africa/Cairo", activities: ["PLAYSTATION"], marketplaceStatus });

function renderDetail(status: "ONBOARDING" | "ACTIVE" | "INACTIVE" | "SUSPENDED") {
  const api = createAdminApiClient({ apiBaseUrl: "http://api" }, () => "token", async (request) => request.url.endsWith("/images") || request.url.endsWith("/resources") ? json({ data: [], meta }) : json({ data: { venue: venue(status), venueAccount: null }, meta }));
  render(<MemoryRouter initialEntries={["/venues/v-1"]}><Routes><Route path="/venues/:venueId" element={<VenueDetail api={api} />} /></Routes></MemoryRouter>);
}

describe("VenueDetail status actions", () => {
  it("shows only Complete onboarding while the venue is onboarding", async () => {
    renderDetail("ONBOARDING");
    await screen.findByRole("heading", { name: /Arena/ });
    expect(screen.getByRole("button", { name: "Complete onboarding" })).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Activate" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Deactivate" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Suspend" })).toBeNull();
    expect(screen.getByRole("heading", { name: "Venue photos" })).toBeTruthy();
  });

  it("never offers the venue's current status after onboarding", async () => {
    renderDetail("INACTIVE");
    await waitFor(() => expect(screen.getByRole("button", { name: "Activate" })).toBeTruthy());
    expect(screen.getByRole("button", { name: "Suspend" })).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Deactivate" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Complete onboarding" })).toBeNull();
  });

  it("sends a trimmed reason to the exact status endpoint and maps a refused transition", async () => {
    const requests: Request[] = [];
    const api = createAdminApiClient({ apiBaseUrl: "http://api" }, () => "token", async (request) => {
      requests.push(request);
      if (request.url.endsWith("/images")) return json({ data: [], meta });
      if (request.url.endsWith("/resources")) return json({ data: [], meta });
      if (request.method === "POST") return new Response(JSON.stringify({ error: { code: "MARKETPLACE_STATUS_CHANGE_NOT_ALLOWED" }, meta }), { status: 409, headers: { "Content-Type": "application/json" } });
      return json({ data: { venue: venue("INACTIVE"), venueAccount: null }, meta });
    });
    render(<MemoryRouter initialEntries={["/venues/v-1"]}><Routes><Route path="/venues/:venueId" element={<VenueDetail api={api} />} /></Routes></MemoryRouter>);
    await screen.findByRole("button", { name: "Activate" });
    fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "  Reviewed by admin  " } });
    fireEvent.click(screen.getByRole("button", { name: "Activate" }));
    expect((await screen.findByRole("alert")).textContent).toBe("This marketplace status change is not allowed.");
    const request = requests.find((item) => item.method === "POST")!;
    expect(request.url).toBe("http://api/api/v1/admin/venues/v-1/activate");
    expect(JSON.parse(await request.text())).toEqual({ reason: "Reviewed by admin" });
    expect(request.headers.get("Idempotency-Key")).toBeNull();
  });
});
