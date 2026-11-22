package ps.e7gz.operations.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ps.e7gz.common.codes.OwnerReviewResolution;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.messaging.api.Idempotency;
import ps.e7gz.messaging.api.IdempotentRequest;
import ps.e7gz.messaging.api.StoredResponse;
import ps.e7gz.operations.internal.OwnerReviewCommands.Resolution;
import ps.e7gz.operations.internal.OwnerReviewCommands.Review;
import ps.e7gz.venue.api.OwnerPasswordGate;
import ps.e7gz.venue.api.VenueAccountVenues;

/**
 * The Venue Dashboard's Owner Reviews and the Platform Admin's read-only queue (E7-84; the E7-77 contract, PO decisions
 * 19 Nov 2026: D3 read-only for the Admin, D6 OPERATIONAL_STATE_CHANGED). Entry has no route: it is the system cascade
 * of an Emergency's end ({@link OwnerReviewCommands#afterBoundary}). A resolution cancels nothing (blocking commitments
 * refuse it, FR-OPS-43), so its Idempotency-Key is optional (the contract's list); when sent, the Owner Password is
 * checked before the key's lookup, as every protected write (PO decision Q3, 24 Oct 2026).
 */
@Service
public class VenueOwnerReviews {

    private final OwnerPasswordGate gate;
    private final VenueAccountVenues accounts;
    private final Idempotency idempotency;
    private final VenueEmergencies emergencies;
    private final JdbcClient jdbc;

    VenueOwnerReviews(OwnerPasswordGate gate, VenueAccountVenues accounts, Idempotency idempotency,
                      VenueEmergencies emergencies, DataSource appDataSource) {
        this.gate = gate;
        this.accounts = accounts;
        this.idempotency = idempotency;
        this.emergencies = emergencies;
        this.jdbc = JdbcClient.create(appDataSource);
    }

    /** {@code operations.owner_review} as the Dashboard sees it. */
    public record View(UUID id, UUID resourceId, Instant enteredAt, Instant resolvedAt,
                       OwnerReviewResolution resolution) {

        static View of(Review review) {
            return new View(review.id(), review.resourceId(), review.enteredAt(), review.resolvedAt(),
                    review.resolution());
        }
    }

    /** As the Platform Admin's queue sees it (D3). */
    public record AdminView(View review, UUID venueId, String venueName, String resourceName) {
    }

    public record AdminPage(List<AdminView> items, String nextCursor) {
    }

    /** The owner's choice; {@code maintenanceEndAt} and {@code maintenanceReason} with EXTEND_MAINTENANCE only. */
    public record ResolutionView(OwnerReviewResolution action, Instant maintenanceEndAt, String maintenanceReason) {
    }

    /** The venue's open Owner Reviews, one per resource, oldest first (FR-OPS-26, FR-OPS-43). */
    public List<View> list(UUID venueAccountId) {
        UUID venueId = accounts.venueOf(venueAccountId);
        return jdbc.sql(OwnerReviewCommands.SELECT + """
                         WHERE r.venue_id = :venue AND o.resolved_at IS NULL
                         ORDER BY o.entered_at, o.id""")
                .param("venue", venueId)
                .query(Review::map).list().stream().map(View::of).toList();
    }

    /**
     * FR-OPS-33: always the Owner Password - checked first, whatever else is wrong with the request. {@code key} null:
     * no Idempotency-Key was sent.
     */
    public <R> R resolve(UUID venueAccountId, UUID resourceId, ResolutionView view, String ownerPassword,
                         IdempotentRequest key, StoredResponse<R> response, Function<View, R> answer) {
        UUID venueId = accounts.venueOf(venueAccountId);
        UUID correlationId = CorrelationId.require();
        Resolution resolution = new Resolution(view.action(), view.maintenanceEndAt(), view.maintenanceReason());
        OwnerReviewCommands.Resolve resolve = new OwnerReviewCommands.Resolve(venueId, venueAccountId, correlationId,
                resourceId, resolution, emergencies.deps());
        if (key == null) {
            return answer.apply(View.of(gate.run(resolve, venueAccountId, ownerPassword, correlationId)));
        }
        return gate.run(new OwnerReviewCommands.ResolveGate<>(idempotency.wrap(key, response,
                        new Mapped<>(resolve, review -> answer.apply(View.of(review))))), venueAccountId, ownerPassword,
                correlationId).body();
    }

    /**
     * D3: every venue's Owner Reviews, newest first, cursor paged as the other admin lists (arch 09 section 1.2): the
     * open ones by default, the resolved ones with {@code resolved = true}.
     */
    public AdminPage adminList(UUID venueId, Boolean resolved, String cursor, Integer limit) {
        int size = limit == null ? VenueEmergencies.DEFAULT_PAGE_SIZE : limit;
        UUID after = VenueEmergencies.decodeCursor(cursor);
        List<AdminView> rows = jdbc.sql("""
                        SELECT o.id, o.resource_id, r.venue_id, o.entered_at, o.entry_cause, o.resolved_at,
                               o.resolution, o.correlation_id, v.name AS venue_name, r.name AS resource_name
                          FROM operations.owner_review o
                          JOIN resource.resource r ON r.id = o.resource_id
                          JOIN venue.venue v ON v.id = r.venue_id
                         WHERE (CAST(:venue AS uuid) IS NULL OR r.venue_id = CAST(:venue AS uuid))
                           AND ((o.resolved_at IS NOT NULL) = :resolved)
                           AND (CAST(:after AS uuid) IS NULL
                                OR (o.entered_at, o.id) < (SELECT c.entered_at, c.id FROM operations.owner_review c
                                                            WHERE c.id = CAST(:after AS uuid)))
                         ORDER BY o.entered_at DESC, o.id DESC
                         LIMIT :limit""")
                .param("venue", venueId).param("resolved", Boolean.TRUE.equals(resolved))
                .param("after", after).param("limit", size + 1)
                .query((rs, n) -> new AdminView(View.of(Review.map(rs, n)), rs.getObject("venue_id", UUID.class),
                        rs.getString("venue_name"), rs.getString("resource_name")))
                .list();
        boolean more = rows.size() > size;
        List<AdminView> page = more ? rows.subList(0, size) : rows;
        return new AdminPage(page, more ? VenueEmergencies.encodeCursor(page.getLast().review().id()) : null);
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
