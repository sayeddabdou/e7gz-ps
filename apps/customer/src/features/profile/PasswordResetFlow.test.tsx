import { fireEvent, render, screen, waitFor } from "@testing-library/react-native";
import type { CustomerProfileApi } from "./api";
import { PasswordResetFlow } from "./PasswordResetFlow";

function profileApi(overrides: Partial<CustomerProfileApi> = {}): CustomerProfileApi {
  return {
    getProfile: jest.fn(),
    updateFullName: jest.fn(),
    changeUsername: jest.fn(),
    requestPhoneChange: jest.fn(),
    verifyPhoneChange: jest.fn(),
    requestPasswordReset: jest.fn().mockResolvedValue({ ok: true, value: { challengeId: "reset-1" } }),
    completePasswordReset: jest.fn().mockResolvedValue({ ok: true, value: undefined }),
    ...overrides,
  };
}

describe("password reset", () => {
  it("requests a phone code, completes the reset and ends the local session", async () => {
    const api = profileApi();
    const onComplete = jest.fn();
    render(<PasswordResetFlow api={api} onCancel={jest.fn()} onComplete={onComplete} />);

    fireEvent.changeText(screen.getByLabelText("Phone number"), "+201001234567");
    fireEvent.press(screen.getByRole("button", { name: "Send reset code" }));
    expect(await screen.findByRole("header", { name: "Create a new password" })).toBeTruthy();

    fireEvent.changeText(screen.getByLabelText("Verification code"), "482913");
    fireEvent.changeText(screen.getByLabelText("New password"), "newPassword1");
    fireEvent.press(screen.getByRole("button", { name: "Reset password" }));

    await waitFor(() => expect(api.completePasswordReset).toHaveBeenCalledWith("reset-1", "482913", "newPassword1"));
    expect(onComplete).toHaveBeenCalledTimes(1);
  });

  it("uses the request endpoint again for resend and displays its server error", async () => {
    const requestPasswordReset = jest.fn()
      .mockResolvedValueOnce({ ok: true, value: { challengeId: "reset-1" } })
      .mockResolvedValueOnce({ ok: false, code: "OTP_RESEND_TOO_SOON" });
    const api = profileApi({ requestPasswordReset });
    render(<PasswordResetFlow api={api} initialPhone="+201001234567" onCancel={jest.fn()} onComplete={jest.fn()} />);

    fireEvent.press(screen.getByRole("button", { name: "Send reset code" }));
    fireEvent.press(await screen.findByRole("button", { name: "Resend code" }));

    expect(await screen.findByText("A new code cannot be sent yet.")).toBeTruthy();
    expect(requestPasswordReset).toHaveBeenCalledTimes(2);
  });

  it("replaces the challenge, clears the old code and confirms a successful resend", async () => {
    const requestPasswordReset = jest.fn()
      .mockResolvedValueOnce({ ok: true, value: { challengeId: "reset-1" } })
      .mockResolvedValueOnce({ ok: true, value: { challengeId: "reset-2" } });
    const api = profileApi({ requestPasswordReset });
    render(<PasswordResetFlow api={api} initialPhone="+201001234567" onCancel={jest.fn()} onComplete={jest.fn()} />);

    fireEvent.press(screen.getByRole("button", { name: "Send reset code" }));
    fireEvent.changeText(await screen.findByLabelText("Verification code"), "111111");
    fireEvent.press(screen.getByRole("button", { name: "Resend code" }));

    expect(await screen.findByText("A new verification code was sent.")).toBeTruthy();
    expect(screen.getByLabelText("Verification code").props.value).toBe("");
    fireEvent.changeText(screen.getByLabelText("Verification code"), "482913");
    fireEvent.changeText(screen.getByLabelText("New password"), "newPassword1");
    fireEvent.press(screen.getByRole("button", { name: "Reset password" }));
    await waitFor(() => expect(api.completePasswordReset).toHaveBeenCalledWith("reset-2", "482913", "newPassword1"));
  });

  it("uses neutral copy when a reset request is not found", async () => {
    const api = profileApi({ requestPasswordReset: jest.fn().mockResolvedValue({ ok: false, code: "NOT_FOUND" }) });
    render(<PasswordResetFlow api={api} onCancel={jest.fn()} onComplete={jest.fn()} />);

    fireEvent.changeText(screen.getByLabelText("Phone number"), "+201001234567");
    fireEvent.press(screen.getByRole("button", { name: "Send reset code" }));

    expect(await screen.findByText("No account with a verified phone matches this number.")).toBeTruthy();
  });

  it("starts the reset resend countdown from server time", async () => {
    jest.useFakeTimers().setSystemTime(new Date("2026-10-23T10:00:00Z"));
    const api = profileApi({
      requestPasswordReset: jest.fn().mockResolvedValue({
        ok: true,
        value: { challengeId: "reset-1" },
        serverTime: "2026-10-23T10:00:00Z",
      }),
    });
    render(<PasswordResetFlow api={api} initialPhone="+201001234567" onCancel={jest.fn()} onComplete={jest.fn()} />);

    fireEvent.press(screen.getByRole("button", { name: "Send reset code" }));

    const resend = await screen.findByRole("button", { name: "Resend in 60s" });
    expect(resend.props.accessibilityState?.disabled ?? resend.props.disabled).toBe(true);
    jest.useRealTimers();
  });
});
