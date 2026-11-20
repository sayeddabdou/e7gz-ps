import { useEffect, useRef, useState, type FormEvent } from "react";
import type { components } from "@e7gz/api-client";
import { createIdempotencyKey, errorCodeOf, errorFieldOf, idempotencyHeaders, type ApiClient } from "../../api";
import { messages as t } from "../../i18n/messages";
import { adminErrorMessage } from "../adminErrors";

type Account = components["schemas"]["AdminPointsAccount"];
type Settings = components["schemas"]["AdminPointsSettings"];
type AccountsState = { status: "loading" } | { status: "error"; message: string } | { status: "ready"; items: Account[]; nextCursor: string | null };
type SettingsState = { status: "loading" } | { status: "error"; message: string } | { status: "ready"; data: Settings };
const dateTime = (value: string) => new Date(value).toLocaleString("en-EG");

export function PointsScreen({ api }: { api: ApiClient }) {
  const [accounts, setAccounts] = useState<AccountsState>({ status: "loading" });
  const [settings, setSettings] = useState<SettingsState>({ status: "loading" });
  const [queryDraft, setQueryDraft] = useState("");
  const [query, setQuery] = useState("");
  const [version, setVersion] = useState(0);
  const [selected, setSelected] = useState<Account>();
  const [amount, setAmount] = useState("");
  const [reason, setReason] = useState("");
  const [attendedPoints, setAttendedPoints] = useState("");
  const [referralPoints, setReferralPoints] = useState("");
  const [pointsPerFeeUnit, setPointsPerFeeUnit] = useState("");
  const [settingsReason, setSettingsReason] = useState("");
  const [message, setMessage] = useState<{ kind: "error" | "success"; text: string }>();
  const [settingsFieldError, setSettingsFieldError] = useState<string>();
  const [adjustWorking, setAdjustWorking] = useState(false);
  const [settingsWorking, setSettingsWorking] = useState(false);
  const adjustInFlight = useRef(false);
  const settingsInFlight = useRef(false);
  const adjustIdempotencyKey = useRef(createIdempotencyKey());
  const settingsIdempotencyKey = useRef(createIdempotencyKey());

  useEffect(() => {
    let active = true;
    void api.GET("/api/v1/admin/points", { params: { query: { ...(query ? { q: query } : {}), limit: 50 } } }).then(({ data, error }) => { if (active) setAccounts(data ? { status: "ready", items: data.data.items, nextCursor: data.data.nextCursor } : { status: "error", message: adminErrorMessage(errorCodeOf(error), errorFieldOf(error)) }); }).catch(() => { if (active) setAccounts({ status: "error", message: t.networkError }); });
    return () => { active = false; };
  }, [api, query, version]);
  useEffect(() => {
    let active = true;
    void api.GET("/api/v1/admin/points/settings").then(({ data, error }) => {
      if (!active) return;
      if (!data) { setSettings({ status: "error", message: adminErrorMessage(errorCodeOf(error), errorFieldOf(error)) }); return; }
      setSettings({ status: "ready", data: data.data }); setAttendedPoints(String(data.data.attendedPoints.value)); setReferralPoints(String(data.data.referralPoints.value)); setPointsPerFeeUnit(String(data.data.pointsPerFeeUnit.value));
    }).catch(() => { if (active) setSettings({ status: "error", message: t.networkError }); });
    return () => { active = false; };
  }, [api, version]);
  const reload = () => { setVersion((value) => value + 1); };
  const loadMore = async () => {
    if (accounts.status !== "ready" || !accounts.nextCursor) return;
    const result = await api.GET("/api/v1/admin/points", { params: { query: { ...(query ? { q: query } : {}), cursor: accounts.nextCursor, limit: 50 } } });
    if (result.data) setAccounts({ status: "ready", items: [...accounts.items, ...result.data.data.items], nextCursor: result.data.data.nextCursor });
  };
  const adjust = async (event: FormEvent) => {
    event.preventDefault(); if (!selected || !reason.trim() || !Number(amount) || adjustInFlight.current) return;
    adjustInFlight.current = true; setAdjustWorking(true);
    setMessage(undefined);
    try {
      const result = await api.POST("/api/v1/admin/points/adjust", { params: { header: idempotencyHeaders(adjustIdempotencyKey.current) }, body: { userId: selected.userId, amount: Number(amount), reason: reason.trim() } });
      if (result.error) {
        const code = errorCodeOf(result.error);
        setMessage({ kind: "error", text: adminErrorMessage(code, errorFieldOf(result.error)) });
        if (code === "ADMIN_ACTION_NOT_ALLOWED") { setSelected(undefined); setAmount(""); setReason(""); adjustIdempotencyKey.current = createIdempotencyKey(); reload(); }
      }
      else { setMessage({ kind: "success", text: `Applied ${result.data.data.amount} points. Balance is ${result.data.data.balanceAfter}.` }); setSelected(undefined); setAmount(""); setReason(""); adjustIdempotencyKey.current = createIdempotencyKey(); reload(); }
    } catch { setMessage({ kind: "error", text: t.networkError }); }
    finally { adjustInFlight.current = false; setAdjustWorking(false); }
  };
  const saveSettings = async (event: FormEvent) => {
    event.preventDefault(); if (!settingsReason.trim() || settingsInFlight.current) return;
    settingsInFlight.current = true; setSettingsWorking(true);
    setSettingsFieldError(undefined); setMessage(undefined);
    try {
      const result = await api.PUT("/api/v1/admin/points/settings", { params: { header: idempotencyHeaders(settingsIdempotencyKey.current) }, body: { attendedPoints: Number(attendedPoints), referralPoints: Number(referralPoints), pointsPerFeeUnit: Number(pointsPerFeeUnit), reason: settingsReason.trim() } });
      if (result.error) {
        const code = errorCodeOf(result.error);
        const field = errorFieldOf(result.error);
        if (field === "pointsPerFeeUnit") setSettingsFieldError(adminErrorMessage(code, field));
        else setMessage({ kind: "error", text: adminErrorMessage(code, field) });
        if (code === "ADMIN_ACTION_NOT_ALLOWED") { setSettingsReason(""); settingsIdempotencyKey.current = createIdempotencyKey(); reload(); }
      } else { setMessage({ kind: "success", text: "Points settings saved for future events." }); setSettingsReason(""); settingsIdempotencyKey.current = createIdempotencyKey(); reload(); }
    } catch { setMessage({ kind: "error", text: t.networkError }); }
    finally { settingsInFlight.current = false; setSettingsWorking(false); }
  };
  return <section className="admin-page" aria-labelledby="points-title">
    <header className="page-heading"><span className="page-eyebrow">Points administration</span><h2 id="points-title">Points</h2></header>
    {message && <p className={`notice notice--${message.kind}`} role={message.kind === "error" ? "alert" : "status"}>{message.text}</p>}
    {settings.status === "loading" && <p className="state-card" role="status">Loading points settings…</p>}
    {settings.status === "error" && <p className="notice notice--error" role="alert">{settings.message}</p>}
    {settings.status === "ready" && <form className="form-card form-grid" onSubmit={(event) => void saveSettings(event)}><h3>Points settings</h3><label>Attended booking points<input min="0" type="number" value={attendedPoints} onChange={(event) => { setAttendedPoints(event.target.value); settingsIdempotencyKey.current = createIdempotencyKey(); }} /><small>Effective from {dateTime(settings.data.attendedPoints.effectiveFrom)}</small></label><label>Referral points<input min="0" type="number" value={referralPoints} onChange={(event) => { setReferralPoints(event.target.value); settingsIdempotencyKey.current = createIdempotencyKey(); }} /><small>Effective from {dateTime(settings.data.referralPoints.effectiveFrom)}</small></label><label>Points per fee unit<input aria-invalid={Boolean(settingsFieldError)} min="2" step="2" type="number" value={pointsPerFeeUnit} onChange={(event) => { setPointsPerFeeUnit(event.target.value); settingsIdempotencyKey.current = createIdempotencyKey(); }} /><small>Effective from {dateTime(settings.data.pointsPerFeeUnit.effectiveFrom)}</small>{settingsFieldError && <span className="field-error">{settingsFieldError}</span>}</label><label className="form-span">Reason<textarea maxLength={500} rows={3} value={settingsReason} onChange={(event) => { setSettingsReason(event.target.value); settingsIdempotencyKey.current = createIdempotencyKey(); }} /></label><div className="form-actions form-span"><button className="button button--primary" disabled={settingsWorking || !settingsReason.trim()}>Save points settings</button></div></form>}
    <form className="filter-bar" onSubmit={(event) => { event.preventDefault(); setAccounts({ status: "loading" }); setQuery(queryDraft.trim()); }}><label>Search users<input value={queryDraft} onChange={(event) => setQueryDraft(event.target.value)} /></label><button className="button button--primary">Search</button></form>
    {selected && <form className="action-card" aria-label={`Adjust points for ${selected.username}`} onSubmit={(event) => void adjust(event)}><label>Amount (positive adds, negative deducts)<input step="1" type="number" value={amount} onChange={(event) => { setAmount(event.target.value); adjustIdempotencyKey.current = createIdempotencyKey(); }} /></label><label>Reason<textarea maxLength={500} rows={3} value={reason} onChange={(event) => { setReason(event.target.value); adjustIdempotencyKey.current = createIdempotencyKey(); }} /></label><div className="form-actions"><button className="button button--primary" disabled={adjustWorking || !reason.trim() || !Number(amount)}>Apply adjustment</button><button className="button button--secondary" disabled={adjustWorking} type="button" onClick={() => { setSelected(undefined); setAmount(""); setReason(""); adjustIdempotencyKey.current = createIdempotencyKey(); }}>Cancel</button></div></form>}
    {accounts.status === "loading" && <p className="state-card" role="status">Loading points accounts…</p>}
    {accounts.status === "error" && <p className="notice notice--error" role="alert">{accounts.message}</p>}
    {accounts.status === "ready" && accounts.items.length === 0 && <p className="state-card">No points accounts found.</p>}
    {accounts.status === "ready" && accounts.items.length > 0 && <div className="data-card"><div className="table-scroll"><table><thead><tr><th>User</th><th>Balance</th><th>Booking rewards</th><th>Referral rewards</th><th /></tr></thead><tbody>{accounts.items.map((account) => <tr key={account.userId}><td><strong>{account.username}</strong><br /><small>{account.fullName}</small></td><td>{account.balance}</td><td>{account.bookingRewards}</td><td>{account.referralRewards}</td><td><button className="button button--compact button--secondary" onClick={() => { setSelected(account); setAmount(""); setReason(""); adjustIdempotencyKey.current = createIdempotencyKey(); }}>Adjust</button></td></tr>)}</tbody></table></div>{accounts.nextCursor && <div className="data-card-footer"><button className="button button--secondary" onClick={() => void loadMore()}>Load more</button></div>}</div>}
  </section>;
}
