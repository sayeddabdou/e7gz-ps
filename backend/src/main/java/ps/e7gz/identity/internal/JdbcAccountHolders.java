package ps.e7gz.identity.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.persistence.DataSourceConfig;
import ps.e7gz.loyalty.api.AccountHolders;

/**
 * {@code iam.user_account} for the Platform Admin's loyalty pages (E7-66), read on the admin connection like every
 * admin page. {@code ck_user_anonymised_pii}: an anonymised account's {@code phone_e164} is
 * {@code 'anonymised:' || id}, never a phone, so it is answered as none.
 */
@Component
class JdbcAccountHolders implements AccountHolders {

    static final String COLUMNS = """
            u.id, u.full_name, u.username, CASE WHEN u.anonymised_at IS NULL THEN u.phone_e164 END AS phone,
            u.anonymised_at IS NOT NULL AS deleted""";

    /** {@link CustomerSearch}, the one search of every admin list. */
    static final String SEARCH = "SELECT u.id FROM iam.user_account u WHERE " + CustomerSearch.MATCHES;

    private final JdbcClient jdbc;

    JdbcAccountHolders(@Qualifier(DataSourceConfig.ADMIN_DATA_SOURCE) DataSource adminDataSource) {
        this.jdbc = JdbcClient.create(adminDataSource);
    }

    @Override
    public Map<UUID, Holder> find(Collection<UUID> userIds) {
        Objects.requireNonNull(userIds, "userIds");
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Holder> holders = new LinkedHashMap<>();
        jdbc.sql("SELECT " + COLUMNS + " FROM iam.user_account u WHERE u.id = ANY(CAST(:ids AS uuid[]))")
                .param("ids", userIds.stream().distinct().toArray(UUID[]::new))
                .query((rs, n) -> holder(rs))
                .list()
                .forEach(holder -> holders.put(holder.userId(), holder));
        return holders;
    }

    @Override
    public Optional<Holder> find(CommandContext command, UUID userId) {
        Objects.requireNonNull(command, "command");
        return command.jdbc().sql("SELECT " + COLUMNS + " FROM iam.user_account u WHERE u.id = :id")
                .param("id", Objects.requireNonNull(userId, "userId"))
                .query((rs, n) -> holder(rs))
                .optional();
    }

    @Override
    public Set<UUID> search(String q) {
        return CustomerSearch.of(q)
                .map(terms -> (Set<UUID>) new LinkedHashSet<>(jdbc.sql(SEARCH)
                        .param("q", terms.text()).param("phone", terms.phone())
                        .query(UUID.class)
                        .list()))
                .orElse(Set.of());
    }

    private static Holder holder(ResultSet rs) throws SQLException {
        return new Holder(rs.getObject("id", UUID.class), rs.getString("full_name"), rs.getString("username"),
                rs.getString("phone"), rs.getBoolean("deleted"));
    }
}
