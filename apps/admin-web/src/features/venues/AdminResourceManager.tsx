import { useEffect, useState, type FormEvent } from "react";
import type { components } from "@e7gz/api-client";
import { errorCodeOf, errorFieldOf, type ApiClient } from "../../api";
import { adminErrorMessage } from "../adminErrors";
import { messages as t } from "../../i18n/messages";

type Resource = components["schemas"]["AdminResource"];
type Activity = components["schemas"]["Activity"];
export function AdminResourceManager({ api, venueId, activities }: { api: ApiClient; venueId: string; activities: Activity[] }) {
  const [resources, setResources] = useState<Resource[]>([]);
  const [activity, setActivity] = useState<Activity>(activities[0] ?? "PLAYSTATION");
  const [consoleType, setConsoleType] = useState<"PS4" | "PS5">("PS5");
  const [error, setError] = useState<string>();
  const [loading, setLoading] = useState(true);
  const [loadFailed, setLoadFailed] = useState(false);
  const [version, setVersion] = useState(0);
  const selectedActivity = activities.includes(activity) ? activity : activities[0] ?? activity;
  useEffect(() => { let active = true; void api.GET("/api/v1/admin/venues/{venueId}/resources", { params: { path: { venueId } } }).then(({ data }) => { if (active) { setResources(data?.data ?? []); setLoadFailed(!data); setLoading(false); } }).catch(() => { if (active) { setLoadFailed(true); setLoading(false); } }); return () => { active = false; }; }, [api, venueId, version]);
  const create = async (event: FormEvent) => {
    event.preventDefault(); setError(undefined);
    const body = { activity: selectedActivity, ...(selectedActivity === "PLAYSTATION" ? { consoleType } : {}) };
    try {
      const result = await api.POST("/api/v1/admin/venues/{venueId}/resources", { params: { path: { venueId } }, body });
      if (result.data) setResources((current) => [...current, result.data!.data]); else setError(adminErrorMessage(errorCodeOf(result.error), errorFieldOf(result.error)));
    } catch { setError(t.networkError); }
  };
  const retry = () => { setLoading(true); setLoadFailed(false); setVersion((value) => value + 1); };
  return <section className="setup-card"><div className="card-heading"><h3>{t.resources.title}</h3><span>{resources.length}</span></div>{loading && <p className="empty-copy" role="status">{t.loading}</p>}{loadFailed && <div className="notice notice--error" role="alert">{t.networkError} <button className="button button--secondary" type="button" onClick={retry}>{t.retry}</button></div>}{!loading && !loadFailed && !resources.length && <p className="empty-copy">{t.resources.empty}</p>}<ul className="resource-list">{resources.map((resource) => <li key={resource.id}><strong>{resource.name}</strong><span>{t.activityLabels[resource.activity]}{resource.consoleType ? ` · ${resource.consoleType}` : ""}</span><span>{t.resources.baseState}: {t.resources.baseStates[resource.baseState]}</span><span>{t.resources.operationalRestriction}: {t.resources.restrictions[resource.operationalRestriction]}</span></li>)}</ul>{error && <p className="notice notice--error" role="alert">{error}</p>}<form className="inline-form" aria-label={t.resources.add} onSubmit={(event) => void create(event)}><label>{t.resources.activity}<select disabled={loading || loadFailed || !activities.length} value={selectedActivity} onChange={(event) => setActivity(event.target.value as Activity)}>{activities.map((option) => <option key={option} value={option}>{t.activityLabels[option]}</option>)}</select></label>{selectedActivity === "PLAYSTATION" && <label>{t.resources.console}<select disabled={loading || loadFailed} value={consoleType} onChange={(event) => setConsoleType(event.target.value as "PS4" | "PS5")}><option>PS5</option><option>PS4</option></select></label>}<button className="button button--primary" disabled={loading || loadFailed || !activities.length} type="submit">{t.resources.add}</button></form></section>;
}
