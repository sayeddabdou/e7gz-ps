import { useCallback, useEffect, useRef, useState } from "react";
import { ActivityIndicator, Pressable, StyleSheet, Text, View } from "react-native";
import type { components } from "@e7gz/api-client";
import { nativeTokens } from "@e7gz/tokens/native";
import { errorCodeOf, type ApiClient } from "../../api";
import { countdownSeconds, countdownSecondsAt, formatCountdown, serverClockOffset } from "../booking/time";
import { en as t } from "../../i18n/en";
import { lateConfirmErrorCodes, lateConfirmErrorMessage } from "./errorMessages";

type Booking = components["schemas"]["CustomerBookingDetail"];
type Action = "accept" | "decline";

export function LateConfirmPanel({
  api,
  booking,
  serverTime,
  onReload,
  idempotencyKeyFactory = createIdempotencyKey,
  pollIntervalMs = 15_000,
}: {
  api: ApiClient;
  booking: Booking;
  serverTime: string;
  onReload(): Promise<void>;
  idempotencyKeyFactory?: () => string;
  pollIntervalMs?: number;
}) {
  const lateConfirm = booking.lateConfirm;
  const [remaining, setRemaining] = useState(() => lateConfirm ? countdownSeconds(lateConfirm.expiresAt, serverTime) : 0);
  const [busy, setBusy] = useState<Action>();
  const [actionError, setActionError] = useState<string>();
  const inFlightRef = useRef(false);
  const keysRef = useRef<Partial<Record<Action, string>>>({});
  const reloadInFlightRef = useRef(false);
  const expiredReloadedForRef = useRef<string | undefined>(undefined);

  const reload = useCallback(async () => {
    if (reloadInFlightRef.current) return;
    reloadInFlightRef.current = true;
    try {
      await onReload();
    } catch {
      // Keep the visible deadline useful; the next poll can recover.
    } finally {
      reloadInFlightRef.current = false;
    }
  }, [onReload]);

  useEffect(() => {
    if (!lateConfirm || lateConfirm.paused) return undefined;
    const offset = serverClockOffset(serverTime, Date.now());
    const update = () => setRemaining(countdownSecondsAt(lateConfirm.expiresAt, Date.now() + offset));
    update();
    const interval = setInterval(update, 1_000);
    return () => clearInterval(interval);
  }, [lateConfirm, serverTime]);

  useEffect(() => {
    if (!lateConfirm || booking.status !== "AWAITING_CUSTOMER_CONFIRMATION") return undefined;
    const interval = setInterval(() => void reload(), pollIntervalMs);
    return () => clearInterval(interval);
  }, [booking.status, lateConfirm, pollIntervalMs, reload]);

  useEffect(() => {
    if (!lateConfirm || booking.status !== "AWAITING_CUSTOMER_CONFIRMATION" || lateConfirm.paused || remaining > 0) return;
    if (expiredReloadedForRef.current === lateConfirm.expiresAt) return;
    expiredReloadedForRef.current = lateConfirm.expiresAt;
    void reload();
  }, [booking.status, lateConfirm, reload, remaining]);

  if (!lateConfirm || booking.status !== "AWAITING_CUSTOMER_CONFIRMATION") return null;

  const expired = !lateConfirm.paused && remaining <= 0;
  const runAction = async (action: Action) => {
    if (inFlightRef.current || expired || (action === "accept" && lateConfirm.paused)) return;
    inFlightRef.current = true;
    setBusy(action);
    setActionError(undefined);
    try {
      const key = keysRef.current[action] ?? idempotencyKeyFactory();
      keysRef.current[action] = key;
      const path = action === "accept"
        ? "/api/v1/customer/bookings/{bookingId}/late-confirm/accept" as const
        : "/api/v1/customer/bookings/{bookingId}/late-confirm/decline" as const;
      const { data, error } = await api.POST(path, {
        params: { path: { bookingId: booking.bookingId }, header: { "Idempotency-Key": key } },
      });
      if (data) {
        await onReload();
        return;
      }
      const code = errorCodeOf(error);
      // A declared API error completes this attempt, so a later press is a fresh attempt.
      // A response without a declared code is ambiguous (for example a 500), so retain the
      // key and let ADR-015 replay the original attempt rather than repeating it.
      if ((lateConfirmErrorCodes as readonly string[]).includes(code ?? "")) delete keysRef.current[action];
      if ([
        "BOOKING_STATE_CHANGED",
        "DEADLINE_PASSED",
        "LATE_CONFIRM_CUSTOMER_CONFLICT",
        "LATE_CONFIRM_RESOURCE_CONFLICT",
        "VENUE_SELLING_BLOCK_ACTIVE",
        "RESOURCE_SELLING_BLOCK_ACTIVE",
      ].includes(code ?? "")) await onReload();
      setActionError(lateConfirmErrorMessage(code));
    } catch {
      setActionError(t.lateConfirm.errors.network);
    } finally {
      inFlightRef.current = false;
      setBusy(undefined);
    }
  };

  return (
    <View style={styles.wrapper}>
      <View style={lateConfirm.paused ? styles.pausedBox : expired ? styles.expiredBox : styles.timerBox}>
        <Text style={lateConfirm.paused ? styles.pausedTitle : expired ? styles.errorTitle : styles.timerTitle}>
          {lateConfirm.paused ? t.lateConfirm.paused : expired ? t.lateConfirm.expired : t.lateConfirm.awaiting}
        </Text>
        {!lateConfirm.paused && !expired ? <Text style={styles.countdown}>{formatCountdown(remaining)}</Text> : null}
        {!expired ? <Text style={styles.help}>{lateConfirm.paused ? t.lateConfirm.pausedHelp : t.lateConfirm.prompt(booking.venue.name)}</Text> : null}
      </View>
      {actionError ? <Text accessibilityRole="alert" style={styles.actionError}>{actionError}</Text> : null}
      <Pressable
        accessibilityRole="button"
        accessibilityState={{ disabled: Boolean(busy) || expired || lateConfirm.paused }}
        disabled={Boolean(busy) || expired || lateConfirm.paused}
        onPress={() => void runAction("accept")}
        style={[styles.acceptButton, (Boolean(busy) || expired || lateConfirm.paused) && styles.disabledButton]}
      >
        {busy === "accept" ? <ActivityIndicator color={nativeTokens.color.text.inverse} /> : <Text style={styles.acceptText}>{t.lateConfirm.accept}</Text>}
      </Pressable>
      <Pressable
        accessibilityRole="button"
        accessibilityState={{ disabled: Boolean(busy) || expired }}
        disabled={Boolean(busy) || expired}
        onPress={() => void runAction("decline")}
        style={[styles.declineButton, (Boolean(busy) || expired) && styles.disabledButton]}
      >
        {busy === "decline" ? <ActivityIndicator color={nativeTokens.color.feedback.danger} /> : <Text style={styles.declineText}>{t.lateConfirm.decline}</Text>}
      </Pressable>
    </View>
  );
}

