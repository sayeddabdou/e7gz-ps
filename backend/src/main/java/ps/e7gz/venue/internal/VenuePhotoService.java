package ps.e7gz.venue.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ps.e7gz.audit.api.AuditWriter;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.ApiException;
import ps.e7gz.venue.api.OwnerPasswordGate;
import ps.e7gz.venue.api.VenuePhotos;
import ps.e7gz.venue.internal.VenuePhotoRow.CopySize;

/**
 * Venue photos (PCR-005, arch 09 §4.2 and §5, arch 12 §2). Storage calls run outside the command transactions
 * (arch 01 §15): the signed PUT is issued after the row is committed, and confirm reads the stored file before its
 * command opens.
 */
@Service
@EnableConfigurationProperties({VenuePhotoProperties.class, ObjectStoreProperties.class})
public class VenuePhotoService implements VenuePhotos {

    private final CommandRunner runner;
    private final OwnerPasswordGate ownerPassword;
    private final AuditWriter audit;
    private final ObjectStore store;
    private final VenuePhotoProperties properties;
    private final Clock clock;
    private final JdbcClient jdbc;

    VenuePhotoService(CommandRunner runner, OwnerPasswordGate ownerPassword, AuditWriter audit, ObjectStore store,
                      VenuePhotoProperties properties, Clock clock, DataSource appDataSource) {
        this.runner = runner;
        this.ownerPassword = ownerPassword;
        this.audit = audit;
        this.store = store;
        this.properties = properties;
        this.clock = clock;
        this.jdbc = JdbcClient.create(appDataSource);
    }

    @Override
    public List<Photo> visible(UUID venueId) {
        requireVenue(venueId);
        Instant expiresAt = clock.instant().plus(properties.viewLinkTtl());
        return visibleRows(venueId).stream().map(row -> photo(row, expiresAt)).toList();
    }

    @Override
    public Upload startUpload(UUID venueId, Actor actor, String contentType, long sizeBytes) {
        if (contentType == null || !properties.acceptedTypes().contains(contentType)) {
            throw new ApiException(ApiErrorCode.VENUE_IMAGE_NOT_ACCEPTED, "contentType");
        }
        if (sizeBytes <= 0 || sizeBytes > properties.maxSizeBytes()) {
            throw new ApiException(ApiErrorCode.VENUE_IMAGE_NOT_ACCEPTED, "sizeBytes");
        }
        requireVenue(venueId);
        VenuePhotoRow row = runner.run(
                new VenuePhotoCommands.Start(venueId, actor, contentType, properties.maxVisible()));
        Instant uploadExpiresAt = clock.instant().plus(properties.uploadLinkTtl());
        return new Upload(photo(row, null),
                store.signedPut(row.objectKey(), contentType, sizeBytes, properties.uploadLinkTtl()), uploadExpiresAt);
    }

    @Override
    public Photo confirm(UUID venueId, UUID imageId, Actor actor) {
        requireVenue(venueId);
        VenuePhotoRow row = row(venueId, imageId);
        if (!row.uploaded() && !row.removed()) {
            checkStoredFile(row);
        }
        VenuePhotoRow confirmed = run(new VenuePhotoCommands.Confirm(venueId, imageId, actor,
                properties.maxVisible(), audit), actor);
        return photo(confirmed, clock.instant().plus(properties.viewLinkTtl()));
    }

    @Override
    public List<Photo> reorder(UUID venueId, List<UUID> imageIds, Actor actor) {
        requireVenue(venueId);
        run(new VenuePhotoCommands.Reorder(venueId, imageIds, actor, audit), actor);
        return visible(venueId);
    }

    @Override
    public void remove(UUID venueId, UUID imageId, Actor actor) {
        requireVenue(venueId);
        run(new VenuePhotoCommands.Remove(venueId, imageId, actor, audit), actor);
    }

