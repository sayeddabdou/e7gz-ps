import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { App } from "./App";

const readyConfig = {
  ok: true as const,
  config: { apiBaseUrl: "http://127.0.0.1:4010", accessToken: "mock-access-token" },
};

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

beforeEach(() => window.history.replaceState({}, "", "/"));
afterEach(cleanup);

describe("Platform Admin shell", () => {
  it("guards protected routes until an access token is available", () => {
    render(
      <App
        config={{ ok: true, config: { apiBaseUrl: "http://127.0.0.1:4010", accessToken: undefined } }}
      />,
    );

    expect(screen.getByRole("heading", { name: "Sign in required" })).toBeTruthy();
    expect(screen.queryByRole("navigation")).toBeNull();
  });

  it("renders exactly the 20 frozen Admin modules", () => {
    render(<App config={readyConfig} />);

    const links = within(screen.getByRole("navigation", { name: "Admin navigation" })).getAllByRole("link");
    expect(links.map((link) => link.textContent)).toEqual(expectedModules);
  });

  it("routes to an empty module page", () => {
    render(<App config={readyConfig} />);

    fireEvent.click(screen.getByRole("link", { name: "Venue Onboarding" }));
    expect(screen.getByRole("heading", { name: "Venue Onboarding", level: 2 })).toBeTruthy();
    expect(window.location.pathname).toBe("/venue-onboarding");
  });
});
