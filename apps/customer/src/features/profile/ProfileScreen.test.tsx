import { fireEvent, render, screen, waitFor } from "@testing-library/react-native";
import type { CustomerProfile, CustomerProfileApi } from "./api";
import { ProfileScreen } from "./ProfileScreen";

const profile: CustomerProfile = {
  userId: "3f6c1d2e-8a4b-4c5d-9e6f-7a8b9c0d1e2f",
  fullName: "Omar Hassan",
  username: "omar_h",
  phone: "+201001234567",
  phoneVerified: true,
  email: "omar@example.com",
  emailVerified: false,
  usernameChangeAvailableAt: "2026-11-10T12:00:00Z",
  joinedAt: "2026-10-01T09:30:00Z",
};

function profileApi(overrides: Partial<CustomerProfileApi> = {}): CustomerProfileApi {
  return {
    getProfile: jest.fn().mockResolvedValue({ ok: true, value: profile, serverTime: "2026-10-23T10:00:00Z" }),
    updateFullName: jest.fn(),
    changeUsername: jest.fn(),
    requestPhoneChange: jest.fn(),
    verifyPhoneChange: jest.fn(),
    requestPasswordReset: jest.fn(),
    completePasswordReset: jest.fn(),
    ...overrides,
  };
}

describe("customer profile", () => {
  it("loads the profile and shows the server cooldown instant", async () => {
    render(<ProfileScreen api={profileApi()} onLogout={jest.fn()} />);

    expect(await screen.findByText("Omar Hassan")).toBeTruthy();
    expect(screen.getByText("omar_h")).toBeTruthy();
    expect(screen.getByText("+201001234567 · Verified")).toBeTruthy();
    expect(screen.getByText("omar@example.com · Not verified")).toBeTruthy();
    expect(screen.getByText(/You can change your username again on/)).toBeTruthy();
  });

  it("updates only the editable full name and uses the returned profile", async () => {
    const updated = { ...profile, fullName: "Omar Ali" };
    const api = profileApi({ updateFullName: jest.fn().mockResolvedValue({ ok: true, value: updated }) });
    render(<ProfileScreen api={api} onLogout={jest.fn()} />);

    fireEvent.press(await screen.findByRole("button", { name: "Edit full name" }));
    fireEvent.changeText(screen.getByLabelText("Full name"), "Omar Ali");
    fireEvent.press(screen.getByRole("button", { name: "Save changes" }));

    await waitFor(() => expect(api.updateFullName).toHaveBeenCalledWith("Omar Ali"));
    expect(await screen.findByText("Your full name was updated.")).toBeTruthy();
    expect(screen.getByText("Omar Ali")).toBeTruthy();
  });

  it("maps the username cooldown returned by the server", async () => {
    const api = profileApi({ changeUsername: jest.fn().mockResolvedValue({ ok: false, code: "USERNAME_COOLDOWN_ACTIVE" }) });
    render(<ProfileScreen api={api} onLogout={jest.fn()} />);

    fireEvent.press(await screen.findByRole("button", { name: "Change username" }));
    fireEvent.changeText(screen.getByLabelText("Username"), "omar_new");
    fireEvent.press(screen.getByRole("button", { name: "Change username" }));

    expect(await screen.findByText("Your username cannot be changed yet. Check the available date shown on your profile.")).toBeTruthy();
  });

  it("changes the username and renders the profile returned by the server", async () => {
    const updated = { ...profile, username: "omar_new" };
    const api = profileApi({ changeUsername: jest.fn().mockResolvedValue({ ok: true, value: updated }) });
    render(<ProfileScreen api={api} onLogout={jest.fn()} />);

    fireEvent.press(await screen.findByRole("button", { name: "Change username" }));
    fireEvent.changeText(screen.getByLabelText("Username"), "omar_new");
    fireEvent.press(screen.getByRole("button", { name: "Change username" }));

    expect(await screen.findByText("Your username was updated.")).toBeTruthy();
    expect(screen.getByText("omar_new")).toBeTruthy();
  });

  it("performs the two-step phone change and resends through the server", async () => {
    const updated = { ...profile, phone: "+201009999999" };
    const api = profileApi({
      requestPhoneChange: jest.fn().mockResolvedValue({ ok: true, value: { challengeId: "challenge-1" } }),
      verifyPhoneChange: jest.fn().mockResolvedValue({ ok: true, value: updated }),
    });
    render(<ProfileScreen api={api} onLogout={jest.fn()} />);

    fireEvent.press(await screen.findByRole("button", { name: "Change phone number" }));
    fireEvent.changeText(screen.getByLabelText("Phone number"), "+201009999999");
    fireEvent.press(screen.getByRole("button", { name: "Continue" }));

    expect(await screen.findByRole("header", { name: "Verify your new phone" })).toBeTruthy();
    fireEvent.changeText(screen.getByLabelText("Verification code"), "111111");
    fireEvent.press(screen.getByRole("button", { name: "Resend code" }));
    await waitFor(() => expect(api.requestPhoneChange).toHaveBeenCalledTimes(2));
    expect(screen.getByLabelText("Verification code").props.value).toBe("");
    expect(screen.getByText("A new verification code was sent.")).toBeTruthy();
    fireEvent.changeText(screen.getByLabelText("Verification code"), "482913");
    fireEvent.press(screen.getByRole("button", { name: "Verify phone" }));

    await waitFor(() => expect(api.verifyPhoneChange).toHaveBeenCalledWith("challenge-1", "482913"));
    expect(await screen.findByText("Your phone number was updated.")).toBeTruthy();
  });

  it("maps PHONE_TAKEN while starting a phone change", async () => {
    const api = profileApi({ requestPhoneChange: jest.fn().mockResolvedValue({ ok: false, code: "PHONE_TAKEN" }) });
    render(<ProfileScreen api={api} onLogout={jest.fn()} />);

    fireEvent.press(await screen.findByRole("button", { name: "Change phone number" }));
    fireEvent.changeText(screen.getByLabelText("Phone number"), "+201009999999");
    fireEvent.press(screen.getByRole("button", { name: "Continue" }));

    expect(await screen.findByText("That phone number is already registered.")).toBeTruthy();
  });

  it.each([
    ["OTP_INVALID", "That verification code is not correct."],
    ["OTP_EXPIRED", "That verification code has expired. Request a new one."],
  ] as const)("maps %s while verifying a phone change", async (code, message) => {
    const api = profileApi({
      requestPhoneChange: jest.fn().mockResolvedValue({ ok: true, value: { challengeId: "challenge-1" } }),
      verifyPhoneChange: jest.fn().mockResolvedValue({ ok: false, code }),
    });
    render(<ProfileScreen api={api} onLogout={jest.fn()} />);

    fireEvent.press(await screen.findByRole("button", { name: "Change phone number" }));
    fireEvent.changeText(screen.getByLabelText("Phone number"), "+201009999999");
    fireEvent.press(screen.getByRole("button", { name: "Continue" }));
    fireEvent.changeText(await screen.findByLabelText("Verification code"), "482913");
    fireEvent.press(screen.getByRole("button", { name: "Verify phone" }));

    expect(await screen.findByText(message)).toBeTruthy();
  });

  it("maps a forbidden profile load without exposing backend text", async () => {
    const api = profileApi({ getProfile: jest.fn().mockResolvedValue({ ok: false, code: "FORBIDDEN" }) });
    render(<ProfileScreen api={api} onLogout={jest.fn()} />);

    expect(await screen.findByText("This account cannot perform that action.")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Retry" })).toBeTruthy();
  });

  it("logs out after a successful password reset", async () => {
    const onLogout = jest.fn();
    const onPasswordResetComplete = jest.fn();
    const api = profileApi({
      requestPasswordReset: jest.fn().mockResolvedValue({ ok: true, value: { challengeId: "reset-1" } }),
      completePasswordReset: jest.fn().mockResolvedValue({ ok: true, value: undefined }),
    });
    render(<ProfileScreen api={api} onLogout={onLogout} onPasswordResetComplete={onPasswordResetComplete} />);

    fireEvent.press(await screen.findByRole("button", { name: "Reset password" }));
    fireEvent.press(screen.getByRole("button", { name: "Send reset code" }));
    fireEvent.changeText(await screen.findByLabelText("Verification code"), "482913");
    fireEvent.changeText(screen.getByLabelText("New password"), "newPassword1");
    fireEvent.press(screen.getByRole("button", { name: "Reset password" }));

    await waitFor(() => expect(onPasswordResetComplete).toHaveBeenCalledTimes(1));
    expect(onLogout).not.toHaveBeenCalled();
  });
});
