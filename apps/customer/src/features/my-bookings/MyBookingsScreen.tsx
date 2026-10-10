import { useCallback, useEffect, useRef, useState } from "react";
import {
  ActivityIndicator, Modal, Pressable, RefreshControl, ScrollView, StyleSheet, Text, View,
} from "react-native";
import type { components } from "@e7gz/api-client";
import { nativeLogicalSpacing, nativeTokens } from "@e7gz/tokens/native";
import { errorCodeOf, type ApiClient } from "../../api";
import { en as t } from "../../i18n/en";
import { formatMoney } from "../discovery/format";
import { NotificationInbox } from "../notifications/NotificationInbox";
import { bookingEventText, bookingReasonText, bookingStatusText, canRequestCancellation } from "./bookingText";
import { customerBookingErrorMessage } from "./errorMessages";

type Bucket = components["schemas"]["CustomerBookingBucket"];
type BookingSummary = components["schemas"]["CustomerBookingSummary"];
type BookingDetail = components["schemas"]["CustomerBookingDetail"];
type Page = { items: BookingSummary[]; nextCursor: string | null };

const buckets: Bucket[] = ["upcoming", "completed", "cancelled"];

export function MyBookingsScreen({
  api,
  focusVersion = 0,
  idempotencyKeyFactory = createIdempotencyKey,
  openBookingId,
  onOpenBookingConsumed,
}: {
  api: ApiClient;
  focusVersion?: number;
  idempotencyKeyFactory?: () => string;
  openBookingId?: string;
  onOpenBookingConsumed?: () => void;
}) {
  const [bucket, setBucket] = useState<Bucket>("upcoming");
  const [page, setPage] = useState<Page>();
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string>();
  const [detailId, setDetailId] = useState<string>();
  const [showNotifications, setShowNotifications] = useState(false);
  const generationRef = useRef(0);

  const load = useCallback(async (mode: "replace" | "refresh" | "append" = "replace", cursor?: string) => {
    const generation = ++generationRef.current;
    if (mode === "replace") setLoading(true);
    if (mode === "refresh") setRefreshing(true);
    if (mode === "append") setLoadingMore(true);
    setError(undefined);
    try {
      const { data, error: responseError } = await api.GET("/api/v1/customer/bookings", {
        params: { query: { bucket, limit: 20, ...(mode === "append" && cursor ? { cursor } : {}) } },
      });
      if (generation !== generationRef.current) return;
      if (data) {
        setPage((previous) => mode === "append"
          ? { items: dedupe([...(previous?.items ?? []), ...data.data.items]), nextCursor: data.data.nextCursor }
          : data.data);
      } else setError(customerBookingErrorMessage(errorCodeOf(responseError)));
    } catch {
      if (generation === generationRef.current) setError(t.myBookings.errors.network);
    } finally {
      if (generation === generationRef.current) {
        setLoading(false);
        setRefreshing(false);
        setLoadingMore(false);
      }
    }
  }, [api, bucket]);

  useEffect(() => { void Promise.resolve().then(() => load("replace")); }, [load, focusVersion]);
  const activeDetailId = openBookingId ?? detailId;
  if (activeDetailId) {
    return (
      <BookingDetailScreen
        api={api}
        bookingId={activeDetailId}
        idempotencyKeyFactory={idempotencyKeyFactory}
        onBack={() => {
          if (openBookingId) onOpenBookingConsumed?.();
          else setDetailId(undefined);
          void load("refresh");
        }}
      />
    );
  }
  if (showNotifications) {
    return (
      <NotificationInbox
        api={api}
        onBack={() => setShowNotifications(false)}
        onOpenBooking={(bookingId) => { setShowNotifications(false); setDetailId(bookingId); }}
      />
    );
  }

  return (
    <View style={styles.screen}>
      <View style={styles.header}>
        <Text accessibilityRole="header" style={styles.title}>{t.myBookings.title}</Text>
        <Pressable accessibilityRole="button" onPress={() => setShowNotifications(true)} style={styles.iconButton}>
          <Text style={styles.iconText}>{t.myBookings.notifications}</Text>
        </Pressable>
      </View>
      <View style={styles.tabs}>
        {buckets.map((value) => (
          <Pressable
            accessibilityRole="tab"
            accessibilityState={{ selected: bucket === value }}
            key={value}
            onPress={() => { setPage(undefined); setBucket(value); }}
            style={[styles.tab, bucket === value && styles.tabSelected]}
          >
            <Text style={[styles.tabText, bucket === value && styles.tabTextSelected]}>{t.myBookings.tabs[value]}</Text>
          </Pressable>
        ))}
      </View>
      <ScrollView
        contentContainerStyle={styles.content}
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => void load("refresh")} />}
      >
        {loading ? <ActivityIndicator accessibilityLabel={t.loading} color={nativeTokens.color.brand.primary} /> : null}
        {!loading && error ? <ErrorState message={error} retry={() => void load("replace")} /> : null}
        {!loading && !error && page?.items.length === 0 ? (
          <View style={styles.empty}>
            <Text style={styles.emptyTitle}>{t.myBookings.empty[bucket]}</Text>
            <Text style={styles.muted}>{t.myBookings.emptyBody}</Text>
          </View>
        ) : null}
        {!loading && !error ? page?.items.map((item) => (
          <BookingCard key={item.bookingId} booking={item} onPress={() => setDetailId(item.bookingId)} />
        )) : null}
        {!loading && !error && page?.nextCursor ? (
          <Pressable disabled={loadingMore} onPress={() => void load("append", page.nextCursor ?? undefined)} style={styles.secondaryButton}>
            <Text style={styles.secondaryButtonText}>{loadingMore ? t.loading : t.myBookings.loadMore}</Text>
          </Pressable>
        ) : null}
      </ScrollView>
    </View>
  );
}

