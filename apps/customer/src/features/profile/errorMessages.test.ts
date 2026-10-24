import { en } from "../../i18n/en";
import { profileErrorMessage, profileErrorMessages } from "./errorMessages";

describe("profile error messages", () => {
  it.each(Object.entries(profileErrorMessages))("maps %s without exposing backend text", (code, message) => {
    expect(profileErrorMessage(code as keyof typeof profileErrorMessages)).toBe(message);
  });

  it("uses safe text for undeclared and unexpected responses", () => {
    expect(profileErrorMessage(undefined)).toBe(en.profile.errors.unexpected);
    expect(profileErrorMessage("BOOKING_STATE_CHANGED" as never)).toBe(en.profile.errors.unexpected);
  });
});
