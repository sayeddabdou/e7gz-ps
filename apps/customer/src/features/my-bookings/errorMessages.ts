import type { components } from "@e7gz/api-client";
import { en } from "../../i18n/en";

type ApiErrorCode = components["schemas"]["ApiErrorCode"];

export const customerBookingErrorCodes = [
  "VALIDATION_FAILED",
  "UNAUTHENTICATED",
  "FORBIDDEN",
  "NOT_FOUND",
  "BOOKING_STATE_CHANGED",
  "DEADLINE_PASSED",
  "IDEMPOTENCY_KEY_REUSED",
  "RATE_LIMITED",
] as const satisfies readonly ApiErrorCode[];

type CustomerBookingErrorCode = typeof customerBookingErrorCodes[number];

const messages: Record<CustomerBookingErrorCode, string> = {
  VALIDATION_FAILED: en.myBookings.errors.validation,
  UNAUTHENTICATED: en.myBookings.errors.unauthenticated,
  FORBIDDEN: en.myBookings.errors.forbidden,
  NOT_FOUND: en.myBookings.errors.notFound,
  BOOKING_STATE_CHANGED: en.myBookings.errors.stateChanged,
  DEADLINE_PASSED: en.myBookings.errors.deadlinePassed,
  IDEMPOTENCY_KEY_REUSED: en.myBookings.errors.idempotencyKeyReused,
  RATE_LIMITED: en.myBookings.errors.rateLimited,
};

export function customerBookingErrorMessage(code: string | undefined): string {
  return code && code in messages
    ? messages[code as CustomerBookingErrorCode]
    : en.myBookings.errors.unexpected;
}
