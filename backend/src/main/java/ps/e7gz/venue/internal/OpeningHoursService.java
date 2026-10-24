package ps.e7gz.venue.internal;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ps.e7gz.audit.api.AuditWriter;
import ps.e7gz.common.codes.OpeningDayMode;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.inventory.ledger.api.ActiveCommitments;
import ps.e7gz.messaging.api.Idempotency;
import ps.e7gz.messaging.api.IdempotentRequest;
import ps.e7gz.messaging.api.IdempotentResult;
import ps.e7gz.messaging.api.StoredResponse;
import ps.e7gz.venue.api.HoursChangeBookings;
import ps.e7gz.venue.api.OwnerPasswordGate;
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
    private final Idempotency idempotency;
    private final OwnerPasswordGate gate;
    private final AuditWriter audit;

    OpeningHoursService(CommandRunner runner, VenueAccounts accounts, ActiveCommitments commitments,
                        HoursChangeBookings bookings, OperatingSessionProperties properties,
                        DataSource appDataSource, Idempotency idempotency, OwnerPasswordGate gate, AuditWriter audit) {
        this.runner = runner;
        this.accounts = accounts;
        this.readers = new Readers(commitments, bookings, properties.horizonDays());
        this.properties = properties;
        this.jdbc = JdbcClient.create(appDataSource);
        this.idempotency = idempotency;
        this.gate = gate;
        this.audit = audit;
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

    /**
     * FR-VEN-23 steps 6–9, all or nothing, once per Idempotency-Key (ADR-015: it cancels bookings and moves Booking Fee
     * refunds). The affected set is computed first, without writing, only to name the locks the commit takes: the
     * venue, every affected Resource and every affected Online Customer. The commit recomputes the set under those locks
     * and refuses one its token or its locks do not match (409 AFFECTED_SET_CHANGED_REFRESH_PREVIEW, FR-VEN-26). A set
     * with a CONFIRMED Online or an AWAITING booking runs the protected command, which checks the Owner Password
     * (FR-VEN-25; missing 423 OWNER_PASSWORD_REQUIRED, wrong 423 OWNER_PASSWORD_INVALID, audited); a set without one
     * runs the unprotected command, which still answers 423 OWNER_PASSWORD_REQUIRED if, under the locks, one became
     * protected. Both write nothing when refused, the idempotency record included.
     *
     * <p>A request that supplies an Owner Password - any value, blank included - always runs the protected command,
     * whatever the set (D1, approved 24 Oct 2026): the gate checks the password before the Idempotency-Key lookup (PO
     * decision Q3), so a replay of a protected commit is checked like a first request even though its own preview is
     * empty by then, and a blank one is 423 OWNER_PASSWORD_REQUIRED, never a quiet unprotected save. A request without
     * one decides by the set as before.
     *
     * <p>A retry with the same key and request returns the stored answer without running anything else; the first pass
     * is then only a read. If that read is refused (a Tournament hold appeared), the commit still runs, under the venue
     * lock alone, so a replay answers as stored and a new request is refused under the lock as before.
     */
    public <R> IdempotentResult<R> commit(UUID venueAccountId, Proposal proposal, String previewToken,
                                          String ownerPassword, IdempotentRequest key, StoredResponse<R> response,
                                          Function<View, R> view) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(view, "view");
        UUID venueId = accounts.venueOf(venueAccountId);
        OpeningHours hours = hours(proposal);
        ZoneId zone = OperatingSessionStore.zoneOf(jdbc, venueId);
        Function<Template, R> answer = saved -> view.apply(view(zone, saved));

        LockSet locks;
        boolean protectedSet;
        try {
            Impact planned = runner.run(new OpeningHoursCommands.Preview(venueId, hours, readers));
            locks = planned.lockSet(venueId);
            protectedSet = planned.ownerPasswordRequired();
        } catch (ApiException refused) {
            if (refused.code() != ApiErrorCode.TOURNAMENT_CONFLICT_BLOCKS_SAVE) {
                throw refused;
            }
            locks = LockSet.builder().venues(venueId).build();
            protectedSet = false;
        }
        if (!protectedSet && ownerPassword == null) {
            return runner.run(idempotency.wrap(key, response, new Mapped<>(new OpeningHoursCommands.Commit(venueId,
                    venueAccountId, hours, previewToken, locks, readers), answer)));
        }
        UUID correlationId = CorrelationId.require();
        return gate.run(new OpeningHoursCommands.Protected<>(idempotency.wrap(key, response,
                new Mapped<>(new OpeningHoursCommands.ProtectedCommit(venueId, venueAccountId, hours, previewToken,
                        locks, correlationId, readers, gate, audit), answer))),
                venueAccountId, ownerPassword, correlationId);
    }

    /** A command's result mapped to the response, in the command's transaction. */
    private record Mapped<T, R>(Command<T> command, Function<T, R> mapping) implements Command<R> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return command.eligibilityCategory();
        }

        @Override
        public CommandScope scope() {
            return command.scope();
        }

        @Override
        public R execute(CommandContext context) {
            return mapping.apply(command.execute(context));
        }
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
