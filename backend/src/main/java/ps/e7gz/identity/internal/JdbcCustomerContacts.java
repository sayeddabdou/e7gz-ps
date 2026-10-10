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

    @Override
    public Optional<Contact> find(CommandContext command, UUID userId) {
        Objects.requireNonNull(command, "command");
        return command.jdbc().sql(FIND)
                .param("id", Objects.requireNonNull(userId, "userId"))
                .query((rs, n) -> new Contact(rs.getObject("id", UUID.class), rs.getString("username"),
                        rs.getString("phone")))
                .optional();
    }
}
