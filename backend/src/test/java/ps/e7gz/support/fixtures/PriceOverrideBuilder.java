package ps.e7gz.support.fixtures;

import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.common.money.Money;

/**
 * One PlayStation room override ({@code pricing.resource_price_override}) for the room's current console, which
 * {@code tr_validate_resource_price_override} requires: single player at 60.00 EGP unless told otherwise.
 */
public final class PriceOverrideBuilder {

    private UUID resourceId;
    private PlayMode playMode = PlayMode.SINGLE;
    private Money hourly = Money.of("60.00");

    PriceOverrideBuilder() {
    }

    public PriceOverrideBuilder forRoom(UUID resourceId) {
        this.resourceId = resourceId;
        return this;
    }

    public PriceOverrideBuilder forRoom(Fixtures.Resource room) {
        return forRoom(room.id());
    }

    public PriceOverrideBuilder playMode(PlayMode playMode) {
        this.playMode = playMode;
        return this;
    }

    public PriceOverrideBuilder hourly(String egp) {
        this.hourly = Money.of(egp);
        return this;
    }

    public UUID create() {
        return create(FixtureDb.owner());
    }

    public UUID create(JdbcClient db) {
        Objects.requireNonNull(resourceId, "forRoom(...) is required");
        UUID id = FixtureDb.id();
        db.sql("""
                        INSERT INTO pricing.resource_price_override (id, resource_id, console_type, play_mode,
                                                                     hourly_price)
                        SELECT :id, r.id, r.console_type, :mode, :price FROM resource.resource r WHERE r.id = :room""")
                .param("id", id).param("room", resourceId).param("mode", playMode.name())
                .param("price", hourly.amount()).update();
        return id;
    }
}
