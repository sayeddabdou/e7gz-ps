import { useCallback, useEffect, useRef, useState } from "react";
import { ActivityIndicator, Pressable, ScrollView, StyleSheet, Text, View } from "react-native";
import type { components } from "@e7gz/api-client";
import { nativeLogicalSpacing, nativeTokens } from "@e7gz/tokens/native";
import { errorCodeOf, type ApiClient } from "../../api";
import { en as t } from "../../i18n/en";
import { discoveryErrorMessage } from "../discovery/errorMessages";
import { notificationMessage } from "./notificationMessages";

type Notification = components["schemas"]["CustomerNotification"];
type Page = { items: Notification[]; nextCursor: string | null };

export function NotificationInbox({ api, onBack, onOpenBooking }: {
  api: ApiClient;
  onBack(): void;
  onOpenBooking(bookingId: string): void;
}) {
  const [page, setPage] = useState<Page>();
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string>();
  const generationRef = useRef(0);

  const load = useCallback(async (append = false, cursor?: string) => {
    const generation = ++generationRef.current;
    if (append) setLoadingMore(true);
    else setLoading(true);
    setError(undefined);
    try {
      const { data, error: responseError } = await api.GET("/api/v1/customer/notifications", {
        params: { query: { limit: 20, ...(append && cursor ? { cursor } : {}) } },
      });
      if (generation !== generationRef.current) return;
      if (data) {
        setPage((previous) => append
          ? { items: dedupe([...(previous?.items ?? []), ...data.data.items]), nextCursor: data.data.nextCursor }
          : data.data);
      } else setError(discoveryErrorMessage(errorCodeOf(responseError)));
    } catch {
      if (generation === generationRef.current) setError(t.networkError);
    } finally {
      if (generation === generationRef.current) { setLoading(false); setLoadingMore(false); }
    }
  }, [api]);
  useEffect(() => { void Promise.resolve().then(() => load()); }, [load]);

  const open = async (item: Notification) => {
    if (!item.readAt) {
      setPage((current) => current ? { ...current, items: current.items.map((row) => row.id === item.id ? { ...row, readAt: new Date().toISOString() } : row) } : current);
      try {
        await api.POST("/api/v1/customer/notifications/{notificationId}/read", {
          params: { path: { notificationId: item.id } },
        });
      } catch {
        // Reading is best effort; a later refresh restores the server state.
      }
    }
    if (item.bookingId) onOpenBooking(item.bookingId);
  };

  return (
    <View style={styles.screen}>
      <View style={styles.header}>
        <Pressable accessibilityRole="button" onPress={onBack} style={styles.backButton}><Text style={styles.backText}>‹</Text></Pressable>
        <Text accessibilityRole="header" style={styles.title}>{t.notifications.title}</Text>
      </View>
      <ScrollView contentContainerStyle={styles.content}>
        {loading ? <ActivityIndicator accessibilityLabel={t.loading} color={nativeTokens.color.brand.primary} /> : null}
        {!loading && error ? (
          <View style={styles.errorBox}><Text accessibilityRole="alert" style={styles.errorText}>{error}</Text><Pressable onPress={() => void load()}><Text style={styles.link}>{t.retry}</Text></Pressable></View>
        ) : null}
        {!loading && !error && page?.items.length === 0 ? (
          <View style={styles.empty}><Text style={styles.emptyTitle}>{t.notifications.empty}</Text><Text style={styles.muted}>{t.notifications.emptyBody}</Text></View>
        ) : null}
        {!loading && !error ? page?.items.map((item) => (
          <Pressable accessibilityRole="button" key={item.id} onPress={() => void open(item)} style={[styles.card, !item.readAt && styles.unreadCard]}>
            <View style={styles.row}>
              <Text style={styles.message}>{notificationMessage(item.code, item.params)}</Text>
              {!item.readAt ? <Text style={styles.unread}>{t.notifications.unread}</Text> : null}
            </View>
            <Text style={styles.muted}>{formatDateTime(item.createdAt)}</Text>
          </Pressable>
        )) : null}
        {!loading && !error && page?.nextCursor ? (
          <Pressable disabled={loadingMore} onPress={() => void load(true, page.nextCursor ?? undefined)} style={styles.button}><Text style={styles.link}>{loadingMore ? t.loading : t.notifications.loadMore}</Text></Pressable>
        ) : null}
      </ScrollView>
    </View>
  );
}

function dedupe(items: Notification[]) { return [...new Map(items.map((item) => [item.id, item])).values()]; }
function formatDateTime(value: string) {
  return new Intl.DateTimeFormat("en-EG", { day: "numeric", month: "short", hour: "numeric", minute: "2-digit", hour12: true, timeZone: "Africa/Cairo" }).format(new Date(value));
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: nativeTokens.color.surface.raised },
  header: { minHeight: 72, flexDirection: "row", alignItems: "center", gap: nativeTokens.spacing["3xl"], borderBottomColor: nativeTokens.color.border.subtle, borderBottomWidth: nativeTokens.spacing.hairline, ...nativeLogicalSpacing.paddingInline(nativeTokens.spacing["5xl"]) },
  backButton: { width: 44, height: 44, alignItems: "center", justifyContent: "center", borderWidth: nativeTokens.spacing.hairline, borderColor: nativeTokens.color.border.customer, borderRadius: nativeTokens.radius["2xl"] },
  backText: { color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.displaySmall },
  title: { color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.heading, fontWeight: nativeTokens.typography.fontWeight.extrabold },
  content: { gap: nativeTokens.spacing["3xl"], padding: nativeTokens.spacing["5xl"], paddingBottom: nativeTokens.spacing["10xl"] },
  card: { gap: nativeTokens.spacing.md, padding: nativeTokens.spacing["4xl"], borderWidth: nativeTokens.spacing.hairline, borderColor: nativeTokens.color.border.customer, borderRadius: nativeTokens.radius["3xl"], backgroundColor: nativeTokens.color.surface.raised },
  unreadCard: { backgroundColor: nativeTokens.color.surface.info, borderColor: nativeTokens.color.brand.primary },
  row: { flexDirection: "row", alignItems: "flex-start", justifyContent: "space-between", gap: nativeTokens.spacing.md },
  message: { flex: 1, color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.bodyLarge, fontWeight: nativeTokens.typography.fontWeight.semibold },
  unread: { color: nativeTokens.color.brand.primary, fontSize: nativeTokens.typography.fontSize.caption, fontWeight: nativeTokens.typography.fontWeight.bold },
  muted: { color: nativeTokens.color.text.muted, fontSize: nativeTokens.typography.fontSize.bodySmall },
  empty: { alignItems: "center", gap: nativeTokens.spacing.md, paddingVertical: nativeTokens.spacing["10xl"] },
  emptyTitle: { color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.headingSmall, fontWeight: nativeTokens.typography.fontWeight.bold },
  errorBox: { gap: nativeTokens.spacing.md, padding: nativeTokens.spacing["5xl"], borderRadius: nativeTokens.radius["3xl"], backgroundColor: nativeTokens.color.feedback.dangerSurface },
  errorText: { color: nativeTokens.color.feedback.danger },
  link: { color: nativeTokens.color.brand.primary, fontWeight: nativeTokens.typography.fontWeight.bold },
  button: { minHeight: 48, alignItems: "center", justifyContent: "center", borderWidth: nativeTokens.spacing.hairline, borderColor: nativeTokens.color.border.customer, borderRadius: nativeTokens.radius["2xl"] },
});
