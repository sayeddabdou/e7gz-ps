import { useState } from "react";
import { useMessages } from "../../i18n";
import { setupStyles as styles } from "./styles";

/** The Owner PIN is 4 digits (PCR-006); Arabic-Indic and Persian digits typed on an Arabic keyboard count as 0-9. */
export function toPinDigits(value: string): string {
  return value
    .replace(/[٠-٩]/g, (d) => String(d.charCodeAt(0) - 0x0660))
    .replace(/[۰-۹]/g, (d) => String(d.charCodeAt(0) - 0x06F0))
    .replace(/[^0-9]/g, "")
    .slice(0, 4);
}

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
            autoComplete="off"
            autoFocus
            inputMode="numeric"
            maxLength={4}
            pattern="[0-9]{4}"
            required
            style={styles.input}
            type="password"
            value={password}
            onChange={(event) => setPassword(toPinDigits(event.target.value))}
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
