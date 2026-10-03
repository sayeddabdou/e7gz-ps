import type { components } from "@e7gz/api-client";
import type { Messages } from "../../i18n";

type ApiErrorCode = components["schemas"]["ApiErrorCode"];

const setupCodes = [
  "VALIDATION_FAILED",
  "OWNER_PASSWORD_REQUIRED",
  "OWNER_PASSWORD_INVALID",
  "RESOURCE_HAS_BLOCKING_RESERVATIONS",
  "TOURNAMENT_CONFLICT_BLOCKS_SAVE",
  "AFFECTED_SET_CHANGED_REFRESH_PREVIEW",
  "VENUE_IMAGE_NOT_ACCEPTED",
  "VENUE_IMAGE_LIMIT_REACHED",
  "VENUE_IMAGE_NOT_UPLOADED",
  "NOT_FOUND",
  "FORBIDDEN",
  "RATE_LIMITED",
] as const satisfies readonly ApiErrorCode[];

type SetupCode = (typeof setupCodes)[number];

export function setupErrorMessage(code: string | undefined, t: Messages): string {
  return setupCodes.includes(code as SetupCode)
    ? t.setup.errors[code as SetupCode]
    : t.setup.errors.unexpected;
}
