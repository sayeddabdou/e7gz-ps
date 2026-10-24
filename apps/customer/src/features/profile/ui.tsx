import type { ComponentProps, ReactNode } from "react";
import { Pressable, StyleSheet, Text, TextInput, View } from "react-native";
import { nativeTokens } from "@e7gz/tokens/native";

export function Field({ label, ...props }: { label: string } & ComponentProps<typeof TextInput>) {
  return (
    <View style={styles.field}>
      <Text style={styles.label}>{label}</Text>
      <TextInput
        accessibilityLabel={label}
        autoCapitalize="none"
        placeholderTextColor={nativeTokens.color.text.subtle}
        style={styles.input}
        {...props}
      />
    </View>
  );
}

export function PrimaryButton({ label, busy, onPress }: { label: string; busy?: boolean; onPress(): void }) {
  return (
    <Pressable accessibilityRole="button" disabled={busy} onPress={onPress} style={({ pressed }) => [styles.primaryButton, pressed && styles.pressed, busy && styles.disabled]}>
      <Text style={styles.primaryButtonText}>{label}</Text>
    </Pressable>
  );
}

export function SecondaryButton({ label, disabled, onPress }: { label: string; disabled?: boolean; onPress(): void }) {
  return (
    <Pressable accessibilityRole="button" disabled={disabled} onPress={onPress} style={({ pressed }) => [styles.secondaryButton, pressed && styles.pressed, disabled && styles.disabled]}>
      <Text style={styles.secondaryButtonText}>{label}</Text>
    </Pressable>
  );
}

export function Notice({ kind, children }: { kind: "error" | "success" | "info"; children: ReactNode }) {
  return (
    <View accessibilityRole={kind === "error" ? "alert" : undefined} style={[styles.notice, kind === "error" ? styles.errorNotice : kind === "success" ? styles.successNotice : styles.infoNotice]}>
      <Text style={kind === "error" ? styles.errorText : styles.noticeText}>{children}</Text>
    </View>
  );
}

export const profileStyles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: nativeTokens.color.surface.canvas },
  content: { gap: nativeTokens.spacing["3xl"], padding: nativeTokens.spacing["4xl"], paddingBottom: nativeTokens.spacing["8xl"] },
  title: { color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.displaySmall, fontWeight: nativeTokens.typography.fontWeight.extrabold },
  subtitle: { color: nativeTokens.color.text.muted, fontSize: nativeTokens.typography.fontSize.body },
  card: { backgroundColor: nativeTokens.color.surface.raised, borderColor: nativeTokens.color.border.subtle, borderRadius: nativeTokens.radius["3xl"], borderWidth: nativeTokens.spacing.hairline, gap: nativeTokens.spacing["2xl"], padding: nativeTokens.spacing["3xl"] },
  identityCard: { alignItems: "center", backgroundColor: nativeTokens.color.surface.raised, borderColor: nativeTokens.color.border.customer, borderRadius: nativeTokens.radius["5xl"], borderWidth: nativeTokens.spacing.hairline, flexDirection: "row", gap: nativeTokens.spacing["2xl"], padding: nativeTokens.spacing["3xl"] },
  avatar: { alignItems: "center", backgroundColor: nativeTokens.color.surface.info, borderRadius: nativeTokens.radius["5xl"], height: nativeTokens.spacing["11xl"], justifyContent: "center", width: nativeTokens.spacing["11xl"] },
  avatarText: { color: nativeTokens.color.brand.primary, fontSize: nativeTokens.typography.fontSize.title, fontWeight: nativeTokens.typography.fontWeight.extrabold },
  identityDetails: { flex: 1, minWidth: 0 },
  identityName: { color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.titleSmall, fontWeight: nativeTokens.typography.fontWeight.bold, marginBottom: nativeTokens.spacing.xs },
  contactLine: { alignItems: "center", flexDirection: "row", gap: nativeTokens.spacing.sm },
  contactValue: { color: nativeTokens.color.text.muted, flexShrink: 1, fontSize: nativeTokens.typography.fontSize.label },
  statusBadge: { borderRadius: nativeTokens.radius.lg, paddingHorizontal: nativeTokens.spacing.sm, paddingVertical: nativeTokens.spacing.xxs },
  verifiedBadge: { backgroundColor: nativeTokens.color.feedback.successSurface },
  unverifiedBadge: { backgroundColor: nativeTokens.color.feedback.warningSurface },
  statusText: { fontSize: nativeTokens.typography.fontSize.caption, fontWeight: nativeTokens.typography.fontWeight.bold },
  verifiedText: { color: nativeTokens.color.feedback.success },
  unverifiedText: { color: nativeTokens.color.feedback.warning },
  memberSince: { color: nativeTokens.color.text.subtle, fontSize: nativeTokens.typography.fontSize.caption, marginTop: nativeTokens.spacing.xs },
  editChip: { borderColor: nativeTokens.color.border.customer, borderRadius: nativeTokens.radius.xl, borderWidth: nativeTokens.spacing.hairline, paddingHorizontal: nativeTokens.spacing["2xl"], paddingVertical: nativeTokens.spacing.lg },
  editChipText: { color: nativeTokens.color.brand.primary, fontSize: nativeTokens.typography.fontSize.label, fontWeight: nativeTokens.typography.fontWeight.bold },
  menu: { backgroundColor: nativeTokens.color.surface.raised, borderColor: nativeTokens.color.border.customer, borderRadius: nativeTokens.radius["5xl"], borderWidth: nativeTokens.spacing.hairline, overflow: "hidden" },
  actionRow: { alignItems: "center", borderBottomColor: nativeTokens.color.border.subtle, borderBottomWidth: nativeTokens.spacing.hairline, flexDirection: "row", gap: nativeTokens.spacing.md, minHeight: nativeTokens.spacing["11xl"], paddingHorizontal: nativeTokens.spacing["3xl"], paddingVertical: nativeTokens.spacing.xl },
  actionContent: { flex: 1 },
  actionLabel: { color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.bodyLarge, fontWeight: nativeTokens.typography.fontWeight.semibold },
  actionHint: { color: nativeTokens.color.text.muted, fontSize: nativeTokens.typography.fontSize.caption, marginTop: nativeTokens.spacing.xs },
  actionValue: { color: nativeTokens.color.text.subtle, flexShrink: 1, fontSize: nativeTokens.typography.fontSize.bodySmall, maxWidth: "35%" },
  logoutRow: { justifyContent: "center", minHeight: nativeTokens.spacing["11xl"], paddingHorizontal: nativeTokens.spacing["3xl"], paddingVertical: nativeTokens.spacing.xl },
  dangerText: { color: nativeTokens.color.feedback.danger, fontWeight: nativeTokens.typography.fontWeight.bold },
  pressed: { opacity: 0.75 },
});

