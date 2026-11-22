package ps.e7gz.operations.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ps.e7gz.admin.api.AdminAlerts;
import ps.e7gz.audit.api.AuditWriter;
import ps.e7gz.common.codes.EmergencyConversionTarget;
import ps.e7gz.common.codes.EmergencyScope;
import ps.e7gz.common.codes.EmergencyStatus;
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
import ps.e7gz.messaging.api.Idempotency;
import ps.e7gz.messaging.api.IdempotentRequest;
import ps.e7gz.messaging.api.IdempotentResult;
import ps.e7gz.messaging.api.StoredResponse;
import ps.e7gz.inventory.ledger.api.ActiveCommitments;
import ps.e7gz.inventory.ledger.api.ReservationLedger;
import ps.e7gz.messaging.api.Outbox;
import ps.e7gz.operations.api.EmergencyBookings;
import ps.e7gz.operations.internal.EmergencyCommands.Conversion;
import ps.e7gz.operations.internal.EmergencyCommands.ConversionImpact;
import ps.e7gz.operations.internal.EmergencyCommands.Deps;
import ps.e7gz.operations.internal.EmergencyCommands.Emergency;
import ps.e7gz.operations.internal.EmergencyCommands.ExtensionImpact;
import ps.e7gz.operations.internal.EmergencyCommands.Subject;
import ps.e7gz.resource.api.ResourceBaseStateChanges;
import ps.e7gz.resource.api.ResourceCatalog;
import ps.e7gz.resource.api.ResourceStates;
import ps.e7gz.scheduling.api.ScheduledJobs;
import ps.e7gz.tournament.api.TournamentCascades;
import ps.e7gz.venue.api.HoursChangeBookings;
import ps.e7gz.venue.api.OperatingSessions;
import ps.e7gz.venue.api.OwnerPasswordGate;
import ps.e7gz.venue.api.VenueAccountVenues;

/**
 * The Venue Dashboard's Emergencies and the Platform Admin's monitor (E7-82; the E7-77 contract D1, D2, D3, D5, D6):
 * list, declare, preview and extend, end, preview and convert. Every write is the flow of the Closure save and the hours
 * commit: what it reaches is read first, without writing, only to name the locks; the write re-reads under them. Every
 * write cancels commitments or may move refunds, so it runs once per Idempotency-Key (ADR-015, PO decision 19 Nov
 * 2026).
 */
@Service
public class VenueEmergencies {

    static final int DEFAULT_PAGE_SIZE = 20;
    /** A declaration whose reach grew between its plan and its write plans again, this many times at most. */
    static final int DECLARE_ATTEMPTS = 3;

    private final CommandRunner runner;
    private final OwnerPasswordGate gate;
    private final VenueAccountVenues accounts;
    private final Idempotency idempotency;
    private final Deps deps;
    private final JdbcClient jdbc;

    VenueEmergencies(CommandRunner runner, OwnerPasswordGate gate, VenueAccountVenues accounts, Idempotency idempotency,
                     EmergencyBookings bookings, TournamentCascades tournaments, ResourceStates resources,
                     ResourceCatalog catalog, OperatingSessions sessions, ActiveCommitments commitments,
                     ResourceBaseStateChanges baseStates, HoursChangeBookings hoursBookings, ReservationLedger ledger,
                     AuditWriter audit, Outbox outbox, ScheduledJobs jobs, ObjectMapper json,
                     DataSource appDataSource, TimerPauses timers, AdminAlerts alerts) {
        this.runner = runner;
        this.gate = gate;
        this.accounts = accounts;
        this.idempotency = idempotency;
        this.deps = new Deps(bookings, tournaments, resources, catalog, sessions, commitments, baseStates, audit,
                outbox, jobs, gate, new ClosureCommands.Deps(commitments, hoursBookings, ledger, catalog, audit, outbox,
                gate), json, timers, alerts);
        this.jdbc = JdbcClient.create(appDataSource);
    }

    Deps deps() {
        return deps;
    }

    /** {@code operations.emergency} as the Dashboard sees it. */
    public record View(UUID id, EmergencyScope scope, UUID resourceId, EmergencyStatus status, Instant declaredAt,
                       Instant expectedEndAt, String reason, EmergencyConversionTarget convertedTo, Instant endedAt) {

        static View of(Emergency e) {
            return new View(e.id(), e.scope(), e.resourceId(), e.status(), e.declaredAt(), e.expectedEndAt(),
                    e.reason(), e.convertedTo(), e.endedAt());
        }
    }

