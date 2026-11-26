import { useEffect, useState } from "react";
import type { components } from "@e7gz/api-client";
import { Link } from "react-router-dom";
import { errorCodeOf, type ApiClient } from "../../api";
import { messages as t } from "../../i18n/messages";
import { adminErrorMessage } from "../adminErrors";
import { adminDateTime, codeLabel } from "../operations/format";

type Alert = components["schemas"]["AdminAlert"];
type AlertFact = { label: string; value: string };

const privatePayloadKey = /(error|exception|message|stack)/iu;

function words(value: string) {
  return value
    .replace(/([a-z0-9])([A-Z])/gu, "$1 $2")
    .replaceAll("_", " ")
    .trim()
    .toLowerCase()
    .replace(/\b(id|otp)\b/gu, (part) => part.toUpperCase());
}

function sentence(value: string) {
  const result = words(value);
  return result ? `${result[0]!.toUpperCase()}${result.slice(1)}` : value;
}

function factValue(key: string, value: string | number | boolean) {
  if (typeof value === "boolean") return value ? "Yes" : "No";
  if (typeof value === "number") return String(value);
  if (/type$/iu.test(key)) return sentence(value);
  if (/at$/iu.test(key) && !Number.isNaN(Date.parse(value))) return adminDateTime(value);
  return value;
}

function alertFacts(alert: Alert): AlertFact[] {
  const payload = alert.payload ?? {};
  const facts = Object.entries(payload)
    .filter(([key, value]) => key !== "correlationId" && !privatePayloadKey.test(key) && ["string", "number", "boolean"].includes(typeof value))
    .map(([key, value]) => ({ label: sentence(key), value: factValue(key, value as string | number | boolean) }));
  facts.push({ label: "Correlation ID", value: alert.correlationId });
  return facts;
}

async function loadVenueAccountMap(api: ApiClient) {
  const entries: Array<[string, string]> = [];
  let cursor: string | undefined;
  do {
    const { data } = await api.GET("/api/v1/admin/venue-accounts", { params: { query: { limit: 100, ...(cursor ? { cursor } : {}) } } });
    if (!data) break;
    entries.push(...data.data.items.map((account): [string, string] => [account.id, account.venueId]));
    cursor = data.data.nextCursor ?? undefined;
  } while (cursor);
  return Object.fromEntries(entries);
}

