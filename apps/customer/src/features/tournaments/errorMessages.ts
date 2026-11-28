import type { components } from "@e7gz/api-client";
import { en as t } from "../../i18n/en";

type ErrorCode = components["schemas"]["ApiErrorCode"];

export function tournamentErrorMessage(code?: ErrorCode | string): string {
  if (!code) return t.tournaments.errors.fallback;
  return t.tournaments.errors[code as keyof Omit<typeof t.tournaments.errors, "fallback">]
    ?? t.tournaments.errors.fallback;
}
