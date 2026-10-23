import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties } from "react";
import type { components } from "@e7gz/api-client";
import { webTokens } from "@e7gz/tokens/web";
import { apiErrorOf, type ApiClient } from "../../api";
import { useMessages } from "../../i18n";
import { bookingErrorMessage } from "../walk-in/errorMessages";
import { createIdempotencyKey, isInsideWindow, serverClockOffset, useServerNow } from "../operations";

type Booking = components["schemas"]["VenueBooking"];
type BookingDetail = components["schemas"]["VenueBookingDetail"];

const styles = {
  page: { display: "grid", gap: webTokens.spacing["4xl"] },
  header: { alignItems: "start", display: "flex", flexWrap: "wrap", gap: webTokens.spacing.xl, justifyContent: "space-between" },
  heading: { fontSize: webTokens.typography.fontSize.displaySmall, marginBlock: 0 },
  intro: { color: webTokens.color.text.venueMuted, marginBlock: 0 },
  date: { backgroundColor: webTokens.color.surface.venueSubtle, borderRadius: webTokens.radius.round, color: webTokens.color.text.venueMuted, fontWeight: webTokens.typography.fontWeight.bold, paddingBlock: webTokens.spacing.sm, paddingInline: webTokens.spacing.xl },
  groups: { display: "grid", gap: webTokens.spacing["3xl"] },
  group: { backgroundColor: webTokens.color.surface.raised, border: `${webTokens.spacing.hairline}px solid ${webTokens.color.border.subtle}`, borderRadius: webTokens.radius["3xl"], boxShadow: webTokens.shadow.card, display: "grid", gap: webTokens.spacing.xl, padding: webTokens.spacing["3xl"] },
  groupHeading: { fontSize: webTokens.typography.fontSize.title, marginBlock: 0 },
  list: { display: "grid", gap: webTokens.spacing.md, listStyle: "none", marginBlock: 0, paddingInlineStart: 0 },
  booking: { alignItems: "center", backgroundColor: webTokens.color.surface.venueSubtle, border: `${webTokens.spacing.hairline}px solid ${webTokens.color.border.subtle}`, borderRadius: webTokens.radius.xl, display: "grid", gap: webTokens.spacing.lg, gridTemplateColumns: "repeat(auto-fit, minmax(9rem, 1fr))", padding: webTokens.spacing.xl },
  status: { color: webTokens.color.brand.venue, fontWeight: webTokens.typography.fontWeight.bold },
  button: { backgroundColor: webTokens.color.surface.raised, border: `${webTokens.spacing.hairline}px solid ${webTokens.color.border.venue}`, borderRadius: webTokens.radius.lg, color: webTokens.color.brand.venue, cursor: "pointer", fontWeight: webTokens.typography.fontWeight.bold, paddingBlock: webTokens.spacing.md, paddingInline: webTokens.spacing.xl },
  error: { backgroundColor: webTokens.color.feedback.dangerSurface, borderRadius: webTokens.radius.lg, color: webTokens.color.feedback.danger, padding: webTokens.spacing.xl },
  detailCard: { backgroundColor: webTokens.color.surface.raised, border: `${webTokens.spacing.hairline}px solid ${webTokens.color.border.subtle}`, borderRadius: webTokens.radius["3xl"], boxShadow: webTokens.shadow.card, display: "grid", gap: webTokens.spacing.xl, maxWidth: "54rem", padding: webTokens.spacing["4xl"] },
  details: { display: "grid", gap: webTokens.spacing.xl, gridTemplateColumns: "repeat(auto-fit, minmax(13rem, 1fr))", marginBlock: 0 },
  detailItem: { borderBlockEnd: `${webTokens.spacing.hairline}px solid ${webTokens.color.border.subtle}`, paddingBlockEnd: webTokens.spacing.lg },
  term: { color: webTokens.color.text.venueMuted, fontSize: webTokens.typography.fontSize.label, fontWeight: webTokens.typography.fontWeight.bold },
  value: { marginBlockStart: webTokens.spacing.sm },
  contact: { backgroundColor: webTokens.color.surface.venueSubtle, borderRadius: webTokens.radius.xl, display: "grid", gap: webTokens.spacing.sm, padding: webTokens.spacing.xl },
  history: { display: "grid", gap: webTokens.spacing.md, listStyle: "none", marginBlock: 0, paddingInlineStart: 0 },
  historyItem: { borderBlockStart: `${webTokens.spacing.hairline}px solid ${webTokens.color.border.subtle}`, paddingBlockStart: webTokens.spacing.md },
  actions: { backgroundColor: webTokens.color.surface.venueSubtle, borderRadius: webTokens.radius.xl, display: "grid", gap: webTokens.spacing.lg, padding: webTokens.spacing.xl },
  actionRow: { display: "flex", flexWrap: "wrap", gap: webTokens.spacing.md },
  dangerButton: { backgroundColor: webTokens.color.surface.raised, border: `${webTokens.spacing.hairline}px solid ${webTokens.color.feedback.danger}`, borderRadius: webTokens.radius.lg, color: webTokens.color.feedback.danger, cursor: "pointer", fontWeight: webTokens.typography.fontWeight.bold, paddingBlock: webTokens.spacing.md, paddingInline: webTokens.spacing.xl },
  input: { border: `${webTokens.spacing.hairline}px solid ${webTokens.color.border.venue}`, borderRadius: webTokens.radius.lg, font: "inherit", paddingBlock: webTokens.spacing.md, paddingInline: webTokens.spacing.lg },
} satisfies Record<string, CSSProperties>;

