import { fireEvent, render, screen, waitFor } from "@testing-library/react-native";
import type { TokenStore } from "../../auth/tokenStore";
import { AuthFlow } from "./AuthFlow";
import { authErrorMessages } from "./errorMessages";
import { resendDeadline, secondsUntil, serverClockOffset, type CustomerAuthApi } from "./api";

const tokens = { accessToken: "access", refreshToken: "refresh" };
const otpErrorCases = [
  ["OTP_INVALID", authErrorMessages.OTP_INVALID],
  ["OTP_EXPIRED", authErrorMessages.OTP_EXPIRED],
  ["OTP_RESEND_TOO_SOON", authErrorMessages.OTP_RESEND_TOO_SOON],
  ["OTP_HOURLY_LIMIT_REACHED", authErrorMessages.OTP_HOURLY_LIMIT_REACHED],
  ["OTP_ATTEMPTS_EXCEEDED", authErrorMessages.OTP_ATTEMPTS_EXCEEDED],
] as const;
const tokenStore: TokenStore = {
  read: jest.fn().mockResolvedValue(null),
  write: jest.fn().mockResolvedValue(undefined),
  clear: jest.fn().mockResolvedValue(undefined),
};

function authApi(overrides: Partial<CustomerAuthApi> = {}): CustomerAuthApi {
  return {
    register: jest.fn(),
    login: jest.fn().mockResolvedValue({ ok: true, value: tokens, serverTime: "2026-10-01T10:00:00Z" }),
    verifyPhone: jest.fn(),
    resend: jest.fn(),
    refresh: jest.fn(),
    logout: jest.fn(),
    ...overrides,
  };
}

describe("customer authentication", () => {
  it("shows a post-reset confirmation on the login screen", () => {
    render(<AuthFlow api={authApi()} tokenStore={tokenStore} notice="Password reset. Every session has ended. Log in with your new password." onAuthenticated={jest.fn()} />);

    expect(screen.getByText("Password reset. Every session has ended. Log in with your new password.")).toBeTruthy();
  });

  it("opens the phone-only password reset flow from login", () => {
    const onForgotPassword = jest.fn();
    render(<AuthFlow api={authApi()} tokenStore={tokenStore} onAuthenticated={jest.fn()} onForgotPassword={onForgotPassword} />);

    fireEvent.press(screen.getByRole("button", { name: "Forgot password?" }));

    expect(onForgotPassword).toHaveBeenCalledTimes(1);
  });

  it("logs in and writes tokens to secure storage", async () => {
    const onAuthenticated = jest.fn();
    render(<AuthFlow api={authApi()} tokenStore={tokenStore} onAuthenticated={onAuthenticated} />);

    fireEvent.changeText(screen.getByLabelText("Username or phone"), "omar_h");
    fireEvent.changeText(screen.getByLabelText("Password"), "password1");
    fireEvent.press(screen.getByRole("button", { name: "Log in" }));

    await waitFor(() => expect(tokenStore.write).toHaveBeenCalledWith(tokens));
    expect(onAuthenticated).toHaveBeenCalled();
  });

  it.each(otpErrorCases)(
    "renders the mapped %s response on the OTP screen",
    async (code, message) => {
      const api = authApi({
        register: jest.fn().mockResolvedValue({
          ok: true,
          value: { phone: "+201001234567", challengeId: "challenge-1" },
          serverTime: "2026-10-01T10:00:00Z",
        }),
        verifyPhone: jest.fn().mockResolvedValue({ ok: false, code, serverTime: "2026-10-01T10:00:01Z" }),
      });
      render(<AuthFlow api={api} tokenStore={tokenStore} onAuthenticated={jest.fn()} />);

      fireEvent.press(screen.getByRole("button", { name: "Create an account" }));
      fireEvent.changeText(screen.getByLabelText("Full name"), "Omar Hassan");
      fireEvent.changeText(screen.getByLabelText("Phone number"), "+201001234567");
      fireEvent.changeText(screen.getByLabelText("Email"), "omar@example.com");
      fireEvent.changeText(screen.getByLabelText("Username or phone"), "omar_h");
      fireEvent.changeText(screen.getByLabelText("Password"), "password1");
      fireEvent.press(screen.getByRole("button", { name: "Continue" }));

      await waitFor(() => expect(screen.getByRole("header", { name: "Verify your phone" })).toBeTruthy());
      fireEvent.changeText(screen.getByLabelText("Verification code"), "123456");
      fireEvent.press(screen.getByRole("button", { name: "Verify" }));

      await waitFor(() => expect(screen.getByRole("alert").props.children).toBe(message));
      expect(api.verifyPhone).toHaveBeenCalledWith("challenge-1", "123456");
    },
  );

  it("anchors the resend countdown to the server instant", () => {
    const deadline = resendDeadline("2026-10-01T10:00:00Z");
    const skewedDeviceNow = Date.parse("2026-10-01T13:00:00Z");
    const offset = serverClockOffset("2026-10-01T10:00:00Z", skewedDeviceNow);
    expect(deadline).toBe("2026-10-01T10:01:00.000Z");
    expect(secondsUntil(deadline, skewedDeviceNow + 15_000, offset)).toBe(45);
  });

  it("requires and sends a new password after resending a registration code", async () => {
    const deviceNow = Date.parse("2020-10-01T10:00:00Z");
    const now = jest.spyOn(Date, "now").mockReturnValueOnce(deviceNow).mockReturnValue(deviceNow + 61_000);
    const api = authApi({
      register: jest.fn().mockResolvedValue({
        ok: true,
        value: { phone: "+201001234567", challengeId: "registration-1" },
        serverTime: "2020-10-01T10:00:00Z",
      }),
      resend: jest.fn().mockResolvedValue({
        ok: true,
        value: { challengeId: "resend-1" },
        serverTime: "2020-10-01T10:00:00Z",
      }),
      verifyPhone: jest.fn().mockResolvedValue({ ok: true, value: tokens, serverTime: "2020-10-01T10:00:01Z" }),
    });
    render(<AuthFlow api={api} tokenStore={tokenStore} onAuthenticated={jest.fn()} />);

    fireEvent.press(screen.getByRole("button", { name: "Create an account" }));
    fireEvent.changeText(screen.getByLabelText("Full name"), "Omar Hassan");
    fireEvent.changeText(screen.getByLabelText("Phone number"), "+201001234567");
    fireEvent.changeText(screen.getByLabelText("Email"), "omar@example.com");
    fireEvent.changeText(screen.getByLabelText("Username or phone"), "omar_h");
    fireEvent.changeText(screen.getByLabelText("Password"), "password1");
    fireEvent.press(screen.getByRole("button", { name: "Continue" }));

    fireEvent.press(await screen.findByRole("button", { name: "Resend code" }));
    fireEvent.changeText(await screen.findByLabelText("Verification code"), "482913");
    expect(screen.getByRole("button", { name: "Verify" }).props.accessibilityState?.disabled).toBe(true);
    fireEvent.changeText(screen.getByLabelText("New password"), "replacement1");
    expect(screen.getByRole("button", { name: "Verify" }).props.accessibilityState?.disabled).toBe(false);
    fireEvent.press(screen.getByRole("button", { name: "Verify" }));

    await waitFor(() => expect(api.verifyPhone).toHaveBeenCalledWith("resend-1", "482913", "replacement1"));
    now.mockRestore();
  });
});
