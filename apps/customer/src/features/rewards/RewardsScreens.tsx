import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { ActivityIndicator, AppState, Pressable, ScrollView, Share, StyleSheet, Text, View } from "react-native";
import type { components } from "@e7gz/api-client";
import { nativeTokens } from "@e7gz/tokens/native";
import type { ApiClient } from "../../api";
import { en as t } from "../../i18n/en";
import {
  createCustomerRewardsApi,
  type CustomerPoints,
  type CustomerReferrals,
  type CustomerStanding,
  type CustomerWallet,
  type RewardsResult,
} from "./api";
import {
  pointsCauseMessages,
  referralStatusMessages,
  rewardsErrorMessage,
  verifiedQualificationMessages,
  verifiedStatusMessages,
  walletCauseMessages,
} from "./messages";

type PointsTransaction = components["schemas"]["CustomerPointsTransaction"];
type Referral = components["schemas"]["CustomerReferral"];
type WalletTransaction = components["schemas"]["CustomerWalletTransaction"];
type Money = components["schemas"]["Money"];

type ScreenProps = { api: ApiClient; onBack(): void; onOpenBooking?(bookingId: string): void };
const BAN_EXPIRY_RETRY_MS = 60_000;

export function remainingBanText(serverTime: string, endsAt: string, now = Date.parse(serverTime)): string {
  const remainingMinutes = Math.max(0, Math.floor((Date.parse(endsAt) - now) / 60_000));
  if (remainingMinutes <= 0) return t.rewards.standing.ending;
  const days = Math.floor(remainingMinutes / 1_440);
  const hours = Math.floor((remainingMinutes % 1_440) / 60);
  const minutes = remainingMinutes % 60;
  if (days > 0) return t.rewards.standing.remainingDays(days, hours);
  if (hours > 0) return t.rewards.standing.remainingHours(hours, minutes);
  return t.rewards.standing.remainingMinutes(minutes);
}

function dateText(value: string): string {
  return new Intl.DateTimeFormat("en", { dateStyle: "medium", timeStyle: "short", timeZone: "Africa/Cairo" }).format(new Date(value));
}

function moneyText(money: Money): string {
  return `${money.amount} ${money.currency}`;
}