    /** As the Platform Admin's monitor sees it (D3). */
    public record AdminView(View emergency, UUID venueId, String venueName, String resourceName) {
    }

    public record AdminPage(List<AdminView> items, String nextCursor) {
    }

    public record Preview(String previewToken, List<AffectedBooking> affected, boolean ownerPasswordRequired) {
    }

    public record AffectedBooking(UUID bookingId, UUID resourceId, Instant startAt, Instant endAt) {
    }

    /** The replacement asked for: the Closure's kind, end and reason, or the Maintenance's end and reason. */
    public record ConversionView(EmergencyConversionTarget target, VenueClosureKind closureKind, Instant endAt,
                                 String reason) {
    }

    /** D1: the venue's unresolved Emergencies (ACTIVE and AWAITING_CONFIRMATION), newest first. */
    public List<View> list(UUID venueAccountId) {
        UUID venueId = accounts.venueOf(venueAccountId);
        return jdbc.sql(EmergencyCommands.SELECT + """
                         WHERE e.venue_id = :venue AND e.status IN ('ACTIVE','AWAITING_CONFIRMATION')
                         ORDER BY e.declared_at DESC, e.id DESC""")
                .param("venue", venueId)
                .query(Emergency::map).list().stream().map(View::of).toList();
    }

    /** A venue Emergency Closure (no {@code resourceId}) or a resource Emergency Outage, starting now. */
    public <R> IdempotentResult<R> declare(UUID venueAccountId, UUID resourceId, String reason, Instant expectedEndAt,
                                           IdempotentRequest key, StoredResponse<R> response,
                                           Function<View, R> view) {
        Objects.requireNonNull(key, "key");
        UUID venueId = accounts.venueOf(venueAccountId);
        Subject subject = new Subject(venueId, resourceId == null ? EmergencyScope.VENUE : EmergencyScope.RESOURCE,
                resourceId);
        UUID correlationId = CorrelationId.require();
        Function<Emergency, R> answer = emergency -> view.apply(View.of(emergency));
        for (int attempt = 1; ; attempt++) {
            LockSet locks = runner.run(new EmergencyCommands.Plan(venueId, subject, null, expectedEndAt, deps));
            EmergencyCommands.Declaration declaration = new EmergencyCommands.Declaration(subject, venueAccountId,
                    correlationId, reason, expectedEndAt, locks, deps);
            Command<Emergency> declare = subject.scope() == EmergencyScope.VENUE
                    ? new EmergencyCommands.DeclareVenue(declaration)
                    : new EmergencyCommands.DeclareResource(declaration);
            try {
                return runner.run(idempotency.wrap(key, response, new Mapped<>(declare, answer)));
            } catch (EmergencyCommands.StaleLocks grown) {
                if (attempt >= DECLARE_ATTEMPTS) {
                    throw new IllegalStateException("The Emergency's reach kept growing while it was declared", grown);
                }
            }
        }
    }

    public Preview previewExtension(UUID venueAccountId, UUID emergencyId, Instant expectedEndAt) {
        UUID venueId = accounts.venueOf(venueAccountId);
        ExtensionImpact impact = runner.run(new EmergencyCommands.PreviewExtension(venueId, emergencyId, expectedEndAt,
                deps));
        return new Preview(impact.previewToken(), impact.affected().stream()
                .map(b -> new AffectedBooking(b.bookingId(), b.resourceId(), b.startAt(), b.endAt())).toList(), false);
    }

    public <R> IdempotentResult<R> extend(UUID venueAccountId, UUID emergencyId, Instant expectedEndAt,
                                          String previewToken, IdempotentRequest key, StoredResponse<R> response,
                                          Function<View, R> view) {
        Objects.requireNonNull(key, "key");
        UUID venueId = accounts.venueOf(venueAccountId);
        LockSet locks = runner.run(new EmergencyCommands.Plan(venueId, null, emergencyId, expectedEndAt, deps));
        return runner.run(idempotency.wrap(key, response, new Mapped<>(new EmergencyCommands.Extend(venueId,
                venueAccountId, CorrelationId.require(), emergencyId, expectedEndAt, previewToken, locks, deps),
                emergency -> view.apply(View.of(emergency)))));
    }