function timeLabel(instant: string, locale: string): string {
  return new Date(instant).toLocaleTimeString(locale, { hour: "numeric", minute: "2-digit", timeZone: "Africa/Cairo" });
}

function dateTimeLabel(instant: string, locale: string): string {
  return new Date(instant).toLocaleString(locale, { dateStyle: "medium", timeStyle: "short", timeZone: "Africa/Cairo" });
}

function businessDateLabel(date: string, locale: string): string {
  return new Date(`${date}T12:00:00Z`).toLocaleDateString(locale, { dateStyle: "long", timeZone: "Africa/Cairo" });
}

function personLabel(booking: Booking, fallback: string): string {
  return booking.customer?.username ?? booking.walkIn?.name ?? fallback;
}

async function loadToday(api: ApiClient): Promise<{ bookings: Booking[]; businessDate: string; error?: unknown }> {
  // BUILD-PLAN E7-31 / FR-AVL-14: only the server knows whether now belongs to an overnight Operating Session.
  const schedule = await api.GET("/api/v1/venue/availability");
  if (!schedule.data) return { bookings: [], businessDate: "", error: schedule.error };
  const businessDate = schedule.data.data.businessDate;
  const bookings = new Map<string, Booking>();
  const seenCursors = new Set<string>();
  let cursor: string | undefined;
  do {
    const page = await api.GET("/api/v1/venue/bookings", {
      params: { query: { from: businessDate, limit: 100, to: businessDate, ...(cursor ? { cursor } : {}) } },
    });
    if (!page.data) return { bookings: [], businessDate, error: page.error };
    page.data.data.items.forEach((booking) => bookings.set(booking.bookingId, booking));
    const nextCursor = page.data.data.nextCursor ?? undefined;
    if (!nextCursor || seenCursors.has(nextCursor)) break;
    seenCursors.add(nextCursor);
    cursor = nextCursor;
  } while (cursor);
  return { bookings: Array.from(bookings.values()), businessDate };
}

