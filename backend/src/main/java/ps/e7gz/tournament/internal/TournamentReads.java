package ps.e7gz.tournament.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.codes.MarketplaceStatus;
import ps.e7gz.common.codes.TournamentHostType;
import ps.e7gz.common.codes.TournamentParticipantStatus;
import ps.e7gz.common.codes.TournamentStatus;
import ps.e7gz.common.codes.TournamentVisibility;
import ps.e7gz.common.codes.VenueAccountStatus;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.common.money.Money;
import ps.e7gz.identity.api.CustomerContacts;
import ps.e7gz.resource.api.ResourceCatalog;
import ps.e7gz.venue.api.VenueAccountVenues;
import ps.e7gz.venue.api.VenueContacts;
import ps.e7gz.venue.api.VenueEligibility;

/**
 * The tournament reads of the three surfaces (E7-126, S6-E5; SPRINT-6-CONTRACT.md section 2, arch 09 sections 3.4,
 * 4.1 and 5): Discover, the Customer's own list and detail, the Venue's list and detail, the Admin's list and detail.
 * Each is one read-only command - one snapshot and the database's command instant, no locks, no writes - so time
 * comes from the database, never from the device or the worker (arch 04 section 2.1).
 *
 * <p>Who sees what (FR-TRN-06, FR-TRN-07, FR-TRN-12): a Tournament a caller may not see is NOT_FOUND, never
 * FORBIDDEN, so its existence is not revealed. Usernames, Venue names and Resource names are read through their
 * modules' APIs; no phone, no email and no INELIGIBLE cause ever leaves this class (arch 09 sections 1.1 and 3.4,
 * PCR-001).
 */
@Component
public class TournamentReads {

    /** Discover reads candidates in batches of at least this size while the Venue filter drops rows. */
    private static final int DISCOVER_BATCH = 50;

    private final CommandRunner runner;
    private final TournamentReadStore store;
    private final VenueAccountVenues accounts;
    private final VenueEligibility eligibility;
    private final VenueContacts venues;
    private final CustomerContacts customers;
    private final ResourceCatalog resources;

    TournamentReads(CommandRunner runner, TournamentReadStore store, VenueAccountVenues accounts,
                    VenueEligibility eligibility, VenueContacts venues, CustomerContacts customers,
                    ResourceCatalog resources) {
        this.runner = runner;
        this.store = store;
        this.accounts = accounts;
        this.eligibility = eligibility;
        this.venues = venues;
        this.customers = customers;
        this.resources = resources;
    }

    // ------------------------------------------------------------------------------------------------ views

    /** A page; {@code nextCursor} is null on the last page. */
    public record Page<T>(List<T> items, String nextCursor) {

        public Page {
            items = List.copyOf(items);
        }
    }

    /**
     * A Tournament with what the surfaces add to its row. {@code venueName} and {@code creatorUsername} are null where
     * the surface does not show them; {@code rooms} is empty for a list that shows none.
     */
    public record Item(TournamentRow row, String venueName, String creatorUsername, List<Room> rooms) {

        public Item {
            Objects.requireNonNull(row, "row");
            rooms = List.copyOf(rooms);
        }

        public Instant approvalExpiresAt() {
            // the answer deadline only while the request is pending (FR-TRN-10)
            return row.status() == TournamentStatus.PENDING_VENUE_APPROVAL ? row.approvalExpiresAt() : null;
        }
    }

    /** A Resource still in the Tournament: its current name (FR-VEN-41) and its snapshots. */
    public record Room(UUID resourceId, String name, ConsoleType consoleType, Money hourlyPrice) {
    }

    /** A participant: username and state only (arch 09 section 1.1); INELIGIBLE without its cause. */
    public record Participant(UUID userId, String username, TournamentParticipantStatus status, Instant joinedAt) {
    }

    /**
     * A detail. {@code inviteCode} and {@code participants} are null where the caller may not see them (the Customer
     * route, FR-TRN-06, FR-TRN-07, FR-TRN-12).
     */
    public record Detail(Item item, String inviteCode, List<Participant> participants) {
    }

    // ------------------------------------------------------------------------------------------------ Customer

