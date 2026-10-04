import { useEffect, useState } from "react";
import type { components } from "@e7gz/api-client";
import type { ApiClient } from "../../api";
import { errorCodeOf } from "../../api";
import { messages as t } from "../../i18n/messages";
import { adminErrorMessage } from "../adminErrors";

type Activity = components["schemas"]["AdminActivity"];
export function ActivityList({ api }: { api: ApiClient }) {
  const [state, setState] = useState<{ loading: boolean; items: Activity[]; error?: string }>({ loading: true, items: [] });
  const [version, setVersion] = useState(0);
  useEffect(() => { let active = true; void api.GET("/api/v1/admin/activities").then(({ data, error }) => { if (active) setState({ loading: false, items: data?.data ?? [], ...(data ? {} : { error: adminErrorMessage(errorCodeOf(error)) }) }); }).catch(() => { if (active) setState({ loading: false, items: [], error: t.networkError }); }); return () => { active = false; }; }, [api, version]);
  if (state.loading) return <p className="state-card" role="status">{t.loading}</p>;
  if (state.error) return <div className="state-card state-card--error" role="alert">{state.error} <button className="button button--primary" type="button" onClick={() => { setState({ loading: true, items: [] }); setVersion((v) => v + 1); }}>{t.retry}</button></div>;
  return <section className="admin-page"><header className="page-heading"><span className="page-eyebrow">Marketplace structure</span><h2>{t.activities.title}</h2></header>{state.items.length === 0 ? <p className="state-card">{t.activities.empty}</p> : <div className="data-card"><div className="table-scroll"><table><thead><tr><th>{t.venues.activities}</th><th>{t.activities.venueCount}</th></tr></thead><tbody>{state.items.map((item) => <tr key={item.activity}><td>{item.activity.replaceAll("_", " ")}</td><td>{item.venueCount}</td></tr>)}</tbody></table></div></div>}</section>;
}
