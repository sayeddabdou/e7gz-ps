import { lateConfirmErrorCodes, lateConfirmErrorMessage } from "./errorMessages";

describe("lateConfirmErrorMessage", () => {
  it("maps every declared Late Confirm error to customer copy", () => {
    for (const code of lateConfirmErrorCodes) {
      expect(lateConfirmErrorMessage(code)).not.toBe("Something went wrong. Try again.");
      expect(lateConfirmErrorMessage(code)).not.toContain(code);
    }
  });

  it("uses safe fallback copy for an unknown response", () => {
    expect(lateConfirmErrorMessage("FUTURE_ERROR")).toBe("Something went wrong. Try again.");
  });
});