export function TodayBookingsScreen({ api, onOpenBooking }: { api: ApiClient; onOpenBooking: (bookingId: string) => void }) {
  const messages = useMessages();
  const t = messages.bookings.today;
  const [bookings, setBookings] = useState<Booking[]>();
  const [businessDate, setBusinessDate] = useState("");
  const [error, setError] = useState<string>();
  const [reload, setReload] = useState(0);
  const [refreshing, setRefreshing] = useState(false);

  useEffect(() => {
    let active = true;
    void loadToday(api).then((result) => {
      if (!active) return;
      setRefreshing(false);
      if (result.error) return setError(bookingErrorMessage(apiErrorOf(result.error).code, messages));
      setError(undefined);
      setBusinessDate(result.businessDate);
      setBookings(result.bookings);
    }).catch(() => { if (active) { setRefreshing(false); setError(messages.bookings.errors.network); } });
    return () => { active = false; };
  }, [api, messages, reload]);

  const groups = useMemo(() => {
    const grouped = new Map<string, { id: string; name: string; bookings: Booking[] }>();
    for (const booking of bookings ?? []) {
      const group = grouped.get(booking.resource.id) ?? { id: booking.resource.id, name: booking.resource.name, bookings: [] };
      group.bookings.push(booking);
      grouped.set(booking.resource.id, group);
    }
    return Array.from(grouped.values())
      .map((group) => ({ ...group, bookings: [...group.bookings].sort((left, right) => left.startAt.localeCompare(right.startAt)) }))
      .sort((left, right) => left.name.localeCompare(right.name, t.locale, { numeric: true }));
  }, [bookings, t.locale]);

  const refresh = () => {
    setRefreshing(true);
    setError(undefined);
    setBookings(undefined);
    setReload((value) => value + 1);
  };

  return <section style={styles.page}>
    <header style={styles.header}><div><h2 style={styles.heading}>{t.title}</h2><p style={styles.intro}>{t.description}</p></div><div>{businessDate ? <time dateTime={businessDate} style={styles.date}>{businessDateLabel(businessDate, t.locale)}</time> : null} <button disabled={refreshing} onClick={refresh} style={styles.button} type="button">{refreshing ? t.refreshing : t.refresh}</button></div></header>
    {!bookings ? error ? <div><p role="alert" style={styles.error}>{error}</p><button onClick={refresh} style={styles.button} type="button">{t.retry}</button></div> : <p role="status">{refreshing ? t.refreshing : t.loading}</p> : bookings.length === 0 ? <p>{t.empty}</p> : <div style={styles.groups}>{groups.map((group) => <section key={group.id} style={styles.group}><h3 style={styles.groupHeading}>{group.name}</h3><ul style={styles.list}>{group.bookings.map((booking) => <li key={booking.bookingId} style={styles.booking}><div><strong>{timeLabel(booking.startAt, t.locale)}–{timeLabel(booking.endAt, t.locale)}</strong><br /><span>{personLabel(booking, t.walkIn)}</span></div><span>{messages.activity[booking.resource.activity]}</span><span style={styles.status}>{t.statuses[booking.status]}</span><button onClick={() => onOpenBooking(booking.bookingId)} style={styles.button} type="button">{t.view}</button></li>)}</ul></section>)}</div>}
  </section>;
}

function DetailItem({ label, value }: { label: string; value: string }) {
  return <div style={styles.detailItem}><dt style={styles.term}>{label}</dt><dd style={styles.value}>{value}</dd></div>;
}

export function BookingDetailScreen({ api, backLabel, bookingId, onBack }: { api: ApiClient; backLabel?: string; bookingId: string; onBack: () => void }) {
  const messages = useMessages();
  const t = messages.bookings.detail;
  const [detail, setDetail] = useState<BookingDetail>();
  const [error, setError] = useState<string>();
  const [reload, setReload] = useState(0);
  const [clockOffsetMs, setClockOffsetMs] = useState(0);

  const load = useCallback(() => {
    void api.GET("/api/v1/venue/bookings/{bookingId}", { params: { path: { bookingId } } }).then(({ data, error: body }) => {
      if (!data) return setError(bookingErrorMessage(apiErrorOf(body).code, messages));
      setDetail(data.data);
      setClockOffsetMs(serverClockOffset(data.meta.serverTime));
      setError(undefined);
    }).catch(() => setError(messages.bookings.errors.network));
  }, [api, bookingId, messages]);

  useEffect(() => { load(); }, [load, reload]);

  if (!detail) return <section style={styles.page}><button onClick={onBack} style={styles.button} type="button">{backLabel ?? t.back}</button>{error ? <div><p role="alert" style={styles.error}>{error}</p><button onClick={() => { setError(undefined); setReload((value) => value + 1); }} style={styles.button} type="button">{t.retry}</button></div> : <p role="status">{t.loading}</p>}</section>;
  const booking = detail.booking;
  const contactName = booking.customer?.username ?? booking.walkIn?.name;
  const contactPhone = booking.customer?.phone ?? booking.walkIn?.phone;

  return <section style={styles.page}>
    <div><button onClick={onBack} style={styles.button} type="button">{backLabel ?? t.back}</button></div>
    <header><h2 style={styles.heading}>{t.title}</h2><p style={styles.intro}>{booking.resource.name}</p></header>
    <article style={styles.detailCard}>
      <dl style={styles.details}>
        <DetailItem label={t.status} value={messages.bookings.today.statuses[booking.status]} />
        <DetailItem label={t.source} value={booking.source === "MANUAL" ? t.manual : t.online} />
        <DetailItem label={t.activity} value={messages.activity[booking.resource.activity]} />
        <DetailItem label={t.businessDate} value={booking.businessDate} />
        <DetailItem label={t.start} value={dateTimeLabel(booking.startAt, t.locale)} />
        <DetailItem label={t.end} value={dateTimeLabel(booking.endAt, t.locale)} />
        <DetailItem label={t.duration} value={t.durationMinutes(booking.nominalBookedMinutes)} />
        <DetailItem label={t.price} value={`${booking.venuePriceTotal.amount} ${booking.venuePriceTotal.currency}`} />
        {contactName ? <DetailItem label={t.customer} value={contactName} /> : null}
        {booking.terminalReason ? <DetailItem label={t.terminalReason} value={t.terminalReasons[booking.terminalReason]} /> : null}
        {booking.rejectReasonText ? <DetailItem label={t.rejectReason} value={booking.rejectReasonText} /> : null}
      </dl>
      {contactPhone ? <section style={styles.contact}><h3 style={styles.groupHeading}>{t.contact}</h3><p><strong>{t.customerPhone}</strong><br />{contactPhone}</p></section> : null}
      <BookingActions api={api} booking={booking} clockOffsetMs={clockOffsetMs} onChanged={() => setReload((value) => value + 1)} />
      {detail.history.length ? <section><h3 style={styles.groupHeading}>{t.history}</h3><ul style={styles.history}>{detail.history.map((entry, index) => <li key={`${entry.at}-${index}`} style={styles.historyItem}><time dateTime={entry.at}>{dateTimeLabel(entry.at, t.locale)}</time> · {entry.toStatus ? messages.bookings.today.statuses[entry.toStatus] : entry.terminalReason ? t.terminalReasons[entry.terminalReason] : entry.eventType ? t.events[entry.eventType] : t.bookingUpdate}</li>)}</ul></section> : null}
    </article>
  </section>;
}

