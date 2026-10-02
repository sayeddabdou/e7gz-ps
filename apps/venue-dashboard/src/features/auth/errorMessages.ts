import type { components } from "@e7gz/api-client";
import type { Language, Messages } from "../../i18n";
import { messages } from "../../i18n";

export type ApiErrorCode = components["schemas"]["ApiErrorCode"];

function mapFor(t: Messages) {
  return {
    UNAUTHENTICATED: t.auth.errors.unauthenticated,
    RATE_LIMITED: t.auth.errors.rateLimited,
    VALIDATION_FAILED: t.auth.errors.validationFailed,
  } satisfies Partial<Record<ApiErrorCode, string>>;
}

export const authErrorMessages: Record<Language, Partial<Record<ApiErrorCode, string>>> = {
  en: mapFor(messages.en),
  ar: mapFor(messages.ar),
};

export function authErrorMessage(language: Language, code: ApiErrorCode | undefined): string {
  return (code && authErrorMessages[language][code]) ?? messages[language].auth.errors.unexpected;
}
