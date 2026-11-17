package ps.e7gz.trust.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.postgresql.Driver;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import ps.e7gz.attendance.internal.AttendanceFlows;
import ps.e7gz.booking.internal.BookingFlows;
import ps.e7gz.booking.internal.BookingFlows.Booking;
import ps.e7gz.support.PostgresTestContainer;

/**
 * E7-72 test flows: a customer reaches Verified Player through the real commands only - ten Online Bookings, each
 * checked in by the venue and completed by its BOOKING_COMPLETE job (T5) - and the Verified Player rows read back as
 * the database holds them.
 */
public final class VerifiedPlayerFlows {

    public static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(new Driver(),
            PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));

    private final BookingFlows flows;
    private final AttendanceFlows attendance;

    private VerifiedPlayerFlows(ApplicationContext context) {
        this.flows = BookingFlows.of(context);
        this.attendance = AttendanceFlows.of(context);
    }

    public static VerifiedPlayerFlows of(ApplicationContext context) {
        return new VerifiedPlayerFlows(context);
    }

    public BookingFlows bookings() {
        return flows;
    }

    public AttendanceFlows attendance() {
        return attendance;
    }

    /** A new customer with {@code count} ATTENDED completions, each through check-in and T5. */
    public List<Booking> attendedCompletions(int count) {
        return attendedCompletions(flows.customer(), count);
    }

    public List<Booking> attendedCompletions(BookingFlows.Customer customer, int count) {
        List<Booking> completed = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Booking booking = attendedPastItsEnd(customer);
            assertThat(flows.fireCompletion(booking)).as("completion %d fired", i).isTrue();
            assertThat(status(booking.bookingId())).as("completion %d", i).isEqualTo("COMPLETED");
            completed.add(booking);
        }
        return completed;
    }

    /** A new ACTIVE booking of the customer, checked in through the real command, then moved past its end. */
    public Booking attendedPastItsEnd(BookingFlows.Customer customer) {
        Booking booking = customer.nextActive(Duration.ofMinutes(5));
        attendance.checkIn(booking.venueAccountId(), booking.bookingId());
        return flows.pastItsEnd(booking, Duration.ofMinutes(10));
    }

    public static String status(UUID bookingId) {
        return OWNER.sql("SELECT status FROM booking.booking WHERE id = :id").param("id", bookingId)
                .query(String.class).single();
    }

    /** The User's transitions, oldest first. */
    public static List<Transition> transitions(UUID userId) {
        return OWNER.sql("""
                        SELECT id, transition_id, from_status, to_status, cause, admin_id, occurred_at,
                               cascade_executed_at
                          FROM trust.verified_player_transition WHERE user_id = :user ORDER BY occurred_at, id""")
                .param("user", userId)
                .query((rs, n) -> new Transition(rs.getObject("id", UUID.class),
                        rs.getObject("transition_id", UUID.class), rs.getString("from_status"),
                        rs.getString("to_status"), rs.getString("cause"), rs.getObject("admin_id", UUID.class),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        instant(rs.getObject("cascade_executed_at", OffsetDateTime.class))))
                .list();
    }

    /** {@code vp_qualification}, {@code completed_attended_count}, {@code vp_operational_status} of the projection. */
    public static Projection projection(UUID userId) {
        return OWNER.sql("""
                        SELECT vp_qualification, completed_attended_count, vp_operational_status
                          FROM trust.trust_projection WHERE user_id = :user""")
                .param("user", userId)
                .query((rs, n) -> new Projection(rs.getString("vp_qualification"),
                        rs.getInt("completed_attended_count"), rs.getString("vp_operational_status")))
                .single();
    }

    /** The {@code VERIFIED_PLAYER} cascade proofs of the User's transitions: their keys. */
    public static List<String> cascadeKeys(UUID userId) {
        return OWNER.sql("""
                        SELECT cascade_key FROM trust.cascade_execution
                         WHERE cascade_type = 'VERIFIED_PLAYER' AND subject_user_id = :user ORDER BY executed_at""")
                .param("user", userId).query(String.class).list();
    }

    /** The ADMIN_VERIFIED_PLAYER_REVOKED alerts about the User: their {@code transitionId} and {@code cause}. */
    public static List<String> revokedAlerts(UUID userId) {
        return OWNER.sql("""
                        SELECT (payload->>'transitionId') || '|' || (payload->>'cause') FROM admin.admin_alert
                         WHERE kind = 'ADMIN_VERIFIED_PLAYER_REVOKED' AND subject_type = 'USER' AND subject_id = :user
                         ORDER BY created_at""")
                .param("user", userId).query(String.class).list();
    }

    /** The {@code VerifiedPlayerTransitioned} outbox events of the User: their {@code transitionId}. */
    public static List<String> transitionedEvents(UUID userId) {
        return OWNER.sql("""
                        SELECT payload->>'transitionId' FROM technical.outbox_event
                         WHERE event_type = 'VerifiedPlayerTransitioned' AND aggregate_type = 'USER'
                           AND aggregate_id = :user ORDER BY id""")
                .param("user", userId).query(String.class).list();
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    public record Transition(UUID id, UUID transitionId, String from, String to, String cause, UUID adminId,
                             Instant occurredAt, Instant cascadeExecutedAt) {
    }

    public record Projection(String qualification, int completed, String status) {
    }
}
