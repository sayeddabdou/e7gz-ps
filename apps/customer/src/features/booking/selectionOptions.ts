import { en as t } from "../../i18n/en";

export type DateOption = {
  accessibilityLabel: string;
  day: string;
  month: string;
  value: string;
  weekday: string;
};

export const allHalfHourTimes = Array.from({ length: 48 }, (_, index) => minutesToTime(index * 30));
// FR-BOK-01: online duration is 60–360 minutes in 30-minute increments.
export const durationOptions = Array.from({ length: 11 }, (_, index) => 60 + index * 30);

export function buildDateOptions(referenceDate: Date): DateOption[] {
  const cairoDate = new Intl.DateTimeFormat("en-CA", { day: "2-digit", month: "2-digit", timeZone: "Africa/Cairo", year: "numeric" })
    .formatToParts(referenceDate)
    .reduce<Record<string, string>>((parts, part) => ({ ...parts, [part.type]: part.value }), {});
  const firstDay = Date.UTC(Number(cairoDate.year), Number(cairoDate.month) - 1, Number(cairoDate.day));
  return Array.from({ length: 7 }, (_, index) => {
    const value = new Date(firstDay + index * 86_400_000);
    const isoDate = value.toISOString().slice(0, 10);
    const weekday = new Intl.DateTimeFormat("en", { timeZone: "UTC", weekday: "short" }).format(value).toUpperCase();
    const weekdayLong = new Intl.DateTimeFormat("en", { timeZone: "UTC", weekday: "long" }).format(value);
    const day = new Intl.DateTimeFormat("en", { day: "numeric", timeZone: "UTC" }).format(value);
    const month = new Intl.DateTimeFormat("en", { month: "short", timeZone: "UTC" }).format(value).toUpperCase();
    const monthLong = new Intl.DateTimeFormat("en", { month: "long", timeZone: "UTC" }).format(value);
    return {
      accessibilityLabel: index === 0 ? t.booking.todayDate(weekdayLong, day, monthLong) : t.booking.dateValue(weekdayLong, day, monthLong),
      day,
      month,
      value: isoDate,
      weekday: index === 0 ? t.booking.today : weekday,
    };
  });
}

export function minutesToTime(value: number): string {
  const normalized = value % (24 * 60);
  return `${String(Math.floor(normalized / 60)).padStart(2, "0")}:${String(normalized % 60).padStart(2, "0")}`;
}

export function formatTwelveHourTime(value: string): string {
  const [hourText, minuteText] = value.split(":");
  const hour = Number(hourText);
  return `${hour % 12 || 12}:${minuteText} ${hour < 12 ? "AM" : "PM"}`;
}
