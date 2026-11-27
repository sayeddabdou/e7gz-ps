import type { components } from "@e7gz/api-client";
import { en } from "../../i18n/en";

type TerminalReason = components["schemas"]["BookingTerminalReason"];
type Status = components["schemas"]["BookingStatus"];
type EventType = components["schemas"]["BookingEventType"];
type BookingDetail = components["schemas"]["CustomerBookingDetail"];

const terminalReasons = en.myBookings.reasons satisfies Record<TerminalReason, string>;
const statuses = en.myBookings.statuses satisfies Record<Status, string>;
const events = en.myBookings.events satisfies Record<EventType, string>;

// Spec v5 §52.2 and FRS §4.9: these are operational outcomes whose customer copy must never fall back to a raw code.
export const operationalBookingReasons = [
  "VENUE_OPENING_HOURS_CHANGED",
  "VENUE_CLOSURE",
  "VENUE_EMERGENCY_CLOSURE",
  "RESOURCE_EMERGENCY_OUTAGE",
  "VENUE_RESPONSE_INTERRUPTED_BY_EMERGENCY",
  "RESOURCE_RESPONSE_INTERRUPTED_BY_EMERGENCY",
  "LATE_CONFIRM_INTERRUPTED_BY_EMERGENCY",
  "RESOURCE_OWNER_REVIEW_REQUIRED",
  "VENUE_ACCOUNT_DISABLED",
  "SYSTEM_RESOURCE_STATE_INTEGRITY_ERROR",
] as const satisfies readonly TerminalReason[];

const operationalReasonSet = new Set<TerminalReason>(operationalBookingReasons);

export function bookingStatusText(status: Status): string {
  return statuses[status];
}

export function bookingReasonText(status: Status, reason: TerminalReason | null): string | undefined {
  if (status === "VENUE_RESPONSE_EXPIRED") return en.myBookings.expiredResponse;
  return reason ? terminalReasons[reason] : undefined;
}

export function bookingDetailReasonText(booking: Pick<BookingDetail, "history" | "status" | "terminalReason">): string | undefined {
  // FR-NTF-06: the event, rather than the VENUE_RESPONSE_EXPIRED status, identifies an account-disable expiry.
  if (booking.status === "VENUE_RESPONSE_EXPIRED"
    && booking.history.some((entry) => entry.eventType === "VENUE_RESPONSE_INTERRUPTED_BY_ACCOUNT_DISABLE")) {
    return en.myBookings.reasons.VENUE_ACCOUNT_DISABLED;
  }
  return bookingReasonText(booking.status, booking.terminalReason);
}

export function isOperationalBookingReason(reason: TerminalReason | null): boolean {
  return reason !== null && operationalReasonSet.has(reason);
}

export function bookingEventText(eventType: EventType): string {
  return events[eventType];
}

export function canRequestCancellation(status: Status, startAt: string, serverTime: string): boolean {
  // AWAITING_CUSTOMER_CONFIRMATION has its separate Accept/Decline flow (FRS §4.4); this endpoint is ordinary cancel.
  return ["PENDING", "CONFIRMED"].includes(status)
    && Date.parse(serverTime) < Date.parse(startAt);
}
