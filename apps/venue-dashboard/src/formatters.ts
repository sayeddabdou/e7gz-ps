const cairoTimeZone = "Africa/Cairo";

type Money = { amount: string; currency: string };

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
  const amount = Number(money.amount);
  const formattedAmount = formatNumber(amount, locale, {
    maximumFractionDigits: 2,
    minimumFractionDigits: 2,
  });
  return `${formattedAmount} ${money.currency}`;
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
