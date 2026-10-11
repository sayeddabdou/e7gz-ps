package ps.e7gz.identity.internal;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.identity.api.CustomerContacts;

/**
 * {@code iam.user_account} on the command's transaction. {@code ck_user_anonymised_pii}: an anonymised account's
 * {@code phone_e164} is {@code 'anonymised:' || id}, never a phone, so it is answered as none.
 */
@Component
class JdbcCustomerContacts implements CustomerContacts {

    static final String FIND = """
            SELECT u.id, u.username, CASE WHEN u.anonymised_at IS NULL THEN u.phone_e164 END AS phone
              FROM iam.user_account u
             WHERE u.id = :id
            """;

    /**
     * The username half of {@link CustomerAccounts}' login match, on {@code ux_user_username_normalized} (unique among
     * accounts not anonymised, so at most one row); the phone half is deliberately absent (PO decision 11 Oct 2026,
     * E7-49, D3 amendment).
     */
    static final String FIND_BY_USERNAME = """
            SELECT u.id
              FROM iam.user_account u
             WHERE u.anonymised_at IS NULL
               AND lower(btrim(u.username)) = lower(btrim(:username))
            """;

    @Override
    public Optional<Contact> find(CommandContext command, UUID userId) {
        Objects.requireNonNull(command, "command");
        return command.jdbc().sql(FIND)
                .param("id", Objects.requireNonNull(userId, "userId"))
                .query((rs, n) -> new Contact(rs.getObject("id", UUID.class), rs.getString("username"),
                        rs.getString("phone")))
                .optional();
    }

    @Override
    public Optional<UUID> findByUsername(CommandContext command, String username) {
        Objects.requireNonNull(command, "command");
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        return command.jdbc().sql(FIND_BY_USERNAME)
                .param("username", username)
                .query(UUID.class)
                .optional();
    }
}
