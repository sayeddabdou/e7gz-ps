import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import type { components } from "@e7gz/api-client";
import { createIdempotencyKey, errorCodeOf, errorFieldOf, idempotencyHeaders, type ApiClient } from "../../api";
import { messages as t } from "../../i18n/messages";
import { adminErrorMessage } from "../adminErrors";

type AdminFees = components["schemas"]["AdminFees"];
type AdminGlobalRules = components["schemas"]["AdminGlobalRules"];
type LoadState<T> = { status: "loading" } | { status: "error"; message: string } | { status: "ready"; data: T };
const dateTime = (value: string) => new Intl.DateTimeFormat("en-EG", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value));
const money = (amount: string) => ({ amount, currency: "EGP" as const });
const period = (value: string) => {
  const match = /^P(?:(\d+)Y)?(?:(\d+)M)?(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?)?$/.exec(value);
  if (!match) return value;
  const units = [[match[1], "year"], [match[2], "month"], [match[3], "week"], [match[4], "day"], [match[5], "hour"], [match[6], "minute"]] as const;
  const parts = units.flatMap(([amount, label]) => amount && Number(amount) ? [`${amount} ${label}${amount === "1" ? "" : "s"}`] : []);
  return parts.length ? parts.join(" ") : "0 days";
};

export function FeesScreen({ api }: { api: ApiClient }) {
  const [state, setState] = useState<LoadState<AdminFees>>({ status: "loading" });
  const [version, setVersion] = useState(0);
  const [mode, setMode] = useState<"PER_BOOKING" | "PER_HOUR">("PER_BOOKING");
  const [bookingFee, setBookingFee] = useState("0.00");
  const [bookingFeePerHour, setBookingFeePerHour] = useState("0.00");
  const [tournamentCreationFee, setTournamentCreationFee] = useState("0.00");
  const [reason, setReason] = useState("");
  const [fieldError, setFieldError] = useState<{ field?: string; message: string }>();
  const [saving, setSaving] = useState(false);
  const inFlight = useRef(false);
  const idempotencyKey = useRef(createIdempotencyKey());

  const reload = useCallback(() => { setState({ status: "loading" }); setVersion((value) => value + 1); }, []);
  useEffect(() => {
    let active = true;
    void api.GET("/api/v1/admin/fees").then(({ data, error }) => {
      if (!active) return;
      if (!data) { setState({ status: "error", message: adminErrorMessage(errorCodeOf(error), errorFieldOf(error)) }); return; }
      const next = data.data;
      setState({ status: "ready", data: next });
      setMode(next.bookingFeeMode.value);
      setBookingFee(next.bookingFee.value.amount);
      setBookingFeePerHour(next.bookingFeePerHour.value.amount);
      setTournamentCreationFee(next.tournamentCreationFee.value.amount);
    }).catch(() => { if (active) setState({ status: "error", message: t.networkError }); });
    return () => { active = false; };
  }, [api, version]);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!reason.trim()) { setFieldError({ field: "reason", message: t.errors.fields.reason }); return; }
    if (inFlight.current) return;
    inFlight.current = true;
    setSaving(true); setFieldError(undefined);
    try {
      const result = await api.PUT("/api/v1/admin/fees", {
        params: { header: idempotencyHeaders(idempotencyKey.current) },
        body: { bookingFeeMode: mode, bookingFee: money(bookingFee), bookingFeePerHour: money(bookingFeePerHour), tournamentCreationFee: money(tournamentCreationFee), reason: reason.trim() },
      });
      if (result.error) {
        const code = errorCodeOf(result.error);
        const field = errorFieldOf(result.error);
        setFieldError({ field, message: adminErrorMessage(code, field) });
        if (code === "ADMIN_ACTION_NOT_ALLOWED") { setReason(""); idempotencyKey.current = createIdempotencyKey(); reload(); }
      } else { setReason(""); idempotencyKey.current = createIdempotencyKey(); reload(); }
    } catch { setFieldError({ message: t.networkError }); }
    finally { inFlight.current = false; setSaving(false); }
  };

  return <section className="admin-page" aria-labelledby="fees-title">
    <header className="page-heading"><span className="page-eyebrow">Configuration</span><h2 id="fees-title">Platform Fees</h2></header>
    {state.status === "loading" && <p className="state-card" role="status">{t.loading}</p>}
    {state.status === "error" && <div className="state-card state-card--error" role="alert"><p>{state.message}</p><button className="button button--primary" onClick={reload}>Retry</button></div>}
    {state.status === "ready" && <form className="form-card form-grid" onSubmit={(event) => void submit(event)}>
      <p className="notice notice--info form-span">{t.e75.feesFutureOnly}</p>
      <label>Booking fee mode<select value={mode} onChange={(event) => { setMode(event.target.value as typeof mode); idempotencyKey.current = createIdempotencyKey(); }}><option value="PER_BOOKING">Per booking</option><option value="PER_HOUR">Per hour</option></select><small>Effective from {dateTime(state.data.bookingFeeMode.effectiveFrom)}</small></label>
      <label>Per-booking fee<input aria-invalid={fieldError?.field === "bookingFee"} inputMode="decimal" value={bookingFee} onChange={(event) => { setBookingFee(event.target.value); idempotencyKey.current = createIdempotencyKey(); }} /><small>Effective from {dateTime(state.data.bookingFee.effectiveFrom)}</small>{fieldError?.field === "bookingFee" && <span className="field-error">{fieldError.message}</span>}</label>
      <label>Per-hour fee<input aria-invalid={fieldError?.field === "bookingFeePerHour"} inputMode="decimal" value={bookingFeePerHour} onChange={(event) => { setBookingFeePerHour(event.target.value); idempotencyKey.current = createIdempotencyKey(); }} /><small>Effective from {dateTime(state.data.bookingFeePerHour.effectiveFrom)}</small>{fieldError?.field === "bookingFeePerHour" && <span className="field-error">{fieldError.message}</span>}</label>
      <label>Tournament creation fee<input aria-invalid={fieldError?.field === "tournamentCreationFee"} inputMode="decimal" value={tournamentCreationFee} onChange={(event) => { setTournamentCreationFee(event.target.value); idempotencyKey.current = createIdempotencyKey(); }} /><small>Effective from {dateTime(state.data.tournamentCreationFee.effectiveFrom)}</small>{fieldError?.field === "tournamentCreationFee" && <span className="field-error">{fieldError.message}</span>}</label>
      <label className="form-span">Reason<textarea maxLength={500} rows={3} value={reason} onChange={(event) => { setReason(event.target.value); idempotencyKey.current = createIdempotencyKey(); }} />{fieldError?.field === "reason" && <span className="field-error">{fieldError.message}</span>}</label>
      {fieldError && !fieldError.field && <p className="notice notice--error form-span" role="alert">{fieldError.message}</p>}
      {fieldError?.field && fieldError.field !== "reason" && <p className="notice notice--error form-span" role="alert">{fieldError.message}</p>}
      <div className="form-actions form-span"><button className="button button--primary" disabled={saving || !reason.trim()} type="submit">{saving ? t.saving : "Save fees"}</button></div>
    </form>}
  </section>;
}

