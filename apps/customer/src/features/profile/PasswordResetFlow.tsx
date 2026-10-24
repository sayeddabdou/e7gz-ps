import { useState } from "react";
import { KeyboardAvoidingView, Platform, ScrollView, Text, View } from "react-native";
import { en as t } from "../../i18n/en";
import type { CustomerProfileApi } from "./api";
import { profileErrorMessage } from "./errorMessages";
import { Field, Notice, PrimaryButton, SecondaryButton, profileStyles as styles } from "./ui";

export function PasswordResetFlow({
  api,
  initialPhone = "",
  cancelLabel = t.profile.passwordReset.backToLogin,
  onCancel,
  onComplete,
}: {
  api: CustomerProfileApi;
  initialPhone?: string;
  cancelLabel?: string;
  onCancel(): void;
  onComplete(): void | Promise<void>;
}) {
  const [step, setStep] = useState<"phone" | "code">("phone");
  const [phone, setPhone] = useState(initialPhone);
  const [challengeId, setChallengeId] = useState("");
  const [code, setCode] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const [success, setSuccess] = useState<string>();

  const run = async (action: () => Promise<void>) => {
    setBusy(true);
    setError(undefined);
    setSuccess(undefined);
    try {
      await action();
    } catch {
      setError(t.profile.errors.unexpected);
    } finally {
      setBusy(false);
    }
  };

  const requestCode = (resend = false) => run(async () => {
    const result = await api.requestPasswordReset(phone);
    if (!result.ok) {
      setError(profileErrorMessage(result.code));
      return;
    }
    setChallengeId(result.value.challengeId);
    setCode("");
    setStep("code");
    if (resend) setSuccess(t.profile.verificationCodeSent);
  });

  const complete = () => run(async () => {
    const result = await api.completePasswordReset(challengeId, code, password);
    if (!result.ok) {
      setError(profileErrorMessage(result.code));
      return;
    }
    await onComplete();
  });

  return (
    <KeyboardAvoidingView behavior={Platform.OS === "ios" ? "padding" : undefined} style={styles.screen}>
      <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
        <Text accessibilityRole="header" style={styles.title}>
          {step === "phone" ? t.profile.passwordReset.title : t.profile.passwordReset.codeTitle}
        </Text>
        <Text style={styles.subtitle}>
          {step === "phone" ? t.profile.passwordReset.help : t.profile.passwordReset.codeHelp(phone)}
        </Text>
        <View style={styles.card}>
          {step === "phone" ? (
            <Field label={t.profile.phone} value={phone} onChangeText={setPhone} keyboardType="phone-pad" />
          ) : (
            <>
              <Field label={t.profile.verificationCode} value={code} onChangeText={setCode} keyboardType="number-pad" />
              <Field label={t.profile.passwordReset.newPassword} value={password} onChangeText={setPassword} secureTextEntry />
            </>
          )}
          {success ? <Notice kind="success">{success}</Notice> : null}
          {error ? <Notice kind="error">{error}</Notice> : null}
          <PrimaryButton
            busy={busy}
            label={busy ? t.profile.working : step === "phone" ? t.profile.passwordReset.request : t.profile.passwordReset.complete}
            onPress={step === "phone" ? () => requestCode() : complete}
          />
          {step === "code" ? (
            <SecondaryButton disabled={busy} label={t.profile.passwordReset.resend} onPress={() => requestCode(true)} />
          ) : null}
          <SecondaryButton disabled={busy} label={cancelLabel} onPress={onCancel} />
        </View>
      </ScrollView>
    </KeyboardAvoidingView>
  );
}
