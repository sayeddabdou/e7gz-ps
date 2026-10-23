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
  sectionTitle: { color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.title, fontWeight: nativeTokens.typography.fontWeight.bold },
  valueLabel: { color: nativeTokens.color.text.muted, fontSize: nativeTokens.typography.fontSize.label, fontWeight: nativeTokens.typography.fontWeight.semibold },
  value: { color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.bodyLarge },
  valueRow: { gap: nativeTokens.spacing.xs },
  actions: { gap: nativeTokens.spacing.md },
  dangerButton: { alignItems: "center", borderColor: nativeTokens.color.feedback.danger, borderRadius: nativeTokens.radius["2xl"], borderWidth: nativeTokens.spacing.hairline, justifyContent: "center", minHeight: 48 },
  dangerText: { color: nativeTokens.color.feedback.danger, fontWeight: nativeTokens.typography.fontWeight.bold },
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
  infoNotice: { backgroundColor: nativeTokens.color.feedback.warningSurface },
  errorText: { color: nativeTokens.color.feedback.danger, fontSize: nativeTokens.typography.fontSize.bodySmall },
  noticeText: { color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.bodySmall },
});
