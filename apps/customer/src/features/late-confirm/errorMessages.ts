import type { components } from "@e7gz/api-client";
import { en } from "../../i18n/en";

type ApiErrorCode = components["schemas"]["ApiErrorCode"];

export const lateConfirmErrorCodes = [
  "VALIDATION_FAILED",
  "UNAUTHENTICATED",
  "PHONE_VERIFICATION_REQUIRED",
  "ACCOUNT_BANNED_FOR_THIS_ACTION",
  "ACCOUNT_DELETED",
  "FORBIDDEN",
  "NOT_FOUND",
  "BOOKING_STATE_CHANGED",
  "LATE_CONFIRM_CUSTOMER_CONFLICT",
  "LATE_CONFIRM_RESOURCE_CONFLICT",
  "DEADLINE_PASSED",
  "COMMITMENT_LIMIT_REACHED",
  "INTERVAL_NOT_WITHIN_ONE_SESSION",
  "AREA_INACTIVE",
  "RESOURCE_NOT_ELIGIBLE",
  "VENUE_SELLING_BLOCK_ACTIVE",
  "RESOURCE_SELLING_BLOCK_ACTIVE",
  "IDEMPOTENCY_KEY_REUSED",
  "RATE_LIMITED",
] as const satisfies readonly ApiErrorCode[];

type LateConfirmErrorCode = typeof lateConfirmErrorCodes[number];

const messages: Record<LateConfirmErrorCode, string> = {
  VALIDATION_FAILED: en.lateConfirm.errors.validation,
  UNAUTHENTICATED: en.lateConfirm.errors.unauthenticated,
  PHONE_VERIFICATION_REQUIRED: en.lateConfirm.errors.phoneVerificationRequired,
  ACCOUNT_BANNED_FOR_THIS_ACTION: en.lateConfirm.errors.accountBanned,
  ACCOUNT_DELETED: en.lateConfirm.errors.accountDeleted,
  FORBIDDEN: en.lateConfirm.errors.forbidden,
  NOT_FOUND: en.lateConfirm.errors.notFound,
  BOOKING_STATE_CHANGED: en.lateConfirm.errors.stateChanged,
  LATE_CONFIRM_CUSTOMER_CONFLICT: en.lateConfirm.errors.customerConflict,
  LATE_CONFIRM_RESOURCE_CONFLICT: en.lateConfirm.errors.resourceConflict,
  DEADLINE_PASSED: en.lateConfirm.errors.deadlinePassed,
  COMMITMENT_LIMIT_REACHED: en.lateConfirm.errors.commitmentLimit,
  INTERVAL_NOT_WITHIN_ONE_SESSION: en.lateConfirm.errors.outsideSession,
  AREA_INACTIVE: en.lateConfirm.errors.areaInactive,
  RESOURCE_NOT_ELIGIBLE: en.lateConfirm.errors.resourceUnavailable,
  VENUE_SELLING_BLOCK_ACTIVE: en.lateConfirm.errors.venuePaused,
  RESOURCE_SELLING_BLOCK_ACTIVE: en.lateConfirm.errors.resourcePaused,
  IDEMPOTENCY_KEY_REUSED: en.lateConfirm.errors.idempotencyKeyReused,
  RATE_LIMITED: en.lateConfirm.errors.rateLimited,
};

export function lateConfirmErrorMessage(code: string | undefined): string {
  return code && code in messages
    ? messages[code as LateConfirmErrorCode]
    : en.lateConfirm.errors.unexpected;
}
