const cairoTimeZone = "Africa/Cairo";

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

export function formatDateTime(instant: string, locale: string): string {
  return new Intl.DateTimeFormat(locale, {
    dateStyle: "medium",
    timeStyle: "short",
    timeZone: cairoTimeZone,
  }).format(new Date(instant));
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
