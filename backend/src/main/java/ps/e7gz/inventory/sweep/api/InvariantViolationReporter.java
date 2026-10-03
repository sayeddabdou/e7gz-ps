package ps.e7gz.inventory.sweep.api;

/**
 * Where sweep violations go after the read-only snapshot has closed. The sweep itself only logs them; the admin-alert
 * reporter ({@code admin.admin_alert}, E7-56) is a bean of this type that the Platform Admin side provides, so the
 * sweep never writes Platform Administration tables itself.
 */
public interface InvariantViolationReporter {

    void report(InvariantViolation violation);
}