function useSingle<T>(load: () => Promise<RewardsResult<T>>) {
  const [value, setValue] = useState<T>();
  const [serverTime, setServerTime] = useState<string>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();
  const [version, setVersion] = useState(0);
  useEffect(() => {
    let active = true;
    void load().then((result) => {
      if (!active) return;
      if (result.ok) {
        setValue(result.value);
        setServerTime(result.serverTime);
      } else setError(rewardsErrorMessage(result.code));
    }).catch(() => { if (active) setError(t.rewards.errors.unexpected); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [load, version]);
  const retry = useCallback(() => {
    setLoading(true);
    setError(undefined);
    setVersion((current) => current + 1);
  }, []);
  return { value, serverTime, loading, error, retry };
}

function usePaged<Item, T extends { items: Item[]; nextCursor?: string | null }>(load: (cursor?: string) => Promise<RewardsResult<T>>) {
  const firstLoad = useCallback(() => load(), [load]);
  const state = useSingle(firstLoad);
  const [extraPages, setExtraPages] = useState<T[]>([]);
  const [moreBusy, setMoreBusy] = useState(false);
  const [moreError, setMoreError] = useState<string>();
  const nextCursor = extraPages.length
    ? extraPages[extraPages.length - 1]?.nextCursor ?? null
    : state.value?.nextCursor ?? null;
  const loadMore = async () => {
    if (!nextCursor || moreBusy) return;
    setMoreBusy(true);
    setMoreError(undefined);
    try {
      const result = await load(nextCursor);
      if (result.ok) {
        setExtraPages((pages) => [...pages, result.value]);
      } else setMoreError(rewardsErrorMessage(result.code));
    } catch {
      setMoreError(t.rewards.errors.unexpected);
    } finally {
      setMoreBusy(false);
    }
  };
  const retry = () => {
    setExtraPages([]);
    setMoreError(undefined);
    state.retry();
  };
  return { ...state, retry, items: [...(state.value?.items ?? []), ...extraPages.flatMap((page) => page.items)], nextCursor, moreBusy, moreError, loadMore };
}

export function AccountStandingScreen({ api, onBack }: ScreenProps) {
  const rewardsApi = useMemo(() => createCustomerRewardsApi(api), [api]);
  const load = useCallback(() => rewardsApi.getStanding(), [rewardsApi]);
  const state = useSingle<CustomerStanding>(load);
  const retryStanding = state.retry;
  const expiryRefresh = useRef<{ banKey: string; lastAttemptAt: number } | undefined>(undefined);
  const expiryRetryTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const activeBanKey = state.value?.currentBan
    ? `${state.value.currentBan.banId}:${state.value.currentBan.endsAt}`
    : undefined;
  const refreshExpiredBan = useCallback((banId: string, endsAt: string) => {
    const banKey = `${banId}:${endsAt}`;
    const now = monotonicNow();
    const previous = expiryRefresh.current;
    const attempt = () => {
      expiryRefresh.current = { banKey, lastAttemptAt: monotonicNow() };
      retryStanding();
    };
    if (!previous || previous.banKey !== banKey) {
      if (expiryRetryTimer.current) clearTimeout(expiryRetryTimer.current);
      expiryRetryTimer.current = undefined;
      attempt();
      return;
    }
    const waitMs = BAN_EXPIRY_RETRY_MS - (now - previous.lastAttemptAt);
    if (waitMs <= 0) {
      attempt();
      return;
    }
    if (expiryRetryTimer.current) return;
    expiryRetryTimer.current = setTimeout(() => {
      expiryRetryTimer.current = undefined;
      if (expiryRefresh.current?.banKey !== banKey) return;
      attempt();
    }, waitMs);
  }, [retryStanding]);
  useEffect(() => {
    if (activeBanKey && expiryRefresh.current?.banKey === activeBanKey) return;
    expiryRefresh.current = undefined;
    if (expiryRetryTimer.current) clearTimeout(expiryRetryTimer.current);
    expiryRetryTimer.current = undefined;
  }, [activeBanKey]);
  useEffect(() => () => {
    if (expiryRetryTimer.current) clearTimeout(expiryRetryTimer.current);
  }, []);
  useEffect(() => {
    const subscription = AppState.addEventListener("change", (nextState) => {
      if (nextState === "active") retryStanding();
    });
    return () => subscription.remove();
  }, [retryStanding]);
  if (!state.value) return <ResourceState title={t.rewards.standing.title} onBack={onBack} loading={state.loading} error={state.error} retry={state.retry} />;
  const standing = state.value;
  const verified = standing.verifiedPlayer;
  return (
    <Screen title={t.rewards.standing.title} onBack={onBack}>
      <View style={styles.card}>
        <Text style={styles.sectionTitle}>{t.rewards.standing.verifiedPlayer}</Text>
        <Text style={styles.value}>{verifiedStatusMessages[verified.status]}</Text>
        <Text style={styles.muted}>{verifiedQualificationMessages[verified.qualification]}</Text>
        <Text style={styles.body}>{t.rewards.standing.progress(verified.completedBookings, verified.requiredBookings)}</Text>
        <View style={styles.progressTrack}><View style={[styles.progressBar, { width: `${Math.min(100, verified.requiredBookings ? verified.completedBookings / verified.requiredBookings * 100 : 0)}%` }]} /></View>
      </View>
      <View style={[styles.card, standing.currentBan ? styles.dangerCard : undefined]}>
        <Text style={styles.sectionTitle}>{standing.currentBan ? t.rewards.standing.banned : t.rewards.standing.active}</Text>
        <Text style={styles.value}>{t.rewards.standing.noShowProgress(standing.consecutiveNoShows, standing.currentThreshold)}</Text>
        <Text style={styles.muted}>{t.rewards.standing.penaltyStage(standing.penaltyStage)}</Text>
        {standing.currentBan && state.serverTime ? (
          <>
            <BanRemaining
              key={`${state.serverTime}-${standing.currentBan.endsAt}`}
              serverTime={state.serverTime}
              endsAt={standing.currentBan.endsAt}
              onExpired={() => refreshExpiredBan(standing.currentBan!.banId, standing.currentBan!.endsAt)}
            />
            <Text style={styles.body}>{t.rewards.standing.started(dateText(standing.currentBan.startsAt))}</Text>
            <Text style={styles.body}>{t.rewards.standing.ends(dateText(standing.currentBan.endsAt))}</Text>
          </>
        ) : <Text style={styles.body}>{t.rewards.standing.activeHelp}</Text>}
      </View>
    </Screen>
  );
}

function monotonicNow(): number {
  return globalThis.performance.now();
}

function BanRemaining({ serverTime, endsAt, onExpired }: { serverTime: string; endsAt: string; onExpired(): void }) {
  const receivedAt = useRef(monotonicNow());
  const expiryRefreshStarted = useRef(false);
  const [elapsedMs, setElapsedMs] = useState(0);
  useEffect(() => {
    const update = () => setElapsedMs(Math.max(0, monotonicNow() - receivedAt.current));
    const timer = setInterval(update, 1_000);
    return () => clearInterval(timer);
  }, []);
  const estimatedServerNow = Date.parse(serverTime) + elapsedMs;
  useEffect(() => {
    if (estimatedServerNow < Date.parse(endsAt) || expiryRefreshStarted.current) return;
    expiryRefreshStarted.current = true;
    onExpired();
  }, [endsAt, estimatedServerNow, onExpired]);
  return <Text style={styles.dangerText}>{remainingBanText(serverTime, endsAt, estimatedServerNow)}</Text>;
}

export function PointsScreen({ api, onBack, onOpenBooking }: ScreenProps) {
  const rewardsApi = useMemo(() => createCustomerRewardsApi(api), [api]);
  const load = useCallback((cursor?: string) => rewardsApi.getPoints(cursor), [rewardsApi]);
  const state = usePaged<PointsTransaction, CustomerPoints>(load);
  if (!state.value) return <ResourceState title={t.rewards.points.title} onBack={onBack} loading={state.loading} error={state.error} retry={state.retry} />;
  const items = state.items.filter((item) => !(item.cause === "COMPENSATION" && item.amount === 0));
  const fee = state.value.feeUnit;
  return (
    <Screen title={t.rewards.points.title} onBack={onBack}>
      <View style={styles.pointsBalance}><Text style={styles.balance}>{t.rewards.points.balance(state.value.balance)}</Text></View>
      <Text style={styles.explanation}>{fee.bookingFeeMode === "PER_HOUR" ? t.rewards.points.perHour(fee.pointsPerFeeUnit) : t.rewards.points.perBooking(fee.pointsPerFeeUnit)}</Text>
      <SectionTitle>{t.rewards.history}</SectionTitle>
      {!items.length && !state.nextCursor ? <Empty title={t.rewards.points.empty} body={t.rewards.points.emptyBody} /> : items.map((item) => (
        <TransactionRow
          key={item.transactionId}
          title={pointsCauseMessages[item.cause]}
          detail={t.rewards.points.balanceAfter(item.balanceAfter, dateText(item.occurredAt))}
          amount={`${item.amount > 0 ? "+" : item.amount < 0 ? "−" : ""}${Math.abs(item.amount).toLocaleString("en")} ${t.rewards.points.unit}`}
          bookingId={item.bookingId}
          onOpenBooking={onOpenBooking}
        />
      ))}
      <LoadMore {...state} />
    </Screen>
  );
}

export function WalletScreen({ api, onBack, onOpenBooking }: ScreenProps) {
  const rewardsApi = useMemo(() => createCustomerRewardsApi(api), [api]);
  const load = useCallback((cursor?: string) => rewardsApi.getWallet(cursor), [rewardsApi]);
  const state = usePaged<WalletTransaction, CustomerWallet>(load);
  if (!state.value) return <ResourceState title={t.rewards.wallet.title} onBack={onBack} loading={state.loading} error={state.error} retry={state.retry} />;
  return (
    <Screen title={t.rewards.wallet.title} onBack={onBack}>
      <View style={styles.walletBalance}>
        <Text style={styles.inverseMuted}>{t.rewards.wallet.balance}</Text>
        <Text style={styles.inverseBalance}>{moneyText(state.value.balance)}</Text>
        <Text style={styles.inverseBody}>{t.rewards.wallet.explanation}</Text>
      </View>
      <SectionTitle>{t.rewards.history}</SectionTitle>
      {!state.items.length ? <Empty title={t.rewards.wallet.empty} body={t.rewards.wallet.emptyBody} /> : state.items.map((item) => (
        <TransactionRow
          key={item.transactionId}
          title={walletCauseMessages[item.cause]}
          detail={t.rewards.wallet.balanceAfter(moneyText(item.balanceAfter), dateText(item.occurredAt))}
          amount={`${item.cause === "BOOKING_FEE_REFUND" ? "+" : "−"}${moneyText(item.amount)}`}
          bookingId={item.bookingId}
          onOpenBooking={onOpenBooking}
        />
      ))}
      <LoadMore {...state} />
    </Screen>
  );
}

export function ReferralsScreen({ api, onBack }: ScreenProps) {
  const rewardsApi = useMemo(() => createCustomerRewardsApi(api), [api]);
  const load = useCallback((cursor?: string) => rewardsApi.getReferrals(cursor), [rewardsApi]);
  const state = usePaged<Referral, CustomerReferrals>(load);
  const [shareError, setShareError] = useState<string>();
  if (!state.value) return <ResourceState title={t.rewards.referrals.title} onBack={onBack} loading={state.loading} error={state.error} retry={state.retry} />;
  const shareCode = async () => {
    setShareError(undefined);
    try {
      await Share.share({ message: state.value!.referralCode });
    } catch {
      setShareError(t.rewards.referrals.shareFailed);
    }
  };
  return (
    <Screen title={t.rewards.referrals.title} onBack={onBack}>
      <View style={styles.referralCard}>
        <Text style={styles.muted}>{t.rewards.referrals.yourCode}</Text>
        <Text selectable style={styles.code}>{state.value.referralCode}</Text>
      </View>
      <Pressable accessibilityRole="button" accessibilityLabel={t.rewards.referrals.share} onPress={() => void shareCode()} style={styles.primaryButton}><Text style={styles.primaryButtonText}>{t.rewards.referrals.share}</Text></Pressable>
      {shareError ? <Text accessibilityRole="alert" style={styles.dangerText}>{shareError}</Text> : null}
      <Text style={styles.explanation}>{t.rewards.referrals.explanation}</Text>
      <SectionTitle>{t.rewards.referrals.history}</SectionTitle>
      {!state.items.length ? <Empty title={t.rewards.referrals.empty} body={t.rewards.referrals.emptyBody} /> : state.items.map((item) => (
        <View key={item.referralId} style={styles.transaction}>
          <View style={styles.transactionBody}>
            <Text style={styles.sectionTitle}>{referralStatusMessages[item.status]}</Text>
            <Text style={styles.muted}>{t.rewards.referrals.signedUp(dateText(item.signedUpAt))}</Text>
            {item.rewardedAt ? <Text style={styles.muted}>{t.rewards.referrals.rewarded(dateText(item.rewardedAt))}</Text> : null}
          </View>
          {item.rewardPoints !== null ? <Text style={styles.positive}>{`+${item.rewardPoints.toLocaleString("en")} ${t.rewards.points.unit}`}</Text> : null}
        </View>
      ))}
      <LoadMore {...state} />
    </Screen>
  );
}

function Screen({ title, onBack, children }: { title: string; onBack(): void; children: ReactNode }) {
  return <View style={styles.screen}><BackHeader title={title} onBack={onBack} /><ScrollView contentContainerStyle={styles.content}>{children}</ScrollView></View>;
}

function BackHeader({ title, onBack }: { title: string; onBack(): void }) {
  return <View style={styles.header}><Pressable accessibilityRole="button" accessibilityLabel={t.discovery.back} onPress={onBack} style={styles.backButton}><Text style={styles.backText}>‹</Text></Pressable><Text accessibilityRole="header" style={styles.headerTitle}>{title}</Text></View>;
}

function ResourceState({ title, onBack, loading, error, retry }: { title: string; onBack(): void; loading: boolean; error?: string; retry(): void }) {
  return <View style={styles.screen}><BackHeader title={title} onBack={onBack} /><View style={styles.state}>{loading ? <><ActivityIndicator /><Text>{t.loading}</Text></> : <><Text accessibilityRole="alert" style={styles.dangerText}>{error}</Text><Pressable accessibilityRole="button" onPress={retry} style={styles.secondaryButton}><Text style={styles.secondaryButtonText}>{t.retry}</Text></Pressable></>}</View></View>;
}

function SectionTitle({ children }: { children: ReactNode }) { return <Text style={styles.sectionHeading}>{children}</Text>; }
function Empty({ title, body }: { title: string; body: string }) { return <View style={styles.empty}><Text style={styles.sectionTitle}>{title}</Text><Text style={styles.muted}>{body}</Text></View>; }

function TransactionRow({ title, detail, amount, bookingId, onOpenBooking }: { title: string; detail: string; amount: string; bookingId: string | null; onOpenBooking?(bookingId: string): void }) {
  const content = <><View style={styles.transactionBody}><Text style={styles.sectionTitle}>{title}</Text><Text style={styles.muted}>{detail}</Text></View><Text style={amount.startsWith("+") ? styles.positive : amount.startsWith("−") ? styles.negative : styles.value}>{amount}</Text></>;
  return bookingId && onOpenBooking
    ? <Pressable key={bookingId} accessibilityRole="button" accessibilityLabel={t.rewards.openBooking(bookingId)} onPress={() => onOpenBooking(bookingId)} style={({ pressed }) => [styles.transaction, pressed && styles.pressed]}>{content}</Pressable>
    : <View style={styles.transaction}>{content}</View>;
}

function LoadMore({ nextCursor, moreBusy, moreError, loadMore }: { nextCursor?: string | null; moreBusy: boolean; moreError?: string; loadMore(): void }) {
  return <>{moreError ? <Text accessibilityRole="alert" style={styles.dangerText}>{moreError}</Text> : null}{nextCursor ? <Pressable accessibilityRole="button" disabled={moreBusy} onPress={() => void loadMore()} style={styles.secondaryButton}><Text style={styles.secondaryButtonText}>{moreBusy ? t.loading : t.rewards.loadMore}</Text></Pressable> : null}</>;
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: nativeTokens.color.surface.raised },
  header: { alignItems: "center", borderBottomColor: nativeTokens.color.border.subtle, borderBottomWidth: nativeTokens.spacing.hairline, flexDirection: "row", gap: nativeTokens.spacing["2xl"], paddingHorizontal: nativeTokens.spacing["4xl"], paddingVertical: nativeTokens.spacing["2xl"] },
  backButton: { alignItems: "center", borderColor: nativeTokens.color.border.customer, borderRadius: nativeTokens.radius.xl, borderWidth: nativeTokens.spacing.hairline, height: 36, justifyContent: "center", width: 36 },
  backText: { color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.heading },
  headerTitle: { color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.titleSmall, fontWeight: nativeTokens.typography.fontWeight.bold },
  content: { gap: nativeTokens.spacing["3xl"], padding: nativeTokens.spacing["4xl"], paddingBottom: nativeTokens.spacing["8xl"] },
  state: { alignItems: "center", flex: 1, gap: nativeTokens.spacing["2xl"], justifyContent: "center", padding: nativeTokens.spacing["4xl"] },
  card: { backgroundColor: nativeTokens.color.surface.raised, borderColor: nativeTokens.color.border.customer, borderRadius: nativeTokens.radius["4xl"], borderWidth: nativeTokens.spacing.hairline, gap: nativeTokens.spacing.md, padding: nativeTokens.spacing["3xl"] },
  dangerCard: { backgroundColor: nativeTokens.color.feedback.dangerSurface },
  sectionHeading: { color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.titleSmall, fontWeight: nativeTokens.typography.fontWeight.extrabold },
  sectionTitle: { color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.bodyLarge, fontWeight: nativeTokens.typography.fontWeight.bold },
  value: { color: nativeTokens.color.text.customer, fontWeight: nativeTokens.typography.fontWeight.bold },
  body: { color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.bodySmall },
  muted: { color: nativeTokens.color.text.muted, fontSize: nativeTokens.typography.fontSize.bodySmall },
  dangerText: { color: nativeTokens.color.feedback.danger, fontWeight: nativeTokens.typography.fontWeight.bold },
  positive: { color: nativeTokens.color.feedback.success, fontWeight: nativeTokens.typography.fontWeight.extrabold },
  negative: { color: nativeTokens.color.feedback.danger, fontWeight: nativeTokens.typography.fontWeight.extrabold },
  progressTrack: { backgroundColor: nativeTokens.color.border.subtle, borderRadius: nativeTokens.radius.lg, height: nativeTokens.spacing.md, overflow: "hidden" },
  progressBar: { backgroundColor: nativeTokens.color.brand.primary, borderRadius: nativeTokens.radius.lg, height: "100%" },
  pointsBalance: { backgroundColor: nativeTokens.color.surface.info, borderRadius: nativeTokens.radius["5xl"], padding: nativeTokens.spacing["4xl"] },
  balance: { color: nativeTokens.color.brand.primary, fontSize: nativeTokens.typography.fontSize.display, fontWeight: nativeTokens.typography.fontWeight.extrabold },
  walletBalance: { backgroundColor: nativeTokens.color.text.customer, borderRadius: nativeTokens.radius["5xl"], gap: nativeTokens.spacing.md, padding: nativeTokens.spacing["4xl"] },
  inverseMuted: { color: nativeTokens.color.text.inverse },
  inverseBalance: { color: nativeTokens.color.text.inverse, fontSize: nativeTokens.typography.fontSize.display, fontWeight: nativeTokens.typography.fontWeight.extrabold },
  inverseBody: { color: nativeTokens.color.text.inverse, lineHeight: 20 },
  explanation: { backgroundColor: nativeTokens.color.surface.customerSubtle, borderRadius: nativeTokens.radius["3xl"], color: nativeTokens.color.text.muted, lineHeight: 20, padding: nativeTokens.spacing["3xl"] },
  transaction: { alignItems: "center", borderBottomColor: nativeTokens.color.border.subtle, borderBottomWidth: nativeTokens.spacing.hairline, flexDirection: "row", gap: nativeTokens.spacing["2xl"], minHeight: 64, paddingVertical: nativeTokens.spacing["2xl"] },
  transactionBody: { flex: 1, gap: nativeTokens.spacing.xs },
  empty: { alignItems: "center", borderColor: nativeTokens.color.border.subtle, borderRadius: nativeTokens.radius["4xl"], borderStyle: "dashed", borderWidth: nativeTokens.spacing.hairline, gap: nativeTokens.spacing.md, padding: nativeTokens.spacing["6xl"] },
  referralCard: { alignItems: "center", backgroundColor: nativeTokens.color.surface.info, borderColor: nativeTokens.color.brand.primary, borderRadius: nativeTokens.radius["4xl"], borderStyle: "dashed", borderWidth: 2, gap: nativeTokens.spacing.md, padding: nativeTokens.spacing["4xl"] },
  code: { color: nativeTokens.color.brand.primary, fontSize: nativeTokens.typography.fontSize.heading, fontWeight: nativeTokens.typography.fontWeight.extrabold, letterSpacing: 1 },
  primaryButton: { alignItems: "center", backgroundColor: nativeTokens.color.brand.primary, borderRadius: nativeTokens.radius["3xl"], justifyContent: "center", minHeight: 52, ...nativeTokens.shadow.action },
  primaryButtonText: { color: nativeTokens.color.text.inverse, fontWeight: nativeTokens.typography.fontWeight.bold },
  secondaryButton: { alignItems: "center", borderColor: nativeTokens.color.border.customer, borderRadius: nativeTokens.radius["2xl"], borderWidth: nativeTokens.spacing.hairline, justifyContent: "center", minHeight: 48, paddingHorizontal: nativeTokens.spacing["3xl"] },
  secondaryButtonText: { color: nativeTokens.color.brand.primary, fontWeight: nativeTokens.typography.fontWeight.bold },
  pressed: { opacity: 0.75 },
});
