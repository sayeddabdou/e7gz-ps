import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { createAdminApiClient } from "../../api";
import { AdminResourceManager } from "./AdminResourceManager";

afterEach(cleanup);
describe("AdminResourceManager", () => {
  it("creates a server-named PlayStation room with activity and console only", async () => {
    const requests: Request[] = [];
    const resource = { id: "r-1", venueId: "v-1", name: "Room 1", activity: "PLAYSTATION", consoleType: "PS5", baseState: "ACTIVE", operationalRestriction: "NONE" };
    const api = createAdminApiClient({ apiBaseUrl: "http://api" }, () => "token", async (request) => { requests.push(request); return new Response(JSON.stringify({ data: request.method === "GET" ? [] : resource, meta: { correlationId: "c", serverTime: "2026-10-03T00:00:00Z" } }), { status: request.method === "GET" ? 200 : 201, headers: { "Content-Type": "application/json" } }); });
    render(<AdminResourceManager activities={["PLAYSTATION"]} api={api} venueId="v-1" />);
    await screen.findByText("No rooms yet.");
    fireEvent.click(screen.getByRole("button", { name: "Add room" }));
    await waitFor(() => expect(requests).toHaveLength(2));
    expect(JSON.parse(await requests[1]!.text())).toEqual({ activity: "PLAYSTATION", consoleType: "PS5" });
    expect(requests[1]!.headers.get("Idempotency-Key")).toBeNull();
    expect(await screen.findByText(/Room 1/)).toBeTruthy();
    expect(screen.getByText("Base state: Active")).toBeTruthy();
    expect(screen.getByText("Operational restriction: None")).toBeTruthy();
  });

  it("retries a failed resource load", async () => {
    let calls = 0;
    const api = createAdminApiClient({ apiBaseUrl: "http://api" }, () => "token", async () => {
      calls += 1;
      if (calls === 1) throw new TypeError("fetch failed");
      return new Response(JSON.stringify({ data: [], meta: { correlationId: "c", serverTime: "2026-10-03T00:00:00Z" } }), { status: 200, headers: { "Content-Type": "application/json" } });
    });
    render(<AdminResourceManager activities={["PLAYSTATION"]} api={api} venueId="v-1" />);
    expect((await screen.findByRole("alert")).textContent).toContain("server could not be reached");
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    expect(await screen.findByText("No rooms yet.")).toBeTruthy();
    expect(calls).toBe(2);
  });

  it("omits consoleType when creating a non-PlayStation room", async () => {
    const requests: Request[] = [];
    const resource = { id: "r-1", venueId: "v-1", name: "Room 1", activity: "BILLIARDS", consoleType: null, baseState: "ACTIVE", operationalRestriction: "NONE" };
    const api = createAdminApiClient({ apiBaseUrl: "http://api" }, () => "token", async (request) => { requests.push(request); return new Response(JSON.stringify({ data: request.method === "GET" ? [] : resource, meta: { correlationId: "c", serverTime: "2026-10-03T00:00:00Z" } }), { status: request.method === "GET" ? 200 : 201, headers: { "Content-Type": "application/json" } }); });
    render(<AdminResourceManager activities={["PLAYSTATION", "BILLIARDS"]} api={api} venueId="v-1" />);
    await screen.findByText("No rooms yet.");
    fireEvent.change(screen.getByLabelText("Activity"), { target: { value: "BILLIARDS" } });
    expect(screen.queryByLabelText("Console")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Add room" }));
    await waitFor(() => expect(requests).toHaveLength(2));
    expect(JSON.parse(await requests[1]!.text())).toEqual({ activity: "BILLIARDS" });
  });

  it("offers only the activities configured for the venue", async () => {
    const api = createAdminApiClient({ apiBaseUrl: "http://api" }, () => "token", async () => new Response(JSON.stringify({ data: [], meta: { correlationId: "c", serverTime: "2026-10-03T00:00:00Z" } }), { status: 200, headers: { "Content-Type": "application/json" } }));
    render(<AdminResourceManager activities={["BILLIARDS"]} api={api} venueId="v-1" />);

    await screen.findByText("No rooms yet.");
    expect(screen.getByRole("option", { name: "Billiards" })).toBeTruthy();
    expect(screen.queryByRole("option", { name: "PlayStation" })).toBeNull();
    expect(screen.queryByRole("option", { name: "Ping pong" })).toBeNull();
  });

  it("maps a resource validation error using the response field", async () => {
    const api = createAdminApiClient({ apiBaseUrl: "http://api" }, () => "token", async (request) => request.method === "GET"
      ? new Response(JSON.stringify({ data: [], meta: { correlationId: "c", serverTime: "2026-10-03T00:00:00Z" } }), { status: 200, headers: { "Content-Type": "application/json" } })
      : new Response(JSON.stringify({ error: { code: "VALIDATION_FAILED", field: "activity" }, meta: { correlationId: "c", serverTime: "2026-10-03T00:00:00Z" } }), { status: 400, headers: { "Content-Type": "application/json" } }));
    render(<AdminResourceManager activities={["PLAYSTATION"]} api={api} venueId="v-1" />);

    await screen.findByText("No rooms yet.");
    fireEvent.click(screen.getByRole("button", { name: "Add room" }));
    expect((await screen.findByRole("alert")).textContent).toBe("Choose an activity offered by this venue.");
  });
});
