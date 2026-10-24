import { en } from "../../i18n/en";
import type { ProfileErrorCode } from "./api";

export const profileErrorMessages = {
  VALIDATION_FAILED: en.profile.errors.validation,
  UNAUTHENTICATED: en.profile.errors.unauthenticated,
  FORBIDDEN: en.profile.errors.forbidden,
  NOT_FOUND: en.profile.errors.notFound,
  RATE_LIMITED: en.profile.errors.rateLimited,
  USERNAME_TAKEN: en.profile.errors.usernameTaken,
  USERNAME_COOLDOWN_ACTIVE: en.profile.errors.usernameCooldown,
  PHONE_TAKEN: en.profile.errors.phoneTaken,
  ACCOUNT_DELETED: en.profile.errors.accountDeleted,
  OTP_INVALID: en.profile.errors.otpInvalid,
  OTP_EXPIRED: en.profile.errors.otpExpired,
  OTP_ATTEMPTS_EXCEEDED: en.profile.errors.otpAttemptsExceeded,
  OTP_RESEND_TOO_SOON: en.profile.errors.otpResendTooSoon,
  OTP_HOURLY_LIMIT_REACHED: en.profile.errors.otpHourlyLimit,
} satisfies Partial<Record<ProfileErrorCode, string>>;

export function profileErrorMessage(code: ProfileErrorCode | undefined): string {
  return (code && profileErrorMessages[code as keyof typeof profileErrorMessages]) ?? en.profile.errors.unexpected;
}
