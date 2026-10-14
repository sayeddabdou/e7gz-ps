import { useCallback, useEffect, useMemo, useState, type CSSProperties } from "react";
import type { components } from "@e7gz/api-client";
import { webTokens } from "@e7gz/tokens/web";
import { apiErrorOf, type ApiClient } from "../../api";
import { useMessages } from "../../i18n";
import { bookingErrorMessage } from "../walk-in/errorMessages";

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
  const bookings: Booking[] = [];
  let cursor: string | undefined;
  do {
    const page = await api.GET("/api/v1/venue/bookings", {
      params: { query: { from: businessDate, limit: 100, to: businessDate, ...(cursor ? { cursor } : {}) } },
    });
    if (!page.data) return { bookings: [], businessDate, error: page.error };
    bookings.push(...page.data.data.items);
    cursor = page.data.data.nextCursor ?? undefined;
  } while (cursor);
  return { bookings, businessDate };
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

  const load = useCallback(() => {
    void api.GET("/api/v1/venue/bookings/{bookingId}", { params: { path: { bookingId } } }).then(({ data, error: body }) => {
      if (!data) return setError(bookingErrorMessage(apiErrorOf(body).code, messages));
      setDetail(data.data);
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
      {detail.history.length ? <section><h3 style={styles.groupHeading}>{t.history}</h3><ul style={styles.history}>{detail.history.map((entry, index) => <li key={`${entry.at}-${index}`} style={styles.historyItem}><time dateTime={entry.at}>{dateTimeLabel(entry.at, t.locale)}</time> · {entry.toStatus ? messages.bookings.today.statuses[entry.toStatus] : entry.terminalReason ? t.terminalReasons[entry.terminalReason] : entry.eventType ? t.events[entry.eventType] : t.bookingUpdate}</li>)}</ul></section> : null}
    </article>
  </section>;
}
