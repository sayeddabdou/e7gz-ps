package ps.e7gz.venue.internal;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ps.e7gz.common.codes.OpeningDayMode;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.inventory.ledger.api.ActiveCommitments;
import ps.e7gz.venue.api.HoursChangeBookings;
import ps.e7gz.venue.internal.OpeningHoursCommands.Impact;
import ps.e7gz.venue.internal.OpeningHoursCommands.Readers;
import ps.e7gz.venue.internal.OperatingSessionStore.Template;

/**
 * A venue's opening hours and Operating Sessions (FR-VEN-17…FR-VEN-27, ADR-009): read, preview and commit a change from
 * the Venue Dashboard, and extend every venue's session horizon nightly.
 */
@Service
@EnableConfigurationProperties(OperatingSessionProperties.class)
public class OpeningHoursService {

    private static final Logger log = LoggerFactory.getLogger(OpeningHoursService.class);

    private final CommandRunner runner;
    private final VenueAccounts accounts;
    private final Readers readers;
    private final OperatingSessionProperties properties;
    private final JdbcClient jdbc;

    OpeningHoursService(CommandRunner runner, VenueAccounts accounts, ActiveCommitments commitments,
                        HoursChangeBookings bookings, OperatingSessionProperties properties,
                        DataSource appDataSource) {
        this.runner = runner;
        this.accounts = accounts;
        this.readers = new Readers(commitments, bookings, properties.horizonDays());
        this.properties = properties;
        this.jdbc = JdbcClient.create(appDataSource);
    }

    /** The hours as the Dashboard shows them; {@code version} 0 means they were never saved. */
    public record View(String timezone, LocalTime defaultOpen, LocalTime defaultClose, boolean defaultFullDay,
                       List<Day> days, long version) {
    }

    public record Day(int weekday, OpeningDayMode mode, LocalTime customOpen, LocalTime customClose) {
    }

    /** The proposed hours, as sent. */
    public record Proposal(LocalTime defaultOpen, LocalTime defaultClose, boolean defaultFullDay, List<Day> days) {
    }

    public record Preview(String previewToken, List<AffectedBooking> affected, boolean ownerPasswordRequired) {
    }

    public record AffectedBooking(UUID bookingId, UUID resourceId, Instant startAt, Instant endAt,
                                  boolean protectedCancellation) {
    }

    /**
     * The signed-in venue's hours. Before the first save there are none: every day is CLOSED (the venue has no
     * Operating Session), shown with an empty full-day default so a proposal built from it is valid.
     */
    public View get(UUID venueAccountId) {
        UUID venueId = accounts.venueOf(venueAccountId);
        ZoneId zone = OperatingSessionStore.zoneOf(jdbc, venueId);
        return OperatingSessionStore.template(jdbc, venueId).map(template -> view(zone, template))
                .orElseGet(() -> {
                    List<Day> closed = new ArrayList<>();
                    for (int weekday = 0; weekday < OpeningHours.WEEKDAYS; weekday++) {
                        closed.add(new Day(weekday, OpeningDayMode.CLOSED, null, null));
                    }
                    return new View(zone.getId(), LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, true, closed, 0);
                });
    }

    /** FR-VEN-23 steps 1–5 (and 6: whether the commit will need the Owner Password). */
    public Preview preview(UUID venueAccountId, Proposal proposal) {
        UUID venueId = accounts.venueOf(venueAccountId);
        Impact impact = runner.run(new OpeningHoursCommands.Preview(venueId, hours(proposal), readers));
        return new Preview(impact.previewToken(), impact.affected().stream()
                .map(a -> new AffectedBooking(a.bookingId(), a.resourceId(), a.startAt(), a.endAt(),
                        a.protectedCancellation()))
                .toList(), impact.ownerPasswordRequired());
    }

    /** FR-VEN-23 steps 6–8, all or nothing. See {@link OpeningHoursCommands.Commit} for what is not built yet. */
    public View commit(UUID venueAccountId, Proposal proposal, String previewToken) {
        UUID venueId = accounts.venueOf(venueAccountId);
        Template saved = runner.run(new OpeningHoursCommands.Commit(venueId, hours(proposal), previewToken, readers));
        return view(OperatingSessionStore.zoneOf(jdbc, venueId), saved);
    }

    /** Extends every venue with saved hours by the new day; one command per venue, so one failure stops no other. */
    public int extendAll() {
        List<UUID> venues = jdbc.sql("SELECT venue_id FROM venue.venue_opening_template ORDER BY venue_id")
                .query(UUID.class).list();
        int extended = 0;
        for (UUID venueId : venues) {
            try {
                if (runner.run(new OpeningHoursCommands.Extend(venueId, properties.horizonDays()))) {
                    extended++;
                }
            } catch (RuntimeException failure) {
                log.error("Operating sessions of venue {} not extended", venueId, failure);
            }
        }
        return extended;
    }

    private static OpeningHours hours(Proposal proposal) {
        return OpeningHours.validated(proposal.defaultOpen(), proposal.defaultClose(), proposal.defaultFullDay(),
                proposal.days().stream()
                        .map(d -> new OpeningHours.Day(d.weekday(), d.mode(), d.customOpen(), d.customClose()))
                        .toList());
    }

    private static View view(ZoneId zone, Template template) {
        OpeningHours hours = template.hours();
        return new View(zone.getId(), hours.defaultOpen(), hours.defaultClose(), hours.defaultFullDay(),
                hours.dayList().stream()
                        .map(d -> new Day(d.weekday(), d.mode(), d.customOpen(), d.customClose())).toList(),
                template.version());
    }
}
