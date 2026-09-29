package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** Venue closure kind - {@code ck_venue_closure_kind}. */
@Schema(enumAsRef = true)
public enum VenueClosureKind {
    FULL_DAY,
    PARTIAL
}