function createIdempotencyKey(): string {
  if (typeof globalThis.crypto?.randomUUID === "function") return globalThis.crypto.randomUUID();
  const bytes = new Uint8Array(16);
  if (typeof globalThis.crypto?.getRandomValues !== "function") throw new Error("Secure randomness is unavailable");
  globalThis.crypto.getRandomValues(bytes);
  bytes[6] = (bytes[6]! & 0x0f) | 0x40;
  bytes[8] = (bytes[8]! & 0x3f) | 0x80;
  const hex = [...bytes].map((value) => value.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

const styles = StyleSheet.create({
  wrapper: { gap: nativeTokens.spacing.lg },
  timerBox: { alignItems: "center", backgroundColor: nativeTokens.color.feedback.warningSurface, borderRadius: nativeTokens.radius["4xl"], gap: nativeTokens.spacing.md, padding: nativeTokens.spacing["4xl"] },
  pausedBox: { backgroundColor: nativeTokens.color.surface.info, borderRadius: nativeTokens.radius["4xl"], gap: nativeTokens.spacing.md, padding: nativeTokens.spacing["4xl"] },
  expiredBox: { backgroundColor: nativeTokens.color.feedback.dangerSurface, borderRadius: nativeTokens.radius["4xl"], padding: nativeTokens.spacing["4xl"] },
  timerTitle: { color: nativeTokens.color.feedback.warning, fontWeight: nativeTokens.typography.fontWeight.bold },
  pausedTitle: { color: nativeTokens.color.brand.primary, fontWeight: nativeTokens.typography.fontWeight.bold },
  errorTitle: { color: nativeTokens.color.feedback.danger, fontWeight: nativeTokens.typography.fontWeight.bold },
  countdown: { color: nativeTokens.color.feedback.warning, fontSize: nativeTokens.typography.fontSize.displaySmall, fontWeight: nativeTokens.typography.fontWeight.extrabold },
  help: { color: nativeTokens.color.text.muted, textAlign: "center" },
  actionError: { backgroundColor: nativeTokens.color.feedback.dangerSurface, borderRadius: nativeTokens.radius["2xl"], color: nativeTokens.color.feedback.danger, padding: nativeTokens.spacing["3xl"] },
  acceptButton: { alignItems: "center", backgroundColor: nativeTokens.color.brand.primary, borderRadius: nativeTokens.radius["3xl"], justifyContent: "center", minHeight: 54, ...nativeTokens.shadow.action },
  acceptText: { color: nativeTokens.color.text.inverse, fontWeight: nativeTokens.typography.fontWeight.bold },
  declineButton: { alignItems: "center", borderColor: nativeTokens.color.feedback.danger, borderRadius: nativeTokens.radius["3xl"], borderWidth: nativeTokens.spacing.hairline, justifyContent: "center", minHeight: 52 },
  declineText: { color: nativeTokens.color.feedback.danger, fontWeight: nativeTokens.typography.fontWeight.bold },
  disabledButton: { opacity: 0.5, shadowOpacity: 0 },
});
