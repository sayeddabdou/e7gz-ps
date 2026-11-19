import { useEffect, useRef, useState, type FormEvent } from "react";
import type { components } from "@e7gz/api-client";
import { createIdempotencyKey, errorCodeOf, errorFieldOf, idempotencyHeaders, type ApiClient } from "../../api";
import { messages as t } from "../../i18n/messages";
import { adminErrorMessage } from "../adminErrors";

type Referral = components["schemas"]["AdminReferral"];
type ReferralStatus = components["schemas"]["ReferralStatus"];
type State = { status: "loading" } | { status: "error"; message: string } | { status: "ready"; items: Referral[]; nextCursor: string | null };
const pretty = (value: string | null) => value ? value.replaceAll("_", " ") : "—";

export function ReferralsScreen({ api }: { api: ApiClient }) {
  const [state, setState] = useState<State>({ status: "loading" });
  const [draftStatus, setDraftStatus] = useState<ReferralStatus | "">("");
  const [draftQuery, setDraftQuery] = useState("");
  const [filters, setFilters] = useState<{ status?: ReferralStatus; q?: string }>({});
  const [version, setVersion] = useState(0);
  const [selected, setSelected] = useState<Referral>();
  const [reason, setReason] = useState("");
  const [message, setMessage] = useState<{ kind: "error" | "success"; text: string }>();
  const [working, setWorking] = useState(false);
  const inFlight = useRef(false);
  const idempotencyKey = useRef(createIdempotencyKey());

  useEffect(() => {
    let active = true;
    void api.GET("/api/v1/admin/referrals", { params: { query: { ...filters, limit: 50 } } }).then(({ data, error }) => {
      if (active) setState(data ? { status: "ready", items: data.data.items, nextCursor: data.data.nextCursor } : { status: "error", message: adminErrorMessage(errorCodeOf(error), errorFieldOf(error)) });
    }).catch(() => { if (active) setState({ status: "error", message: t.networkError }); });
    return () => { active = false; };
  }, [api, filters, version]);
  const reload = () => { setState({ status: "loading" }); setVersion((value) => value + 1); };
  const loadMore = async () => {
    if (state.status !== "ready" || !state.nextCursor) return;
    const result = await api.GET("/api/v1/admin/referrals", { params: { query: { ...filters, cursor: state.nextCursor, limit: 50 } } });
    if (result.data) setState({ status: "ready", items: [...state.items, ...result.data.data.items], nextCursor: result.data.data.nextCursor });
  };
  const search = (event: FormEvent) => { event.preventDefault(); setState({ status: "loading" }); setFilters({ ...(draftStatus ? { status: draftStatus } : {}), ...(draftQuery.trim() ? { q: draftQuery.trim() } : {}) }); };
  const invalidate = async (event: FormEvent) => {
    event.preventDefault();
    if (!selected || !reason.trim() || inFlight.current) return;
    inFlight.current = true;
    setWorking(true); setMessage(undefined);
    try {
      const result = await api.POST("/api/v1/admin/referrals/{referralId}/invalidate", { params: { path: { referralId: selected.referralId }, header: idempotencyHeaders(idempotencyKey.current) }, body: { reason: reason.trim() } });
      if (result.error) {
        const code = errorCodeOf(result.error);
        setMessage({ kind: "error", text: adminErrorMessage(code, errorFieldOf(result.error)) });
        if (code === "ADMIN_ACTION_NOT_ALLOWED") { setSelected(undefined); setReason(""); idempotencyKey.current = createIdempotencyKey(); reload(); }
      } else {
        const reversal = result.data.data.reversal;
        setMessage({ kind: "success", text: reversal ? t.e75.referralReversal(Math.abs(reversal.requestedAmount), Math.abs(reversal.amount), reversal.unrecoveredAmount) : "Referral invalidated. No points reversal was required." });
        setSelected(undefined); setReason(""); idempotencyKey.current = createIdempotencyKey(); reload();
      }
    } catch { setMessage({ kind: "error", text: t.networkError }); }
    finally { inFlight.current = false; setWorking(false); }
  };

  return <section className="admin-page" aria-labelledby="referrals-title">
    <header className="page-heading"><span className="page-eyebrow">Points and referrals</span><h2 id="referrals-title">Referrals</h2></header>
    <form className="filter-bar" onSubmit={search}><label>Status<select value={draftStatus} onChange={(event) => setDraftStatus(event.target.value as ReferralStatus | "")}><option value="">All statuses</option><option value="PENDING">Pending</option><option value="REWARDED">Rewarded</option><option value="INVALID">Invalid</option></select></label><label>Search users<input value={draftQuery} onChange={(event) => setDraftQuery(event.target.value)} /></label><button className="button button--primary" type="submit">Search</button></form>
    {message && <p className={`notice notice--${message.kind}`} role={message.kind === "error" ? "alert" : "status"}>{message.text}</p>}
    {selected && <form className="action-card" aria-label={`Invalidate referral ${selected.code}`} onSubmit={(event) => void invalidate(event)}><label>Reason<textarea maxLength={500} rows={3} value={reason} onChange={(event) => { setReason(event.target.value); idempotencyKey.current = createIdempotencyKey(); }} /></label><div className="form-actions"><button className="button button--danger" disabled={working || !reason.trim()} type="submit">Confirm invalidation</button><button className="button button--secondary" disabled={working} type="button" onClick={() => { setSelected(undefined); setReason(""); idempotencyKey.current = createIdempotencyKey(); }}>Cancel</button></div></form>}
    {state.status === "loading" && <p className="state-card" role="status">{t.loading}</p>}
    {state.status === "error" && <div className="state-card state-card--error" role="alert"><p>{state.message}</p><button className="button button--primary" onClick={reload}>Retry</button></div>}
    {state.status === "ready" && state.items.length === 0 && <p className="state-card">No referrals found.</p>}
    {state.status === "ready" && state.items.length > 0 && <div className="data-card"><div className="table-scroll"><table><thead><tr><th>Referrer</th><th>Code</th><th>Referred user</th><th>Signed up</th><th>Status</th><th>Reward</th><th>Outcome</th><th>Actions</th></tr></thead><tbody>{state.items.map((referral) => <tr key={referral.referralId}><td>{referral.referrerUsername ?? "No matching referrer"}</td><td className="account-identifier">{referral.code}</td><td>{referral.referredUsername}</td><td>{new Date(referral.signedUpAt).toLocaleString("en-EG")}</td><td><span className={`status-pill status-pill--${referral.status === "REWARDED" ? "success" : referral.status === "INVALID" ? "suspended" : "neutral"}`}>{pretty(referral.status)}</span></td><td>{referral.rewardPointsApplied ?? "—"}</td><td>{referral.rewardedAt ? `Rewarded ${new Date(referral.rewardedAt).toLocaleString("en-EG")}` : referral.invalidatedAt ? `${pretty(referral.invalidationKind)} · ${new Date(referral.invalidatedAt).toLocaleString("en-EG")}` : "—"}</td><td>{referral.status !== "INVALID" && <button className="button button--compact button--danger" onClick={() => { setSelected(referral); setReason(""); idempotencyKey.current = createIdempotencyKey(); }}>Invalidate</button>}</td></tr>)}</tbody></table></div>{state.nextCursor && <div className="data-card-footer"><button className="button button--secondary" onClick={() => void loadMore()}>Load more</button></div>}</div>}
  </section>;
}
