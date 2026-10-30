package ps.e7gz.loyalty.internal;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.loyalty.api.ReferralSettlement;

/**
 * {@code loyalty.begin_command} for both Users and {@code loyalty.reward_referral} on the command's transaction. The
 * producer decides everything itself - the reward value at the verification instant, a self-referral found now, a
 * referral already settled - so this code re-checks none of it (ADR-026).
 */
@Component
class JdbcReferralSettlement implements ReferralSettlement {

    static final String PENDING_REFERRER = """
            SELECT r.referrer_user_id FROM loyalty.referral r
             WHERE r.referred_user_id = :user AND r.status = 'PENDING'""";

    static final String BEGIN = """
            SELECT loyalty.begin_command(ARRAY[CAST(:referred AS uuid), CAST(:referrer AS uuid)], :correlation)""";

    static final String REWARD = """
            SELECT loyalty.reward_referral(r.id) FROM loyalty.referral r
             WHERE r.referred_user_id = :user AND r.status = 'PENDING'""";

    private final JdbcClient app;

    JdbcReferralSettlement(DataSource appDataSource) {
        this.app = JdbcClient.create(appDataSource);
    }

    @Override
    public Optional<UUID> pendingReferrer(UUID referredUserId) {
        return app.sql(PENDING_REFERRER).param("user", Objects.requireNonNull(referredUserId, "referredUserId"))
                .query(UUID.class).optional();
    }

    @Override
    public void settle(CommandContext command, UUID referredUserId, UUID referrerId) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(referredUserId, "referredUserId");
        Objects.requireNonNull(referrerId, "referrerId");
        command.jdbc().sql(BEGIN).param("referred", referredUserId).param("referrer", referrerId)
                .param("correlation", CorrelationId.require()).query().singleRow();
        command.jdbc().sql(REWARD).param("user", referredUserId).query().listOfRows();
    }
}
