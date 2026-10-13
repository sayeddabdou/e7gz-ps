import type { Messages } from "../../i18n";

export function bookingErrorMessage(code: string | undefined, t: Messages): string {
  const errors = t.bookings.errors;
  if (!code) return errors.unexpected;
  return errors[code as keyof typeof errors] ?? errors.unexpected;
}
