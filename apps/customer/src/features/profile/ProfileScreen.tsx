import { useEffect, useState } from "react";
import { Pressable, RefreshControl, ScrollView, Text, View } from "react-native";
import { en as t } from "../../i18n/en";
import type { CustomerProfile, CustomerProfileApi } from "./api";
import { profileErrorMessage } from "./errorMessages";
import { PasswordResetFlow } from "./PasswordResetFlow";
import { Field, Notice, PrimaryButton, SecondaryButton, profileStyles as styles } from "./ui";

type Mode = "summary" | "name" | "username" | "phone" | "phoneOtp" | "password";

function dateText(value: string): string {
  return new Intl.DateTimeFormat("en", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value));
}

export function ProfileScreen({ api, onLogout, onPasswordResetComplete = onLogout }: { api: CustomerProfileApi; onLogout(): void | Promise<void>; onPasswordResetComplete?(): void | Promise<void> }) {
  const [profile, setProfile] = useState<CustomerProfile>();
  const [mode, setMode] = useState<Mode>("summary");
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [loadVersion, setLoadVersion] = useState(0);
  const [error, setError] = useState<string>();
  const [success, setSuccess] = useState<string>();
  const [value, setValue] = useState("");
  const [phone, setPhone] = useState("");
  const [challengeId, setChallengeId] = useState("");
  const [code, setCode] = useState("");

  useEffect(() => {
    let active = true;
    void api.getProfile().then((result) => {
      if (!active) return;
      if (result.ok) setProfile(result.value);
      else setError(profileErrorMessage(result.code));
      setLoading(false);
    }).catch(() => {
      if (!active) return;
      setError(t.profile.errors.unexpected);
      setLoading(false);
    });
    return () => { active = false; };
  }, [api, loadVersion]);

  const reload = () => {
    setLoading(true);
    setError(undefined);
    setLoadVersion((current) => current + 1);
  };

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

  const show = (next: Mode, initial = "") => {
    setError(undefined);
    setSuccess(undefined);
    setValue(initial);
    setMode(next);
  };

  const acceptProfile = (next: CustomerProfile, message: string) => {
    setProfile(next);
    setSuccess(message);
    setMode("summary");
  };

  const saveName = () => run(async () => {
    const result = await api.updateFullName(value);
    if (result.ok) acceptProfile(result.value, t.profile.nameUpdated);
    else setError(profileErrorMessage(result.code));
  });

  const saveUsername = () => run(async () => {
    const result = await api.changeUsername(value);
    if (result.ok) acceptProfile(result.value, t.profile.usernameUpdated);
    else setError(profileErrorMessage(result.code));
  });

  const requestPhone = (resend = false) => run(async () => {
    const result = await api.requestPhoneChange(phone);
    if (result.ok) {
      setChallengeId(result.value.challengeId);
      setCode("");
      setMode("phoneOtp");
      if (resend) setSuccess(t.profile.verificationCodeSent);
    } else setError(profileErrorMessage(result.code));
  });

  const verifyPhone = () => run(async () => {
    const result = await api.verifyPhoneChange(challengeId, code);
    if (result.ok) acceptProfile(result.value, t.profile.phoneUpdated);
    else setError(profileErrorMessage(result.code));
  });

  if (mode === "password" && profile) {
    return <PasswordResetFlow api={api} initialPhone={profile.phone} cancelLabel={t.profile.cancel} onCancel={() => setMode("summary")} onComplete={onPasswordResetComplete} />;
  }

  if (loading && !profile) {
    return (
      <View style={styles.content}>
        <Text accessibilityRole="header" style={styles.title}>{t.profile.title}</Text>
        <Text>{t.profile.loading}</Text>
        <LogoutButton onLogout={onLogout} />
      </View>
    );
  }

  if (!profile) {
    return (
      <View style={styles.content}>
        <Text accessibilityRole="header" style={styles.title}>{t.profile.title}</Text>
        {error ? <Notice kind="error">{error}</Notice> : null}
        <PrimaryButton label={t.profile.retry} onPress={reload} />
        <LogoutButton onLogout={onLogout} />
      </View>
    );
  }

  if (mode !== "summary") {
    const title = mode === "name" ? t.profile.editName : mode === "username" ? t.profile.changeUsername : mode === "phone" ? t.profile.phoneChangeTitle : t.profile.phoneOtpTitle;
    return (
      <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
        <Text accessibilityRole="header" style={styles.title}>{title}</Text>
        {mode === "phone" ? <Text style={styles.subtitle}>{t.profile.phoneChangeHelp}</Text> : null}
        {mode === "phoneOtp" ? <Text style={styles.subtitle}>{t.profile.phoneOtpHelp(phone)}</Text> : null}
        <View style={styles.card}>
          {mode === "name" ? <Field label={t.profile.fullName} value={value} onChangeText={setValue} autoCapitalize="words" /> : null}
          {mode === "username" ? (
            <>
              <Field label={t.profile.username} value={value} onChangeText={setValue} />
              {profile.usernameChangeAvailableAt ? <Notice kind="info">{t.profile.usernameCooldown(dateText(profile.usernameChangeAvailableAt))}</Notice> : null}
            </>
          ) : null}
          {mode === "phone" ? <Field label={t.profile.phone} value={phone} onChangeText={setPhone} keyboardType="phone-pad" /> : null}
          {mode === "phoneOtp" ? <Field label={t.profile.verificationCode} value={code} onChangeText={setCode} keyboardType="number-pad" /> : null}
          {success ? <Notice kind="success">{success}</Notice> : null}
          {error ? <Notice kind="error">{error}</Notice> : null}
          <PrimaryButton busy={busy} label={busy ? t.profile.working : mode === "name" ? t.profile.save : mode === "username" ? t.profile.changeUsername : mode === "phone" ? t.profile.continue : t.profile.verify} onPress={mode === "name" ? saveName : mode === "username" ? saveUsername : mode === "phone" ? () => requestPhone() : verifyPhone} />
          {mode === "phoneOtp" ? <SecondaryButton disabled={busy} label={t.profile.resend} onPress={() => requestPhone(true)} /> : null}
          <SecondaryButton disabled={busy} label={t.profile.cancel} onPress={() => setMode("summary")} />
        </View>
      </ScrollView>
    );
  }

  return (
    <ScrollView contentContainerStyle={styles.content} refreshControl={<RefreshControl refreshing={loading} onRefresh={reload} />}>
      <Text accessibilityRole="header" style={styles.title}>{t.profile.title}</Text>
      {success ? <Notice kind="success">{success}</Notice> : null}
      {error ? <Notice kind="error">{error}</Notice> : null}
      <View style={styles.card}>
        <Value label={t.profile.fullName} value={profile.fullName} />
        <Value label={t.profile.username} value={profile.username} />
        {profile.usernameChangeAvailableAt ? <Notice kind="info">{t.profile.usernameCooldown(dateText(profile.usernameChangeAvailableAt))}</Notice> : null}
        <Value label={t.profile.phone} value={`${profile.phone} · ${profile.phoneVerified ? t.profile.verified : t.profile.notVerified}`} />
        <Value label={t.profile.email} value={`${profile.email} · ${profile.emailVerified ? t.profile.verified : t.profile.notVerified}`} />
        <Value label={t.profile.joined} value={dateText(profile.joinedAt)} />
      </View>
      <View style={styles.actions}>
        <SecondaryButton label={t.profile.editName} onPress={() => show("name", profile.fullName)} />
        <SecondaryButton label={t.profile.changeUsername} onPress={() => show("username", profile.username)} />
        <SecondaryButton label={t.profile.changePhone} onPress={() => { setPhone(""); show("phone"); }} />
      </View>
      <View style={styles.card}>
        <Text style={styles.sectionTitle}>{t.profile.security}</Text>
        <SecondaryButton label={t.profile.resetPassword} onPress={() => show("password")} />
      </View>
      <LogoutButton onLogout={onLogout} />
    </ScrollView>
  );
}

function Value({ label, value }: { label: string; value: string }) {
  return <View style={styles.valueRow}><Text style={styles.valueLabel}>{label}</Text><Text style={styles.value}>{value}</Text></View>;
}

function LogoutButton({ onLogout }: { onLogout(): void | Promise<void> }) {
  return (
    <Pressable accessibilityRole="button" onPress={() => void onLogout()} style={styles.dangerButton}>
      <Text style={styles.dangerText}>{t.auth.logout}</Text>
    </Pressable>
  );
}
