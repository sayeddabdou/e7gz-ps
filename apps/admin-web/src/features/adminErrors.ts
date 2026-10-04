import { messages as t } from "../i18n/messages";

export function adminErrorMessage(code?: string, field?: string) {
  if (code === "VALIDATION_FAILED" && field) {
    return t.errors.fields[field as keyof typeof t.errors.fields] ?? t.errors.validationFailed;
  }
  return t.errors.codes[code as keyof typeof t.errors.codes] ?? t.errors.unexpected;
}
