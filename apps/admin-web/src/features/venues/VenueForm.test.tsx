import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { createAdminApiClient } from "../../api";
import { VenueForm } from "./VenueForm";

afterEach(cleanup);
const meta = { correlationId: "c-1", serverTime: "2026-10-03T10:00:00Z" };
const venue = { id: "v-1", name: "Arena", areaId: "a-1", address: "Street", phone: "+201001234567", location: { lat: 30.15, lng: 31.33 }, timezone: "Africa/Cairo", activities: ["PLAYSTATION" as const], marketplaceStatus: "INACTIVE" as const };

describe("VenueForm", () => {
  it("sends the full activity list as replacement and shows the centroid distance warning", async () => {
    const requests: Request[] = [];
    const api = createAdminApiClient({ apiBaseUrl: "http://api" }, () => "token", async (request) => {
      requests.push(request);
      if (request.method === "GET") return new Response(JSON.stringify({ data: [{ id: "a-1", name: "Nasr City", nameAr: null, active: true, centroid: { lat: 30.0561, lng: 31.3301 } }], meta }), { status: 200, headers: { "Content-Type": "application/json" } });
      return new Response(JSON.stringify({ data: { ...venue, activities: ["PLAYSTATION", "BILLIARDS"] }, meta }), { status: 200, headers: { "Content-Type": "application/json" } });
    });
    render(<VenueForm api={api} venue={venue} onSaved={() => undefined} />);
    expect(await screen.findByText(/more than 5 km/)).toBeTruthy();
    fireEvent.click(screen.getByRole("checkbox", { name: "BILLIARDS" }));
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(requests).toHaveLength(2));
    const body = JSON.parse(await requests[1]!.text());
    expect(body.activities).toEqual(["PLAYSTATION", "BILLIARDS"]);
    expect(body.location).toEqual({ lat: 30.15, lng: 31.33 });
  });

  it("moves the selected location from pasted coordinates and sends latitude before longitude", async () => {
    const requests: Request[] = [];
    const api = createAdminApiClient({ apiBaseUrl: "http://api" }, () => "token", async (request) => {
      requests.push(request);
      if (request.method === "GET") return new Response(JSON.stringify({ data: [{ id: "a-1", name: "Nasr City", nameAr: null, active: true, centroid: { lat: 30.0444, lng: 31.2357 } }], meta }), { status: 200, headers: { "Content-Type": "application/json" } });
      return new Response(JSON.stringify({ data: { ...venue, location: { lat: 30.0561, lng: 31.3301 } }, meta }), { status: 201, headers: { "Content-Type": "application/json" } });
    });
    render(<VenueForm api={api} onSaved={() => undefined} />);
    await screen.findByRole("option", { name: "Nasr City" });
    fireEvent.change(screen.getByLabelText("Venue name"), { target: { value: "Arena" } });
    fireEvent.change(screen.getByLabelText("Area"), { target: { value: "a-1" } });
    fireEvent.change(screen.getByLabelText("Address"), { target: { value: "Street" } });
    fireEvent.change(screen.getByLabelText("Phone"), { target: { value: "01001234567" } });
    fireEvent.change(screen.getByLabelText("Paste coordinates or a Google Maps URL"), { target: { value: "https://maps.google.com/?q=30.0561,31.3301" } });
    fireEvent.click(screen.getByRole("button", { name: "Use location" }));
    expect(screen.getByText(/Latitude: 30.0561; Longitude: 31.3301/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(requests).toHaveLength(2));
    expect(JSON.parse(await requests[1]!.text()).location).toEqual({ lat: 30.0561, lng: 31.3301 });
  });

  it("rejects an unreadable location without moving the existing pin", async () => {
    const api = createAdminApiClient({ apiBaseUrl: "http://api" }, () => "token", async () => new Response(JSON.stringify({ data: [{ id: "a-1", name: "Nasr City", nameAr: null, active: true, centroid: null }], meta }), { status: 200, headers: { "Content-Type": "application/json" } }));
    render(<VenueForm api={api} venue={venue} onSaved={() => undefined} />);
    await screen.findByRole("option", { name: "Nasr City" });
    expect(screen.getByText(/Latitude: 30.15; Longitude: 31.33/)).toBeTruthy();
    fireEvent.change(screen.getByLabelText("Paste coordinates or a Google Maps URL"), { target: { value: "https://maps.app.goo.gl/short-link" } });
    fireEvent.click(screen.getByRole("button", { name: "Use location" }));
    expect(screen.getByRole("alert").textContent).toContain("Enter valid latitude and longitude");
    expect(screen.getByText(/Latitude: 30.15; Longitude: 31.33/)).toBeTruthy();
  });

  it("shows an area loading failure and retries before allowing submission", async () => {
    let calls = 0;
    const api = createAdminApiClient({ apiBaseUrl: "http://api" }, () => "token", async () => {
      calls += 1;
      return calls === 1
        ? new Response(JSON.stringify({ error: { code: "UNEXPECTED_ERROR" }, meta }), { status: 500, headers: { "Content-Type": "application/json" } })
        : new Response(JSON.stringify({ data: [{ id: "a-1", name: "Nasr City", nameAr: null, active: true, centroid: null }], meta }), { status: 200, headers: { "Content-Type": "application/json" } });
    });
    render(<VenueForm api={api} onSaved={() => undefined} />);
    expect((await screen.findByRole("alert")).textContent).toContain("server could not be reached");
    expect(screen.getByRole("button", { name: "Save" }).hasAttribute("disabled")).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    expect(await screen.findByRole("option", { name: "Nasr City" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "Save" }).hasAttribute("disabled")).toBe(false);
  });

  it("highlights the contract field when venue validation fails", async () => {
    const api = createAdminApiClient({ apiBaseUrl: "http://api" }, () => "token", async (request) => request.method === "GET"
      ? new Response(JSON.stringify({ data: [{ id: "a-1", name: "Nasr City", nameAr: null, active: true, centroid: null }], meta }), { status: 200, headers: { "Content-Type": "application/json" } })
      : new Response(JSON.stringify({ error: { code: "VALIDATION_FAILED", field: "phone" }, meta }), { status: 400, headers: { "Content-Type": "application/json" } }));
    render(<VenueForm api={api} venue={venue} onSaved={() => undefined} />);
    await screen.findByRole("option", { name: "Nasr City" });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    expect((await screen.findByRole("alert")).textContent).toBe("Enter a valid phone number.");
    expect(screen.getByLabelText("Phone").getAttribute("aria-invalid")).toBe("true");
  });
});
