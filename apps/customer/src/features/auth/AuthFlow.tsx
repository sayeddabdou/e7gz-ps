import { useEffect, useState, type ComponentProps } from "react";
import { Pressable, StyleSheet, Text, TextInput, View } from "react-native";
import { nativeLogicalSpacing, nativeTokens } from "@e7gz/tokens/native";
import type { TokenStore } from "../../auth/tokenStore";
import { en as t } from "../../i18n/en";
import type { CustomerAuthApi } from "./api";
import { resendDeadline, secondsUntil, serverClockOffset } from "./api";
import { authErrorMessage } from "./errorMessages";

type Step = "login" | "register" | "otp";

export function AuthFlow({ api, tokenStore, onAuthenticated }: { api: CustomerAuthApi; tokenStore: TokenStore; onAuthenticated(): void }) {
  const [step, setStep] = useState<Step>("login");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const [form, setForm] = useState({ fullName: "", username: "", phone: "", email: "", password: "", code: "" });
  const [challengeId, setChallengeId] = useState("");
  const [deadline, setDeadline] = useState<string>();
  const [clockOffset, setClockOffset] = useState(0);
  const [remaining, setRemaining] = useState(0);

  useEffect(() => {
    if (!deadline) return undefined;
    const updateRemaining = () => setRemaining(secondsUntil(deadline, Date.now(), clockOffset));
    updateRemaining();
    const timer = setInterval(updateRemaining, 1_000);
    return () => clearInterval(timer);
  }, [clockOffset, deadline]);

  const startResendCountdown = (serverTime: string) => {
    const deviceNow = Date.now();
    setClockOffset(serverClockOffset(serverTime, deviceNow));
    setDeadline(resendDeadline(serverTime));
  };

  const update = (field: keyof typeof form) => (value: string) => setForm((current) => ({ ...current, [field]: value }));
  const finish = async (tokens: { accessToken: string; refreshToken: string }) => {
    await tokenStore.write(tokens);
    onAuthenticated();
  };
  const run = async (action: () => Promise<void>) => {
    setBusy(true);
    setError(undefined);
    try {
      await action();
    } catch {
      setError(t.auth.errors.unexpected);
    } finally {
      setBusy(false);
    }
  };

  const submitLogin = () => run(async () => {
    const result = await api.login({ usernameOrPhone: form.username, password: form.password });
    if (result.ok) await finish(result.value); else setError(authErrorMessage(result.code));
  });
  const submitRegistration = () => run(async () => {
    const result = await api.register({ fullName: form.fullName, username: form.username, phone: form.phone, email: form.email, password: form.password });
    if (result.ok) {
      setChallengeId(result.value.challengeId);
      startResendCountdown(result.serverTime);
      setStep("otp");
    } else setError(authErrorMessage(result.code));
  });
  const submitOtp = () => run(async () => {
    const result = await api.verifyPhone(challengeId, form.code);
    if (result.ok) await finish(result.value); else setError(authErrorMessage(result.code));
  });
  const resend = () => run(async () => {
    const result = await api.resend(form.phone);
    if (result.ok) {
      setChallengeId(result.value.challengeId);
      startResendCountdown(result.serverTime);
    } else setError(authErrorMessage(result.code));
  });

  const title = step === "login" ? t.auth.login.title : step === "register" ? t.auth.register.title : t.auth.otp.title;
  const submitLabel = step === "login" ? t.auth.login.submit : step === "register" ? t.auth.register.submit : t.auth.otp.submit;
  return (
    <View style={styles.screen}>
      <Text accessibilityRole="header" style={styles.title}>{title}</Text>
      {step === "register" ? (
        <>
          <Field label={t.auth.fields.fullName} value={form.fullName} onChangeText={update("fullName")} />
          <Field label={t.auth.fields.phone} value={form.phone} onChangeText={update("phone")} keyboardType="phone-pad" />
          <Field label={t.auth.fields.email} value={form.email} onChangeText={update("email")} keyboardType="email-address" />
        </>
      ) : null}
      {step !== "otp" ? (
        <>
          <Field label={t.auth.fields.username} value={form.username} onChangeText={update("username")} />
          <Field label={t.auth.fields.password} value={form.password} onChangeText={update("password")} secureTextEntry />
        </>
      ) : <Field label={t.auth.fields.code} value={form.code} onChangeText={update("code")} keyboardType="number-pad" />}
      {error ? <Text accessibilityRole="alert" style={styles.error}>{error}</Text> : null}
      <Pressable accessibilityRole="button" disabled={busy} onPress={step === "login" ? submitLogin : step === "register" ? submitRegistration : submitOtp} style={styles.primaryButton}>
        <Text style={styles.primaryButtonText}>{busy ? t.auth.working : submitLabel}</Text>
      </Pressable>
      {step === "otp" ? (
        <Pressable accessibilityRole="button" disabled={busy || remaining > 0} onPress={resend} style={styles.linkButton}>
          <Text style={styles.linkText}>{remaining > 0 ? t.auth.otp.resendCountdown.replace("{seconds}", String(remaining)) : t.auth.otp.resend}</Text>
        </Pressable>
      ) : (
        <Pressable accessibilityRole="button" onPress={() => { setError(undefined); setStep(step === "login" ? "register" : "login"); }} style={styles.linkButton}>
          <Text style={styles.linkText}>{step === "login" ? t.auth.login.createAccount : t.auth.register.haveAccount}</Text>
        </Pressable>
      )}
    </View>
  );
}

function Field({ label, ...props }: { label: string } & ComponentProps<typeof TextInput>) {
  return (
    <View style={styles.field}>
      <Text style={styles.label}>{label}</Text>
      <TextInput accessibilityLabel={label} autoCapitalize="none" placeholderTextColor={nativeTokens.color.text.subtle} style={styles.input} {...props} />
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: nativeTokens.color.surface.raised, justifyContent: "center", ...nativeLogicalSpacing.paddingInline(nativeTokens.spacing["3xl"]) },
  title: { color: nativeTokens.color.text.customer, fontSize: nativeTokens.typography.fontSize.heading, fontWeight: nativeTokens.typography.fontWeight.extrabold, marginBottom: nativeTokens.spacing["2xl"] },
  field: { gap: nativeTokens.spacing.xs, marginBottom: nativeTokens.spacing.md },
  label: { color: nativeTokens.color.text.subtle, fontSize: nativeTokens.typography.fontSize.caption, fontWeight: nativeTokens.typography.fontWeight.semibold },
  input: { backgroundColor: nativeTokens.color.surface.canvas, borderColor: nativeTokens.color.border.customer, borderRadius: nativeTokens.radius["2xl"], borderWidth: nativeTokens.spacing.hairline, color: nativeTokens.color.text.customer, minHeight: 52, paddingHorizontal: nativeTokens.spacing.lg },
  error: { color: nativeTokens.color.feedback.danger, marginBottom: nativeTokens.spacing.md },
  primaryButton: { alignItems: "center", backgroundColor: nativeTokens.color.brand.primary, borderRadius: nativeTokens.radius["3xl"], minHeight: 54, justifyContent: "center", marginTop: nativeTokens.spacing.sm },
  primaryButtonText: { color: nativeTokens.color.text.inverse, fontWeight: nativeTokens.typography.fontWeight.bold },
  linkButton: { alignItems: "center", padding: nativeTokens.spacing.lg },
  linkText: { color: nativeTokens.color.brand.primary, fontWeight: nativeTokens.typography.fontWeight.semibold },
});
