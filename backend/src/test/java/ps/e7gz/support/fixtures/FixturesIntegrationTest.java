package ps.e7gz.support.fixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static ps.e7gz.support.fixtures.Fixtures.aDefaultPrice;
import static ps.e7gz.support.fixtures.Fixtures.aPriceOverride;
import static ps.e7gz.support.fixtures.Fixtures.aResource;
import static ps.e7gz.support.fixtures.Fixtures.aUser;
import static ps.e7gz.support.fixtures.Fixtures.aVenue;
import static ps.e7gz.support.fixtures.Fixtures.aVenueAccount;
import static ps.e7gz.support.fixtures.Fixtures.anAdmin;
import static ps.e7gz.support.fixtures.Fixtures.anArea;
import static ps.e7gz.support.fixtures.Fixtures.anOperatingSession;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import ps.e7gz.common.codes.Activity;
import ps.e7gz.common.codes.ConsoleType;
import ps.e7gz.common.codes.PlayMode;
import ps.e7gz.support.IntegrationTest;

/** E7-28: every builder writes rows the migrated database accepts, with the defaults its documentation promises. */
class FixturesIntegrationTest extends IntegrationTest {

    private static final JdbcClient DB = FixtureDb.owner();

    @Test
    void aVenueIsOnboardingInANewActiveAreaOfferingPlayStation() {
        Fixtures.Venue venue = aVenue().create();

        assertThat(DB.sql("SELECT marketplace_status FROM venue.venue WHERE id = :id").param("id", venue.id())
                .query(String.class).single()).isEqualTo("ONBOARDING");
        assertThat(DB.sql("SELECT is_active FROM geo.area WHERE id = :id").param("id", venue.areaId())
                .query(Boolean.class).single()).isTrue();
        assertThat(activities(venue)).containsExactly("PLAYSTATION");
    }

    @Test
    void anActiveVenueInAGivenAreaWithItsActivities() {
        Fixtures.Area area = anArea().named("Fixture Maadi " + FixtureDb.unique()).namedAr("المعادي").create();
        Fixtures.Venue venue = aVenue().inArea(area).active().offering(Activity.BILLIARDS, Activity.PING_PONG)
                .at("29.9602", "31.2569").create();

        assertThat(venue.areaId()).isEqualTo(area.id());
        assertThat(activities(venue)).containsExactlyInAnyOrder("BILLIARDS", "PING_PONG");
    }

    @Test
    void resourcesAreNamedLikeTheServicesNameThemAndCarryTheirHistory() {
        Fixtures.Venue venue = aVenue().active().create();
        Fixtures.Resource first = aResource().at(venue).create();
        Fixtures.Resource second = aResource().at(venue).playStation(ConsoleType.PS4).disabled().create();
        Fixtures.Resource table = aResource().at(venue).billiards().create();

        assertThat(List.of(first.name(), second.name(), table.name()))
                .containsExactly("Room 1", "Room 2", "Pool Table 1");
        assertThat(activities(venue)).containsExactlyInAnyOrder("PLAYSTATION", "BILLIARDS");
        assertThat(DB.sql("SELECT base_state FROM resource.resource WHERE id = :id").param("id", second.id())
                .query(String.class).single()).isEqualTo("DISABLED");
        assertThat(DB.sql("SELECT count(*) FROM resource.resource_state_change WHERE resource_id = :id")
                .param("id", second.id()).query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void defaultPricesAndARoomOverrideForTheRoomsConsole() {
        Fixtures.Venue venue = aVenue().create();
        aDefaultPrice().forVenue(venue).playStation(ConsoleType.PS5, PlayMode.MULTIPLAYER).hourly("80.00").create();
        aDefaultPrice().forVenue(venue).billiards().hourly("40.00").create();
        Fixtures.Resource room = aResource().at(venue).playStation(ConsoleType.PS4).create();
        aPriceOverride().forRoom(room).playMode(PlayMode.SINGLE).hourly("35.50").create();

        assertThat(DB.sql("SELECT count(*) FROM pricing.venue_default_price WHERE venue_id = :id")
                .param("id", venue.id()).query(Integer.class).single()).isEqualTo(2);
        assertThat(DB.sql("SELECT console_type || '/' || hourly_price FROM pricing.resource_price_override "
                        + "WHERE resource_id = :id").param("id", room.id()).query(String.class).single())
                .isEqualTo("PS4/35.50");
    }

    @Test
    void aVenueAccountWithItsOwnerSecret() {
        Fixtures.VenueAccount account = aVenueAccount().ownerPasswordHash("hash").create();

        assertThat(DB.sql("SELECT count(*) FROM iam.venue_owner_secret WHERE venue_account_id = :id")
                .param("id", account.id()).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void usersAreUnverifiedUnlessVerified() {
        Fixtures.User plain = aUser().create();
        Fixtures.User verified = aUser().verified().create();

        assertThat(plain.phoneE164()).isEqualTo("+2" + plain.localPhone()).startsWith("+2010");
        assertThat(phoneVerified(plain)).isFalse();
        assertThat(phoneVerified(verified)).isTrue();
        assertThat(DB.sql("SELECT count(*) FROM loyalty.points_account WHERE user_id = :id")
                .param("id", verified.id()).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void anAdminIsActive() {
        Fixtures.Admin admin = anAdmin().create();

        assertThat(DB.sql("SELECT is_active FROM iam.admin_account WHERE id = :id").param("id", admin.id())
                .query(Boolean.class).single()).isTrue();
    }

    @Test
    void anOperatingSessionAcrossMidnightGetsATemplateOfItsHours() {
        Fixtures.OperatingSession session = anOperatingSession()
                .on("2031-03-05", "2031-03-05T18:00", "2031-03-06T02:00").create();
        anOperatingSession().forVenue(session.venueId())
                .on("2031-03-06", "2031-03-06T18:00", "2031-03-07T02:00").create();

        assertThat(DB.sql("""
                        SELECT extract(epoch FROM upper(session_period) - lower(session_period))
                          FROM venue.operating_session WHERE id = :id""")
                .param("id", session.id()).query(BigDecimal.class).single()).isEqualByComparingTo("28800");
        assertThat(DB.sql("SELECT count(DISTINCT opening_template_id) FROM venue.operating_session "
                        + "WHERE venue_id = :id").param("id", session.venueId()).query(Integer.class).single())
                .isEqualTo(1);
    }

    private static List<String> activities(Fixtures.Venue venue) {
        return DB.sql("SELECT activity FROM venue.venue_activity WHERE venue_id = :id ORDER BY activity")
                .param("id", venue.id()).query(String.class).list();
    }

    private static boolean phoneVerified(Fixtures.User user) {
        return DB.sql("SELECT phone_verified FROM iam.user_account WHERE id = :id").param("id", user.id())
                .query(Boolean.class).single();
    }
}
