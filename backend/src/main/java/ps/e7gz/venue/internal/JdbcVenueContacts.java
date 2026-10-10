package ps.e7gz.venue.internal;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.venue.api.VenueContacts;

/** {@code venue.venue} name and phone on the command's transaction. */
@Component
class JdbcVenueContacts implements VenueContacts {

    static final String FIND = """
            SELECT v.id, v.name, v.phone
              FROM venue.venue v
             WHERE v.id = :venueId
            """;

    @Override
    public Optional<Contact> find(CommandContext command, UUID venueId) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(venueId, "venueId");
        return command.jdbc().sql(FIND)
                .param("venueId", venueId)
                .query((rs, n) -> new Contact(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("phone")))
                .optional();
    }
}
