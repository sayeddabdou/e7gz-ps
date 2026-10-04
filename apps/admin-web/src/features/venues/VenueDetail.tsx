import { useEffect, useState } from "react";
import type { components } from "@e7gz/api-client";
import { useParams } from "react-router-dom";
import { errorCodeOf, type ApiClient } from "../../api";
import { messages as t } from "../../i18n/messages";
import { adminErrorMessage } from "../adminErrors";
import { VenueAccountPanel } from "../venue-accounts/VenueAccountPanel";
import { AdminPhotoManager } from "./AdminPhotoManager";
import { VenueForm } from "./VenueForm";
import { AdminResourceManager } from "./AdminResourceManager";

type Detail = components["schemas"]["AdminVenueDetail"];
type Status = components["schemas"]["MarketplaceStatus"];
export function VenueDetail({ api }: { api: ApiClient }) {
  const { venueId = "" } = useParams();
  const [detail, setDetail] = useState<Detail>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();
  const [editing, setEditing] = useState(false);
  const [reason, setReason] = useState("");
  const [version, setVersion] = useState(0);
  useEffect(() => { let active = true; void api.GET("/api/v1/admin/venues/{venueId}", { params: { path: { venueId } } }).then(({ data, error: responseError }) => { if (active) { setDetail(data?.data); setError(data ? undefined : adminErrorMessage(errorCodeOf(responseError))); setLoading(false); } }).catch(() => { if (active) { setError(t.networkError); setLoading(false); } }); return () => { active = false; }; }, [api, venueId, version]);
  if (loading) return <p className="state-card" role="status">{t.loading}</p>;
  if (!detail) return <div className="state-card state-card--error" role="alert">{error ?? t.errors.unexpected} <button className="button button--primary" type="button" onClick={() => { setLoading(true); setVersion((v) => v + 1); }}>{t.retry}</button></div>;
  const status = detail.venue.marketplaceStatus;
  const changeStatus = async (target: "complete" | "activate" | "deactivate" | "suspend") => {
    const paths = { complete: "/api/v1/admin/venues/{venueId}/onboarding/complete", activate: "/api/v1/admin/venues/{venueId}/activate", deactivate: "/api/v1/admin/venues/{venueId}/deactivate", suspend: "/api/v1/admin/venues/{venueId}/suspend" } as const;
    setError(undefined);
    try {
      const result = await api.POST(paths[target], { params: { path: { venueId } }, body: { reason: reason.trim() } });
      if (result.data) { setDetail((current) => current ? { ...current, venue: result.data!.data } : current); setReason(""); } else setError(adminErrorMessage(errorCodeOf(result.error)));
    } catch { setError(t.networkError); }
  };
  const statusButton = (target: "complete" | "activate" | "deactivate" | "suspend", label: string) => <button className="button button--secondary" type="button" disabled={!reason.trim()} onClick={() => void changeStatus(target)}>{label}</button>;
  const actions: Array<[Exclude<Status, "ONBOARDING">, "activate" | "deactivate" | "suspend", string]> = [["ACTIVE", "activate", t.activate], ["INACTIVE", "deactivate", t.deactivate], ["SUSPENDED", "suspend", t.suspend]];
  return <section className="admin-page"><header className="page-heading page-heading--actions"><div><span className="page-eyebrow">{t.venues.managementEyebrow}</span><h2>{t.venues.details}: {detail.venue.name}</h2></div><button className="button button--secondary" type="button" onClick={() => setEditing((value) => !value)}>{editing ? t.cancel : t.edit}</button></header>{error && <p className="notice notice--error" role="alert">{error}</p>}
    <div className="venue-summary"><div><span>{t.venues.status}</span><strong className={`status-pill status-pill--${status.toLowerCase()}`}>{t.venues.statuses[status]}</strong></div><div><span>{t.venues.address}</span><strong>{detail.venue.address}</strong></div><div><span>{t.venues.phone}</span><strong>{detail.venue.phone}</strong></div><div><span>{t.venues.location}</span><strong>{detail.venue.location.lat}, {detail.venue.location.lng}</strong></div><div><span>{t.venues.timezone}</span><strong>{detail.venue.timezone}</strong></div><div><span>{t.venues.activities}</span><strong>{detail.venue.activities.map((activity) => t.activityLabels[activity]).join(", ")}</strong></div></div>
    {editing && <VenueForm api={api} venue={detail.venue} onSaved={(venue) => { setDetail({ ...detail, venue }); setEditing(false); }} />}
    <div className="action-card"><label>{t.venues.reason}<input value={reason} onChange={(event) => setReason(event.target.value)} /></label><div className="form-actions">{status === "ONBOARDING" ? statusButton("complete", t.completeOnboarding) : actions.filter(([target]) => target !== status).map(([, target, label]) => <span key={target}>{statusButton(target, label)} </span>)}</div></div>
    <div className="setup-grid"><VenueAccountPanel api={api} venueId={venueId} initial={detail.venueAccount} /><AdminResourceManager activities={detail.venue.activities} api={api} venueId={venueId} /><AdminPhotoManager api={api} venueId={venueId} /></div>
  </section>;
}
