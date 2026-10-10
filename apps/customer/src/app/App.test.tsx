import { act, fireEvent, render, screen, waitFor } from "@testing-library/react-native";
import { App } from "./App";
import type { TokenStore } from "../auth/tokenStore";
import type { PushRegistration, PushSetupState } from "../features/notifications/pushRegistration";

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

    (globalThis.fetch as jest.Mock).mockResolvedValue(
      new Response(JSON.stringify({ data: { items: [], nextCursor: null }, meta }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      }),
    );
    fireEvent.press(screen.getByText("Bookings"));
    expect(await screen.findByRole("header", { name: "My Bookings" })).toBeTruthy();
    expect(await screen.findByText("No upcoming bookings")).toBeTruthy();

    fireEvent.press(screen.getByText("Profile"));
    expect(screen.getByRole("header", { name: "Profile" })).toBeTruthy();
  });

  it("logs out even when push-device revocation fails offline", async () => {
    const logout = jest.fn().mockResolvedValue(undefined);
    const pushRegistration: PushRegistration = {
      start: jest.fn().mockResolvedValue("registered"),
      revoke: jest.fn().mockRejectedValue(new Error("offline")),
      stop: jest.fn(),
    };
    render(
      <App
        config={config}
        tokenStore={tokenStore}
        authApi={{ logout, refresh: jest.fn() } as never}
        pushRegistrationOverride={pushRegistration}
      />,
    );

    fireEvent.press(await screen.findByText("Profile"));
    fireEvent.press(screen.getByText("Log out"));

    await waitFor(() => expect(logout).toHaveBeenCalledTimes(1));
    expect(pushRegistration.revoke).toHaveBeenCalledTimes(1);
  });

  it("keeps the inbox available and explains when push permission is denied", async () => {
    const pushRegistration: PushRegistration = {
      start: jest.fn().mockResolvedValue("denied"),
      revoke: jest.fn().mockResolvedValue(undefined),
      stop: jest.fn(),
    };
    render(<App config={config} tokenStore={tokenStore} pushRegistrationOverride={pushRegistration} />);

    expect(await screen.findByText("Push notifications are turned off. You can still read updates here.")).toBeTruthy();
    expect(screen.getByTestId("push-setup-notice")).toBeTruthy();
    expect(screen.getByText("Bookings")).toBeTruthy();
  });

  it("clears a failed-push banner when a retry succeeds", async () => {
    let reportState: ((state: PushSetupState) => void) | undefined;
    const pushRegistration: PushRegistration = {
      start: jest.fn(async (_onOpen, onState) => {
        reportState = onState;
        return "failed";
      }),
      revoke: jest.fn().mockResolvedValue(undefined),
      stop: jest.fn(),
    };
    render(<App config={config} tokenStore={tokenStore} pushRegistrationOverride={pushRegistration} />);

    expect(await screen.findByText("Push notifications could not be set up. You can still read updates here.")).toBeTruthy();
    act(() => reportState?.("registered"));

    await waitFor(() => expect(screen.queryByTestId("push-setup-notice")).toBeNull());
  });
});
