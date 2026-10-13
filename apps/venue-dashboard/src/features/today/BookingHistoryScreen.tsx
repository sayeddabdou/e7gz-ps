import { useEffect, useRef, useState, type CSSProperties } from "react";
import type { components } from "@e7gz/api-client";
import { webTokens } from "@e7gz/tokens/web";
import { apiErrorOf, type ApiClient } from "../../api";
import { useMessages } from "../../i18n";
import { bookingErrorMessage } from "../walk-in/errorMessages";

type Booking = components["schemas"]["VenueBooking"];
type VenueResource = components["schemas"]["VenueResource"];
type Activity = components["schemas"]["Activity"];
type Source = components["schemas"]["BookingSource"];
type Status = components["schemas"]["BookingStatus"];
export type DatePreset = "today" | "yesterday" | "last7" | "month" | "custom";

interface Query {
  from: string;
  to: string;
  activity?: Activity;
  source?: Source;
  resourceId?: string;
  status?: Status;
  q?: string;
}

const statuses: Status[] = ["PENDING", "VENUE_RESPONSE_EXPIRED", "AWAITING_CUSTOMER_CONFIRMATION", "CONFIRMED", "ACTIVE", "COMPLETED", "REJECTED", "CANCELLED", "NO_SHOW"];
const activities: Activity[] = ["PLAYSTATION", "BILLIARDS", "PING_PONG"];
const sources: Source[] = ["ONLINE", "MANUAL"];

const styles = {
  page: { display: "grid", gap: webTokens.spacing["4xl"] },
  heading: { fontSize: webTokens.typography.fontSize.displaySmall, marginBlock: 0 },
  intro: { color: webTokens.color.text.venueMuted, marginBlock: webTokens.spacing.sm },
  filters: { backgroundColor: webTokens.color.surface.raised, border: `${webTokens.spacing.hairline}px solid ${webTokens.color.border.subtle}`, borderRadius: webTokens.radius["3xl"], boxShadow: webTokens.shadow.card, display: "grid", gap: webTokens.spacing.xl, padding: webTokens.spacing["3xl"] },
  filterGrid: { display: "grid", gap: webTokens.spacing.xl, gridTemplateColumns: "repeat(auto-fit, minmax(12rem, 1fr))" },
  field: { display: "grid", gap: webTokens.spacing.sm },
  label: { color: webTokens.color.text.venueMuted, fontSize: webTokens.typography.fontSize.label, fontWeight: webTokens.typography.fontWeight.bold },
  input: { backgroundColor: webTokens.color.surface.raised, border: `${webTokens.spacing.hairline}px solid ${webTokens.color.border.venue}`, borderRadius: webTokens.radius.lg, color: webTokens.color.text.venue, minHeight: webTokens.spacing["10xl"], paddingBlock: webTokens.spacing.md, paddingInline: webTokens.spacing.xl, width: "100%" },
  hint: { color: webTokens.color.text.venueMuted, fontSize: webTokens.typography.fontSize.caption, marginBlock: 0 },
  button: { backgroundColor: webTokens.color.brand.venue, border: 0, borderRadius: webTokens.radius.lg, color: webTokens.color.text.inverse, cursor: "pointer", fontWeight: webTokens.typography.fontWeight.bold, paddingBlock: webTokens.spacing.md, paddingInline: webTokens.spacing.xl },
  secondaryButton: { backgroundColor: webTokens.color.surface.raised, border: `${webTokens.spacing.hairline}px solid ${webTokens.color.border.venue}`, borderRadius: webTokens.radius.lg, color: webTokens.color.brand.venue, cursor: "pointer", fontWeight: webTokens.typography.fontWeight.bold, paddingBlock: webTokens.spacing.md, paddingInline: webTokens.spacing.xl },
  tableWrap: { overflowX: "auto" },
  table: { borderCollapse: "collapse", inlineSize: "100%", minInlineSize: "48rem" },
  cell: { borderBlockEnd: `${webTokens.spacing.hairline}px solid ${webTokens.color.border.subtle}`, paddingBlock: webTokens.spacing.xl, paddingInline: webTokens.spacing.lg, textAlign: "start" },
  status: { color: webTokens.color.brand.venue, fontWeight: webTokens.typography.fontWeight.bold },
  actions: { display: "flex", justifyContent: "center" },
  error: { backgroundColor: webTokens.color.feedback.dangerSurface, borderRadius: webTokens.radius.lg, color: webTokens.color.feedback.danger, padding: webTokens.spacing.xl },
} satisfies Record<string, CSSProperties>;

function shiftDate(date: string, days: number): string {
  const [year, month, day] = date.split("-").map(Number);
  return new Date(Date.UTC(year!, month! - 1, day! + days)).toISOString().slice(0, 10);
}

