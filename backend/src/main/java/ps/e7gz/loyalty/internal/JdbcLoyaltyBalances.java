package ps.e7gz.loyalty.internal;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ps.e7gz.common.money.Money;
import ps.e7gz.loyalty.api.LoyaltyBalances;

/** Both balances in one read, outside any command on the app connection. */
@Component
class JdbcLoyaltyBalances implements LoyaltyBalances {

    static final String BALANCES = """
            SELECT p.balance AS points, w.balance AS wallet
              FROM loyalty.points_account p
              JOIN loyalty.wallet_account w ON w.user_id = p.user_id
             WHERE p.user_id = :user
            """;

    private final JdbcClient jdbc;

    JdbcLoyaltyBalances(DataSource appDataSource) {
        this.jdbc = JdbcClient.create(appDataSource);
    }

    @Override
    public Optional<Balances> of(UUID userId) {
        return jdbc.sql(BALANCES).param("user", Objects.requireNonNull(userId, "userId"))
                .query((rs, n) -> new Balances(rs.getInt("points"), Money.of(rs.getBigDecimal("wallet"))))
                .optional();
    }
}
