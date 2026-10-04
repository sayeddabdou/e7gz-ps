import { useEffect, useMemo, useState } from "react";
import type { components } from "@e7gz/api-client";
import { apiErrorOf, type ApiClient } from "../../api";
import { useMessages } from "../../i18n";
import { setupErrorMessage } from "./errorMessages";
import { OwnerPasswordDialog } from "./OwnerPasswordDialog";
import { setupStyles as styles } from "./styles";

type Pricing = components["schemas"]["VenuePricing"];
type VenueResource = components["schemas"]["VenueResource"];
type Activity = components["schemas"]["Activity"];
type ConsoleType = components["schemas"]["ConsoleType"];
type PlayMode = components["schemas"]["PlayMode"];
type Action = { kind: "defaults" } | { kind: "override" | "reset"; resource: VenueResource };
type OverrideDraft = { SINGLE: string; MULTIPLAYER: string };
type Slot = { activity: Activity; consoleType: ConsoleType | null; playMode: PlayMode | null };

// Refusals about the PIN itself stay in the PIN dialog; any other refusal closes it and shows on the page.
const pinCodes = new Set(["OWNER_PASSWORD_REQUIRED", "OWNER_PASSWORD_INVALID", "RATE_LIMITED"]);

/**
 * Every default price the venue's activities need (FR-VEN-43, FR-VEN-44): PlayStation per console × play mode,
 * Billiards and Ping Pong one hourly rate. Built from the activities, so a venue with no prices yet can enter them.
 */
function slotsFor(activities: readonly Activity[]): Slot[] {
  return activities.flatMap((activity): Slot[] => activity === "PLAYSTATION"
    ? (["PS5", "PS4"] as const).flatMap((consoleType) => (["SINGLE", "MULTIPLAYER"] as const)
      .map((playMode) => ({ activity, consoleType, playMode })))
    : [{ activity, consoleType: null, playMode: null }]);
}

const slotKey = (slot: Slot) => `${slot.activity}|${slot.consoleType ?? ""}|${slot.playMode ?? ""}`;

