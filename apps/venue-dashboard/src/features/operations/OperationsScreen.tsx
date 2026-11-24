import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { components } from "@e7gz/api-client";
import { apiErrorOf, type ApiClient } from "../../api";
import { StoredText } from "../../components";
import { cairoTimeZone, formatDateTime, instantToLocalDateTime, localDateTimeToInstant } from "../../formatters";
import { useMessages } from "../../i18n";
import { OwnerPasswordDialog } from "../setup/OwnerPasswordDialog";
import { setupStyles as styles } from "../setup/styles";
import { createIdempotencyKey } from "./idempotency";

type Resource = components["schemas"]["VenueResource"];
type Maintenance = components["schemas"]["VenueMaintenanceWindow"];
type Closure = components["schemas"]["VenueClosure"];
type ClosureProposal = components["schemas"]["VenueClosureProposal"];
type ClosurePreview = components["schemas"]["VenueClosurePreview"];
type Emergency = components["schemas"]["VenueEmergency"];
type EmergencyPreview = components["schemas"]["VenueEmergencyImpactPreview"];
type Conversion = components["schemas"]["VenueEmergencyConversion"];
type OwnerReview = components["schemas"]["VenueOwnerReview"];
type Resolution = components["schemas"]["OwnerReviewResolution"];

type MaintenanceDraft = { id?: string; resourceId: string; startAt: string; endAt: string; reason: string };
type ClosureDraft = { id?: string; kind: "FULL_DAY" | "PARTIAL"; startAt: string; endAt: string; reason: string };
type EmergencyDraft = { scope: "VENUE" | "RESOURCE"; resourceId: string; reason: string; expectedEndAt: string };
type PendingDelete = { kind: "maintenance" | "closure"; id: string };
type PendingPin =
  | { kind: "maintenance"; draft: MaintenanceDraft }
  | { kind: "closure" }
  | { kind: "conversion" }
  | { kind: "review" };

const emptyMaintenance: MaintenanceDraft = { resourceId: "", startAt: "", endAt: "", reason: "" };
const emptyClosure: ClosureDraft = { kind: "PARTIAL", startAt: "", endAt: "", reason: "" };
const emptyEmergency: EmergencyDraft = { scope: "VENUE", resourceId: "", reason: "", expectedEndAt: "" };

