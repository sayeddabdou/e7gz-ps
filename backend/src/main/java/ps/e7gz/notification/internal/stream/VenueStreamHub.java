package ps.e7gz.notification.internal.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The open venue streams of this API replica (ADR-014, arch 08 section 5). Fan-out across replicas is PostgreSQL
 * {@code LISTEN/NOTIFY}: this replica listens, on one connection of its own, to {@code venue_{venueAccountId}} for every
 * account with a stream open here, and the worker sends each signal with {@code pg_notify} (no broker, no shared
 * cache). Delivery is at most once: a stream that missed a signal refetches on reconnect, and the Dashboard keeps its
 * 30-second poll.
 *
 * <p>Every {@link VenueStreamProperties#heartbeat()} each stream gets a {@code : heartbeat} comment, and the accounts with
 * a stream open are read once: a stream whose account is no longer ENABLED is closed (the backup to the immediate close
 * on {@code VenueAccountDisabled}, PO decision 10 Oct 2026). The listening connection and the heartbeat thread start
 * with the first stream, so a replica no Dashboard connects to holds no connection for this.
 */
@Component
@EnableConfigurationProperties(VenueStreamProperties.class)
public class VenueStreamHub implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(VenueStreamHub.class);

    private final DataSource dataSource;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final VenueStreamProperties properties;
    private final ConcurrentHashMap<UUID, Set<SseEmitter>> streams = new ConcurrentHashMap<>();
    /** The channels LISTENed to on the current connection (written by the listener thread only). */
    private final Set<String> listening = ConcurrentHashMap.newKeySet();
    private final Object startLock = new Object();
    private volatile boolean running;
    private Thread listener;
    private ScheduledExecutorService heartbeats;

    VenueStreamHub(DataSource appDataSource, ObjectMapper json, VenueStreamProperties properties) {
        this.dataSource = appDataSource;
        this.jdbc = JdbcClient.create(appDataSource);
        this.json = json;
        this.properties = properties;
    }

    /**
     * Opens a stream for the venue account (already authenticated and ENABLED: the per-request active check, arch 10
     * section 2.3). It closes after {@link VenueStreamProperties#maxDuration()}, when the account is disabled, or when
     * this replica stops; the Dashboard then reconnects and refetches.
     */
    public SseEmitter open(UUID venueAccountId) {
        SseEmitter emitter = new SseEmitter(properties.maxDuration().toMillis());
        streams.compute(venueAccountId, (account, open) -> {
            Set<SseEmitter> set = open != null ? open : ConcurrentHashMap.newKeySet();
            set.add(emitter);
            return set;
        });
        emitter.onCompletion(() -> forget(venueAccountId, emitter));
        emitter.onError(failure -> forget(venueAccountId, emitter));
        emitter.onTimeout(emitter::complete);
        startOnce();
        // Sends the headers now, so the Dashboard knows the stream is open before the first signal.
        send(venueAccountId, emitter, SseEmitter.event().comment(" open"));
        return emitter;
    }

    /** How many streams this replica has open for the account (tests and diagnostics). */
    public int openStreams(UUID venueAccountId) {
        Set<SseEmitter> open = streams.get(venueAccountId);
        return open == null ? 0 : open.size();
    }

    /** Whether this replica already hears the account's signals (tests and diagnostics). */
    public boolean listensFor(UUID venueAccountId) {
        return listening.contains(VenueStreamChannel.channel(venueAccountId));
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        streams.keySet().forEach(this::closeAll);
        synchronized (startLock) {
            if (heartbeats != null) {
                heartbeats.shutdownNow();
            }
            if (listener != null) {
                try {
                    listener.join(properties.listenPoll().multipliedBy(4).toMillis() + 1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            heartbeats = null;
            listener = null;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void startOnce() {
        synchronized (startLock) {
            if (listener != null || !running) {
                return;
            }
            listener = Thread.ofPlatform().name("e7gz-venue-stream-listen").daemon(true).start(this::listen);
            heartbeats = Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().name("e7gz-venue-stream-heartbeat").daemon(true).factory());
            long period = properties.heartbeat().toMillis();
            heartbeats.scheduleAtFixedRate(this::beat, period, period, TimeUnit.MILLISECONDS);
            log.info("Venue live channel started: heartbeat {}", properties.heartbeat());
        }
    }

    // ---- LISTEN / NOTIFY ----------------------------------------------------------------------------------------

    /** One listening connection; LISTEN / UNLISTEN follow the open streams. Reconnects after a lost connection. */
    private void listen() {
        while (running) {
            try (Connection connection = dataSource.getConnection()) {
                listening.clear();
                try {
                    PGConnection notifications = connection.unwrap(PGConnection.class);
                    int poll = (int) properties.listenPoll().toMillis();
                    while (running) {
                        follow(connection);
                        PGNotification[] received = notifications.getNotifications(poll);
                        if (received != null) {
                            for (PGNotification notification : received) {
                                deliver(notification.getName(), notification.getParameter());
                            }
                        }
                    }
                } finally {
                    listening.clear();
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("UNLISTEN *");             // the connection goes back to the pool
                    }
                }
            } catch (SQLException e) {
                if (running) {
                    log.warn("Venue live channel connection lost; streams get no signal until it is back", e);
                    pause();
                }
            }
        }
    }

    /** LISTEN to every account with a stream open here and to no other (the channel name is a quoted identifier). */
    private void follow(Connection connection) throws SQLException {
        Set<String> wanted = new HashSet<>();
        streams.keySet().forEach(account -> wanted.add(VenueStreamChannel.channel(account)));
        try (Statement statement = connection.createStatement()) {
            for (String channel : wanted) {
                if (!listening.contains(channel)) {
                    statement.execute("LISTEN \"" + channel + "\"");
                    listening.add(channel);
                }
            }
            for (String channel : List.copyOf(listening)) {
                if (!wanted.contains(channel)) {
                    statement.execute("UNLISTEN \"" + channel + "\"");
                    listening.remove(channel);
                }
            }
        }
    }

    private void deliver(String channel, String payload) {
        Optional<UUID> account = VenueStreamChannel.account(channel);
        if (account.isEmpty()) {
            return;
        }
        Optional<VenueStreamChannel.Message> message = VenueStreamChannel.read(payload, json);
        switch (message.orElse(null)) {
            case VenueStreamChannel.Message.Close ignored -> closeAll(account.get());
            case VenueStreamChannel.Message.Change change -> {
                Set<SseEmitter> open = streams.get(account.get());
                if (open != null) {
                    for (SseEmitter emitter : List.copyOf(open)) {
                        send(account.get(), emitter, SseEmitter.event().name("change")
                                .data(change.signal(), MediaType.APPLICATION_JSON));
                    }
                }
            }
            case null -> log.warn("Venue live channel ignored a malformed message on {}", channel);
        }
    }

    // ---- heartbeat and the disabled-account backup --------------------------------------------------------------

    private void beat() {
        try {
            Set<UUID> open = Set.copyOf(streams.keySet());
            if (open.isEmpty()) {
                return;
            }
            Set<UUID> enabled = Set.copyOf(enabledAmong(open));
            for (UUID account : open) {
                if (!enabled.contains(account)) {
                    log.info("Venue account {} is no longer ENABLED: its streams close (FR-VEN-08)", account);
                    closeAll(account);
                    continue;
                }
                Set<SseEmitter> emitters = streams.get(account);
                if (emitters != null) {
                    for (SseEmitter emitter : List.copyOf(emitters)) {
                        send(account, emitter, SseEmitter.event().comment(" heartbeat"));
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("Venue live channel heartbeat failed", e);
        }
    }

    /** One read for every account with a stream open here; on a database error none is closed. */
    private List<UUID> enabledAmong(Set<UUID> accounts) {
        try {
            return jdbc.sql("SELECT id FROM iam.venue_account WHERE id IN (:ids) AND account_status = 'ENABLED'")
                    .param("ids", accounts).query(UUID.class).list();
        } catch (RuntimeException e) {
            log.warn("Venue live channel could not read account status; streams stay open", e);
            return List.copyOf(accounts);
        }
    }

    // ---- streams ------------------------------------------------------------------------------------------------

    private void send(UUID account, SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException gone) {
            forget(account, emitter);                                // the Dashboard went away
            emitter.completeWithError(gone);
        }
    }

    private void closeAll(UUID account) {
        Set<SseEmitter> open = streams.remove(account);
        if (open != null) {
            open.forEach(SseEmitter::complete);
        }
    }

    private void forget(UUID account, SseEmitter emitter) {
        streams.computeIfPresent(account, (key, open) -> {
            open.remove(emitter);
            return open.isEmpty() ? null : open;
        });
    }

    private void pause() {
        long deadline = System.nanoTime() + properties.reconnectWait().toNanos();
        try {
            while (running && System.nanoTime() < deadline) {
                Thread.sleep(properties.listenPoll());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
