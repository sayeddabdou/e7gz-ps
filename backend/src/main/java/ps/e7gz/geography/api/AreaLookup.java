package ps.e7gz.geography.api;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Read access to {@code geo.area} for other modules (arch 03 section 4). */
public interface AreaLookup {

    /** True when the area exists, active or not. Areas are never deleted (FR-VEN-12). */
    boolean exists(UUID areaId);

    /** True when the area exists and is active (arch 03 section 4 {@code AreaQuery.isActive}). */
    boolean isActive(UUID areaId);

    /** The area's name; empty when there is no such area (E7-24: the venue's settings show it). */
    Optional<String> name(UUID areaId);

    /**
     * The names of the given areas, active or not, in one read (E7-34: customer venue cards and details show the area,
     * FR-DIS-03, FR-DIS-11). Unknown ids are left out.
     */
    Map<UUID, AreaName> names(Collection<UUID> areaIds);

    /** An area's name and its optional Arabic name. */
    record AreaName(UUID id, String name, String nameAr) {

        public AreaName {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
        }
    }
}
