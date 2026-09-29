package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** Venue Dashboard language, in lower case exactly as the database stores it - {@code ck_venue_account_language}. */
@Schema(enumAsRef = true)
public enum VenueAccountLanguage {
    en,
    ar
}
