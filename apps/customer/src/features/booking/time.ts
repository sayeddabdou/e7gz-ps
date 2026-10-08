const cairoParts = new Intl.DateTimeFormat("en-CA", {
  timeZone: "Africa/Cairo",
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
  second: "2-digit",
  hourCycle: "h23",
});

type LocalParts = { year: number; month: number; day: number; hour: number; minute: number; second: number };

function partsAt(timestamp: number): LocalParts {
  const values = Object.fromEntries(
    cairoParts.formatToParts(new Date(timestamp))
      .filter((part) => part.type !== "literal")
      .map((part) => [part.type, Number(part.value)]),
  );
  return {
    year: values.year,
    month: values.month,
    day: values.day,
    hour: values.hour,
    minute: values.minute,
    second: values.second,
  } as LocalParts;
}

/** Converts a customer-selected Cairo wall time without consulting the device timezone (CLAUDE.md §5). */
export function cairoLocalToInstant(date: string, time: string): string | undefined {
  const dateMatch = /^(\d{4})-(\d{2})-(\d{2})$/u.exec(date);
  const timeMatch = /^(\d{2}):(\d{2})$/u.exec(time);
  if (!dateMatch || !timeMatch) return undefined;
  const wanted: LocalParts = {
    year: Number(dateMatch[1]),
    month: Number(dateMatch[2]),
    day: Number(dateMatch[3]),
    hour: Number(timeMatch[1]),
    minute: Number(timeMatch[2]),
    second: 0,
  };
  if (wanted.month < 1 || wanted.month > 12 || wanted.day < 1 || wanted.day > 31 || wanted.hour > 23 || wanted.minute > 59) return undefined;

  const wallClockAsUtc = Date.UTC(wanted.year, wanted.month - 1, wanted.day, wanted.hour, wanted.minute, 0);
  let candidate = wallClockAsUtc;
  for (let attempt = 0; attempt < 3; attempt += 1) {
    const observed = partsAt(candidate);
    const observedAsUtc = Date.UTC(observed.year, observed.month - 1, observed.day, observed.hour, observed.minute, observed.second);
    candidate -= observedAsUtc - wallClockAsUtc;
  }
  const roundTrip = partsAt(candidate);
  if (Object.keys(wanted).some((key) => wanted[key as keyof LocalParts] !== roundTrip[key as keyof LocalParts])) return undefined;
  return new Date(candidate).toISOString();
}

/** NFR-10: the UI derives remaining time from server-provided instants. */
export function countdownSeconds(expiresAt: string, serverTime: string): number {
  return Math.max(0, Math.ceil((Date.parse(expiresAt) - Date.parse(serverTime)) / 1000));
}

/** Keeps the server clock authoritative while allowing the UI to refresh from the advancing device clock. */
export function serverClockOffset(serverTime: string, deviceTime: number): number {
  return Date.parse(serverTime) - deviceTime;
}

export function countdownSecondsAt(expiresAt: string, currentServerTime: number): number {
  return Math.max(0, Math.ceil((Date.parse(expiresAt) - currentServerTime) / 1000));
}

export function formatCountdown(seconds: number): string {
  const minutes = Math.floor(seconds / 60).toString().padStart(2, "0");
  const remainder = (seconds % 60).toString().padStart(2, "0");
  return `${minutes}:${remainder}`;
}
