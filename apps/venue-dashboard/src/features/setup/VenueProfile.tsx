import { useEffect, useState } from "react";
import type { components } from "@e7gz/api-client";
import { apiErrorOf, type ApiClient } from "../../api";
import { useMessages, type Language } from "../../i18n";
import { setupErrorMessage } from "./errorMessages";
import { setupStyles as styles } from "./styles";

type VenueSettings = components["schemas"]["VenueSettings"];
type EditableField = "name" | "address" | "phone";

const editableFields = new Set<EditableField>(["name", "address", "phone"]);

export function VenueProfile({ api, onLanguagePreference }: { api: ApiClient; onLanguagePreference: (language: Language) => void }) {
  const t = useMessages();
  const [settings, setSettings] = useState<VenueSettings>();
  const [form, setForm] = useState({ name: "", address: "", phone: "", languagePreference: "en" as Language });
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string>();
  const [saved, setSaved] = useState(false);
  const [fieldErrors, setFieldErrors] = useState<Partial<Record<EditableField, string>>>({});
  const [refreshVersion, setRefreshVersion] = useState(0);

  useEffect(() => {
    let active = true;
    void api.GET("/api/v1/venue/settings").then(({ data, error: body }) => {
      if (!active) return;
      if (!data) {
        setError(setupErrorMessage(apiErrorOf(body).code, t));
        return;
      }
      setSettings(data.data);
      setForm({ name: data.data.name, address: data.data.address, phone: data.data.phone, languagePreference: data.data.languagePreference });
    }).catch(() => { if (active) setError(t.setup.errors.network); }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [api, refreshVersion, t]);

  const retry = () => {
    setLoading(true);
    setError(undefined);
    setRefreshVersion((version) => version + 1);
  };

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    setSaving(true);
    setSaved(false);
    setError(undefined);
    setFieldErrors({});
    try {
      const result = await api.PATCH("/api/v1/venue/settings", { body: form });
      if (!result.data) {
        const apiError = apiErrorOf(result.error);
        if (apiError.code === "VALIDATION_FAILED") {
          const invalidFields = new Set<string>();
          if (apiError.field) invalidFields.add(apiError.field);
          if (Array.isArray(apiError.details?.fields)) {
            for (const field of apiError.details.fields) if (typeof field === "string") invalidFields.add(field);
          }
          const nextErrors: Partial<Record<EditableField, string>> = {};
          for (const field of invalidFields) {
            if (editableFields.has(field as EditableField)) nextErrors[field as EditableField] = t.setup.profile.fieldError;
          }
          setFieldErrors(nextErrors);
        }
        setError(setupErrorMessage(apiError.code, t));
        return;
      }
      setSettings(result.data.data);
      setSaved(true);
      onLanguagePreference(result.data.data.languagePreference);
    } catch {
      setError(t.setup.errors.network);
    } finally {
      setSaving(false);
    }
  };

  const updateField = <Field extends keyof typeof form>(field: Field, value: (typeof form)[Field]) => {
    setForm({ ...form, [field]: value });
    if (editableFields.has(field as EditableField)) {
      setFieldErrors((current) => ({ ...current, [field]: undefined }));
    }
  };

  return (
    <section aria-labelledby="profile-title" style={styles.card}>
      <div>
        <h2 id="profile-title" style={styles.cardTitle}>{t.setup.profile.title}</h2>
        <p style={styles.muted}>{t.setup.profile.description}</p>
      </div>
      {loading ? <p role="status">{t.setup.common.loading}</p> : null}
      {error ? <div role="alert" style={styles.error}><p>{error}</p><button style={styles.secondaryButton} type="button" onClick={retry}>{t.setup.common.retry}</button></div> : null}
      {settings && !loading ? (
        <form onSubmit={(event) => void submit(event)}>
          <div style={styles.grid}>
            <div style={styles.field}><label htmlFor="venue-name" style={styles.label}>{t.setup.profile.name}</label><input id="venue-name" aria-describedby={fieldErrors.name ? "venue-name-error" : undefined} aria-invalid={Boolean(fieldErrors.name)} required style={{ ...styles.input, ...(fieldErrors.name ? styles.invalidInput : {}) }} value={form.name} onChange={(event) => updateField("name", event.target.value)} />{fieldErrors.name ? <span id="venue-name-error" style={styles.fieldError}>{fieldErrors.name}</span> : null}</div>
            <div style={styles.field}><label htmlFor="venue-address" style={styles.label}>{t.setup.profile.address}</label><input id="venue-address" aria-describedby={fieldErrors.address ? "venue-address-error" : undefined} aria-invalid={Boolean(fieldErrors.address)} required style={{ ...styles.input, ...(fieldErrors.address ? styles.invalidInput : {}) }} value={form.address} onChange={(event) => updateField("address", event.target.value)} />{fieldErrors.address ? <span id="venue-address-error" style={styles.fieldError}>{fieldErrors.address}</span> : null}</div>
            <div style={styles.field}><label htmlFor="venue-phone" style={styles.label}>{t.setup.profile.phone}</label><input id="venue-phone" aria-describedby={fieldErrors.phone ? "venue-phone-error" : undefined} aria-invalid={Boolean(fieldErrors.phone)} required style={{ ...styles.input, ...(fieldErrors.phone ? styles.invalidInput : {}) }} value={form.phone} onChange={(event) => updateField("phone", event.target.value)} />{fieldErrors.phone ? <span id="venue-phone-error" style={styles.fieldError}>{fieldErrors.phone}</span> : null}</div>
            <label style={styles.field}><span style={styles.label}>{t.setup.profile.language}</span><select style={styles.input} value={form.languagePreference} onChange={(event) => updateField("languagePreference", event.target.value as Language)}><option value="en">English</option><option value="ar">العربية</option></select></label>
          </div>
          <dl style={styles.grid}>
            <div><dt style={styles.label}>{t.setup.profile.area}</dt><dd>{settings.areaName}</dd></div>
            <div><dt style={styles.label}>{t.setup.profile.location}</dt><dd>{settings.location.lat}, {settings.location.lng}</dd></div>
            <div><dt style={styles.label}>{t.setup.profile.timezone}</dt><dd>{settings.timezone}</dd></div>
            <div><dt style={styles.label}>{t.setup.profile.status}</dt><dd>{t.setup.profile.statuses[settings.marketplaceStatus]}</dd></div>
            <div><dt style={styles.label}>{t.setup.profile.activities}</dt><dd>{settings.activities.map((activity) => t.activity[activity]).join(", ")}</dd></div>
          </dl>
          <p style={styles.info}>{t.setup.profile.readOnly}</p>
          {saved ? <p role="status" style={styles.success}>{t.setup.common.saved}</p> : null}
          <button disabled={saving} style={styles.primaryButton} type="submit">{saving ? t.setup.common.saving : t.setup.common.save}</button>
        </form>
      ) : null}
    </section>
  );
}
