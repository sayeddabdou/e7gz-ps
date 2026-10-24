import { useCallback, useEffect, useState, type CSSProperties } from "react";
import type { components } from "@e7gz/api-client";
import { webTokens } from "@e7gz/tokens/web";
import { apiErrorOf, type ApiClient } from "../../api";
import { useMessages, type Messages } from "../../i18n";
import { AvailabilitySchedule } from "../schedule";

type VenueOverview = components["schemas"]["VenueOverview"];
type Money = components["schemas"]["Money"];

const overviewErrorCodes = ["UNAUTHENTICATED", "FORBIDDEN", "NOT_FOUND", "RATE_LIMITED"] as const;
type OverviewErrorCode = (typeof overviewErrorCodes)[number];

function errorMessage(code: string | undefined, t: Messages): string {
  return overviewErrorCodes.includes(code as OverviewErrorCode)
    ? t.overview.errors[code as OverviewErrorCode]
    : t.overview.errors.unexpected;
}

function moneyText(money: Money): string {
  // FR-VEN-35: display the API amount string exactly; the Dashboard does not calculate or reformat revenue.
  return `${money.amount} ${money.currency}`;
}

export function OverviewScreen({
  api,
  bookingChangeVersion,
  direction,
  onOpenManualBooking,
  onOpenPendingRequests,
}: {
  api: ApiClient;
  bookingChangeVersion: number;
  direction: "ltr" | "rtl";
  onOpenManualBooking(): void;
  onOpenPendingRequests(): void;
}) {
  const t = useMessages();
  const [overview, setOverview] = useState<VenueOverview>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();
  const [refreshVersion, setRefreshVersion] = useState(0);

  useEffect(() => {
    let active = true;
    void api.GET("/api/v1/venue/overview")
      .then(({ data, error: body }) => {
        if (!active) return;
        if (!data) {
          setOverview(undefined);
          setError(errorMessage(apiErrorOf(body).code, t));
          return;
        }
        setError(undefined);
        setOverview(data.data);
      })
      .catch(() => {
        if (active) {
          setOverview(undefined);
          setError(t.overview.errors.network);
        }
      })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [api, bookingChangeVersion, refreshVersion, t]);

  const retry = useCallback(() => {
    setLoading(true);
    setError(undefined);
    setRefreshVersion((version) => version + 1);
  }, []);

  return (
    <section data-testid="overview-page" dir={direction} style={styles.page}>
      <header className="overview-header" style={styles.header}>
        <div>
          <h2 style={styles.heading}>{t.overview.title}</h2>
          <p style={styles.intro}>{t.overview.description}</p>
        </div>
        <div className="overview-actions" style={styles.actions}>
          <button onClick={onOpenManualBooking} style={styles.primaryButton} type="button">{t.overview.manualBooking}</button>
          <button onClick={onOpenPendingRequests} style={styles.secondaryButton} type="button">{t.overview.viewPending}</button>
        </div>
      </header>

      {loading ? <p role="status" style={styles.status}>{t.overview.loading}</p> : null}
      {!loading && error ? (
        <div role="alert" style={styles.error}>
          <span>{error}</span>
          <button onClick={retry} style={styles.secondaryButton} type="button">{t.overview.retry}</button>
        </div>
      ) : null}
      {!loading && !error && overview ? (
        <div className="overview-kpis" style={styles.kpis}>
          <Kpi label={t.overview.todayRevenue} value={moneyText(overview.todayRevenue)} />
          <Kpi label={t.overview.monthRevenue} value={moneyText(overview.monthRevenue)} />
          <Kpi label={t.overview.todayBookings} value={String(overview.todayBookings)} />
          <Kpi label={t.overview.pendingRequests} value={String(overview.pendingRequests)} />
        </div>
      ) : null}

      <AvailabilitySchedule api={api} direction={direction} refreshVersion={bookingChangeVersion} variant="overview" />
    </section>
  );
}

function Kpi({ label, value }: { label: string; value: string }) {
  return (
    <article style={styles.kpi}>
      <h3 style={styles.kpiLabel}>{label}</h3>
      <p style={styles.kpiValue}>{value}</p>
    </article>
  );
}

const styles = {
  page: { display: "grid", gap: webTokens.spacing["7xl"] },
  header: { alignItems: "end", display: "flex", flexWrap: "wrap", gap: webTokens.spacing["3xl"], justifyContent: "space-between" },
  heading: { fontSize: webTokens.typography.fontSize.displaySmall, fontWeight: webTokens.typography.fontWeight.extrabold, lineHeight: webTokens.typography.lineHeight.tight, marginBlock: 0 },
  intro: { color: webTokens.color.text.venueMuted, marginBlock: webTokens.spacing.sm },
  actions: { display: "flex", flexWrap: "wrap", gap: webTokens.spacing.md },
  primaryButton: { backgroundColor: webTokens.color.brand.venue, border: 0, borderRadius: webTokens.radius.lg, color: webTokens.color.text.inverse, cursor: "pointer", fontWeight: webTokens.typography.fontWeight.bold, paddingBlock: webTokens.spacing.lg, paddingInline: webTokens.spacing["3xl"] },
  secondaryButton: { backgroundColor: webTokens.color.surface.raised, border: `${webTokens.spacing.hairline}px solid ${webTokens.color.border.venue}`, borderRadius: webTokens.radius.lg, color: webTokens.color.text.venue, cursor: "pointer", fontWeight: webTokens.typography.fontWeight.bold, paddingBlock: webTokens.spacing.lg, paddingInline: webTokens.spacing["3xl"] },
  kpis: { display: "grid", gap: webTokens.spacing["3xl"], gridTemplateColumns: "repeat(4, minmax(0, 1fr))" },
  kpi: { backgroundColor: webTokens.color.surface.raised, border: `${webTokens.spacing.hairline}px solid ${webTokens.color.border.subtle}`, borderRadius: webTokens.radius["3xl"], boxShadow: webTokens.shadow.card, padding: webTokens.spacing["4xl"] },
  kpiLabel: { color: webTokens.color.text.venueMuted, fontSize: webTokens.typography.fontSize.label, fontWeight: webTokens.typography.fontWeight.bold, marginBlock: 0 },
  kpiValue: { color: webTokens.color.text.venue, fontSize: webTokens.typography.fontSize.displaySmall, fontVariantNumeric: "tabular-nums", fontWeight: webTokens.typography.fontWeight.extrabold, marginBlockEnd: 0, marginBlockStart: webTokens.spacing.lg },
  status: { color: webTokens.color.text.venueMuted },
  error: { alignItems: "start", backgroundColor: webTokens.color.feedback.dangerSurface, borderRadius: webTokens.radius.lg, color: webTokens.color.feedback.danger, display: "grid", gap: webTokens.spacing.lg, padding: webTokens.spacing.xl },
} satisfies Record<string, CSSProperties>;
