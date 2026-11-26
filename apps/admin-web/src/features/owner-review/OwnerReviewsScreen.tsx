import { useEffect, useRef, useState } from "react";
import type { components } from "@e7gz/api-client";
import { Link } from "react-router-dom";
import { errorCodeOf, type ApiClient } from "../../api";
import { messages as t } from "../../i18n/messages";
import { adminErrorMessage } from "../adminErrors";
import { adminDateTime, codeLabel } from "../operations/format";

type Review = components["schemas"]["AdminOwnerReview"];

export function OwnerReviewsScreen({ api }: { api: ApiClient }) {
  const [resolved, setResolved] = useState(false);
  const [items, setItems] = useState<Review[]>([]);
  const [cursor, setCursor] = useState<string | null>();
  const [loading, setLoading] = useState(true);
  const [failure, setFailure] = useState<string>();
  const [version, setVersion] = useState(0);
  const requestVersion = useRef(0);
  useEffect(() => {
    const requestId = ++requestVersion.current;
    let active = true;
    void api.GET("/api/v1/admin/owner-reviews", { params: { query: { resolved, limit: 20 } } })
      .then(({ data, error }) => { if (active && requestId === requestVersion.current) { setItems(data?.data.items ?? []); setCursor(data?.data.nextCursor); setFailure(data ? undefined : adminErrorMessage(errorCodeOf(error))); setLoading(false); } })
      .catch(() => { if (active && requestId === requestVersion.current) { setFailure(t.networkError); setLoading(false); } });
    return () => { active = false; };
  }, [api, resolved, version]);
  const loadMore = async () => {
    if (!cursor) return; const requestId = requestVersion.current; setLoading(true); setFailure(undefined);
    try { const { data, error } = await api.GET("/api/v1/admin/owner-reviews", { params: { query: { resolved, cursor, limit: 20 } } }); if (requestId !== requestVersion.current) return; if (data) { setItems((current) => [...current, ...data.data.items]); setCursor(data.data.nextCursor); } else setFailure(adminErrorMessage(errorCodeOf(error))); }
    catch { if (requestId === requestVersion.current) setFailure(t.networkError); } finally { if (requestId === requestVersion.current) setLoading(false); }
  };
  return <section className="admin-page"><header className="page-heading"><span className="page-eyebrow">{t.e86.operations}</span><h2>{t.e86.ownerReviews.title}</h2><p>{t.e86.ownerReviews.description}</p></header>
    <label className="filter-control">{t.e86.ownerReviews.filter}<select value={resolved ? "resolved" : "open"} onChange={(event) => { requestVersion.current += 1; setLoading(true); setFailure(undefined); setItems([]); setCursor(undefined); setResolved(event.target.value === "resolved"); }}><option value="open">{t.e86.ownerReviews.open}</option><option value="resolved">{t.e86.ownerReviews.resolved}</option></select></label>
    {failure && <div className="state-card state-card--error" role="alert">{failure} <button className="button button--primary" type="button" onClick={() => { requestVersion.current += 1; setLoading(true); setFailure(undefined); setItems([]); setCursor(undefined); setVersion((value) => value + 1); }}>{t.retry}</button></div>}
    {!loading && !failure && items.length === 0 && <p className="state-card">{t.e86.ownerReviews.empty}</p>}
    {items.length > 0 && <div className="data-card"><div className="table-scroll"><table><thead><tr><th>{t.e86.ownerReviews.venue}</th><th>{t.e86.ownerReviews.resource}</th><th>{t.e86.ownerReviews.entered}</th><th>{t.e86.ownerReviews.resolution}</th></tr></thead><tbody>{items.map((item) => <tr key={item.id}><td><Link to={`/venues/${item.venueId}`}>{item.venueName}</Link></td><td>{item.resourceName}</td><td>{adminDateTime(item.enteredAt)}</td><td>{codeLabel(item.resolution)}</td></tr>)}</tbody></table></div>{cursor && !loading && <div className="data-card-footer"><button className="button button--secondary" type="button" onClick={() => void loadMore()}>{t.e86.loadMore}</button></div>}</div>}
    {loading && <p className="state-card" role="status">{t.loading}</p>}
  </section>;
}
