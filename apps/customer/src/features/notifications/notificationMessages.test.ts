import { customerNotificationCodes, notificationMessage } from "./notificationMessages";

describe("customer notification text map", () => {
  it("maps every launch customer push code without backend prose", () => {
    for (const code of customerNotificationCodes) {
      expect(notificationMessage(code, {})).not.toBe("You have a new account update.");
    }
  });

  it("uses machine params and falls back safely for future codes", () => {
    expect(notificationMessage("POINTS_EARNED", { points: 25, message: "ignore me" })).toBe("You earned 25 points.");
    expect(notificationMessage("BOOKING_CONFIRMED", { venueName: "Level Up" })).toBe("The venue confirmed your booking at Level Up.");
    expect(notificationMessage("BOOKING_REJECTED", { venueName: "Level Up" })).toBe("The venue rejected your booking request at Level Up.");
    expect(notificationMessage("BOOKING_CANCELLED_VENUE_ACCOUNT_DISABLED", {})).toBe("Venue is temporarily unable to confirm this booking.");
    expect(notificationMessage("FUTURE_CODE", { message: "backend prose" })).toBe("You have a new account update.");
  });
});