export function OperationsScreen({ api, onOpenHoursChange }: { api: ApiClient; onOpenHoursChange: (emergencyId: string) => void }) {
  const t = useMessages();
  const o = t.operations;
  const [resources, setResources] = useState<Resource[]>([]);
  const [maintenance, setMaintenance] = useState<Maintenance[]>();
  const [closures, setClosures] = useState<Closure[]>();
  const [emergencies, setEmergencies] = useState<Emergency[]>();
  const [reviews, setReviews] = useState<OwnerReview[]>();
  const [refreshVersion, setRefreshVersion] = useState(0);
  const [error, setError] = useState<string>();
  const [notice, setNotice] = useState<string>();
  const [working, setWorking] = useState(false);
  const [timeZone, setTimeZone] = useState(cairoTimeZone);
  const [maintenanceDraft, setMaintenanceDraft] = useState<MaintenanceDraft>();
  const [closureDraft, setClosureDraft] = useState<ClosureDraft>();
  const [closurePreview, setClosurePreview] = useState<ClosurePreview>();
  const [emergencyDraft, setEmergencyDraft] = useState<EmergencyDraft>();
  const [selectedEmergency, setSelectedEmergency] = useState<Emergency>();
  const [emergencyAction, setEmergencyAction] = useState<"extend" | "convert">();
  const [emergencyEndAt, setEmergencyEndAt] = useState("");
  const [conversionTarget, setConversionTarget] = useState<"CLOSURE" | "MAINTENANCE" | "DISABLED" | "ARCHIVED">("CLOSURE");
  const [conversionReason, setConversionReason] = useState("");
  const [emergencyPreview, setEmergencyPreview] = useState<EmergencyPreview>();
  const [selectedReview, setSelectedReview] = useState<OwnerReview>();
  const [resolution, setResolution] = useState<Resolution>("RETURN_TO_SERVICE");
  const [reviewEndAt, setReviewEndAt] = useState("");
  const [reviewReason, setReviewReason] = useState("");
  const [blocking, setBlocking] = useState<Array<{ bookingId?: string; period?: unknown; kind?: string }>>([]);
  const [pendingPin, setPendingPin] = useState<PendingPin>();
  const [pendingDelete, setPendingDelete] = useState<PendingDelete>();
  const [pinError, setPinError] = useState<string>();
  const closureKey = useRef(createIdempotencyKey());
  const emergencyKey = useRef(createIdempotencyKey());
  const emergencyEndKeys = useRef(new Map<string, string>());
  const submitting = useRef(false);

  const renewEmergencyDraft = (draft: EmergencyDraft) => {
    emergencyKey.current = createIdempotencyKey();
    setEmergencyDraft(draft);
  };

  const resourceNames = useMemo(() => Object.fromEntries(resources.map((resource) => [resource.id, resource.name])), [resources]);
  const refresh = useCallback(() => { setError(undefined); setRefreshVersion((value) => value + 1); }, []);

  const messageFor = useCallback((code?: string) => {
    if (code === "OPERATIONAL_STATE_CHANGED") return o.changed;
    if (code === "OWNER_PASSWORD_REQUIRED") return o.ownerPinRequired;
    if (code === "OWNER_PASSWORD_INVALID") return o.ownerPinInvalid;
    if (code === "OWNER_PASSWORD_LOCKED") return o.ownerPinLocked;
    return code && code in o.errors ? o.errors[code as keyof typeof o.errors] : o.errors.unexpected;
  }, [o]);

  useEffect(() => {
    let active = true;
    Promise.all([
      api.GET("/api/v1/venue/resources"), api.GET("/api/v1/venue/maintenance"), api.GET("/api/v1/venue/closures"),
      api.GET("/api/v1/venue/emergencies"), api.GET("/api/v1/venue/owner-reviews"), api.GET("/api/v1/venue/settings"),
    ]).then(([resourceResult, maintenanceResult, closureResult, emergencyResult, reviewResult, settingsResult]) => {
      if (!active) return;
      const failure = [resourceResult, maintenanceResult, closureResult, emergencyResult, reviewResult, settingsResult].find((result) => !result.data);
      if (failure) return setError(messageFor(apiErrorOf(failure.error).code));
      setResources(resourceResult.data!.data);
      setMaintenance(maintenanceResult.data!.data);
      setClosures(closureResult.data!.data);
      setEmergencies(emergencyResult.data!.data);
      setReviews(reviewResult.data!.data);
      setTimeZone(settingsResult.data!.data.timezone);
    }).catch(() => { if (active) setError(o.errors.network); });
    return () => { active = false; };
  }, [api, messageFor, o.errors.network, refreshVersion]);

  const changed = () => { setNotice(o.success); refresh(); };
  const operationalFailure = (code?: string) => {
    if (code === "OPERATIONAL_STATE_CHANGED") {
      setNotice(messageFor(code));
      setError(undefined);
      setRefreshVersion((value) => value + 1);
      return;
    }
    setError(messageFor(code));
  };

  const saveMaintenance = async (draft: MaintenanceDraft, ownerPassword?: string): Promise<boolean> => {
    if (submitting.current) return false;
    submitting.current = true; setWorking(true); setError(undefined); setPinError(undefined);
    try {
      const body = { startAt: localDateTimeToInstant(draft.startAt, timeZone)!, endAt: localDateTimeToInstant(draft.endAt, timeZone)!, reason: draft.reason, ...(ownerPassword ? { ownerPassword } : {}) };
      const result = draft.id
        ? await api.PATCH("/api/v1/venue/maintenance/{maintenanceId}", { params: { path: { maintenanceId: draft.id } }, body })
        : await api.POST("/api/v1/venue/maintenance", { body: { ...body, resourceId: draft.resourceId } });
      if (!result.data) {
        const code = apiErrorOf(result.error).code;
        if (code === "OWNER_PASSWORD_REQUIRED" && !ownerPassword) { setPendingPin({ kind: "maintenance", draft }); return false; }
        if (["OWNER_PASSWORD_REQUIRED", "OWNER_PASSWORD_INVALID", "OWNER_PASSWORD_LOCKED", "RATE_LIMITED"].includes(code ?? "")) { setPinError(messageFor(code)); return false; }
        operationalFailure(code); return false;
      }
      setPendingPin(undefined); setMaintenanceDraft(undefined); changed(); return true;
    } catch { setError(o.errors.network); return false; } finally { submitting.current = false; setWorking(false); }
  };

  const deleteMaintenance = async (id: string) => {
    if (submitting.current) return;
    submitting.current = true; setWorking(true); setError(undefined);
    try {
      const result = await api.DELETE("/api/v1/venue/maintenance/{maintenanceId}", { params: { path: { maintenanceId: id } } });
      if (result.error) operationalFailure(apiErrorOf(result.error).code);
      else { setPendingDelete(undefined); changed(); }
    } catch { setError(o.errors.network); } finally { submitting.current = false; setWorking(false); }
  };

  const proposalOf = (draft: ClosureDraft): ClosureProposal => ({ kind: draft.kind, startAt: localDateTimeToInstant(draft.startAt, timeZone)!, endAt: localDateTimeToInstant(draft.endAt, timeZone)!, reason: draft.reason });
  const previewClosure = async (draft: ClosureDraft, refreshed = false) => {
    setWorking(true); setError(undefined);
    try {
      const result = await api.POST("/api/v1/venue/closures/preview", { body: { ...(draft.id ? { closureId: draft.id } : {}), proposal: proposalOf(draft) } });
      if (!result.data) return operationalFailure(apiErrorOf(result.error).code);
      setClosurePreview(result.data.data); closureKey.current = createIdempotencyKey();
      if (refreshed) setNotice(o.refreshed);
    } catch { setError(o.errors.network); } finally { setWorking(false); }
  };

  const saveClosure = async (ownerPassword?: string): Promise<boolean> => {
    if (!closureDraft || !closurePreview || submitting.current) return false;
    if (closurePreview.ownerPasswordRequired && !ownerPassword) { setPendingPin({ kind: "closure" }); return false; }
    submitting.current = true; setWorking(true); setPinError(undefined); setError(undefined);
    const body = { proposal: proposalOf(closureDraft), previewToken: closurePreview.previewToken, ...(ownerPassword ? { ownerPassword } : {}) };
    const params = { header: { "Idempotency-Key": closureKey.current } };
    try {
      const result = closureDraft.id
        ? await api.PATCH("/api/v1/venue/closures/{closureId}", { params: { ...params, path: { closureId: closureDraft.id } }, body })
        : await api.POST("/api/v1/venue/closures", { params, body });
      if (!result.data) {
        const code = apiErrorOf(result.error).code;
        if (code === "AFFECTED_SET_CHANGED_REFRESH_PREVIEW") { await previewClosure(closureDraft, true); return false; }
        if (code === "OWNER_PASSWORD_REQUIRED" && !ownerPassword) { setPendingPin({ kind: "closure" }); return false; }
        if (["OWNER_PASSWORD_REQUIRED", "OWNER_PASSWORD_INVALID", "OWNER_PASSWORD_LOCKED", "RATE_LIMITED"].includes(code ?? "")) { setPinError(messageFor(code)); return false; }
        operationalFailure(code); return false;
      }
      setPendingPin(undefined); setClosureDraft(undefined); setClosurePreview(undefined); changed(); return true;
    } catch { setError(o.errors.network); return false; } finally { submitting.current = false; setWorking(false); }
  };

  const deleteClosure = async (id: string) => {
    if (submitting.current) return;
    submitting.current = true; setWorking(true); setError(undefined);
    try {
      const result = await api.DELETE("/api/v1/venue/closures/{closureId}", { params: { path: { closureId: id } } });
      if (result.error) operationalFailure(apiErrorOf(result.error).code);
      else { setPendingDelete(undefined); changed(); }
    } catch { setError(o.errors.network); } finally { submitting.current = false; setWorking(false); }
  };

  const declareEmergency = async (draft: EmergencyDraft) => {
    if (submitting.current) return;
    submitting.current = true; setWorking(true); setError(undefined);
    try {
      const params = { header: { "Idempotency-Key": emergencyKey.current } };
      const result = draft.scope === "VENUE"
        ? await api.POST("/api/v1/venue/emergencies/venue-closure", { params, body: { reason: draft.reason, expectedEndAt: localDateTimeToInstant(draft.expectedEndAt, timeZone)! } })
        : await api.POST("/api/v1/venue/emergencies/resource-outage", { params, body: { resourceId: draft.resourceId, reason: draft.reason, expectedReturnAt: localDateTimeToInstant(draft.expectedEndAt, timeZone)! } });
      if (!result.data) return operationalFailure(apiErrorOf(result.error).code);
      setEmergencyDraft(undefined); emergencyKey.current = createIdempotencyKey(); changed();
    } catch { setError(o.errors.network); } finally { submitting.current = false; setWorking(false); }
  };

  const previewEmergency = async (emergency: Emergency, action: "extend" | "convert", refreshed = false) => {
    setWorking(true); setError(undefined);
    try {
      let result;
      if (action === "extend") result = await api.POST("/api/v1/venue/emergencies/{emergencyId}/extend/preview", { params: { path: { emergencyId: emergency.id } }, body: { expectedEndAt: localDateTimeToInstant(emergencyEndAt, timeZone)! } });
      else result = await api.POST("/api/v1/venue/emergencies/{emergencyId}/convert/preview", { params: { path: { emergencyId: emergency.id } }, body: conversionOf() });
      if (!result.data) return operationalFailure(apiErrorOf(result.error).code);
      setEmergencyPreview(result.data.data); emergencyKey.current = createIdempotencyKey();
      if (refreshed) setNotice(o.refreshed);
    } catch { setError(o.errors.network); } finally { setWorking(false); }
  };

  const conversionOf = (): Conversion => conversionTarget === "CLOSURE"
    ? { target: "CLOSURE", closure: { kind: "PARTIAL", endAt: localDateTimeToInstant(emergencyEndAt, timeZone)!, reason: conversionReason } }
    : conversionTarget === "MAINTENANCE"
      ? { target: "MAINTENANCE", maintenance: { endAt: localDateTimeToInstant(emergencyEndAt, timeZone)!, reason: conversionReason } }
      : { target: conversionTarget };

  const commitEmergency = async (ownerPassword?: string): Promise<boolean> => {
    if (!selectedEmergency || !emergencyAction || !emergencyPreview || submitting.current) return false;
    if (emergencyAction === "convert" && emergencyPreview.ownerPasswordRequired && !ownerPassword) { setPendingPin({ kind: "conversion" }); return false; }
    submitting.current = true; setWorking(true); setPinError(undefined);
    const params = { header: { "Idempotency-Key": emergencyKey.current }, path: { emergencyId: selectedEmergency.id } };
    try {
      const result = emergencyAction === "extend"
        ? await api.POST("/api/v1/venue/emergencies/{emergencyId}/extend", { params, body: { expectedEndAt: localDateTimeToInstant(emergencyEndAt, timeZone)!, previewToken: emergencyPreview.previewToken } })
        : await api.POST("/api/v1/venue/emergencies/{emergencyId}/convert", { params, body: { conversion: conversionOf(), previewToken: emergencyPreview.previewToken, ...(ownerPassword ? { ownerPassword } : {}) } });
      if (!result.data) {
        const code = apiErrorOf(result.error).code;
        if (code === "AFFECTED_SET_CHANGED_REFRESH_PREVIEW") { await previewEmergency(selectedEmergency, emergencyAction, true); return false; }
        if (code === "OWNER_PASSWORD_REQUIRED" && !ownerPassword) { setPendingPin({ kind: "conversion" }); return false; }
        if (["OWNER_PASSWORD_REQUIRED", "OWNER_PASSWORD_INVALID", "OWNER_PASSWORD_LOCKED", "RATE_LIMITED"].includes(code ?? "")) { setPinError(messageFor(code)); return false; }
        operationalFailure(code); return false;
      }
      setPendingPin(undefined); setSelectedEmergency(undefined); setEmergencyAction(undefined); setEmergencyPreview(undefined); changed(); return true;
    } catch { setError(o.errors.network); return false; } finally { submitting.current = false; setWorking(false); }
  };

  const endEmergency = async (emergency: Emergency) => {
    if (submitting.current) return;
    submitting.current = true; setWorking(true); setError(undefined);
    const key = emergencyEndKeys.current.get(emergency.id) ?? createIdempotencyKey();
    emergencyEndKeys.current.set(emergency.id, key);
    try {
      const result = await api.POST("/api/v1/venue/emergencies/{emergencyId}/end", { params: { header: { "Idempotency-Key": key }, path: { emergencyId: emergency.id } } });
      if (!result.data) operationalFailure(apiErrorOf(result.error).code);
      else { emergencyEndKeys.current.delete(emergency.id); changed(); }
    } catch { setError(o.errors.network); } finally { submitting.current = false; setWorking(false); }
  };

  const resolveReview = async (ownerPassword?: string): Promise<boolean> => {
    if (!selectedReview || submitting.current) return false;
    if (!ownerPassword) { setPendingPin({ kind: "review" }); return false; }
    submitting.current = true; setWorking(true); setPinError(undefined); setBlocking([]);
    const body = { action: resolution, ownerPassword, ...(resolution === "EXTEND_MAINTENANCE" ? { maintenance: { endAt: localDateTimeToInstant(reviewEndAt, timeZone)!, reason: reviewReason } } : {}) };
    try {
      const result = await api.POST("/api/v1/venue/owner-reviews/{resourceId}/resolve", { params: { path: { resourceId: selectedReview.resourceId } }, body });
      if (!result.data) {
        const apiError = apiErrorOf(result.error);
        if (apiError.code === "OWNER_REVIEW_RESOLUTION_BLOCKED") {
          const rows = Array.isArray(apiError.details?.blockingReservations) ? apiError.details!.blockingReservations as Array<{ bookingId?: string; period?: unknown; kind?: string }> : [];
          setBlocking(rows); setPendingPin(undefined); setPinError(undefined); return false;
        }
        if (["OWNER_PASSWORD_REQUIRED", "OWNER_PASSWORD_INVALID", "OWNER_PASSWORD_LOCKED", "RATE_LIMITED"].includes(apiError.code ?? "")) { setPinError(messageFor(apiError.code)); return false; }
        operationalFailure(apiError.code); return false;
      }
      setPendingPin(undefined); setSelectedReview(undefined); changed(); return true;
    } catch { setPinError(o.errors.network); return false; } finally { submitting.current = false; setWorking(false); }
  };

  const confirmPin = (password: string) => pendingPin?.kind === "maintenance" ? saveMaintenance(pendingPin.draft, password)
    : pendingPin?.kind === "closure" ? saveClosure(password)
      : pendingPin?.kind === "conversion" ? commitEmergency(password)
        : resolveReview(password);

  if (!maintenance && !error) return <p role="status">{o.loading}</p>;
  if (!maintenance && error) return <section aria-labelledby="operations-title" style={styles.page}>
    <div><h2 id="operations-title" style={styles.heading}>{o.title}</h2><p style={styles.intro}>{o.description}</p></div>
    <div role="alert" style={styles.error}><p>{error}</p><button style={styles.secondaryButton} type="button" onClick={refresh}>{o.retry}</button></div>
  </section>;

  return <section aria-labelledby="operations-title" style={styles.page}>
    <div><h2 id="operations-title" style={styles.heading}>{o.title}</h2><p style={styles.intro}>{o.description}</p></div>
    {reviews?.length ? <section style={styles.info}><h3>{o.ownerReviews}</h3><ul style={styles.list}>{reviews.map((review) => <li key={review.id} style={styles.row}><span><StoredText>{resourceNames[review.resourceId] ?? review.resourceId}</StoredText> · {formatDateTime(review.enteredAt, t.locale, timeZone)}</span><button style={styles.primaryButton} type="button" onClick={() => { setSelectedReview(review); setBlocking([]); }}>{o.resolve}</button></li>)}</ul></section> : null}
    {error ? <div role="alert" style={styles.error}><p>{error}</p><button style={styles.secondaryButton} type="button" onClick={refresh}>{o.retry}</button></div> : null}
    {notice ? <p role="status" style={styles.success}>{notice}</p> : null}
    <section style={styles.card}><div style={styles.cardHeader}><h3 style={styles.cardTitle}>{o.maintenance}</h3><button style={styles.primaryButton} type="button" onClick={() => setMaintenanceDraft({ ...emptyMaintenance, resourceId: resources[0]?.id ?? "" })}>{o.addMaintenance}</button></div>
      {maintenance?.length ? <ul style={styles.list}>{maintenance.map((item) => <li key={item.id} style={styles.row}><span><strong><StoredText>{resourceNames[item.resourceId] ?? item.resourceId}</StoredText></strong><br />{formatDateTime(item.startAt, t.locale, timeZone)} – {formatDateTime(item.endAt, t.locale, timeZone)} · <StoredText>{item.reason}</StoredText></span><span style={styles.actions}><button style={styles.secondaryButton} type="button" onClick={() => setMaintenanceDraft({ id: item.id, resourceId: item.resourceId, startAt: instantToLocalDateTime(item.startAt, timeZone), endAt: instantToLocalDateTime(item.endAt, timeZone), reason: item.reason })}>{o.edit}</button><button style={styles.dangerButton} type="button" onClick={() => setPendingDelete({ kind: "maintenance", id: item.id })}>{o.remove}</button></span></li>)}</ul> : <p>{o.empty}</p>}
    </section>
    <section style={styles.card}><div style={styles.cardHeader}><h3 style={styles.cardTitle}>{o.closures}</h3><button style={styles.primaryButton} type="button" onClick={() => { setClosureDraft(emptyClosure); setClosurePreview(undefined); }}>{o.addClosure}</button></div>
      {closures?.length ? <ul style={styles.list}>{closures.map((item) => <li key={item.id} style={styles.row}><span><strong>{item.kind === "FULL_DAY" ? o.fullDay : o.partial}</strong><br />{formatDateTime(item.startAt, t.locale, timeZone)} – {formatDateTime(item.endAt, t.locale, timeZone)} · <StoredText>{item.reason}</StoredText></span><span style={styles.actions}><button style={styles.secondaryButton} type="button" onClick={() => { setClosureDraft({ id: item.id, kind: item.kind, startAt: instantToLocalDateTime(item.startAt, timeZone), endAt: instantToLocalDateTime(item.endAt, timeZone), reason: item.reason }); setClosurePreview(undefined); }}>{o.edit}</button><button style={styles.dangerButton} type="button" onClick={() => setPendingDelete({ kind: "closure", id: item.id })}>{o.remove}</button></span></li>)}</ul> : <p>{o.empty}</p>}
    </section>
    <section style={styles.card}><div style={styles.cardHeader}><h3 style={styles.cardTitle}>{o.emergencies}</h3><span style={styles.actions}><button style={styles.primaryButton} type="button" onClick={() => renewEmergencyDraft(emptyEmergency)}>{o.declareVenue}</button><button style={styles.secondaryButton} type="button" onClick={() => renewEmergencyDraft({ ...emptyEmergency, scope: "RESOURCE", resourceId: resources[0]?.id ?? "" })}>{o.declareResource}</button></span></div>
      {emergencies?.length ? <ul style={styles.list}>{emergencies.map((item) => <li key={item.id} style={styles.card}><div><strong>{item.scope === "VENUE" ? o.venue : <StoredText>{resourceNames[item.resourceId ?? ""] ?? item.resourceId}</StoredText>}</strong> · {item.status === "ACTIVE" ? o.active : o.awaiting}<br />{formatDateTime(item.declaredAt, t.locale, timeZone)} – {formatDateTime(item.expectedEndAt, t.locale, timeZone)} · <StoredText>{item.reason}</StoredText></div><div style={styles.actions}><button style={styles.secondaryButton} type="button" onClick={() => { setSelectedEmergency(item); setEmergencyAction("extend"); setEmergencyEndAt(instantToLocalDateTime(item.expectedEndAt, timeZone)); setEmergencyPreview(undefined); }}>{o.extend}</button><button style={styles.dangerButton} type="button" onClick={() => void endEmergency(item)}>{o.endEmergency}</button><button style={styles.secondaryButton} type="button" onClick={() => { setSelectedEmergency(item); setEmergencyAction("convert"); setEmergencyEndAt(instantToLocalDateTime(item.expectedEndAt, timeZone)); setConversionReason(item.reason); setConversionTarget(item.scope === "VENUE" ? "CLOSURE" : "MAINTENANCE"); setEmergencyPreview(undefined); }}>{o.convert}</button>{item.scope === "VENUE" ? <button style={styles.secondaryButton} type="button" onClick={() => onOpenHoursChange(item.id)}>{t.setup.hours.title}</button> : null}</div></li>)}</ul> : <p>{o.empty}</p>}
    </section>

    {maintenanceDraft ? <Editor title={maintenanceDraft.id ? o.edit : o.addMaintenance} onCancel={() => setMaintenanceDraft(undefined)} onSubmit={() => void saveMaintenance(maintenanceDraft)} working={working} submit={o.save} t={o}><label style={styles.field}><span style={styles.label}>{o.resource}</span><select disabled={Boolean(maintenanceDraft.id)} style={styles.input} value={maintenanceDraft.resourceId} onChange={(event) => setMaintenanceDraft({ ...maintenanceDraft, resourceId: event.target.value })}>{resources.map((resource) => <option key={resource.id} value={resource.id}>{resource.name}</option>)}</select></label><DateFields start={maintenanceDraft.startAt} end={maintenanceDraft.endAt} onStart={(startAt) => setMaintenanceDraft({ ...maintenanceDraft, startAt })} onEnd={(endAt) => setMaintenanceDraft({ ...maintenanceDraft, endAt })} labels={o} /><Reason value={maintenanceDraft.reason} onChange={(reason) => setMaintenanceDraft({ ...maintenanceDraft, reason })} label={o.reason} /></Editor> : null}
    {closureDraft ? <Editor title={closureDraft.id ? o.edit : o.addClosure} onCancel={() => { setClosureDraft(undefined); setClosurePreview(undefined); }} onSubmit={() => closurePreview ? void saveClosure() : void previewClosure(closureDraft)} working={working} submit={closurePreview ? o.confirm : o.preview} t={o}><label style={styles.field}><span style={styles.label}>{o.kind}</span><select style={styles.input} value={closureDraft.kind} onChange={(event) => { setClosureDraft({ ...closureDraft, kind: event.target.value as ClosureDraft["kind"] }); setClosurePreview(undefined); }}><option value="FULL_DAY">{o.fullDay}</option><option value="PARTIAL">{o.partial}</option></select></label><DateFields start={closureDraft.startAt} end={closureDraft.endAt} onStart={(startAt) => { setClosureDraft({ ...closureDraft, startAt }); setClosurePreview(undefined); }} onEnd={(endAt) => { setClosureDraft({ ...closureDraft, endAt }); setClosurePreview(undefined); }} labels={o} /><Reason value={closureDraft.reason} onChange={(reason) => { setClosureDraft({ ...closureDraft, reason }); setClosurePreview(undefined); }} label={o.reason} />{closurePreview ? <Impact preview={closurePreview} resourceNames={resourceNames} timeZone={timeZone} /> : null}</Editor> : null}
    {emergencyDraft ? <Editor title={emergencyDraft.scope === "VENUE" ? o.declareVenue : o.declareResource} onCancel={() => setEmergencyDraft(undefined)} onSubmit={() => void declareEmergency(emergencyDraft)} working={working} submit={o.save} t={o}>{emergencyDraft.scope === "RESOURCE" ? <label style={styles.field}><span style={styles.label}>{o.resource}</span><select style={styles.input} value={emergencyDraft.resourceId} onChange={(event) => renewEmergencyDraft({ ...emergencyDraft, resourceId: event.target.value })}>{resources.map((resource) => <option key={resource.id} value={resource.id}>{resource.name}</option>)}</select></label> : null}<label style={styles.field}><span style={styles.label}>{o.expectedEnd}</span><input required style={styles.input} type="datetime-local" value={emergencyDraft.expectedEndAt} onChange={(event) => renewEmergencyDraft({ ...emergencyDraft, expectedEndAt: event.target.value })} /></label><Reason value={emergencyDraft.reason} onChange={(reason) => renewEmergencyDraft({ ...emergencyDraft, reason })} label={o.reason} /></Editor> : null}
    {selectedEmergency && emergencyAction ? <Editor title={emergencyAction === "extend" ? o.extend : o.convert} onCancel={() => { setSelectedEmergency(undefined); setEmergencyAction(undefined); setEmergencyPreview(undefined); }} onSubmit={() => emergencyPreview ? void commitEmergency() : void previewEmergency(selectedEmergency, emergencyAction)} working={working} submit={emergencyPreview ? o.confirm : o.preview} t={o}>{emergencyAction === "convert" ? <><label style={styles.field}><span style={styles.label}>{o.target}</span><select style={styles.input} value={conversionTarget} onChange={(event) => { setConversionTarget(event.target.value as typeof conversionTarget); setEmergencyPreview(undefined); }}><option value={selectedEmergency.scope === "VENUE" ? "CLOSURE" : "MAINTENANCE"}>{selectedEmergency.scope === "VENUE" ? o.closure : o.maintenanceTarget}</option>{selectedEmergency.scope === "RESOURCE" ? <><option value="DISABLED">{o.disabled}</option><option value="ARCHIVED">{o.archived}</option></> : null}</select></label>{conversionTarget === "CLOSURE" || conversionTarget === "MAINTENANCE" ? <Reason value={conversionReason} onChange={(reason) => { setConversionReason(reason); setEmergencyPreview(undefined); }} label={o.reason} /> : null}</> : null}{emergencyAction === "extend" || conversionTarget === "CLOSURE" || conversionTarget === "MAINTENANCE" ? <label style={styles.field}><span style={styles.label}>{o.expectedEnd}</span><input required style={styles.input} type="datetime-local" value={emergencyEndAt} onChange={(event) => { setEmergencyEndAt(event.target.value); setEmergencyPreview(undefined); }} /></label> : null}{emergencyPreview ? <Impact preview={emergencyPreview} resourceNames={resourceNames} timeZone={timeZone} /> : null}</Editor> : null}
    {selectedReview ? <Editor title={o.ownerReviews} onCancel={() => setSelectedReview(undefined)} onSubmit={() => void resolveReview()} working={working} submitDisabled={blocking.length > 0} submit={o.resolve} t={o}><label style={styles.field}><span style={styles.label}>{o.resolution}</span><select style={styles.input} value={resolution} onChange={(event) => { setResolution(event.target.value as Resolution); setBlocking([]); }}><option value="RETURN_TO_SERVICE">{o.returnToService}</option><option value="EXTEND_MAINTENANCE">{o.extendMaintenance}</option><option value="DISABLE">{o.disable}</option><option value="ARCHIVE">{o.archive}</option></select></label>{resolution === "EXTEND_MAINTENANCE" ? <><label style={styles.field}><span style={styles.label}>{o.ends}</span><input required style={styles.input} type="datetime-local" value={reviewEndAt} onChange={(event) => { setReviewEndAt(event.target.value); setBlocking([]); }} /></label><Reason value={reviewReason} onChange={(reason) => { setReviewReason(reason); setBlocking([]); }} label={o.reason} /></> : null}{blocking.length ? <div role="alert" style={styles.error}><strong>{o.blocked}</strong><h3>{o.blocking}</h3><ul>{blocking.map((row, index) => <li key={`${row.bookingId ?? index}`}><StoredText>{row.bookingId ?? "—"}</StoredText> · {blockingKind(row.kind, o.blockingKinds)} · {formatBlockingPeriod(row.period, t.locale, timeZone, o.periodUnavailable)}</li>)}</ul></div> : null}</Editor> : null}
    {pendingDelete ? <Editor title={o.confirmDelete} onCancel={() => setPendingDelete(undefined)} onSubmit={() => void (pendingDelete.kind === "maintenance" ? deleteMaintenance(pendingDelete.id) : deleteClosure(pendingDelete.id))} working={working} submit={o.remove} t={o}><p>{o.deleteWarning}</p></Editor> : null}
    <OwnerPasswordDialog actionLabel={o.confirm} error={pinError} open={Boolean(pendingPin)} onCancel={() => { setPendingPin(undefined); setPinError(undefined); }} onConfirm={confirmPin} />
  </section>;
}

