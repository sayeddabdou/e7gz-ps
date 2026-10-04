import { useEffect, useState } from "react";
import type { components } from "@e7gz/api-client";
import { apiErrorOf, type ApiClient } from "../../api";
import { useMessages } from "../../i18n";
import { setupErrorMessage } from "./errorMessages";
import { OwnerPasswordDialog } from "./OwnerPasswordDialog";
import { setupStyles as styles } from "./styles";

type VenueResource = components["schemas"]["VenueResource"];
type Activity = components["schemas"]["Activity"];
type ConsoleType = components["schemas"]["ConsoleType"];
type Action =
  | { kind: "create"; activity: Activity; consoleType?: ConsoleType }
  | { kind: "disable" | "enable" | "archive" | "restore"; resource: VenueResource }
  | { kind: "console"; resource: VenueResource; consoleType: ConsoleType };

// Refusals about the PIN itself stay in the PIN dialog; any other refusal closes it and shows on the page.
const pinCodes = new Set(["OWNER_PASSWORD_REQUIRED", "OWNER_PASSWORD_INVALID", "RATE_LIMITED"]);

export function ResourceList({ api }: { api: ApiClient }) {
  const t = useMessages();
  const [resources, setResources] = useState<VenueResource[]>();
  const [offered, setOffered] = useState<Activity[]>();
  const [activity, setActivity] = useState<Activity>();
  const [consoleType, setConsoleType] = useState<ConsoleType>("PS5");
  const [consoleSelections, setConsoleSelections] = useState<Record<string, ConsoleType>>({});
  const [action, setAction] = useState<Action>();
  const [error, setError] = useState<string>();
  const [actionError, setActionError] = useState<string>();
  const [passwordError, setPasswordError] = useState<string>();
  const [refreshVersion, setRefreshVersion] = useState(0);

  useEffect(() => {
    let active = true;
    void api.GET("/api/v1/venue/resources").then(({ data, error: body }) => {
      if (!active) return;
      if (data) setResources(data.data);
      else setError(setupErrorMessage(apiErrorOf(body).code, t));
    }).catch(() => { if (active) setError(t.setup.errors.network); });
    return () => { active = false; };
  }, [api, refreshVersion, t]);

  // Only the activities the venue offers can get a room (FR-VEN-41); the Platform Admin manages that list.
  useEffect(() => {
    let active = true;
    void api.GET("/api/v1/venue/settings").then(({ data, error: body }) => {
      if (!active) return;
      if (!data) return setError(setupErrorMessage(apiErrorOf(body).code, t));
      setOffered(data.data.activities);
      setActivity((current) => current && data.data.activities.includes(current) ? current : data.data.activities[0]);
    }).catch(() => { if (active) setError(t.setup.errors.network); });
    return () => { active = false; };
  }, [api, refreshVersion, t]);

  const retry = () => {
    setError(undefined);
    setResources(undefined);
    setRefreshVersion((version) => version + 1);
  };

  const run = async (ownerPassword: string): Promise<boolean> => {
    if (!action) return false;
    setPasswordError(undefined);
    setActionError(undefined);
    try {
      let result;
      if (action.kind === "create") {
        result = await api.POST("/api/v1/venue/resources", { body: { activity: action.activity, ...(action.consoleType ? { consoleType: action.consoleType } : {}), ownerPassword } });
      } else if (action.kind === "console") {
        result = await api.POST("/api/v1/venue/resources/{resourceId}/console", { params: { path: { resourceId: action.resource.id } }, body: { consoleType: action.consoleType, ownerPassword } });
      } else {
        const body = { ownerPassword };
        const params = { path: { resourceId: action.resource.id } };
        result = action.kind === "disable"
          ? await api.POST("/api/v1/venue/resources/{resourceId}/disable", { params, body })
          : action.kind === "enable"
            ? await api.POST("/api/v1/venue/resources/{resourceId}/re-enable", { params, body })
            : action.kind === "archive"
              ? await api.POST("/api/v1/venue/resources/{resourceId}/archive", { params, body })
              : await api.POST("/api/v1/venue/resources/{resourceId}/restore", { params, body });
      }
      if (!result.data) {
        const { code, field } = apiErrorOf(result.error);
        if (code && pinCodes.has(code)) {
          setPasswordError(setupErrorMessage(code, t));
          return false;
        }
        setAction(undefined);
        setActionError(code === "VALIDATION_FAILED" && field === "activity"
          ? t.setup.resources.activityNotOffered
          : setupErrorMessage(code, t));
        return false;
      }
      setAction(undefined);
      setRefreshVersion((version) => version + 1);
      return true;
    } catch {
      setPasswordError(t.setup.errors.network);
      return false;
    }
  };

  const actionName = action?.kind === "create" ? t.setup.resources.add
    : action?.kind === "disable" ? t.setup.resources.disable
      : action?.kind === "enable" ? t.setup.resources.enable
        : action?.kind === "archive" ? t.setup.resources.archive
          : action?.kind === "restore" ? t.setup.resources.restore
            : t.setup.resources.changeConsole;

  return (
    <section aria-labelledby="resources-title" style={styles.page}>
      <div><h2 id="resources-title" style={styles.heading}>{t.setup.resources.title}</h2><p style={styles.intro}>{t.setup.resources.description}</p></div>
      <section style={styles.card}>
        <h3 style={styles.cardTitle}>{t.setup.resources.add}</h3>
        <div style={styles.grid}>
          <label style={styles.field}><span style={styles.label}>{t.setup.resources.activity}</span><select disabled={!offered} style={styles.input} value={activity ?? ""} onChange={(event) => setActivity(event.target.value as Activity)}>{(offered ?? []).map((value) => <option key={value} value={value}>{t.activity[value]}</option>)}</select></label>
          {activity === "PLAYSTATION" ? <label style={styles.field}><span style={styles.label}>{t.setup.resources.console}</span><select style={styles.input} value={consoleType} onChange={(event) => setConsoleType(event.target.value as ConsoleType)}><option value="PS5">PS5</option><option value="PS4">PS4</option></select></label> : null}
        </div>
        <button disabled={!activity} style={styles.primaryButton} type="button" onClick={() => activity && setAction({ kind: "create", activity, ...(activity === "PLAYSTATION" ? { consoleType } : {}) })}>{t.setup.resources.add}</button>
      </section>
      {actionError ? <div role="alert" style={styles.error}><p>{actionError}</p></div> : null}
      {resources === undefined && !error ? <p role="status">{t.setup.common.loading}</p> : null}
      {error ? <div role="alert" style={styles.error}><p>{error}</p><button style={styles.secondaryButton} type="button" onClick={retry}>{t.setup.common.retry}</button></div> : null}
      {resources?.length === 0 ? <p>{t.setup.resources.empty}</p> : null}
      {resources?.length ? (
        <ul style={styles.list}>
          {resources.map((resource) => {
            const selectedConsole = consoleSelections[resource.id] ?? resource.consoleType ?? "PS5";
            return (
              <li key={resource.id} style={styles.card}>
                <div style={styles.cardHeader}><strong>{resource.name}</strong><span>{t.activity[resource.activity]}{resource.consoleType ? ` · ${resource.consoleType}` : ""} · {t.baseState[resource.baseState]}{t.restriction[resource.operationalRestriction] ? ` · ${t.restriction[resource.operationalRestriction]}` : ""}</span></div>
                {resource.activity === "PLAYSTATION" ? <div style={styles.actions}><select aria-label={`${resource.name} ${t.setup.resources.console}`} style={styles.input} value={selectedConsole} onChange={(event) => setConsoleSelections({ ...consoleSelections, [resource.id]: event.target.value as ConsoleType })}><option value="PS5">PS5</option><option value="PS4">PS4</option></select><button style={styles.secondaryButton} type="button" onClick={() => setAction({ kind: "console", resource, consoleType: selectedConsole })}>{t.setup.resources.changeConsole}</button></div> : null}
                <div style={styles.actions}>
                  {resource.baseState === "ACTIVE" ? <button style={styles.secondaryButton} type="button" onClick={() => setAction({ kind: "disable", resource })}>{t.setup.resources.disable}</button> : null}
                  {resource.baseState === "DISABLED" ? <button style={styles.secondaryButton} type="button" onClick={() => setAction({ kind: "enable", resource })}>{t.setup.resources.enable}</button> : null}
                  {resource.baseState !== "ARCHIVED" ? <button style={styles.dangerButton} type="button" onClick={() => setAction({ kind: "archive", resource })}>{t.setup.resources.archive}</button> : <button style={styles.secondaryButton} type="button" onClick={() => setAction({ kind: "restore", resource })}>{t.setup.resources.restore}</button>}
                </div>
              </li>
            );
          })}
        </ul>
      ) : null}
      <OwnerPasswordDialog actionLabel={actionName} error={passwordError} open={Boolean(action)} onCancel={() => { setAction(undefined); setPasswordError(undefined); }} onConfirm={run} />
    </section>
  );
}
