import { useEffect, useRef, useState, type FormEvent } from "react";
import { Link, useParams } from "react-router-dom";
import type { components } from "@e7gz/api-client";
import { createIdempotencyKey, errorCodeOf, errorFieldOf, idempotencyHeaders, type ApiClient } from "../../api";
import { messages as t } from "../../i18n/messages";
import { adminErrorMessage } from "../adminErrors";

type UserSummary = components["schemas"]["AdminUserSummary"];
type UserDetail = components["schemas"]["AdminUserDetail"];
type ListState = { status: "loading" } | { status: "error"; message: string } | { status: "ready"; items: UserSummary[]; nextCursor: string | null };
type DetailState = { status: "loading" } | { status: "error"; message: string } | { status: "ready"; detail: UserDetail };
type PointsHistory = components["schemas"]["AdminPointsHistory"];
type BanAction = "lift" | "shorten" | "correct";
const pretty = (value: string) => value.replaceAll("_", " ");

export function UsersScreen({ api }: { api: ApiClient }) {
  const [state, setState] = useState<ListState>({ status: "loading" });
  const [draft, setDraft] = useState("");
  const [query, setQuery] = useState("");
  const [version, setVersion] = useState(0);
  const [loadingMore, setLoadingMore] = useState(false);
  useEffect(() => {
    let active = true;
    void api.GET("/api/v1/admin/users", { params: { query: { ...(query ? { q: query } : {}), limit: 50 } } }).then(({ data, error }) => {
      if (active) setState(data ? { status: "ready", items: data.data.items, nextCursor: data.data.nextCursor } : { status: "error", message: adminErrorMessage(errorCodeOf(error), errorFieldOf(error)) });
    }).catch(() => { if (active) setState({ status: "error", message: t.networkError }); });
    return () => { active = false; };
  }, [api, query, version]);
  const retry = () => { setState({ status: "loading" }); setVersion((value) => value + 1); };
  const loadMore = async () => {
    if (state.status !== "ready" || !state.nextCursor) return;
    setLoadingMore(true);
    try {
      const result = await api.GET("/api/v1/admin/users", { params: { query: { ...(query ? { q: query } : {}), cursor: state.nextCursor, limit: 50 } } });
      if (result.data) setState({ status: "ready", items: [...state.items, ...result.data.data.items], nextCursor: result.data.data.nextCursor });
    } finally { setLoadingMore(false); }
  };
  return <section className="admin-page" aria-labelledby="users-title">
    <header className="page-heading"><span className="page-eyebrow">Accounts and trust</span><h2 id="users-title">Users</h2></header>
    <form className="filter-bar" onSubmit={(event) => { event.preventDefault(); setState({ status: "loading" }); setQuery(draft.trim()); }}><label>Search users<input value={draft} onChange={(event) => setDraft(event.target.value)} /></label><button className="button button--primary">Search</button></form>
    {state.status === "loading" && <p className="state-card" role="status">{t.loading}</p>}
    {state.status === "error" && <div className="state-card state-card--error" role="alert"><p>{state.message}</p><button className="button button--primary" onClick={retry}>Retry</button></div>}
    {state.status === "ready" && state.items.length === 0 && <p className="state-card">No users found.</p>}
    {state.status === "ready" && state.items.length > 0 && <div className="data-card"><div className="table-scroll"><table><thead><tr><th>User</th><th>Phone</th><th>Trust</th><th>Verified Player</th><th>Joined</th><th /></tr></thead><tbody>{state.items.map((user) => <tr key={user.userId}><td><strong>{user.username}</strong><br /><small>{user.fullName}</small></td><td>{user.phone}<br /><small>{user.phoneVerified ? "Verified" : "Not verified"}</small></td><td>{user.banned ? "Active ban" : `${user.consecutiveNoShows} consecutive no-shows`}<br /><small>Stage {user.penaltyStage}</small></td><td>{pretty(user.verifiedPlayerStatus)}<br /><small>{user.completedBookings} completed attended bookings</small></td><td>{new Date(user.joinedAt).toLocaleDateString("en-EG")}</td><td><Link className="button button--compact button--secondary" to={`/users/${user.userId}`}>View</Link></td></tr>)}</tbody></table></div>{state.nextCursor && <div className="data-card-footer"><button className="button button--secondary" disabled={loadingMore} onClick={() => void loadMore()}>{loadingMore ? "Loading…" : "Load more"}</button></div>}</div>}
  </section>;
}

