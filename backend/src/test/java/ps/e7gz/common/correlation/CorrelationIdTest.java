package ps.e7gz.common.correlation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/** Scopes, MDC mirroring, nesting for workers, and header parsing (NFR-32). */
class CorrelationIdTest {

    @Test
    void aScopeSetsTheIdAndTheMdcAndClosingClearsBoth() {
        UUID id = UUID.randomUUID();
        try (CorrelationId.Scope ignored = CorrelationId.open(id)) {
            assertThat(CorrelationId.require()).isEqualTo(id);
            assertThat(MDC.get(CorrelationId.MDC_KEY)).isEqualTo(id.toString());
        }
        assertThat(CorrelationId.current()).isEmpty();
        assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
    }

    /** A worker processing a job on a pooled thread reopens the job's persisted ID, then restores the outer one. */
    @Test
    void scopesNestAndRestore() {
        UUID outer = UUID.randomUUID();
        UUID job = UUID.randomUUID();
        try (CorrelationId.Scope ignored = CorrelationId.open(outer)) {
            try (CorrelationId.Scope inner = CorrelationId.open(job)) {
                assertThat(CorrelationId.require()).isEqualTo(job);
            }
            assertThat(CorrelationId.require()).isEqualTo(outer);
            assertThat(MDC.get(CorrelationId.MDC_KEY)).isEqualTo(outer.toString());
        }
    }

    @Test
    void requireOutsideAScopeIsAWiringDefect() {
        assertThatThrownBy(CorrelationId::require).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anotherThreadDoesNotSeeTheId() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (CorrelationId.Scope ignored = CorrelationId.open(UUID.randomUUID())) {
            assertThat(CompletableFuture.supplyAsync(CorrelationId::current, pool).get()).isEmpty();
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void onlyACanonicalUuidIsAccepted() {
        UUID id = UUID.randomUUID();
        assertThat(CorrelationId.parse(id.toString())).contains(id);
        assertThat(CorrelationId.parse(id.toString().toUpperCase())).contains(id);
        assertThat(CorrelationId.parse(null)).isEmpty();
        assertThat(CorrelationId.parse("")).isEmpty();
        assertThat(CorrelationId.parse("not-a-uuid")).isEmpty();
        assertThat(CorrelationId.parse("1-1-1-1-1")).isEmpty();
        assertThat(CorrelationId.parse(id + "\nX-Injected: yes")).isEmpty();
    }
}
