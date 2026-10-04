import { useState } from "react";
import type { ApiClient } from "../../api";
import { messages as t } from "../../i18n/messages";
import { VenueForm } from "./VenueForm";
import { Link } from "react-router-dom";
import { AdminPhotoManager } from "./AdminPhotoManager";
import { AdminResourceManager } from "./AdminResourceManager";
import { VenueAccountPanel } from "../venue-accounts/VenueAccountPanel";

export function Onboarding({ api }: { api: ApiClient }) {
  const [createdId, setCreatedId] = useState<string>();
  return <section className="admin-page admin-onboarding"><header className="page-heading page-heading--actions"><div><span className="page-eyebrow">Venue onboarding · admin operated</span><h2>{t.venues.onboarding}</h2></div><Link className="button button--secondary" to="/venues">{t.cancel}</Link></header>
    <div className="step-strip" aria-hidden="true"><span className="is-current"><b>1</b> Venue information</span><span><b>2</b> Resources</span><span><b>3</b> Photos</span><span><b>4</b> Venue account</span><span><b>5</b> Review</span></div>
    {createdId ? <><p className="notice notice--success" role="status">{t.venues.created}</p><div className="setup-grid"><AdminResourceManager api={api} venueId={createdId} /><AdminPhotoManager api={api} venueId={createdId} /><VenueAccountPanel api={api} venueId={createdId} initial={null} /></div><div className="form-actions"><Link className="button button--primary" to={`/venues/${createdId}`}>Review venue and complete onboarding</Link></div></> : <VenueForm api={api} onSaved={(venue) => setCreatedId(venue.id)} />}
  </section>;
}
