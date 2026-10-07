package ps.e7gz.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/** E7-34: the discovery report's p95 is the nearest-rank percentile (runs in {@code check}; the report does not). */
class DiscoveryPerfPercentileTest {

    @Test
    void p95IsTheNearestRankPercentile() {
        List<Long> hundred = LongStream.rangeClosed(1, 100).boxed().toList().reversed();

        assertThat(DiscoveryPerfReport.percentile(hundred, 95)).isEqualTo(95);
        assertThat(DiscoveryPerfReport.percentile(hundred, 50)).isEqualTo(50);
        assertThat(DiscoveryPerfReport.percentile(hundred, 100)).isEqualTo(100);
        assertThat(DiscoveryPerfReport.percentile(List.of(7L), 95)).isEqualTo(7);
        assertThat(DiscoveryPerfReport.TARGET_P95.toMillis()).isEqualTo(1_000);
    }
}