    /**
     * Public Discover (FR-TRN-06, FR-TRN-07): PUBLIC, WAITING_FOR_PLAYERS or CONFIRMED, {@code now < startAt}, at a
     * Venue that is live - Marketplace ACTIVE and Venue Account ENABLED, what a new Tournament needs - in
     * {@code areaId} when given. Soonest first. A full Tournament stays listed.
     */
    public Page<Item> discover(UUID customerId, UUID areaId, Activity activity, String cursor, Integer limit) {
        Objects.requireNonNull(customerId, "customerId");
        TournamentCursor.Position start = TournamentCursor.decode(cursor);
        int size = TournamentCursor.size(limit);
        return runner.run(new Read<>(context -> {
            Map<UUID, Boolean> shown = new HashMap<>();
            List<TournamentRow> found = new ArrayList<>();
            TournamentCursor.Position after = start;
            int batch = Math.max(size + 1, DISCOVER_BATCH);
            while (found.size() <= size) {
                List<TournamentRow> rows = store.discover(context, customerId, activity, after, batch);
                for (TournamentRow row : rows) {
                    after = new TournamentCursor.Position(row.startAt(), row.id());
                    if (shown.computeIfAbsent(row.venueId(), venue -> live(context, venue, areaId))) {
                        found.add(row);
                        if (found.size() > size) {
                            break;
                        }
                    }
                }
                if (rows.size() < batch) {
                    break;
                }
            }
            return page(context, found, size, TournamentRow::startAt, false);
        }));
    }

    /**
     * The Tournaments the Customer created or has a participation in (PO decision 26 Nov 2026, E7-88):
     * {@code ended=false} (default) not yet ended, soonest first; {@code ended=true} ended, newest first.
     */
    public Page<Item> mine(UUID customerId, Boolean ended, String cursor, Integer limit) {
        Objects.requireNonNull(customerId, "customerId");
        boolean past = Boolean.TRUE.equals(ended);
        TournamentCursor.Position after = TournamentCursor.decode(cursor);
        int size = TournamentCursor.size(limit);
        return runner.run(new Read<>(context -> page(context, store.mine(context, customerId,
                past ? TournamentReadStore.ENDED : TournamentReadStore.NOT_ENDED, !past, after, size + 1), size,
                TournamentRow::startAt, false)));
    }

    /**
     * One Tournament as the Customer may see it: its creator and anyone with a participation, in any state; any
     * Customer for a PUBLIC Tournament the Venue has accepted. Otherwise NOT_FOUND (FR-TRN-07). The invite code (of a
     * PRIVATE Tournament once accepted) and the participants go to the creator only.
     */
    public Detail customerDetail(UUID customerId, UUID tournamentId) {
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(tournamentId, "tournamentId");
        return runner.run(new Read<>(context -> {
            TournamentRow row = store.find(context, tournamentId, customerId)
                    .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
            boolean creator = customerId.equals(row.creatorUserId());
            boolean visible = creator || row.myStatus() != null
                    || (row.visibility() == TournamentVisibility.PUBLIC && row.accepted());
            if (!visible) {
                throw new ApiException(ApiErrorCode.NOT_FOUND);
            }
            Item item = item(context, row, true, false, true);
            return creator
                    ? new Detail(item, inviteCode(row), participants(context, row))
                    : new Detail(item, null, null);
        }));
    }

    // ------------------------------------------------------------------------------------------------ Venue

    /**
     * The signed-in account's Venue's Tournaments (arch 09 section 4.1): {@code ended=false} (default) not yet ended,
     * soonest first; {@code ended=true} ended, newest first. {@code status} narrows to one state; without
     * {@code ended}, the list follows that state's side, and a state of the other side than an explicit
     * {@code ended} gives an empty list.
     */
    public Page<Item> venueList(UUID venueAccountId, Boolean ended, TournamentStatus status, String cursor,
                                Integer limit) {
        UUID venueId = accounts.venueOf(Objects.requireNonNull(venueAccountId, "venueAccountId"));
        boolean past = ended != null ? ended : status != null && TournamentReadStore.ENDED.contains(status.name());
        List<String> side = past ? TournamentReadStore.ENDED : TournamentReadStore.NOT_ENDED;
        List<String> states = status == null ? side
                : side.contains(status.name()) ? List.of(status.name()) : List.of();
        TournamentCursor.Position after = TournamentCursor.decode(cursor);
        int size = TournamentCursor.size(limit);
        if (states.isEmpty()) {
            return new Page<>(List.of(), null);
        }
        return runner.run(new Read<>(context -> page(context,
                store.ofVenue(context, venueId, states, !past, after, size + 1), size, TournamentRow::startAt, true)));
    }

    /** One of the Venue's Tournaments in any state, with its participants and invite code; another's is NOT_FOUND. */
    public Detail venueDetail(UUID venueAccountId, UUID tournamentId) {
        UUID venueId = accounts.venueOf(Objects.requireNonNull(venueAccountId, "venueAccountId"));
        Objects.requireNonNull(tournamentId, "tournamentId");
        return runner.run(new Read<>(context -> {
            TournamentRow row = store.find(context, tournamentId, null).filter(found -> found.venueId().equals(venueId))
                    .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
            return new Detail(item(context, row, false, true, true), inviteCode(row), participants(context, row));
        }));
    }

