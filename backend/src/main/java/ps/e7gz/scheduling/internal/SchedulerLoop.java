package ps.e7gz.scheduling.internal;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * The worker profile's scheduler threads (arch 08 section 2.4): each {@link DeadlineWorker} polls every tick (1 second),
 * and sooner when a {@code pg_notify('sched')} arrives on the listening connection. At start-up every recurring system
 * job gets its first run if it has none. On shutdown the threads finish the job in hand; a lease left behind expires
 * by itself (arch 12 section 1.3).
 */
final class SchedulerLoop implements SmartLifecycle {

    /** The {@code LISTEN} / {@code pg_notify} channel. */
    static final String CHANNEL = "sched";

    private static final Logger log = LoggerFactory.getLogger(SchedulerLoop.class);
    private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(30);
    /** The listener checks for shutdown at least this often, whatever the tick. */
    private static final Duration MAX_LISTEN_WAIT = Duration.ofSeconds(1);
    /** Wait before reconnecting a lost listening connection. */
    private static final Duration RECONNECT_WAIT = Duration.ofSeconds(5);

    private final List<DeadlineWorker> workers;
    private final DataSource dataSource;
    private final Duration tick;
    private final Duration listenWait;
    private final WakeSignal wake = new WakeSignal();
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean running;

    SchedulerLoop(List<DeadlineWorker> workers, DataSource appDataSource, Duration tick) {
        if (workers.isEmpty()) {
            throw new IllegalArgumentException("The scheduler needs at least one worker");
        }
        this.workers = List.copyOf(workers);
        this.dataSource = appDataSource;
        this.tick = tick;
        this.listenWait = tick.compareTo(MAX_LISTEN_WAIT) < 0 ? tick : MAX_LISTEN_WAIT;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        try {
            workers.getFirst().scheduleRecurringJobs();
        } catch (RuntimeException e) {
            log.error("Could not schedule the recurring system jobs; they start with the next worker start", e);
        }
        running = true;
        threads.add(thread("e7gz-scheduler-listen", this::listen));
        for (DeadlineWorker worker : workers) {
            threads.add(thread("e7gz-scheduler-" + worker.name(), () -> poll(worker)));
        }
        log.info("Deadline worker started: {} threads, tick {}", workers.size(), tick);
    }

    @Override
    public synchronized void stop() {
        running = false;
        wake.wakeAll();
        for (Thread thread : threads) {
            try {
                thread.join(SHUTDOWN_WAIT.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        threads.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void poll(DeadlineWorker worker) {
        while (running) {
            long seen = wake.generation();
            int settled = 0;
            try {
                settled = worker.fireDueJobs();
            } catch (RuntimeException e) {
                log.error("Deadline worker {} could not poll", worker.name(), e);
            }
            if (settled == 0 && running) {
                try {
                    wake.awaitChange(seen, tick);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** {@code LISTEN sched} on a connection of its own; reconnects a few seconds after the connection is lost. */
    private void listen() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try (Connection connection = dataSource.getConnection()) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("LISTEN " + CHANNEL);
                }
                try {
                    PGConnection notifications = connection.unwrap(PGConnection.class);
                    wake.wakeAll();                                  // anything due while (re)connecting
                    while (running) {
                        PGNotification[] received = notifications.getNotifications((int) listenWait.toMillis());
                        if (received != null && received.length > 0) {
                            wake.wakeAll();
                        }
                    }
                } finally {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("UNLISTEN *");             // the connection goes back to the pool
                    }
                }
            } catch (SQLException e) {
                if (running) {
                    log.warn("Scheduler wake-up connection lost; polling every {} until it is back", tick, e);
                    pauseBeforeReconnect();
                }
            }
        }
    }

    /** Sleeps {@link #RECONNECT_WAIT} in short steps, so a shutdown is never held up by it. */
    private void pauseBeforeReconnect() {
        long deadline = System.nanoTime() + RECONNECT_WAIT.toNanos();
        try {
            while (running && System.nanoTime() < deadline) {
                Thread.sleep(listenWait);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Thread thread(String name, Runnable body) {
        return Thread.ofPlatform().name(name).daemon(true).start(body);
    }
}
