import type { components } from "@e7gz/api-client";
import { en } from "../../i18n/en";

type TerminalReason = components["schemas"]["BookingTerminalReason"];
type Status = components["schemas"]["BookingStatus"];
type EventType = components["schemas"]["BookingEventType"];

const terminalReasons = en.myBookings.reasons satisfies Record<TerminalReason, string>;
const statuses = en.myBookings.statuses satisfies Record<Status, string>;
const events = en.myBookings.events satisfies Record<EventType, string>;

export function bookingStatusText(status: Status): string {
  return statuses[status];
}

export function bookingReasonText(status: Status, reason: TerminalReason | null): string | undefined {
  if (status === "VENUE_RESPONSE_EXPIRED") return en.myBookings.expiredResponse;
  return reason ? terminalReasons[reason] : undefined;
}

export function bookingEventText(eventType: EventType): string {
  return events[eventType];
}

export function canRequestCancellation(status: Status, startAt: string, serverTime: string): boolean {
  // AWAITING_CUSTOMER_CONFIRMATION has its separate Accept/Decline flow (FRS §4.4); this endpoint is ordinary cancel.
  return ["PENDING", "CONFIRMED"].includes(status)
    && Date.parse(serverTime) < Date.parse(startAt);
}
