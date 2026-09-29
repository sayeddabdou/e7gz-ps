package ps.e7gz.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ps.e7gz.audit.AuditAssertions.assertExactlyOneAuditRow;
import static ps.e7gz.audit.AuditAssertions.auditRows;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import ps.e7gz.audit.AuditAssertions.AuditRow;
import ps.e7gz.audit.api.AuditActor;
import ps.e7gz.audit.api.AuditRecord;
import ps.e7gz.audit.api.AuditWriter;
import ps.e7gz.audit.api.OwnerPasswordCheck;
import ps.e7gz.common.command.Command;
import ps.e7gz.common.command.CommandContext;
import ps.e7gz.common.command.CommandRunner;
import ps.e7gz.common.command.CommandScope;
import ps.e7gz.common.command.EligibilityCategory;
import ps.e7gz.common.command.LockSet;
import ps.e7gz.common.correlation.CorrelationId;
import ps.e7gz.support.IntegrationTest;

/** E7-17 - the audit writer inside real commands, against the migrated database. */
class AuditWriterIntegrationTest extends IntegrationTest {

    @Autowired
    CommandRunner runner;

    @Autowired
    AuditWriter auditWriter;

    @Autowired
    DataSource appDataSource;

    record TestCommand<R>(Function<CommandContext, R> body) implements Command<R> {

        @Override
        public EligibilityCategory eligibilityCategory() {
            return EligibilityCategory.C_INTERNAL_VENUE_OPERATION;
        }

        @Override
        public CommandScope scope() {
            return CommandScope.shared(LockSet.empty());
        }

        @Override
        public R execute(CommandContext context) {
            return body.apply(context);
        }
    }

    private <R> R run(Function<CommandContext, R> body) {
        return runner.run(new TestCommand<>(body));
    }

    private static AuditRecord.Builder record(TestAuditAction action, AuditActor actor, UUID subject, UUID correlation) {
        return AuditRecord.builder(action, actor, subject, correlation);
    }

    // ---- same transaction ---------------------------------------------------------------------------------------

    @Test
    void aSuccessfulCommandLeavesExactlyOneAuditRowAtTheCommandInstant() {
        UUID subject = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();

        record Written(UUID id, Instant commandNow) {
        }
        Written written = run(ctx -> new Written(
                auditWriter.write(ctx, record(TestAuditAction.E7_17_TEST_NO_PAYLOAD, AuditActor.system(), subject,
                        correlation).build()),
                ctx.commandNow()));

        AuditRow row = assertExactlyOneAuditRow(appDataSource, TestAuditAction.E7_17_TEST_NO_PAYLOAD, subject, correlation);
        assertThat(row.id()).isEqualTo(written.id());
        assertThat(row.occurredAt()).as("occurred_at = CommandContext.commandNow()").isEqualTo(written.commandNow());
        assertThat(row.actorType()).isEqualTo("SYSTEM");
        assertThat(row.actorId()).isNull();
        assertThat(row.subjectType()).isEqualTo("BOOKING");
        assertThat(row.reason()).isNull();
        assertThat(row.ownerPasswordUsed()).isNull();
        assertThat(row.ownerPasswordOutcome()).isNull();
        assertThat(row.payload()).isNull();
    }

    @Test
    void throwingAfterTheAuditWriteRollsTheAuditRowBack() {
        UUID subject = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        int[] visibleInsideTheCommand = {-1};

        assertThatThrownBy(() -> run(ctx -> {
            auditWriter.write(ctx, record(TestAuditAction.E7_17_TEST_NO_PAYLOAD, AuditActor.system(), subject,
                    correlation).build());
            visibleInsideTheCommand[0] = ctx.jdbc()
                    .sql("SELECT count(*) FROM audit.audit_event WHERE subject_id = :s AND correlation_id = :c")
                    .param("s", subject).param("c", correlation).query(Integer.class).single();
            throw new IllegalStateException("command failed after the audit write");
        })).isInstanceOf(IllegalStateException.class).hasMessage("command failed after the audit write");

        assertThat(visibleInsideTheCommand[0]).as("the row was written inside the command transaction").isEqualTo(1);
        assertThat(auditRows(appDataSource, TestAuditAction.E7_17_TEST_NO_PAYLOAD, subject, correlation))
                .as("rolled back with the command").isEmpty();
    }

    // ---- correlation ----------------------------------------------------------------------------------------------

    @Test
    void theCorrelationIdOfTheScopeIsStoredExactly() {
        UUID known = UUID.fromString("7e57c0de-0000-4000-8000-00000000e717");
        UUID subject = UUID.randomUUID();

        try (var scope = CorrelationId.open(known)) {
            UUID correlation = CorrelationId.require(); // the command edge reads it once and passes it explicitly
            run(ctx -> auditWriter.write(ctx, record(TestAuditAction.E7_17_TEST_NO_PAYLOAD, AuditActor.system(),
                    subject, correlation).build()));
        }

        assertThat(assertExactlyOneAuditRow(appDataSource, TestAuditAction.E7_17_TEST_NO_PAYLOAD, subject, null)
                .correlationId()).isEqualTo(known);
    }

    // ---- actors (real rows, so audit.guard_audit_insert checks provenance) ---------------------------------------

