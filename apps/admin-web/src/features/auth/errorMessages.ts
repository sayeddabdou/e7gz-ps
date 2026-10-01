import type { components } from "@e7gz/api-client";
import { messages } from "../../i18n/messages";

export type ApiErrorCode = components["schemas"]["ApiErrorCode"];

export const authErrorMessages = {
  UNAUTHENTICATED: messages.auth.errors.unauthenticated,
  RATE_LIMITED: messages.auth.errors.rateLimited,
  VALIDATION_FAILED: messages.auth.errors.validationFailed,
} satisfies Partial<Record<ApiErrorCode, string>>;

export function authErrorMessage(code: ApiErrorCode | undefined): string {
  return (code && (authErrorMessages as Partial<Record<ApiErrorCode, string>>)[code]) ?? messages.auth.errors.unexpected;
}
