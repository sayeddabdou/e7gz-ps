package ps.e7gz.common.command;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The rows a command names up front: the arguments of {@code tournament.begin_command(users[], venues[], resources[],
 * tournaments[], new_tournaments[])} (arch 04 section 2.1). The database discovers the rest of the lock set from these and
 * takes every lock in its fixed order; the application never locks rows itself.
 *
 * <p>Ids are de-duplicated and sorted; {@code null} ids are refused (the database refuses them too).
 */
public record LockSet(List<UUID> users, List<UUID> venues, List<UUID> resources, List<UUID> tournaments,
                      List<UUID> newTournaments) {

    private static final LockSet EMPTY = builder().build();

    public LockSet {
        users = sorted(users);
        venues = sorted(venues);
        resources = sorted(resources);
        tournaments = sorted(tournaments);
        newTournaments = sorted(newTournaments);
        if (tournaments.stream().anyMatch(newTournaments::contains)) {
            throw new IllegalArgumentException("Existing and new Tournament ids must be disjoint");
        }
    }

    /** A command that names no rows (the database still opens the command context and fixes the instant). */
    public static LockSet empty() {
        return EMPTY;
    }

    public static Builder builder() {
        return new Builder();
    }

    private static List<UUID> sorted(Collection<UUID> ids) {
        Objects.requireNonNull(ids, "ids");
        ids.forEach(id -> Objects.requireNonNull(id, "lock set ids must not be null"));
        return List.copyOf(new TreeSet<>(ids));
    }

    public static final class Builder {

        private final TreeSet<UUID> users = new TreeSet<>();
        private final TreeSet<UUID> venues = new TreeSet<>();
        private final TreeSet<UUID> resources = new TreeSet<>();
        private final TreeSet<UUID> tournaments = new TreeSet<>();
        private final TreeSet<UUID> newTournaments = new TreeSet<>();

        private Builder() {
        }

        public Builder users(UUID... ids) {
            return add(users, List.of(ids));
        }

        public Builder users(Collection<UUID> ids) {
            return add(users, ids);
        }

        public Builder venues(UUID... ids) {
            return add(venues, List.of(ids));
        }

        public Builder venues(Collection<UUID> ids) {
            return add(venues, ids);
        }

        public Builder resources(UUID... ids) {
            return add(resources, List.of(ids));
        }

        public Builder resources(Collection<UUID> ids) {
            return add(resources, ids);
        }

        public Builder tournaments(UUID... ids) {
            return add(tournaments, List.of(ids));
        }

        public Builder tournaments(Collection<UUID> ids) {
            return add(tournaments, ids);
        }

        /** Preallocated ids of Tournaments this command creates. */
        public Builder newTournaments(UUID... ids) {
            return add(newTournaments, List.of(ids));
        }

        public Builder newTournaments(Collection<UUID> ids) {
            return add(newTournaments, ids);
        }

        public LockSet build() {
            return new LockSet(List.copyOf(users), List.copyOf(venues), List.copyOf(resources),
                    List.copyOf(tournaments), List.copyOf(newTournaments));
        }

        private Builder add(TreeSet<UUID> target, Collection<UUID> ids) {
            ids.forEach(id -> target.add(Objects.requireNonNull(id, "lock set ids must not be null")));
            return this;
        }
    }
}