function Editor({ title, children, onCancel, onSubmit, working, submitDisabled = false, submit, t }: { title: string; children: React.ReactNode; onCancel: () => void; onSubmit: () => void; working: boolean; submitDisabled?: boolean; submit: string; t: { cancel: string; working: string } }) {
  return <div style={styles.dialogBackdrop}><form aria-label={title} aria-modal="true" role="dialog" style={{ ...styles.dialog, maxWidth: "48rem" }} onSubmit={(event) => { event.preventDefault(); onSubmit(); }}><h2 style={styles.cardTitle}>{title}</h2>{children}<div style={styles.actions}><button disabled={working || submitDisabled} style={styles.primaryButton} type="submit">{working ? t.working : submit}</button><button disabled={working} style={styles.secondaryButton} type="button" onClick={onCancel}>{t.cancel}</button></div></form></div>;
}

function DateFields({ start, end, onStart, onEnd, labels }: { start: string; end: string; onStart: (value: string) => void; onEnd: (value: string) => void; labels: { starts: string; ends: string } }) {
  return <div style={styles.grid}><label style={styles.field}><span style={styles.label}>{labels.starts}</span><input required style={styles.input} type="datetime-local" value={start} onChange={(event) => onStart(event.target.value)} /></label><label style={styles.field}><span style={styles.label}>{labels.ends}</span><input required style={styles.input} type="datetime-local" value={end} onChange={(event) => onEnd(event.target.value)} /></label></div>;
}

