package ps.e7gz.common.web;

import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Component;
import ps.e7gz.common.correlation.CorrelationId;

/**
 * Builds {@code meta} for every response (arch 09 §1.3): the request's correlation ID and {@code serverTime} from the
 * injected {@link Clock} (arch 08 §8), so a test that fixes the clock sees a fixed {@code serverTime}.
 */
@Component
public class ResponseMetaFactory {

    private final Clock clock;

    public ResponseMetaFactory(Clock clock) {
        this.clock = clock;
    }

    public ResponseMeta meta() {
        // Outside a request scope (should not happen behind CorrelationIdFilter) a fresh ID still identifies the log line.
        String correlationId = CorrelationId.current().orElseGet(CorrelationId::generate).toString();
        return new ResponseMeta(correlationId, Instant.now(clock));
    }

    /** The success envelope {@code {data, meta}}. */
    public <T> DataResponse<T> data(T data) {
        return new DataResponse<>(data, meta());
    }
}
