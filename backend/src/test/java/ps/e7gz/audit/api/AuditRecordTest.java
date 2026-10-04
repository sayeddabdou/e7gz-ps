package ps.e7gz.audit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import ps.e7gz.audit.TestAuditAction;
import ps.e7gz.common.codes.ActorType;
import ps.e7gz.common.codes.OwnerPasswordOutcome;

class AuditRecordTest {

    private final UUID subject = UUID.randomUUID();
    private final UUID correlation = UUID.randomUUID();

    // ---- actor -----------------------------------------------------------------------------------------------------

    @Test
    void theSystemActorHasNoId() {
        assertThat(AuditActor.system().actorType()).isEqualTo(ActorType.SYSTEM);
        assertThat(AuditActor.system().actorId()).isNull();
    }

    @Test
    void everyOtherActorRequiresAnId() {
        UUID id = UUID.randomUUID();
        assertThat(AuditActor.customer(id).actorType()).isEqualTo(ActorType.CUSTOMER);
        assertThat(AuditActor.venue(id).actorType()).isEqualTo(ActorType.VENUE);
        assertThat(AuditActor.admin(id).actorType()).isEqualTo(ActorType.ADMIN);
        assertThat(AuditActor.admin(id).actorId()).isEqualTo(id);

        assertThatThrownBy(() -> AuditActor.customer(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> AuditActor.venue(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> AuditActor.admin(null)).isInstanceOf(NullPointerException.class);
    }

    // ---- owner password ----------------------------------------------------------------------------------------

    @Test
    void anOwnerPasswordCheckAlwaysHasAnOutcome() {
        assertThat(OwnerPasswordCheck.succeeded().outcome()).isEqualTo(OwnerPasswordOutcome.SUCCESS);
        assertThat(OwnerPasswordCheck.failed().outcome()).isEqualTo(OwnerPasswordOutcome.FAILURE);
        assertThatThrownBy(() -> new OwnerPasswordCheck(null)).isInstanceOf(NullPointerException.class);
        assertThat(AuditRecord.builder(TestAuditAction.E7_17_TEST_NO_PAYLOAD, AuditActor.system(), subject, correlation)
                .build().ownerPasswordCheck()).as("no check: used and outcome both stay NULL").isEmpty();
    }

    // ---- required fields, reason ----------------------------------------------------------------------------------

    @Test
    void subjectAndCorrelationAreRequired() {
        assertThatThrownBy(() -> AuditRecord.builder(TestAuditAction.E7_17_TEST_NO_PAYLOAD, AuditActor.system(), null,
                correlation)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> AuditRecord.builder(TestAuditAction.E7_17_TEST_NO_PAYLOAD, AuditActor.system(), subject,
                null)).isInstanceOf(NullPointerException.class).hasMessageContaining("CorrelationId.require()");
    }

    @Test
    void aSuppliedReasonIsKeptAsIsAndABlankOneIsNoReason() {
        assertThat(AuditRecord.builder(TestAuditAction.E7_17_TEST_NO_PAYLOAD, AuditActor.system(), subject, correlation)
                .reason(" as given ").build().reason()).contains(" as given ");
        assertThat(AuditRecord.builder(TestAuditAction.E7_17_TEST_NO_PAYLOAD, AuditActor.system(), subject, correlation)
                .reason("   ").build().reason()).isEmpty();
    }

    @Test
    void anActionThatRequiresAReasonIsRefusedWithoutOne() {
        assertThatThrownBy(() -> AuditRecord.builder(TestAuditAction.E7_17_TEST_REASON_REQUIRED, AuditActor.system(),
                subject, correlation).reason(" ").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires a reason");
    }

    // ---- action definitions ---------------------------------------------------------------------------------------

    enum BadSubject implements AuditAction {
        E7_17_BAD;

        @Override
        public String subjectType() {
            return "booking with spaces";
        }

        @Override
        public AuditPayloadAllowlist payloadAllowlist() {
            return AuditPayloadAllowlist.none();
        }
    }

    @Test
    void theSubjectTypeMustBeAnUpperCaseIdentifier() {
        assertThatThrownBy(() -> AuditRecord.builder(BadSubject.E7_17_BAD, AuditActor.system(), subject, correlation)
                .build()).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("subject type");
    }

    @Test
    void aStateChangeNeedsANewStateAndBothFitTheDatabaseCheck() {
        AuditRecord created = AuditRecord.builder(TestAuditAction.E7_17_TEST_NO_PAYLOAD, AuditActor.system(), subject,
                correlation).stateChange(null, "ACTIVE").build();
        assertThat(created.previousState()).isEmpty();
        assertThat(created.newState()).contains("ACTIVE");

        AuditRecord.Builder builder = AuditRecord.builder(TestAuditAction.E7_17_TEST_NO_PAYLOAD, AuditActor.system(),
                subject, correlation);
        assertThatThrownBy(() -> builder.stateChange("ACTIVE", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> builder.stateChange(" ", "ACTIVE")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.stateChange("ACTIVE", "X".repeat(81)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anAllowlistCannotNameACredentialOrSecretAtAnyDepth() {
        for (String path : new String[] {"password", "ownerPassword", "window.token", "items[].apiKey",
                "session.cookie", "otp", "privateKey", "Authorization", "clientSecret", "pin"}) {
            assertThatThrownBy(() -> AuditPayloadAllowlist.of(path))
                    .as(path).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("secret");
        }
        assertThatThrownBy(() -> AuditPayloadAllowlist.of("window..startAt")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditPayloadAllowlist.of("a", "a")).isInstanceOf(IllegalArgumentException.class);
        assertThat(AuditPayloadAllowlist.of("window.startAt", "bookingIds[]", "items[].id").paths()).hasSize(3);
    }
}
