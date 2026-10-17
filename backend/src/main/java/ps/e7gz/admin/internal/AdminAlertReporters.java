package ps.e7gz.admin.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ps.e7gz.admin.api.AdminAlertKind;
import ps.e7gz.admin.api.AdminAlertSeverity;
import ps.e7gz.admin.api.NewAdminAlert;
import ps.e7gz.common.codes.AuthSubjectType;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.identity.api.SecurityAlerts;
import ps.e7gz.inventory.sweep.api.InvariantViolation;
import ps.e7gz.inventory.sweep.api.InvariantViolationReporter;
import ps.e7gz.messaging.api.DeadLetterAlerts;
import ps.e7gz.messaging.api.OutboxEvent;

/**
 * The Platform Administration side of the alert ports other modules declare (E7-56): the nightly invariant sweep
 * (PO decision 4 Oct 2026), the outbox dead letter (PO decision 12 Oct 2026) and refresh-token reuse (Sprint 0 review
 * S-9). Each port lives in its caller's {@code api} so no module depends on Administration in a cycle.
 */
@Component
class AdminAlertReporters implements InvariantViolationReporter, DeadLetterAlerts, SecurityAlerts {

    private static final Logger log = LoggerFactory.getLogger(AdminAlertReporters.class);

    private final JdbcAdminAlerts alerts;

    AdminAlertReporters(JdbcAdminAlerts alerts) {
        this.alerts = alerts;
    }

    /**
     * INVARIANT_VIOLATION, SEV_1, about the violating row; payload = the invariant code plus every fact. Called after
     * the sweep's read-only snapshot has closed, so each alert commits on its own, with the sweep's correlation ID
     * (opened by the E7-40 worker - never a new one per violation). Raised once while an identical alert is open, so a
     * violation still there tomorrow night is not repeated. Never throws: one failing alert is logged and the sweep
     * reports the next violation.
     */
    @Override
    public void report(InvariantViolation violation) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>(violation.facts());
            payload.put("invariant", violation.invariant());
            alerts.raiseOnceAlone(new NewAdminAlert(AdminAlertKind.INVARIANT_VIOLATION, AdminAlertSeverity.SEV_1,
                    violation.subjectType(), violation.subjectId(), payload, CorrelationId.require()));
        } catch (RuntimeException failure) {
            log.error("Invariant violation {} on {} {} raised no admin alert", violation.invariant(),
                    violation.subjectType(), violation.subjectId(), failure);
        }
    }

    /**
     * OUTBOX_DEAD_LETTER, SEV_2, about the event's aggregate (the event id is a number, not a subject id), with the
     * event id, type, aggregate, last error and correlation ID (arch 12 section 6.3).
     */
    @Override
    public void deadLettered(JdbcClient sameTransaction, OutboxEvent event, String lastError) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", event.id());
        payload.put("eventType", event.eventType());
        payload.put("aggregateType", event.aggregateType());
        payload.put("aggregateId", event.aggregateId().toString());
        payload.put("lastError", lastError == null ? "" : lastError);
        payload.put("correlationId", event.correlationId().toString());
        alerts.raise(sameTransaction, new NewAdminAlert(AdminAlertKind.OUTBOX_DEAD_LETTER, AdminAlertSeverity.SEV_2,
                event.aggregateType(), event.aggregateId(), payload, event.correlationId()));
    }

    /**
     * REFRESH_TOKEN_REUSE, SEV_2 (PO decision 15 Oct 2026), about the account whose chain was revoked; the payload is
     * the audience only - never the token. In a transaction of its own: the revocation is already committed.
     */
    @Override
    public void refreshTokenReused(AuthSubjectType audience, UUID subjectId) {
        try {
            alerts.raiseAlone(new NewAdminAlert(AdminAlertKind.REFRESH_TOKEN_REUSE, AdminAlertSeverity.SEV_2,
                    subjectType(audience), subjectId, Map.of("audience", audience.name()), CorrelationId.require()));
        } catch (RuntimeException failure) {
            log.error("Refresh token reuse on {} {} raised no admin alert", audience, subjectId, failure);
        }
    }

    /**
     * ACCOUNT_LOGIN_LIMIT_REACHED, SEV_3 (PO decision 15 Oct 2026, E7-57), about the paused account; the payload is the
     * audience only - never the identifier, the password or the IP. In a transaction of its own: the pause is committed.
     */
    @Override
    public void accountLoginLimitReached(AuthSubjectType audience, UUID accountId) {
        try {
            alerts.raiseAlone(new NewAdminAlert(AdminAlertKind.ACCOUNT_LOGIN_LIMIT_REACHED, AdminAlertSeverity.SEV_3,
                    subjectType(audience), accountId, Map.of("audience", audience.name()), CorrelationId.require()));
        } catch (RuntimeException failure) {
            log.error("Login limit on {} {} raised no admin alert", audience, accountId, failure);
        }
    }

    /** The account table each audience lives in, as audit subject types name them. */
    static String subjectType(AuthSubjectType audience) {
        return switch (audience) {
            case CUSTOMER -> "USER";
            case VENUE -> "VENUE_ACCOUNT";
            case ADMIN -> "ADMIN";
        };
    }
}
