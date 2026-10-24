import { useCallback, useEffect, useState } from "react";
import { resendDeadline, secondsUntil, serverClockOffset } from "../auth/api";

export function useResendCountdown() {
  const [deadline, setDeadline] = useState<string>();
  const [clockOffset, setClockOffset] = useState(0);
  const [remaining, setRemaining] = useState(0);

  useEffect(() => {
    if (!deadline) return undefined;
    const updateRemaining = () => setRemaining(secondsUntil(deadline, Date.now(), clockOffset));
    updateRemaining();
    const timer = setInterval(updateRemaining, 1_000);
    return () => clearInterval(timer);
  }, [clockOffset, deadline]);

  const start = useCallback((serverTime?: string) => {
    if (!serverTime) {
      setDeadline(undefined);
      setRemaining(0);
      return;
    }
    const deviceNow = Date.now();
    setClockOffset(serverClockOffset(serverTime, deviceNow));
    setDeadline(resendDeadline(serverTime));
  }, []);

  return { remaining, start };
}
