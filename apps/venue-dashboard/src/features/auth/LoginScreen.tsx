import { useMemo, useState, type CSSProperties, type FormEvent } from "react";
import { logicalSpacing, webTokens } from "@e7gz/tokens/web";
import { messages, type Language } from "../../i18n";
import { authErrorMessage } from "./errorMessages";
import { createVenueSessionApi } from "./session";

export function VenueLoginScreen({ apiBaseUrl, language, onAuthenticated }: { apiBaseUrl: string; language: Language; onAuthenticated(accessToken: string): void }) {
  const t = messages[language];
  const session = useMemo(() => createVenueSessionApi(apiBaseUrl), [apiBaseUrl]);
  const [loginIdentifier, setLoginIdentifier] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(undefined);
    try {
      const result = await session.login(loginIdentifier, password);
      if (result.ok) onAuthenticated(result.accessToken);
      else setError(authErrorMessage(language, result.code));
    } catch {
      setError(t.auth.errors.unexpected);
    } finally {
      setBusy(false);
    }
  };

  return (
    <main dir={language === "ar" ? "rtl" : "ltr"} style={styles.page}>
      <form onSubmit={submit} style={styles.card}>
        <h2 style={styles.title}>{t.auth.title}</h2>
        <label style={styles.label}>{t.auth.identifier}<input aria-label={t.auth.identifier} autoComplete="username" value={loginIdentifier} onChange={(event) => setLoginIdentifier(event.target.value)} style={styles.input} /></label>
        <label style={styles.label}>{t.auth.password}<input aria-label={t.auth.password} autoComplete="current-password" type="password" value={password} onChange={(event) => setPassword(event.target.value)} style={styles.input} /></label>
        {error ? <p role="alert" style={styles.error}>{error}</p> : null}
        <button disabled={busy} type="submit" style={styles.button}>{busy ? t.auth.working : t.auth.submit}</button>
      </form>
    </main>
  );
}

const styles = {
  page: { alignItems: "center", display: "flex", justifyContent: "center", minHeight: "calc(100vh - 4.5rem)", ...logicalSpacing.paddingInline(webTokens.spacing["3xl"]), paddingBlock: webTokens.spacing["8xl"] },
  card: { backgroundColor: webTokens.color.surface.raised, border: `${webTokens.spacing.hairline}px solid ${webTokens.color.border.subtle}`, borderRadius: webTokens.radius["3xl"], boxShadow: webTokens.shadow.raised, display: "grid", gap: webTokens.spacing["4xl"], maxWidth: "26rem", padding: webTokens.spacing["8xl"], width: "100%" },
  title: { fontSize: webTokens.typography.fontSize.heading, marginBlock: 0, textAlign: "start" },
  label: { color: webTokens.color.text.venueMuted, display: "grid", fontSize: webTokens.typography.fontSize.label, fontWeight: webTokens.typography.fontWeight.semibold, gap: webTokens.spacing.sm, textAlign: "start" },
  input: { backgroundColor: webTokens.color.surface.raised, border: `${webTokens.spacing.hairline}px solid ${webTokens.color.border.venue}`, borderRadius: webTokens.radius.xl, color: webTokens.color.text.venue, minHeight: 48, paddingInline: webTokens.spacing["3xl"] },
  button: { backgroundColor: webTokens.color.brand.venue, border: 0, borderRadius: webTokens.radius.xl, boxShadow: webTokens.shadow.action, color: webTokens.color.text.inverse, cursor: "pointer", fontWeight: webTokens.typography.fontWeight.bold, minHeight: 48 },
  error: { color: webTokens.color.feedback.danger, marginBlock: 0, textAlign: "start" },
} satisfies Record<string, CSSProperties>;
