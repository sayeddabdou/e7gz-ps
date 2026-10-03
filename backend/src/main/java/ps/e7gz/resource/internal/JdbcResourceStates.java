package ps.e7gz.resource.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.OperationalRestriction;
import ps.e7gz.common.codes.ResourceBaseState;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.resource.api.ResourceStates;

/** {@code resource.resource} and {@code resource.resource_selling_block} on the command's transaction. */
@Component
class JdbcResourceStates implements ResourceStates {

    static final String FIND = """
            SELECT r.venue_id, r.base_state, r.operational_restriction
              FROM resource.resource r
             WHERE r.id = :resourceId
            """;

    /** {@code ck_resource_block_state}: an active row has no {@code ended_at}; an ended row has one. */
    static final String BLOCKED_AT = """
            SELECT EXISTS (
                SELECT 1
                  FROM resource.resource_selling_block b
                 WHERE b.resource_id = :resourceId
                   AND b.started_at <= :at
                   AND (b.active OR b.ended_at > :at))
            """;

    @Override
    public Optional<ResourceState> find(CommandContext command, UUID resourceId) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(resourceId, "resourceId");
        return command.jdbc().sql(FIND)
                .param("resourceId", resourceId)
                .query((rs, n) -> new ResourceState(resourceId, rs.getObject("venue_id", UUID.class),
                        ResourceBaseState.valueOf(rs.getString("base_state")),
                        OperationalRestriction.valueOf(rs.getString("operational_restriction"))))
                .optional();
    }

    @Override
    public boolean resourceBlocked(CommandContext command, UUID resourceId, Instant at) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(at, "at");
        return command.jdbc().sql(BLOCKED_AT)
                .param("resourceId", resourceId)
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .query(Boolean.class).single();
    }
}
