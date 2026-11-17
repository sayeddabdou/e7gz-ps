package ps.e7gz.trust.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.scheduling.api.CommandJobHandler;
import ps.e7gz.scheduling.api.JobOutcome;
import ps.e7gz.scheduling.api.ScheduledJob;
import ps.e7gz.trust.api.TrustReconciler;

/**
 * T10, {@code BAN_EXPIRY:{banId}} (arch 08 section 1; FR-TRS-23; E7-71), scheduled at the Ban's {@code endsAt} by its
 * activation. Fired by the deadline worker in one command under the User's lock: a Ban still ACTIVE whose end has come
 * is EXPIRED ({@link JdbcBanReconciliation#expireDue}) and the User reconciled, so the projection no longer points at
 * it; a Ban an extension moved on is rescheduled to its new end; a Ban already ended otherwise (expired, corrected,
 * lifted) leaves nothing to do. Expiry is effective at {@code endsAt}, not at the fire instant (arch 08 section 1: a
 * late worker changes no Product boundary). Tournament participant restoration is the database's own cascade on the
 * same transition ({@code tr_ban_tournament_participant_restore}).
 */
@Component
@Profile("worker")
class BanExpiryHandler implements CommandJobHandler {

    private static final String BAN = "SELECT user_id, status, ends_at FROM trust.ban_instance WHERE id = :id";

    private final JdbcClient jdbc;
    private final JdbcBanReconciliation bans;
    private final TrustReconciler reconciler;

    BanExpiryHandler(DataSource appDataSource, JdbcBanReconciliation bans, TrustReconciler reconciler) {
        this.jdbc = JdbcClient.create(appDataSource);
        this.bans = bans;
        this.reconciler = reconciler;
    }

    @Override
    public String type() {
        return JdbcBanReconciliation.BAN_EXPIRY;
    }

    /** The end of an existing restriction, not new marketplace activity (ADR-022). */
    @Override
    public EligibilityCategory eligibilityCategory() {
        return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
    }

    /** The banned User - with their Bookings and Bans, as every Trust write needs ({@code trust.command_now}). */
    @Override
    public LockSet lockSet(ScheduledJob job) {
        return jdbc.sql(BAN).param("id", job.subjectId())
                .query((rs, n) -> rs.getObject("user_id", UUID.class)).optional()
                .map(user -> LockSet.builder().users(user).build())
                .orElse(LockSet.empty());
    }

    @Override
    public JobOutcome fire(CommandContext context, ScheduledJob job) {
        Ban ban = context.jdbc().sql(BAN).param("id", job.subjectId())
                .query((rs, n) -> new Ban(rs.getObject("user_id", UUID.class), rs.getString("status"),
                        rs.getObject("ends_at", OffsetDateTime.class).toInstant()))
                .optional().orElse(null);
        if (ban == null || !"ACTIVE".equals(ban.status())) {
            return JobOutcome.superseded("Ban no longer ACTIVE");
        }
        if (!due(context.commandNow(), ban.endsAt())) {
            return JobOutcome.rescheduleAt(ban.endsAt());                       // extended since it was scheduled
        }
        bans.expireDue(context, ban.userId());
        reconciler.reconcile(context, ban.userId());
        return JobOutcome.done();
    }

    /**
     * FR-TRS-23, half-open as every deadline (FR-AVL-15): the Ban is in force in {@code [activatedAt, endsAt)}, so it
     * expires at {@code endsAt} itself - the instant {@code trust.guard_ban_instance} first accepts EXPIRED.
     */
    static boolean due(Instant now, Instant endsAt) {
        return !now.isBefore(endsAt);
    }

    private record Ban(UUID userId, String status, Instant endsAt) { }
}
