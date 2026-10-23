import { useEffect, useMemo, useRef, useState, type CSSProperties } from "react";
import type { components } from "@e7gz/api-client";
import { webTokens } from "@e7gz/tokens/web";
import { apiErrorOf, type ApiClient } from "../../api";
import { useMessages } from "../../i18n";
import { createIdempotencyKey } from "../operations";
import { bookingErrorMessage } from "./errorMessages";

type VenueResource = components["schemas"]["VenueResource"];
type PlayMode = components["schemas"]["PlayMode"];

const styles = {
  page: { display: "grid", gap: webTokens.spacing["4xl"], maxWidth: "64rem" },
  heading: { fontSize: webTokens.typography.fontSize.displaySmall, marginBlock: 0 },
  intro: { color: webTokens.color.text.venueMuted, marginBlock: 0 },
  card: { backgroundColor: webTokens.color.surface.raised, border: `${webTokens.spacing.hairline}px solid ${webTokens.color.border.subtle}`, borderRadius: webTokens.radius["3xl"], boxShadow: webTokens.shadow.card, display: "grid", gap: webTokens.spacing["3xl"], padding: webTokens.spacing["4xl"] },
  grid: { display: "grid", gap: webTokens.spacing.xl, gridTemplateColumns: "repeat(auto-fit, minmax(15rem, 1fr))" },
  field: { display: "grid", gap: webTokens.spacing.sm },
  label: { color: webTokens.color.text.venueMuted, fontSize: webTokens.typography.fontSize.label, fontWeight: webTokens.typography.fontWeight.bold },
  input: { backgroundColor: webTokens.color.surface.raised, border: `${webTokens.spacing.hairline}px solid ${webTokens.color.border.venue}`, borderRadius: webTokens.radius.lg, color: webTokens.color.text.venue, minHeight: webTokens.spacing["10xl"], paddingBlock: webTokens.spacing.lg, paddingInline: webTokens.spacing.xl, width: "100%" },
  choices: { display: "flex", flexWrap: "wrap", gap: webTokens.spacing.xl },
  choice: { alignItems: "center", display: "flex", gap: webTokens.spacing.sm },
  button: { backgroundColor: webTokens.color.brand.venue, border: 0, borderRadius: webTokens.radius.lg, boxShadow: webTokens.shadow.action, color: webTokens.color.text.inverse, cursor: "pointer", fontWeight: webTokens.typography.fontWeight.bold, minHeight: webTokens.spacing["10xl"], paddingBlock: webTokens.spacing.lg, paddingInline: webTokens.spacing["3xl"] },
  retry: { backgroundColor: webTokens.color.surface.raised, border: `${webTokens.spacing.hairline}px solid ${webTokens.color.border.venue}`, borderRadius: webTokens.radius.lg, color: webTokens.color.brand.venue, cursor: "pointer", fontWeight: webTokens.typography.fontWeight.bold, paddingBlock: webTokens.spacing.md, paddingInline: webTokens.spacing.xl },
  error: { backgroundColor: webTokens.color.feedback.dangerSurface, borderRadius: webTokens.radius.lg, color: webTokens.color.feedback.danger, padding: webTokens.spacing.xl },
  success: { backgroundColor: webTokens.color.feedback.successSurface, borderRadius: webTokens.radius.lg, color: webTokens.color.feedback.success, padding: webTokens.spacing.xl },
} satisfies Record<string, CSSProperties>;

const cairoFormatter = new Intl.DateTimeFormat("en-CA", {
  day: "2-digit", hour: "2-digit", hour12: false, minute: "2-digit", month: "2-digit",
  second: "2-digit", timeZone: "Africa/Cairo", year: "numeric",
});

