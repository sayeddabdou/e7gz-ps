package ps.e7gz.common.command.archfixture;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Negative control for {@code CommandBoundaryArchitectureTest}: technical code that is not a Command (e.g. a worker)
 * may use transaction APIs; E7-15 does not prohibit that globally.
 */
@Transactional
public class InfrastructureTransactionWorker {

    @Transactional
    public void poll() {
    }

    public void pollByHand(PlatformTransactionManager manager) {
        new TransactionTemplate(manager).executeWithoutResult(status -> { });
    }
}
