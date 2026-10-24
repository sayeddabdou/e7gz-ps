import { useEffect, useState } from "react";
import { Pressable, RefreshControl, ScrollView, Text, View } from "react-native";
import Ionicons from "@expo/vector-icons/Ionicons";
import { nativeTokens } from "@e7gz/tokens/native";
import { en as t } from "../../i18n/en";
import type { CustomerProfile, CustomerProfileApi } from "./api";
import { profileErrorMessage } from "./errorMessages";
import { PasswordResetFlow } from "./PasswordResetFlow";
import { Field, Notice, PrimaryButton, SecondaryButton, profileStyles as styles } from "./ui";
import { useResendCountdown } from "./useResendCountdown";

type Mode = "summary" | "name" | "username" | "phone" | "phoneOtp" | "password";

export function profileDateText(value: string): string {
  return new Intl.DateTimeFormat("en", { dateStyle: "medium", timeStyle: "short", timeZone: "Africa/Cairo" }).format(new Date(value));
}

export function profileInitials(fullName: string): string {
  return fullName.trim().split(/\s+/).filter(Boolean).slice(0, 2).map((part) => part[0]).join("").toUpperCase();
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
  const resendCountdown = useResendCountdown();

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
      resendCountdown.start(result.serverTime);
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
              {profile.usernameChangeAvailableAt ? <Notice kind="info">{t.profile.usernameCooldown(profileDateText(profile.usernameChangeAvailableAt))}</Notice> : null}
            </>
          ) : null}
          {mode === "phone" ? <Field label={t.profile.phone} value={phone} onChangeText={setPhone} keyboardType="phone-pad" /> : null}
          {mode === "phoneOtp" ? <Field label={t.profile.verificationCode} value={code} onChangeText={setCode} keyboardType="number-pad" /> : null}
          {success ? <Notice kind="success">{success}</Notice> : null}
          {error ? <Notice kind="error">{error}</Notice> : null}
          <PrimaryButton busy={busy} label={busy ? t.profile.working : mode === "name" ? t.profile.save : mode === "username" ? t.profile.changeUsername : mode === "phone" ? t.profile.continue : t.profile.verify} onPress={mode === "name" ? saveName : mode === "username" ? saveUsername : mode === "phone" ? () => requestPhone() : verifyPhone} />
          {mode === "phoneOtp" ? (
            <SecondaryButton
              disabled={busy || resendCountdown.remaining > 0}
              label={resendCountdown.remaining > 0
                ? t.auth.otp.resendCountdown.replace("{seconds}", String(resendCountdown.remaining))
                : t.profile.resend}
              onPress={() => requestPhone(true)}
            />
          ) : null}
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
      <View style={styles.identityCard}>
        <View style={styles.avatar}><Text style={styles.avatarText}>{profileInitials(profile.fullName)}</Text></View>
        <View style={styles.identityDetails}>
          <Text testID="profile-name" style={styles.identityName}>{profile.fullName}</Text>
          <ContactLine value={profile.phone} verified={profile.phoneVerified} />
          <ContactLine value={profile.email} verified={profile.emailVerified} />
          <Text style={styles.memberSince}>{`${t.profile.joined} ${profileDateText(profile.joinedAt)}`}</Text>
        </View>
        <Pressable accessibilityLabel={t.profile.editName} accessibilityRole="button" onPress={() => show("name", profile.fullName)} style={styles.editChip}>
          <Text style={styles.editChipText}>{t.profile.edit}</Text>
        </Pressable>
      </View>
      <View style={styles.menu}>
        <ActionRow
          label={t.profile.changeUsername}
          value={profile.username}
          hint={profile.usernameChangeAvailableAt ? t.profile.usernameCooldown(profileDateText(profile.usernameChangeAvailableAt)) : undefined}
          onPress={() => show("username", profile.username)}
        />
        <ActionRow label={t.profile.changePhone} onPress={() => { setPhone(""); show("phone"); }} />
        {profile.phoneVerified ? <ActionRow label={t.profile.resetPassword} onPress={() => show("password")} /> : null}
        <LogoutButton onLogout={onLogout} />
      </View>
    </ScrollView>
  );
}

function ContactLine({ value, verified }: { value: string; verified: boolean }) {
  return (
    <View style={styles.contactLine}>
      <Text numberOfLines={1} style={styles.contactValue}>{value}</Text>
      <View style={[styles.statusBadge, verified ? styles.verifiedBadge : styles.unverifiedBadge]}>
        <Text style={[styles.statusText, verified ? styles.verifiedText : styles.unverifiedText]}>{verified ? t.profile.verified : t.profile.notVerified}</Text>
      </View>
    </View>
  );
}

function ActionRow({ label, value, hint, onPress }: { label: string; value?: string; hint?: string; onPress(): void }) {
  return (
    <Pressable accessibilityRole="button" onPress={onPress} style={({ pressed }) => [styles.actionRow, pressed && styles.pressed]}>
      <View style={styles.actionContent}>
        <Text style={styles.actionLabel}>{label}</Text>
        {hint ? <Text style={styles.actionHint}>{hint}</Text> : null}
      </View>
      {value ? <Text numberOfLines={1} style={styles.actionValue}>{value}</Text> : null}
      <Ionicons name="chevron-forward" color={nativeTokens.color.text.subtle} size={18} />
    </Pressable>
  );
}

function LogoutButton({ onLogout }: { onLogout(): void | Promise<void> }) {
  return (
    <Pressable accessibilityRole="button" onPress={() => void onLogout()} style={({ pressed }) => [styles.logoutRow, pressed && styles.pressed]}>
      <Text style={styles.dangerText}>{t.auth.logout}</Text>
    </Pressable>
  );
}
