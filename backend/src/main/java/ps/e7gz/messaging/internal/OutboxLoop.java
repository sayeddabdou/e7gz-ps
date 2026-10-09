package ps.e7gz.messaging.internal;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * The worker profile's dispatcher threads (arch 08 section 4.2): each {@link OutboxDispatcher} delivers events until none
 * is due, then waits one tick (1 second, the safety net: {@code NOTIFY} is not durable) or until a
 * {@code pg_notify('outbox')} arrives on the listening connection - milliseconds after the publishing command commits. On
 * shutdown the threads finish the event in hand; an event left locked by a dying process is released with its
 * connection.
 */
final class OutboxLoop implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxLoop.class);
    private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(30);
    /** The listener checks for shutdown at least this often, whatever the tick. */
    private static final Duration MAX_LISTEN_WAIT = Duration.ofSeconds(1);
    /** Wait before reconnecting a lost listening connection. */
    private static final Duration RECONNECT_WAIT = Duration.ofSeconds(5);

    private final List<OutboxDispatcher> dispatchers;
    private final DataSource dataSource;
    private final Duration tick;
    private final Duration listenWait;
    private final Object monitor = new Object();
    private final List<Thread> threads = new ArrayList<>();
    /** Bumped by every wake-up; a dispatcher that saw it change while busy polls again at once. */
    private long generation;
    private volatile boolean running;

    OutboxLoop(List<OutboxDispatcher> dispatchers, DataSource appDataSource, Duration tick) {
        if (dispatchers.isEmpty()) {
            throw new IllegalArgumentException("The outbox needs at least one dispatcher");
        }
        this.dispatchers = List.copyOf(dispatchers);
        this.dataSource = appDataSource;
        this.tick = tick;
        this.listenWait = tick.compareTo(MAX_LISTEN_WAIT) < 0 ? tick : MAX_LISTEN_WAIT;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        threads.add(thread("e7gz-outbox-listen", this::listen));
        for (OutboxDispatcher dispatcher : dispatchers) {
            threads.add(thread("e7gz-outbox-" + dispatcher.name(), () -> poll(dispatcher)));
        }
        log.info("Outbox dispatcher started: {} threads, tick {}", dispatchers.size(), tick);
    }

    @Override
    public synchronized void stop() {
        running = false;
        wakeAll();
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

    private void poll(OutboxDispatcher dispatcher) {
        while (running) {
            long seen = generation();
            boolean delivered = false;
            try {
                delivered = dispatcher.dispatchNext();
            } catch (RuntimeException e) {
                log.error("Outbox dispatcher {} could not dispatch", dispatcher.name(), e);
            }
            if (!delivered && running) {
                try {
                    awaitChange(seen);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** {@code LISTEN outbox} on a connection of its own; reconnects a few seconds after the connection is lost. */
    private void listen() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try (Connection connection = dataSource.getConnection()) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("LISTEN " + JdbcOutboxStore.CHANNEL);
                }
                try {
                    PGConnection notifications = connection.unwrap(PGConnection.class);
                    wakeAll();                                       // anything committed while (re)connecting
                    while (running) {
                        PGNotification[] received = notifications.getNotifications((int) listenWait.toMillis());
                        if (received != null && received.length > 0) {
                            wakeAll();
                        }
                    }
                } finally {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("UNLISTEN *");             // the connection goes back to the pool
                    }
                }
            } catch (SQLException e) {
                if (running) {
                    log.warn("Outbox wake-up connection lost; polling every {} until it is back", tick, e);
                    pauseBeforeReconnect();
                }
            }
        }
    }

    private void wakeAll() {
        synchronized (monitor) {
            generation++;
            monitor.notifyAll();
        }
    }

    private long generation() {
        synchronized (monitor) {
            return generation;
        }
    }

    /** Waits until a wake-up after {@code seen}, or one tick. */
    private void awaitChange(long seen) throws InterruptedException {
        long deadline = System.nanoTime() + tick.toNanos();
        synchronized (monitor) {
            while (generation == seen && running) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return;
                }
                TimeUnit.NANOSECONDS.timedWait(monitor, left);
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
