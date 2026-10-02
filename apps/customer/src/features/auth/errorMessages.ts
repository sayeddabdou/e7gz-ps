import type { components } from "@e7gz/api-client";
import { en } from "../../i18n/en";

export type ApiErrorCode = components["schemas"]["ApiErrorCode"];

export const authErrorMessages = {
  OTP_INVALID: en.auth.errors.otpInvalid,
  OTP_EXPIRED: en.auth.errors.otpExpired,
  OTP_RESEND_TOO_SOON: en.auth.errors.otpResendTooSoon,
  OTP_HOURLY_LIMIT_REACHED: en.auth.errors.otpHourlyLimit,
  OTP_ATTEMPTS_EXCEEDED: en.auth.errors.otpAttemptsExceeded,
  USERNAME_TAKEN: en.auth.errors.usernameTaken,
  PHONE_TAKEN: en.auth.errors.phoneTaken,
  UNAUTHENTICATED: en.auth.errors.unauthenticated,
  RATE_LIMITED: en.auth.errors.rateLimited,
  VALIDATION_FAILED: en.auth.errors.validationFailed,
  ACCOUNT_DELETED: en.auth.errors.accountDeleted,
} satisfies Partial<Record<ApiErrorCode, string>>;

export function authErrorMessage(code: ApiErrorCode | undefined): string {
  return (code && (authErrorMessages as Partial<Record<ApiErrorCode, string>>)[code]) ?? en.auth.errors.unexpected;
}
