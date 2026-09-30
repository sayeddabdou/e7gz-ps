import { fireEvent, render, screen, waitFor } from "@testing-library/react-native";
import { App } from "./App";

const config = { ok: true as const, config: { apiBaseUrl: "http://127.0.0.1:4010" } };
const meta = { correlationId: "c-1", serverTime: "2026-10-12T17:04:11Z" };

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
    render(<App config={config} />);

    expect(screen.getByText("Home")).toBeTruthy();
    expect(screen.getByText("Tournaments")).toBeTruthy();
    expect(screen.getByText("Bookings")).toBeTruthy();
    expect(screen.getByText("Profile")).toBeTruthy();
    await waitFor(() => expect(globalThis.fetch).toHaveBeenCalled());
  });

  it("navigates between empty feature screens", async () => {
    render(<App config={config} />);

    await waitFor(() => expect(globalThis.fetch).toHaveBeenCalled());

    fireEvent.press(screen.getByText("Tournaments"));
    expect(screen.getByRole("header", { name: "Tournaments" })).toBeTruthy();

    fireEvent.press(screen.getByText("Profile"));
    expect(screen.getByRole("header", { name: "Profile" })).toBeTruthy();
  });
});
