export const cairoTimeZone = "Africa/Cairo";

type Money = { amount: string; currency: string };

function localizedDecimal(value: string, locale: string): string {
  const match = /^(-?)(\d+)(?:\.(\d+))?$/u.exec(value);
  if (!match) return value;
  const [, sign, integer, fraction] = match;
  const digitFormatter = new Intl.NumberFormat(locale, { useGrouping: false });
  const digits = Array.from({ length: 10 }, (_, digit) => digitFormatter.format(digit));
  const localizeDigits = (part: string) => part.replace(/\d/gu, (digit) => digits[Number(digit)]!);
  const minusSign = new Intl.NumberFormat(locale).formatToParts(-1).find((part) => part.type === "minusSign")?.value ?? "-";
  const decimal = new Intl.NumberFormat(locale).formatToParts(1.1).find((part) => part.type === "decimal")?.value ?? ".";
  return `${sign ? minusSign : ""}${localizeDigits(integer!)}${fraction === undefined ? "" : `${decimal}${localizeDigits(fraction)}`}`;
}

export function formatNumber(value: number, locale: string, options?: Intl.NumberFormatOptions): string {
  return new Intl.NumberFormat(locale, options).format(value);
}

export function formatInteger(value: number, locale: string): string {
  return formatNumber(value, locale, { maximumFractionDigits: 0 });
}

export function formatCountdown(totalSeconds: number, locale: string, includeHours = false): string {
  const segment = (value: number) => formatNumber(value, locale, {
    maximumFractionDigits: 0,
    minimumIntegerDigits: 2,
    useGrouping: false,
  });
  const hours = Math.floor(totalSeconds / 3_600);
  const minutes = Math.floor((totalSeconds % 3_600) / 60);
  const seconds = totalSeconds % 60;
  return includeHours || hours > 0
    ? `${segment(hours)}:${segment(minutes)}:${segment(seconds)}`
    : `${segment(minutes)}:${segment(seconds)}`;
}

export function formatMoney(money: Money, locale: string): string {
  return `${localizedDecimal(money.amount, locale)} ${money.currency}`;
}

export function formatDateTime(instant: string, locale: string, timeZone = cairoTimeZone): string {
  return new Intl.DateTimeFormat(locale, {
    dateStyle: "medium",
    timeStyle: "short",
    timeZone,
  }).format(new Date(instant));
}

function localParts(instant: Date, timeZone: string): Record<string, string> {
  return Object.fromEntries(new Intl.DateTimeFormat("en-CA", {
    day: "2-digit",
    hour: "2-digit",
    hourCycle: "h23",
    minute: "2-digit",
    month: "2-digit",
    second: "2-digit",
    timeZone,
    year: "numeric",
  }).formatToParts(instant).map((part) => [part.type, part.value]));
}

/** Renders an API instant for a datetime-local field without using the device time zone. */
export function instantToLocalDateTime(instant: string, timeZone = cairoTimeZone): string {
  const parts = localParts(new Date(instant), timeZone);
  return `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}`;
}

/** Converts a datetime-local value to an API instant without using the device time zone. */
export function localDateTimeToInstant(value: string, timeZone = cairoTimeZone): string | undefined {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/u.exec(value);
  if (!match) return undefined;
  const [, year, month, day, hour, minute] = match;
  const wallTime = Date.UTC(Number(year), Number(month) - 1, Number(day), Number(hour), Number(minute));
  let instant = wallTime;
  for (let attempt = 0; attempt < 3; attempt += 1) {
    const parts = localParts(new Date(instant), timeZone);
    const renderedWallTime = Date.UTC(Number(parts.year), Number(parts.month) - 1, Number(parts.day), Number(parts.hour), Number(parts.minute), Number(parts.second));
    instant = wallTime - (renderedWallTime - instant);
  }
  return new Date(instant).toISOString();
}

export function formatDate(instant: string | number, locale: string, timeZone = cairoTimeZone): string {
  return new Intl.DateTimeFormat(locale, {
    dateStyle: "medium",
    timeZone,
  }).format(typeof instant === "number" ? instant : new Date(instant));
}

export function formatTime(instant: string | number, locale: string, timeZone = cairoTimeZone): string {
  return new Intl.DateTimeFormat(locale, {
    hour: "numeric",
    minute: "2-digit",
    timeZone,
  }).format(typeof instant === "number" ? instant : new Date(instant));
}

export function formatBusinessDate(date: string, locale: string): string {
  return new Intl.DateTimeFormat(locale, {
    dateStyle: "long",
    timeZone: cairoTimeZone,
  }).format(new Date(`${date}T12:00:00Z`));
}
