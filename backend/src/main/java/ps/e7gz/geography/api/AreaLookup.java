package ps.e7gz.geography.api;

import java.util.UUID;

/** Read access to {@code geo.area} for other modules (arch 03 section 4). */
public interface AreaLookup {

    /** True when the area exists, active or not. Areas are never deleted (FR-VEN-12). */
    boolean exists(UUID areaId);
}
