package ps.e7gz.venue.internal;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ps.e7gz.common.codes.MarketplaceStatus;
import ps.e7gz.common.codes.VenueAccountStatus;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.venue.api.VenueEligibility;

/** {@code venue.venue} and its {@code iam.venue_account} ({@code uq_venue_account_venue}: at most one) on the command's transaction. */
@Component
class JdbcVenueEligibility implements VenueEligibility {

    static final String FIND = """
            SELECT v.id, v.area_id, v.marketplace_status, va.account_status
              FROM venue.venue v
              LEFT JOIN iam.venue_account va ON va.venue_id = v.id
             WHERE v.id = :venueId
            """;

    @Override
    public Optional<Eligibility> find(CommandContext command, UUID venueId) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(venueId, "venueId");
        return command.jdbc().sql(FIND)
                .param("venueId", venueId)
                .query((rs, n) -> {
                    String account = rs.getString("account_status");
                    return new Eligibility(rs.getObject("id", UUID.class), rs.getObject("area_id", UUID.class),
                            MarketplaceStatus.valueOf(rs.getString("marketplace_status")),
                            account == null ? null : VenueAccountStatus.valueOf(account));
                })
                .optional();
    }
}
