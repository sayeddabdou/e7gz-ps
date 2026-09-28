// The venue's resources from GET /api/v1/venue/resources (arch 09 §4.2), with loading, success and error states.
// A starting point for the resource part of the setup screens (E7-35).
import { useEffect, useState } from "react";
import type { components } from "@e7gz/api-client";
import { errorCodeOf, type ApiClient } from "../../api";
import { useMessages } from "../../i18n";

type VenueResource = components["schemas"]["VenueResource"];

type State =
  | { status: "loading" }
  | { status: "success"; resources: VenueResource[] }
  | { status: "error"; code: string | undefined; network: boolean };

export function ResourceList({ api }: { api: ApiClient }) {
  const t = useMessages();
  const [state, setState] = useState<State>({ status: "loading" });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    api
      .GET("/api/v1/venue/resources", { signal: controller.signal })
      .then(({ data, error }) => {
        setState(data ? { status: "success", resources: data.data } : { status: "error", code: errorCodeOf(error), network: false });
      })
      .catch(() => {
        if (!controller.signal.aborted) {
          setState({ status: "error", code: undefined, network: true });
        }
      });
    return () => controller.abort();
  }, [api, attempt]);

  const retry = () => {
    setState({ status: "loading" });
    setAttempt((n) => n + 1);
  };

  return (
    <section aria-labelledby="resources-title">
      <h2 id="resources-title">{t.resourcesTitle}</h2>
      {state.status === "loading" && <p role="status">{t.loading}</p>}
      {state.status === "error" && (
        <div role="alert">
          <p>{state.network ? t.networkError : t.loadFailed}</p>
          {state.code && (
            <p>
              {t.errorCode}: <code>{state.code}</code>
            </p>
          )}
          <button type="button" onClick={retry}>
            {t.retry}
          </button>
        </div>
      )}
      {state.status === "success" && state.resources.length === 0 && <p>{t.empty}</p>}
      {state.status === "success" && state.resources.length > 0 && (
        <ul>
          {state.resources.map((resource) => (
            <li key={resource.id}>
              <strong>{resource.name}</strong>
              {" · "}
              {t.activity[resource.activity]}
              {resource.consoleType ? ` · ${resource.consoleType}` : ""}
              {" · "}
              {t.baseState[resource.baseState]}
              {t.restriction[resource.operationalRestriction] ? ` · ${t.restriction[resource.operationalRestriction]}` : ""}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
