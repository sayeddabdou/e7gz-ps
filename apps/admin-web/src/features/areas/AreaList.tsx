import { useEffect, useState, type FormEvent } from "react";
import type { components } from "@e7gz/api-client";
import { errorCodeOf, errorFieldOf, type ApiClient } from "../../api";
import { adminErrorMessage } from "../adminErrors";
import { parseLocation } from "../venues/location";
import { messages as t } from "../../i18n/messages";

type AdminArea = components["schemas"]["AdminArea"];

type State = { status: "loading" } | { status: "success"; areas: AdminArea[] } | { status: "error"; message: string };

export function AreaList({ api }: { api: ApiClient }) {
  const [state, setState] = useState<State>({ status: "loading" });
  const [version, setVersion] = useState(0);
  const [editing, setEditing] = useState<AdminArea | "new">();
  const [message, setMessage] = useState<string>();

  useEffect(() => {
    let active = true;
    void api.GET("/api/v1/admin/areas").then(({ data, error }) => {
      if (active) setState(data ? { status: "success", areas: data.data } : { status: "error", message: adminErrorMessage(errorCodeOf(error), errorFieldOf(error)) });
    }).catch(() => { if (active) setState({ status: "error", message: t.networkError }); });
    return () => { active = false; };
  }, [api, version]);

  const reload = () => { setState({ status: "loading" }); setVersion((value) => value + 1); };
  const changeStatus = async (area: AdminArea) => {
    setMessage(undefined);
    const path = area.active ? "/api/v1/admin/areas/{areaId}/deactivate" as const : "/api/v1/admin/areas/{areaId}/activate" as const;
    try {
      const result = await api.POST(path, { params: { path: { areaId: area.id } } });
      if (result.error) setMessage(adminErrorMessage(errorCodeOf(result.error), errorFieldOf(result.error))); else reload();
    } catch { setMessage(t.networkError); }
  };

  return (
    <section className="admin-page" aria-labelledby="areas-title">
      <header className="page-heading page-heading--actions"><div><span className="page-eyebrow">{t.marketplaceStructure}</span><h2 id="areas-title">{t.areasTitle}</h2></div>
      <button className="button button--primary" type="button" onClick={() => setEditing("new")}><span aria-hidden="true">＋</span>{t.areas.create}</button></header>
      {message && <p className="notice notice--error" role="alert">{message}</p>}
      {editing && <AreaForm api={api} area={editing === "new" ? undefined : editing} onCancel={() => setEditing(undefined)} onSaved={() => { setEditing(undefined); reload(); }} />}
      {state.status === "loading" && <p className="state-card" role="status">{t.loading}</p>}
      {state.status === "error" && (
        <div className="state-card state-card--error" role="alert">
          <p>{state.message}</p>
          <button className="button button--primary" type="button" onClick={reload}>
            {t.retry}
          </button>
        </div>
      )}
      {state.status === "success" && state.areas.length === 0 && <p className="state-card">{t.empty}</p>}
      {state.status === "success" && state.areas.length > 0 && (
        <div className="data-card"><div className="table-scroll"><table>
          <thead><tr><th>{t.areas.name}</th><th>{t.areas.nameAr}</th><th>{t.areas.status}</th><th>{t.areas.actions}</th></tr></thead>
          <tbody>
            {state.areas.map((area) => (
              <tr key={area.id}>
                <td>{area.name}</td>
                <td lang="ar">{area.nameAr ?? "—"}</td>
                <td><span className={`status-pill ${area.active ? "status-pill--success" : "status-pill--neutral"}`}>{area.active ? t.areaActive : t.areaInactive}</span></td>
                <td className="table-actions"><button className="button button--compact button--secondary" type="button" onClick={() => setEditing(area)}>{t.edit}</button> <button className="button button--compact button--secondary" type="button" onClick={() => void changeStatus(area)}>{area.active ? t.deactivate : t.activate}</button></td>
              </tr>
            ))}
          </tbody>
        </table></div></div>
      )}
    </section>
  );
}

function AreaForm({ api, area, onCancel, onSaved }: { api: ApiClient; area?: AdminArea; onCancel: () => void; onSaved: () => void }) {
  const [name, setName] = useState(area?.name ?? "");
  const [nameAr, setNameAr] = useState(area?.nameAr ?? "");
  const [centroid, setCentroid] = useState(area?.centroid ? `${area.centroid.lat},${area.centroid.lng}` : "");
  const [error, setError] = useState<string>();
  const [saving, setSaving] = useState(false);
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const point = centroid ? parseLocation(centroid) : undefined;
    if (!name.trim() || (centroid && !point)) { setError(centroid && !point ? t.venues.locationInvalid : t.errors.fields.name); return; }
    setSaving(true); setError(undefined);
    const update = { name: name.trim(), ...(nameAr.trim() ? { nameAr: nameAr.trim() } : area?.nameAr ? { removeNameAr: true } : {}), ...(point ? { centroid: point } : area?.centroid ? { removeCentroid: true } : {}) };
    try {
      const result = area
        ? await api.PATCH("/api/v1/admin/areas/{areaId}", { params: { path: { areaId: area.id } }, body: update })
        : await api.POST("/api/v1/admin/areas", { body: { name: name.trim(), ...(nameAr.trim() ? { nameAr: nameAr.trim() } : {}), ...(point ? { centroid: point } : {}) } });
      if (result.error) setError(adminErrorMessage(errorCodeOf(result.error), errorFieldOf(result.error))); else onSaved();
    } catch { setError(t.networkError); }
    finally { setSaving(false); }
  };
  return <form className="form-card form-grid" aria-label={area ? t.areas.edit : t.areas.create} onSubmit={(event) => void submit(event)}>
    <h3>{area ? t.areas.edit : t.areas.create}</h3>
    <label>{t.areas.name}<input maxLength={200} value={name} onChange={(event) => setName(event.target.value)} /></label>
    <label>{t.areas.nameAr}<input maxLength={200} value={nameAr} onChange={(event) => setNameAr(event.target.value)} /></label>
    <label>{t.areas.centroid}<input value={centroid} onChange={(event) => setCentroid(event.target.value)} /></label>
    {error && <p className="notice notice--error form-span" role="alert">{error}</p>}
    <div className="form-actions form-span"><button className="button button--primary" disabled={saving} type="submit">{saving ? t.saving : t.save}</button> <button className="button button--secondary" type="button" onClick={onCancel}>{t.cancel}</button></div>
  </form>;
}
