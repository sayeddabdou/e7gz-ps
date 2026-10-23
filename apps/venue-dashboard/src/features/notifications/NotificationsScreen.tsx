import { useCallback, useEffect, useState, type CSSProperties } from "react";
import type { components } from "@e7gz/api-client";
import { webTokens } from "@e7gz/tokens/web";
import { apiErrorOf, type ApiClient } from "../../api";
import { useMessages } from "../../i18n";

type Notification = components["schemas"]["VenueNotification"];

const styles = {
  page: { display: "grid", gap: webTokens.spacing["3xl"] },
  heading: { fontSize: webTokens.typography.fontSize.displaySmall, marginBlock: 0 },
  muted: { color: webTokens.color.text.venueMuted, marginBlock: 0 },
  list: { display: "grid", gap: webTokens.spacing.lg, listStyle: "none", marginBlock: 0, paddingInlineStart: 0 },
  card: { backgroundColor: webTokens.color.surface.raised, border: `${webTokens.spacing.hairline}px solid ${webTokens.color.border.subtle}`, borderRadius: webTokens.radius.xl, display: "grid", gap: webTokens.spacing.md, padding: webTokens.spacing.xl },
  unread: { borderInlineStart: `${webTokens.spacing.sm}px solid ${webTokens.color.brand.venue}` },
  row: { alignItems: "center", display: "flex", flexWrap: "wrap", gap: webTokens.spacing.md, justifyContent: "space-between" },
  actions: { display: "flex", flexWrap: "wrap", gap: webTokens.spacing.md },
  button: { backgroundColor: webTokens.color.surface.raised, border: `${webTokens.spacing.hairline}px solid ${webTokens.color.border.venue}`, borderRadius: webTokens.radius.lg, color: webTokens.color.brand.venue, cursor: "pointer", fontWeight: webTokens.typography.fontWeight.bold, paddingBlock: webTokens.spacing.md, paddingInline: webTokens.spacing.xl },
  error: { backgroundColor: webTokens.color.feedback.dangerSurface, borderRadius: webTokens.radius.lg, color: webTokens.color.feedback.danger, padding: webTokens.spacing.lg },
} satisfies Record<string, CSSProperties>;

export function NotificationsScreen({ api, onOpenBooking }: { api: ApiClient; onOpenBooking: (bookingId: string) => void }) {
  const t = useMessages().notifications;
  const [items, setItems] = useState<Notification[]>();
  const [nextCursor, setNextCursor] = useState<string>();
  const [error, setError] = useState<string>();
  const [loadingMore, setLoadingMore] = useState(false);
  const [markingRead, setMarkingRead] = useState<string>();

  const messageFor = useCallback((value: unknown) => {
    const code = apiErrorOf(value).code;
    return code && code in t.errors ? t.errors[code as keyof typeof t.errors] : t.errors.unexpected;
  }, [t]);

  const load = useCallback(async (cursor?: string) => {
    try {
      const result = await api.GET("/api/v1/venue/notifications", { params: { query: { limit: 50, ...(cursor ? { cursor } : {}) } } });
      if (!result.data) return setError(messageFor(result.error));
      setItems((current) => cursor ? [...(current ?? []), ...result.data!.data.items] : result.data!.data.items);
      setNextCursor(result.data.data.nextCursor ?? undefined);
      setError(undefined);
    } catch {
      setError(t.errors.network);
    } finally {
      setLoadingMore(false);
    }
  }, [api, messageFor, t]);

  useEffect(() => {
    const timeout = window.setTimeout(() => { void load(); }, 0);
    return () => window.clearTimeout(timeout);
  }, [load]);

  const markRead = async (notification: Notification) => {
    if (notification.readAt || markingRead) return;
    setMarkingRead(notification.id);
    setError(undefined);
    try {
      const result = await api.POST("/api/v1/venue/notifications/{notificationId}/read", { params: { path: { notificationId: notification.id } } });
      if (!result.response.ok) return setError(messageFor(result.error));
      setItems((current) => current?.map((item) => item.id === notification.id ? { ...item, readAt: new Date().toISOString() } : item));
    } catch {
      setError(t.errors.network);
    } finally {
      setMarkingRead(undefined);
    }
  };

  return <section style={styles.page}>
    <header><h2 style={styles.heading}>{t.title}</h2><p style={styles.muted}>{t.description}</p></header>
    {error ? <p role="alert" style={styles.error}>{error}</p> : null}
    {!items ? error ? <button onClick={() => void load()} style={styles.button} type="button">{t.retry}</button> : <p role="status">{t.loading}</p>
      : items.length === 0 ? <p>{t.empty}</p>
        : <><ul style={styles.list}>{items.map((notification) => {
          const text = notification.code in t.codes ? t.codes[notification.code as keyof typeof t.codes] : t.unknown;
          return <li key={notification.id} style={{ ...styles.card, ...(notification.readAt ? undefined : styles.unread) }}>
            <div style={styles.row}><strong>{text}</strong><span>{notification.readAt ? t.read : t.unread}</span></div>
            <time dateTime={notification.createdAt} style={styles.muted}>{new Date(notification.createdAt).toLocaleString(t.locale, { timeZone: "Africa/Cairo" })}</time>
            <div style={styles.actions}>{notification.bookingId ? <button onClick={() => onOpenBooking(notification.bookingId!)} style={styles.button} type="button">{t.openBooking}</button> : null}{!notification.readAt ? <button disabled={Boolean(markingRead)} onClick={() => void markRead(notification)} style={styles.button} type="button">{markingRead === notification.id ? t.markingRead : t.markRead}</button> : null}</div>
          </li>;
        })}</ul>{nextCursor ? <button disabled={loadingMore} onClick={() => { setLoadingMore(true); void load(nextCursor); }} style={styles.button} type="button">{loadingMore ? t.loadingMore : t.loadMore}</button> : null}</>}
  </section>;
}
