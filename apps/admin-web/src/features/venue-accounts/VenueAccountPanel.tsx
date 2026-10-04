import { useState, type FormEvent } from "react";
import type { components } from "@e7gz/api-client";
import { errorCodeOf, errorFieldOf, idempotencyHeaders, type ApiClient } from "../../api";
import { adminErrorMessage } from "../adminErrors";
import { messages as t } from "../../i18n/messages";

type Account = components["schemas"]["AdminVenueAccount"];
type Language = components["schemas"]["VenueAccountLanguage"];
const pinDigits = (value: string) => value.replace(/[٠-٩۰-۹]/g, (digit) => String("٠١٢٣٤٥٦٧٨٩۰۱۲۳۴۵۶۷۸۹".indexOf(digit) % 10)).replace(/\D/g, "").slice(0, 4);

export function VenueAccountPanel({ api, venueId, initial }: { api: ApiClient; venueId: string; initial: Account | null }) {
  const [account, setAccount] = useState(initial);
  const [loginIdentifier, setLoginIdentifier] = useState("");
  const [password, setPassword] = useState("");
  const [ownerPin, setOwnerPin] = useState("");
  const [languagePreference, setLanguagePreference] = useState<Language>("en");
  const [error, setError] = useState<string>();
  const [resetKind, setResetKind] = useState<"login" | "pin">();
  const [newPassword, setNewPassword] = useState("");
  const [reason, setReason] = useState("");
  const create = async (event: FormEvent) => {
    event.preventDefault(); if (!/^\d{4}$/.test(ownerPin)) { setError(t.errors.fields.initialOwnerPassword); return; }
    if (!/^(?=.*[A-Za-z])(?=.*\d).{8,}$/.test(password)) { setError(t.errors.fields.password); return; }
    setError(undefined);
    try {
      const result = await api.POST("/api/v1/admin/venues/{venueId}/venue-account", { params: { path: { venueId }, header: idempotencyHeaders() }, body: { loginIdentifier, password, initialOwnerPassword: ownerPin, languagePreference } });
      if (result.data) setAccount(result.data.data); else setError(adminErrorMessage(errorCodeOf(result.error), errorFieldOf(result.error)));
    } catch { setError(t.networkError); }
  };
  const setEnabled = async (enabled: boolean) => {
    if (!account) return;
    const path = enabled ? "/api/v1/admin/venue-accounts/{venueAccountId}/enable" as const : "/api/v1/admin/venue-accounts/{venueAccountId}/disable" as const;
    if (!reason.trim()) { setError("Enter a reason."); return; }
    try {
      const result = await api.POST(path, { params: { path: { venueAccountId: account.id }, header: idempotencyHeaders() }, body: { reason: reason.trim() } });
      if (result.data) setAccount(result.data.data); else setError(adminErrorMessage(errorCodeOf(result.error)));
    } catch { setError(t.networkError); }
  };
  const reset = async (event: FormEvent) => {
    event.preventDefault(); if (!account || !resetKind) return;
    if (resetKind === "pin" && !/^\d{4}$/.test(newPassword)) { setError(t.errors.fields.initialOwnerPassword); return; }
    if (resetKind === "login" && !/^(?=.*[A-Za-z])(?=.*\d).{8,}$/.test(newPassword)) { setError(t.errors.fields.password); return; }
    const path = resetKind === "pin" ? "/api/v1/admin/venue-accounts/{venueAccountId}/reset-owner-password" as const : "/api/v1/admin/venue-accounts/{venueAccountId}/reset-login-password" as const;
    try {
      const result = await api.POST(path, { params: { path: { venueAccountId: account.id }, header: idempotencyHeaders() }, body: { newPassword } });
      if (result.error) setError(adminErrorMessage(errorCodeOf(result.error), errorFieldOf(result.error))); else { setResetKind(undefined); setNewPassword(""); }
    } catch { setError(t.networkError); }
  };
  return <section className="setup-card"><div className="card-heading"><h3>{t.account.title}</h3>{account && <span className={`status-pill ${account.accountStatus === "ENABLED" ? "status-pill--success" : "status-pill--neutral"}`}>{t.account.statuses[account.accountStatus]}</span>}</div>{error && <p className="notice notice--error" role="alert">{error}</p>}
    {!account ? <form className="stack-form" autoComplete="off" onSubmit={(event) => void create(event)}>
      <p className="empty-copy">{t.account.none}</p>
      <label>{t.account.loginIdentifier}<input autoComplete="off" name="newVenueLoginIdentifier" value={loginIdentifier} onChange={(event) => setLoginIdentifier(event.target.value)} /></label>
      <label>{t.account.loginPassword}<input autoComplete="new-password" name="newVenueLoginPassword" type="password" minLength={8} pattern="(?=.*[A-Za-z])(?=.*[0-9]).{8,}" value={password} onChange={(event) => setPassword(event.target.value)} /></label>
      <label>{t.account.ownerPin}<input autoComplete="new-password" name="newVenueOwnerPin" inputMode="numeric" pattern="[0-9]{4}" maxLength={4} type="password" value={ownerPin} onChange={(event) => setOwnerPin(pinDigits(event.target.value))} /></label>
      <label>{t.account.language}<select value={languagePreference} onChange={(event) => setLanguagePreference(event.target.value as Language)}><option value="en">{t.account.languages.en}</option><option value="ar">{t.account.languages.ar}</option></select></label>
      <button className="button button--primary" type="submit">{t.account.create}</button>
    </form> : <>
      <p className="account-identifier">{account.loginIdentifier}</p>
      <label>{t.venues.reason}<input value={reason} onChange={(event) => setReason(event.target.value)} /></label>
      <div className="form-actions"><button className="button button--secondary" type="button" onClick={() => void setEnabled(account.accountStatus === "DISABLED")}>{account.accountStatus === "DISABLED" ? t.account.enable : t.account.disable}</button>
      <button className="button button--secondary" type="button" onClick={() => setResetKind("login")}>{t.account.resetLogin}</button>
      <button className="button button--secondary" type="button" onClick={() => setResetKind("pin")}>{t.account.resetOwnerPin}</button></div>
    </>}
    {resetKind && <form className="stack-form reset-form" autoComplete="off" onSubmit={(event) => void reset(event)}><label>{resetKind === "pin" ? t.account.newOwnerPin : t.account.newLoginPassword}<input autoComplete="new-password" name={resetKind === "pin" ? "resetVenueOwnerPin" : "resetVenueLoginPassword"} type="password" inputMode={resetKind === "pin" ? "numeric" : undefined} minLength={resetKind === "login" ? 8 : undefined} pattern={resetKind === "pin" ? "[0-9]{4}" : "(?=.*[A-Za-z])(?=.*[0-9]).{8,}"} maxLength={resetKind === "pin" ? 4 : undefined} value={newPassword} onChange={(event) => setNewPassword(resetKind === "pin" ? pinDigits(event.target.value) : event.target.value)} /></label><div className="form-actions"><button className="button button--primary" type="submit">{t.save}</button><button className="button button--secondary" type="button" onClick={() => setResetKind(undefined)}>{t.cancel}</button></div></form>}
  </section>;
}
