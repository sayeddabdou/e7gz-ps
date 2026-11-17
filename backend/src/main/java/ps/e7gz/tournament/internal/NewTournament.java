package ps.e7gz.tournament.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.codes.TournamentVisibility;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.money.Money;

/**
 * What creating a Tournament takes (E7-73): only the facts a creator chooses. The database derives the rest at insert -
 * {@code created_at} (the command instant), {@code end_at}, {@code checkpoint_at}, {@code approval_expires_at} and the
 * first status - and {@link Tournaments#create} snapshots the prices and the Creation Fee from the database's own
 * functions, so nothing here can disagree with the guards (arch 04 section 4.4).
 *
 * <p>Only {@code null}s are refused here. Capacity 8-32, duration of at least 60 minutes in 30-minute steps, the
 * creation cutoff {@code startAt - 2h}, 1-4 Resources of the Venue and Activity, one Operating Session: the database
 * checks each of them and refuses the command.
 *
 * @param id                 preallocated, so the creating command can name it in {@link LockSet#newTournaments()}
 * @param operatingSessionId the Venue's Operating Session the whole interval fits in; {@code businessDate} is its day
 * @param resourceIds        the 1-4 Resources, each held for {@code [startAt, startAt + nominalMinutes)}
 */
record NewTournament(UUID id, UUID venueId, Host host, Activity activity, PlayMode playMode, int capacity,
                     Instant startAt, int nominalMinutes, TournamentVisibility visibility, String inviteCode,
                     Money entryFee, Money prize, UUID operatingSessionId, LocalDate businessDate,
                     List<UUID> resourceIds) {

    NewTournament {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(venueId, "venueId");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(activity, "activity");
        Objects.requireNonNull(startAt, "startAt");
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(entryFee, "entryFee");
        Objects.requireNonNull(prize, "prize");
        Objects.requireNonNull(operatingSessionId, "operatingSessionId");
        Objects.requireNonNull(businessDate, "businessDate");
        resourceIds = List.copyOf(resourceIds);
    }

    /** The rows the creating command names up front: the Creator, the Venue, the Resources and the new id. */
    LockSet lockSet() {
        LockSet.Builder locks = LockSet.builder().venues(venueId).resources(resourceIds).newTournaments(id);
        if (host instanceof Host.Player player) {
            locks.users(player.creatorUserId());
        }
        return locks.build();
    }

    BigDecimal entryFeeAmount() {
        return entryFee.amount();
    }

    BigDecimal prizeAmount() {
        return prize.amount();
    }

    /** Who hosts it, and so who is the actor of its first history row (arch 06 section 3.1). */
    sealed interface Host {

        /** Player-hosted: created {@code PENDING_VENUE_APPROVAL} with Approval Holds. */
        record Player(UUID creatorUserId, boolean creatorParticipates) implements Host {

            public Player {
                Objects.requireNonNull(creatorUserId, "creatorUserId");
            }
        }

        /** Venue-hosted: created {@code WAITING_FOR_PLAYERS} with standard Tournament Holds, by a Venue Account. */
        record Venue(UUID venueAccountId) implements Host {

            public Venue {
                Objects.requireNonNull(venueAccountId, "venueAccountId");
            }
        }
    }
}
