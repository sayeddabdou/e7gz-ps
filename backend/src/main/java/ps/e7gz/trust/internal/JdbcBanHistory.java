package ps.e7gz.trust.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.trust.api.BanHistory;

/**
 * {@code trust.ban_instance} on the command's transaction (E7-69 as-of query), over {@code ix_ban_user_operational_window}.
 * Unlike {@link JdbcActiveBans} it does not filter on the status: the question is historical.
 */
@Component
class JdbcBanHistory implements BanHistory {

    static final String BANNED_AT = """
            SELECT EXISTS (
                SELECT 1
                  FROM trust.ban_instance b
                 WHERE b.user_id = :userId
                   AND b.activated_at <= :at
                   AND b.ends_at > :at)
            """;

    @Override
    public boolean bannedAt(CommandContext command, UUID userId, Instant at) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(at, "at");
        return command.jdbc().sql(BANNED_AT)
                .param("userId", userId)
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .query(Boolean.class).single();
    }
}