    public <R> IdempotentResult<R> end(UUID venueAccountId, UUID emergencyId, IdempotentRequest key,
                                       StoredResponse<R> response, Function<View, R> view) {
        Objects.requireNonNull(key, "key");
        UUID venueId = accounts.venueOf(venueAccountId);
        UUID correlationId = CorrelationId.require();
        // E7-84: the end of a resource Emergency may enter an Owner Review, whose cascade reaches the room's Customers
        // and Tournaments; a reach grown between plan and write plans again, as a declaration does
        for (int attempt = 1; ; attempt++) {
            LockSet locks = runner.run(new SubjectLocks(venueId, emergencyId, deps)).build();
            try {
                return runner.run(idempotency.wrap(key, response, new Mapped<>(new EmergencyCommands.End(venueId,
                        venueAccountId, correlationId, emergencyId, locks, deps),
                        emergency -> view.apply(View.of(emergency)))));
            } catch (EmergencyCommands.StaleLocks grown) {
                if (attempt >= DECLARE_ATTEMPTS) {
                    throw new IllegalStateException("The Owner Review's reach kept growing while the Emergency ended",
                            grown);
                }
            }
        }
    }

    public Preview previewConversion(UUID venueAccountId, UUID emergencyId, ConversionView conversion) {
        UUID venueId = accounts.venueOf(venueAccountId);
        ConversionImpact impact = runner.run(new EmergencyCommands.PreviewConversion(venueId, emergencyId,
                conversion(conversion), deps));
        return new Preview(impact.previewToken(), impact.affected().stream()
                .map(a -> new AffectedBooking(a.bookingId(), a.resourceId(), a.startAt(), a.endAt())).toList(),
                impact.ownerPasswordRequired());
    }

    /**
     * The conversion save. A target that needs the Owner Password - or any request that sends one - runs the protected
     * save, which the gate checks before the Idempotency-Key lookup (PO decision Q3, 24 Oct 2026), audited under the
     * target's own action id (FR-OPS-23); otherwise the unprotected one, which still answers 423
     * OWNER_PASSWORD_REQUIRED if, under the locks, the target came to need it.
     */
    public <R> IdempotentResult<R> convert(UUID venueAccountId, UUID emergencyId, ConversionView view,
                                           String previewToken, String ownerPassword, IdempotentRequest key,
                                           StoredResponse<R> response, Function<View, R> answerView) {
        Objects.requireNonNull(key, "key");
        UUID venueId = accounts.venueOf(venueAccountId);
        Conversion conversion = conversion(view);
        LockSet.Builder locks = runner.run(new SubjectLocks(venueId, emergencyId, deps));
        boolean protectedTarget;
        try {
            ConversionImpact planned = runner.run(new EmergencyCommands.PreviewConversion(venueId, emergencyId,
                    conversion, deps));
            planned.affected().forEach(row -> {
                locks.resources(row.resourceId());
                if (row.customerId() != null) {
                    locks.users(row.customerId());
                }
            });
            protectedTarget = planned.ownerPasswordRequired();
        } catch (ApiException refused) {
            // A replay must answer as stored even when a fresh read is refused now; a new request is refused again
            // under the locks
            protectedTarget = false;
        }
        UUID correlationId = CorrelationId.require();
        EmergencyCommands.Convert convert = new EmergencyCommands.Convert(venueId, venueAccountId, correlationId,
                emergencyId, conversion, previewToken, locks.build(), deps);
        Function<Emergency, R> answer = emergency -> answerView.apply(View.of(emergency));
        if (!protectedTarget && ownerPassword == null) {
            return runner.run(idempotency.wrap(key, response, new Mapped<>(convert, answer)));
        }
        Command<IdempotentResult<R>> gated = switch (conversion.target()) {
            case CLOSURE -> new EmergencyCommands.ClosureGate<>(idempotency.wrap(key, response,
                    new Mapped<>(new EmergencyCommands.ProtectedClosureConvert(convert), answer)));
            case MAINTENANCE -> new EmergencyCommands.MaintenanceGate<>(idempotency.wrap(key, response,
                    new Mapped<>(new EmergencyCommands.ProtectedMaintenanceConvert(convert), answer)));
            case DISABLED, ARCHIVED -> new EmergencyCommands.ResourceGate<>(idempotency.wrap(key, response,
                    new Mapped<>(new EmergencyCommands.ProtectedResourceConvert(convert), answer)));
            case OPENING_HOURS_CHANGE -> throw new ApiException(ApiErrorCode.CONVERSION_TARGET_NOT_ALLOWED);
        };
        return gate.run(gated, venueAccountId, ownerPassword, correlationId);
    }