function BookingCard({ booking, onPress }: { booking: BookingSummary; onPress(): void }) {
  const reason = bookingReasonText(booking.status, booking.terminalReason);
  return (
    <Pressable accessibilityRole="button" onPress={onPress} style={styles.card}>
      <View style={styles.rowBetween}>
        <Text style={styles.cardTitle}>{booking.venue.name}</Text>
        <StatusPill status={booking.status} />
      </View>
      <Text style={styles.body}>{resourceLabel(booking.resource)}</Text>
      <Text style={styles.muted}>{formatDateTimeRange(booking.startAt, booking.endAt)}</Text>
      {reason ? <Text style={styles.reason}>{reason}</Text> : null}
      <Text style={styles.price}>{formatMoney(booking.totalAmount)}</Text>
    </Pressable>
  );
}

function BookingDetailScreen({
  api, bookingId, idempotencyKeyFactory, onBack,
}: {
  api: ApiClient;
  bookingId: string;
  idempotencyKeyFactory(): string;
  onBack(): void;
}) {
  const [booking, setBooking] = useState<BookingDetail>();
  const [serverTime, setServerTime] = useState<string>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();
  const [confirming, setConfirming] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const [cancelError, setCancelError] = useState<string>();
  const keyRef = useRef<string | undefined>(undefined);
  const inFlightRef = useRef(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(undefined);
    try {
      const { data, error: responseError } = await api.GET("/api/v1/customer/bookings/{bookingId}", {
        params: { path: { bookingId } },
      });
      if (data) { setBooking(data.data); setServerTime(data.meta.serverTime); }
      else setError(customerBookingErrorMessage(errorCodeOf(responseError)));
    } catch { setError(t.myBookings.errors.network); }
    finally { setLoading(false); }
  }, [api, bookingId]);
  useEffect(() => { void Promise.resolve().then(load); }, [load]);

  const cancel = async () => {
    if (inFlightRef.current) return;
    inFlightRef.current = true;
    setCancelling(true);
    setCancelError(undefined);
    const key = keyRef.current ?? idempotencyKeyFactory();
    keyRef.current = key;
    try {
      const { data, error: responseError } = await api.POST("/api/v1/customer/bookings/{bookingId}/cancel", {
        params: { path: { bookingId }, header: { "Idempotency-Key": key } },
      });
      if (data) { setBooking(data.data); setServerTime(data.meta.serverTime); setConfirming(false); }
      else setCancelError(customerBookingErrorMessage(errorCodeOf(responseError)));
    } catch { setCancelError(t.myBookings.errors.network); }
    finally { inFlightRef.current = false; setCancelling(false); }
  };

  if (loading) return <View style={styles.center}><ActivityIndicator accessibilityLabel={t.loading} /></View>;
  if (error || !booking || !serverTime) {
    return <View style={styles.screen}><BackHeader title={t.myBookings.detailTitle} onBack={onBack} /><ErrorState message={error ?? t.myBookings.errors.unexpected} retry={() => void load()} /></View>;
  }
  const reason = bookingReasonText(booking.status, booking.terminalReason);
  const canCancel = canRequestCancellation(booking.status, booking.startAt, serverTime);
  return (
    <View style={styles.screen}>
      <BackHeader title={t.myBookings.detailTitle} onBack={onBack} />
      <ScrollView contentContainerStyle={styles.content}>
        <View style={styles.card}>
          <View style={styles.rowBetween}><Text style={styles.cardTitle}>{booking.venue.name}</Text><StatusPill status={booking.status} /></View>
          <Text style={styles.body}>{resourceLabel(booking.resource)}</Text>
          <DetailRow label={t.myBookings.date} value={formatDate(booking.startAt)} />
          <DetailRow label={t.myBookings.time} value={formatTimeRange(booking.startAt, booking.endAt)} />
          <DetailRow label={t.myBookings.duration} value={t.booking.durationValue(booking.nominalBookedMinutes)} />
          <DetailRow label={t.booking.total} value={formatMoney(booking.totalAmount) ?? ""} strong />
          {reason ? <DetailRow label={t.myBookings.reason} value={reason} /> : null}
          {booking.status === "PENDING" && booking.venueResponsePaused ? (
            <DetailRow label={t.myBookings.venueResponse} value={t.myBookings.responsePaused} />
          ) : null}
          {booking.status === "PENDING" && !booking.venueResponsePaused && booking.venueResponseExpiresAt ? (
            <DetailRow label={t.myBookings.venueResponse} value={t.myBookings.responseDueBy(formatDateTime(booking.venueResponseExpiresAt))} />
          ) : null}
          {booking.venue.phone ? <DetailRow label={t.myBookings.venuePhone} value={booking.venue.phone} /> : null}
          <Text style={styles.bookingId}>{`${t.myBookings.bookingId} ${booking.bookingId}`}</Text>
        </View>
        {booking.history.length ? (
          <View style={styles.card}>
            <Text style={styles.sectionTitle}>{t.myBookings.history}</Text>
            {booking.history.map((entry, index) => (
              <Text key={`${entry.at}-${index}`} style={styles.historyLine}>
                {`${formatDateTime(entry.at)} · ${entry.toStatus ? bookingStatusText(entry.toStatus) : entry.eventType ? bookingEventText(entry.eventType) : t.myBookings.bookingUpdate}`}
              </Text>
            ))}
          </View>
        ) : null}
        {booking.status === "CANCELLED" && booking.terminalReason === "CUSTOMER_CANCELLED" ? (
          <View style={styles.successBox}><Text style={styles.successText}>{t.myBookings.cancelled}</Text></View>
        ) : null}
        {canCancel ? (
          <Pressable accessibilityRole="button" onPress={() => setConfirming(true)} style={styles.dangerButton}>
            <Text style={styles.dangerButtonText}>{t.myBookings.cancel}</Text>
          </Pressable>
        ) : null}
      </ScrollView>
      <Modal transparent animationType="fade" visible={confirming} onRequestClose={() => setConfirming(false)}>
        <View style={styles.modalBackdrop}>
          <View style={styles.modalCard}>
            <Text style={styles.cardTitle}>{t.myBookings.cancelTitle}</Text>
            <Text style={styles.muted}>{t.myBookings.cancelBody}</Text>
            {cancelError ? <Text accessibilityRole="alert" style={styles.errorText}>{cancelError}</Text> : null}
            <Pressable disabled={cancelling} onPress={() => void cancel()} style={styles.dangerFilledButton}>
              <Text style={styles.dangerFilledText}>{cancelling ? t.myBookings.cancelling : cancelError ? t.myBookings.retryCancel : t.myBookings.confirmCancel}</Text>
            </Pressable>
            <Pressable disabled={cancelling} onPress={() => setConfirming(false)} style={styles.secondaryButton}>
              <Text style={styles.secondaryButtonText}>{t.myBookings.keep}</Text>
            </Pressable>
          </View>
        </View>
      </Modal>
    </View>
  );
}

