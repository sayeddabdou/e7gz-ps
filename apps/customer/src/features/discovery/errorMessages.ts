import { en } from "../../i18n/en";

export function discoveryErrorMessage(code: string | undefined, field?: string): string {
  if (code === "RATE_LIMITED") return en.discovery.errors.rateLimited;
  if (code === "NOT_FOUND") return en.discovery.errors.notFound;
  if (code === "VALIDATION_FAILED" && field === "durationMinutes") return en.findAvailable.errors.duration;
  if (code === "VALIDATION_FAILED" && field === "startTime") return en.findAvailable.errors.startTime;
  if (code === "VALIDATION_FAILED") return en.discovery.errors.validation;
  return en.discovery.errors.unexpected;
}
