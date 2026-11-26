import { useEffect, useRef, useState } from "react";
import type { components } from "@e7gz/api-client";
import { Link } from "react-router-dom";
import { errorCodeOf, type ApiClient } from "../../api";
import { messages as t } from "../../i18n/messages";
import { adminErrorMessage } from "../adminErrors";
import { adminDateTime, codeLabel } from "../operations/format";

type Emergency = components["schemas"]["AdminEmergency"];
type Status = components["schemas"]["EmergencyStatus"];

export function EmergenciesScreen({ api }: { api: ApiClient }) {
  const [status, setStatus] = useState<Status | "">("");
  const [items, setItems] = useState<Emergency[]>([]);
  const [cursor, setCursor] = useState<string | null>();
  const [loading, setLoading] = useState(true);
  const [failure, setFailure] = useState<string>();
  const [version, setVersion] = useState(0);
  const requestVersion = useRef(0);
  useEffect(() => { const requestId = ++requestVersion.current; let active = true; const query = { limit: 20, ...(status ? { status } : {}) }; void api.GET("/api/v1/admin/emergencies", { params: { query } }).then(({ data, error }) => { if (active && requestId === requestVersion.current) { setItems(data?.data.items ?? []); setCursor(data?.data.nextCursor); setFailure(data ? undefined : adminErrorMessage(errorCodeOf(error))); setLoading(false); } }).catch(() => { if (active && requestId === requestVersion.current) { setFailure(t.networkError); setLoading(false); } }); return () => { active = false; }; }, [api, status, version]);
  const loadMore = async () => { if (!cursor) return; const requestId = requestVersion.current; setLoading(true); try { const query = { limit: 20, cursor, ...(status ? { status } : {}) }; const { data, error } = await api.GET("/api/v1/admin/emergencies", { params: { query } }); if (requestId !== requestVersion.current) return; if (data) { setItems((current) => [...current, ...data.data.items]); setCursor(data.data.nextCursor); } else setFailure(adminErrorMessage(errorCodeOf(error))); } catch { if (requestId === requestVersion.current) setFailure(t.networkError); } finally { if (requestId === requestVersion.current) setLoading(false); } };
  return <section className="admin-page"><header className="page-heading"><span className="page-eyebrow">{t.e86.operations}</span><h2>{t.e86.emergencies.title}</h2><p>{t.e86.emergencies.description}</p></header>
    <label className="filter-control">{t.e86.emergencies.filter}<select value={status} onChange={(event) => { requestVersion.current += 1; setLoading(true); setFailure(undefined); setItems([]); setCursor(undefined); setStatus(event.target.value as Status | ""); }}><option value="">{t.e86.emergencies.all}</option><option value="ACTIVE">Active</option><option value="AWAITING_CONFIRMATION">Awaiting confirmation</option><option value="ENDED">Ended</option><option value="CONVERTED">Converted</option></select></label>
    {failure && <div className="state-card state-card--error" role="alert">{failure} <button className="button button--primary" type="button" onClick={() => { requestVersion.current += 1; setLoading(true); setFailure(undefined); setItems([]); setCursor(undefined); setVersion((value) => value + 1); }}>{t.retry}</button></div>}
    {!loading && !failure && items.length === 0 && <p className="state-card">{t.e86.emergencies.empty}</p>}
    {items.length > 0 && <div className="data-card"><div className="table-scroll"><table><thead><tr><th>{t.e86.emergencies.venue}</th><th>{t.e86.emergencies.scope}</th><th>{t.e86.emergencies.reason}</th><th>{t.e86.emergencies.declared}</th><th>{t.e86.emergencies.expectedEnd}</th><th>{t.e86.status}</th></tr></thead><tbody>{items.map((item) => <tr key={item.id}><td><Link to={`/venues/${item.venueId}`}>{item.venueName}</Link><small>{item.resourceName ?? ""}</small></td><td>{codeLabel(item.scope)}</td><td>{item.reason}</td><td>{adminDateTime(item.declaredAt)}</td><td>{adminDateTime(item.expectedEndAt)}</td><td><span className="status-pill status-pill--neutral">{codeLabel(item.status)}</span></td></tr>)}</tbody></table></div>{cursor && !loading && <div className="data-card-footer"><button className="button button--secondary" type="button" onClick={() => void loadMore()}>{t.e86.loadMore}</button></div>}</div>}
    {loading && <p className="state-card" role="status">{t.loading}</p>}
  </section>;
}
