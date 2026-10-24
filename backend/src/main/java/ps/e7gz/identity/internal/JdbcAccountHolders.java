package ps.e7gz.identity.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
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

    /**
     * The search of the Sprint 4 contract (E7-65 section 2, "Lists"): a substring of the name, username or email - with
     * {@code strpos}, so {@code %} and {@code _} in {@code q} are plain characters - or the exact E.164 phone.
     */
    static final String SEARCH = """
            SELECT u.id
              FROM iam.user_account u
             WHERE strpos(lower(u.full_name), :q) > 0
                OR strpos(lower(u.username), :q) > 0
                OR strpos(lower(u.email), :q) > 0
                OR u.phone_e164 = CAST(:phone AS text)
            """;

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
        if (q == null || q.isBlank()) {
            return Set.of();
        }
        String text = q.strip();
        return new LinkedHashSet<>(jdbc.sql(SEARCH)
                .param("q", text.toLowerCase(Locale.ROOT))
                .param("phone", PhoneNumbers.toE164(text).orElse(null))
                .query(UUID.class)
                .list());
    }

    private static Holder holder(ResultSet rs) throws SQLException {
        return new Holder(rs.getObject("id", UUID.class), rs.getString("full_name"), rs.getString("username"),
                rs.getString("phone"), rs.getBoolean("deleted"));
    }
}
