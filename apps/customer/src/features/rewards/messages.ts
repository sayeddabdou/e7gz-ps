import type { components } from "@e7gz/api-client";
import { en as t } from "../../i18n/en";
import type { RewardsErrorCode } from "./api";

type PointsCause = components["schemas"]["PointsTransactionCause"];
type WalletCause = components["schemas"]["WalletTransactionCause"];
type ReferralStatus = components["schemas"]["ReferralStatus"];
type VerifiedStatus = components["schemas"]["VerifiedPlayerStatus"];
type VerifiedQualification = components["schemas"]["VerifiedPlayerQualification"];

export const rewardsErrorMessages: Record<RewardsErrorCode, string> = {
  VALIDATION_FAILED: t.rewards.errors.validation,
  UNAUTHENTICATED: t.rewards.errors.unauthenticated,
  FORBIDDEN: t.rewards.errors.forbidden,
  RATE_LIMITED: t.rewards.errors.rateLimited,
};

export function rewardsErrorMessage(code?: RewardsErrorCode): string {
  return code ? rewardsErrorMessages[code] : t.rewards.errors.unexpected;
}

export const pointsCauseMessages: Record<PointsCause, string> = {
  ATTENDED_COMPLETION: t.rewards.points.causes.ATTENDED_COMPLETION,
  REFERRAL_SIGNUP: t.rewards.points.causes.REFERRAL_SIGNUP,
  COMPENSATION: t.rewards.points.causes.COMPENSATION,
  ADMIN: t.rewards.points.causes.ADMIN,
  BOOKING_FEE_PAYMENT: t.rewards.points.causes.BOOKING_FEE_PAYMENT,
  BOOKING_FEE_REFUND: t.rewards.points.causes.BOOKING_FEE_REFUND,
};

export const walletCauseMessages: Record<WalletCause, string> = {
  BOOKING_FEE_REFUND: t.rewards.wallet.causes.BOOKING_FEE_REFUND,
  BOOKING_FEE_PAYMENT: t.rewards.wallet.causes.BOOKING_FEE_PAYMENT,
};

export const referralStatusMessages: Record<ReferralStatus, string> = {
  PENDING: t.rewards.referrals.statuses.PENDING,
  REWARDED: t.rewards.referrals.statuses.REWARDED,
  INVALID: t.rewards.referrals.statuses.INVALID,
};

export const verifiedStatusMessages: Record<VerifiedStatus, string> = {
  NOT_VERIFIED: t.rewards.standing.verifiedStatuses.NOT_VERIFIED,
  VERIFIED: t.rewards.standing.verifiedStatuses.VERIFIED,
  SUSPENDED: t.rewards.standing.verifiedStatuses.SUSPENDED,
  REVOKED: t.rewards.standing.verifiedStatuses.REVOKED,
};

export const verifiedQualificationMessages: Record<VerifiedQualification, string> = {
  NOT_QUALIFIED: t.rewards.standing.qualifications.NOT_QUALIFIED,
  QUALIFIED: t.rewards.standing.qualifications.QUALIFIED,
};
