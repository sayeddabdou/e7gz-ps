package ps.e7gz.inventory.sweep.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import ps.e7gz.inventory.sweep.api.InvariantCheck;
import ps.e7gz.inventory.sweep.api.InvariantSweep;
import ps.e7gz.inventory.sweep.api.InvariantViolation;
import ps.e7gz.inventory.sweep.api.InvariantViolationReporter;
import ps.e7gz.inventory.sweep.api.SweepReport;

/**
 * Runs every {@link InvariantCheck} in one {@code READ ONLY}, {@code REPEATABLE READ} transaction on the app
 * connection: all checks see the same snapshot, and the database itself refuses any write a check might attempt
 * (SQLSTATE {@code 25006}). This is a detector, not a business write, so it does not go through the CommandRunner and
 * takes no command locks. Violations are reported after the snapshot has closed.
 */
@Component
class ReadOnlyInvariantSweep implements InvariantSweep {

    private static final Logger log = LoggerFactory.getLogger(ReadOnlyInvariantSweep.class);

    private final TransactionTemplate snapshot;
    private final JdbcClient jdbc;
    private final List<InvariantCheck> checks;
    private final List<InvariantViolationReporter> reporters;
    private final Clock clock;

    @Autowired
    ReadOnlyInvariantSweep(DataSource appDataSource, List<InvariantCheck> checks,
                           ObjectProvider<InvariantViolationReporter> reporters, Clock clock) {
        this(appDataSource, checks, reporters.orderedStream().toList(), clock);
    }

    ReadOnlyInvariantSweep(DataSource dataSource, List<InvariantCheck> checks,
                           List<InvariantViolationReporter> reporters, Clock clock) {
        JdbcTransactionManager transactions = new JdbcTransactionManager(dataSource);
        transactions.setEnforceReadOnly(true); // SET TRANSACTION READ ONLY, not only a driver hint
        this.snapshot = new TransactionTemplate(transactions);
        this.snapshot.setReadOnly(true);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.jdbc = JdbcClient.create(dataSource);
        this.checks = checks.stream().sorted(Comparator.comparing(InvariantCheck::invariant)).toList();
        this.reporters = List.copyOf(reporters);
        this.clock = Objects.requireNonNull(clock, "clock");
        Set<String> codes = new HashSet<>();
        for (InvariantCheck check : this.checks) {
            if (!codes.add(check.invariant())) {
                throw new IllegalStateException("two invariant checks share the code " + check.invariant());
            }
        }
    }

    @Override
    public SweepReport run() {
        // E7-40: inside a caller's transaction the snapshot would silently be that transaction's; fail instead.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalTransactionStateException("The invariant sweep never runs inside a transaction");
        }
        Instant startedAt = clock.instant();
        List<InvariantViolation> violations = snapshot.execute(status -> {
            List<InvariantViolation> found = new ArrayList<>();
            for (InvariantCheck check : checks) {
                found.addAll(check.detect(jdbc));
            }
            return found;
        });
        SweepReport report = new SweepReport(startedAt, clock.instant(),
                checks.stream().map(InvariantCheck::invariant).toList(), violations);

        for (InvariantViolation violation : report.violations()) {
            log.error("{} invariant violation {} on {} {}: {}", violation.severity(), violation.invariant(),
                    violation.subjectType(), violation.subjectId(), violation.facts());
            reporters.forEach(reporter -> reporter.report(violation));
        }
        log.info("Invariant sweep checked {}: {} violations", report.invariants(), report.violations().size());
        return report;
    }
}