const styles = StyleSheet.create({
  field: { gap: nativeTokens.spacing.sm },
  label: { color: nativeTokens.color.text.muted, fontSize: nativeTokens.typography.fontSize.label, fontWeight: nativeTokens.typography.fontWeight.semibold },
  input: { backgroundColor: nativeTokens.color.surface.canvas, borderColor: nativeTokens.color.border.customer, borderRadius: nativeTokens.radius["2xl"], borderWidth: nativeTokens.spacing.hairline, color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.bodyLarge, minHeight: 52, paddingHorizontal: nativeTokens.spacing["3xl"] },
  primaryButton: { alignItems: "center", backgroundColor: nativeTokens.color.brand.primary, borderRadius: nativeTokens.radius["3xl"], justifyContent: "center", minHeight: 52, ...nativeTokens.shadow.action },
  primaryButtonText: { color: nativeTokens.color.text.inverse, fontWeight: nativeTokens.typography.fontWeight.bold },
  secondaryButton: { alignItems: "center", backgroundColor: nativeTokens.color.surface.raised, borderColor: nativeTokens.color.border.customer, borderRadius: nativeTokens.radius["2xl"], borderWidth: nativeTokens.spacing.hairline, justifyContent: "center", minHeight: 48 },
  secondaryButtonText: { color: nativeTokens.color.brand.primary, fontWeight: nativeTokens.typography.fontWeight.bold },
  pressed: { opacity: 0.75 },
  disabled: { opacity: 0.55 },
  notice: { borderRadius: nativeTokens.radius["2xl"], padding: nativeTokens.spacing["2xl"] },
  errorNotice: { backgroundColor: nativeTokens.color.feedback.dangerSurface },
  successNotice: { backgroundColor: nativeTokens.color.feedback.successSurface },
  infoNotice: { backgroundColor: nativeTokens.color.feedback.infoSurface },
  errorText: { color: nativeTokens.color.feedback.danger, fontSize: nativeTokens.typography.fontSize.bodySmall },
  noticeText: { color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.bodySmall },
});
