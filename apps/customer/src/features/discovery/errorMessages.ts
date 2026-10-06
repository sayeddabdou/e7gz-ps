import { en } from "../../i18n/en";

export function discoveryErrorMessage(code: string | undefined): string {
  if (code === "RATE_LIMITED") return en.discovery.errors.rateLimited;
  if (code === "NOT_FOUND") return en.discovery.errors.notFound;
  if (code === "VALIDATION_FAILED") return en.discovery.errors.validation;
  return en.discovery.errors.unexpected;
}
