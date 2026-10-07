import type { components } from "@e7gz/api-client";

type Money = components["schemas"]["Money"];

export const formatMoney = (money: Money | null) => money ? `${money.amount} ${money.currency}` : undefined;

export const formatDistance = (meters: number | null) => meters === null
  ? undefined
  : meters < 1000 ? `${meters} m` : `${(meters / 1000).toFixed(1)} km`;

export const formatInstant = (instant: string | null) => instant
  ? new Intl.DateTimeFormat("en-EG", {
      timeZone: "Africa/Cairo",
      month: "short",
      day: "numeric",
      hour: "numeric",
      minute: "2-digit",
      hour12: true,
    }).format(new Date(instant))
  : undefined;

export const formatInstantTime = (instant: string | null) => instant
  ? new Intl.DateTimeFormat("en-EG", {
      timeZone: "Africa/Cairo",
      hour: "numeric",
      minute: "2-digit",
      hour12: true,
    }).format(new Date(instant))
  : undefined;

export function formatLocalTime(value: string | null): string | undefined {
  if (!value) return undefined;
  const [hourText, minute = "00"] = value.split(":");
  const hour = Number(hourText);
  const suffix = hour >= 12 ? "PM" : "AM";
  const clockHour = hour % 12 || 12;
  return `${clockHour}:${minute} ${suffix}`;
}
