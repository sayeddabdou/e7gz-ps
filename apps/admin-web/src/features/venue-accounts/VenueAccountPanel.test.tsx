import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { createAdminApiClient } from "../../api";
import { VenueAccountPanel } from "./VenueAccountPanel";

afterEach(cleanup);
describe("VenueAccountPanel", () => {
  it("normalizes and sends exactly four Owner PIN digits while keeping the login password separate", async () => {
    let request: Request | undefined;
    const api = createAdminApiClient({ apiBaseUrl: "http://api" }, () => "token", async (next) => { request = next; return new Response(JSON.stringify({ data: { id: "va-1", venueId: "v-1", loginIdentifier: "arena", accountStatus: "ENABLED" }, meta: { correlationId: "c", serverTime: "2026-10-03T00:00:00Z" } }), { status: 201, headers: { "Content-Type": "application/json" } }); });
    render(<VenueAccountPanel api={api} venueId="v-1" initial={null} />);
    expect(screen.getByLabelText("Login password").getAttribute("autocomplete")).toBe("new-password");
    expect(screen.getByLabelText("Initial 4-digit Owner PIN").getAttribute("autocomplete")).toBe("new-password");
    fireEvent.change(screen.getByLabelText("Login identifier"), { target: { value: "arena" } });
    fireEvent.change(screen.getByLabelText("Login password"), { target: { value: "Password1" } });
    fireEvent.change(screen.getByLabelText("Initial 4-digit Owner PIN"), { target: { value: "١٢٣٤" } });
    fireEvent.change(screen.getByLabelText("Dashboard language"), { target: { value: "ar" } });
    fireEvent.click(screen.getByRole("button", { name: "Create venue account" }));
    await waitFor(() => expect(request).toBeTruthy());
    expect(JSON.parse(await request!.text())).toEqual({ loginIdentifier: "arena", password: "Password1", initialOwnerPassword: "1234", languagePreference: "ar" });
    expect(window.localStorage.length).toBe(0);
  });

  it("sends exactly four digits to reset-owner-password", async () => {
    let request: Request | undefined;
    const api = createAdminApiClient({ apiBaseUrl: "http://api" }, () => "token", async (next) => { request = next; return new Response(undefined, { status: 204 }); });
    render(<VenueAccountPanel api={api} venueId="v-1" initial={{ id: "va-1", venueId: "v-1", loginIdentifier: "arena", accountStatus: "ENABLED" }} />);
    fireEvent.click(screen.getByRole("button", { name: "Reset Owner PIN" }));
    fireEvent.change(screen.getByLabelText("New 4-digit Owner PIN"), { target: { value: "۴۵۶۷" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(request).toBeTruthy());
    expect(request!.url).toContain("/reset-owner-password");
    expect(JSON.parse(await request!.text())).toEqual({ newPassword: "4567" });
  });

  it("disables, enables, and resets the login password through the documented endpoints", async () => {
    const requests: Request[] = [];
    let status: "ENABLED" | "DISABLED" = "ENABLED";
    const api = createAdminApiClient({ apiBaseUrl: "http://api" }, () => "token", async (request) => {
      requests.push(request);
      if (request.url.endsWith("/disable")) status = "DISABLED";
      if (request.url.endsWith("/enable")) status = "ENABLED";
      if (request.url.includes("/reset-login-password")) return new Response(undefined, { status: 204 });
      return new Response(JSON.stringify({ data: { id: "va-1", venueId: "v-1", loginIdentifier: "arena", accountStatus: status }, meta: { correlationId: "c", serverTime: "2026-10-03T00:00:00Z" } }), { status: 200, headers: { "Content-Type": "application/json" } });
    });
    render(<VenueAccountPanel api={api} venueId="v-1" initial={{ id: "va-1", venueId: "v-1", loginIdentifier: "arena", accountStatus: "ENABLED" }} />);
    fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "Owner request" } });
    fireEvent.click(screen.getByRole("button", { name: "Disable account" }));
    await screen.findByRole("button", { name: "Enable account" });
    expect(JSON.parse(await requests[0]!.text())).toEqual({ reason: "Owner request" });
    fireEvent.click(screen.getByRole("button", { name: "Enable account" }));
    await screen.findByRole("button", { name: "Disable account" });
    fireEvent.click(screen.getByRole("button", { name: "Reset login password" }));
    fireEvent.change(screen.getByLabelText("New login password"), { target: { value: "NewPassword1" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(requests).toHaveLength(3));
    expect(requests.map((request) => new URL(request.url).pathname)).toEqual([
      "/api/v1/admin/venue-accounts/va-1/disable",
      "/api/v1/admin/venue-accounts/va-1/enable",
      "/api/v1/admin/venue-accounts/va-1/reset-login-password",
    ]);
    expect(JSON.parse(await requests[2]!.text())).toEqual({ newPassword: "NewPassword1" });
  });

  it("maps account validation fields and network failures to user-facing messages", async () => {
    let calls = 0;
    const api = createAdminApiClient({ apiBaseUrl: "http://api" }, () => "token", async () => {
      calls += 1;
      if (calls === 1) return new Response(JSON.stringify({ error: { code: "VALIDATION_FAILED", field: "password" }, meta: { correlationId: "c", serverTime: "2026-10-03T00:00:00Z" } }), { status: 400, headers: { "Content-Type": "application/json" } });
      throw new TypeError("fetch failed");
    });
    const { rerender } = render(<VenueAccountPanel api={api} venueId="v-1" initial={null} />);
    fireEvent.change(screen.getByLabelText("Login identifier"), { target: { value: "arena" } });
    fireEvent.change(screen.getByLabelText("Login password"), { target: { value: "Password1" } });
    fireEvent.change(screen.getByLabelText("Initial 4-digit Owner PIN"), { target: { value: "1234" } });
    fireEvent.click(screen.getByRole("button", { name: "Create venue account" }));
    expect((await screen.findByRole("alert")).textContent).toBe("Use at least 8 characters with a letter and a number.");

    rerender(<VenueAccountPanel api={api} venueId="v-2" initial={null} />);
    fireEvent.click(screen.getByRole("button", { name: "Create venue account" }));
    expect((await screen.findByRole("alert")).textContent).toBe("The server could not be reached.");
  });
});