/** Converts a venue-local datetime input to an instant without using the device timezone. */
export function cairoLocalToInstant(value: string): string | undefined {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/u.exec(value);
  if (!match) return undefined;
  const [, year, month, day, hour, minute] = match;
  const wallTime = Date.UTC(Number(year), Number(month) - 1, Number(day), Number(hour), Number(minute));
  let instant = wallTime;
  for (let attempt = 0; attempt < 3; attempt += 1) {
    const parts = Object.fromEntries(cairoFormatter.formatToParts(new Date(instant)).map((part) => [part.type, part.value]));
    const renderedWallTime = Date.UTC(Number(parts.year), Number(parts.month) - 1, Number(parts.day), Number(parts.hour) % 24, Number(parts.minute), Number(parts.second));
    instant = wallTime - (renderedWallTime - instant);
  }
  return new Date(instant).toISOString();
}

function resourceLabel(resource: VenueResource, activity: Record<VenueResource["activity"], string>): string {
  return [resource.name, activity[resource.activity], resource.consoleType].filter(Boolean).join(" · ");
}

function isEligible(resource: VenueResource): boolean {
  return resource.baseState === "ACTIVE" && resource.operationalRestriction === "NONE";
}

export function ManualBookingScreen({ api, onOpenBooking }: { api: ApiClient; onOpenBooking: (bookingId: string) => void }) {
  const messages = useMessages();
  const t = messages.bookings.manual;
  const [resources, setResources] = useState<VenueResource[]>();
  const [loadError, setLoadError] = useState<string>();
  const [reload, setReload] = useState(0);
  const [resourceId, setResourceId] = useState("");
  const [playMode, setPlayMode] = useState<PlayMode>("SINGLE");
  const [startKind, setStartKind] = useState<"now" | "future">("now");
  const [startAt, setStartAt] = useState("");
  const [durationMinutes, setDurationMinutes] = useState(60);
  const [customerName, setCustomerName] = useState("");
  const [customerPhone, setCustomerPhone] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const submittingRef = useRef(false);
  const keyRef = useRef<string | undefined>(undefined);
  const [error, setError] = useState<string>();
  const [fieldError, setFieldError] = useState<string>();
  const [createdId, setCreatedId] = useState<string>();

  useEffect(() => {
    let active = true;
    void api.GET("/api/v1/venue/resources").then(({ data, error: body }) => {
      if (!active) return;
      if (!data) return setLoadError(bookingErrorMessage(apiErrorOf(body).code, messages));
      setResources(data.data);
      setResourceId((current) => data.data.some((resource) => resource.id === current && isEligible(resource)) ? current : data.data.find(isEligible)?.id ?? "");
    }).catch(() => { if (active) setLoadError(messages.bookings.errors.network); });
    return () => { active = false; };
  }, [api, messages, reload]);

  const selected = useMemo(() => resources?.find((resource) => resource.id === resourceId), [resourceId, resources]);
  const change = (update: () => void) => {
    update();
    keyRef.current = undefined;
    setCreatedId(undefined);
    setError(undefined);
    setFieldError(undefined);
  };

  const submit = async () => {
    if (submittingRef.current || !selected) return;
    const instant = startKind === "future" ? cairoLocalToInstant(startAt) : undefined;
    if (startKind === "future" && !instant) {
      setFieldError(t.startRequired);
      return;
    }
    setFieldError(undefined);
    setError(undefined);
    submittingRef.current = true;
    setSubmitting(true);
    try {
      const key = keyRef.current ?? createIdempotencyKey();
      keyRef.current = key;
      const name = customerName.trim();
      const phone = customerPhone.trim();
      const result = await api.POST("/api/v1/venue/bookings", {
        body: {
          resourceId: selected.id,
          durationMinutes,
          ...(selected.activity === "PLAYSTATION" ? { playMode } : {}),
          ...(instant ? { startAt: instant } : {}),
          ...(name ? { customerName: name } : {}),
          ...(phone ? { customerPhone: phone } : {}),
        },
        params: { header: { "Idempotency-Key": key } },
      });
      if (!result.data) {
        setError(bookingErrorMessage(apiErrorOf(result.error).code, messages));
        return;
      }
      keyRef.current = undefined;
      setCreatedId(result.data.data.bookingId);
    } catch {
      setError(messages.bookings.errors.network);
    } finally {
      submittingRef.current = false;
      setSubmitting(false);
    }
  };

  return <section style={styles.page}>
    <header><h2 style={styles.heading}>{t.title}</h2><p style={styles.intro}>{t.description}</p></header>
    {!resources ? loadError ? <div><p role="alert" style={styles.error}>{loadError}</p><button onClick={() => { setLoadError(undefined); setReload((value) => value + 1); }} style={styles.retry} type="button">{t.retry}</button></div> : <p role="status">{t.loading}</p> : resources.length === 0 ? <p>{t.noResources}</p> : <form onSubmit={(event) => { event.preventDefault(); void submit(); }} style={styles.card}>
      <div style={styles.grid}>
        <label style={styles.field}><span style={styles.label}>{t.resource}</span><select onChange={(event) => change(() => setResourceId(event.currentTarget.value))} style={styles.input} value={resourceId}>{resources.map((resource) => <option disabled={!isEligible(resource)} key={resource.id} value={resource.id}>{resourceLabel(resource, messages.activity)}{isEligible(resource) ? "" : ` · ${t.unavailable}`}</option>)}</select></label>
        {selected?.activity === "PLAYSTATION" ? <label style={styles.field}><span style={styles.label}>{t.playMode}</span><select onChange={(event) => change(() => setPlayMode(event.currentTarget.value as PlayMode))} style={styles.input} value={playMode}><option value="SINGLE">{t.playModes.SINGLE}</option><option value="MULTIPLAYER">{t.playModes.MULTIPLAYER}</option></select></label> : null}
        <label style={styles.field}><span style={styles.label}>{t.duration}</span><select onChange={(event) => change(() => setDurationMinutes(Number(event.currentTarget.value)))} style={styles.input} value={durationMinutes}>{Array.from({ length: 11 }, (_, index) => 60 + index * 30).map((minutes) => <option key={minutes} value={minutes}>{t.durationMinutes(minutes)}</option>)}</select></label>
      </div>
      <fieldset style={styles.field}><legend style={styles.label}>{t.start}</legend><div style={styles.choices}><label style={styles.choice}><input checked={startKind === "now"} name="start-kind" onChange={() => change(() => setStartKind("now"))} type="radio" />{t.now}</label><label style={styles.choice}><input aria-label={t.future} checked={startKind === "future"} name="start-kind" onChange={() => change(() => setStartKind("future"))} type="radio" />{t.future}</label></div></fieldset>
      {startKind === "future" ? <label style={styles.field}><span style={styles.label}>{t.startAt}</span><input aria-label={t.startAt} onChange={(event) => change(() => setStartAt(event.currentTarget.value))} style={styles.input} type="datetime-local" value={startAt} />{fieldError ? <span role="alert" style={styles.error}>{fieldError}</span> : null}</label> : null}
      <div style={styles.grid}>
        <label style={styles.field}><span style={styles.label}>{t.customerName}</span><input maxLength={200} onChange={(event) => change(() => setCustomerName(event.currentTarget.value))} style={styles.input} value={customerName} /></label>
        <label style={styles.field}><span style={styles.label}>{t.customerPhone}</span><input inputMode="tel" maxLength={40} onChange={(event) => change(() => setCustomerPhone(event.currentTarget.value))} style={styles.input} value={customerPhone} /></label>
      </div>
      {error ? <p role="alert" style={styles.error}>{error}</p> : null}
      {!resourceId ? <p role="alert" style={styles.error}>{t.noEligibleResources}</p> : null}
      {createdId ? <div role="status" style={styles.success}>{t.created} <button onClick={() => onOpenBooking(createdId)} style={styles.retry} type="button">{t.viewCreated}</button></div> : null}
      <button disabled={submitting || Boolean(createdId) || !resourceId} style={styles.button} type="submit">{submitting ? t.creating : t.create}</button>
    </form>}
  </section>;
}
