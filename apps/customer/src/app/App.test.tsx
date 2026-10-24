import { act, fireEvent, render, screen, waitFor } from "@testing-library/react-native";
import { App } from "./App";
import type { TokenStore } from "../auth/tokenStore";
import type { PushRegistration, PushSetupState } from "../features/notifications/pushRegistration";
import type { CustomerProfileApi } from "../features/profile";

const config = { ok: true as const, config: { apiBaseUrl: "http://127.0.0.1:4010" } };
const meta = { correlationId: "c-1", serverTime: "2026-10-12T17:04:11Z" };
const tokenStore: TokenStore = {
  read: jest.fn().mockResolvedValue({ accessToken: "access", refreshToken: "refresh" }),
  write: jest.fn().mockResolvedValue(undefined),
  clear: jest.fn().mockResolvedValue(undefined),
};
const profile = {
  userId: "3f6c1d2e-8a4b-4c5d-9e6f-7a8b9c0d1e2f",
  fullName: "Omar Hassan",
  username: "omar_h",
  phone: "+201001234567",
  phoneVerified: true,
  email: "omar@example.com",
  emailVerified: false,
  usernameChangeAvailableAt: null,
  joinedAt: "2026-10-01T09:30:00Z",
};
const profileApi = {
  getProfile: jest.fn().mockResolvedValue({ ok: true, value: profile, serverTime: meta.serverTime }),
} as unknown as CustomerProfileApi;

beforeEach(() => {
  jest.spyOn(globalThis, "fetch").mockImplementation(async (request) => {
    const url = request instanceof Request ? request.url : String(request);
    const data = url.endsWith("/api/v1/customer/profile") ? profile : [];
    return new Response(JSON.stringify({ data, meta }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    });
  });
});

afterEach(() => jest.restoreAllMocks());

describe("Customer app shell", () => {
  it("shows the four prototype tabs", async () => {
    render(<App config={config} tokenStore={tokenStore} profileApi={profileApi} />);

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
    render(<App config={config} tokenStore={tokenStore} profileApi={profileApi} />);

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
    expect(await screen.findByTestId("profile-name")).toBeTruthy();
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
        profileApi={profileApi}
        pushRegistrationOverride={pushRegistration}
      />,
    );

    fireEvent.press(await screen.findByText("Profile"));
    expect(await screen.findByTestId("profile-name")).toBeTruthy();
    fireEvent.press(screen.getByText("Log out"));

    await waitFor(() => expect(logout).toHaveBeenCalledTimes(1));
    expect(pushRegistration.revoke).toHaveBeenCalledTimes(1);
  });

  it("completes Forgot password with a local sign-out and confirmation, without calling logout", async () => {
    const signedOutStore: TokenStore = {
      read: jest.fn().mockResolvedValue(null),
      write: jest.fn().mockResolvedValue(undefined),
      clear: jest.fn().mockResolvedValue(undefined),
    };
    const logout = jest.fn();
    const pushRegistration: PushRegistration = {
      start: jest.fn(),
      revoke: jest.fn(),
      stop: jest.fn(),
    };
    const resetApi = {
      requestPasswordReset: jest.fn().mockResolvedValue({ ok: true, value: { challengeId: "reset-1" } }),
      completePasswordReset: jest.fn().mockResolvedValue({ ok: true, value: undefined }),
    } as unknown as CustomerProfileApi;
    render(
      <App
        config={config}
        tokenStore={signedOutStore}
        authApi={{ logout, refresh: jest.fn() } as never}
        profileApi={resetApi}
        pushRegistrationOverride={pushRegistration}
      />,
    );

    fireEvent.press(await screen.findByRole("button", { name: "Forgot password?" }));
    fireEvent.changeText(screen.getByLabelText("Phone number"), "+201001234567");
    fireEvent.press(screen.getByRole("button", { name: "Send reset code" }));
    fireEvent.changeText(await screen.findByLabelText("Verification code"), "482913");
    fireEvent.changeText(screen.getByLabelText("New password"), "newPassword1");
    fireEvent.press(screen.getByRole("button", { name: "Reset password" }));

    expect(await screen.findByText("Password reset. Every session has ended. Log in with your new password.")).toBeTruthy();
    expect(screen.getByRole("header", { name: "Welcome back" })).toBeTruthy();
    expect(signedOutStore.clear).toHaveBeenCalledTimes(1);
    expect(pushRegistration.revoke).toHaveBeenCalledTimes(1);
    expect(logout).not.toHaveBeenCalled();
  });

  it("completes a Profile password reset with the same local sign-out and confirmation", async () => {
    const signedInStore: TokenStore = {
      read: jest.fn().mockResolvedValue({ accessToken: "access", refreshToken: "refresh" }),
      write: jest.fn().mockResolvedValue(undefined),
      clear: jest.fn().mockResolvedValue(undefined),
    };
    const logout = jest.fn();
    const pushRegistration: PushRegistration = {
      start: jest.fn().mockResolvedValue("unsupported"),
      revoke: jest.fn(),
      stop: jest.fn(),
    };
    const resetApi = {
      getProfile: jest.fn().mockResolvedValue({ ok: true, value: profile, serverTime: meta.serverTime }),
      requestPasswordReset: jest.fn().mockResolvedValue({ ok: true, value: { challengeId: "reset-1" } }),
      completePasswordReset: jest.fn().mockResolvedValue({ ok: true, value: undefined }),
    } as unknown as CustomerProfileApi;
    render(
      <App
        config={config}
        tokenStore={signedInStore}
        authApi={{ logout, refresh: jest.fn() } as never}
        profileApi={resetApi}
        pushRegistrationOverride={pushRegistration}
      />,
    );

    fireEvent.press(await screen.findByText("Profile"));
    fireEvent.press(await screen.findByRole("button", { name: "Reset password" }));
    fireEvent.press(screen.getByRole("button", { name: "Send reset code" }));
    fireEvent.changeText(await screen.findByLabelText("Verification code"), "482913");
    fireEvent.changeText(screen.getByLabelText("New password"), "newPassword1");
    fireEvent.press(screen.getByRole("button", { name: "Reset password" }));

    expect(await screen.findByText("Password reset. Every session has ended. Log in with your new password.")).toBeTruthy();
    expect(screen.getByRole("header", { name: "Welcome back" })).toBeTruthy();
    expect(signedInStore.clear).toHaveBeenCalledTimes(1);
    expect(pushRegistration.revoke).toHaveBeenCalledTimes(1);
    expect((pushRegistration.revoke as jest.Mock).mock.invocationCallOrder[0]!)
      .toBeLessThan((signedInStore.clear as jest.Mock).mock.invocationCallOrder[0]!);
    expect(logout).not.toHaveBeenCalled();
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