function Reason({ value, onChange, label }: { value: string; onChange: (value: string) => void; label: string }) {
  return <label style={styles.field}><span style={styles.label}>{label}</span><textarea required style={styles.input} value={value} onChange={(event) => onChange(event.target.value)} /></label>;
}

function Impact({ preview, resourceNames, timeZone }: { preview: ClosurePreview | EmergencyPreview; resourceNames: Record<string, string>; timeZone: string }) {
  const t = useMessages();
  const o = t.operations;
  return <section style={styles.info}><h3>{o.affected}</h3>{preview.affected.length ? <ul>{preview.affected.map((item) => <li key={item.bookingId}><StoredText>{item.bookingId}</StoredText> · <StoredText>{resourceNames[item.resourceId] ?? item.resourceId}</StoredText> · {formatDateTime(item.startAt, t.locale, timeZone)} – {formatDateTime(item.endAt, t.locale, timeZone)}</li>)}</ul> : <p>{o.noAffected}</p>}<p>{o.refund}</p>{preview.ownerPasswordRequired ? <strong>{o.protected}</strong> : null}</section>;
}

function blockingKind(kind: string | undefined, labels: Record<string, string>): string {
  return kind && kind in labels ? labels[kind]! : "—";
}

function formatBlockingPeriod(period: unknown, locale: string, timeZone: string, unavailable: string): string {
  if (typeof period !== "string") return unavailable;
  const match = /^\[([^,]+),([^)]+)\)$/u.exec(period);
  if (!match) return unavailable;
  return `${formatDateTime(match[1]!, locale, timeZone)} – ${formatDateTime(match[2]!, locale, timeZone)}`;
}