function BackHeader({ title, onBack }: { title: string; onBack(): void }) {
  return <View style={styles.header}><Pressable accessibilityRole="button" onPress={onBack} style={styles.backButton}><Text style={styles.backText}>‹</Text></Pressable><Text accessibilityRole="header" style={styles.title}>{title}</Text></View>;
}
function StatusPill({ status }: { status: components["schemas"]["BookingStatus"] }) {
  const warning = status === "PENDING" || status === "AWAITING_CUSTOMER_CONFIRMATION";
  const success = status === "CONFIRMED" || status === "ACTIVE" || status === "COMPLETED";
  return <View style={[styles.pill, warning ? styles.pillWarning : success ? styles.pillSuccess : styles.pillDanger]}><Text style={[styles.pillText, warning ? styles.warningText : success ? styles.successText : styles.errorText]}>{bookingStatusText(status)}</Text></View>;
}
function DetailRow({ label, value, strong = false }: { label: string; value: string; strong?: boolean }) {
  return <View style={styles.detailRow}><Text style={styles.muted}>{label}</Text><Text style={strong ? styles.cardTitle : styles.body}>{value}</Text></View>;
}
function ErrorState({ message, retry }: { message: string; retry(): void }) {
  return <View style={styles.errorBox}><Text accessibilityRole="alert" style={styles.errorText}>{message}</Text><Pressable accessibilityRole="button" onPress={retry}><Text style={styles.link}>{t.retry}</Text></Pressable></View>;
}
function dedupe(items: BookingSummary[]) { return [...new Map(items.map((item) => [item.bookingId, item])).values()]; }
function resourceLabel(resource: BookingSummary["resource"] | BookingDetail["resource"]) { return [resource.name, resource.consoleType, resource.playMode ? t.discovery.playModes[resource.playMode] : undefined].filter(Boolean).join(" · "); }
function formatDate(value: string) { return new Intl.DateTimeFormat("en-EG", { weekday: "short", day: "numeric", month: "short", year: "numeric", timeZone: "Africa/Cairo" }).format(new Date(value)); }
function formatTime(value: string) { return new Intl.DateTimeFormat("en-EG", { hour: "numeric", minute: "2-digit", hour12: true, timeZone: "Africa/Cairo" }).format(new Date(value)); }
function formatTimeRange(start: string, end: string) { return `${formatTime(start)} – ${formatTime(end)}`; }
function formatDateTimeRange(start: string, end: string) { return `${formatDate(start)} · ${formatTimeRange(start, end)}`; }
function formatDateTime(value: string) { return `${formatDate(value)} · ${formatTime(value)}`; }
function createIdempotencyKey(): string {
  if (typeof globalThis.crypto?.randomUUID === "function") return globalThis.crypto.randomUUID();
  const bytes = new Uint8Array(16);
  if (typeof globalThis.crypto?.getRandomValues === "function") globalThis.crypto.getRandomValues(bytes);
  else for (let index = 0; index < bytes.length; index += 1) bytes[index] = Math.floor(Math.random() * 256);
  bytes[6] = (bytes[6]! & 0x0f) | 0x40;
  bytes[8] = (bytes[8]! & 0x3f) | 0x80;
  const hex = [...bytes].map((value) => value.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: nativeTokens.color.surface.raised },
  center: { flex: 1, alignItems: "center", justifyContent: "center", backgroundColor: nativeTokens.color.surface.raised },
  header: { minHeight: 72, flexDirection: "row", alignItems: "center", justifyContent: "space-between", borderBottomColor: nativeTokens.color.border.subtle, borderBottomWidth: nativeTokens.spacing.hairline, ...nativeLogicalSpacing.paddingInline(nativeTokens.spacing["5xl"]) },
  title: { color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.heading, fontWeight: nativeTokens.typography.fontWeight.extrabold },
  iconButton: { minHeight: 44, justifyContent: "center", padding: nativeTokens.spacing.md },
  iconText: { color: nativeTokens.color.brand.primary, fontWeight: nativeTokens.typography.fontWeight.bold },
  backButton: { width: 44, height: 44, alignItems: "center", justifyContent: "center", borderWidth: nativeTokens.spacing.hairline, borderColor: nativeTokens.color.border.customer, borderRadius: nativeTokens.radius["2xl"] },
  backText: { color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.displaySmall },
  tabs: { flexDirection: "row", padding: nativeTokens.spacing["3xl"], gap: nativeTokens.spacing.md },
  tab: { flex: 1, alignItems: "center", paddingVertical: nativeTokens.spacing.xl, borderBottomWidth: 2, borderBottomColor: nativeTokens.color.border.customer },
  tabSelected: { borderBottomColor: nativeTokens.color.brand.primary },
  tabText: { color: nativeTokens.color.text.muted, fontWeight: nativeTokens.typography.fontWeight.semibold },
  tabTextSelected: { color: nativeTokens.color.brand.primary },
  content: { gap: nativeTokens.spacing["3xl"], padding: nativeTokens.spacing["5xl"], paddingBottom: nativeTokens.spacing["10xl"] },
  card: { gap: nativeTokens.spacing.md, padding: nativeTokens.spacing["5xl"], borderWidth: nativeTokens.spacing.hairline, borderColor: nativeTokens.color.border.customer, borderRadius: nativeTokens.radius["4xl"], backgroundColor: nativeTokens.color.surface.raised, ...nativeTokens.shadow.card },
  rowBetween: { flexDirection: "row", alignItems: "center", justifyContent: "space-between", gap: nativeTokens.spacing.md },
  cardTitle: { color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.title, fontWeight: nativeTokens.typography.fontWeight.bold },
  sectionTitle: { color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.titleSmall, fontWeight: nativeTokens.typography.fontWeight.bold },
  body: { color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.bodyLarge },
  muted: { color: nativeTokens.color.text.muted, fontSize: nativeTokens.typography.fontSize.body },
  reason: { color: nativeTokens.color.feedback.danger, fontSize: nativeTokens.typography.fontSize.body, fontWeight: nativeTokens.typography.fontWeight.semibold },
  price: { color: nativeTokens.color.brand.primary, fontSize: nativeTokens.typography.fontSize.titleSmall, fontWeight: nativeTokens.typography.fontWeight.bold },
  pill: { borderRadius: nativeTokens.radius.round, paddingHorizontal: nativeTokens.spacing.xl, paddingVertical: nativeTokens.spacing.sm, maxWidth: "55%" },
  pillWarning: { backgroundColor: nativeTokens.color.feedback.warningSurface },
  pillSuccess: { backgroundColor: nativeTokens.color.feedback.successSurface },
  pillDanger: { backgroundColor: nativeTokens.color.feedback.dangerSurface },
  pillText: { fontSize: nativeTokens.typography.fontSize.caption, fontWeight: nativeTokens.typography.fontWeight.bold },
  warningText: { color: nativeTokens.color.feedback.warning },
  successText: { color: nativeTokens.color.feedback.success, fontWeight: nativeTokens.typography.fontWeight.bold },
  errorText: { color: nativeTokens.color.feedback.danger },
  errorBox: { gap: nativeTokens.spacing.md, padding: nativeTokens.spacing["5xl"], borderRadius: nativeTokens.radius["3xl"], backgroundColor: nativeTokens.color.feedback.dangerSurface },
  successBox: { padding: nativeTokens.spacing["4xl"], borderRadius: nativeTokens.radius["3xl"], backgroundColor: nativeTokens.color.feedback.successSurface },
  link: { color: nativeTokens.color.brand.primary, fontWeight: nativeTokens.typography.fontWeight.bold },
  empty: { alignItems: "center", gap: nativeTokens.spacing.md, paddingVertical: nativeTokens.spacing["10xl"] },
  emptyTitle: { color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.headingSmall, fontWeight: nativeTokens.typography.fontWeight.bold },
  secondaryButton: { minHeight: 48, alignItems: "center", justifyContent: "center", borderWidth: nativeTokens.spacing.hairline, borderColor: nativeTokens.color.border.customer, borderRadius: nativeTokens.radius["2xl"], paddingHorizontal: nativeTokens.spacing["4xl"] },
  secondaryButtonText: { color: nativeTokens.color.brand.primary, fontWeight: nativeTokens.typography.fontWeight.bold },
  dangerButton: { minHeight: 52, alignItems: "center", justifyContent: "center", borderWidth: nativeTokens.spacing.hairline, borderColor: nativeTokens.color.feedback.danger, borderRadius: nativeTokens.radius["3xl"] },
  dangerButtonText: { color: nativeTokens.color.feedback.danger, fontWeight: nativeTokens.typography.fontWeight.bold },
  dangerFilledButton: { minHeight: 52, alignItems: "center", justifyContent: "center", backgroundColor: nativeTokens.color.feedback.danger, borderRadius: nativeTokens.radius["3xl"], paddingHorizontal: nativeTokens.spacing["4xl"] },
  dangerFilledText: { color: nativeTokens.color.text.inverse, fontWeight: nativeTokens.typography.fontWeight.bold },
  detailRow: { flexDirection: "row", alignItems: "center", justifyContent: "space-between", gap: nativeTokens.spacing["3xl"], paddingVertical: nativeTokens.spacing.md, borderBottomWidth: nativeTokens.spacing.hairline, borderBottomColor: nativeTokens.color.border.subtle },
  bookingId: { color: nativeTokens.color.text.subtle, fontSize: nativeTokens.typography.fontSize.caption },
  historyLine: { color: nativeTokens.color.text.muted, paddingVertical: nativeTokens.spacing.sm },
  modalBackdrop: { flex: 1, justifyContent: "center", padding: nativeTokens.spacing["5xl"], backgroundColor: nativeTokens.color.surface.canvas },
  modalCard: { gap: nativeTokens.spacing["3xl"], padding: nativeTokens.spacing["6xl"], backgroundColor: nativeTokens.color.surface.raised, borderRadius: nativeTokens.radius["4xl"] },
});
