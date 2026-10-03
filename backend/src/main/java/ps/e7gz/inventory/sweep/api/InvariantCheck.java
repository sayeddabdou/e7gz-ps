package ps.e7gz.inventory.sweep.api;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * One explicit invariant of the nightly integrity sweep (arch 11 section 4). A check is a detector only: it reads
 * through the sweep's read-only snapshot and returns what it found; it never writes, releases or repairs anything.
 * Further checks (trust replay, sellable gap, Tournament integrity) are added as more beans of this type by the tasks
 * that own those domains.
 */
public interface InvariantCheck {

    /** Stable code of the invariant, unique across checks, for example {@code NFR_28_ACTIVE_RESERVATION_OVERLAP}. */
    String invariant();

    InvariantSeverity severity();

    /**
     * Every violation present in the snapshot; empty when the invariant holds.
     *
     * @param snapshot runs SQL in the sweep's read-only transaction (writes are refused by the database)
     */
    List<InvariantViolation> detect(JdbcClient snapshot);
}
