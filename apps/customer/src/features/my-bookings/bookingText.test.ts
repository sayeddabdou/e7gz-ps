import { bookingEventText, bookingReasonText, canRequestCancellation } from "./bookingText";

describe("booking presentation rules", () => {
  it("uses the required venue non-response text in the cancelled projection", () => {
    expect(bookingReasonText("VENUE_RESPONSE_EXPIRED", null)).toBe("Venue did not respond in time");
    expect(bookingReasonText("CANCELLED", "VENUE_DID_NOT_RESPOND_IN_TIME")).toBe("Venue did not respond in time");
    expect(bookingReasonText("CANCELLED", "VENUE_ACCOUNT_DISABLED")).toBe("Venue is temporarily unable to confirm this booking");
  });

  it("maps booking history event codes to customer text", () => {
    expect(bookingEventText("CHECK_IN_RECORDED")).toBe("Check-in recorded");
    expect(bookingEventText("LATE_CONFIRM_FAILED_BOOKING_LIMIT")).toBe("Late confirmation could not continue because your booking limit was reached");
    expect(bookingEventText("VENUE_RESPONSE_INTERRUPTED_BY_ACCOUNT_DISABLE")).toBe("Venue is temporarily unable to confirm this booking");
  });

  it("offers cancellation only for server-documented future cancellable states", () => {
    const start = "2026-10-12T18:00:00Z";
    const now = "2026-10-12T17:00:00Z";
    expect(canRequestCancellation("PENDING", start, now)).toBe(true);
    expect(canRequestCancellation("AWAITING_CUSTOMER_CONFIRMATION", start, now)).toBe(false);
    expect(canRequestCancellation("CONFIRMED", start, now)).toBe(true);
    expect(canRequestCancellation("ACTIVE", start, now)).toBe(false);
    expect(canRequestCancellation("CONFIRMED", start, start)).toBe(false);
  });
});
