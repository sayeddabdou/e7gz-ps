import { useEffect, useRef, useState } from "react";
import type { components } from "@e7gz/api-client";
import { Link } from "react-router-dom";
import { createIdempotencyKey, errorCodeOf, idempotencyHeaders, type ApiClient } from "../../api";
import { messages as t } from "../../i18n/messages";
import { adminErrorMessage } from "../adminErrors";

type Account = components["schemas"]["AdminVenueAccountListItem"];
type Status = components["schemas"]["VenueAccountStatus"];

export function VenueAccountsScreen({ api }: { api: ApiClient }) {
  const [status, setStatus] = useState<Status | "">("");
  const [items, setItems] = useState<Account[]>([]);
  const [cursor, setCursor] = useState<string | null>();
  const [loading, setLoading] = useState(true);
  const [failure, setFailure] = useState<string>();
  const [notice, setNotice] = useState<string>();
  const [selected, setSelected] = useState<Account>();
  const [reason, setReason] = useState("");
  const [reasonError, setReasonError] = useState<string>();
  const [version, setVersion] = useState(0);
  const submitting = useRef(false);
  const idempotencyKey = useRef(createIdempotencyKey());
  const requestVersion = useRef(0);
  useEffect(() => { const requestId = ++requestVersion.current; let active = true; const query = { limit: 20, ...(status ? { accountStatus: status } : {}) }; void api.GET("/api/v1/admin/venue-accounts", { params: { query } }).then(({ data, error }) => { if (active && requestId === requestVersion.current) { setItems(data?.data.items ?? []); setCursor(data?.data.nextCursor); setFailure(data ? undefined : adminErrorMessage(errorCodeOf(error))); setLoading(false); } }).catch(() => { if (active && requestId === requestVersion.current) { setFailure(t.networkError); setLoading(false); } }); return () => { active = false; }; }, [api, status, version]);
  const loadMore = async () => { if (!cursor) return; const requestId = requestVersion.current; setLoading(true); try { const query = { limit: 20, cursor, ...(status ? { accountStatus: status } : {}) }; const { data, error } = await api.GET("/api/v1/admin/venue-accounts", { params: { query } }); if (requestId !== requestVersion.current) return; if (data) { setItems((current) => [...current, ...data.data.items]); setCursor(data.data.nextCursor); } else setFailure(adminErrorMessage(errorCodeOf(error))); } catch { if (requestId === requestVersion.current) setFailure(t.networkError); } finally { if (requestId === requestVersion.current) setLoading(false); } };
  const begin = (account: Account) => { setSelected(account); setReason(""); setReasonError(undefined); setFailure(undefined); idempotencyKey.current = createIdempotencyKey(); };
  const changeReason = (value: string) => { setReason(value); setReasonError(undefined); idempotencyKey.current = createIdempotencyKey(); };
  const apply = async () => {
    if (!selected || submitting.current) return;
    const cleanReason = reason.trim(); if (!cleanReason) { setReasonError(t.errors.fields.reason); return; }
    submitting.current = true; setFailure(undefined);
    const enabling = selected.accountStatus === "DISABLED";
    const path = enabling ? "/api/v1/admin/venue-accounts/{venueAccountId}/enable" as const : "/api/v1/admin/venue-accounts/{venueAccountId}/disable" as const;
    try {
      const { data, error } = await api.POST(path, { params: { path: { venueAccountId: selected.id }, header: idempotencyHeaders(idempotencyKey.current) }, body: { reason: cleanReason } });
      if (!data) { setFailure(adminErrorMessage(errorCodeOf(error))); return; }
      const nextStatus: Status = data.data.accountStatus;
      setItems((current) => current.map((item) => item.id === selected.id ? { ...item, accountStatus: nextStatus } : item).filter((item) => !status || item.accountStatus === status));
      setNotice(t.e86.venueAccounts.success(enabling ? "Enable" : "Disable", selected.venueName)); setSelected(undefined); setReason(""); idempotencyKey.current = createIdempotencyKey();
    } catch { setFailure(t.networkError); } finally { submitting.current = false; }
  };
  return <section className="admin-page"><header className="page-heading"><span className="page-eyebrow">{t.e86.operations}</span><h2>{t.e86.venueAccounts.title}</h2><p>{t.e86.venueAccounts.description}</p></header>
    {notice && <p className="notice notice--success" role="status">{notice}</p>}
    <label className="filter-control">{t.e86.venueAccounts.filter}<select value={status} onChange={(event) => { requestVersion.current += 1; setLoading(true); setFailure(undefined); setItems([]); setCursor(undefined); setStatus(event.target.value as Status | ""); }}><option value="">{t.e86.venueAccounts.all}</option><option value="ENABLED">{t.account.statuses.ENABLED}</option><option value="DISABLED">{t.account.statuses.DISABLED}</option></select></label>
    {failure && <div className="state-card state-card--error" role="alert">{failure} <button className="button button--primary" type="button" onClick={() => { requestVersion.current += 1; setLoading(true); setFailure(undefined); setItems([]); setCursor(undefined); setVersion((value) => value + 1); }}>{t.retry}</button></div>}
    {!loading && !failure && items.length === 0 && <p className="state-card">{t.e86.venueAccounts.empty}</p>}
    {items.length > 0 && <div className="data-card"><div className="table-scroll"><table><thead><tr><th>{t.e86.venueAccounts.venue}</th><th>{t.e86.venueAccounts.login}</th><th>{t.e86.venueAccounts.language}</th><th>{t.e86.status}</th><th>{t.e86.actions}</th></tr></thead><tbody>{items.map((item) => <tr key={item.id}><td><Link to={`/venues/${item.venueId}`}>{item.venueName}</Link></td><td>{item.loginIdentifier}</td><td>{item.languagePreference === "ar" ? "Arabic" : "English"}</td><td><span className={`status-pill ${item.accountStatus === "ENABLED" ? "status-pill--success" : "status-pill--neutral"}`}>{t.account.statuses[item.accountStatus]}</span></td><td><button className={`button button--compact ${item.accountStatus === "ENABLED" ? "button--danger" : "button--secondary"}`} type="button" onClick={() => begin(item)}>{item.accountStatus === "ENABLED" ? t.account.disable : t.account.enable}</button></td></tr>)}</tbody></table></div>{cursor && !loading && <div className="data-card-footer"><button className="button button--secondary" type="button" onClick={() => void loadMore()}>{t.e86.loadMore}</button></div>}</div>}
    {selected && <div className="action-dialog" role="dialog" aria-modal="true" aria-labelledby="venue-account-action-title"><h3 id="venue-account-action-title">{t.e86.venueAccounts.actionTitle(selected.accountStatus === "ENABLED" ? t.account.disable : t.account.enable, selected.venueName)}</h3><p>{selected.accountStatus === "ENABLED" ? t.e86.venueAccounts.disableWarning : t.e86.venueAccounts.enableNote}</p><label>{t.e86.venueAccounts.reason}<textarea rows={3} value={reason} onChange={(event) => changeReason(event.target.value)} />{reasonError && <small className="field-error">{reasonError}</small>}</label><div className="form-actions"><button className={selected.accountStatus === "ENABLED" ? "button button--danger" : "button button--primary"} type="button" onClick={() => void apply()}>{selected.accountStatus === "ENABLED" ? t.account.disable : t.account.enable}</button><button className="button button--secondary" type="button" onClick={() => setSelected(undefined)}>{t.cancel}</button></div></div>}
    {loading && <p className="state-card" role="status">{t.loading}</p>}
  </section>;
}