export function UserDetailScreen({ api }: { api: ApiClient }) {
  const { userId = "" } = useParams();
  const [state, setState] = useState<DetailState>({ status: "loading" });
  const [version, setVersion] = useState(0);
  const [counterAfter, setCounterAfter] = useState("0");
  const [stageAfter, setStageAfter] = useState("0");
  const [trustReason, setTrustReason] = useState("");
  const [banAction, setBanAction] = useState<BanAction>();
  const [banEndsAt, setBanEndsAt] = useState("");
  const [banReason, setBanReason] = useState("");
  const [message, setMessage] = useState<{ kind: "error" | "success"; text: string }>();
  const [working, setWorking] = useState(false);
  const [points, setPoints] = useState<PointsHistory>();
  const [pointsError, setPointsError] = useState<string>();
  const trustInFlight = useRef(false);
  const banInFlight = useRef(false);
  const trustIdempotencyKey = useRef(createIdempotencyKey());
  const banIdempotencyKey = useRef(createIdempotencyKey());

  useEffect(() => {
    let active = true;
    void api.GET("/api/v1/admin/users/{userId}", { params: { path: { userId } } }).then(({ data, error }) => {
      if (!active) return;
      if (data) { setState({ status: "ready", detail: data.data }); setCounterAfter(String(data.data.user.consecutiveNoShows)); setStageAfter(String(data.data.user.penaltyStage)); }
      else setState({ status: "error", message: adminErrorMessage(errorCodeOf(error), errorFieldOf(error)) });
    }).catch(() => { if (active) setState({ status: "error", message: t.networkError }); });
    return () => { active = false; };
  }, [api, userId, version]);
  useEffect(() => {
    let active = true;
    void api.GET("/api/v1/admin/users/{userId}/points", { params: { path: { userId }, query: { limit: 50 } } }).then(({ data, error }) => {
      if (!active) return;
      if (data) setPoints(data.data); else setPointsError(adminErrorMessage(errorCodeOf(error), errorFieldOf(error)));
    }).catch(() => { if (active) setPointsError(t.networkError); });
    return () => { active = false; };
  }, [api, userId, version]);
  const reload = () => { setState({ status: "loading" }); setVersion((value) => value + 1); };
  const handleError = (error: unknown, form: "trust" | "ban") => {
    const code = errorCodeOf(error);
    setMessage({ kind: "error", text: adminErrorMessage(code, errorFieldOf(error)) });
    if (code === "ADMIN_ACTION_NOT_ALLOWED") {
      if (form === "trust") { setTrustReason(""); trustIdempotencyKey.current = createIdempotencyKey(); }
      else { setBanAction(undefined); setBanEndsAt(""); setBanReason(""); banIdempotencyKey.current = createIdempotencyKey(); }
      reload();
    }
  };
  const adjustTrust = async (event: FormEvent) => {
    event.preventDefault(); if (!trustReason.trim() || trustInFlight.current) return;
    trustInFlight.current = true;
    setWorking(true); setMessage(undefined);
    try {
      const result = await api.POST("/api/v1/admin/users/{userId}/trust-adjustment", { params: { path: { userId }, header: idempotencyHeaders(trustIdempotencyKey.current) }, body: { counterAfter: Number(counterAfter), penaltyStageAfter: Number(stageAfter), reason: trustReason.trim() } });
      if (result.error) handleError(result.error, "trust"); else { setMessage({ kind: "success", text: "Trust standing adjusted and replayed." }); setTrustReason(""); trustIdempotencyKey.current = createIdempotencyKey(); reload(); }
    } catch { setMessage({ kind: "error", text: t.networkError }); } finally { trustInFlight.current = false; setWorking(false); }
  };
  const changeBan = async (event: FormEvent) => {
    event.preventDefault();
    if (state.status !== "ready" || !state.detail.activeBan || !banAction || !banReason.trim() || (banAction === "shorten" && !banEndsAt) || banInFlight.current) return;
    banInFlight.current = true;
    setWorking(true); setMessage(undefined);
    const pathParams = { userId, banId: state.detail.activeBan.banId };
    try {
      const result = banAction === "lift"
        ? await api.POST("/api/v1/admin/users/{userId}/bans/{banId}/lift", { params: { path: pathParams, header: idempotencyHeaders(banIdempotencyKey.current) }, body: { reason: banReason.trim() } })
        : banAction === "correct"
          ? await api.POST("/api/v1/admin/users/{userId}/bans/{banId}/correct", { params: { path: pathParams, header: idempotencyHeaders(banIdempotencyKey.current) }, body: { reason: banReason.trim() } })
          : await api.POST("/api/v1/admin/users/{userId}/bans/{banId}/shorten", { params: { path: pathParams, header: idempotencyHeaders(banIdempotencyKey.current) }, body: { endsAt: new Date(banEndsAt).toISOString(), reason: banReason.trim() } });
      if (result.error) handleError(result.error, "ban"); else { setMessage({ kind: "success", text: "Ban updated." }); setBanAction(undefined); setBanEndsAt(""); setBanReason(""); banIdempotencyKey.current = createIdempotencyKey(); reload(); }
    } catch { setMessage({ kind: "error", text: t.networkError }); } finally { banInFlight.current = false; setWorking(false); }
  };

  return <section className="admin-page" aria-labelledby="user-title">
    <Link className="button button--secondary" to="/users">Back to users</Link>
    {message && <p className={`notice notice--${message.kind}`} role={message.kind === "error" ? "alert" : "status"}>{message.text}</p>}
    {state.status === "loading" && <p className="state-card" role="status">{t.loading}</p>}
    {state.status === "error" && <div className="state-card state-card--error" role="alert"><p>{state.message}</p><button className="button button--primary" onClick={reload}>Retry</button></div>}
    {state.status === "ready" && <><header className="page-heading"><span className="page-eyebrow">Customer account</span><h2 id="user-title">{state.detail.user.username}</h2><p>{state.detail.user.fullName} · {state.detail.user.phone} ({state.detail.user.phoneVerified ? "verified" : "not verified"}) · {state.detail.user.email} ({state.detail.emailVerified ? "verified" : "not verified"})</p></header>
      <div className="venue-summary"><div><span>Verified Player</span><strong>{pretty(state.detail.user.verifiedPlayerStatus)}</strong><small>{state.detail.verifiedPlayerQualification} · {state.detail.user.completedBookings} completed attended bookings</small></div><div><span>Trust standing</span><strong>{state.detail.user.consecutiveNoShows} consecutive no-shows</strong><small>Stage {state.detail.user.penaltyStage} · next threshold {state.detail.currentThreshold}</small></div><div><span>Points</span><strong>{state.detail.pointsBalance.toLocaleString("en-EG")}</strong><small>Referral code {state.detail.referralCode}</small></div><div><span>Wallet</span><strong>{state.detail.walletBalance.amount} {state.detail.walletBalance.currency}</strong><small>{t.e75.walletReadOnly}</small></div><div><span>Terms and Privacy Policy</span><strong>{state.detail.termsAcceptance ? `Version ${state.detail.termsAcceptance.version}` : "Nothing recorded"}</strong><small>{state.detail.termsAcceptance ? `Accepted ${new Date(state.detail.termsAcceptance.acceptedAt).toLocaleString("en-EG")}` : "This account predates acceptance recording."}</small></div></div>
      <form className="form-card form-grid" onSubmit={(event) => void adjustTrust(event)}><h3>Trust adjustment</h3><label>Consecutive no-shows after correction<input min="0" type="number" value={counterAfter} onChange={(event) => { setCounterAfter(event.target.value); trustIdempotencyKey.current = createIdempotencyKey(); }} /></label><label>Penalty stage after correction<input min="0" type="number" value={stageAfter} onChange={(event) => { setStageAfter(event.target.value); trustIdempotencyKey.current = createIdempotencyKey(); }} /></label><label className="form-span">Reason<textarea maxLength={500} rows={3} value={trustReason} onChange={(event) => { setTrustReason(event.target.value); trustIdempotencyKey.current = createIdempotencyKey(); }} /></label><div className="form-actions form-span"><button className="button button--primary" disabled={working || !trustReason.trim()}>Adjust trust</button></div></form>
      {state.detail.activeBan && <section className="form-card"><div className="card-heading"><h3>Active ban</h3><span className="status-pill status-pill--suspended">Stage {state.detail.activeBan.penaltyStage}</span></div><p>Active {new Date(state.detail.activeBan.activatedAt).toLocaleString("en-EG")} — {new Date(state.detail.activeBan.endsAt).toLocaleString("en-EG")}</p><div className="form-actions"><button className="button button--danger" onClick={() => { setBanAction("lift"); setBanEndsAt(""); setBanReason(""); banIdempotencyKey.current = createIdempotencyKey(); }}>Lift ban</button><button className="button button--secondary" onClick={() => { setBanAction("shorten"); setBanEndsAt(""); setBanReason(""); banIdempotencyKey.current = createIdempotencyKey(); }}>Shorten ban</button><button className="button button--secondary" onClick={() => { setBanAction("correct"); setBanEndsAt(""); setBanReason(""); banIdempotencyKey.current = createIdempotencyKey(); }}>Correct ban</button></div></section>}
      {banAction && <form className="action-card" onSubmit={(event) => void changeBan(event)}>{banAction === "shorten" && <label>New end time (your local time)<input type="datetime-local" value={banEndsAt} onChange={(event) => { setBanEndsAt(event.target.value); banIdempotencyKey.current = createIdempotencyKey(); }} /></label>}<label>Reason<textarea maxLength={500} rows={3} value={banReason} onChange={(event) => { setBanReason(event.target.value); banIdempotencyKey.current = createIdempotencyKey(); }} /></label><div className="form-actions"><button className="button button--primary" disabled={working || !banReason.trim() || (banAction === "shorten" && !banEndsAt)}>Confirm {banAction}</button><button className="button button--secondary" disabled={working} type="button" onClick={() => { setBanAction(undefined); setBanEndsAt(""); setBanReason(""); banIdempotencyKey.current = createIdempotencyKey(); }}>Cancel</button></div></form>}
      <section className="form-card"><div className="card-heading"><h3>Points history</h3><span>{points?.account.balance ?? state.detail.pointsBalance}</span></div>{pointsError && <p className="notice notice--error" role="alert">{pointsError}</p>}{points && points.items.length === 0 && <p className="empty-copy">No points transactions.</p>}{points && points.items.length > 0 && <div className="table-scroll"><table><thead><tr><th>When</th><th>Cause</th><th>Applied</th><th>Requested</th><th>Balance</th><th>Related record</th></tr></thead><tbody>{points.items.map((item) => <tr key={item.transactionId}><td>{new Date(item.occurredAt).toLocaleString("en-EG")}</td><td>{pretty(item.cause)}</td><td>{item.amount}</td><td>{item.requestedAmount}</td><td>{item.balanceAfter}</td><td>{item.bookingId ? `Booking ${item.bookingId}` : item.referralId ? `Referral ${item.referralId}` : "—"}{item.unrecoveredAmount > 0 && <><br /><small>{t.e75.unrecoveredPoints(item.unrecoveredAmount)}</small></>}</td></tr>)}</tbody></table></div>}</section>
    </>}
  </section>;
}
