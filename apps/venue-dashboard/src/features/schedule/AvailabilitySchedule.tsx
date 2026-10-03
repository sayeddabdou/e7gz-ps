import { useEffect, useMemo, useState } from "react";
import type { components } from "@e7gz/api-client";
import { apiErrorOf, type ApiClient } from "../../api";
import { useMessages } from "../../i18n";
import { setupErrorMessage } from "../setup/errorMessages";
import { scheduleStyles as styles, stateStyles } from "./styles";

type VenueSchedule = components["schemas"]["VenueSchedule"];
type VenueScheduleSlot = components["schemas"]["VenueScheduleSlot"];
type ScheduleDisplayState = components["schemas"]["ScheduleDisplayState"];

const cairoTimeZone = "Africa/Cairo";
const hourMs = 60 * 60 * 1000;
const displayStates = [
  "FREE",
  "PENDING",
  "AWAITING_CUSTOMER_CONFIRMATION",
  "CONFIRMED",
  "ACTIVE",
  "MANUAL",
  "TOURNAMENT_HOLD",
  "MAINTENANCE",
  "CLOSURE",
] as const satisfies readonly ScheduleDisplayState[];

function shiftBusinessDate(date: string, days: number): string {
  const instant = new Date(`${date}T00:00:00Z`);
  instant.setUTCDate(instant.getUTCDate() + days);
  return instant.toISOString().slice(0, 10);
}

function rangeOf(schedule: VenueSchedule): { start: number; end: number } | undefined {
  const instants = schedule.resources.flatMap((resource) =>
    resource.slots.flatMap((slot) => [Date.parse(slot.startAt), Date.parse(slot.endAt)]),
  ).filter(Number.isFinite);
  if (instants.length === 0) return undefined;
  return {
    start: Math.floor(Math.min(...instants) / hourMs) * hourMs,
    end: Math.ceil(Math.max(...instants) / hourMs) * hourMs,
  };
}

function ticksFor(range: { start: number; end: number } | undefined): number[] {
  if (!range) return [];
  const ticks: number[] = [];
  for (let instant = range.start; instant < range.end; instant += hourMs) ticks.push(instant);
  return ticks;
}

