import type { components } from "@e7gz/api-client";
import { en } from "../../i18n/en";

type ApiErrorCode = components["schemas"]["ApiErrorCode"];

// Exact union of the E7-39 quote and create endpoint error codes.
export const bookingErrorCodes = [
  "VALIDATION_FAILED",
  "UNAUTHENTICATED",
  "FORBIDDEN",
  "NOT_FOUND",
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
] as const satisfies readonly ApiErrorCode[];

type BookingErrorCode = typeof bookingErrorCodes[number];

const messages: Record<BookingErrorCode, string> = {
  VALIDATION_FAILED: en.booking.errors.validation,
  UNAUTHENTICATED: en.booking.errors.unauthenticated,
  FORBIDDEN: en.booking.errors.forbidden,
  NOT_FOUND: en.booking.errors.notFound,
  PHONE_VERIFICATION_REQUIRED: en.booking.errors.phoneVerificationRequired,
  ACCOUNT_BANNED_FOR_THIS_ACTION: en.booking.errors.accountBanned,
  ACCOUNT_DELETED: en.booking.errors.accountDeleted,
  SLOT_NO_LONGER_AVAILABLE: en.booking.errors.slotNoLongerAvailable,
  BOOKING_LEAD_TIME_TOO_SHORT: en.booking.errors.leadTimeTooShort,
  BOOKING_DURATION_INVALID: en.booking.errors.durationInvalid,
  COMMITMENT_LIMIT_REACHED: en.booking.errors.commitmentLimit,
  OVERLAPPING_COMMITMENT: en.booking.errors.overlappingCommitment,
  INTERVAL_NOT_WITHIN_ONE_SESSION: en.booking.errors.outsideSession,
  ONLINE_INTAKE_WINDOW_CLOSED: en.booking.errors.intakeClosed,
  VENUE_NOT_ELIGIBLE_FOR_NEW_ACTIVITY: en.booking.errors.venueUnavailable,
  RESOURCE_NOT_ELIGIBLE: en.booking.errors.resourceUnavailable,
  VENUE_SELLING_BLOCK_ACTIVE: en.booking.errors.venueSellingBlocked,
  RESOURCE_SELLING_BLOCK_ACTIVE: en.booking.errors.resourceSellingBlocked,
  IDEMPOTENCY_KEY_REUSED: en.booking.errors.idempotencyKeyReused,
  RATE_LIMITED: en.booking.errors.rateLimited,
};

export function bookingErrorMessage(code: string | undefined): string {
  return code && code in messages ? messages[code as BookingErrorCode] : en.booking.errors.unexpected;
}
