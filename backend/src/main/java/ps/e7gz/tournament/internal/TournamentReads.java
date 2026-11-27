package ps.e7gz.tournament.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
import ps.e7gz.venue.api.VenueDiscovery;
import ps.e7gz.venue.api.VenueEligibility;

/**
 * The tournament reads of the three surfaces (E7-126, S6-E5; SPRINT-6-CONTRACT.md section 2, arch 09 sections 3.4,
 * 4.1 and 5): Discover, the Customer's own list and detail, the Venue's list and detail, the Admin's list and detail.
 * Each is one read-only command - one READ COMMITTED transaction at one database command instant, no locks, no
 * writes - so time comes from the database, never from the device or the worker (arch 04 section 2.1). Each
 * statement sees what was committed when it ran: a Join committing between two statements of one read may show in
 * one and not the other (a read decides nothing, so no lock is worth taking for it).
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
    private final VenueDiscovery discovery;

    TournamentReads(CommandRunner runner, TournamentReadStore store, VenueAccountVenues accounts,
                    VenueEligibility eligibility, VenueContacts venues, CustomerContacts customers,
                    ResourceCatalog resources, VenueDiscovery discovery) {
        this.runner = runner;
        this.store = store;
        this.accounts = accounts;
        this.eligibility = eligibility;
        this.venues = venues;
        this.customers = customers;
        this.resources = resources;
        this.discovery = discovery;
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

    /** A Resource assigned to the Tournament: its current name (FR-VEN-41) and its snapshots. */
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
        List<UUID> inArea = areaId == null ? null : venuesIn(areaId, activity);
        if (inArea != null && inArea.isEmpty()) {
            return new Page<>(List.of(), null);
        }
        return runner.run(new Read<>(context -> {
            Map<UUID, Boolean> shown = new HashMap<>();
            List<TournamentRow> found = new ArrayList<>();
            TournamentCursor.Position after = start;
            int batch = Math.max(size + 1, DISCOVER_BATCH);
            while (found.size() <= size) {
                List<TournamentRow> rows = store.discover(context, customerId, activity, inArea, after, batch);
                for (TournamentRow row : rows) {
                    after = new TournamentCursor.Position(row.startAt(), row.id());
                    if (shown.computeIfAbsent(row.venueId(), venue -> live(context, venue))) {
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
            return page(new Lookups(context), found, size, TournamentRow::startAt, Shows.CUSTOMER_LIST);
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
        return runner.run(new Read<>(context -> page(new Lookups(context), store.mine(context, customerId,
                past ? TournamentReadStore.ENDED : TournamentReadStore.NOT_ENDED, !past, after, size + 1), size,
                TournamentRow::startAt, Shows.CUSTOMER_LIST)));
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
            Lookups lookups = new Lookups(context);
            Item item = lookups.item(row, Shows.CUSTOMER_DETAIL);
            return creator
                    ? new Detail(item, inviteCode(row), lookups.participants(row))
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
        return runner.run(new Read<>(context -> page(new Lookups(context),
                store.ofVenue(context, venueId, states, !past, after, size + 1), size, TournamentRow::startAt,
                Shows.VENUE)));
    }

    /** One of the Venue's Tournaments in any state, with its participants and invite code; another's is NOT_FOUND. */
    public Detail venueDetail(UUID venueAccountId, UUID tournamentId) {
        UUID venueId = accounts.venueOf(Objects.requireNonNull(venueAccountId, "venueAccountId"));
        Objects.requireNonNull(tournamentId, "tournamentId");
        return runner.run(new Read<>(context -> {
            TournamentRow row = store.find(context, tournamentId, null).filter(found -> found.venueId().equals(venueId))
                    .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
            Lookups lookups = new Lookups(context);
            return new Detail(lookups.item(row, Shows.VENUE), inviteCode(row), lookups.participants(row));
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
            List<Item> items = new Lookups(context).items(shown, Shows.ADMIN_LIST);
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
            Lookups lookups = new Lookups(context);
            return new Detail(lookups.item(row, Shows.ADMIN_DETAIL), inviteCode(row), lookups.participants(row));
        }));
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** Live for Discover: Marketplace ACTIVE and Venue Account ENABLED, as a new Tournament needs. */
    private boolean live(CommandContext context, UUID venueId) {
        return eligibility.find(context, venueId)
                .filter(venue -> venue.marketplaceStatus() == MarketplaceStatus.ACTIVE)
                .filter(venue -> venue.accountStatus() == VenueAccountStatus.ENABLED)
                .isPresent();
    }

    /**
     * The Marketplace-ACTIVE Venues of {@code areaId} offering {@code activity}, or any activity when null - the only
     * Venues whose Tournaments Discover can show there, pushed into its SQL so a quiet Area never scans the city.
     */
    private List<UUID> venuesIn(UUID areaId, Activity activity) {
        List<Activity> activities = activity != null ? List.of(activity) : List.of(Activity.values());
        Set<UUID> ids = new LinkedHashSet<>();
        for (Activity each : activities) {
            discovery.inArea(areaId, each).forEach(venue -> ids.add(venue.venueId()));
        }
        return List.copyOf(ids);
    }

    /** The first {@code size} of {@code rows} (read with one more) and the cursor after the last one shown. */
    private static Page<Item> page(Lookups lookups, List<TournamentRow> rows, int size,
                                   Function<TournamentRow, Instant> order, Shows shows) {
        List<TournamentRow> shown = rows.stream().limit(size).toList();
        return new Page<>(lookups.items(shown, shows), rows.size() > size
                ? TournamentCursor.encode(new TournamentCursor.Position(order.apply(shown.getLast()),
                        shown.getLast().id()))
                : null);
    }

    /**
     * What a surface adds to the rows it shows. {@code lostRooms}: also the Resources lost while ACTIVE (FR-TRN-27) -
     * the Venue and the Admin see every assigned room, the ones the venue cost was computed from (FR-TRN-28); the
     * Customer sees the rooms still in play.
     */
    private record Shows(boolean venueName, boolean creator, boolean rooms, boolean lostRooms) {

        static final Shows CUSTOMER_LIST = new Shows(true, false, false, false);
        static final Shows CUSTOMER_DETAIL = new Shows(true, false, true, false);
        static final Shows VENUE = new Shows(false, true, true, true);
        static final Shows ADMIN_LIST = new Shows(true, true, false, false);
        static final Shows ADMIN_DETAIL = new Shows(true, true, true, true);
    }

    /**
     * The other modules' facts one read needs - Venue names, usernames, Resource names - each read once per read and
     * kept for the rest of it, and the rooms of a whole page in one query. The phone of a Contact never leaves here
     * (arch 09 section 1.1).
     */
    private final class Lookups {

        private final CommandContext context;
        private final Map<UUID, String> venueNames = new HashMap<>();
        private final Map<UUID, String> usernames = new HashMap<>();
        private final Map<UUID, String> resourceNames = new HashMap<>();

        Lookups(CommandContext context) {
            this.context = context;
        }

        List<Item> items(List<TournamentRow> rows, Shows shows) {
            Map<UUID, List<Room>> rooms = shows.rooms() ? rooms(rows, shows.lostRooms()) : Map.of();
            return rows.stream().map(row -> new Item(row,
                    shows.venueName() ? venueName(row.venueId()) : null,
                    shows.creator() && row.creatorUserId() != null ? username(row.creatorUserId()) : null,
                    rooms.getOrDefault(row.id(), List.of()))).toList();
        }

        Item item(TournamentRow row, Shows shows) {
            return items(List.of(row), shows).getFirst();
        }

        List<Participant> participants(TournamentRow row) {
            return store.participants(context, row.id()).stream()
                    .map(p -> new Participant(p.userId(), username(p.userId()), p.status(), p.joinedAt()))
                    .toList();
        }

        private Map<UUID, List<Room>> rooms(List<TournamentRow> rows, boolean withRemoved) {
            Map<UUID, List<Room>> byTournament = new HashMap<>();
            for (TournamentReadStore.RoomRow room : store.rooms(context,
                    rows.stream().map(TournamentRow::id).toList(), withRemoved)) {
                byTournament.computeIfAbsent(room.tournamentId(), id -> new ArrayList<>()).add(new Room(
                        room.resourceId(), resourceName(room.resourceId()), room.consoleType(), room.hourlyPrice()));
            }
            byTournament.values().forEach(list ->
                    list.sort(Comparator.comparing(Room::name).thenComparing(Room::resourceId)));
            return byTournament;
        }

        private String venueName(UUID venueId) {
            return venueNames.computeIfAbsent(venueId, id -> venues.find(context, id)
                    .map(VenueContacts.Contact::name)
                    .orElseThrow(() -> new IllegalStateException("Venue " + id + " vanished")));
        }

        private String username(UUID userId) {
            return usernames.computeIfAbsent(userId, id -> customers.find(context, id)
                    .map(CustomerContacts.Contact::username)
                    .orElseThrow(() -> new IllegalStateException("User " + id + " vanished")));
        }

        private String resourceName(UUID resourceId) {
            return resourceNames.computeIfAbsent(resourceId, id -> resources.name(context, id)
                    .orElseThrow(() -> new IllegalStateException("Resource " + id + " vanished")));
        }
    }

    /** A PRIVATE Tournament's code once the Venue accepted it (FR-TRN-06, FR-TRN-07); null otherwise. */
    private static String inviteCode(TournamentRow row) {
        return row.visibility() == TournamentVisibility.PRIVATE && row.accepted() ? row.inviteCode() : null;
    }

    /** A read-only command: the database's command instant, no locks, no writes. */
    private record Read<R>(Function<CommandContext, R> body) implements Command<R> {

        /**
         * Nominal: a read starts no marketplace activity and honours no commitment, so no venue-eligibility rule
         * applies to it; the runner only requires a category. B is what every other read in the code base declares
         * (VenueBookings, TournamentLookup).
         */
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
