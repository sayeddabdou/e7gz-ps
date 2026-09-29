import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { App } from "./App";

const readyConfig = {
  ok: true as const,
  config: { apiBaseUrl: "http://127.0.0.1:4010", accessToken: "mock-access-token" },
};

beforeEach(() => {
  window.localStorage.clear();
  window.history.replaceState({}, "", "/");
});

afterEach(cleanup);

describe("Venue Dashboard shell", () => {
  it("guards protected routes until an access token is available", () => {
    render(
      <App
        config={{ ok: true, config: { apiBaseUrl: "http://127.0.0.1:4010", accessToken: undefined } }}
      />,
    );

    expect(screen.getByRole("heading", { name: "Sign in required" })).toBeTruthy();
    expect(screen.queryByRole("navigation")).toBeNull();
  });

  it("prefers the venue account language, mirrors the shell in Arabic, and keeps logical styles", () => {
    window.localStorage.setItem("e7gz.venue.language", "en");
    render(<App config={readyConfig} preferredLanguage="ar" />);

    const shell = screen.getByRole("main");
    const sidebar = screen.getByTestId("venue-sidebar");
    expect(shell.getAttribute("dir")).toBe("rtl");
    expect(document.documentElement.dir).toBe("rtl");
    expect(sidebar.style.borderInlineEnd).not.toBe("");
    expect(sidebar.style.textAlign).toBe("start");
    expect(sidebar.getAttribute("style")).not.toMatch(/(?:^|-)left|(?:^|-)right/u);
    expect(screen.getByRole("navigation", { name: "التنقل في لوحة التحكم" })).toBeTruthy();
  });

  it("uses the stored language, switches direction, and persists the choice", () => {
    window.localStorage.setItem("e7gz.venue.language", "ar");
    render(<App config={readyConfig} />);

    expect(screen.getByRole("main").getAttribute("dir")).toBe("rtl");
    fireEvent.click(screen.getByRole("button", { name: "English" }));
    expect(screen.getByRole("main").getAttribute("dir")).toBe("ltr");
    expect(window.localStorage.getItem("e7gz.venue.language")).toBe("en");
  });

  it("routes between empty Dashboard modules", () => {
    render(<App config={readyConfig} />);

    const navigation = screen.getByRole("navigation", { name: "Dashboard navigation" });
    fireEvent.click(within(navigation).getByRole("link", { name: "Schedule" }));

    expect(screen.getByRole("heading", { name: "Schedule", level: 2 })).toBeTruthy();
    expect(window.location.pathname).toBe("/schedule");
  });
});
