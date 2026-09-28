// All areas from GET /api/v1/admin/areas (arch 09 §5), with loading, success and error states.
// A starting point for the Areas module screens (E7-37).
import { useEffect, useState } from "react";
import type { components } from "@e7gz/api-client";
import { errorCodeOf, type ApiClient } from "../../api";
import { messages as t } from "../../i18n/messages";

type AdminArea = components["schemas"]["AdminArea"];

type State =
  | { status: "loading" }
  | { status: "success"; areas: AdminArea[] }
  | { status: "error"; code: string | undefined; network: boolean };

export function AreaList({ api }: { api: ApiClient }) {
  const [state, setState] = useState<State>({ status: "loading" });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    api
      .GET("/api/v1/admin/areas", { signal: controller.signal })
      .then(({ data, error }) => {
        setState(data ? { status: "success", areas: data.data } : { status: "error", code: errorCodeOf(error), network: false });
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
    <section aria-labelledby="areas-title">
      <h2 id="areas-title">{t.areasTitle}</h2>
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
      {state.status === "success" && state.areas.length === 0 && <p>{t.empty}</p>}
      {state.status === "success" && state.areas.length > 0 && (
        <table>
          <tbody>
            {state.areas.map((area) => (
              <tr key={area.id}>
                <td>{area.name}</td>
                <td lang="ar">{area.nameAr ?? ""}</td>
                <td>{area.active ? t.areaActive : t.areaInactive}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
