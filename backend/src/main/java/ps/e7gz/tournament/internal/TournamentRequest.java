package ps.e7gz.tournament.internal;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.codes.TournamentVisibility;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.money.Money;

/**
 * What a creator asks for when creating a Tournament (O16 part 1, E7-91): the same for a Player's request and a Venue's
 * Tournament (arch 09 sections 3.4 and 4.2). The Venue is not here: a Player names it beside the request, a Venue
 * Account creates at its own Venue.
 *
 * <p>{@link #of} refuses only a request whose shape cannot be a Tournament at all - a missing field, 1-4 distinct
 * Resources, a play mode exactly for PlayStation (FR-TRN-04), capacity 8-32, at least 60 minutes in 30-minute steps
 * (FR-TRN-03) - as {@code VALIDATION_FAILED} on the field, before any idempotency record. Capacity and duration are the
 * input bounds of the API: the same values the database's {@code ck_tournament_capacity} and
 * {@code ck_tournament_duration} refuse, which would otherwise be an unexpected 500 (their CHECK violations carry no
 * code). Every rule that depends on the state of the platform is decided in the creating command.
 *
 * @param startAt    truncated to the microsecond the database keeps
 * @param entryFee   informational only (FR-TRN-29); EGP 0 when absent
 * @param prize      informational only (FR-TRN-29); EGP 0 when absent
 */
public record TournamentRequest(Activity activity, PlayMode playMode, List<UUID> resourceIds, int capacity,
                                Instant startAt, int durationMinutes, TournamentVisibility visibility, Money entryFee,
                                Money prize) {

    /** FR-TRN-03: 1-4 Resources (the database counts them too: {@code tournament.assert_resources_operational}). */
    public static final int MAX_RESOURCES = 4;
    /** FR-TRN-03, {@code ck_tournament_capacity}. */
    public static final int MIN_CAPACITY = 8;
    public static final int MAX_CAPACITY = 32;
    /** FR-TRN-03, {@code ck_tournament_duration}: no maximum below the Operating Session (FR-TRN-04). */
    public static final int MIN_MINUTES = 60;
    public static final int STEP_MINUTES = 30;

    public TournamentRequest {
        Objects.requireNonNull(activity, "activity");
        Objects.requireNonNull(startAt, "startAt");
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(entryFee, "entryFee");
        Objects.requireNonNull(prize, "prize");
        resourceIds = List.copyOf(resourceIds);
        startAt = startAt.truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * The request, or {@code VALIDATION_FAILED} on the first field whose shape is wrong.
     *
     * @param entryFee {@code null} for EGP 0
     * @param prize    {@code null} for EGP 0
     */
    public static TournamentRequest of(Activity activity, PlayMode playMode, List<UUID> resourceIds, Integer capacity,
                                       Instant startAt, Integer durationMinutes, TournamentVisibility visibility,
                                       Money entryFee, Money prize) {
        require(activity != null, "activity");
        // FR-TRN-04, ck_tournament_play_mode: Single or Multiplayer for PlayStation, nothing for Billiards / Ping Pong
        require((activity == Activity.PLAYSTATION) == (playMode != null), "playMode");
        require(resourceIds != null && !resourceIds.isEmpty() && resourceIds.size() <= MAX_RESOURCES
                && resourceIds.stream().noneMatch(Objects::isNull)
                && new HashSet<>(resourceIds).size() == resourceIds.size(),
                "resourceIds");
        require(capacity != null && capacity >= MIN_CAPACITY && capacity <= MAX_CAPACITY, "capacity");
        require(startAt != null, "startAt");
        require(durationMinutes != null && durationMinutes >= MIN_MINUTES && durationMinutes % STEP_MINUTES == 0,
                "durationMinutes");
        require(visibility != null, "visibility");
        return new TournamentRequest(activity, playMode, resourceIds, capacity, startAt, durationMinutes, visibility,
                entryFee == null ? Money.ZERO : entryFee, prize == null ? Money.ZERO : prize);
    }

    Instant endAt() {
        return startAt.plus(durationMinutes, ChronoUnit.MINUTES);
    }

    private static void require(boolean valid, String field) {
        if (!valid) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, field);
        }
    }
}
