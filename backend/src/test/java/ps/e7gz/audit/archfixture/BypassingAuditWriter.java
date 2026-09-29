package ps.e7gz.audit.archfixture;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ps.e7gz.common.correlation.CorrelationId;

/** Positive control for {@code AuditArchitectureTest}: everything an audit writer must not do. Never used. */
public class BypassingAuditWriter {

    private final DataSource dataSource;
    private final Clock clock;

    public BypassingAuditWriter(DataSource dataSource, Clock clock) {
        this.dataSource = dataSource;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write() {
        UUID correlation = CorrelationId.generate();
        new JdbcTemplate(dataSource).update("INSERT INTO audit.audit_event (id, occurred_at, correlation_id) VALUES (?, ?, ?)",
                UUID.randomUUID(), Instant.now(), correlation);
        clock.instant();
        System.currentTimeMillis();
    }

    public void deleteOld() {
    }
}
