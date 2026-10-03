import { useEffect, useMemo, useState } from "react";
import type { components } from "@e7gz/api-client";
import { apiErrorOf, type ApiClient } from "../../api";
import { useMessages } from "../../i18n";
import { setupErrorMessage } from "./errorMessages";
import { OwnerPasswordDialog } from "./OwnerPasswordDialog";
import { setupStyles as styles } from "./styles";

type Pricing = components["schemas"]["VenuePricing"];
type VenueResource = components["schemas"]["VenueResource"];
type DefaultPrice = components["schemas"]["VenueDefaultPrice"];
type Action = { kind: "defaults" } | { kind: "override" | "reset"; resource: VenueResource };
type OverrideDraft = { SINGLE: string; MULTIPLAYER: string };

export function PricingEditor({ api }: { api: ApiClient }) {
  const t = useMessages();
  const [pricing, setPricing] = useState<Pricing>();
  const [defaults, setDefaults] = useState<DefaultPrice[]>([]);
  const [resources, setResources] = useState<VenueResource[]>([]);
  const [overrideDrafts, setOverrideDrafts] = useState<Record<string, OverrideDraft>>({});
  const [action, setAction] = useState<Action>();
  const [error, setError] = useState<string>();
  const [passwordError, setPasswordError] = useState<string>();
  const [refreshVersion, setRefreshVersion] = useState(0);

  useEffect(() => {
    let active = true;
    void Promise.all([api.GET("/api/v1/venue/pricing"), api.GET("/api/v1/venue/resources")]).then(([priceResult, resourceResult]) => {
      if (!active) return;
      if (!priceResult.data) return setError(setupErrorMessage(apiErrorOf(priceResult.error).code, t));
      if (!resourceResult.data) return setError(setupErrorMessage(apiErrorOf(resourceResult.error).code, t));
      setPricing(priceResult.data.data);
      setDefaults(priceResult.data.data.defaults);
      setResources(resourceResult.data.data);
      const drafts: Record<string, OverrideDraft> = {};
      for (const resource of resourceResult.data.data.filter((item) => item.activity === "PLAYSTATION")) {
        const entries = priceResult.data.data.overrides.filter((item) => item.resourceId === resource.id);
        drafts[resource.id] = {
          SINGLE: entries.find((item) => item.playMode === "SINGLE")?.hourlyPrice.amount ?? "",
          MULTIPLAYER: entries.find((item) => item.playMode === "MULTIPLAYER")?.hourlyPrice.amount ?? "",
        };
      }
      setOverrideDrafts(drafts);
    }).catch(() => { if (active) setError(t.setup.errors.network); });
    return () => { active = false; };
  }, [api, refreshVersion, t]);

  const retry = () => {
    setError(undefined);
    setRefreshVersion((version) => version + 1);
  };

  const existingOverrideIds = useMemo(() => new Set(pricing?.overrides.map((item) => item.resourceId) ?? []), [pricing]);

  const run = async (ownerPassword: string): Promise<boolean> => {
    if (!action) return false;
    setPasswordError(undefined);
    try {
      let result;
      if (action.kind === "defaults") {
        result = await api.PUT("/api/v1/venue/pricing", { body: { defaults, ownerPassword } });
      } else if (action.kind === "override") {
        const draft = overrideDrafts[action.resource.id];
        if (!draft?.SINGLE || !draft.MULTIPLAYER) {
          setPasswordError(t.setup.errors.VALIDATION_FAILED);
          return false;
        }
        result = await api.PUT("/api/v1/venue/resources/{resourceId}/price-override", {
          params: { path: { resourceId: action.resource.id } },
          body: { ownerPassword, prices: [
            { playMode: "SINGLE", hourlyPrice: { amount: draft.SINGLE, currency: "EGP" } },
            { playMode: "MULTIPLAYER", hourlyPrice: { amount: draft.MULTIPLAYER, currency: "EGP" } },
          ] },
        });
      } else {
        result = await api.DELETE("/api/v1/venue/resources/{resourceId}/price-override", { params: { path: { resourceId: action.resource.id } }, body: { ownerPassword } });
      }
      if (!result.response.ok) {
        setPasswordError(setupErrorMessage(apiErrorOf(result.error).code, t));
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

  const updateDefault = (index: number, amount: string) => setDefaults(defaults.map((item, itemIndex) => itemIndex === index ? { ...item, hourlyPrice: { ...item.hourlyPrice, amount } } : item));

  return (
    <section aria-labelledby="pricing-title" style={styles.page}>
      <div><h2 id="pricing-title" style={styles.heading}>{t.setup.pricing.title}</h2><p style={styles.intro}>{t.setup.pricing.description}</p></div>
      {!pricing && !error ? <p role="status">{t.setup.common.loading}</p> : null}
      {error ? <div role="alert" style={styles.error}><p>{error}</p><button style={styles.secondaryButton} type="button" onClick={retry}>{t.setup.common.retry}</button></div> : null}
      {pricing ? <>
        <section style={styles.card}>
          <h3 style={styles.cardTitle}>{t.setup.pricing.defaults}</h3>
          <div style={styles.grid}>{defaults.map((item, index) => <label key={`${item.activity}-${item.consoleType ?? "table"}-${item.playMode ?? "hour"}`} style={styles.field}><span style={styles.label}>{t.activity[item.activity]}{item.consoleType ? ` · ${item.consoleType}` : ""}{item.playMode ? ` · ${t.setup.pricing[item.playMode]}` : ""}</span><input inputMode="decimal" required style={styles.input} value={item.hourlyPrice.amount} onChange={(event) => updateDefault(index, event.target.value)} /></label>)}</div>
          <button style={styles.primaryButton} type="button" onClick={() => setAction({ kind: "defaults" })}>{t.setup.common.save}</button>
        </section>
        <section style={styles.card}>
          <h3 style={styles.cardTitle}>{t.setup.pricing.overrides}</h3>
          {resources.filter((resource) => resource.activity === "PLAYSTATION").map((resource) => {
            const draft = overrideDrafts[resource.id] ?? { SINGLE: "", MULTIPLAYER: "" };
            return <div key={resource.id} style={styles.card}>
              <strong>{resource.name} · {resource.consoleType}</strong>
              <div style={styles.grid}>{(["SINGLE", "MULTIPLAYER"] as const).map((mode) => <label key={mode} style={styles.field}><span style={styles.label}>{t.setup.pricing[mode]}</span><input inputMode="decimal" style={styles.input} value={draft[mode]} onChange={(event) => setOverrideDrafts({ ...overrideDrafts, [resource.id]: { ...draft, [mode]: event.target.value } })} /></label>)}</div>
              <div style={styles.actions}><button style={styles.secondaryButton} type="button" onClick={() => setAction({ kind: "override", resource })}>{t.setup.pricing.editOverride}</button>{existingOverrideIds.has(resource.id) ? <button style={styles.dangerButton} type="button" onClick={() => setAction({ kind: "reset", resource })}>{t.setup.pricing.resetOverride}</button> : <span style={styles.muted}>{t.setup.pricing.inherit}</span>}</div>
            </div>;
          })}
          {resources.every((resource) => resource.activity !== "PLAYSTATION") ? <p>{t.setup.pricing.noOverrides}</p> : null}
        </section>
      </> : null}
      <OwnerPasswordDialog actionLabel={action?.kind === "reset" ? t.setup.pricing.resetOverride : action?.kind === "override" ? t.setup.pricing.editOverride : t.setup.common.save} error={passwordError} open={Boolean(action)} onCancel={() => { setAction(undefined); setPasswordError(undefined); }} onConfirm={run} />
    </section>
  );
}
