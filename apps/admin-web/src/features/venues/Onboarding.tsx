import { useState } from "react";
import type { components } from "@e7gz/api-client";
import type { ApiClient } from "../../api";
import { messages as t } from "../../i18n/messages";
import { VenueForm } from "./VenueForm";
import { Link } from "react-router-dom";
import { AdminPhotoManager } from "./AdminPhotoManager";
import { AdminResourceManager } from "./AdminResourceManager";
import { VenueAccountPanel } from "../venue-accounts/VenueAccountPanel";

export function Onboarding({ api }: { api: ApiClient }) {
  const [createdVenue, setCreatedVenue] = useState<components["schemas"]["AdminVenue"]>();
  return <section className="admin-page admin-onboarding"><header className="page-heading page-heading--actions"><div><span className="page-eyebrow">{t.venues.onboardingEyebrow}</span><h2>{t.venues.onboarding}</h2></div><Link className="button button--secondary" to="/venues">{t.cancel}</Link></header>
    <div className="step-strip" aria-hidden="true"><span className="is-current"><b>1</b> {t.venues.steps.information}</span><span><b>2</b> {t.venues.steps.resources}</span><span><b>3</b> {t.venues.steps.photos}</span><span><b>4</b> {t.venues.steps.account}</span><span><b>5</b> {t.venues.steps.review}</span></div>
    {createdVenue ? <><p className="notice notice--success" role="status">{t.venues.created}</p><div className="setup-grid"><AdminResourceManager activities={createdVenue.activities} api={api} venueId={createdVenue.id} /><AdminPhotoManager api={api} venueId={createdVenue.id} /><VenueAccountPanel api={api} venueId={createdVenue.id} initial={null} /></div><div className="form-actions"><Link className="button button--primary" to={`/venues/${createdVenue.id}`}>{t.venues.reviewAndComplete}</Link></div></> : <VenueForm api={api} onSaved={setCreatedVenue} />}
  </section>;
}
