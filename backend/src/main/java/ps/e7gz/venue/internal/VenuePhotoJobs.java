package ps.e7gz.venue.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.scheduling.api.Recurrence;
import ps.e7gz.scheduling.api.RecurringJob;
import ps.e7gz.venue.internal.VenuePhotoRow.CopySize;

/**
 * The photo jobs of the worker profile (PO decisions 3 Oct 2026): make the small and medium copies of confirmed photos,
 * and every night hide uploads that were never confirmed and delete their files. Recurring system jobs on
 * {@code technical.scheduled_job} (E7-40): the copies every {@code e7gz.venue-photos.process-every}, the clean-up nightly
 * at {@code e7gz.venue-photos.cleanup-at}, Africa/Cairo. Both are safe to repeat and to run on several workers at once:
 * a copy is the same file whoever makes it, and each update only touches rows still in the expected state.
 */
@Configuration(proxyBeanMethods = false)
@Profile("worker")
class VenuePhotoJobs {

    static final String COPIES = "VENUE_PHOTO_COPIES";
    static final String CLEANUP = "VENUE_PHOTO_CLEANUP";

    private static final Logger log = LoggerFactory.getLogger(VenuePhotoJobs.class);
    private static final int BATCH = 20;
    /** A photo whose copies could not be made is retried after this, so it never holds up newer photos (E7-24). */
    static final Duration RETRY_FAILED_AFTER = Duration.ofHours(1);

    private final CommandRunner runner;
    private final ObjectStore store;
    private final VenuePhotoProperties properties;
    private final Clock clock;
    private final JdbcClient jdbc;
    /** This worker's photos that failed, with the time they may be tried again. Lost on restart: they are retried. */
    private final Map<UUID, Instant> failedUntil = new ConcurrentHashMap<>();

    VenuePhotoJobs(CommandRunner runner, ObjectStore store, VenuePhotoProperties properties, Clock clock,
                   DataSource appDataSource) {
        this.runner = runner;
        this.store = store;
        this.properties = properties;
        this.clock = clock;
        this.jdbc = JdbcClient.create(appDataSource);
    }

    @Bean
    RecurringJob venuePhotoCopiesJob() {
        return new RecurringJob(COPIES, Recurrence.every(properties.processEvery()), this::makeCopies);
    }

    @Bean
    RecurringJob venuePhotoCleanupJob() {
        return new RecurringJob(CLEANUP, Recurrence.nightlyAt(properties.cleanupAt()), this::cleanUpAbandonedUploads);
    }

    void makeCopies() {
        Instant now = clock.instant();
        failedUntil.values().removeIf(until -> !until.isAfter(now));
        List<VenuePhotoRow> pending = jdbc.sql(VenuePhotoRow.SELECT + """

                         WHERE i.uploaded_at IS NOT NULL AND i.processed_at IS NULL AND i.removed_at IS NULL
                           AND NOT (i.id = ANY(:skip))
                         ORDER BY i.uploaded_at
                         LIMIT :batch""")
                .param("skip", failedUntil.keySet().toArray(UUID[]::new)).param("batch", BATCH)
                .query(VenuePhotoRow::map).list();
        for (VenuePhotoRow row : pending) {
            try {
                makeCopies(row);
            } catch (RuntimeException e) {
                // Set aside and retried later; the photo shows a placeholder meanwhile.
                failedUntil.put(row.id(), now.plus(RETRY_FAILED_AFTER));
                log.error("Could not make the copies of venue photo {}", row.id(), e);
            }
        }
    }

    void makeCopies(VenuePhotoRow row) {
        byte[] original = store.read(row.objectKey())
                .orElseThrow(() -> new IllegalStateException("The original of a confirmed photo is missing"));
        VenuePhotoImages.Size size = VenuePhotoImages.inspect(original, row.contentType(), properties.maxPixels());
        String type = row.copyContentType();
        store.write(row.copyKey(CopySize.SMALL),
                VenuePhotoImages.copy(original, size, properties.smallMaxSide(), type), type);
        store.write(row.copyKey(CopySize.MEDIUM),
                VenuePhotoImages.copy(original, size, properties.mediumMaxSide(), type), type);
        runner.run(new VenuePhotoCommands.MarkProcessed(row.venueId(), row.id()));
    }

    void cleanUpAbandonedUploads() {
        List<String> keys = runner.run(new VenuePhotoCommands.HideAbandoned(properties.abandonAfter()));
        for (String key : keys) {
            try {
                store.delete(key);
            } catch (RuntimeException e) {
                log.warn("Could not delete the file of an abandoned photo upload {}", key, e);
            }
        }
        if (!keys.isEmpty()) {
            log.info("Hid {} photo uploads that were never confirmed", keys.size());
        }
    }
}
