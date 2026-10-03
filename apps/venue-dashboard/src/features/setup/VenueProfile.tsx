import { useEffect, useState } from "react";
import type { components } from "@e7gz/api-client";
import { apiErrorOf, type ApiClient } from "../../api";
import { useMessages, type Language } from "../../i18n";
import { setupErrorMessage } from "./errorMessages";
import { setupStyles as styles } from "./styles";

type VenueSettings = components["schemas"]["VenueSettings"];

export function VenueProfile({ api, onLanguagePreference }: { api: ApiClient; onLanguagePreference: (language: Language) => void }) {
  const t = useMessages();
  const [settings, setSettings] = useState<VenueSettings>();
  const [form, setForm] = useState({ name: "", address: "", phone: "", languagePreference: "en" as Language });
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string>();
  const [saved, setSaved] = useState(false);
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
    try {
      const result = await api.PATCH("/api/v1/venue/settings", { body: form });
      if (!result.data) {
        setError(setupErrorMessage(apiErrorOf(result.error).code, t));
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
            <label style={styles.field}><span style={styles.label}>{t.setup.profile.name}</span><input required style={styles.input} value={form.name} onChange={(event) => setForm({ ...form, name: event.target.value })} /></label>
            <label style={styles.field}><span style={styles.label}>{t.setup.profile.address}</span><input required style={styles.input} value={form.address} onChange={(event) => setForm({ ...form, address: event.target.value })} /></label>
            <label style={styles.field}><span style={styles.label}>{t.setup.profile.phone}</span><input required style={styles.input} value={form.phone} onChange={(event) => setForm({ ...form, phone: event.target.value })} /></label>
            <label style={styles.field}><span style={styles.label}>{t.setup.profile.language}</span><select style={styles.input} value={form.languagePreference} onChange={(event) => setForm({ ...form, languagePreference: event.target.value as Language })}><option value="en">English</option><option value="ar">العربية</option></select></label>
          </div>
          <dl style={styles.grid}>
            <div><dt style={styles.label}>{t.setup.profile.area}</dt><dd>{settings.areaName}</dd></div>
            <div><dt style={styles.label}>{t.setup.profile.timezone}</dt><dd>{settings.timezone}</dd></div>
            <div><dt style={styles.label}>{t.setup.profile.status}</dt><dd>{settings.marketplaceStatus}</dd></div>
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
