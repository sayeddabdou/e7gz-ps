package ps.e7gz.venue;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.support.PostgresTestContainer;

/**
 * E7-31: materialized {@code venue.operating_session} rows written directly on a test command's transaction, with the
 * wall clock made absolute in Africa/Cairo by {@code java.time}. Not the session generator: each row is exactly what
 * the test declares, so a test can make the rows disagree with the weekly template.
 */
public final class OperatingSessionFixtures {

    private static final ZoneId CAIRO = ZoneId.of("Africa/Cairo");

    /** No opening hours; tests write a template and sessions inside a command and roll back. */
    public static final UUID VENUE = id(0x002);
    /** No opening hours; a second Venue whose sessions must never answer for {@link #VENUE}. */
    public static final UUID NEIGHBOUR = id(0x003);
    /**
     * Committed: template 10:00-12:00 and one past session, Wednesday 10 June 2025 18:00 -> Thursday 02:00 Cairo.
     */
    public static final UUID COMMITTED = id(0x004);

    private static final String SCRIPT = "ps/e7gz/venue/operating-session-fixtures.sql";
    private static boolean loaded;

    private OperatingSessionFixtures() {
    }

    /** Loads the Venues above once per test JVM, as the migrator, outside any command. */
    public static synchronized void load() {
        if (loaded) {
            return;
        }
        try (Connection owner = DriverManager.getConnection(PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR,
                MIGRATOR)) {
            owner.setAutoCommit(false);
            ScriptUtils.executeSqlScript(owner, new ClassPathResource(SCRIPT));
            owner.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("Operating session fixtures failed", e);
        }
        loaded = true;
    }

    /** Runs {@code body} in a command on the app connection, then rolls it back; nothing it wrote is ever seen. */
    public static void inRolledBackCommand(CommandRunner runner, Consumer<CommandContext> body) {
        assertThatThrownBy(() -> runner.run(new RolledBack(body))).isInstanceOf(RollBack.class);
    }

    private record RolledBack(Consumer<CommandContext> body) implements Command<Void> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.empty());
        }

        @Override
        public Void execute(CommandContext context) {
            body.accept(context);
            throw new RollBack();
        }
    }

    private static final class RollBack extends RuntimeException {

        RollBack() {
            super("roll back on purpose", null, false, false);
        }
    }

    private static UUID id(int n) {
        return UUID.fromString("e7310100-0000-4000-8000-%012x".formatted(n));
    }

    /** A template of 10:00-12:00 every day: what the weekly hours say, never what the rows say. */
    public static UUID template(CommandContext ctx, UUID venue) {
        UUID template = UUID.randomUUID();
        ctx.jdbc().sql("""
                        INSERT INTO venue.venue_opening_template (id, venue_id, default_local_open, default_local_close,
                                                                  updated_at)
                        VALUES (:id, :venue, TIME '10:00', TIME '12:00', :now)""")
                .param("id", template).param("venue", venue)
                .param("now", OffsetDateTime.ofInstant(ctx.commandNow(), ZoneOffset.UTC)).update();
        return template;
    }

    /** One session of {@code day} from {@code localStart} to {@code localEnd}, Cairo wall clock. */
    public static void session(CommandContext ctx, UUID venue, UUID template, String day, String localStart,
                               String localEnd) {
        LocalDateTime start = LocalDateTime.parse(localStart);
        LocalDateTime end = LocalDateTime.parse(localEnd);
        ctx.jdbc().sql("""
                        INSERT INTO venue.operating_session (id, venue_id, opening_template_id, operating_day,
                                                             local_start, local_end, session_period, timezone,
                                                             generated_from_version, generated_at)
                        VALUES (:id, :venue, :template, :day, :localStart, :localEnd,
                                tstzrange(:start, :end, '[)'), 'Africa/Cairo', 0, :now)""")
                .param("id", UUID.randomUUID()).param("venue", venue).param("template", template)
                .param("day", LocalDate.parse(day)).param("localStart", start).param("localEnd", end)
                .param("start", start.atZone(CAIRO).toOffsetDateTime())
                .param("end", end.atZone(CAIRO).toOffsetDateTime())
                .param("now", OffsetDateTime.ofInstant(ctx.commandNow(), ZoneOffset.UTC)).update();
    }
}
