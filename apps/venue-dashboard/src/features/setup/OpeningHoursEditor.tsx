import { useCallback, useEffect, useMemo, useState } from "react";
import type { components } from "@e7gz/api-client";
import { apiErrorOf, type ApiClient } from "../../api";
import { useMessages } from "../../i18n";
import { setupErrorMessage } from "./errorMessages";
import { OwnerPasswordDialog } from "./OwnerPasswordDialog";
import { setupStyles as styles } from "./styles";

type Proposal = components["schemas"]["VenueOpeningHoursProposal"];
type Preview = components["schemas"]["VenueOpeningHoursPreview"];
type OpeningDayMode = components["schemas"]["OpeningDayMode"];

const toInputTime = (time: string | null) => time?.slice(0, 5) ?? "";
const toApiTime = (time: string) => `${time}:00`;

export function OpeningHoursEditor({ api }: { api: ApiClient }) {
  const t = useMessages();
  const [proposal, setProposal] = useState<Proposal>();
  const [preview, setPreview] = useState<Preview>();
  const [loading, setLoading] = useState(true);
  const [working, setWorking] = useState<"preview" | "commit">();
  const [error, setError] = useState<string>();
  const [notice, setNotice] = useState<string>();
  const [passwordOpen, setPasswordOpen] = useState(false);
  const [passwordError, setPasswordError] = useState<string>();
  const [refreshVersion, setRefreshVersion] = useState(0);
  const [timezone, setTimezone] = useState("Africa/Cairo");

  useEffect(() => {
    let active = true;
    void api.GET("/api/v1/venue/opening-hours").then(({ data, error: body }) => {
      if (!active) return;
      if (!data) return setError(setupErrorMessage(apiErrorOf(body).code, t));
      setTimezone(data.data.timezone);
      setProposal({
        defaultOpen: data.data.defaultOpen,
        defaultClose: data.data.defaultClose,
        defaultFullDay: data.data.defaultFullDay,
        days: data.data.days,
      });
    }).catch(() => { if (active) setError(t.setup.errors.network); }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [api, refreshVersion, t]);

  const retry = () => {
    setLoading(true);
    setError(undefined);
    setRefreshVersion((version) => version + 1);
  };

  const requestPreview = useCallback(async (nextProposal: Proposal, refreshed = false): Promise<Preview | undefined> => {
    const result = await api.POST("/api/v1/venue/opening-hours/preview", { body: { proposal: nextProposal } });
    if (!result.data) {
      setError(setupErrorMessage(apiErrorOf(result.error).code, t));
      return undefined;
    }
    setPreview(result.data.data);
    setNotice(refreshed ? t.setup.hours.refreshed : undefined);
    return result.data.data;
  }, [api, t]);

  const previewChanges = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!proposal) return;
    setWorking("preview");
    setError(undefined);
    setNotice(undefined);
    try { await requestPreview(proposal); } catch { setError(t.setup.errors.network); } finally { setWorking(undefined); }
  };

  const commit = async (ownerPassword?: string): Promise<boolean> => {
    if (!proposal || !preview) return false;
    setWorking("commit");
    setError(undefined);
    setPasswordError(undefined);
    try {
      const result = await api.POST("/api/v1/venue/opening-hours/commit", {
        body: { proposal, previewToken: preview.previewToken, ...(ownerPassword ? { ownerPassword } : {}) },
      });
      if (result.data) {
        setPreview(undefined);
        setNotice(t.setup.common.saved);
        return true;
      }
      const code = apiErrorOf(result.error).code;
      if (code === "AFFECTED_SET_CHANGED_REFRESH_PREVIEW") {
        await requestPreview(proposal, true);
        setPasswordOpen(false);
        return false;
      }
      const message = setupErrorMessage(code, t);
      if (ownerPassword) setPasswordError(message); else setError(message);
      return false;
    } catch {
      const message = t.setup.errors.network;
      if (ownerPassword) setPasswordError(message); else setError(message);
      return false;
    } finally {
      setWorking(undefined);
    }
  };

  const updateDay = (weekday: number, patch: Partial<Proposal["days"][number]>) => {
    if (!proposal) return;
    setPreview(undefined);
    setProposal({ ...proposal, days: proposal.days.map((day) => day.weekday === weekday ? { ...day, ...patch } : day) });
  };

  const affectedDateFormatter = useMemo(() => new Intl.DateTimeFormat(t.setup.hours.locale, {
    dateStyle: "medium",
    timeZone: timezone,
  }), [t, timezone]);
  const affectedTimeFormatter = useMemo(() => new Intl.DateTimeFormat(t.setup.hours.locale, {
    hour: "numeric",
    minute: "2-digit",
    timeZone: timezone,
  }), [t, timezone]);

  return (
    <section aria-labelledby="hours-title" style={styles.card}>
      <div><h2 id="hours-title" style={styles.cardTitle}>{t.setup.hours.title}</h2><p style={styles.muted}>{t.setup.hours.description}</p></div>
      {loading ? <p role="status">{t.setup.common.loading}</p> : null}
      {error ? <div role="alert" style={styles.error}><p>{error}</p><button style={styles.secondaryButton} type="button" onClick={retry}>{t.setup.common.retry}</button></div> : null}
      {proposal && !loading ? (
        <form onSubmit={(event) => void previewChanges(event)}>
          <fieldset style={styles.card}>
            <legend style={styles.label}>{t.setup.hours.defaultSchedule}</legend>
            <div style={styles.grid}>
              <label style={styles.field}><span style={styles.label}>{t.setup.hours.opens}</span><input disabled={proposal.defaultFullDay} required={!proposal.defaultFullDay} style={styles.input} type="time" value={toInputTime(proposal.defaultOpen)} onChange={(event) => { setPreview(undefined); setProposal({ ...proposal, defaultOpen: toApiTime(event.target.value) }); }} /></label>
              <label style={styles.field}><span style={styles.label}>{t.setup.hours.closes}</span><input disabled={proposal.defaultFullDay} required={!proposal.defaultFullDay} style={styles.input} type="time" value={toInputTime(proposal.defaultClose)} onChange={(event) => { setPreview(undefined); setProposal({ ...proposal, defaultClose: toApiTime(event.target.value) }); }} /></label>
            </div>
            <label><input checked={proposal.defaultFullDay} type="checkbox" onChange={(event) => { setPreview(undefined); setProposal({ ...proposal, defaultFullDay: event.target.checked }); }} /> {t.setup.hours.fullDay}</label>
          </fieldset>
          <div style={styles.list}>
            {proposal.days.map((day) => (
              <div key={day.weekday} style={styles.row}>
                <strong>{t.setup.hours.weekdays[day.weekday]}</strong>
                <select aria-label={`${t.setup.hours.weekdays[day.weekday]} ${t.setup.hours.dayMode}`} style={styles.input} value={day.mode} onChange={(event) => updateDay(day.weekday, { mode: event.target.value as OpeningDayMode })}>
                  {Object.entries(t.setup.hours.modes).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
                </select>
                {day.mode === "CUSTOM" ? <>
                  <input aria-label={`${t.setup.hours.weekdays[day.weekday]} ${t.setup.hours.opens}`} required style={styles.input} type="time" value={toInputTime(day.customOpen)} onChange={(event) => updateDay(day.weekday, { customOpen: toApiTime(event.target.value) })} />
                  <input aria-label={`${t.setup.hours.weekdays[day.weekday]} ${t.setup.hours.closes}`} required style={styles.input} type="time" value={toInputTime(day.customClose)} onChange={(event) => updateDay(day.weekday, { customClose: toApiTime(event.target.value) })} />
                </> : null}
              </div>
            ))}
          </div>
          <button disabled={Boolean(working)} style={styles.primaryButton} type="submit">{working === "preview" ? t.setup.hours.previewing : t.setup.hours.preview}</button>
        </form>
      ) : null}
      {notice ? <p role="status" style={styles.info}>{notice}</p> : null}
      {preview ? (
        <section aria-labelledby="affected-title" style={styles.card}>
          <h3 id="affected-title" style={styles.cardTitle}>{t.setup.hours.affected}</h3>
          {preview.affected.length ? <ul style={styles.list}>{preview.affected.map((item) => {
            const start = new Date(item.startAt);
            const end = new Date(item.endAt);
            return <li key={item.bookingId} style={styles.field}>
              <strong>{t.setup.hours.booking} {item.bookingId}</strong>
              <span>{t.setup.hours.resource} {item.resourceId}</span>
              <span><time dateTime={item.startAt}>{affectedDateFormatter.format(start)}, {affectedTimeFormatter.format(start)}</time> – <time dateTime={item.endAt}>{affectedTimeFormatter.format(end)}</time></span>
            </li>;
          })}</ul> : <p>{t.setup.hours.noAffected}</p>}
          {preview.ownerPasswordRequired ? <p style={styles.info}>{t.setup.hours.protectedNotice}</p> : null}
          <button disabled={working === "commit"} style={styles.primaryButton} type="button" onClick={() => preview.ownerPasswordRequired ? setPasswordOpen(true) : void commit()}>{working === "commit" ? t.setup.hours.committing : t.setup.hours.commit}</button>
        </section>
      ) : null}
      <OwnerPasswordDialog actionLabel={t.setup.hours.commit} error={passwordError} open={passwordOpen} onCancel={() => { setPasswordOpen(false); setPasswordError(undefined); }} onConfirm={commit} />
    </section>
  );
}
