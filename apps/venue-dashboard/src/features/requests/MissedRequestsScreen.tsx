import { useCallback, useEffect, useRef, useState, type CSSProperties } from "react";
import type { components } from "@e7gz/api-client";
import { webTokens } from "@e7gz/tokens/web";
import { apiErrorOf, type ApiClient } from "../../api";
import { useMessages } from "../../i18n";
import { createIdempotencyKey, serverClockOffset, useServerNow } from "../operations";

type Booking = components["schemas"]["VenueBooking"];

const styles = {
  page: { display: "grid", gap: webTokens.spacing["3xl"] },
  heading: { fontSize: webTokens.typography.fontSize.displaySmall, marginBlock: 0 },
  muted: { color: webTokens.color.text.venueMuted, marginBlock: 0 },
  list: { display: "grid", gap: webTokens.spacing.xl, listStyle: "none", marginBlock: 0, paddingInlineStart: 0 },
  card: { backgroundColor: webTokens.color.surface.raised, border: `${webTokens.spacing.hairline}px solid ${webTokens.color.border.subtle}`, borderRadius: webTokens.radius.xl, boxShadow: webTokens.shadow.card, display: "grid", gap: webTokens.spacing.xl, padding: webTokens.spacing["3xl"] },
  cardHeader: { alignItems: "start", display: "flex", flexWrap: "wrap", gap: webTokens.spacing.lg, justifyContent: "space-between" },
  title: { fontSize: webTokens.typography.fontSize.title, marginBlock: 0 },
  countdown: { color: webTokens.color.brand.venue, fontWeight: webTokens.typography.fontWeight.extrabold },
  details: { display: "grid", gap: webTokens.spacing.sm, gridTemplateColumns: "repeat(auto-fit, minmax(10rem, 1fr))" },
  actions: { display: "flex", flexWrap: "wrap", gap: webTokens.spacing.md },
  button: { borderRadius: webTokens.radius.lg, cursor: "pointer", fontWeight: webTokens.typography.fontWeight.bold, paddingBlock: webTokens.spacing.md, paddingInline: webTokens.spacing.xl },
  primary: { backgroundColor: webTokens.color.brand.venue, border: 0, color: webTokens.color.text.inverse },
  danger: { backgroundColor: webTokens.color.surface.raised, border: `${webTokens.spacing.hairline}px solid ${webTokens.color.feedback.danger}`, color: webTokens.color.feedback.danger },
  error: { backgroundColor: webTokens.color.feedback.dangerSurface, borderRadius: webTokens.radius.lg, color: webTokens.color.feedback.danger, marginBlock: 0, padding: webTokens.spacing.lg },
  confirmation: { backgroundColor: webTokens.color.surface.venueSubtle, borderRadius: webTokens.radius.xl, display: "grid", gap: webTokens.spacing.md, padding: webTokens.spacing.lg },
} satisfies Record<string, CSSProperties>;

function timeLeftLabel(startAt: string, serverNow: number, started: string): string {
  const seconds = Math.max(0, Math.floor((Date.parse(startAt) - serverNow) / 1_000));
  if (seconds === 0) return started;
  const hours = Math.floor(seconds / 3_600);
  const minutes = Math.floor((seconds % 3_600) / 60);
  const remainder = seconds % 60;
  return hours > 0
    ? `${String(hours).padStart(2, "0")}:${String(minutes).padStart(2, "0")}:${String(remainder).padStart(2, "0")}`
    : `${String(minutes).padStart(2, "0")}:${String(remainder).padStart(2, "0")}`;
}

