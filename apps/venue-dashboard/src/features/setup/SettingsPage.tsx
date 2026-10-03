import type { ApiClient } from "../../api";
import type { Language } from "../../i18n";
import { OpeningHoursEditor } from "./OpeningHoursEditor";
import { PhotoManager } from "./PhotoManager";
import { VenueProfile } from "./VenueProfile";
import { setupStyles as styles } from "./styles";

export function SettingsPage({ api, onLanguagePreference }: { api: ApiClient; onLanguagePreference: (language: Language) => void }) {
  return <div style={styles.page}>
    <VenueProfile api={api} onLanguagePreference={onLanguagePreference} />
    <OpeningHoursEditor api={api} />
    <PhotoManager api={api} />
  </div>;
}
