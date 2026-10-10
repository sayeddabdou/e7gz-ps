package ps.e7gz.operations.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static ps.e7gz.support.fixtures.Fixtures.aResource;
import static ps.e7gz.support.fixtures.Fixtures.aVenue;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.operations.api.UnresolvedEmergencies;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;
import ps.e7gz.support.fixtures.Fixtures;

/**
 * {@link UnresolvedEmergencies} (E7-47, PO decision D3): a VENUE- or RESOURCE-scoped Emergency names its own cause while
 * it is unresolved and declared by the instant asked about - past its declared interval too - and never the other
 * scope's.
 */
class JdbcUnresolvedEmergenciesTest extends IntegrationTest {

    private static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(new Driver(),
            PostgresTestContainer.jdbcUrl(PostgresTestContainer.DATABASE), PostgresTestContainer.MIGRATOR,
            PostgresTestContainer.MIGRATOR));

    @Autowired
    UnresolvedEmergencies emergencies;

    @Autowired
    CommandRunner runner;

    @Test
    void anUnresolvedVenueEmergencyIsTheVenuesCauseOnly() {
        Fixtures.Venue venue = aVenue().create();
        openNow(venue);
        Fixtures.Resource room = aResource().at(venue).playStation(ConsoleType.PS5).create();
        Instant declared = emergency("VENUE", venue.id(), null, "ACTIVE");

        assertThat(read(c -> emergencies.venueEmergency(c, venue.id(), declared))).isTrue();
        assertThat(read(c -> emergencies.venueEmergency(c, venue.id(), declared.plusSeconds(86_400))))
                .as("a state, past the declared interval").isTrue();
        assertThat(read(c -> emergencies.venueEmergency(c, venue.id(), declared.minusSeconds(1))))
                .as("not before it was declared").isFalse();
        assertThat(read(c -> emergencies.resourceEmergency(c, room.id(), declared))).isFalse();
    }

    @Test
    void anUnresolvedResourceEmergencyIsTheResourcesCauseOnly() {
        Fixtures.Venue venue = aVenue().create();
        openNow(venue);
        Fixtures.Resource room = aResource().at(venue).playStation(ConsoleType.PS5).create();
        Fixtures.Resource other = aResource().at(venue).playStation(ConsoleType.PS5).create();
        Instant declared = emergency("RESOURCE", venue.id(), room.id(), "ACTIVE");

        assertThat(read(c -> emergencies.resourceEmergency(c, room.id(), declared))).isTrue();
        assertThat(read(c -> emergencies.resourceEmergency(c, other.id(), declared))).isFalse();
        assertThat(read(c -> emergencies.venueEmergency(c, venue.id(), declared))).isFalse();
    }

    @Test
    void anEndedEmergencyIsNoLongerACause() {
        Fixtures.Venue venue = aVenue().create();
        openNow(venue);
        Instant declared = emergency("VENUE", venue.id(), null, "ACTIVE");
        OWNER.sql("""
                        WITH e AS (
                            UPDATE operations.emergency SET status = 'ENDED', ended_at = now()
                             WHERE venue_id = :venue RETURNING id, ended_at)
                        UPDATE venue.venue_selling_block b SET active = false, ended_at = e.ended_at
                          FROM e WHERE b.emergency_id = e.id""")
                .param("venue", venue.id()).update();

        assertThat(read(c -> emergencies.venueEmergency(c, venue.id(), declared))).isFalse();
    }

    /** As Operations stores one; an unresolved one with its selling block, which its consistency check requires. */
    /** An Emergency is declared inside a materialized Operating Session: one around now (Africa/Cairo). */
    private static void openNow(Fixtures.Venue venue) {
        java.time.ZoneId cairo = java.time.ZoneId.of("Africa/Cairo");
        java.time.LocalDateTime start = java.time.LocalDateTime.now(cairo).minusHours(2).withSecond(0).withNano(0);
        Fixtures.anOperatingSession().forVenue(venue)
                .on(start.toLocalDate().toString(), start.toString(), start.plusHours(12).toString()).create();
    }

    /**
     * As Operations stores one: an unresolved Emergency together with the selling block its consistency check requires,
     * in one statement (the check is deferred to the statement's commit); an ENDED one with no active block.
     */
    private static Instant emergency(String scope, UUID venueId, UUID resourceId, String status) {
        String emergency = """
                INSERT INTO operations.emergency (id, scope, venue_id, resource_id, status, declared_at,
                                                  expected_end_at,
                                                  declared_period, reason, ended_at)
                VALUES (gen_random_uuid(), :scope, :venue, :resource, :status, now(), now() + interval '10 minutes',
                        tstzrange(now(), now() + interval '10 minutes', '[)'), 'E7-47 test',
                        CASE WHEN :status = 'ENDED' THEN now() END)
                RETURNING id, declared_at""";
        String sql = switch (status) {
            case "ENDED" -> "WITH e AS (" + emergency + ") SELECT declared_at FROM e";
            default -> scope.equals("VENUE")
                    ? "WITH e AS (" + emergency + ") INSERT INTO venue.venue_selling_block (id, venue_id, cause, "
                    + "emergency_id, active, started_at) SELECT gen_random_uuid(), :venue, 'EMERGENCY', e.id, true, "
                    + "e.declared_at FROM e RETURNING started_at"
                    : "WITH e AS (" + emergency + ") INSERT INTO resource.resource_selling_block (id, resource_id, "
                    + "cause, emergency_id, active, started_at) SELECT gen_random_uuid(), :resource, 'EMERGENCY', e.id, "
                    + "true, e.declared_at FROM e RETURNING started_at";
        };
        return OWNER.sql(sql).param("scope", scope).param("venue", venueId).param("resource", resourceId)
                .param("status", status)
                .query(OffsetDateTime.class).single().toInstant();
    }

    private boolean read(Function<CommandContext, Boolean> body) {
        return runner.run(new Command<Boolean>() {
            @Override
            public EligibilityCategory eligibilityCategory() {
                return EligibilityCategory.B_EXISTING_COMMITMENT_OPERATIONAL;
            }

            @Override
            public CommandScope scope() {
                return CommandScope.shared(LockSet.empty());
            }

            @Override
            public Boolean execute(CommandContext context) {
                return body.apply(context);
            }
        });
    }
}
