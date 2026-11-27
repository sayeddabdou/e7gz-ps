import type { components } from "@e7gz/api-client";
import openApi from "../../../../../packages/api-client/openapi.json";
import { en } from "../../i18n/en";
import {
  bookingDetailReasonText,
  bookingEventText,
  bookingReasonText,
  canRequestCancellation,
  operationalBookingReasons,
} from "./bookingText";

type BookingDetail = components["schemas"]["CustomerBookingDetail"];
type TerminalReason = components["schemas"]["BookingTerminalReason"];

const contractTerminalReasons = openApi.components.schemas.BookingTerminalReason.enum as TerminalReason[];

const operationalDetail = (terminalReason: BookingDetail["terminalReason"]): BookingDetail => ({
  bookingId: "b-1",
  status: "CANCELLED",
  displayBucket: "cancelled",
  terminalReason,
  venue: { id: "v-1", name: "Game Zone" },
  resource: { name: "Room 1", activity: "PLAYSTATION", consoleType: "PS5", playMode: "MULTIPLAYER" },
  startAt: "2026-11-26T18:00:00Z",
  endAt: "2026-11-26T20:00:00Z",
  nominalBookedMinutes: 120,
  venuePriceTotal: { amount: "200.00", currency: "EGP" },
  bookingFee: { amount: "20.00", currency: "EGP", displayAsFree: false },
  bookingFeePayment: {
    pointsUsed: 10_000,
    paidByPoints: { amount: "10.00", currency: "EGP" },
    paidByWallet: { amount: "10.00", currency: "EGP" },
    leftToPay: { amount: "0.00", currency: "EGP" },
  },
  totalAmount: { amount: "220.00", currency: "EGP" },
  venueResponseExpiresAt: null,
  venueResponsePaused: false,
  lateConfirm: null,
  attendanceResult: null,
  history: [],
});

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

  it("has customer copy for every operational booking reason", () => {
    for (const reason of operationalBookingReasons) {
      const message = bookingDetailReasonText(operationalDetail(reason));
      expect(message).toBeTruthy();
      expect(message).not.toBe(reason);
    }
  });

  it("has customer copy for every terminal reason in the generated API contract", () => {
    expect(Object.keys(en.myBookings.reasons).sort()).toEqual([...contractTerminalReasons].sort());
    for (const reason of contractTerminalReasons) {
      const message = bookingReasonText("CANCELLED", reason);
      expect(message).toBeTruthy();
      expect(message).not.toBe(reason);
    }
  });

  it("uses booking history to distinguish account-disable expiry from ordinary expiry", () => {
    const ordinary = { ...operationalDetail(null), status: "VENUE_RESPONSE_EXPIRED" as const };
    const disabled = {
      ...ordinary,
      history: [{
        at: "2026-11-26T16:00:00Z",
        kind: "BOOKING_EVENT" as const,
        eventType: "VENUE_RESPONSE_INTERRUPTED_BY_ACCOUNT_DISABLE" as const,
        fromStatus: null,
        toStatus: null,
        terminalReason: null,
      }],
    };

    expect(bookingDetailReasonText(ordinary)).toBe("Venue did not respond in time");
    expect(bookingDetailReasonText(disabled)).toBe("Venue is temporarily unable to confirm this booking");
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
