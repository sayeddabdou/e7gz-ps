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
    render(<AdminResourceManager api={api} venueId="v-1" />);
    await screen.findByText("No rooms yet.");
    fireEvent.click(screen.getByRole("button", { name: "Add room" }));
    await waitFor(() => expect(requests).toHaveLength(2));
    expect(JSON.parse(await requests[1]!.text())).toEqual({ activity: "PLAYSTATION", consoleType: "PS5" });
    expect(await screen.findByText(/Room 1/)).toBeTruthy();
  });

  it("retries a failed resource load", async () => {
    let calls = 0;
    const api = createAdminApiClient({ apiBaseUrl: "http://api" }, () => "token", async () => {
      calls += 1;
      if (calls === 1) throw new TypeError("fetch failed");
      return new Response(JSON.stringify({ data: [], meta: { correlationId: "c", serverTime: "2026-10-03T00:00:00Z" } }), { status: 200, headers: { "Content-Type": "application/json" } });
    });
    render(<AdminResourceManager api={api} venueId="v-1" />);
    expect((await screen.findByRole("alert")).textContent).toContain("server could not be reached");
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    expect(await screen.findByText("No rooms yet.")).toBeTruthy();
    expect(calls).toBe(2);
  });

  it("omits consoleType when creating a non-PlayStation room", async () => {
    const requests: Request[] = [];
    const resource = { id: "r-1", venueId: "v-1", name: "Room 1", activity: "BILLIARDS", consoleType: null, baseState: "ACTIVE", operationalRestriction: "NONE" };
    const api = createAdminApiClient({ apiBaseUrl: "http://api" }, () => "token", async (request) => { requests.push(request); return new Response(JSON.stringify({ data: request.method === "GET" ? [] : resource, meta: { correlationId: "c", serverTime: "2026-10-03T00:00:00Z" } }), { status: request.method === "GET" ? 200 : 201, headers: { "Content-Type": "application/json" } }); });
    render(<AdminResourceManager api={api} venueId="v-1" />);
    await screen.findByText("No rooms yet.");
    fireEvent.change(screen.getByLabelText("Activity"), { target: { value: "BILLIARDS" } });
    expect(screen.queryByLabelText("Console")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Add room" }));
    await waitFor(() => expect(requests).toHaveLength(2));
    expect(JSON.parse(await requests[1]!.text())).toEqual({ activity: "BILLIARDS" });
  });
});