    /**
     * D3: every venue's Emergencies, newest first, cursor paged as the other admin lists (arch 09 section 1.2); without
     * {@code status}, the unresolved ones.
     */
    public AdminPage adminList(UUID venueId, EmergencyStatus status, String cursor, Integer limit) {
        int size = limit == null ? DEFAULT_PAGE_SIZE : limit;
        UUID after = decodeCursor(cursor);
        List<AdminView> rows = jdbc.sql("""
                        SELECT e.id, e.scope, e.venue_id, e.resource_id, e.status, e.declared_at, e.expected_end_at,
                               e.reason, e.converted_to, e.ended_at, v.name AS venue_name, r.name AS resource_name
                          FROM operations.emergency e
                          JOIN venue.venue v ON v.id = e.venue_id
                          LEFT JOIN resource.resource r ON r.id = e.resource_id
                         WHERE (CAST(:venue AS uuid) IS NULL OR e.venue_id = CAST(:venue AS uuid))
                           AND ((CAST(:status AS text) IS NULL AND e.status IN ('ACTIVE','AWAITING_CONFIRMATION'))
                                OR e.status = CAST(:status AS text))
                           AND (CAST(:after AS uuid) IS NULL
                                OR (e.declared_at, e.id) < (SELECT c.declared_at, c.id FROM operations.emergency c
                                                             WHERE c.id = CAST(:after AS uuid)))
                         ORDER BY e.declared_at DESC, e.id DESC
                         LIMIT :limit""")
                .param("venue", venueId).param("status", status == null ? null : status.name())
                .param("after", after).param("limit", size + 1)
                .query((rs, n) -> new AdminView(View.of(Emergency.map(rs, n)), rs.getObject("venue_id", UUID.class),
                        rs.getString("venue_name"), rs.getString("resource_name")))
                .list();
        boolean more = rows.size() > size;
        List<AdminView> page = more ? rows.subList(0, size) : rows;
        return new AdminPage(page, more ? encodeCursor(page.getLast().emergency().id())
                : null);
    }

    private static Conversion conversion(ConversionView view) {
        if (view == null || view.target() == null) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "target");
        }
        return new Conversion(view.target(), view.closureKind(), view.endAt(), view.reason());
    }

    /**
     * Read-only: the venue, and the Emergency's resource when it has one - the locks of an end or a conversion - and
     * E7-83: the Resource and Customer of every timer paused at its venue (or on its resource), which the resolution
     * decides again at once ({@link TimerPauses#afterResolution}; one paused meanwhile is left to a check job).
     */
    private record SubjectLocks(UUID venueId, UUID emergencyId, Deps deps) implements Command<LockSet.Builder> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.builder().venues(venueId).build());
        }

        @Override
        public LockSet.Builder execute(CommandContext context) {
            LockSet.Builder locks = LockSet.builder().venues(venueId);
            Optional<Emergency> emergency = EmergencyCommands.read(context, venueId, emergencyId);
            emergency.map(Emergency::resourceId).filter(Objects::nonNull).ifPresent(locks::resources);
            emergency.ifPresent(found -> deps.bookings().pausedTimers(context, venueId, found.resourceId())
                    .forEach(timer -> {
                        locks.resources(timer.resourceId());
                        locks.users(timer.customerId());
                    }));
            // E7-84: an end may enter an Owner Review (FR-OPS-26); a conversion never does, but shares these locks
            emergency.ifPresent(found -> EmergencyCommands.ownerReviewLocks(context, deps, found, locks));
            return locks;
        }
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

    static OffsetDateTime utc(Instant instant) {
        return MaintenanceCommands.utc(instant);
    }

    /** The opaque id of the last row of the previous page, as the admin alert inbox (arch 09 section 1.2). */
    static String encodeCursor(UUID id) {
        java.nio.ByteBuffer bytes = java.nio.ByteBuffer.allocate(16).putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits());
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.array());
    }

    /** Null or blank is the first page; anything else that is not a cursor is VALIDATION_FAILED on {@code cursor}. */
    static UUID decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            byte[] bytes = java.util.Base64.getUrlDecoder().decode(cursor);
            if (bytes.length != 16) {
                throw new IllegalArgumentException("cursor length");
            }
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
            return new UUID(buffer.getLong(), buffer.getLong());
        } catch (IllegalArgumentException malformed) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "cursor");
        }
    }
}
