package ps.e7gz.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import ps.e7gz.common.money.Money;
import ps.e7gz.pricing.api.PriceCalculator;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;

/**
 * E7-27 done-when: the Java formula matches the DB CHECK on booking rows, {@code ck_booking_venue_price_math}:
 * {@code venue_price_total = ROUND(hourly_price_snapshot * nominal_booked_minutes / 60.0, 2)}.
 */
class PriceCalculatorDatabaseTest extends IntegrationTest {

    private static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(
            new Driver(), PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));

    /** Every duration a booking may have (ck_booking_duration: 60-360 in steps of 30). */
    private static final int[] BOOKING_MINUTES = {60, 90, 120, 150, 180, 210, 240, 270, 300, 330, 360};

    @Test
    void propertyJavaEqualsTheDatabaseExpressionOnRandomPrices() {
        Random random = new Random(27L);
        List<Money> prices = new ArrayList<>();
        List<Integer> minutes = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            // Mostly realistic hourly prices, sometimes anything up to the numeric(12,2) maximum.
            long cents = i % 10 == 0 ? random.nextLong(0, 1_000_000_000_000L) : random.nextLong(0, 100_000);
            prices.add(Money.of(BigDecimal.valueOf(cents, 2)));
            minutes.add(i % 2 == 0 ? BOOKING_MINUTES[random.nextInt(BOOKING_MINUTES.length)]
                    : random.nextInt(0, 24 * 60 + 1));
        }
        // Every price whose 90- or 30-minute line item lands exactly on a half piastre.
        for (long cents = 0; cents < 2_000; cents++) {
            prices.add(Money.of(BigDecimal.valueOf(cents, 2)));
            minutes.add(cents % 2 == 0 ? 90 : 30);
        }

        List<BigDecimal> database = OWNER.sql("""
                        SELECT ROUND(t.h * t.m / 60.0, 2)
                          FROM unnest(string_to_array(:prices, ',')::numeric(12,2)[],
                                      string_to_array(:minutes, ',')::integer[]) WITH ORDINALITY AS t(h, m, n)
                         ORDER BY t.n""")
                .param("prices", prices.stream().map(Money::toString).collect(Collectors.joining(",")))
                .param("minutes", minutes.stream().map(String::valueOf).collect(Collectors.joining(",")))
                .query(BigDecimal.class).list();

        assertThat(database).hasSize(prices.size());
        for (int i = 0; i < prices.size(); i++) {
            assertThat(PriceCalculator.linePrice(prices.get(i), minutes.get(i)).amount())
                    .as("%s × %d", prices.get(i), minutes.get(i)).isEqualByComparingTo(database.get(i));
        }
    }

    @Test
    void theBookingCheckAcceptsTheCalculatedPriceAndRefusesOnePiastreOff() {
        UUID[] room = venueWithRoom();
        Random random = new Random(2027L);
        Instant start = Instant.parse("2031-01-01T10:00:00Z");
        for (int i = 0; i < 200; i++) {
            Money hourly = Money.of(BigDecimal.valueOf(random.nextLong(0, 100_000), 2));
            int minutes = BOOKING_MINUTES[random.nextInt(BOOKING_MINUTES.length)];
            Money line = PriceCalculator.linePrice(hourly, minutes);
            insertBooking(room, start.plus(i, ChronoUnit.DAYS), minutes, hourly, line);
        }

        Money hourly = Money.of("33.33");
        Money line = PriceCalculator.linePrice(hourly, 90);
        assertThat(line).isEqualTo(Money.of("50.00"));
        assertThatThrownBy(() -> insertBooking(room, start.minus(1, ChronoUnit.DAYS), 90, hourly,
                line.minus(Money.of("0.01")))).hasMessageContaining("ck_booking_venue_price_math");
    }

    private static void insertBooking(UUID[] room, Instant startAt, int minutes, Money hourly, Money line) {
        OWNER.sql("""
                        INSERT INTO booking.booking (id, venue_id, resource_id, customer_id, source, status, activity,
                                                     console_type, play_mode, resource_name_snapshot, start_at, end_at,
                                                     nominal_booked_minutes, hourly_price_snapshot, venue_price_total,
                                                     booking_fee_snapshot, total_amount, business_date,
                                                     session_timezone, created_at)
                        VALUES (:id, :venue, :room, NULL, 'MANUAL', 'CONFIRMED', 'PLAYSTATION', 'PS5', 'SINGLE',
                                'Room 1', :start, :end, :minutes, :hourly, :line, 0.00, :line, :day,
                                'Africa/Cairo', now())""")
                .param("id", UUID.randomUUID()).param("venue", room[0]).param("room", room[1])
                .param("start", OffsetDateTime.ofInstant(startAt, ZoneOffset.UTC))
                .param("end", OffsetDateTime.ofInstant(startAt.plus(minutes, ChronoUnit.MINUTES), ZoneOffset.UTC))
                .param("minutes", minutes).param("hourly", hourly.amount()).param("line", line.amount())
                .param("day", startAt.atOffset(ZoneOffset.UTC).toLocalDate()).update();
    }

    /** An ACTIVE venue with one PS5 room. */
    private static UUID[] venueWithRoom() {
        UUID area = UUID.randomUUID();
        OWNER.sql("INSERT INTO geo.area (id, name, is_active) VALUES (:id, :name, true)")
                .param("id", area).param("name", "E7-27 area " + area).update();
        UUID venue = UUID.randomUUID();
        OWNER.sql("""
                        INSERT INTO venue.venue (id, area_id, name, address, location, marketplace_status, timezone, phone)
                        VALUES (:id, :area, :name, '12 Test St', 'SRID=4326;POINT(31.2 30.0)'::geography, 'ACTIVE',
                                'Africa/Cairo', '+20223456789')""")
                .param("id", venue).param("area", area).param("name", "E7-27 venue " + venue).update();
        OWNER.sql("INSERT INTO venue.venue_activity (id, venue_id, activity) VALUES (:id, :venue, 'PLAYSTATION')")
                .param("id", UUID.randomUUID()).param("venue", venue).update();
        OWNER.sql("""
                        INSERT INTO iam.venue_account (id, venue_id, login_identifier, password_hash, account_status,
                                                       language_preference)
                        VALUES (:id, :venue, :login, 'not-used-here', 'ENABLED', 'en')""")
                .param("id", UUID.randomUUID()).param("venue", venue).param("login", "e7-27-" + venue).update();
        UUID room = UUID.randomUUID();
        OWNER.sql("""
                        INSERT INTO resource.resource (id, venue_id, activity, name, console_type, base_state,
                                                       operational_restriction)
                        VALUES (:id, :venue, 'PLAYSTATION', 'Room 1', 'PS5', 'ACTIVE', 'NONE')""")
                .param("id", room).param("venue", venue).update();
        return new UUID[]{venue, room};
    }
}
