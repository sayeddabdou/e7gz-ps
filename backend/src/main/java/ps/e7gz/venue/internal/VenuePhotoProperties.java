package ps.e7gz.venue.internal;

import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Venue photo limits and timings ({@code e7gz.venue-photos.*}). PCR-005 makes the type, size and count limits
 * technical defaults held in configuration; the link lifetimes, copies and clean-up are the Product Owner decisions of
 * 3 Oct 2026.
 *
 * @param contentTypes     accepted types (PCR-005: JPEG, PNG, WebP)
 * @param maxSizeBytes     largest accepted file (PCR-005: 5 MB)
 * @param maxVisible       most visible photos per venue (PCR-005: 10)
 * @param maxPixels        largest picture (width × height) the copy job will decode; a small file that unpacks into a
 *                         huge picture is refused at confirm
 * @param uploadLinkTtl    signed PUT lifetime (10 minutes)
 * @param viewLinkTtl      signed GET lifetime (1 hour)
 * @param smallMaxSide     venue-card copy: longest side in pixels
 * @param mediumMaxSide    Venue Details copy: longest side in pixels
 * @param abandonAfter     an upload not confirmed within this time is hidden and its file deleted (24 hours)
 * @param processEvery     how often the worker looks for photos without copies
 * @param cleanupAt        when the nightly clean-up runs (Africa/Cairo)
 */
@ConfigurationProperties("e7gz.venue-photos")
public record VenuePhotoProperties(
        @DefaultValue({"image/jpeg", "image/png", "image/webp"}) List<String> contentTypes,
        @DefaultValue("5242880") long maxSizeBytes,
        @DefaultValue("10") int maxVisible,
        @DefaultValue("50000000") long maxPixels,
        @DefaultValue("10m") Duration uploadLinkTtl,
        @DefaultValue("1h") Duration viewLinkTtl,
        @DefaultValue("480") int smallMaxSide,
        @DefaultValue("1280") int mediumMaxSide,
        @DefaultValue("24h") Duration abandonAfter,
        @DefaultValue("30s") Duration processEvery,
        @DefaultValue("03:30") LocalTime cleanupAt) {

    Set<String> acceptedTypes() {
        return Set.copyOf(contentTypes);
    }
}
