import { useEffect, useState } from "react";
import type { components } from "@e7gz/api-client";
import { Link } from "react-router-dom";
import { errorCodeOf, type ApiClient } from "../../api";
import { messages as t } from "../../i18n/messages";
import { adminErrorMessage } from "../adminErrors";

type Venue = components["schemas"]["AdminVenue"];
export function VenueList({ api }: { api: ApiClient }) {
  const [items, setItems] = useState<Venue[]>([]);
  const [cursor, setCursor] = useState<string | null>();
  const [areaNames, setAreaNames] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(true);
  const [failure, setFailure] = useState<string>();
  const [version, setVersion] = useState(0);
  useEffect(() => { let active = true; void Promise.all([api.GET("/api/v1/admin/venues", { params: { query: { limit: 20 } } }), api.GET("/api/v1/admin/areas")]).then(([venues, areas]) => { if (active) { setItems(venues.data?.data.items ?? []); setCursor(venues.data?.data.nextCursor); setAreaNames(Object.fromEntries((areas.data?.data ?? []).map((area) => [area.id, area.name]))); setFailure(venues.data && areas.data ? undefined : adminErrorMessage(errorCodeOf(venues.error ?? areas.error))); setLoading(false); } }).catch(() => { if (active) { setFailure(t.networkError); setLoading(false); } }); return () => { active = false; }; }, [api, version]);
  const loadMore = async (next: string) => {
    setLoading(true); setFailure(undefined);
    try { const { data, error } = await api.GET("/api/v1/admin/venues", { params: { query: { limit: 20, cursor: next } } }); if (!data) { setFailure(adminErrorMessage(errorCodeOf(error))); return; } setItems((current) => [...current, ...data.data.items]); setCursor(data.data.nextCursor); }
    catch { setFailure(t.networkError); } finally { setLoading(false); }
  };
  return <section className="admin-page"><header className="page-heading page-heading--actions"><div><span className="page-eyebrow">{t.marketplaceStructure}</span><h2>{t.venues.title}</h2></div><Link className="button button--primary" to="/venue-onboarding"><span aria-hidden="true">＋</span>{t.venues.onboarding}</Link></header>
    {failure && <div className="state-card state-card--error" role="alert">{failure} <button className="button button--primary" type="button" onClick={() => { setLoading(true); setFailure(undefined); setVersion((v) => v + 1); }}>{t.retry}</button></div>}
    {!loading && !failure && items.length === 0 && <p className="state-card">{t.venues.empty}</p>}
    {items.length > 0 && <div className="data-card"><div className="table-scroll"><table><thead><tr><th>{t.venues.name}</th><th>{t.venues.area}</th><th>{t.venues.status}</th></tr></thead><tbody>{items.map((venue) => <tr key={venue.id}><td><Link to={`/venues/${venue.id}`}>{venue.name}</Link></td><td>{areaNames[venue.areaId] ?? "—"}</td><td><span className={`status-pill status-pill--${venue.marketplaceStatus.toLowerCase()}`}>{t.venues.statuses[venue.marketplaceStatus]}</span></td></tr>)}</tbody></table></div>{!loading && cursor && <div className="data-card-footer"><button className="button button--secondary" type="button" onClick={() => void loadMore(cursor)}>{t.venues.loadMore}</button></div>}</div>}
    {loading && <p className="state-card" role="status">{t.loading}</p>}
  </section>;
}
