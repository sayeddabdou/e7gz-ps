import { bookingErrorMessage, bookingErrorCodes } from "./errorMessages";

describe("bookingErrorMessage", () => {
  it("maps every quote and create error code declared by the E7-39 contract", () => {
    expect(bookingErrorCodes).toEqual(expect.arrayContaining([
      "VALIDATION_FAILED",
      "UNAUTHENTICATED",
      "FORBIDDEN",
      "PHONE_VERIFICATION_REQUIRED",
      "ACCOUNT_BANNED_FOR_THIS_ACTION",
      "ACCOUNT_DELETED",
      "SLOT_NO_LONGER_AVAILABLE",
      "BOOKING_LEAD_TIME_TOO_SHORT",
      "BOOKING_DURATION_INVALID",
      "COMMITMENT_LIMIT_REACHED",
      "OVERLAPPING_COMMITMENT",
      "INTERVAL_NOT_WITHIN_ONE_SESSION",
      "ONLINE_INTAKE_WINDOW_CLOSED",
      "VENUE_NOT_ELIGIBLE_FOR_NEW_ACTIVITY",
      "RESOURCE_NOT_ELIGIBLE",
      "VENUE_SELLING_BLOCK_ACTIVE",
      "RESOURCE_SELLING_BLOCK_ACTIVE",
      "IDEMPOTENCY_KEY_REUSED",
      "RATE_LIMITED",
    ]));
    expect(new Set(bookingErrorCodes).size).toBe(bookingErrorCodes.length);
    bookingErrorCodes.forEach((code) => expect(bookingErrorMessage(code)).not.toMatch(/something went wrong/iu));
  });

  it("uses a safe fallback for an unexpected response", () => {
    expect(bookingErrorMessage(undefined)).toBe("Something went wrong. Try again.");
  });
});
