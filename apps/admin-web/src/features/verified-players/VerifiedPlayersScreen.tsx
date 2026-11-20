import { useEffect, useRef, useState, type FormEvent } from "react";
import type { components } from "@e7gz/api-client";
import { createIdempotencyKey, errorCodeOf, errorFieldOf, idempotencyHeaders, type ApiClient } from "../../api";
import { messages as t } from "../../i18n/messages";
import { adminErrorMessage } from "../adminErrors";

type Player = components["schemas"]["AdminVerifiedPlayer"];
type Status = components["schemas"]["VerifiedPlayerStatus"];
type Action = "suspend" | "revoke" | "restore";
type State = { status: "loading" } | { status: "error"; message: string } | { status: "ready"; items: Player[]; nextCursor: string | null };
const pretty = (value: string | null) => value ? value.replaceAll("_", " ") : "—";

export function VerifiedPlayersScreen({ api }: { api: ApiClient }) {
  const [state, setState] = useState<State>({ status: "loading" });
  const [status, setStatus] = useState<Status | "">("");
  const [version, setVersion] = useState(0);
  const [selected, setSelected] = useState<{ player: Player; action: Action }>();
  const [reason, setReason] = useState("");
  const [message, setMessage] = useState<string>();
  const [working, setWorking] = useState(false);
  const inFlight = useRef(false);
  const idempotencyKey = useRef(createIdempotencyKey());

  useEffect(() => {
    let active = true;
    void api.GET("/api/v1/admin/verified-players", { params: { query: { ...(status ? { status } : {}), limit: 50 } } }).then(({ data, error }) => {
      if (active) setState(data ? { status: "ready", items: data.data.items, nextCursor: data.data.nextCursor } : { status: "error", message: adminErrorMessage(errorCodeOf(error), errorFieldOf(error)) });
    }).catch(() => { if (active) setState({ status: "error", message: t.networkError }); });
    return () => { active = false; };
  }, [api, status, version]);

  const reload = () => { setState({ status: "loading" }); setVersion((value) => value + 1); };
  const loadMore = async () => {
    if (state.status !== "ready" || !state.nextCursor) return;
    const result = await api.GET("/api/v1/admin/verified-players", { params: { query: { ...(status ? { status } : {}), cursor: state.nextCursor, limit: 50 } } });
    if (result.data) setState({ status: "ready", items: [...state.items, ...result.data.data.items], nextCursor: result.data.data.nextCursor });
  };
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!selected || !reason.trim() || inFlight.current) return;
    inFlight.current = true;
    setWorking(true); setMessage(undefined);
    const path = (`/api/v1/admin/verified-players/{userId}/${selected.action}`) as
      | "/api/v1/admin/verified-players/{userId}/suspend"
      | "/api/v1/admin/verified-players/{userId}/revoke"
      | "/api/v1/admin/verified-players/{userId}/restore";
    try {
      const result = await api.POST(path, { params: { path: { userId: selected.player.userId }, header: idempotencyHeaders(idempotencyKey.current) }, body: { reason: reason.trim() } });
      if (result.error) {
        const code = errorCodeOf(result.error);
        setMessage(adminErrorMessage(code, errorFieldOf(result.error)));
        if (code === "ADMIN_ACTION_NOT_ALLOWED") { setSelected(undefined); setReason(""); idempotencyKey.current = createIdempotencyKey(); reload(); }
      } else { setSelected(undefined); setReason(""); idempotencyKey.current = createIdempotencyKey(); reload(); }
    } catch { setMessage(t.networkError); }
    finally { inFlight.current = false; setWorking(false); }
  };

  const choose = (player: Player, action: Action) => { setSelected({ player, action }); setReason(""); setMessage(undefined); idempotencyKey.current = createIdempotencyKey(); };
  return <section className="admin-page" aria-labelledby="verified-title">
    <header className="page-heading"><span className="page-eyebrow">Trust and eligibility</span><h2 id="verified-title">Verified Players</h2></header>
    <label className="filter-control">Status<select value={status} onChange={(event) => setStatus(event.target.value as Status | "")}><option value="">All statuses</option><option value="VERIFIED">Verified</option><option value="SUSPENDED">Suspended</option><option value="REVOKED">Revoked</option><option value="NOT_VERIFIED">Not verified</option></select></label>
    {message && <p className="notice notice--error" role="alert">{message}</p>}
    {selected && <form className="action-card" aria-label={`${selected.action} ${selected.player.username}`} onSubmit={(event) => void submit(event)}>
      <label>Reason<textarea maxLength={500} rows={3} value={reason} onChange={(event) => { setReason(event.target.value); idempotencyKey.current = createIdempotencyKey(); }} /></label>
      <div className="form-actions"><button className="button button--primary" disabled={working || !reason.trim()} type="submit">Confirm {selected.action[0]!.toUpperCase() + selected.action.slice(1)}</button><button className="button button--secondary" disabled={working} type="button" onClick={() => { setSelected(undefined); setReason(""); idempotencyKey.current = createIdempotencyKey(); }}>Cancel</button></div>
    </form>}
    {state.status === "loading" && <p className="state-card" role="status">{t.loading}</p>}
    {state.status === "error" && <div className="state-card state-card--error" role="alert"><p>{state.message}</p><button className="button button--primary" onClick={reload}>Retry</button></div>}
    {state.status === "ready" && state.items.length === 0 && <p className="state-card">No Verified Player records found.</p>}
    {state.status === "ready" && state.items.length > 0 && <div className="data-card"><div className="table-scroll"><table><thead><tr><th>Player</th><th>Qualification</th><th>Completed bookings</th><th>Status</th><th>Latest cause</th><th>Actions</th></tr></thead><tbody>{state.items.map((player) => <tr key={player.userId}><td><strong>{player.username}</strong><br /><small>{player.fullName}</small></td><td>{pretty(player.qualification)}</td><td>{player.completedBookings}</td><td><span className={`status-pill status-pill--${player.status === "VERIFIED" ? "success" : player.status === "SUSPENDED" || player.status === "REVOKED" ? "suspended" : "neutral"}`}>{pretty(player.status)}</span>{player.statusSince && <><br /><small>Since {new Date(player.statusSince).toLocaleString("en-EG")}</small></>}</td><td>{pretty(player.statusCause)}</td><td className="table-actions">{player.status === "VERIFIED" && <><button className="button button--compact button--secondary" onClick={() => choose(player, "suspend")}>Suspend</button><button className="button button--compact button--danger" onClick={() => choose(player, "revoke")}>Revoke</button></>}{(player.status === "SUSPENDED" || player.status === "REVOKED") && <button className="button button--compact button--primary" onClick={() => choose(player, "restore")}>Restore</button>}</td></tr>)}</tbody></table></div>{state.nextCursor && <div className="data-card-footer"><button className="button button--secondary" onClick={() => void loadMore()}>Load more</button></div>}</div>}
  </section>;
}
