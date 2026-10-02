import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { VenueLoginScreen } from "./LoginScreen";
import { authErrorMessage } from "./errorMessages";

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

describe("venue authentication", () => {
  it("logs in with the generated venue contract", async () => {
    const onAuthenticated = vi.fn();
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ data: { accessToken: "access" }, meta: { correlationId: "c-1", serverTime: "2026-10-01T10:00:00Z" } }), { status: 200, headers: { "Content-Type": "application/json" } }));
    vi.stubGlobal("fetch", fetch);
    render(<VenueLoginScreen apiBaseUrl="http://127.0.0.1:4010" language="en" onAuthenticated={onAuthenticated} />);
    fireEvent.change(screen.getByLabelText("Login identifier"), { target: { value: "levelup.nasrcity" } });
    fireEvent.change(screen.getByLabelText("Password"), { target: { value: "password1" } });
    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
    await waitFor(() => expect(onAuthenticated).toHaveBeenCalledWith("access"));
    expect(fetch).toHaveBeenCalledWith("http://127.0.0.1:4010/api/v1/venue/auth/login", expect.objectContaining({ credentials: "include", method: "POST" }));
  });

  it("renders the login form in Arabic RTL", () => {
    render(<VenueLoginScreen apiBaseUrl="http://127.0.0.1:4010" language="ar" onAuthenticated={vi.fn()} />);
    expect(screen.getByRole("main").getAttribute("dir")).toBe("rtl");
    expect(screen.getByRole("heading", { name: "تسجيل دخول المكان" })).toBeTruthy();
  });

  it("maps API codes rather than server prose", () => {
    expect(authErrorMessage("en", "UNAUTHENTICATED")).toBe("The login identifier or password is incorrect.");
  });
});