    @Override
    public Map<UUID, SignedLink> cardImages(Collection<UUID> venueIds) {
        if (venueIds.isEmpty()) {
            return Map.of();
        }
        Instant expiresAt = clock.instant().plus(properties.viewLinkTtl());
        List<VenuePhotoRow> firsts = jdbc.sql(VenuePhotoRow.SELECT + """

                         WHERE i.venue_id = ANY(:venues) AND i.uploaded_at IS NOT NULL AND i.removed_at IS NULL
                           AND i.processed_at IS NOT NULL
                           AND NOT EXISTS (SELECT 1 FROM venue.venue_image e
                                            WHERE e.venue_id = i.venue_id AND e.uploaded_at IS NOT NULL
                                              AND e.removed_at IS NULL AND e.processed_at IS NOT NULL
                                              AND (e.sort_order, e.id) < (i.sort_order, i.id))""")
                .param("venues", venueIds.toArray(UUID[]::new)).query(VenuePhotoRow::map).list();
        Map<UUID, SignedLink> cards = new LinkedHashMap<>();
        for (VenuePhotoRow row : firsts) {
            cards.put(row.venueId(), new SignedLink(
                    store.signedGet(row.copyKey(CopySize.SMALL), properties.viewLinkTtl()), expiresAt));
        }
        return cards;
    }

    @Override
    public List<SignedLink> gallery(UUID venueId) {
        Instant expiresAt = clock.instant().plus(properties.viewLinkTtl());
        return visibleRows(venueId).stream().filter(VenuePhotoRow::processed)
                .map(row -> new SignedLink(store.signedGet(row.copyKey(CopySize.MEDIUM), properties.viewLinkTtl()),
                        expiresAt))
                .toList();
    }

    /**
     * The venue's add, reorder and remove go through the Owner Password gate, which audits and counts a refused
     * password (FRS §2.4 as amended by PCR-005); the Platform Admin's run directly on the admin connection.
     */
    private <R> R run(Command<R> command, Actor actor) {
        return switch (actor) {
            case Actor.Venue venue -> ownerPassword.run(command, venue.venueAccountId(), venue.correlationId());
            case Actor.Admin ignored -> runner.run(command);
        };
    }

    /**
     * Confirm checks what is really stored (arch 09 §4.2): the file exists, its size is within the limit, its
     * Content-Type is the declared one and its bytes are that type and decodable.
     */
    private void checkStoredFile(VenuePhotoRow row) {
        ObjectStore.StoredObject stored = store.head(row.objectKey())
                .orElseThrow(() -> new ApiException(ApiErrorCode.VENUE_IMAGE_NOT_UPLOADED));
        if (stored.sizeBytes() > properties.maxSizeBytes() || !row.contentType().equals(stored.contentType())) {
            throw new ApiException(ApiErrorCode.VENUE_IMAGE_NOT_UPLOADED);
        }
        byte[] bytes = store.read(row.objectKey())
                .orElseThrow(() -> new ApiException(ApiErrorCode.VENUE_IMAGE_NOT_UPLOADED));
        VenuePhotoImages.inspect(bytes, row.contentType(), properties.maxPixels());
    }

    private Photo photo(VenuePhotoRow row, Instant expiresAt) {
        if (!row.processed() || expiresAt == null) {
            return new Photo(row.id(), row.contentType(), null, null, null);
        }
        return new Photo(row.id(), row.contentType(),
                store.signedGet(row.copyKey(CopySize.SMALL), properties.viewLinkTtl()),
                store.signedGet(row.copyKey(CopySize.MEDIUM), properties.viewLinkTtl()), expiresAt);
    }

    private List<VenuePhotoRow> visibleRows(UUID venueId) {
        return jdbc.sql(VenuePhotoRow.VISIBLE).param("venue", venueId).query(VenuePhotoRow::map).list();
    }

    private VenuePhotoRow row(UUID venueId, UUID imageId) {
        return jdbc.sql(VenuePhotoRow.SELECT + " WHERE i.id = :id AND i.venue_id = :venue")
                .param("id", imageId).param("venue", venueId).query(VenuePhotoRow::map).optional()
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND));
    }

    /** The command locks the venue row, so an unknown venue is answered before the command opens. */
    private void requireVenue(UUID venueId) {
        boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM venue.venue WHERE id = :id)").param("id", venueId)
                .query(Boolean.class).single();
        if (!exists) {
            throw new ApiException(ApiErrorCode.NOT_FOUND);
        }
    }
}