export function dateRangeFor(preset: Exclude<DatePreset, "custom">, businessDate: string): { from: string; to: string } {
  if (preset === "today") return { from: businessDate, to: businessDate };
  if (preset === "yesterday") {
    const yesterday = shiftDate(businessDate, -1);
    return { from: yesterday, to: yesterday };
  }
  if (preset === "last7") return { from: shiftDate(businessDate, -6), to: businessDate };
  const [year, month] = businessDate.split("-").map(Number);
  const from = `${year}-${String(month).padStart(2, "0")}-01`;
  const to = new Date(Date.UTC(year!, month!, 0)).toISOString().slice(0, 10);
  return { from, to };
}

function timeLabel(instant: string, locale: string): string {
  return new Date(instant).toLocaleString(locale, { dateStyle: "medium", timeStyle: "short", timeZone: "Africa/Cairo" });
}

export function BookingHistoryScreen({ api, onOpenBooking }: { api: ApiClient; onOpenBooking: (bookingId: string) => void }) {
  const messages = useMessages();
  const t = messages.bookings.history;
  const [businessDate, setBusinessDate] = useState<string>();
  const [resources, setResources] = useState<VenueResource[]>([]);
  const [preset, setPreset] = useState<DatePreset>("today");
  const [customFrom, setCustomFrom] = useState("");
  const [customTo, setCustomTo] = useState("");
  const [activity, setActivity] = useState<Activity | "">("");
  const [source, setSource] = useState<Source | "">("");
  const [resourceId, setResourceId] = useState("");
  const [status, setStatus] = useState<Status | "">("");
  const [search, setSearch] = useState("");
  const [items, setItems] = useState<Booking[]>();
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [applying, setApplying] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string>();
  const [reload, setReload] = useState(0);
  const activeQuery = useRef<Query | undefined>(undefined);
  const applyingRef = useRef(false);
  const requestSequence = useRef(0);

  useEffect(() => {
    let active = true;
    void Promise.all([api.GET("/api/v1/venue/availability"), api.GET("/api/v1/venue/resources")]).then(async ([schedule, resourceResult]) => {
      if (!active) return;
      if (!schedule.data || !resourceResult.data) {
        setError(bookingErrorMessage(apiErrorOf(schedule.error ?? resourceResult.error).code, messages));
        return;
      }
      const currentDate = schedule.data.data.businessDate;
      const query = dateRangeFor("today", currentDate);
      const page = await api.GET("/api/v1/venue/bookings", { params: { query: { ...query, limit: 100 } } });
      if (!active) return;
      if (!page.data) {
        setError(bookingErrorMessage(apiErrorOf(page.error).code, messages));
        return;
      }
      setBusinessDate(currentDate);
      setCustomFrom(currentDate);
      setCustomTo(currentDate);
      setResources(resourceResult.data.data);
      activeQuery.current = query;
      setItems(page.data.data.items);
      setNextCursor(page.data.data.nextCursor);
    }).catch(() => { if (active) setError(messages.bookings.errors.network); });
    return () => { active = false; };
  }, [api, messages, reload]);

  const buildQuery = (): Query | undefined => {
    if (!businessDate) return undefined;
    const range = preset === "custom" ? { from: customFrom, to: customTo } : dateRangeFor(preset, businessDate);
    if (!range.from || !range.to) return undefined;
    const trimmed = search.trim();
    return { ...range, ...(activity ? { activity } : {}), ...(source ? { source } : {}), ...(resourceId ? { resourceId } : {}), ...(status ? { status } : {}), ...(trimmed ? { q: trimmed } : {}) };
  };

  const apply = async () => {
    if (applyingRef.current) return;
    const query = buildQuery();
    if (!query) return setError(t.customRequired);
    if (query.from > query.to) return setError(t.customOrder);
    applyingRef.current = true;
    setApplying(true);
    setItems(undefined);
    setError(undefined);
    const requestId = ++requestSequence.current;
    try {
      const page = await api.GET("/api/v1/venue/bookings", { params: { query: { ...query, limit: 100 } } });
      if (requestId !== requestSequence.current) return;
      if (!page.data) {
        setError(bookingErrorMessage(apiErrorOf(page.error).code, messages));
        return;
      }
      activeQuery.current = query;
      setItems(page.data.data.items);
      setNextCursor(page.data.data.nextCursor);
    } catch {
      if (requestId === requestSequence.current) setError(messages.bookings.errors.network);
    } finally {
      if (requestId === requestSequence.current) {
        applyingRef.current = false;
        setApplying(false);
      }
    }
  };

  const loadMore = async () => {
    if (!nextCursor || !activeQuery.current || loadingMore) return;
    setLoadingMore(true);
    const page = await api.GET("/api/v1/venue/bookings", { params: { query: { ...activeQuery.current, cursor: nextCursor, limit: 100 } } }).catch(() => undefined);
    setLoadingMore(false);
    if (!page?.data) return setError(page ? bookingErrorMessage(apiErrorOf(page.error).code, messages) : messages.bookings.errors.network);
    setItems((current) => [...(current ?? []), ...page.data.data.items]);
    setNextCursor(page.data.data.nextCursor);
  };

  return <section style={styles.page}>
    <header><h2 style={styles.heading}>{t.title}</h2><p style={styles.intro}>{t.description}</p></header>
    <form onSubmit={(event) => { event.preventDefault(); void apply(); }} style={styles.filters}>
      <div style={styles.filterGrid}>
        <label style={styles.field}><span style={styles.label}>{t.dateRange}</span><select aria-label={t.dateRange} onChange={(event) => setPreset(event.currentTarget.value as DatePreset)} style={styles.input} value={preset}><option value="today">{t.presets.today}</option><option value="yesterday">{t.presets.yesterday}</option><option value="last7">{t.presets.last7}</option><option value="month">{t.presets.month}</option><option value="custom">{t.presets.custom}</option></select></label>
        {preset === "custom" ? <><label style={styles.field}><span style={styles.label}>{t.from}</span><input aria-label={t.from} onChange={(event) => setCustomFrom(event.currentTarget.value)} style={styles.input} type="date" value={customFrom} /></label><label style={styles.field}><span style={styles.label}>{t.to}</span><input aria-label={t.to} onChange={(event) => setCustomTo(event.currentTarget.value)} style={styles.input} type="date" value={customTo} /></label></> : null}
        <label style={styles.field}><span style={styles.label}>{t.activity}</span><select aria-label={t.activity} onChange={(event) => setActivity(event.currentTarget.value as Activity | "")} style={styles.input} value={activity}><option value="">{t.all}</option>{activities.map((value) => <option key={value} value={value}>{messages.activity[value]}</option>)}</select></label>
        <label style={styles.field}><span style={styles.label}>{t.source}</span><select aria-label={t.source} onChange={(event) => setSource(event.currentTarget.value as Source | "")} style={styles.input} value={source}><option value="">{t.all}</option>{sources.map((value) => <option key={value} value={value}>{value === "ONLINE" ? t.sources.ONLINE : t.sources.MANUAL}</option>)}</select></label>
        <label style={styles.field}><span style={styles.label}>{t.resource}</span><select aria-label={t.resource} onChange={(event) => setResourceId(event.currentTarget.value)} style={styles.input} value={resourceId}><option value="">{t.all}</option>{resources.map((resource) => <option key={resource.id} value={resource.id}>{resource.name}</option>)}</select></label>
        <label style={styles.field}><span style={styles.label}>{t.status}</span><select aria-label={t.status} onChange={(event) => setStatus(event.currentTarget.value as Status | "")} style={styles.input} value={status}><option value="">{t.all}</option>{statuses.map((value) => <option key={value} value={value}>{messages.bookings.today.statuses[value]}</option>)}</select></label>
      </div>
      <label style={styles.field}><span style={styles.label}>{t.search}</span><input aria-label={t.search} maxLength={100} onChange={(event) => setSearch(event.currentTarget.value)} style={styles.input} value={search} /><span style={styles.hint}>{t.searchHint}</span></label>
      <div><button disabled={!businessDate || applying} style={styles.button} type="submit">{applying ? t.applying : t.apply}</button></div>
    </form>
    {error ? <div><p role="alert" style={styles.error}>{error}</p><button onClick={() => { setError(undefined); if (businessDate) void apply(); else setReload((value) => value + 1); }} style={styles.secondaryButton} type="button">{t.retry}</button></div> : null}
    {!items ? error ? null : <p role="status">{t.loading}</p> : items.length === 0 ? <p>{t.empty}</p> : <div style={styles.tableWrap}><table style={styles.table}><thead><tr><th style={styles.cell}>{t.start}</th><th style={styles.cell}>{t.resource}</th><th style={styles.cell}>{t.customer}</th><th style={styles.cell}>{t.source}</th><th style={styles.cell}>{t.status}</th><th style={styles.cell}>{t.price}</th><th style={styles.cell}>{t.actions}</th></tr></thead><tbody>{items.map((booking) => <tr key={booking.bookingId}><td style={styles.cell}>{timeLabel(booking.startAt, t.locale)}</td><td style={styles.cell}>{booking.resource.name}</td><td style={styles.cell}>{booking.customer?.username ?? booking.walkIn?.name ?? t.walkIn}</td><td style={styles.cell}>{booking.source === "ONLINE" ? t.sources.ONLINE : t.sources.MANUAL}</td><td style={{ ...styles.cell, ...styles.status }}>{messages.bookings.today.statuses[booking.status]}</td><td style={styles.cell}>{booking.venuePriceTotal.amount} {booking.venuePriceTotal.currency}</td><td style={styles.cell}><button onClick={() => onOpenBooking(booking.bookingId)} style={styles.secondaryButton} type="button">{t.view}</button></td></tr>)}</tbody></table></div>}
    {nextCursor ? <div style={styles.actions}><button disabled={loadingMore} onClick={() => void loadMore()} style={styles.secondaryButton} type="button">{loadingMore ? t.loadingMore : t.loadMore}</button></div> : null}
  </section>;
}
