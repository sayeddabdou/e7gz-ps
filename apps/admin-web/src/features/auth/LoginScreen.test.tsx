import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AdminLoginScreen } from "./LoginScreen";
import { authErrorMessage } from "./errorMessages";

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

describe("admin authentication", () => {
  it("logs in with the generated admin contract", async () => {
    const onAuthenticated = vi.fn();
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ data: { accessToken: "access" }, meta: { correlationId: "c-1", serverTime: "2026-10-01T10:00:00Z" } }), { status: 200, headers: { "Content-Type": "application/json" } }));
    vi.stubGlobal("fetch", fetch);
    render(<AdminLoginScreen apiBaseUrl="http://127.0.0.1:4010" onAuthenticated={onAuthenticated} />);
    fireEvent.change(screen.getByLabelText("Email"), { target: { value: "admin@e7gz.example" } });
    fireEvent.change(screen.getByLabelText("Password"), { target: { value: "password1" } });
    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
    await waitFor(() => expect(onAuthenticated).toHaveBeenCalledWith("access"));
    expect(fetch).toHaveBeenCalledWith("http://127.0.0.1:4010/api/v1/admin/auth/login", expect.objectContaining({ credentials: "include", method: "POST" }));
  });

  it("maps API codes rather than server prose", () => {
    expect(authErrorMessage("UNAUTHENTICATED")).toBe("The email or password is incorrect.");
  });
});
