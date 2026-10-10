import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";

const readyConfig = {
  ok: true as const,
  config: { apiBaseUrl: "http://127.0.0.1:4010" },
};

const sessionResponse = (accessToken: string) => new Response(
  JSON.stringify({ data: { accessToken }, meta: { correlationId: "c-1", serverTime: "2026-10-02T10:00:00Z" } }),
  { status: 200, headers: { "Content-Type": "application/json" } },
);
const settingsResponse = (languagePreference: "en" | "ar") => new Response(
  JSON.stringify({
    data: {
      venueId: "v-1",
      name: "Arena",
      areaId: "a-1",
      areaName: "Nasr City",
      address: "Street",
      phone: "+201001234567",
      location: { lat: 30.05, lng: 31.33 },
      timezone: "Africa/Cairo",
      activities: ["PLAYSTATION"],
      marketplaceStatus: "ACTIVE",
      languagePreference,
    },
    meta: { correlationId: "c-1", serverTime: "2026-10-02T10:00:00Z" },
  }),
  { status: 200, headers: { "Content-Type": "application/json" } },
);

beforeEach(() => {
  window.localStorage.clear();
  window.history.replaceState({}, "", "/");
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(sessionResponse("memory-only-access")));
});

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

describe("Venue Dashboard shell", () => {
  it("renders the reference dashboard shell without replacing existing controls", async () => {
    render(<App config={readyConfig} />);

    await waitFor(() => expect(screen.getByTestId("venue-sidebar")).toBeTruthy());
    expect(screen.getByTestId("venue-shell").className).toContain("venue-dashboard-shell");
    expect(screen.getByTestId("venue-topbar").className).toContain("venue-topbar");
    expect(screen.getByTestId("venue-brand-mark").textContent).toBe("E7");
    expect(screen.getByRole("navigation", { name: "Dashboard navigation" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "العربية" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "Log out" })).toBeTruthy();
  });

  it.each([401, 403])("guards protected routes when session restoration returns %s", async (status) => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(undefined, { status })));
    render(<App config={readyConfig} />);

    expect(screen.getByRole("status").textContent).toBe("Restoring your session…");
    await waitFor(() => expect(screen.getByRole("heading", { name: "Venue sign in" })).toBeTruthy());
    expect(screen.queryByRole("navigation")).toBeNull();
  });

  it("prefers the venue account language, mirrors the shell in Arabic, and keeps logical styles", async () => {
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
      return url.endsWith("/api/v1/venue/settings") ? settingsResponse("ar") : sessionResponse("memory-only-access");
    }));
    render(<App config={readyConfig} />);

    await waitFor(() => expect(screen.getByRole("navigation", { name: "التنقل في لوحة التحكم" })).toBeTruthy());
    const shell = screen.getByRole("main");
    const sidebar = screen.getByTestId("venue-sidebar");
    expect(shell.getAttribute("dir")).toBe("rtl");
    await waitFor(() => expect(document.documentElement.dir).toBe("rtl"));
    expect(sidebar.style.borderInlineEnd).not.toBe("");
    expect(sidebar.style.textAlign).toBe("start");
    expect(sidebar.getAttribute("style")).not.toMatch(/(?:^|-)left|(?:^|-)right/u);
    expect(screen.getByRole("navigation", { name: "التنقل في لوحة التحكم" })).toBeTruthy();
    expect(window.localStorage.getItem("e7gz.venue.language")).toBe("ar");
  });

  it("uses the stored language, switches direction, and persists the choice", async () => {
    window.localStorage.setItem("e7gz.venue.language", "ar");
    render(<App config={readyConfig} />);

    await waitFor(() => expect(screen.getByTestId("venue-sidebar")).toBeTruthy());
    expect(screen.getByRole("main").getAttribute("dir")).toBe("rtl");
    fireEvent.click(screen.getByRole("button", { name: "English" }));
    expect(screen.getByRole("main").getAttribute("dir")).toBe("ltr");
    expect(window.localStorage.getItem("e7gz.venue.language")).toBe("en");
  });

  it("routes between empty Dashboard modules", async () => {
    render(<App config={readyConfig} />);

    await waitFor(() => expect(screen.getByRole("navigation", { name: "Dashboard navigation" })).toBeTruthy());
    const navigation = screen.getByRole("navigation", { name: "Dashboard navigation" });
    fireEvent.click(within(navigation).getByRole("link", { name: "Schedule" }));

    expect(screen.getByRole("heading", { name: "Availability schedule", level: 2 })).toBeTruthy();
    expect(window.location.pathname).toBe("/schedule");
  });

  it("restores and ends the HttpOnly-cookie session without browser token storage", async () => {
    const fetch = vi.mocked(globalThis.fetch);
    render(<App config={readyConfig} />);

    await waitFor(() => expect(screen.getByRole("button", { name: "Log out" })).toBeTruthy());
    await waitFor(() => expect(fetch).toHaveBeenCalledWith("http://127.0.0.1:4010/api/v1/venue/auth/refresh", expect.objectContaining({
      credentials: "include",
      headers: { "X-Requested-With": "e7gz" },
      method: "POST",
    })));
    expect(window.localStorage.getItem("accessToken")).toBeNull();
    expect(window.sessionStorage.getItem("accessToken")).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "Log out" }));
    await waitFor(() => expect(screen.getByRole("heading", { name: "Venue sign in" })).toBeTruthy());
    expect(fetch).toHaveBeenCalledWith("http://127.0.0.1:4010/api/v1/venue/auth/logout", expect.objectContaining({
      credentials: "include",
      headers: { "X-Requested-With": "e7gz" },
      method: "POST",
    }));
  });

  it("revokes the stored browser push subscription before logout", async () => {
    window.localStorage.setItem("e7gz.venue.pushSubscriptionId", "018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e6f");
    const fetch = vi.fn(async (input: RequestInfo | URL) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
      if (url.endsWith("/api/v1/venue/auth/refresh")) return sessionResponse("memory-only-access");
      if (url.endsWith("/api/v1/venue/settings")) return settingsResponse("en");
      if (url.includes("/api/v1/venue/requests/pending")) {
        return new Response(JSON.stringify({ data: [], meta: { correlationId: "c-1", serverTime: "2026-10-02T10:00:00Z" } }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
      }
      return new Response(undefined, { status: 204 });
    });
    vi.stubGlobal("fetch", fetch);
    render(<App config={readyConfig} />);

    await waitFor(() => expect(screen.getByRole("button", { name: "Log out" })).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: "Log out" }));
    await waitFor(() => expect(screen.getByRole("heading", { name: "Venue sign in" })).toBeTruthy());

    const calledUrl = (input: RequestInfo | URL) => input instanceof Request ? input.url : String(input);
    const revokeCall = fetch.mock.calls.findIndex(([input]) => calledUrl(input).includes("/push-subscriptions/018f3c2a-7b1e-7c4d-9e0f-1a2b3c4d5e6f/revoke"));
    const logoutCall = fetch.mock.calls.findIndex(([input]) => calledUrl(input).includes("/auth/logout"));
    expect(revokeCall).toBeGreaterThan(-1);
    expect(logoutCall).toBeGreaterThan(revokeCall);
    expect(window.localStorage.getItem("e7gz.venue.pushSubscriptionId")).toBeNull();
  });
});
