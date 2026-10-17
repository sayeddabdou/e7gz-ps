package ps.e7gz.booking.internal;

import static ps.e7gz.booking.internal.VenueAnswerDb.OWNER;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import ps.e7gz.common.command.CommandContext;

/**
 * TEST-ONLY (E7-113 Phase 3D): PostgreSQL lock-wait evidence for race tests - which backend waits behind which
 * transaction, read from {@code pg_stat_activity} and {@code pg_blocking_pids} on the owner's own connection, outside
 * every command. Polling here only observes; the order of a race is set by the transactions' own locks and gates. Every
 * wait is bounded and fails fast, with the database's view of the waiting backends, when the waiting side ended first.
 */
final class LockWaits {

    private static final Duration POLL = Duration.ofMillis(20);

    private LockWaits() {
    }

    /** A backend waiting on a lock, as PostgreSQL reports it. */
    record Waiter(int pid, String waitEventType, String waitEvent, List<Integer> blockedBy, String query) {
    }

    /** The backend pid of the command's own connection - the transaction that holds its locks. */
    static int backendPid(CommandContext context) {
        return context.jdbc().sql("SELECT pg_backend_pid()").query(Integer.class).single();
    }

    /**
     * Waits, bounded, until a backend waits on a lock held by {@code holderPid}; returns those waiters. Fails at once
     * when {@code waiter} - the task expected to block - finished (returned or threw) before blocking.
     */
    static List<Waiter> awaitBlockedBy(int holderPid, Future<?> waiter, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            List<Waiter> blocked = blockedBy(holderPid);
            if (!blocked.isEmpty()) {
                return blocked;
            }
            failIfDone(waiter, "the task expected to wait behind backend " + holderPid);
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("no backend waited behind backend " + holderPid + " within " + timeout
                        + "; active backends: " + activity());
            }
            pause();
        }
    }

    /** Waits, bounded, for {@code latch}; fails at once when {@code task} ended without reaching it. */
    static void await(CountDownLatch latch, Duration timeout, Future<?> task, String what) {
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (!latch.await(POLL.toNanos(), TimeUnit.NANOSECONDS)) {
                failIfDone(task, what);
                if (System.nanoTime() >= deadline) {
                    throw new AssertionError(what + " was not reached within " + timeout + "; active backends: "
                            + activity());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for " + what, e);
        }
    }

    /** The backends waiting on a lock that {@code holderPid} holds. */
    static List<Waiter> blockedBy(int holderPid) {
        return OWNER.sql("""
                        SELECT pid, wait_event_type, wait_event, pg_blocking_pids(pid) AS blockers, query
                          FROM pg_stat_activity
                         WHERE :holder = ANY (pg_blocking_pids(pid))
                         ORDER BY pid""")
                .param("holder", holderPid)
                .query((rs, n) -> new Waiter(rs.getInt("pid"), rs.getString("wait_event_type"),
                        rs.getString("wait_event"), List.of((Integer[]) rs.getArray("blockers").getArray()),
                        rs.getString("query")))
                .list();
    }

    /** The non-idle backends of this database, for a failure message. */
    static String activity() {
        return OWNER.sql("""
                        SELECT string_agg(format('[pid=%s state=%s wait=%s/%s blockedBy=%s query=%s]', pid, state,
                                                 wait_event_type, wait_event, pg_blocking_pids(pid),
                                                 left(query, 120)), ' ' ORDER BY pid)
                          FROM pg_stat_activity
                         WHERE datname = current_database() AND pid <> pg_backend_pid() AND state <> 'idle'""")
                .query(String.class).optional().orElse("none");
    }

    private static void failIfDone(Future<?> task, String what) {
        if (task == null || !task.isDone()) {
            return;
        }
        try {
            Object result = task.get();
            throw new AssertionError(what + " ended early, returning " + result);
        } catch (ExecutionException e) {
            throw new AssertionError(what + " ended early by throwing", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted reading " + what, e);
        } catch (CancellationException e) {
            throw new AssertionError(what + " was cancelled", e);
        }
    }

    private static void pause() {
        try {
            Thread.sleep(POLL.toMillis());          // poll interval only: the order is proved by pg_blocking_pids
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while polling pg_stat_activity", e);
        }
    }
}
