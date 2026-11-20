package ps.e7gz.operations.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ps.e7gz.audit.api.AuditWriter;
import ps.e7gz.common.codes.VenueClosureKind;
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
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.messaging.api.Idempotency;
import ps.e7gz.messaging.api.IdempotentRequest;
import ps.e7gz.messaging.api.IdempotentResult;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.messaging.api.StoredResponse;
import ps.e7gz.operations.internal.ClosureCommands.Closure;
import ps.e7gz.operations.internal.ClosureCommands.Deps;
import ps.e7gz.operations.internal.ClosureCommands.Impact;
import ps.e7gz.operations.internal.ClosureCommands.Proposal;
import ps.e7gz.resource.api.ResourceCatalog;
import ps.e7gz.venue.api.HoursChangeBookings;
import ps.e7gz.venue.api.OwnerPasswordGate;
import ps.e7gz.venue.api.VenueAccountVenues;

/**
 * The Venue Dashboard's normal Closures (FR-VEN-29…FR-VEN-33; E7-81, the E7-77 contract D1, D2, D6): list, preview,
 * save (create or edit) and delete. The save is the opening-hours commit's flow (E7-113): the affected set is computed
 * first, without writing, only to name the locks; the save recomputes it under them. A set with a CONFIRMED Online or an
 * AWAITING booking - or any request that sends an Owner Password (D1 of the hours commit, 24 Oct 2026) - runs the
 * protected save, which the gate checks before the Idempotency-Key lookup; otherwise the unprotected one, which still
 * answers 423 OWNER_PASSWORD_REQUIRED if, under the locks, the set became protected. Saves cancel bookings and move
 * Booking Fee refunds, so they run once per Idempotency-Key (ADR-015).
 */
@Service
public class VenueClosures {

    private final CommandRunner runner;
    private final OwnerPasswordGate gate;
    private final VenueAccountVenues accounts;
    private final Idempotency idempotency;
    private final Deps deps;
    private final JdbcClient jdbc;
    private final Clock clock;

    VenueClosures(CommandRunner runner, OwnerPasswordGate gate, VenueAccountVenues accounts, Idempotency idempotency,
                  ActiveCommitments commitments, HoursChangeBookings bookings, ReservationLedger ledger,
                  ResourceCatalog resources, AuditWriter audit, Outbox outbox, DataSource appDataSource,
                  Clock clock) {
        this.runner = runner;
        this.gate = gate;
        this.accounts = accounts;
        this.idempotency = idempotency;
        this.deps = new Deps(commitments, bookings, ledger, resources, audit, outbox, gate);
        this.jdbc = JdbcClient.create(appDataSource);
        this.clock = clock;
    }

    public record View(UUID id, VenueClosureKind kind, Instant startAt, Instant endAt, String reason) {

        static View of(Closure closure) {
            return new View(closure.id(), closure.kind(), closure.startAt(), closure.endAt(), closure.reason());
        }
    }

    public record ProposalView(VenueClosureKind kind, Instant startAt, Instant endAt, String reason) {
    }

    public record Preview(String previewToken, List<AffectedBooking> affected, boolean ownerPasswordRequired) {
    }

    public record AffectedBooking(UUID bookingId, UUID resourceId, Instant startAt, Instant endAt,
                                  boolean protectedCancellation) {
    }

    /** The venue's Closures that have not ended, earliest first, deleted ones left out (E7-77 D1). */
    public List<View> list(UUID venueAccountId) {
        UUID venueId = accounts.venueOf(venueAccountId);
        return jdbc.sql(ClosureCommands.SELECT + """
                         WHERE c.venue_id = :venue AND c.deleted_at IS NULL AND upper(c.period) > :now
                         ORDER BY lower(c.period), c.id""")
                .param("venue", venueId).param("now", MaintenanceCommands.utc(clock.instant()))
                .query(Closure::map).list().stream().map(View::of).toList();
    }

    public Preview preview(UUID venueAccountId, UUID closureId, ProposalView proposal) {
        UUID venueId = accounts.venueOf(venueAccountId);
        Impact impact = runner.run(new ClosureCommands.Preview(venueId, closureId, proposal(proposal), deps));
        return new Preview(impact.previewToken(), impact.affected().stream()
                .map(a -> new AffectedBooking(a.bookingId(), a.resourceId(), a.startAt(), a.endAt(),
                        a.protectedCancellation()))
                .toList(), impact.ownerPasswordRequired());
    }

    /** Create ({@code closureId} null) or edit. */
    public <R> IdempotentResult<R> save(UUID venueAccountId, UUID closureId, ProposalView proposalView,
                                        String previewToken, String ownerPassword, IdempotentRequest key,
                                        StoredResponse<R> response, Function<View, R> view) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(view, "view");
        UUID venueId = accounts.venueOf(venueAccountId);
        Proposal proposal = proposal(proposalView);
        UUID correlationId = CorrelationId.require();

        LockSet locks;
        boolean protectedSet;
        try {
            Impact planned = runner.run(new ClosureCommands.Preview(venueId, closureId, proposal, deps));
            locks = planned.lockSet(venueId);
            protectedSet = planned.ownerPasswordRequired();
        } catch (ApiException refused) {
            // A replay must answer as stored even when a fresh read is refused now (as the hours commit): the save
            // runs under the venue lock alone and refuses again a request that is new.
            locks = LockSet.builder().venues(venueId).build();
            protectedSet = false;
        }
        ClosureCommands.Save save = new ClosureCommands.Save(venueId, venueAccountId, correlationId, closureId,
                proposal, previewToken, locks, deps);
        Function<Closure, R> answer = closure -> view.apply(View.of(closure));
        if (!protectedSet && ownerPassword == null) {
            return runner.run(idempotency.wrap(key, response, new Mapped<>(save, answer)));
        }
        return gate.run(new ClosureCommands.Protected<>(idempotency.wrap(key, response,
                        new Mapped<>(new ClosureCommands.ProtectedSave(save), answer))),
                venueAccountId, ownerPassword, correlationId);
    }

    public void delete(UUID venueAccountId, UUID closureId) {
        UUID venueId = accounts.venueOf(venueAccountId);
        runner.run(new ClosureCommands.Delete(venueId, venueAccountId, CorrelationId.require(), closureId, deps));
    }

    private static Proposal proposal(ProposalView view) {
        if (view == null) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "proposal");
        }
        return new Proposal(view.kind(), view.startAt(), view.endAt(), view.reason());
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
}