export function AvailabilitySchedule({
  api,
  direction,
}: {
  api: ApiClient;
  direction: "ltr" | "rtl";
}) {
  const t = useMessages();
  const locale = direction === "rtl" ? "ar-EG" : "en-US";
  const [selectedDate, setSelectedDate] = useState("");
  const [requestedDate, setRequestedDate] = useState<string>();
  const [schedule, setSchedule] = useState<VenueSchedule>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();
  const [refreshVersion, setRefreshVersion] = useState(0);

  useEffect(() => {
    let active = true;
    const request = requestedDate
      ? api.GET("/api/v1/venue/availability", { params: { query: { date: requestedDate } } })
      : api.GET("/api/v1/venue/availability");
    void request
      .then(({ data, error: body }) => {
        if (!active) return;
        if (!data) {
          setSchedule(undefined);
          setError(setupErrorMessage(apiErrorOf(body).code, t));
          return;
        }
        setSchedule(data.data);
        setSelectedDate(data.data.businessDate);
      })
      .catch(() => {
        if (active) {
          setSchedule(undefined);
          setError(t.setup.errors.network);
        }
      })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [api, refreshVersion, requestedDate, t]);

  const range = useMemo(() => schedule ? rangeOf(schedule) : undefined, [schedule]);
  const ticks = useMemo(() => ticksFor(range), [range]);
  const timeFormatter = useMemo(() => new Intl.DateTimeFormat(locale, {
    hour: "numeric",
    minute: "2-digit",
    timeZone: cairoTimeZone,
  }), [locale]);
  const dateFormatter = useMemo(() => new Intl.DateTimeFormat(locale, {
    dateStyle: "long",
    timeZone: "UTC",
  }), [locale]);
  const slotCount = schedule?.resources.reduce((total, resource) => total + resource.slots.length, 0) ?? 0;
  const formatTime = (instant: number | string) => timeFormatter.format(typeof instant === "number" ? instant : new Date(instant));
  const slotPosition = (slot: VenueScheduleSlot) => {
    if (!range || range.end === range.start) return { insetInlineStart: "0%", inlineSize: "100%" };
    const span = range.end - range.start;
    return {
      insetInlineStart: `${((Date.parse(slot.startAt) - range.start) / span) * 100}%`,
      inlineSize: `${((Date.parse(slot.endAt) - Date.parse(slot.startAt)) / span) * 100}%`,
    };
  };
  const loadDate = (date: string) => {
    setLoading(true);
    setError(undefined);
    setSchedule(undefined);
    setSelectedDate(date);
    setRequestedDate(date);
  };
  const retry = () => {
    setLoading(true);
    setError(undefined);
    setRefreshVersion((version) => version + 1);
  };

  return (
    <section dir={direction} style={styles.page}>
      <header>
        <h2 style={styles.heading}>{t.schedule.title}</h2>
        <p style={styles.intro}>{t.schedule.description}</p>
      </header>

      <div style={styles.controls}>
        <button aria-label={t.schedule.previousDate} disabled={!selectedDate} onClick={() => loadDate(shiftBusinessDate(selectedDate, -1))} style={styles.button} type="button">
          {t.schedule.previous}
        </button>
        <label style={styles.field}>
          <span style={styles.label}>{t.schedule.businessDate}</span>
          <input aria-label={t.schedule.businessDate} onChange={(event) => { if (event.target.value) loadDate(event.target.value); }} style={styles.dateInput} type="date" value={selectedDate} />
        </label>
        <button aria-label={t.schedule.nextDate} disabled={!selectedDate} onClick={() => loadDate(shiftBusinessDate(selectedDate, 1))} style={styles.button} type="button">
          {t.schedule.next}
        </button>
      </div>

      {loading ? <p role="status" style={styles.status}>{t.schedule.loading}</p> : null}
      {!loading && error ? (
        <div role="alert" style={styles.error}>
          <span>{error}</span>
          <button onClick={retry} style={styles.button} type="button">{t.schedule.retry}</button>
        </div>
      ) : null}
      {!loading && !error && schedule ? (
        <div style={styles.card}>
          <header style={styles.cardHeader}>
            <div>
              <h3 style={styles.businessDate}>{dateFormatter.format(new Date(`${schedule.businessDate}T00:00:00Z`))}</h3>
              <p style={styles.summary}>{t.schedule.summary(schedule.resources.length, slotCount)}</p>
            </div>
            <span style={styles.label}>{cairoTimeZone}</span>
          </header>

          <div aria-label={t.schedule.legend} style={styles.legend}>
            {displayStates.map((state) => (
              <div key={state} style={styles.legendItem}>
                <span aria-hidden="true" style={{ ...styles.legendSwatch, ...stateStyles[state] }} />
                <span>{t.schedule.states[state]}</span>
              </div>
            ))}
          </div>

          {schedule.resources.length === 0 ? <p style={styles.status}>{t.schedule.empty}</p> : (
            <div style={styles.scroller}>
              <div data-testid="availability-grid" dir={direction} style={styles.grid}>
                <div style={styles.axisRow}>
                  <span />
                  <div style={{ ...styles.timeAxis, gridTemplateColumns: `repeat(${Math.max(ticks.length, 1)}, minmax(0, 1fr))` }}>
                    {ticks.map((tick) => <span key={tick} style={styles.tick}>{formatTime(tick)}</span>)}
                  </div>
                </div>
                {schedule.resources.map((resource) => (
                  <div key={resource.resourceId} style={styles.resourceRow}>
                    <div style={styles.resource}>
                      <span style={styles.resourceName}>{resource.name}</span>
                      <span style={styles.resourceMeta}>
                        {t.activity[resource.activity]}{resource.consoleType ? ` · ${resource.consoleType}` : ""}
                      </span>
                    </div>
                    <div aria-label={`${resource.name} ${t.schedule.timeline}`} style={styles.timeline}>
                      {resource.slots.map((slot, index) => (
                        <div
                          aria-label={`${t.schedule.states[slot.state]}, ${formatTime(slot.startAt)} – ${formatTime(slot.endAt)}`}
                          key={`${slot.startAt}-${slot.endAt}-${slot.state}-${index}`}
                          style={{ ...styles.slot, ...stateStyles[slot.state], ...slotPosition(slot) }}
                        >
                          <div style={styles.slotState}>{t.schedule.states[slot.state]}</div>
                          <div style={styles.slotTime}>{formatTime(slot.startAt)} – {formatTime(slot.endAt)}</div>
                        </div>
                      ))}
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      ) : null}
    </section>
  );
}
