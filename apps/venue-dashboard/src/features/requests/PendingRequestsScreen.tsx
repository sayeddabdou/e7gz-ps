import { useState, useSyncExternalStore, type CSSProperties } from "react";
import type { components } from "@e7gz/api-client";
import { webTokens } from "@e7gz/tokens/web";
import { useMessages } from "../../i18n";

type Booking = components["schemas"]["VenueBooking"];

export interface PendingRequestsViewModel {
  bookings: Booking[];
  clockOffsetMs: number;
  loading: boolean;
  error?: string;
  actionError?: string;
  pendingAction?: string;
  streamState: "connected" | "connecting" | "reconnecting" | "stopped";
  accept: (bookingId: string) => void;
  reject: (bookingId: string, reason: string) => void;
  refresh: () => void;
  soundState?: "disabled" | "enabled" | "unsupported";
  pushState?: "denied" | "disabled" | "enabled" | "failed" | "subscribing" | "unsupported";
  enableSound?: () => void;
  enablePush?: () => void;
}

const styles = {
  page: { display: "grid", gap: webTokens.spacing["3xl"] },
  header: { alignItems: "start", display: "flex", flexWrap: "wrap", gap: webTokens.spacing.xl, justifyContent: "space-between" },
  heading: { fontSize: webTokens.typography.fontSize.displaySmall, marginBlock: 0 },
  muted: { color: webTokens.color.text.venueMuted, marginBlock: 0 },
  status: { backgroundColor: webTokens.color.surface.venueSubtle, borderRadius: webTokens.radius.round, color: webTokens.color.text.venueMuted, paddingBlock: webTokens.spacing.xs, paddingInline: webTokens.spacing.lg },
  list: { display: "grid", gap: webTokens.spacing.xl, listStyle: "none", marginBlock: 0, paddingInlineStart: 0 },
  card: { backgroundColor: webTokens.color.surface.raised, border: `${webTokens.spacing.hairline}px solid ${webTokens.color.border.subtle}`, borderRadius: webTokens.radius.xl, boxShadow: webTokens.shadow.card, display: "grid", gap: webTokens.spacing.xl, padding: webTokens.spacing["3xl"] },
  cardHeader: { alignItems: "start", display: "flex", flexWrap: "wrap", gap: webTokens.spacing.lg, justifyContent: "space-between" },
  title: { fontSize: webTokens.typography.fontSize.title, marginBlock: 0 },
  countdown: { color: webTokens.color.brand.venue, fontSize: webTokens.typography.fontSize.title, fontWeight: webTokens.typography.fontWeight.extrabold },
  details: { display: "grid", gap: webTokens.spacing.sm, gridTemplateColumns: "repeat(auto-fit, minmax(10rem, 1fr))" },
  actions: { display: "flex", flexWrap: "wrap", gap: webTokens.spacing.md },
  button: { borderRadius: webTokens.radius.lg, cursor: "pointer", fontWeight: webTokens.typography.fontWeight.bold, paddingBlock: webTokens.spacing.md, paddingInline: webTokens.spacing.xl },
  primary: { backgroundColor: webTokens.color.brand.venue, border: 0, color: webTokens.color.text.inverse },
  danger: { backgroundColor: webTokens.color.surface.raised, border: `${webTokens.spacing.hairline}px solid ${webTokens.color.feedback.danger}`, color: webTokens.color.feedback.danger },
  input: { border: `${webTokens.spacing.hairline}px solid ${webTokens.color.border.venue}`, borderRadius: webTokens.radius.lg, padding: webTokens.spacing.md, width: "100%" },
  reject: { display: "grid", gap: webTokens.spacing.md },
  error: { color: webTokens.color.feedback.danger, marginBlock: 0 },
  notice: { alignItems: "center", backgroundColor: webTokens.color.surface.venueSubtle, borderRadius: webTokens.radius.xl, display: "flex", flexWrap: "wrap", gap: webTokens.spacing.lg, justifyContent: "space-between", padding: webTokens.spacing.xl },
} satisfies Record<string, CSSProperties>;

function remainingLabel(expiresAt: string | null, paused: boolean, clockOffsetMs: number, now: number): string {
  if (paused) return "paused";
  if (!expiresAt) return "00:00";
  const seconds = Math.max(0, Math.ceil((Date.parse(expiresAt) - (now + clockOffsetMs)) / 1_000));
  return `${String(Math.floor(seconds / 60)).padStart(2, "0")}:${String(seconds % 60).padStart(2, "0")}`;
}

let clockNow = Date.now();
let clockInterval: number | undefined;
const clockListeners = new Set<() => void>();

function subscribeClock(notify: () => void): () => void {
  clockListeners.add(notify);
  clockNow = Date.now();
  notify();
  clockInterval ??= window.setInterval(() => {
    clockNow = Date.now();
    clockListeners.forEach((listener) => listener());
  }, 1_000);
  return () => {
    clockListeners.delete(notify);
    if (clockListeners.size === 0 && clockInterval !== undefined) {
      window.clearInterval(clockInterval);
      clockInterval = undefined;
    }
  };
}

