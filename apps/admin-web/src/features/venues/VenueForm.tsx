import { useEffect, useMemo, useState, type FormEvent } from "react";
import type { components } from "@e7gz/api-client";
import { errorCodeOf, errorFieldOf, idempotencyHeaders, type ApiClient } from "../../api";
import { messages as t } from "../../i18n/messages";
import { adminErrorMessage } from "../adminErrors";
import { distanceKm, parseLocation, type Coordinates } from "./location";
import { LocationPicker } from "./LocationPicker";

type Area = components["schemas"]["AdminArea"];
type Venue = components["schemas"]["AdminVenue"];
type Activity = components["schemas"]["Activity"];
const activities: Activity[] = ["PLAYSTATION", "BILLIARDS", "PING_PONG"];
const defaultPoint = { lat: 30.0444, lng: 31.2357 };

export function VenueForm({ api, venue, onSaved }: { api: ApiClient; venue?: Venue; onSaved: (venue: Venue) => void }) {
  const [areas, setAreas] = useState<Area[]>([]);
  const [areasLoading, setAreasLoading] = useState(true);
  const [areasFailed, setAreasFailed] = useState(false);
  const [areasVersion, setAreasVersion] = useState(0);
  const [name, setName] = useState(venue?.name ?? "");
  const [areaId, setAreaId] = useState(venue?.areaId ?? "");
  const [address, setAddress] = useState(venue?.address ?? "");
  const [phone, setPhone] = useState(venue?.phone ?? "");
  const [selectedActivities, setActivities] = useState<Activity[]>(venue?.activities ?? ["PLAYSTATION"]);
  const [location, setLocation] = useState<Coordinates>(venue?.location ?? defaultPoint);
  const [locationText, setLocationText] = useState(venue ? `${venue.location.lat},${venue.location.lng}` : "");
  const [fieldError, setFieldError] = useState<Record<string, string>>({});
  const [error, setError] = useState<string>();
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    let active = true;
    void api.GET("/api/v1/admin/areas").then(({ data }) => {
      if (active) { setAreas(data?.data ?? []); setAreasFailed(!data); setAreasLoading(false); }
    }).catch(() => { if (active) { setAreasFailed(true); setAreasLoading(false); } });
    return () => { active = false; };
  }, [api, areasVersion]);
  const retryAreas = () => { setAreasLoading(true); setAreasFailed(false); setAreasVersion((value) => value + 1); };
  const selectedArea = areas.find((area) => area.id === areaId);
  const farFromArea = useMemo(() => selectedArea?.centroid ? distanceKm(selectedArea.centroid, location) > 5 : false, [selectedArea, location]);
  const parsePaste = () => {
    const parsed = parseLocation(locationText);
    if (!parsed) setFieldError((current) => ({ ...current, location: t.venues.locationInvalid }));
    else { setLocation(parsed); setFieldError((current) => ({ ...current, location: "" })); }
  };
  const toggleActivity = (activity: Activity) => setActivities((current) => current.includes(activity) ? current.filter((item) => item !== activity) : [...current, activity]);
  const chooseArea = (nextAreaId: string) => {
    setAreaId(nextAreaId);
    const centroid = areas.find((area) => area.id === nextAreaId)?.centroid;
    if (!venue && centroid) { setLocation(centroid); setLocationText(`${centroid.lat},${centroid.lng}`); }
  };
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const errors: Record<string, string> = {};
    if (!name.trim()) errors.name = t.errors.fields.name;
    if (!areaId) errors.areaId = t.errors.fields.areaId;
    if (!address.trim()) errors.address = t.errors.fields.address;
    if (!phone.trim()) errors.phone = t.errors.fields.phone;
    if (!selectedActivities.length) errors.activities = "Select at least one activity.";
    setFieldError(errors); if (Object.keys(errors).length) return;
    setSaving(true); setError(undefined);
    const body = { name: name.trim(), areaId, address: address.trim(), location, phone: phone.trim(), activities: selectedActivities };
    try {
      const result = venue
        ? await api.PATCH("/api/v1/admin/venues/{venueId}", { params: { path: { venueId: venue.id }, header: idempotencyHeaders() }, body })
        : await api.POST("/api/v1/admin/venues", { params: { header: idempotencyHeaders() }, body });
      if (result.error) {
        const field = errorFieldOf(result.error);
        const message = adminErrorMessage(errorCodeOf(result.error), field);
        if (field) setFieldError((current) => ({ ...current, [field]: message })); else setError(message);
      } else if (result.data) onSaved(result.data.data);
    } catch { setError(t.networkError); }
    finally { setSaving(false); }
  };
  const field = (key: string, label: string, input: React.ReactNode, wide = false) => <div className={wide ? "form-span" : undefined}><label>{label}{input}</label>{fieldError[key] && <p className="field-error" role="alert">{fieldError[key]}</p>}</div>;
  return <form className="form-card form-grid venue-form" aria-label={venue ? "Edit venue" : t.venues.onboarding} onSubmit={(event) => void submit(event)}>
    <div className="form-heading form-span"><h3>{venue ? t.edit : "Venue information"}</h3><p>Enter the venue profile and choose its exact map location.</p></div>
    {areasLoading && <p className="notice notice--info form-span" role="status">{t.loading}</p>}
    {areasFailed && <div className="notice notice--error form-span" role="alert">{t.networkError} <button className="button button--secondary" type="button" onClick={retryAreas}>{t.retry}</button></div>}
    {field("name", t.venues.name, <input maxLength={200} value={name} aria-invalid={Boolean(fieldError.name)} onChange={(event) => setName(event.target.value)} />)}
    {field("areaId", t.venues.area, <select value={areaId} aria-invalid={Boolean(fieldError.areaId)} onChange={(event) => chooseArea(event.target.value)}><option value="">Select area</option>{areas.filter((area) => area.active || area.id === venue?.areaId).map((area) => <option key={area.id} value={area.id}>{area.name}</option>)}</select>)}
    {field("address", t.venues.address, <input maxLength={200} value={address} aria-invalid={Boolean(fieldError.address)} onChange={(event) => setAddress(event.target.value)} />, true)}
    {field("phone", t.venues.phone, <input value={phone} aria-invalid={Boolean(fieldError.phone)} onChange={(event) => setPhone(event.target.value)} />)}
    <fieldset className="form-span activity-picker"><legend>{t.venues.activities}</legend>{activities.map((activity) => <label key={activity}><input type="checkbox" checked={selectedActivities.includes(activity)} onChange={() => toggleActivity(activity)} /><span>{activity.replaceAll("_", " ")}</span></label>)}{fieldError.activities && <p className="field-error" role="alert">{fieldError.activities}</p>}</fieldset>
    <div className="form-span location-entry">
      <label>{t.venues.locationPaste}<input value={locationText} aria-invalid={Boolean(fieldError.location)} onChange={(event) => setLocationText(event.target.value)} /></label>
      <button className="button button--secondary" type="button" onClick={parsePaste}>Use location</button>
      {fieldError.location && <p className="field-error" role="alert">{fieldError.location}</p>}
    </div>
    <div className="form-span map-card"><LocationPicker value={location} onChange={(point) => { setLocation(point); setLocationText(`${point.lat},${point.lng}`); }} /><p className="coordinate-readout">{t.venues.latitude}: {location.lat}; {t.venues.longitude}: {location.lng}</p></div>
    {farFromArea && <p className="notice notice--warning form-span" role="status">{t.venues.distanceWarning}</p>}
    {error && <p className="notice notice--error form-span" role="alert">{error}</p>}
    <div className="form-actions form-span"><button className="button button--primary" disabled={saving || areasLoading || areasFailed} type="submit">{saving ? t.saving : t.save}</button></div>
  </form>;
}