    @Test
    void realCustomerVenueAndAdminActorsAreStoredWithTheirIds() {
        UUID user = AuditFixtures.user();
        UUID venueAccount = AuditFixtures.venueAccount();
        UUID admin = AuditFixtures.admin();
        UUID correlation = UUID.randomUUID();

        for (AuditActor actor : List.of(AuditActor.customer(user), AuditActor.venue(venueAccount), AuditActor.admin(admin))) {
            UUID subject = UUID.randomUUID();
            run(ctx -> auditWriter.write(ctx, record(TestAuditAction.E7_17_TEST_NO_PAYLOAD, actor, subject, correlation)
                    .build()));

            AuditRow row = assertExactlyOneAuditRow(appDataSource, TestAuditAction.E7_17_TEST_NO_PAYLOAD, subject,
                    correlation);
            assertThat(row.actorType()).isEqualTo(actor.actorType().name());
            assertThat(row.actorId()).isEqualTo(actor.actorId());
        }
    }

    @Test
    void theDatabaseRefusesAnActorIdThatIsNotARealAccount() {
        UUID subject = UUID.randomUUID();

        assertThatThrownBy(() -> run(ctx -> auditWriter.write(ctx, record(TestAuditAction.E7_17_TEST_NO_PAYLOAD,
                AuditActor.admin(UUID.randomUUID()), subject, UUID.randomUUID()).build())))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("ADMIN actor must be an active Admin account");
        assertThatThrownBy(() -> run(ctx -> auditWriter.write(ctx, record(TestAuditAction.E7_17_TEST_NO_PAYLOAD,
                AuditActor.customer(UUID.randomUUID()), subject, UUID.randomUUID()).build())))
                .rootCause().hasMessageContaining("CUSTOMER actor must be a real User");
        assertThat(auditRows(appDataSource, TestAuditAction.E7_17_TEST_NO_PAYLOAD, subject, null)).isEmpty();
    }

    // ---- owner password, reason, payload -----------------------------------------------------------------------------

    @Test
    void ownerPasswordOutcomesAreStoredCoherently() {
        UUID venueAccount = AuditFixtures.venueAccount();
        UUID correlation = UUID.randomUUID();

        for (OwnerPasswordCheck check : List.of(OwnerPasswordCheck.succeeded(), OwnerPasswordCheck.failed())) {
            UUID subject = UUID.randomUUID();
            run(ctx -> auditWriter.write(ctx, record(TestAuditAction.E7_17_TEST_NO_PAYLOAD,
                    AuditActor.venue(venueAccount), subject, correlation).ownerPasswordCheck(check).build()));

            AuditRow row = assertExactlyOneAuditRow(appDataSource, TestAuditAction.E7_17_TEST_NO_PAYLOAD, subject,
                    correlation);
            assertThat(row.ownerPasswordUsed()).isTrue();
            assertThat(row.ownerPasswordOutcome()).isEqualTo(check.outcome().name());
        }
    }

    @Test
    void aSuppliedReasonIsStoredUnchanged() {
        UUID subject = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        String reason = "  Customer arrived, venue confirmed by phone.  ";

        run(ctx -> auditWriter.write(ctx, record(TestAuditAction.E7_17_TEST_REASON_REQUIRED, AuditActor.system(),
                subject, correlation).reason(reason).build()));

        assertThat(assertExactlyOneAuditRow(appDataSource, TestAuditAction.E7_17_TEST_REASON_REQUIRED, subject,
                correlation).reason()).isEqualTo(reason);
    }

    @Test
    void onlyAllowlistedPayloadFieldsAreStored() {
        UUID subject = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("startAt", Instant.parse("2026-10-01T18:00:00Z"));
        window.put("accessToken", "leak-1");
        window.put("note", "unknown");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("resourceName", "Room 1");
        payload.put("ownerPassword", "leak-2");
        payload.put("otp", "123456");
        payload.put("comment", "unknown");
        payload.put("window", window);
        payload.put("items", List.of(Map.of("id", "A", "apiKey", "leak-3")));

        run(ctx -> auditWriter.write(ctx, record(TestAuditAction.E7_17_TEST_RESOURCE_NOTE, AuditActor.system(),
                subject, correlation).payload(payload).build()));

        String stored = assertExactlyOneAuditRow(appDataSource, TestAuditAction.E7_17_TEST_RESOURCE_NOTE, subject,
                correlation).payload();
        assertThat(stored).isEqualTo(
                "{\"items\": [{\"id\": \"A\"}], \"window\": {\"startAt\": \"2026-10-01T18:00:00Z\"}, \"resourceName\": \"Room 1\"}");
        assertThat(stored).doesNotContain("leak", "123456", "unknown", "ownerPassword", "otp", "accessToken", "apiKey");
    }

    // ---- the test helper -----------------------------------------------------------------------------------------

    @Test
    void theHelperFailsForZeroAndForDuplicateRows() {
        UUID subject = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();

        assertThatThrownBy(() -> assertExactlyOneAuditRow(appDataSource, TestAuditAction.E7_17_TEST_NO_PAYLOAD, subject,
                correlation)).isInstanceOf(AssertionError.class).hasMessageContaining("found 0");

        run(ctx -> {
            for (int i = 0; i < 2; i++) {
                auditWriter.write(ctx, record(TestAuditAction.E7_17_TEST_NO_PAYLOAD, AuditActor.system(), subject,
                        correlation).build());
            }
            return null;
        });

        assertThatThrownBy(() -> assertExactlyOneAuditRow(appDataSource, TestAuditAction.E7_17_TEST_NO_PAYLOAD, subject,
                correlation)).isInstanceOf(AssertionError.class).hasMessageContaining("found 2");
        assertThatThrownBy(() -> assertExactlyOneAuditRow(appDataSource, TestAuditAction.E7_17_TEST_NO_PAYLOAD, subject,
                null)).as("without correlation filter").isInstanceOf(AssertionError.class);
        assertThat(auditRows(appDataSource, TestAuditAction.E7_17_TEST_REASON_REQUIRED, subject, correlation))
                .as("another action on the same subject does not match").isEmpty();
    }
}
