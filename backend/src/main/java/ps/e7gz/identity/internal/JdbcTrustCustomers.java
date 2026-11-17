package ps.e7gz.identity.internal;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ps.e7gz.trust.api.TrustCustomers;

/**
 * Identity's side of {@link TrustCustomers} (E7-72): the Customers Trust's admin pages name, from
 * {@code iam.user_account} on the app connection. An anonymised account (FR-ACC-10) is deleted, with its blanked name.
 */
@Component
class JdbcTrustCustomers implements TrustCustomers {

    static final String CUSTOMERS = """
            SELECT u.id, u.full_name, u.username, u.anonymised_at IS NOT NULL AS deleted
              FROM iam.user_account u
             WHERE u.id = ANY (CAST(:ids AS uuid[]))
            """;

    private final JdbcClient jdbc;

    JdbcTrustCustomers(DataSource appDataSource) {
        this.jdbc = JdbcClient.create(appDataSource);
    }

    @Override
    public Map<UUID, Customer> of(Collection<UUID> userIds) {
        Objects.requireNonNull(userIds, "userIds");
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Customer> customers = new LinkedHashMap<>();
        jdbc.sql(CUSTOMERS)
                .param("ids", userIds.stream().distinct().toArray(UUID[]::new))
                .query((rs, n) -> new Customer(rs.getObject("id", UUID.class), rs.getString("full_name"),
                        rs.getString("username"), rs.getBoolean("deleted")))
                .list()
                .forEach(customer -> customers.put(customer.userId(), customer));
        return customers;
    }
}