export function PricingEditor({ api }: { api: ApiClient }) {
  const t = useMessages();
  const [pricing, setPricing] = useState<Pricing>();
  const [slots, setSlots] = useState<Slot[]>([]);
  const [defaultDrafts, setDefaultDrafts] = useState<Record<string, string>>({});
  const [resources, setResources] = useState<VenueResource[]>([]);
  const [overrideDrafts, setOverrideDrafts] = useState<Record<string, OverrideDraft>>({});
  const [action, setAction] = useState<Action>();
  const [error, setError] = useState<string>();
  const [actionError, setActionError] = useState<string>();
  const [passwordError, setPasswordError] = useState<string>();
  const [refreshVersion, setRefreshVersion] = useState(0);

  useEffect(() => {
    let active = true;
    void Promise.all([
      api.GET("/api/v1/venue/pricing"),
      api.GET("/api/v1/venue/resources"),
      api.GET("/api/v1/venue/settings"),
    ]).then(([priceResult, resourceResult, settingsResult]) => {
      if (!active) return;
      if (!priceResult.data) return setError(setupErrorMessage(apiErrorOf(priceResult.error).code, t));
      if (!resourceResult.data) return setError(setupErrorMessage(apiErrorOf(resourceResult.error).code, t));
      if (!settingsResult.data) return setError(setupErrorMessage(apiErrorOf(settingsResult.error).code, t));
      setPricing(priceResult.data.data);
      const venueSlots = slotsFor(settingsResult.data.data.activities);
      const saved = new Map(priceResult.data.data.defaults.map((item) => [slotKey(item), item.hourlyPrice.amount]));
      setSlots(venueSlots);
      setDefaultDrafts(Object.fromEntries(venueSlots.map((slot) => [slotKey(slot), saved.get(slotKey(slot)) ?? ""])));
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

  // Only filled fields are sent: a default price not sent keeps its saved value on the server, and a play mode with
  // no override uses the venue default (FR-VEN-43). The server checks every amount.
  const filledDefaults = slots.filter((slot) => defaultDrafts[slotKey(slot)]?.trim())
    .map((slot) => ({ ...slot, hourlyPrice: { amount: defaultDrafts[slotKey(slot)]!.trim(), currency: "EGP" as const } }));
  const filledOverrides = (resource: VenueResource) => (["SINGLE", "MULTIPLAYER"] as const)
    .filter((mode) => overrideDrafts[resource.id]?.[mode].trim())
    .map((playMode) => ({ playMode, hourlyPrice: { amount: overrideDrafts[resource.id]![playMode].trim(), currency: "EGP" as const } }));

  const ask = (next: Action) => {
    setActionError(undefined);
    const empty = next.kind === "defaults" ? filledDefaults.length === 0
      : next.kind === "override" ? filledOverrides(next.resource).length === 0 : false;
    if (empty) return setActionError(t.setup.pricing.enterAPrice);
    setAction(next);
  };

  const run = async (ownerPassword: string): Promise<boolean> => {
    if (!action) return false;
    setPasswordError(undefined);
    try {
      let result;
      if (action.kind === "defaults") {
        result = await api.PUT("/api/v1/venue/pricing", { body: { defaults: filledDefaults, ownerPassword } });
      } else if (action.kind === "override") {
        result = await api.PUT("/api/v1/venue/resources/{resourceId}/price-override", {
          params: { path: { resourceId: action.resource.id } },
          body: { ownerPassword, prices: filledOverrides(action.resource) },
        });
      } else {
        result = await api.DELETE("/api/v1/venue/resources/{resourceId}/price-override", { params: { path: { resourceId: action.resource.id } }, body: { ownerPassword } });
      }
      if (!result.response.ok) {
        const { code, field } = apiErrorOf(result.error);
        if (code && pinCodes.has(code)) {
          setPasswordError(setupErrorMessage(code, t));
          return false;
        }
        setAction(undefined);
        setActionError(code === "VALIDATION_FAILED" && field?.includes("hourlyPrice")
          ? t.setup.pricing.invalidPrice
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

  return (
    <section aria-labelledby="pricing-title" style={styles.page}>
      <div><h2 id="pricing-title" style={styles.heading}>{t.setup.pricing.title}</h2><p style={styles.intro}>{t.setup.pricing.description}</p></div>
      {!pricing && !error ? <p role="status">{t.setup.common.loading}</p> : null}
      {error ? <div role="alert" style={styles.error}><p>{error}</p><button style={styles.secondaryButton} type="button" onClick={retry}>{t.setup.common.retry}</button></div> : null}
      {actionError ? <div role="alert" style={styles.error}><p>{actionError}</p></div> : null}
      {pricing ? <>
        <section style={styles.card}>
          <h3 style={styles.cardTitle}>{t.setup.pricing.defaults}</h3>
          <div style={styles.grid}>{slots.map((slot) => <label key={slotKey(slot)} style={styles.field}><span style={styles.label}>{t.activity[slot.activity]}{slot.consoleType ? ` · ${slot.consoleType}` : ""}{slot.playMode ? ` · ${t.setup.pricing[slot.playMode]}` : ""}</span><input inputMode="decimal" placeholder="0.00" style={styles.input} value={defaultDrafts[slotKey(slot)] ?? ""} onChange={(event) => setDefaultDrafts({ ...defaultDrafts, [slotKey(slot)]: event.target.value })} /></label>)}</div>
          <button style={styles.primaryButton} type="button" onClick={() => ask({ kind: "defaults" })}>{t.setup.common.save}</button>
        </section>
        <section style={styles.card}>
          <h3 style={styles.cardTitle}>{t.setup.pricing.overrides}</h3>
          {resources.filter((resource) => resource.activity === "PLAYSTATION").map((resource) => {
            const draft = overrideDrafts[resource.id] ?? { SINGLE: "", MULTIPLAYER: "" };
            return <div key={resource.id} style={styles.card}>
              <strong>{resource.name} · {resource.consoleType}</strong>
              <div style={styles.grid}>{(["SINGLE", "MULTIPLAYER"] as const).map((mode) => <label key={mode} style={styles.field}><span style={styles.label}>{t.setup.pricing[mode]}</span><input inputMode="decimal" style={styles.input} value={draft[mode]} onChange={(event) => setOverrideDrafts({ ...overrideDrafts, [resource.id]: { ...draft, [mode]: event.target.value } })} /></label>)}</div>
              <div style={styles.actions}><button style={styles.secondaryButton} type="button" onClick={() => ask({ kind: "override", resource })}>{t.setup.pricing.editOverride}</button>{existingOverrideIds.has(resource.id) ? <button style={styles.dangerButton} type="button" onClick={() => ask({ kind: "reset", resource })}>{t.setup.pricing.resetOverride}</button> : <span style={styles.muted}>{t.setup.pricing.inherit}</span>}</div>
            </div>;
          })}
          {resources.every((resource) => resource.activity !== "PLAYSTATION") ? <p>{t.setup.pricing.noOverrides}</p> : null}
        </section>
      </> : null}
      <OwnerPasswordDialog actionLabel={action?.kind === "reset" ? t.setup.pricing.resetOverride : action?.kind === "override" ? t.setup.pricing.editOverride : t.setup.common.save} error={passwordError} open={Boolean(action)} onCancel={() => { setAction(undefined); setPasswordError(undefined); }} onConfirm={run} />
    </section>
  );
}