export function MissedRequestsScreen({
  api,
  bookingChangeVersion,
  pollMilliseconds = 30_000,
}: {
  api: ApiClient;
  bookingChangeVersion: number;
  pollMilliseconds?: number;
}) {
  const messages = useMessages();
  const t = messages.missed;
  const [bookings, setBookings] = useState<Booking[]>();
  const [clockOffsetMs, setClockOffsetMs] = useState(0);
  const [error, setError] = useState<string>();
  const [actionError, setActionError] = useState<string>();
  const [actionSuccess, setActionSuccess] = useState<string>();
  const [busy, setBusy] = useState<string>();
  const [confirmingReject, setConfirmingReject] = useState<string>();
  const actionKeys = useRef(new Map<string, string>());
  const busyRef = useRef(false);
  const serverNow = useServerNow(clockOffsetMs);

  const messageFor = useCallback((value: unknown) => {
    const code = apiErrorOf(value).code;
    return code && code in t.errors ? t.errors[code as keyof typeof t.errors] : t.errors.unexpected;
  }, [t]);

  const refresh = useCallback(async () => {
    try {
      const result = await api.GET("/api/v1/venue/requests/missed");
      if (!result.data) return setError(messageFor(result.error));
      setBookings(result.data.data);
      setClockOffsetMs(serverClockOffset(result.data.meta.serverTime));
      setError(undefined);
    } catch {
      setError(t.errors.network);
    }
  }, [api, messageFor, t]);

  useEffect(() => {
    const timeout = window.setTimeout(() => { void refresh(); }, 0);
    return () => window.clearTimeout(timeout);
  }, [bookingChangeVersion, refresh]);
  useEffect(() => {
    const interval = window.setInterval(() => { void refresh(); }, pollMilliseconds);
    return () => window.clearInterval(interval);
  }, [pollMilliseconds, refresh]);

  const act = useCallback(async (bookingId: string, action: "late-confirm" | "reject-after-expiry") => {
    if (busyRef.current) return;
    busyRef.current = true;
    const signature = `${bookingId}:${action}`;
    setBusy(signature);
    setActionError(undefined);
    setActionSuccess(undefined);
    try {
      const key = actionKeys.current.get(signature) ?? createIdempotencyKey();
      actionKeys.current.set(signature, key);
      const result = action === "late-confirm"
        ? await api.POST("/api/v1/venue/bookings/{bookingId}/late-confirm", { params: { header: { "Idempotency-Key": key }, path: { bookingId } } })
        : await api.POST("/api/v1/venue/bookings/{bookingId}/reject-after-expiry", { params: { header: { "Idempotency-Key": key }, path: { bookingId } } });
      if (!result.data) {
        setActionError(messageFor(result.error));
        if (result.response?.status === 409 || result.response?.status === 422) await refresh();
        return;
      }
      actionKeys.current.delete(signature);
      setConfirmingReject(undefined);
      await refresh();
      setActionSuccess(action === "late-confirm" ? t.lateConfirmSent : t.rejected);
    } catch {
      setActionError(t.errors.network);
    } finally {
      busyRef.current = false;
      setBusy(undefined);
    }
  }, [api, messageFor, refresh, t]);

  return <section style={styles.page}>
    <header><h2 style={styles.heading}>{t.title}</h2><p style={styles.muted}>{t.description}</p></header>
    {actionError ? <p role="alert" style={styles.error}>{actionError}</p> : null}
    {actionSuccess ? <p role="status">{actionSuccess}</p> : null}
    {!bookings ? error ? <div><p role="alert" style={styles.error}>{error}</p><button onClick={() => void refresh()} style={{ ...styles.button, ...styles.primary }} type="button">{t.retry}</button></div> : <p role="status">{t.loading}</p>
      : bookings.length === 0 ? <p>{t.empty}</p>
        : <ul style={styles.list}>{bookings.map((booking) => {
          const started = serverNow >= Date.parse(booking.startAt);
          const cardBusy = busy?.startsWith(`${booking.bookingId}:`) ?? false;
          return <li key={booking.bookingId} style={styles.card}>
            <div style={styles.cardHeader}><div><h3 style={styles.title}>{booking.resource.name}</h3><p style={styles.muted}>{booking.customer?.username ?? t.customerHidden}</p>{booking.customer?.phone ? <p style={styles.muted}>{booking.customer.phone}</p> : null}</div><span aria-label={t.timeToStart} style={styles.countdown}>{timeLeftLabel(booking.startAt, serverNow, t.started)}</span></div>
            <div style={styles.details}><p><strong>{t.starts}</strong><br />{new Date(booking.startAt).toLocaleString(t.locale, { timeZone: "Africa/Cairo" })}</p><p><strong>{t.duration}</strong><br />{messages.requests.durationMinutes(booking.nominalBookedMinutes)}</p><p><strong>{t.price}</strong><br />{booking.venuePriceTotal.amount} {booking.venuePriceTotal.currency}</p></div>
            {confirmingReject === booking.bookingId ? <div style={styles.confirmation}><strong>{t.reject}</strong><div style={styles.actions}><button disabled={cardBusy || started} onClick={() => void act(booking.bookingId, "reject-after-expiry")} style={{ ...styles.button, ...styles.danger }} type="button">{cardBusy ? t.working : t.confirmReject}</button><button disabled={cardBusy} onClick={() => setConfirmingReject(undefined)} style={{ ...styles.button, ...styles.primary }} type="button">{t.keepRequest}</button></div></div>
              : <div style={styles.actions}><button disabled={cardBusy || started} onClick={() => void act(booking.bookingId, "late-confirm")} style={{ ...styles.button, ...styles.primary }} type="button">{cardBusy ? t.working : t.lateConfirm}</button><button disabled={cardBusy || started} onClick={() => setConfirmingReject(booking.bookingId)} style={{ ...styles.button, ...styles.danger }} type="button">{t.reject}</button></div>}
          </li>;
        })}</ul>}
  </section>;
}
