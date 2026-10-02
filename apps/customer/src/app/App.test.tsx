import { fireEvent, render, screen, waitFor } from "@testing-library/react-native";
import { App } from "./App";
import type { TokenStore } from "../auth/tokenStore";

const config = { ok: true as const, config: { apiBaseUrl: "http://127.0.0.1:4010" } };
const meta = { correlationId: "c-1", serverTime: "2026-10-12T17:04:11Z" };
const tokenStore: TokenStore = {
  read: jest.fn().mockResolvedValue({ accessToken: "access", refreshToken: "refresh" }),
  write: jest.fn().mockResolvedValue(undefined),
  clear: jest.fn().mockResolvedValue(undefined),
};

beforeEach(() => {
  jest.spyOn(globalThis, "fetch").mockResolvedValue(
    new Response(JSON.stringify({ data: [], meta }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    }),
  );
});

afterEach(() => jest.restoreAllMocks());

describe("Customer app shell", () => {
  it("shows the four prototype tabs", async () => {
    render(<App config={config} tokenStore={tokenStore} />);

    await waitFor(() => expect(screen.getByText("Home")).toBeTruthy());
    expect(screen.getByText("Tournaments")).toBeTruthy();
    expect(screen.getByText("Bookings")).toBeTruthy();
    expect(screen.getByText("Profile")).toBeTruthy();
    expect(screen.getAllByTestId("tab-icon-home").length).toBeGreaterThan(0);
    expect(screen.getAllByTestId("tab-icon-tournaments").length).toBeGreaterThan(0);
    expect(screen.getAllByTestId("tab-icon-bookings").length).toBeGreaterThan(0);
    expect(screen.getAllByTestId("tab-icon-profile").length).toBeGreaterThan(0);
    await waitFor(() => expect(globalThis.fetch).toHaveBeenCalled());
  }, 10_000);

  it("navigates between empty feature screens", async () => {
    render(<App config={config} tokenStore={tokenStore} />);

    await waitFor(() => expect(screen.getByText("Home")).toBeTruthy());
    await waitFor(() => expect(globalThis.fetch).toHaveBeenCalled());

    fireEvent.press(screen.getByText("Tournaments"));
    expect(screen.getByRole("header", { name: "Tournaments" })).toBeTruthy();

    fireEvent.press(screen.getByText("Profile"));
    expect(screen.getByRole("header", { name: "Profile" })).toBeTruthy();
  });
});
