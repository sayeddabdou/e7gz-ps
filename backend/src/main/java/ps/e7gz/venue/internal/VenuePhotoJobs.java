package ps.e7gz.venue.internal;

import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.venue.internal.VenuePhotoRow.CopySize;

/**
 * The photo jobs of the worker profile (PO decisions 3 Oct 2026): make the small and medium copies of confirmed photos,
 * and every night hide uploads that were never confirmed and delete their files. Simple timers until the deadline engine
 * exists; E7-40 moves them onto {@code technical.scheduled_job}. Both are safe to run on several workers at once: a
 * copy is the same file whoever makes it, and each update only touches rows still in the expected state.
 */
@Configuration
@Profile("worker")
@EnableScheduling
class VenuePhotoJobs {

    private static final Logger log = LoggerFactory.getLogger(VenuePhotoJobs.class);
    private static final int BATCH = 20;

    private final CommandRunner runner;
    private final ObjectStore store;
    private final VenuePhotoProperties properties;
    private final JdbcClient jdbc;

    VenuePhotoJobs(CommandRunner runner, ObjectStore store, VenuePhotoProperties properties, DataSource appDataSource) {
        this.runner = runner;
        this.store = store;
        this.properties = properties;
        this.jdbc = JdbcClient.create(appDataSource);
    }

    @Scheduled(fixedDelayString = "${e7gz.venue-photos.process-every:30s}", initialDelayString = "10s")
    void makeCopies() {
        try (CorrelationId.Scope ignored = CorrelationId.open(CorrelationId.generate())) {
            List<VenuePhotoRow> pending = jdbc.sql(VenuePhotoRow.SELECT + """

                             WHERE i.uploaded_at IS NOT NULL AND i.processed_at IS NULL AND i.removed_at IS NULL
                             ORDER BY i.uploaded_at
                             LIMIT :batch""").param("batch", BATCH).query(VenuePhotoRow::map).list();
            for (VenuePhotoRow row : pending) {
                try {
                    makeCopies(row);
                } catch (RuntimeException e) {
                    // Left unprocessed: retried on the next run; the photo shows a placeholder meanwhile.
                    log.error("Could not make the copies of venue photo {}", row.id(), e);
                }
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

    @Scheduled(cron = "${e7gz.venue-photos.cleanup-cron:0 30 3 * * *}", zone = VenueCommands.TIMEZONE)
    void cleanUpAbandonedUploads() {
        try (CorrelationId.Scope ignored = CorrelationId.open(CorrelationId.generate())) {
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
}
