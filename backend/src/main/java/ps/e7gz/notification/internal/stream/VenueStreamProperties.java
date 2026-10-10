package ps.e7gz.notification.internal.stream;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The venue live channel ({@code e7gz.venue-stream}; ADR-014, arch 08 section 5).
 *
 * @param heartbeat     a {@code : heartbeat} comment this often keeps proxies from closing an idle stream; each beat
 *                      also re-checks that the open streams' accounts are still ENABLED (PO decision 10 Oct 2026)
 * @param maxDuration   a stream is closed after this long and the Dashboard reconnects (and refetches), so no
 *                      connection lives forever
 * @param listenPoll    how often the listening connection checks for notifications and for streams opened or closed
 * @param reconnectWait wait before reopening a lost listening connection
 */
@ConfigurationProperties("e7gz.venue-stream")
public record VenueStreamProperties(
        @DefaultValue("15s") Duration heartbeat,
        @DefaultValue("1h") Duration maxDuration,
        @DefaultValue("250ms") Duration listenPoll,
        @DefaultValue("5s") Duration reconnectWait) {
}