type BookingAction = "check-in" | "late-present" | "no-show" | "cancel-manual" | "cancel-confirmed";

function BookingActions({ api, booking, clockOffsetMs, onChanged }: { api: ApiClient; booking: Booking; clockOffsetMs: number; onChanged: () => void }) {
  const messages = useMessages();
  const t = messages.bookings.detail;
  const now = useServerNow(clockOffsetMs);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const [success, setSuccess] = useState<string>();
  const [confirming, setConfirming] = useState<"no-show" | "cancel-manual" | "cancel-confirmed">();
  const [ownerPin, setOwnerPin] = useState("");
  const keys = useRef(new Map<string, string>());
  const busyRef = useRef(false);

  const attendance = booking.attendance;
  const attendanceRecorded = Boolean(attendance?.checkedInAt || attendance?.latePresentAt || attendance?.noShowAt);
  const attendanceStatusAllowsAction = booking.status === "CONFIRMED" || booking.status === "ACTIVE";
  const canCheckIn = Boolean(attendanceStatusAllowsAction && attendance && !attendanceRecorded && isInsideWindow(attendance.checkInWindow, now));
  const canLate = Boolean(attendanceStatusAllowsAction && attendance && !attendanceRecorded && isInsideWindow(attendance.lateWindow, now));
  const canCancelManual = booking.source === "MANUAL" && (booking.status === "CONFIRMED" || booking.status === "ACTIVE");
  const canCancelConfirmed = booking.source === "ONLINE" && booking.status === "CONFIRMED";

  const messageFor = (value: unknown) => {
    const code = apiErrorOf(value).code;
    return code && code in t.actionErrors ? t.actionErrors[code as keyof typeof t.actionErrors] : t.actionErrors.unexpected;
  };

  const clearConfirmedCancel = () => {
    setOwnerPin("");
    for (const signature of keys.current.keys()) {
      if (signature.startsWith("cancel-confirmed:")) keys.current.delete(signature);
    }
  };

  const closeConfirmation = () => {
    if (confirming === "cancel-confirmed") clearConfirmedCancel();
    setConfirming(undefined);
  };

  const act = async (action: BookingAction) => {
    if (busyRef.current) return;
    const signature = action === "cancel-confirmed" ? `${action}:${ownerPin}` : action;
    const key = keys.current.get(signature) ?? createIdempotencyKey();
    keys.current.set(signature, key);
    busyRef.current = true;
    setBusy(true);
    setError(undefined);
    setSuccess(undefined);
    try {
      const common = { params: { header: { "Idempotency-Key": key }, path: { bookingId: booking.bookingId } } } as const;
      const result = action === "check-in"
        ? await api.POST("/api/v1/venue/bookings/{bookingId}/check-in", common)
        : action === "late-present"
          ? await api.POST("/api/v1/venue/bookings/{bookingId}/late-present", common)
          : action === "no-show"
            ? await api.POST("/api/v1/venue/bookings/{bookingId}/no-show", common)
            : action === "cancel-manual"
              ? await api.POST("/api/v1/venue/bookings/{bookingId}/cancel-manual", common)
              : await api.POST("/api/v1/venue/bookings/{bookingId}/cancel-confirmed", { ...common, body: { ownerPassword: ownerPin } });
      if (!result.data) {
        setError(messageFor(result.error));
        if (action === "cancel-confirmed" && result.response?.status === 423) clearConfirmedCancel();
        if (result.response?.status === 409 || result.response?.status === 422) onChanged();
        return;
      }
      keys.current.delete(signature);
      setConfirming(undefined);
      if (action === "cancel-confirmed") clearConfirmedCancel();
      setSuccess(action === "check-in" || action === "late-present" || action === "no-show"
        ? t.attendanceRecorded
        : action === "cancel-manual" ? t.manualCancelled : t.confirmedCancelled);
      onChanged();
    } catch {
      setError(t.actionErrors.network);
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  };

  if (!canCheckIn && !canLate && !canCancelManual && !canCancelConfirmed && !attendanceRecorded) return null;
  return <section style={styles.actions}>
    <h3 style={styles.groupHeading}>{attendance ? t.attendance : t.actions}</h3>
    {error ? <p role="alert" style={styles.error}>{error}</p> : null}
    {success ? <p role="status">{success}</p> : null}
    {attendance?.checkedInAt ? <p>{t.checkIn}: {dateTimeLabel(attendance.checkedInAt, t.locale)}</p> : null}
    {attendance?.latePresentAt ? <p>{t.latePresent}: {dateTimeLabel(attendance.latePresentAt, t.locale)}</p> : null}
    {attendance?.noShowAt ? <p>{t.noShow}: {dateTimeLabel(attendance.noShowAt, t.locale)}</p> : null}
    <div style={styles.actionRow}>
      {canCheckIn ? <button disabled={busy} onClick={() => void act("check-in")} style={styles.button} type="button">{busy ? t.actionWorking : t.checkIn}</button> : null}
      {canLate ? <button disabled={busy} onClick={() => void act("late-present")} style={styles.button} type="button">{busy ? t.actionWorking : t.latePresent}</button> : null}
      {canLate && confirming !== "no-show" ? <button disabled={busy} onClick={() => setConfirming("no-show")} style={styles.dangerButton} type="button">{t.noShow}</button> : null}
      {confirming === "no-show" ? <><button disabled={busy} onClick={() => void act("no-show")} style={styles.dangerButton} type="button">{busy ? t.actionWorking : t.confirmNoShow}</button><button disabled={busy} onClick={closeConfirmation} style={styles.button} type="button">{t.cancelAction}</button></> : null}
      {canCancelManual && confirming !== "cancel-manual" ? <button disabled={busy} onClick={() => setConfirming("cancel-manual")} style={styles.dangerButton} type="button">{t.cancelManual}</button> : null}
      {confirming === "cancel-manual" ? <><button disabled={busy} onClick={() => void act("cancel-manual")} style={styles.dangerButton} type="button">{busy ? t.actionWorking : t.confirmCancelManual}</button><button disabled={busy} onClick={closeConfirmation} style={styles.button} type="button">{t.cancelAction}</button></> : null}
      {canCancelConfirmed && confirming !== "cancel-confirmed" ? <button disabled={busy} onClick={() => setConfirming("cancel-confirmed")} style={styles.dangerButton} type="button">{t.cancelConfirmed}</button> : null}
    </div>
    {confirming === "cancel-confirmed" ? <div style={styles.actions}><label>{t.ownerPin}<input aria-describedby="owner-pin-hint" autoComplete="off" inputMode="numeric" maxLength={4} onChange={(event) => setOwnerPin(event.target.value.replace(/\D/gu, ""))} style={styles.input} type="password" value={ownerPin} /></label><p id="owner-pin-hint" style={styles.intro}>{t.ownerPinHint}</p><div style={styles.actionRow}><button disabled={busy || ownerPin.length !== 4} onClick={() => void act("cancel-confirmed")} style={styles.dangerButton} type="button">{busy ? t.actionWorking : t.confirmCancelConfirmed}</button><button disabled={busy} onClick={closeConfirmation} style={styles.button} type="button">{t.cancelAction}</button></div></div> : null}
  </section>;
}
