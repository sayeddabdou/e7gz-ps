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

const expectedModules = [
  "Dashboard",
  "Areas",
  "Venues",
  "Venue Onboarding",
  "Venue Accounts",
  "Activities",
  "Users",
  "Verified Players",
  "Reviews Moderation",
  "Global Bookings",
  "No-show Records",
  "Tournaments",
  "Wallet",
  "Points",
  "Referrals",
  "Platform Fees",
  "Global Rules",
  "Notifications",
  "Global Search",
  "Settings",
];

beforeEach(() => {
  window.history.replaceState({}, "", "/");
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(sessionResponse("memory-only-access")));
});
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

describe("Platform Admin shell", () => {
  it.each([401, 403])("guards protected routes when session restoration returns %s", async (status) => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(undefined, { status })));
    render(<App config={readyConfig} />);

    expect(screen.getByRole("status").textContent).toBe("Restoring your session…");
    await waitFor(() => expect(screen.getByRole("heading", { name: "Admin sign in" })).toBeTruthy());
    expect(screen.queryByRole("navigation")).toBeNull();
  });

  it("renders exactly the 20 frozen Admin modules", async () => {
    render(<App config={readyConfig} />);

    await waitFor(() => expect(screen.getByRole("navigation", { name: "Admin navigation" })).toBeTruthy());
    const links = within(screen.getByRole("navigation", { name: "Admin navigation" })).getAllByRole("link");
    expect(links.map((link) => link.textContent)).toEqual(expectedModules);
  });

  it("routes to an empty module page", async () => {
    render(<App config={readyConfig} />);

    await waitFor(() => expect(screen.getByRole("navigation", { name: "Admin navigation" })).toBeTruthy());
    fireEvent.click(screen.getByRole("link", { name: "Users" }));
    expect(screen.getByRole("heading", { name: "Users", level: 2 })).toBeTruthy();
    expect(window.location.pathname).toBe("/users");
  });

  it("keeps Venue Accounts as a placeholder without calling an unsupported list endpoint", async () => {
    const fetch = vi.mocked(globalThis.fetch);
    render(<App config={readyConfig} />);

    await waitFor(() => expect(screen.getByRole("navigation", { name: "Admin navigation" })).toBeTruthy());
    fireEvent.click(screen.getByRole("link", { name: "Venue Accounts" }));
    expect(screen.getByRole("heading", { name: "Venue Accounts", level: 2 })).toBeTruthy();
    expect(window.location.pathname).toBe("/venue-accounts");
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(fetch.mock.calls.some(([input]) => String(input).endsWith("/api/v1/admin/venue-accounts"))).toBe(false);
  });

  it("restores and ends the HttpOnly-cookie session without browser token storage", async () => {
    const fetch = vi.mocked(globalThis.fetch);
    render(<App config={readyConfig} />);

    await waitFor(() => expect(screen.getByRole("button", { name: "Log out" })).toBeTruthy());
    expect(fetch).toHaveBeenNthCalledWith(1, "http://127.0.0.1:4010/api/v1/admin/auth/refresh", expect.objectContaining({
      credentials: "include",
      headers: { "X-Requested-With": "e7gz" },
      method: "POST",
    }));
    expect(window.localStorage.getItem("accessToken")).toBeNull();
    expect(window.sessionStorage.getItem("accessToken")).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "Log out" }));
    await waitFor(() => expect(screen.getByRole("heading", { name: "Admin sign in" })).toBeTruthy());
    expect(fetch).toHaveBeenNthCalledWith(2, "http://127.0.0.1:4010/api/v1/admin/auth/logout", expect.objectContaining({
      credentials: "include",
      headers: { "X-Requested-With": "e7gz" },
      method: "POST",
    }));
  });
});