const currentTime = () => clockNow;

function RequestCard({ booking, model }: { booking: Booking; model: PendingRequestsViewModel }) {
  const messages = useMessages();
  const t = messages.requests;
  const now = useSyncExternalStore(subscribeClock, currentTime, currentTime);
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState("");
  const [reasonError, setReasonError] = useState(false);
  const countdown = remainingLabel(booking.venueResponseExpiresAt, booking.venueResponsePaused, model.clockOffsetMs, now);
  const busy = model.pendingAction?.startsWith(`${booking.bookingId}:`) ?? false;
  const submitReject = () => {
    const trimmed = reason.trim();
    if (!trimmed) {
      setReasonError(true);
      return;
    }
    setReasonError(false);
    model.reject(booking.bookingId, trimmed);
  };

  return <li style={styles.card}>
    <div style={styles.cardHeader}>
      <div>
        <h3 style={styles.title}>{booking.resource.name}</h3>
        <p style={styles.muted}>{booking.customer?.username ?? t.customerHidden} · {messages.activity[booking.resource.activity]}</p>
      </div>
      <span aria-label={t.timeRemaining} style={styles.countdown}>{countdown === "paused" ? t.paused : countdown}</span>
    </div>
    {booking.venueResponsePaused ? <p style={styles.muted}>{t.acceptPaused}</p> : null}
    <div style={styles.details}>
      <p><strong>{t.starts}</strong><br />{new Date(booking.startAt).toLocaleString(t.locale, { timeZone: "Africa/Cairo" })}</p>
      <p><strong>{t.duration}</strong><br />{t.durationMinutes(booking.nominalBookedMinutes)}</p>
      <p><strong>{t.price}</strong><br />{booking.venuePriceTotal.amount} {booking.venuePriceTotal.currency}</p>
    </div>
    {model.actionError ? <p role="alert" style={styles.error}>{model.actionError}</p> : null}
    {rejecting ? <div style={styles.reject}>
      <label>{t.reason}<textarea aria-label={t.reason} maxLength={200} onChange={(event) => setReason(event.currentTarget.value)} style={styles.input} value={reason} /></label>
      {reasonError ? <p role="alert" style={styles.error}>{t.reasonRequired}</p> : null}
      <div style={styles.actions}>
        <button disabled={busy} onClick={submitReject} style={{ ...styles.button, ...styles.danger }} type="button">{busy ? t.working : t.confirmReject}</button>
        <button disabled={busy} onClick={() => { setRejecting(false); setReasonError(false); }} style={{ ...styles.button, ...styles.primary }} type="button">{t.keepPending}</button>
      </div>
    </div> : <div style={styles.actions}>
      <button disabled={busy || booking.venueResponsePaused} onClick={() => model.accept(booking.bookingId)} style={{ ...styles.button, ...styles.primary }} type="button">{busy ? t.working : t.accept}</button>
      <button disabled={busy} onClick={() => setRejecting(true)} style={{ ...styles.button, ...styles.danger }} type="button">{t.reject}</button>
    </div>}
  </li>;
}

export function PendingRequestsScreen({ model }: { model: PendingRequestsViewModel }) {
  const t = useMessages().requests;
  return <section style={styles.page}>
    <header style={styles.header}>
      <div><h2 style={styles.heading}>{t.title}</h2><p style={styles.muted}>{t.description}</p></div>
      <span role="status" style={styles.status}>{t.stream[model.streamState]}</span>
    </header>
    {model.soundState ? <div role="status" style={styles.notice}>
      <p>{t.sound[model.soundState]}</p>
      {model.soundState === "disabled" ? <button onClick={model.enableSound} style={{ ...styles.button, ...styles.primary }} type="button">{t.sound.enable}</button> : null}
    </div> : null}
    {model.pushState ? <div role="status" style={styles.notice}>
      <p>{t.push[model.pushState]}</p>
      {model.pushState !== "enabled" && model.pushState !== "unsupported" && model.pushState !== "denied" ? <button disabled={model.pushState === "subscribing"} onClick={model.enablePush} style={{ ...styles.button, ...styles.primary }} type="button">{t.push.enable}</button> : null}
    </div> : null}
    {model.loading ? <p role="status">{t.loading}</p> : model.error ? <div><p role="alert" style={styles.error}>{model.error}</p><button onClick={model.refresh} style={{ ...styles.button, ...styles.primary }} type="button">{t.retry}</button></div> : model.bookings.length === 0 ? <p>{t.empty}</p> : <ul style={styles.list}>{model.bookings.map((booking) => <RequestCard booking={booking} key={booking.bookingId} model={model} />)}</ul>}
  </section>;
}