export function AlertsScreen({ api }: { api: ApiClient }) {
  const [acknowledged, setAcknowledged] = useState(false);
  const [items, setItems] = useState<Alert[]>([]);
  const [cursor, setCursor] = useState<string | null>();
  const [venueByAccount, setVenueByAccount] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(true);
  const [failure, setFailure] = useState<string>();
  const [selected, setSelected] = useState<Alert>();
  const [note, setNote] = useState("");
  const [version, setVersion] = useState(0);
  useEffect(() => {
    let active = true;
    void Promise.all([
      api.GET("/api/v1/admin/alerts", { params: { query: { acknowledged, limit: 20 } } }),
      loadVenueAccountMap(api),
    ]).then(([alerts, accounts]) => { if (active) { setItems(alerts.data?.data.items ?? []); setCursor(alerts.data?.data.nextCursor); setVenueByAccount(accounts); setFailure(alerts.data ? undefined : adminErrorMessage(errorCodeOf(alerts.error))); setLoading(false); } }).catch(() => { if (active) { setFailure(t.networkError); setLoading(false); } });
    return () => { active = false; };
  }, [acknowledged, api, version]);
  const loadMore = async () => { if (!cursor) return; setLoading(true); try { const { data, error } = await api.GET("/api/v1/admin/alerts", { params: { query: { acknowledged, cursor, limit: 20 } } }); if (data) { setItems((current) => [...current, ...data.data.items]); setCursor(data.data.nextCursor); } else setFailure(adminErrorMessage(errorCodeOf(error))); } catch { setFailure(t.networkError); } finally { setLoading(false); } };
  const acknowledge = async () => {
    if (!selected) return; setFailure(undefined);
    try { const { data, error } = await api.POST("/api/v1/admin/alerts/{alertId}/acknowledge", { params: { path: { alertId: selected.id } }, body: { ...(note.trim() ? { reason: note.trim() } : {}) } }); if (!data) { setFailure(adminErrorMessage(errorCodeOf(error))); return; } setItems((current) => current.filter((item) => item.id !== selected.id)); setSelected(undefined); setNote(""); }
    catch { setFailure(t.networkError); }
  };
  const title = (alert: Alert) => alert.kind === "OWNER_PASSWORD_LONG_LOCK" ? t.e86.alerts.ownerPasswordLongLock : t.e86.alerts.operational;
  const venueLink = (alert: Alert) => alert.kind === "OWNER_PASSWORD_LONG_LOCK" ? venueByAccount[alert.subjectId] : undefined;
  return <section className="admin-page"><header className="page-heading page-heading--actions"><div><span className="page-eyebrow">{t.e86.operations}</span><h2>{t.e86.alerts.title}</h2><p>{t.e86.alerts.description}</p></div><div className="form-actions"><Link className="button button--secondary" to="/owner-reviews">{t.e86.alerts.ownerReviews}</Link><Link className="button button--secondary" to="/emergencies">{t.e86.alerts.emergencies}</Link></div></header>
    <label className="filter-control">{t.e86.alerts.filter}<select value={acknowledged ? "acknowledged" : "open"} onChange={(event) => { setLoading(true); setFailure(undefined); setItems([]); setCursor(undefined); setAcknowledged(event.target.value === "acknowledged"); }}><option value="open">{t.e86.alerts.open}</option><option value="acknowledged">{t.e86.alerts.acknowledged}</option></select></label>
    {failure && <div className="state-card state-card--error" role="alert">{failure} <button className="button button--primary" type="button" onClick={() => { setLoading(true); setFailure(undefined); setItems([]); setCursor(undefined); setVersion((value) => value + 1); }}>{t.retry}</button></div>}
    {!loading && !failure && items.length === 0 && <p className="state-card">{t.e86.alerts.empty}</p>}
    {items.length > 0 && <div className="data-card"><div className="table-scroll"><table className="alerts-table"><thead><tr><th>{t.e86.alerts.severity}</th><th>{t.e86.alerts.kind}</th><th>{t.e86.alerts.subject}</th><th>{t.e86.alerts.created}</th><th>{t.e86.actions}</th></tr></thead><tbody>{items.map((item) => { const venueId = venueLink(item); return <tr key={item.id}><td><span className="status-pill status-pill--neutral">{item.severity}</span></td><td><strong>{title(item)}</strong><small>{codeLabel(item.kind)}</small><dl className="alert-facts">{alertFacts(item).map((fact) => <div className="alert-fact" key={fact.label}><dt>{fact.label}</dt><dd>{fact.value}</dd></div>)}</dl></td><td className="alert-subject">{venueId ? <Link to={`/venues/${venueId}`}>{t.e86.alerts.openVenue}</Link> : <><span>{codeLabel(item.subjectType)}</span><small>{item.subjectId}</small></>}</td><td>{adminDateTime(item.createdAt)}</td><td>{item.acknowledgedAt ? adminDateTime(item.acknowledgedAt) : <button className="button button--compact button--primary" type="button" onClick={() => { setSelected(item); setNote(""); }}>{t.e86.alerts.acknowledge}</button>}</td></tr>; })}</tbody></table></div>{cursor && !loading && <div className="data-card-footer"><button className="button button--secondary" type="button" onClick={() => void loadMore()}>{t.e86.loadMore}</button></div>}</div>}
    {selected && <div className="action-dialog" role="dialog" aria-modal="true" aria-labelledby="acknowledge-alert-title"><h3 id="acknowledge-alert-title">{t.e86.alerts.acknowledge}</h3><label>{t.e86.alerts.note}<textarea maxLength={500} rows={3} value={note} onChange={(event) => setNote(event.target.value)} /></label><div className="form-actions"><button className="button button--primary" type="button" onClick={() => void acknowledge()}>{t.e86.alerts.acknowledge}</button><button className="button button--secondary" type="button" onClick={() => setSelected(undefined)}>{t.cancel}</button></div></div>}
    {loading && <p className="state-card" role="status">{t.loading}</p>}
  </section>;
}
