import { useState } from "react";
import { useMessages } from "../../i18n";
import { setupStyles as styles } from "./styles";

export function OwnerPasswordDialog({
  actionLabel,
  error,
  open,
  onCancel,
  onConfirm,
}: {
  actionLabel: string;
  error?: string;
  open: boolean;
  onCancel: () => void;
  onConfirm: (password: string) => Promise<boolean>;
}) {
  if (!open) return null;

  return <OpenOwnerPasswordDialog actionLabel={actionLabel} error={error} onCancel={onCancel} onConfirm={onConfirm} />;
}

function OpenOwnerPasswordDialog({
  actionLabel,
  error,
  onCancel,
  onConfirm,
}: {
  actionLabel: string;
  error?: string;
  onCancel: () => void;
  onConfirm: (password: string) => Promise<boolean>;
}) {
  const t = useMessages();
  const [password, setPassword] = useState("");
  const [submitting, setSubmitting] = useState(false);

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    setSubmitting(true);
    try {
      const complete = await onConfirm(password);
      setPassword("");
      if (complete) onCancel();
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div style={styles.dialogBackdrop}>
      <form aria-label={actionLabel} aria-modal="true" onSubmit={(event) => void submit(event)} role="dialog" style={styles.dialog}>
        <div>
          <h2 style={styles.cardTitle}>{t.setup.ownerPassword.title}</h2>
          <p style={styles.muted}>{t.setup.ownerPassword.description}</p>
        </div>
        <label style={styles.field}>
          <span style={styles.label}>{t.setup.ownerPassword.label}</span>
          <input
            autoComplete="current-password"
            autoFocus
            required
            style={styles.input}
            type="password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
          />
        </label>
        {error ? <p role="alert" style={styles.error}>{error}</p> : null}
        <div style={styles.actions}>
          <button disabled={submitting} style={styles.primaryButton} type="submit">
            {submitting ? t.setup.ownerPassword.confirming : t.setup.ownerPassword.confirm}
          </button>
          <button disabled={submitting} style={styles.secondaryButton} type="button" onClick={onCancel}>
            {t.setup.common.cancel}
          </button>
        </div>
      </form>
    </div>
  );
}
