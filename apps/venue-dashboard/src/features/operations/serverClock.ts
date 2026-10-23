import { useEffect, useState } from "react";

export function serverClockOffset(serverTime: string): number {
  return Date.parse(serverTime) - Date.now();
}

export function useServerNow(clockOffsetMs: number): number {
  const [deviceNow, setDeviceNow] = useState(() => Date.now());
  useEffect(() => {
    const interval = window.setInterval(() => setDeviceNow(Date.now()), 1_000);
    return () => window.clearInterval(interval);
  }, []);
  return deviceNow + clockOffsetMs;
}

export function isInsideWindow(window: { opensAt: string; closesAt: string }, serverNow: number): boolean {
  return Date.parse(window.opensAt) <= serverNow && serverNow < Date.parse(window.closesAt);
}