    // ------------------------------------------------------------------------------------------------ Admin

    /** Every Tournament, newest {@code createdAt} first (arch 09 section 5, FR-ADM-08); each filter optional. */
    public Page<Item> adminList(UUID venueId, TournamentStatus status, TournamentHostType hostType, String cursor,
                                Integer limit) {
        TournamentCursor.Position after = TournamentCursor.decode(cursor);
        int size = TournamentCursor.size(limit);
        return runner.run(new Read<>(context -> {
            List<TournamentRow> rows = store.all(context, venueId, status, hostType, after, size + 1);
            List<TournamentRow> shown = rows.stream().limit(size).toList();
            List<Item> items = shown.stream().map(row -> item(context, row, true, true, false)).toList();
            return new Page<>(items, rows.size() > size
                    ? TournamentCursor.encode(new TournamentCursor.Position(shown.getLast().createdAt(),
                            shown.getLast().id()))
                    : null);
        }));
    }

    /** Any Tournament in any state, with its participants (FR-ADM-08). */
    public Detail adminDetail(UUID tournamentId) {
        Objects.requireNonNull(tournamentId, "tournamentId");
        return runner.run(new Read<>(context -> {
            TournamentRow row = store.find(context, tournamentId, null)
                    .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
            return new Detail(item(context, row, true, true, true), inviteCode(row), participants(context, row));
        }));
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** Live for Discover, and in {@code areaId} when one is asked. */
    private boolean live(CommandContext context, UUID venueId, UUID areaId) {
        return eligibility.find(context, venueId)
                .filter(venue -> venue.marketplaceStatus() == MarketplaceStatus.ACTIVE)
                .filter(venue -> venue.accountStatus() == VenueAccountStatus.ENABLED)
                .filter(venue -> areaId == null || areaId.equals(venue.areaId()))
                .isPresent();
    }

    /** The first {@code size} of {@code rows} (read with one more) and the cursor after the last one shown. */
    private Page<Item> page(CommandContext context, List<TournamentRow> rows, int size, Function<TournamentRow, Instant> order,
                            boolean venueSurface) {
        List<TournamentRow> shown = rows.stream().limit(size).toList();
        List<Item> items = shown.stream()
                .map(row -> item(context, row, !venueSurface, venueSurface, venueSurface)).toList();
        return new Page<>(items, rows.size() > size
                ? TournamentCursor.encode(new TournamentCursor.Position(order.apply(shown.getLast()),
                        shown.getLast().id()))
                : null);
    }

    private Item item(CommandContext context, TournamentRow row, boolean withVenueName, boolean withCreator,
                      boolean withRooms) {
        String venueName = withVenueName ? venues.find(context, row.venueId())
                .map(VenueContacts.Contact::name)
                .orElseThrow(() -> new IllegalStateException("Venue " + row.venueId() + " vanished")) : null;
        String creator = withCreator && row.creatorUserId() != null ? username(context, row.creatorUserId()) : null;
        List<Room> rooms = withRooms ? rooms(context, row.id()) : List.of();
        return new Item(row, venueName, creator, rooms);
    }

    private List<Room> rooms(CommandContext context, UUID tournamentId) {
        return store.rooms(context, List.of(tournamentId)).stream()
                .map(room -> new Room(room.resourceId(), resources.name(context, room.resourceId())
                        .orElseThrow(() -> new IllegalStateException("Resource " + room.resourceId() + " vanished")),
                        room.consoleType(), room.hourlyPrice()))
                .sorted(Comparator.comparing(Room::name).thenComparing(Room::resourceId))
                .toList();
    }

    private List<Participant> participants(CommandContext context, TournamentRow row) {
        return store.participants(context, row.id()).stream()
                .map(p -> new Participant(p.userId(), username(context, p.userId()), p.status(), p.joinedAt()))
                .toList();
    }

    private String username(CommandContext context, UUID userId) {
        // the stored username only: the phone of the Contact never leaves this method (arch 09 section 1.1)
        Optional<CustomerContacts.Contact> contact = customers.find(context, userId);
        return contact.map(CustomerContacts.Contact::username)
                .orElseThrow(() -> new IllegalStateException("User " + userId + " vanished"));
    }

    /** A PRIVATE Tournament's code once the Venue accepted it (FR-TRN-06, FR-TRN-07); null otherwise. */
    private static String inviteCode(TournamentRow row) {
        return row.visibility() == TournamentVisibility.PRIVATE && row.accepted() ? row.inviteCode() : null;
    }

    /** A read-only command: one snapshot and the database's instant, no locks. */
    private record Read<R>(Function<CommandContext, R> body) implements Command<R> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.empty());
        }

        @Override
        public R execute(CommandContext context) {
            return body.apply(context);
        }
    }
}
