import { useMemo, useState, type CSSProperties, type FormEvent } from "react";
import { logicalSpacing, webTokens } from "@e7gz/tokens/web";
import { messages as t } from "../../i18n/messages";
import { authErrorMessage } from "./errorMessages";
import { createAdminSessionApi } from "./session";

export function AdminLoginScreen({ apiBaseUrl, onAuthenticated }: { apiBaseUrl: string; onAuthenticated(accessToken: string): void }) {
  const session = useMemo(() => createAdminSessionApi(apiBaseUrl), [apiBaseUrl]);
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string>();
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    setBusy(true);
    setError(undefined);
    try {
      const result = await session.login(email, password);
      if (result.ok) onAuthenticated(result.accessToken);
      else setError(authErrorMessage(result.code));
    } catch {
      setError(t.auth.errors.unexpected);
    } finally {
      setBusy(false);
    }
  };
  return (
    <main style={styles.page}>
      <form onSubmit={submit} style={styles.card}>
        <h2 style={styles.title}>{t.auth.title}</h2>
        <label style={styles.label}>{t.auth.email}<input aria-label={t.auth.email} autoComplete="username" type="email" value={email} onChange={(event) => setEmail(event.target.value)} style={styles.input} /></label>
        <label style={styles.label}>{t.auth.password}<input aria-label={t.auth.password} autoComplete="current-password" type="password" value={password} onChange={(event) => setPassword(event.target.value)} style={styles.input} /></label>
        {error ? <p role="alert" style={styles.error}>{error}</p> : null}
        <button disabled={busy} type="submit" style={styles.button}>{busy ? t.auth.working : t.auth.submit}</button>
      </form>
    </main>
  );
}

const styles = {
  page: { alignItems: "center", display: "flex", justifyContent: "center", minHeight: "calc(100vh - 5rem)", ...logicalSpacing.paddingInline(webTokens.spacing["3xl"]) },
  card: { backgroundColor: webTokens.color.surface.raised, border: `${webTokens.spacing.hairline}px solid ${webTokens.color.border.admin}`, borderRadius: webTokens.radius["3xl"], boxShadow: webTokens.shadow.card, display: "grid", gap: webTokens.spacing.lg, maxWidth: "26rem", padding: webTokens.spacing["5xl"], width: "100%" },
  title: { marginBlock: 0, textAlign: "start" },
  label: { color: webTokens.color.text.adminMuted, display: "grid", fontSize: webTokens.typography.fontSize.label, fontWeight: webTokens.typography.fontWeight.semibold, gap: webTokens.spacing.sm, textAlign: "start" },
  input: { backgroundColor: webTokens.color.surface.adminSubtle, border: `${webTokens.spacing.hairline}px solid ${webTokens.color.border.admin}`, borderRadius: webTokens.radius["2xl"], color: webTokens.color.text.admin, minHeight: 48, paddingInline: webTokens.spacing["3xl"] },
  button: { backgroundColor: webTokens.color.brand.primary, border: 0, borderRadius: webTokens.radius["2xl"], color: webTokens.color.text.inverse, cursor: "pointer", fontWeight: webTokens.typography.fontWeight.bold, minHeight: 48 },
  error: { color: webTokens.color.feedback.danger, marginBlock: 0, textAlign: "start" },
} satisfies Record<string, CSSProperties>;