export function GlobalRulesScreen({ api }: { api: ApiClient }) {
  const [state, setState] = useState<LoadState<AdminGlobalRules>>({ status: "loading" });
  const [version, setVersion] = useState(0);
  useEffect(() => {
    let active = true;
    void api.GET("/api/v1/admin/global-rules").then(({ data, error }) => {
      if (active) setState(data ? { status: "ready", data: data.data } : { status: "error", message: adminErrorMessage(errorCodeOf(error), errorFieldOf(error)) });
    }).catch(() => { if (active) setState({ status: "error", message: t.networkError }); });
    return () => { active = false; };
  }, [api, version]);
  const retry = () => { setState({ status: "loading" }); setVersion((value) => value + 1); };
  return <section className="admin-page" aria-labelledby="rules-title">
    <header className="page-heading"><span className="page-eyebrow">Read-only configuration</span><h2 id="rules-title">Global Rules</h2></header>
    <p className="notice notice--info">{t.e75.globalRulesReadOnly}</p>
    {state.status === "loading" && <p className="state-card" role="status">{t.loading}</p>}
    {state.status === "error" && <div className="state-card state-card--error" role="alert"><p>{state.message}</p><button className="button button--primary" onClick={retry}>Retry</button></div>}
    {state.status === "ready" && <div className="data-card"><div className="table-scroll"><table><thead><tr><th>Penalty stage</th><th>Consecutive no-shows</th><th>Ban duration</th><th>Repeats</th></tr></thead><tbody>{state.data.noShowLadder.map((step) => <tr key={`${step.penaltyStage}-${step.consecutiveNoShows}`}><td>{step.penaltyStage}</td><td>{step.consecutiveNoShows}</td><td>{period(step.banDuration)}</td><td>{step.recurring ? "Recurring" : "No"}</td></tr>)}</tbody></table></div></div>}
  </section>;
}
